package com.claw0.sessions;

/**
 * Section 05: Gateway & Routing -- "Every message finds its home"
 *
 * Gateway is the message hub: each inbound message resolves to (agent_id, session_key).
 * The routing system is a 5-tier binding table, matching from most specific to most general.
 *
 *     Inbound message (channel, account_id, peer_id, text)
 *            |
 *     +------v------+     +----------+
 *     |   Gateway    | <-- | WS/REPL  |  JSON-RPC 2.0
 *     +------+------+
 *            |
 *     +------v------+
 *     |   Routing    |  5 tiers: peer > guild > account > channel > default
 *     +------+------+
 *            |
 *      (agent_id, session_key)
 *            |
 *     +------v------+
 *     | AgentManager |  per-agent config / workspace / sessions
 *     +------+------+
 *            |
 *         LLM API
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S05GatewayRouting"
 */

// region Common Imports
import com.claw0.common.AnsiColors;
import com.claw0.common.Config;
import com.claw0.common.JsonUtils;

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
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

public class S05GatewayRouting {

    // region Configuration
    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    static final int MAX_TOOL_OUTPUT = 30_000;
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));
    static final Path WORKSPACE_DIR = WORKDIR.resolve("workspace");
    static final Path AGENTS_DIR = WORKSPACE_DIR.resolve(".agents");

    static final AnthropicClient client = AnthropicOkHttpClient.builder()
            .fromEnv()
            .build();

    static final Pattern VALID_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    static final Pattern INVALID_CHARS = Pattern.compile("[^a-z0-9_-]+");
    static final String DEFAULT_AGENT_ID = "main";
    // endregion

    // ================================================================
    // Agent ID Normalization
    // ================================================================

    static String normalizeAgentId(String value) {
        if (value == null || value.isBlank()) return DEFAULT_AGENT_ID;
        String trimmed = value.strip();
        if (VALID_ID.matcher(trimmed).matches()) return trimmed.toLowerCase();
        String cleaned = INVALID_CHARS.matcher(trimmed.toLowerCase()).replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64);
        return cleaned.isBlank() ? DEFAULT_AGENT_ID : cleaned;
    }

    // ================================================================
    // Binding: 5-tier routing resolution
    // ================================================================
    // Tier 1: peer_id    -- route specific user to an agent
    // Tier 2: guild_id   -- guild/server level
    // Tier 3: account_id -- bot account level
    // Tier 4: channel    -- entire channel (e.g. all Telegram)
    // Tier 5: default    -- fallback

    record Binding(String agentId, int tier, String matchKey, String matchValue, int priority)
            implements Comparable<Binding> {

        String display() {
            String[] names = {"", "peer", "guild", "account", "channel", "default"};
            String label = tier >= 1 && tier <= 5 ? names[tier] : "tier-" + tier;
            return "[" + label + "] " + matchKey + "=" + matchValue
                    + " -> agent:" + agentId + " (pri=" + priority + ")";
        }

        @Override
        public int compareTo(Binding o) {
            int cmp = Integer.compare(this.tier, o.tier);
            return cmp != 0 ? cmp : Integer.compare(o.priority, this.priority);
        }
    }

    static class BindingTable {
        private final List<Binding> bindings = new ArrayList<>();

        void add(Binding b) {
            bindings.add(b);
            bindings.sort(Comparator.naturalOrder());
        }

        boolean remove(String agentId, String matchKey, String matchValue) {
            int before = bindings.size();
            bindings.removeIf(b ->
                    b.agentId().equals(agentId)
                    && b.matchKey().equals(matchKey)
                    && b.matchValue().equals(matchValue));
            return bindings.size() < before;
        }

        List<Binding> listAll() { return new ArrayList<>(bindings); }

        /** Walk tiers 1-5, first match wins. */
        Optional<Binding> resolve(String channel, String accountId,
                                  String guildId, String peerId) {
            for (Binding b : bindings) {
                boolean match = switch (b.tier()) {
                    case 1 -> b.matchKey().equals("peer_id") && (
                            (b.matchValue().contains(":")
                                    ? b.matchValue().equals(channel + ":" + peerId)
                                    : b.matchValue().equals(peerId)));
                    case 2 -> b.matchKey().equals("guild_id") && b.matchValue().equals(guildId);
                    case 3 -> b.matchKey().equals("account_id") && b.matchValue().equals(accountId);
                    case 4 -> b.matchKey().equals("channel") && b.matchValue().equals(channel);
                    case 5 -> b.matchKey().equals("default");
                    default -> false;
                };
                if (match) return Optional.of(b);
            }
            return Optional.empty();
        }
    }

    // ================================================================
    // Session Key Builder (dm_scope isolation)
    // ================================================================
    // dm_scope controls DM session isolation granularity:
    //   main                      -> agent:{id}:main
    //   per-peer                  -> agent:{id}:direct:{peer}
    //   per-channel-peer          -> agent:{id}:{ch}:direct:{peer}
    //   per-account-channel-peer  -> agent:{id}:{ch}:{acc}:direct:{peer}

    static String buildSessionKey(String agentId, String channel, String accountId,
                                  String peerId, String dmScope) {
        String aid = normalizeAgentId(agentId);
        String ch = (channel == null || channel.isBlank()) ? "unknown" : channel.strip().toLowerCase();
        String acc = (accountId == null || accountId.isBlank()) ? "default" : accountId.strip().toLowerCase();
        String pid = (peerId == null) ? "" : peerId.strip().toLowerCase();

        if ("per-account-channel-peer".equals(dmScope) && !pid.isEmpty())
            return "agent:" + aid + ":" + ch + ":" + acc + ":direct:" + pid;
        if ("per-channel-peer".equals(dmScope) && !pid.isEmpty())
            return "agent:" + aid + ":" + ch + ":direct:" + pid;
        if ("per-peer".equals(dmScope) && !pid.isEmpty())
            return "agent:" + aid + ":direct:" + pid;
        return "agent:" + aid + ":main";
    }

    // ================================================================
    // AgentConfig & AgentManager
    // ================================================================

    record AgentConfig(String id, String name, String personality,
                       String model, String dmScope) {
        String effectiveModel() { return (model == null || model.isBlank()) ? MODEL_ID : model; }

        String systemPrompt() {
            StringBuilder sb = new StringBuilder("You are ").append(name).append(".");
            if (personality != null && !personality.isBlank())
                sb.append(" Your personality: ").append(personality);
            sb.append(" Answer questions helpfully and stay in character.");
            return sb.toString();
        }
    }

    static class AgentManager {
        private final Map<String, AgentConfig> agents = new LinkedHashMap<>();
        private final Map<String, List<MessageParam>> sessions = new ConcurrentHashMap<>();
        private final Path agentsBase;

        AgentManager() { this(AGENTS_DIR); }
        AgentManager(Path agentsBase) {
            this.agentsBase = agentsBase;
            try { Files.createDirectories(agentsBase); } catch (IOException e) { /* ignore */ }
        }

        void register(AgentConfig config) {
            String aid = normalizeAgentId(config.id());
            agents.put(aid, new AgentConfig(aid, config.name(), config.personality(),
                    config.model(), config.dmScope()));
            // Create agent directories
            try {
                Files.createDirectories(agentsBase.resolve(aid).resolve("sessions"));
                Files.createDirectories(WORKSPACE_DIR.resolve("workspace-" + aid));
            } catch (IOException e) { /* ignore */ }
        }

        AgentConfig getAgent(String agentId) {
            return agents.get(normalizeAgentId(agentId));
        }

        List<AgentConfig> listAgents() { return new ArrayList<>(agents.values()); }

        List<MessageParam> getSession(String sessionKey) {
            return sessions.computeIfAbsent(sessionKey, k -> new ArrayList<>());
        }

        Map<String, Integer> listSessions(String agentId) {
            String prefix = agentId.isBlank() ? "agent:" : "agent:" + normalizeAgentId(agentId) + ":";
            return sessions.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(prefix))
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
        }
    }

    // ================================================================
    // Route Resolution
    // ================================================================

    static String[] resolveRoute(BindingTable bindings, AgentManager mgr,
                                String channel, String peerId,
                                String accountId, String guildId) {
        Optional<Binding> matched = bindings.resolve(channel, accountId, guildId, peerId);
        String agentId;
        if (matched.isEmpty()) {
            agentId = DEFAULT_AGENT_ID;
            AnsiColors.printInfo("  [route] No binding matched, default: " + agentId);
        } else {
            Binding b = matched.get();
            agentId = b.agentId();
            AnsiColors.printInfo("  [route] Matched: " + b.display());
        }
        AgentConfig agent = mgr.getAgent(agentId);
        String dmScope = agent != null ? agent.dmScope() : "per-peer";
        String sessionKey = buildSessionKey(agentId, channel, accountId, peerId, dmScope);
        return new String[]{agentId, sessionKey};
    }

    // ================================================================
    // Tools (read_file, get_current_time)
    // ================================================================

    static final Path WORKSPACE_DIR_RESOLVE = WORKSPACE_DIR;

    static Path safePath(String raw) {
        Path target = WORKSPACE_DIR_RESOLVE.resolve(raw).normalize().toAbsolutePath();
        if (!target.startsWith(WORKSPACE_DIR_RESOLVE.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Path traversal blocked: " + raw);
        return target;
    }

    static String toolReadFile(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        AnsiColors.printTool("read_file", filePath);
        try {
            Path target = safePath(filePath);
            if (!Files.exists(target)) return "Error: File not found: " + filePath;
            String content = Files.readString(target);
            return content.length() > MAX_TOOL_OUTPUT
                    ? content.substring(0, MAX_TOOL_OUTPUT) + "\n... [truncated]"
                    : content;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    static String toolGetCurrentTime(Map<String, Object> input) {
        AnsiColors.printTool("get_current_time", "");
        return Instant.now().toString().replace("T", " ").replace("Z", " UTC");
    }

    static Tool buildTool(String name, String description,
                          Map<String, Map<String, String>> properties,
                          List<String> required) {
        Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> pb.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object")).properties(pb.build())
                        .required(required).build())
                .build();
    }

    static final List<ToolUnion> TOOLS = List.of(
            ToolUnion.ofTool(buildTool("read_file",
                    "Read the contents of a file under the workspace directory.",
                    Map.of("file_path", Map.of("type", "string",
                            "description", "Path relative to workspace directory.")),
                    List.of("file_path"))),
            ToolUnion.ofTool(buildTool("get_current_time",
                    "Get the current date and time in UTC.",
                    Map.of(), List.of()))
    );

    static final Map<String, Function<Map<String, Object>, String>> TOOL_HANDLERS = new LinkedHashMap<>();
    static {
        TOOL_HANDLERS.put("read_file", S05GatewayRouting::toolReadFile);
        TOOL_HANDLERS.put("get_current_time", S05GatewayRouting::toolGetCurrentTime);
    }

    // ================================================================
    // Agent Runner (with concurrency limit)
    // ================================================================

    static final Semaphore agentSemaphore = new Semaphore(4);

    static String runAgent(AgentManager mgr, String agentId, String sessionKey,
                          String userText) {
        AgentConfig agent = mgr.getAgent(agentId);
        if (agent == null) return "Error: agent '" + agentId + "' not found";

        List<MessageParam> messages = mgr.getSession(sessionKey);
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER).content(userText).build());

        if (!agentSemaphore.tryAcquire()) {
            // Remove the user message we just added
            messages.remove(messages.size() - 1);
            return "Error: Server busy, try again later.";
        }

        try {
            return agentLoop(agent.effectiveModel(), agent.systemPrompt(), messages);
        } finally {
            agentSemaphore.release();
        }
    }

    static String agentLoop(String model, String system, List<MessageParam> messages) {
        for (int i = 0; i < 15; i++) {
            Message response;
            try {
                response = client.messages().create(MessageCreateParams.builder()
                        .model(model).maxTokens(4096)
                        .system(system).tools(TOOLS)
                        .messages(messages).build());
            } catch (Exception e) {
                // Rollback
                while (!messages.isEmpty()
                        && messages.get(messages.size() - 1).role() != MessageParam.Role.USER)
                    messages.remove(messages.size() - 1);
                if (!messages.isEmpty()) messages.remove(messages.size() - 1);
                return "API Error: " + e.getMessage();
            }

            messages.add(response.toParam());
            StopReason reason = response.stopReason().orElse(null);

            if (reason == StopReason.END_TURN) {
                return response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
            } else if (reason == StopReason.TOOL_USE) {
                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (!block.isToolUse()) continue;
                    ToolUseBlock tu = block.asToolUse();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> toolInput = tu._input().convert(Map.class);
                    String result = TOOL_HANDLERS.getOrDefault(tu.name(),
                                    (m) -> "Error: Unknown tool '" + tu.name() + "'")
                            .apply(toolInput);
                    results.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(tu.id()).content(result).build()));
                }
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(results).build());
            } else {
                return response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
            }
        }
        return "[max iterations reached]";
    }

    // ================================================================
    // GatewayServer (WebSocket + JSON-RPC 2.0)
    // ================================================================

    static class GatewayServer extends WebSocketServer {
        private final AgentManager mgr;
        private final BindingTable bindings;
        private final long startTime;

        GatewayServer(AgentManager mgr, BindingTable bindings, int port) {
            super(new InetSocketAddress(port));
            this.mgr = mgr;
            this.bindings = bindings;
            this.startTime = System.currentTimeMillis();
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            AnsiColors.printInfo("  [ws] Client connected: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            AnsiColors.printInfo("  [ws] Client disconnected: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            AnsiColors.printError("  [ws] Error: " + ex.getMessage());
        }

        @Override
        public void onStart() {
            System.out.println(AnsiColors.GREEN + "  Gateway started on ws://localhost:"
                    + getPort() + AnsiColors.RESET);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            Object id = null;
            try {
                Map<String, Object> req = JsonUtils.toMap(message);
                id = req.get("id");
                String method = (String) req.getOrDefault("method", "");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) req.getOrDefault("params", Map.of());

                Object result = switch (method) {
                    case "send" -> handleSend(params);
                    case "bindings.set" -> handleBindSet(params);
                    case "bindings.list" -> handleBindList();
                    case "sessions.list" -> handleSessionsList(params);
                    case "agents.list" -> handleAgentsList();
                    case "status" -> handleStatus();
                    default -> throw new RuntimeException("Unknown method: " + method);
                };

                conn.send(JsonUtils.toJson(Map.of("jsonrpc", "2.0", "result", result, "id", id)));
            } catch (Exception e) {
                conn.send(JsonUtils.toJson(Map.of("jsonrpc", "2.0",
                        "error", Map.of("code", -32000, "message", e.getMessage()), "id", id)));
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> handleSend(Map<String, Object> p) {
            String text = (String) p.getOrDefault("text", "");
            if (text.isEmpty()) throw new RuntimeException("text is required");
            String ch = (String) p.getOrDefault("channel", "websocket");
            String pid = (String) p.getOrDefault("peer_id", "ws-client");

            String agentId, sessionKey;
            if (p.containsKey("agent_id")) {
                agentId = normalizeAgentId((String) p.get("agent_id"));
                AgentConfig a = mgr.getAgent(agentId);
                sessionKey = buildSessionKey(agentId, ch, null, pid,
                        a != null ? a.dmScope() : "per-peer");
            } else {
                String[] route = resolveRoute(bindings, mgr, ch, pid, null, null);
                agentId = route[0];
                sessionKey = route[1];
            }

            String reply = runAgent(mgr, agentId, sessionKey, text);
            return Map.of("agent_id", agentId, "session_key", sessionKey, "reply", reply);
        }

        Map<String, Object> handleBindSet(Map<String, Object> p) {
            Binding b = new Binding(
                    normalizeAgentId((String) p.getOrDefault("agent_id", "")),
                    ((Number) p.getOrDefault("tier", 5)).intValue(),
                    (String) p.getOrDefault("match_key", "default"),
                    (String) p.getOrDefault("match_value", "*"),
                    ((Number) p.getOrDefault("priority", 0)).intValue());
            bindings.add(b);
            return Map.of("ok", true, "binding", b.display());
        }

        List<Map<String, Object>> handleBindList() {
            return bindings.listAll().stream()
                    .map(b -> Map.<String, Object>of(
                            "agent_id", b.agentId(), "tier", b.tier(),
                            "match_key", b.matchKey(), "match_value", b.matchValue(),
                            "priority", b.priority()))
                    .toList();
        }

        Map<String, Integer> handleSessionsList(Map<String, Object> p) {
            return mgr.listSessions((String) p.getOrDefault("agent_id", ""));
        }

        List<Map<String, Object>> handleAgentsList() {
            return mgr.listAgents().stream()
                    .map(a -> Map.<String, Object>of(
                            "id", a.id(), "name", a.name(),
                            "model", a.effectiveModel(), "dm_scope", a.dmScope()))
                    .toList();
        }

        Map<String, Object> handleStatus() {
            return Map.<String, Object>of(
                    "running", true,
                    "uptime_seconds", (System.currentTimeMillis() - startTime) / 1000,
                    "connected_clients", getConnections().size(),
                    "agent_count", mgr.listAgents().size(),
                    "binding_count", bindings.listAll().size());
        }
    }

    // ================================================================
    // REPL Commands
    // ================================================================

    static void cmdBindings(BindingTable bt) {
        List<Binding> all = bt.listAll();
        if (all.isEmpty()) { AnsiColors.printInfo("  (no bindings)"); return; }
        System.out.println(AnsiColors.BOLD + "\nRoute Bindings (" + all.size() + "):" + AnsiColors.RESET);
        String[] colors = {AnsiColors.MAGENTA, AnsiColors.BLUE, AnsiColors.CYAN, AnsiColors.GREEN, AnsiColors.DIM};
        for (Binding b : all) {
            String c = colors[Math.min(b.tier() - 1, 4)];
            System.out.println("  " + c + b.display() + AnsiColors.RESET);
        }
        System.out.println();
    }

    static void cmdRoute(BindingTable bt, AgentManager mgr, String args) {
        String[] parts = args.strip().split("\\s+");
        if (parts.length < 2 || parts[0].isBlank()) {
            System.out.println(AnsiColors.YELLOW + "  Usage: /route <channel> <peer_id> [account_id] [guild_id]"
                    + AnsiColors.RESET);
            return;
        }
        String ch = parts[0], pid = parts[1];
        String acc = parts.length > 2 ? parts[2] : "";
        String gid = parts.length > 3 ? parts[3] : "";
        String[] route = resolveRoute(bt, mgr, ch, pid, acc, gid);
        AgentConfig a = mgr.getAgent(route[0]);
        System.out.println(AnsiColors.BOLD + "\nRoute Resolution:" + AnsiColors.RESET);
        AnsiColors.printInfo("  Input:   ch=" + ch + " peer=" + pid
                + " acc=" + (acc.isEmpty() ? "-" : acc) + " guild=" + (gid.isEmpty() ? "-" : gid));
        System.out.println(AnsiColors.CYAN + "  Agent:   " + route[0]
                + " (" + (a != null ? a.name() : "?") + ")" + AnsiColors.RESET);
        System.out.println(AnsiColors.GREEN + "  Session: " + route[1] + AnsiColors.RESET + "\n");
    }

    static void cmdAgents(AgentManager mgr) {
        List<AgentConfig> agents = mgr.listAgents();
        if (agents.isEmpty()) { AnsiColors.printInfo("  (no agents)"); return; }
        System.out.println(AnsiColors.BOLD + "\nAgents (" + agents.size() + "):" + AnsiColors.RESET);
        for (AgentConfig a : agents) {
            System.out.println(AnsiColors.CYAN + "  " + a.id() + AnsiColors.RESET
                    + " (" + a.name() + ")  model=" + a.effectiveModel() + "  dm_scope=" + a.dmScope());
            if (a.personality() != null && !a.personality().isEmpty()) {
                String preview = a.personality().length() > 70
                        ? a.personality().substring(0, 70) + "..." : a.personality();
                AnsiColors.printInfo("    " + preview);
            }
        }
        System.out.println();
    }

    static void cmdSessions(AgentManager mgr) {
        Map<String, Integer> sessions = mgr.listSessions("");
        if (sessions.isEmpty()) { AnsiColors.printInfo("  (no sessions)"); return; }
        System.out.println(AnsiColors.BOLD + "\nSessions (" + sessions.size() + "):" + AnsiColors.RESET);
        sessions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println(AnsiColors.GREEN + "  " + e.getKey()
                        + AnsiColors.RESET + " (" + e.getValue() + " msgs)"));
        System.out.println();
    }

    // ================================================================
    // Demo Setup: dual agent (luna + sage) + routing bindings
    // ================================================================

    static AgentManager setupAgents() {
        AgentManager mgr = new AgentManager();
        mgr.register(new AgentConfig("luna", "Luna",
                "warm, curious, and encouraging. You love asking follow-up questions.",
                "", "per-peer"));
        mgr.register(new AgentConfig("sage", "Sage",
                "direct, analytical, and concise. You prefer facts over opinions.",
                "", "per-peer"));
        return mgr;
    }

    static BindingTable setupBindings() {
        BindingTable bt = new BindingTable();
        bt.add(new Binding("luna", 5, "default", "*", 0));
        bt.add(new Binding("sage", 4, "channel", "telegram", 0));
        bt.add(new Binding("sage", 1, "peer_id", "discord:admin-001", 10));
        return bt;
    }

    // ================================================================
    // Main REPL
    // ================================================================

    static void repl() {
        AgentManager mgr = setupAgents();
        BindingTable bindings = setupBindings();

        AnsiColors.printInfo("================================================================");
        AnsiColors.printInfo("  claw0  |  Section 05: Gateway & Routing");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("================================================================");
        AnsiColors.printInfo("  /bindings  /route <ch> <peer>  /agents  /sessions  /switch <id>  /gateway");
        System.out.println();

        String ch = "cli", pid = "repl-user";
        String forceAgent = "";
        GatewayServer gateway = null;

        Scanner scanner = new Scanner(System.in);
        while (true) {
            String userInput;
            try {
                System.out.print(AnsiColors.coloredPrompt());
                userInput = scanner.nextLine().strip();
            } catch (Exception e) {
                AnsiColors.printInfo("Goodbye.");
                break;
            }

            if (userInput.isEmpty()) continue;
            if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) {
                AnsiColors.printInfo("Goodbye.");
                break;
            }

            if (userInput.startsWith("/")) {
                String[] cmdParts = userInput.split("\\s+", 2);
                String cmd = cmdParts[0].toLowerCase();
                String cmdArgs = cmdParts.length > 1 ? cmdParts[1] : "";

                switch (cmd) {
                    case "/bindings" -> cmdBindings(bindings);
                    case "/route" -> cmdRoute(bindings, mgr, cmdArgs);
                    case "/agents" -> cmdAgents(mgr);
                    case "/sessions" -> cmdSessions(mgr);
                    case "/switch" -> {
                        if (cmdArgs.isEmpty()) {
                            AnsiColors.printInfo("  force=" + (forceAgent.isEmpty() ? "(off)" : forceAgent));
                        } else if ("off".equalsIgnoreCase(cmdArgs)) {
                            forceAgent = "";
                            AnsiColors.printInfo("  Routing mode restored.");
                        } else {
                            String aid = normalizeAgentId(cmdArgs);
                            if (mgr.getAgent(aid) != null) {
                                forceAgent = aid;
                                System.out.println(AnsiColors.GREEN + "  Forcing: " + aid + AnsiColors.RESET);
                            } else {
                                System.out.println(AnsiColors.YELLOW + "  Not found: " + aid + AnsiColors.RESET);
                            }
                        }
                    }
                    case "/gateway" -> {
                        if (gateway != null) {
                            AnsiColors.printInfo("  Already running.");
                        } else {
                            int port = Integer.parseInt(Config.get("GATEWAY_PORT", "8765"));
                            gateway = new GatewayServer(mgr, bindings, port);
                            gateway.start();
                            System.out.println(AnsiColors.GREEN
                                    + "  Gateway running in background on ws://localhost:" + port
                                    + AnsiColors.RESET + "\n");
                        }
                    }
                    default -> System.out.println(AnsiColors.YELLOW + "  Unknown: " + cmd + AnsiColors.RESET);
                }
                continue;
            }

            // Resolve route or use forced agent
            String agentId, sessionKey;
            if (!forceAgent.isEmpty()) {
                agentId = forceAgent;
                AgentConfig a = mgr.getAgent(agentId);
                sessionKey = buildSessionKey(agentId, ch, null, pid,
                        a != null ? a.dmScope() : "per-peer");
            } else {
                String[] route = resolveRoute(bindings, mgr, ch, pid, null, null);
                agentId = route[0];
                sessionKey = route[1];
            }

            AgentConfig agent = mgr.getAgent(agentId);
            String name = agent != null ? agent.name() : agentId;
            AnsiColors.printInfo("  -> " + name + " (" + agentId + ") | " + sessionKey);

            String reply = runAgent(mgr, agentId, sessionKey, userInput);
            System.out.println("\n" + AnsiColors.GREEN + AnsiColors.BOLD + name + ":" + AnsiColors.RESET
                    + " " + reply + "\n");
        }

        if (gateway != null) {
            try { gateway.stop(); } catch (Exception e) { /* ignore */ }
        }
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
        repl();
    }
}
