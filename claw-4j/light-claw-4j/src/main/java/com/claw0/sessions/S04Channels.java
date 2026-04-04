package com.claw0.sessions;

/**
 * Section 04: Channels -- "Same brain, many mouths"
 *
 * Channel encapsulates platform differences, so the agent loop only sees
 * unified InboundMessage. Adding a new platform = implement receive() + send();
 * the loop doesn't change.
 *
 *   Telegram ----.                          .---- sendMessage API
 *   Feishu -------+-- InboundMessage ---+---- im/v1/messages
 *   CLI (stdin) --'    Agent Loop        '---- print(stdout)
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S04Channels"
 */

// region Common Imports
import com.claw0.common.AnsiColors;
import com.claw0.common.Config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.core.JsonValue;
// endregion

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Collectors;

public class S04Channels {

    // region Configuration
    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant connected to multiple messaging channels.\n"
            + "You can save and search notes using the provided tools.\n"
            + "When responding, be concise and helpful.";

    static final int MAX_TOOL_OUTPUT = 50_000;
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));
    static final Path WORKSPACE_DIR = WORKDIR.resolve("workspace");
    static final Path STATE_DIR = WORKSPACE_DIR.resolve(".state");
    static final Path MEMORY_FILE = WORKSPACE_DIR.resolve("MEMORY.md");

    static final AnthropicClient client = AnthropicOkHttpClient.builder()
            .fromEnv()
            .build();
    // endregion

    // ================================================================
    // Data Structures
    // ================================================================

    /** All channels normalize to this. The agent loop only sees InboundMessage. */
    record InboundMessage(
            String text, String senderId, String channel,
            String accountId, String peerId, boolean isGroup,
            List<Map<String, Object>> media, Map<String, Object> raw
    ) {
        InboundMessage(String text, String senderId, String channel,
                       String accountId, String peerId) {
            this(text, senderId, channel, accountId, peerId, false, List.of(), Map.of());
        }
    }

    /** Per-bot configuration. Same channel type can run multiple bots. */
    record ChannelAccount(
            String channel, String accountId, String token,
            Map<String, Object> config
    ) {
        ChannelAccount(String channel, String accountId, String token) {
            this(channel, accountId, token, Map.of());
        }
    }

    // ================================================================
    // Session Key
    // ================================================================

    static String buildSessionKey(String channel, String accountId, String peerId) {
        return "agent:main:direct:" + channel + ":" + peerId;
    }

    // ================================================================
    // Channel Interface
    // ================================================================

    interface Channel {
        String name();
        Optional<InboundMessage> receive();
        boolean send(String to, String text);
        default void close() {}
    }

    // ================================================================
    // CLIChannel
    // ================================================================

    static class CLIChannel implements Channel {
        private final String accountId = "cli-local";
        private final Scanner scanner = new Scanner(System.in);

        @Override public String name() { return "cli"; }

        @Override
        public Optional<InboundMessage> receive() {
            try {
                System.out.print(AnsiColors.coloredPrompt());
                String text = scanner.nextLine().trim();
                if (text.isEmpty()) return Optional.empty();
                return Optional.of(new InboundMessage(
                        text, "cli-user", "cli", accountId, "cli-user"));
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        @Override
        public boolean send(String to, String text) {
            AnsiColors.printAssistant(text);
            return true;
        }
    }

    // ================================================================
    // Offset Persistence
    // ================================================================

    static void saveOffset(Path path, int offset) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, String.valueOf(offset));
        } catch (IOException e) { /* ignore */ }
    }

    static int loadOffset(Path path) {
        try { return Integer.parseInt(Files.readString(path).trim()); }
        catch (Exception e) { return 0; }
    }

    // ================================================================
    // TelegramChannel -- Bot API long polling
    // ================================================================

    static class TelegramChannel implements Channel {
        static final int MAX_MSG_LEN = 4096;
        private final String accountId;
        private final String baseUrl;
        private final HttpClient http;
        private final Set<String> allowedChats;
        private final Path offsetPath;
        private int offset;
        private final Set<Integer> seen = ConcurrentHashMap.newKeySet();

        TelegramChannel(ChannelAccount account) {
            this.accountId = account.accountId();
            this.baseUrl = "https://api.telegram.org/bot" + account.token();
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(35))
                    .build();
            String raw = (String) account.config().getOrDefault("allowed_chats", "");
            this.allowedChats = raw.isBlank() ? Set.of()
                    : Set.of(raw.split(","));
            this.offsetPath = STATE_DIR.resolve("telegram/offset-" + accountId + ".txt");
            this.offset = loadOffset(offsetPath);
        }

        @SuppressWarnings("unchecked")
        Object apiCall(String method, Map<String, Object> params) {
            Map<String, Object> filtered = new LinkedHashMap<>();
            params.forEach((k, v) -> { if (v != null) filtered.put(k, v); });
            try {
                String body = com.claw0.common.JsonUtils.toJson(filtered);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/" + method))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(35))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> data = com.claw0.common.JsonUtils.toMap(resp.body());
                if (!Boolean.TRUE.equals(data.get("ok"))) {
                    System.out.println("  " + AnsiColors.RED + "[telegram] " + method
                            + ": " + data.getOrDefault("description", "?") + AnsiColors.RESET);
                    return null;
                }
                return data.get("result");
            } catch (Exception e) {
                System.out.println("  " + AnsiColors.RED + "[telegram] " + method
                        + ": " + e.getMessage() + AnsiColors.RESET);
                return null;
            }
        }

        void sendTyping(String chatId) {
            apiCall("sendChatAction", Map.of("chat_id", chatId, "action", "typing"));
        }

        @SuppressWarnings("unchecked")
        List<InboundMessage> poll() {
            var result = apiCall("getUpdates",
                    Map.of("offset", offset, "timeout", 30));
            if (!(result instanceof List<?> list)) return new ArrayList<>();

            List<InboundMessage> messages = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> updateRaw)) continue;
                Map<String, Object> update = (Map<String, Object>) updateRaw;
                int uid = ((Number) update.getOrDefault("update_id", 0)).intValue();
                if (uid >= offset) {
                    offset = uid + 1;
                    saveOffset(offsetPath, offset);
                }
                if (!seen.add(uid)) continue;
                if (seen.size() > 5000) seen.clear();

                Map<String, Object> msg = (Map<String, Object>) update.get("message");
                if (msg == null) continue;
                InboundMessage inbound = parseMessage(msg, update);
                if (inbound == null) continue;
                if (!allowedChats.isEmpty() && !allowedChats.contains(inbound.peerId())) continue;
                messages.add(inbound);
            }
            return messages;
        }

        @SuppressWarnings("unchecked")
        InboundMessage parseMessage(Map<String, Object> msg, Map<String, Object> rawUpdate) {
            Map<String, Object> chat = (Map<String, Object>) msg.getOrDefault("chat", Map.of());
            String chatType = (String) chat.getOrDefault("type", "");
            String chatId = String.valueOf(chat.getOrDefault("id", ""));
            Map<String, Object> from = (Map<String, Object>) msg.getOrDefault("from", Map.of());
            String userId = String.valueOf(from.getOrDefault("id", ""));
            String text = (String) msg.getOrDefault("text", "");
            if (text == null) text = (String) msg.getOrDefault("caption", "");
            if (text == null || text.isBlank()) return null;

            boolean isGroup = "group".equals(chatType) || "supergroup".equals(chatType);
            String peerId = "private".equals(chatType) ? userId : chatId;

            return new InboundMessage(text, userId, "telegram",
                    accountId, peerId, isGroup, List.of(), rawUpdate);
        }

        @Override public String name() { return "telegram"; }

        @Override
        public Optional<InboundMessage> receive() {
            List<InboundMessage> msgs = poll();
            return msgs.isEmpty() ? Optional.empty() : Optional.of(msgs.get(0));
        }

        @Override
        public boolean send(String to, String text) {
            String chatId = to;
            Integer threadId = null;
            if (to.contains(":topic:")) {
                String[] parts = to.split(":topic:");
                chatId = parts[0];
                if (parts.length > 1) threadId = Integer.parseInt(parts[1]);
            }
            boolean ok = true;
            for (String chunk : chunkMessage(text)) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("chat_id", chatId);
                params.put("text", chunk);
                if (threadId != null) params.put("message_thread_id", threadId);
                if (apiCall("sendMessage", params) == null) ok = false;
            }
            return ok;
        }

        List<String> chunkMessage(String text) {
            if (text.length() <= MAX_MSG_LEN) return List.of(text);
            List<String> chunks = new ArrayList<>();
            while (!text.isEmpty()) {
                if (text.length() <= MAX_MSG_LEN) { chunks.add(text); break; }
                int cut = text.lastIndexOf('\n', MAX_MSG_LEN);
                if (cut <= 0) cut = MAX_MSG_LEN;
                chunks.add(text.substring(0, cut));
                text = text.substring(cut).stripLeading();
            }
            return chunks;
        }
    }

    // ================================================================
    // FeishuChannel -- webhook-based (Feishu/Lark)
    // ================================================================

    static class FeishuChannel implements Channel {
        private final String accountId;
        private final String appId;
        private final String appSecret;
        private final String botOpenId;
        private final String apiBase;
        private final HttpClient http;
        private volatile String tenantToken = "";
        private volatile double tokenExpiresAt = 0;

        FeishuChannel(ChannelAccount account) {
            this.accountId = account.accountId();
            Map<String, Object> cfg = account.config();
            this.appId = (String) cfg.getOrDefault("app_id", "");
            this.appSecret = (String) cfg.getOrDefault("app_secret", "");
            this.botOpenId = (String) cfg.getOrDefault("bot_open_id", "");
            boolean isLark = "true".equals(String.valueOf(cfg.getOrDefault("is_lark", "false")));
            this.apiBase = isLark ? "https://open.larksuite.com/open-apis"
                    : "https://open.feishu.cn/open-apis";
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
        }

        String refreshToken() {
            if (!tenantToken.isEmpty() && System.currentTimeMillis() / 1000.0 < tokenExpiresAt) {
                return tenantToken;
            }
            try {
                String body = com.claw0.common.JsonUtils.toJson(
                        Map.of("app_id", appId, "app_secret", appSecret));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(apiBase + "/auth/v3/tenant_access_token/internal"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> data = com.claw0.common.JsonUtils.toMap(resp.body());
                if (!Number.class.cast(data.getOrDefault("code", -1)).equals(0)) {
                    System.out.println("  " + AnsiColors.RED + "[feishu] Token error: "
                            + data.getOrDefault("msg", "?") + AnsiColors.RESET);
                    return "";
                }
                tenantToken = (String) data.getOrDefault("tenant_access_token", "");
                int expire = ((Number) data.getOrDefault("expire", 7200)).intValue();
                tokenExpiresAt = System.currentTimeMillis() / 1000.0 + expire - 300;
                return tenantToken;
            } catch (Exception e) {
                System.out.println("  " + AnsiColors.RED + "[feishu] Token error: "
                        + e.getMessage() + AnsiColors.RESET);
                return "";
            }
        }

        /** Parse Feishu event callback. */
        @SuppressWarnings("unchecked")
        Optional<InboundMessage> parseEvent(Map<String, Object> payload) {
            if (payload.containsKey("challenge")) return Optional.empty();
            Map<String, Object> event = (Map<String, Object>) payload.getOrDefault("event", Map.of());
            Map<String, Object> message = (Map<String, Object>) event.getOrDefault("message", Map.of());
            Map<String, Object> sender = (Map<String, Object>)
                    ((Map<String, Object>) event.getOrDefault("sender", Map.of()))
                            .getOrDefault("sender_id", Map.of());
            String userId = (String) sender.getOrDefault("open_id",
                    sender.getOrDefault("user_id", ""));
            String chatId = (String) message.getOrDefault("chat_id", "");
            String chatType = (String) message.getOrDefault("chat_type", "");
            boolean isGroup = "group".equals(chatType);

            // Parse content
            String msgType = (String) message.getOrDefault("msg_type", "text");
            Object rawContent = message.getOrDefault("content", "{}");
            Map<String, Object> content;
            try {
                content = rawContent instanceof String s
                        ? com.claw0.common.JsonUtils.toMap(s)
                        : (Map<String, Object>) rawContent;
            } catch (Exception e) { return Optional.empty(); }

            String text = "";
            if ("text".equals(msgType)) {
                text = (String) content.getOrDefault("text", "");
            }
            if (text.isEmpty()) return Optional.empty();

            String peerId = "p2p".equals(chatType) ? userId : chatId;
            return Optional.of(new InboundMessage(text, userId, "feishu",
                    accountId, peerId, isGroup, List.of(), payload));
        }

        @Override public String name() { return "feishu"; }

        @Override public Optional<InboundMessage> receive() { return Optional.empty(); }

        @Override
        public boolean send(String to, String text) {
            String token = refreshToken();
            if (token.isEmpty()) return false;
            try {
                Map<String, Object> body = Map.of(
                        "receive_id", to,
                        "msg_type", "text",
                        "content", com.claw0.common.JsonUtils.toJson(Map.of("text", text)));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(apiBase + "/im/v1/messages?receive_id_type=chat_id"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                com.claw0.common.JsonUtils.toJson(body)))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> data = com.claw0.common.JsonUtils.toMap(resp.body());
                if (!Number.class.cast(data.getOrDefault("code", -1)).equals(0)) {
                    System.out.println("  " + AnsiColors.RED + "[feishu] Send: "
                            + data.getOrDefault("msg", "?") + AnsiColors.RESET);
                    return false;
                }
                return true;
            } catch (Exception e) {
                System.out.println("  " + AnsiColors.RED + "[feishu] Send: "
                        + e.getMessage() + AnsiColors.RESET);
                return false;
            }
        }
    }

    // ================================================================
    // Memory Tools
    // ================================================================

    static String toolMemoryWrite(Map<String, Object> input) {
        String content = (String) input.get("content");
        AnsiColors.printTool("memory_write", content.length() + " chars");
        try {
            Files.createDirectories(MEMORY_FILE.getParent());
            // Append to MEMORY.md (persistent)
            Files.writeString(MEMORY_FILE, "\n- " + content + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            // Append to daily JSONL
            Path dailyDir = WORKSPACE_DIR.resolve(".memory");
            Files.createDirectories(dailyDir);
            String today = java.time.LocalDate.now().toString();
            Map<String, Object> record = Map.of(
                    "content", content, "ts", Instant.now().toString());
            com.claw0.common.JsonUtils.appendJsonl(dailyDir.resolve(today + ".jsonl"), record);
            return "Written to memory: " + content.substring(0, Math.min(80, content.length())) + "...";
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    static String toolMemorySearch(Map<String, Object> input) {
        String query = (String) input.get("query");
        AnsiColors.printTool("memory_search", query);
        if (!Files.exists(MEMORY_FILE)) return "Memory file is empty.";
        try {
            String lower = query.toLowerCase();
            List<String> matches = Files.readAllLines(MEMORY_FILE).stream()
                    .filter(line -> line.toLowerCase().contains(lower))
                    .limit(20)
                    .toList();
            return matches.isEmpty()
                    ? "No matches for '" + query + "'."
                    : String.join("\n", matches);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ================================================================
    // Tool Schema + Dispatch
    // ================================================================

    static Tool buildTool(String name, String description,
                          Map<String, Map<String, String>> properties,
                          List<String> required) {
        Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
        properties.forEach((key, value) ->
                propsBuilder.putAdditionalProperty(key, JsonValue.from(value)));
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .properties(propsBuilder.build())
                        .required(required).build())
                .build();
    }

    static final List<ToolUnion> TOOLS = List.of(
            ToolUnion.ofTool(buildTool("memory_write",
                    "Save a note to long-term memory.",
                    Map.of("content", Map.of("type", "string",
                            "description", "The text to remember.")),
                    List.of("content"))),
            ToolUnion.ofTool(buildTool("memory_search",
                    "Search through saved memory notes.",
                    Map.of("query", Map.of("type", "string",
                            "description", "Search keyword.")),
                    List.of("query")))
    );

    static final Map<String, Function<Map<String, Object>, String>> TOOL_HANDLERS = new LinkedHashMap<>();
    static {
        TOOL_HANDLERS.put("memory_write", S04Channels::toolMemoryWrite);
        TOOL_HANDLERS.put("memory_search", S04Channels::toolMemorySearch);
    }

    static String processToolCall(String toolName, Map<String, Object> toolInput) {
        var handler = TOOL_HANDLERS.get(toolName);
        if (handler == null) return "Error: Unknown tool '" + toolName + "'";
        try { return handler.apply(toolInput); }
        catch (Exception e) { return "Error: " + toolName + " failed: " + e.getMessage(); }
    }

    // ================================================================
    // ChannelManager
    // ================================================================

    static class ChannelManager {
        final Map<String, Channel> channels = new LinkedHashMap<>();
        final List<ChannelAccount> accounts = new ArrayList<>();

        void register(Channel channel) {
            channels.put(channel.name(), channel);
            System.out.println(AnsiColors.BLUE + "  [+] Channel registered: " + channel.name()
                    + AnsiColors.RESET);
        }

        List<String> listChannels() { return new ArrayList<>(channels.keySet()); }

        Channel get(String name) { return channels.get(name); }

        void closeAll() { channels.values().forEach(Channel::close); }
    }

    // ================================================================
    // Telegram Background Polling
    // ================================================================

    static void telegramPollLoop(TelegramChannel tg, ConcurrentLinkedQueue<InboundMessage> queue,
                                  Set<?> stopFlag) {
        System.out.println(AnsiColors.BLUE + "  [telegram] Polling started for " + tg.accountId
                + AnsiColors.RESET);
        int backoff = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<InboundMessage> msgs = tg.poll();
                queue.addAll(msgs);
                backoff = 0;
            } catch (Exception e) {
                System.out.println("  " + AnsiColors.RED + "[telegram] Poll error: "
                        + e.getMessage() + AnsiColors.RESET);
                backoff = Math.min(backoff + 1, 6); // max 30s
                try { Thread.sleep((long) Math.pow(2, backoff) * 1000); }
                catch (InterruptedException ie) { break; }
            }
        }
    }

    // ================================================================
    // REPL Commands
    // ================================================================

    static boolean handleReplCommand(String cmd, ChannelManager mgr) {
        cmd = cmd.strip().toLowerCase();
        if ("/channels".equals(cmd)) {
            for (String name : mgr.listChannels())
                System.out.println(AnsiColors.BLUE + "  - " + name + AnsiColors.RESET);
            return true;
        }
        if ("/accounts".equals(cmd)) {
            for (ChannelAccount acc : mgr.accounts) {
                String masked = acc.token().length() > 8
                        ? acc.token().substring(0, 8) + "..." : "(none)";
                System.out.println(AnsiColors.BLUE + "  - " + acc.channel() + "/"
                        + acc.accountId() + "  token=" + masked + AnsiColors.RESET);
            }
            return true;
        }
        if ("/help".equals(cmd) || "/h".equals(cmd)) {
            AnsiColors.printInfo("  /channels  /accounts  /help  quit/exit");
            return true;
        }
        return false;
    }

    // ================================================================
    // Agent Turn
    // ================================================================

    static void runAgentTurn(InboundMessage inbound,
                            Map<String, List<MessageParam>> conversations,
                            ChannelManager mgr) {
        String sk = buildSessionKey(inbound.channel(), inbound.accountId(), inbound.peerId());
        conversations.putIfAbsent(sk, new ArrayList<>());
        List<MessageParam> messages = conversations.get(sk);

        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(inbound.text())
                .build());

        // Typing indicator for Telegram
        if ("telegram".equals(inbound.channel())) {
            Channel tg = mgr.get("telegram");
            if (tg instanceof TelegramChannel tgc) tgc.sendTyping(inbound.peerId());
        }

        while (true) {
            Message response;
            try {
                response = client.messages().create(MessageCreateParams.builder()
                        .model(MODEL_ID).maxTokens(8096)
                        .system(SYSTEM_PROMPT)
                        .tools(TOOLS)
                        .messages(messages)
                        .build());
            } catch (Exception e) {
                System.out.println("\n" + AnsiColors.YELLOW + "API Error: "
                        + e.getMessage() + AnsiColors.RESET + "\n");
                while (!messages.isEmpty()
                        && messages.get(messages.size() - 1).role() != MessageParam.Role.USER) {
                    messages.remove(messages.size() - 1);
                }
                if (!messages.isEmpty()) messages.remove(messages.size() - 1);
                return;
            }

            messages.add(response.toParam());
            StopReason reason = response.stopReason().orElse(null);

            if (reason == StopReason.END_TURN) {
                String text = response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
                if (!text.isEmpty()) {
                    Channel ch = mgr.get(inbound.channel());
                    if (ch != null) ch.send(inbound.peerId(), text);
                    else AnsiColors.printAssistant(text);
                }
                break;
            } else if (reason == StopReason.TOOL_USE) {
                List<ContentBlockParam> toolResultBlocks = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (!block.isToolUse()) continue;
                    ToolUseBlock tu = block.asToolUse();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> toolInput = tu._input().convert(Map.class);
                    String result = processToolCall(tu.name(), toolInput);
                    toolResultBlocks.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(tu.id()).content(result).build()));
                }
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(toolResultBlocks)
                        .build());
                continue;
            } else {
                String text = response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
                if (!text.isEmpty()) {
                    Channel ch = mgr.get(inbound.channel());
                    if (ch != null) ch.send(inbound.peerId(), text);
                }
                break;
            }
        }
    }

    // ================================================================
    // Main Loop
    // ================================================================

    static void agentLoop() {
        try { Files.createDirectories(STATE_DIR); } catch (IOException e) { /* ignore */ }

        ChannelManager mgr = new ChannelManager();
        CLIChannel cli = new CLIChannel();
        mgr.register(cli);

        TelegramChannel tgChannel = null;
        ConcurrentLinkedQueue<InboundMessage> msgQueue = new ConcurrentLinkedQueue<>();
        Thread tgThread = null;

        String tgToken = Config.get("TELEGRAM_BOT_TOKEN", "");
        if (!tgToken.isBlank()) {
            ChannelAccount tgAcc = new ChannelAccount("telegram", "tg-primary", tgToken,
                    Map.of("allowed_chats", Config.get("TELEGRAM_ALLOWED_CHATS", "")));
            mgr.accounts.add(tgAcc);
            tgChannel = new TelegramChannel(tgAcc);
            mgr.register(tgChannel);
            TelegramChannel finalTg = tgChannel;
            tgThread = Thread.ofVirtual().name("telegram-poll").start(
                    () -> telegramPollLoop(finalTg, msgQueue, Set.of()));
        }

        String fsId = Config.get("FEISHU_APP_ID", "");
        String fsSecret = Config.get("FEISHU_APP_SECRET", "");
        if (!fsId.isBlank() && !fsSecret.isBlank()) {
            ChannelAccount fsAcc = new ChannelAccount("feishu", "feishu-primary", "",
                    Map.of("app_id", fsId, "app_secret", fsSecret,
                            "encrypt_key", Config.get("FEISHU_ENCRYPT_KEY", ""),
                            "bot_open_id", Config.get("FEISHU_BOT_OPEN_ID", ""),
                            "is_lark", Config.get("FEISHU_IS_LARK", "false")));
            mgr.accounts.add(fsAcc);
            mgr.register(new FeishuChannel(fsAcc));
        }

        AnsiColors.printInfo("============================================================");
        AnsiColors.printInfo("  claw0  |  Section 04: Channels");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("  Channels: " + String.join(", ", mgr.listChannels()));
        AnsiColors.printInfo("  Commands: /channels /accounts /help  |  quit/exit");
        AnsiColors.printInfo("============================================================");
        System.out.println();

        Map<String, List<MessageParam>> conversations = new LinkedHashMap<>();

        while (true) {
            // Drain Telegram queue
            InboundMessage tgMsg;
            while ((tgMsg = msgQueue.poll()) != null) {
                System.out.println(AnsiColors.BLUE + "\n  [telegram] " + tgMsg.senderId()
                        + ": " + tgMsg.text().substring(0, Math.min(80, tgMsg.text().length()))
                        + AnsiColors.RESET);
                runAgentTurn(tgMsg, conversations, mgr);
            }

            // CLI input (non-blocking when Telegram is active)
            if (tgChannel != null) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                try { if (System.in.available() == 0) continue; }
                catch (IOException e) { break; }
            }

            Optional<InboundMessage> optMsg = cli.receive();
            if (optMsg.isEmpty()) break;
            String userInput = optMsg.get().text();

            if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) break;
            if (userInput.startsWith("/") && handleReplCommand(userInput, mgr)) continue;

            runAgentTurn(optMsg.get(), conversations, mgr);
        }

        AnsiColors.printInfo("Goodbye.");
        if (tgThread != null) tgThread.interrupt();
        mgr.closeAll();
    }

    // ================================================================
    // Entry Point
    // ================================================================

    public static void main(String[] args) {
        String apiKey = Config.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.startsWith("sk-ant-x")) {
            AnsiColors.printError("Error: ANTHROPIC_API_KEY not set.");
            AnsiColors.printInfo("Copy .env.example to .env and fill in your key.");
            System.exit(1);
        }
        agentLoop();
    }
}
