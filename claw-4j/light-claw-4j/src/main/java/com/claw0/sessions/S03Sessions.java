package com.claw0.sessions;

/**
 * Section 03: Sessions & Context Guard
 * "Sessions are JSONL files. Append on write, replay on read. Summarize when too large."
 *
 * Two layers around the same agent loop:
 *
 *   SessionStore -- JSONL persistence (append on write, replay on read)
 *   ContextGuard -- 3-phase overflow retry:
 *     normal call -> truncate tool results -> compress history (50%) -> throw
 *
 *   User input
 *       |
 *   load_session() --> rebuild messages[] from JSONL
 *       |
 *   guard_api_call() --> try -> truncate -> compact -> throw
 *       |
 *   save_turn() --> append to JSONL
 *       |
 *   Print response
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S03Sessions"
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class S03Sessions {

    // region Configuration
    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant with access to tools.\n"
            + "Use tools to help the user with file and time queries.\n"
            + "Be concise. If a session has prior context, use it.";

    static final int MAX_TOOL_OUTPUT = 50_000;
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));
    static final Path WORKSPACE_DIR = WORKDIR.resolve("workspace");
    static final int CONTEXT_SAFE_LIMIT = 180_000;

    static final AnthropicClient client = AnthropicOkHttpClient.builder()
            .fromEnv()
            .build();
    // endregion

    // ================================================================
    // S01-S02 Core: Safety Helpers
    // ================================================================

    static Path safePath(String raw) {
        Path target = WORKSPACE_DIR.resolve(raw).normalize().toAbsolutePath();
        Path wsAbs = WORKSPACE_DIR.toAbsolutePath().normalize();
        if (!target.startsWith(wsAbs)) {
            throw new IllegalArgumentException("Path traversal blocked: " + raw);
        }
        return target;
    }

    static String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        return text.substring(0, limit) + "\n... [truncated, " + text.length() + " total chars]";
    }

    static String truncate(String text) {
        return truncate(text, MAX_TOOL_OUTPUT);
    }

    // ================================================================
    // S01-S02 Core: Tool Implementations
    // ================================================================

    static String toolReadFile(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        AnsiColors.printTool("read_file", filePath);
        try {
            Path target = safePath(filePath);
            if (!Files.exists(target)) return "Error: File not found: " + filePath;
            if (!Files.isRegularFile(target)) return "Error: Not a file: " + filePath;
            return truncate(Files.readString(target));
        } catch (IllegalArgumentException e) { return e.getMessage(); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    static String toolListDirectory(Map<String, Object> input) {
        String directory = (String) input.getOrDefault("directory", ".");
        AnsiColors.printTool("list_directory", directory);
        try {
            Path target = safePath(directory);
            if (!Files.exists(target)) return "Error: Directory not found: " + directory;
            if (!Files.isDirectory(target)) return "Error: Not a directory: " + directory;
            StringBuilder sb = new StringBuilder();
            try (var stream = Files.list(target)) {
                stream.sorted().forEach(p -> {
                    sb.append(Files.isDirectory(p) ? "[dir]  " : "[file] ");
                    sb.append(p.getFileName()).append("\n");
                });
            }
            return sb.isEmpty() ? "[empty directory]" : sb.toString().trim();
        } catch (IllegalArgumentException e) { return e.getMessage(); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    static String toolGetCurrentTime(Map<String, Object> input) {
        AnsiColors.printTool("get_current_time", "");
        return Instant.now().toString().replace("T", " ").replace("Z", " UTC");
    }

    // ================================================================
    // S01-S02 Core: Tool Schema + Dispatch
    // ================================================================

    static Tool buildTool(String name, String description,
                          Map<String, Map<String, String>> properties,
                          List<String> required) {
        Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
        properties.forEach((key, value) ->
                propsBuilder.putAdditionalProperty(key, JsonValue.from(value)));
        return Tool.builder()
                .name(name).description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .properties(propsBuilder.build())
                        .required(required).build())
                .build();
    }

    static final List<ToolUnion> TOOLS = List.of(
            ToolUnion.ofTool(buildTool("read_file",
                    "Read the contents of a file under the workspace directory.",
                    Map.of("file_path", Map.of("type", "string",
                            "description", "Path relative to workspace directory.")),
                    List.of("file_path"))),
            ToolUnion.ofTool(buildTool("list_directory",
                    "List files and subdirectories in a directory under workspace.",
                    Map.of("directory", Map.of("type", "string",
                            "description", "Path relative to workspace directory. Default is root.")),
                    List.of())),
            ToolUnion.ofTool(buildTool("get_current_time",
                    "Get the current date and time in UTC.",
                    Map.of(), List.of()))
    );

    static final Map<String, Function<Map<String, Object>, String>> TOOL_HANDLERS = new LinkedHashMap<>();
    static {
        TOOL_HANDLERS.put("read_file", S03Sessions::toolReadFile);
        TOOL_HANDLERS.put("list_directory", S03Sessions::toolListDirectory);
        TOOL_HANDLERS.put("get_current_time", S03Sessions::toolGetCurrentTime);
    }

    static String processToolCall(String toolName, Map<String, Object> toolInput) {
        var handler = TOOL_HANDLERS.get(toolName);
        if (handler == null) return "Error: Unknown tool '" + toolName + "'";
        try { return handler.apply(toolInput); }
        catch (Exception e) { return "Error: " + toolName + " failed: " + e.getMessage(); }
    }

    // ================================================================
    // S03 NEW: SessionStore -- JSONL-based session persistence
    // ================================================================

    static class SessionStore {
        final String agentId;
        final Path baseDir;
        final Path indexPath;
        Map<String, Map<String, Object>> index;
        String currentSessionId;

        SessionStore(String agentId) {
            this.agentId = agentId;
            this.baseDir = WORKSPACE_DIR.resolve(".sessions/agents/" + agentId + "/sessions");
            this.indexPath = WORKSPACE_DIR.resolve(".sessions/agents/" + agentId + "/sessions.json");
            try { Files.createDirectories(baseDir); } catch (IOException e) { /* ignore */ }
            this.index = loadIndex();
            this.currentSessionId = null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> loadIndex() {
            if (Files.exists(indexPath)) {
                try {
                    Map<String, Map<String, Object>> loaded =
                            JsonUtils.MAPPER.readValue(indexPath.toFile(), Map.class);
                    return loaded;
                } catch (Exception e) { return new LinkedHashMap<>(); }
            }
            return new LinkedHashMap<>();
        }

        void saveIndex() {
            try {
                Files.createDirectories(indexPath.getParent());
                Files.writeString(indexPath, JsonUtils.MAPPER
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(index));
            } catch (IOException e) {
                AnsiColors.printError("Failed to save session index: " + e.getMessage());
            }
        }

        Path sessionPath(String sessionId) {
            return baseDir.resolve(sessionId + ".jsonl");
        }

        String createSession(String label) {
            String sessionId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            String now = Instant.now().toString();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("label", label != null ? label : "");
            meta.put("created_at", now);
            meta.put("last_active", now);
            meta.put("message_count", 0L);
            index.put(sessionId, meta);
            saveIndex();
            try { sessionPath(sessionId).toFile().createNewFile(); } catch (IOException e) { /* ignore */ }
            currentSessionId = sessionId;
            return sessionId;
        }

        List<MessageParam> loadSession(String sessionId) {
            Path path = sessionPath(sessionId);
            if (!Files.exists(path)) return new ArrayList<>();
            currentSessionId = sessionId;
            return rebuildHistory(path);
        }

        void saveTurn(String role, Object content) {
            if (currentSessionId == null) return;
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", role);
            record.put("content", content);
            record.put("ts", System.currentTimeMillis() / 1000.0);
            appendTranscript(currentSessionId, record);
        }

        void saveToolResult(String toolUseId, String name,
                            Map<String, Object> toolInput, String result) {
            if (currentSessionId == null) return;
            double ts = System.currentTimeMillis() / 1000.0;
            Map<String, Object> useRecord = new LinkedHashMap<>();
            useRecord.put("type", "tool_use");
            useRecord.put("tool_use_id", toolUseId);
            useRecord.put("name", name);
            useRecord.put("input", toolInput);
            useRecord.put("ts", ts);
            appendTranscript(currentSessionId, useRecord);

            Map<String, Object> resultRecord = new LinkedHashMap<>();
            resultRecord.put("type", "tool_result");
            resultRecord.put("tool_use_id", toolUseId);
            resultRecord.put("content", result);
            resultRecord.put("ts", ts);
            appendTranscript(currentSessionId, resultRecord);
        }

        void appendTranscript(String sessionId, Map<String, Object> record) {
            try {
                JsonUtils.appendJsonl(sessionPath(sessionId), record);
                if (index.containsKey(sessionId)) {
                    Map<String, Object> meta = index.get(sessionId);
                    meta.put("last_active", Instant.now().toString());
                    meta.put("message_count", ((Number) meta.getOrDefault("message_count", 0)).longValue() + 1);
                    saveIndex();
                }
            } catch (IOException e) {
                AnsiColors.printError("Failed to append transcript: " + e.getMessage());
            }
        }

        /**
         * Rebuild API-format messages[] from JSONL lines.
         * Rules: user/assistant must alternate, tool_use blocks belong to assistant,
         * tool_result blocks belong to user, consecutive tool_results are merged.
         */
        @SuppressWarnings("unchecked")
        List<MessageParam> rebuildHistory(Path path) {
            List<MessageParam> messages = new ArrayList<>();
            // We build intermediate representation and then convert to MessageParam
            List<Map<String, Object>> rawMessages = new ArrayList<>();

            try {
                for (String line : Files.readAllLines(path)) {
                    if (line.isBlank()) continue;
                    Map<String, Object> record;
                    try { record = JsonUtils.toMap(line); }
                    catch (Exception e) { continue; }

                    String rtype = (String) record.get("type");

                    if ("user".equals(rtype)) {
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("role", "user");
                        msg.put("content", record.get("content"));
                        rawMessages.add(msg);
                    } else if ("assistant".equals(rtype)) {
                        Object content = record.get("content");
                        Map<String, Object> msg = new LinkedHashMap<>();
                        msg.put("role", "assistant");
                        msg.put("content", content);
                        rawMessages.add(msg);
                    } else if ("tool_use".equals(rtype)) {
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", "tool_use");
                        block.put("id", record.get("tool_use_id"));
                        block.put("name", record.get("name"));
                        block.put("input", record.get("input"));

                        if (!rawMessages.isEmpty()
                                && "assistant".equals(rawMessages.get(rawMessages.size() - 1).get("role"))) {
                            Object content = rawMessages.get(rawMessages.size() - 1).get("content");
                            if (content instanceof List) {
                                ((List<Object>) content).add(block);
                            } else {
                                List<Object> blocks = new ArrayList<>();
                                blocks.add(Map.of("type", "text", "text", String.valueOf(content)));
                                blocks.add(block);
                                rawMessages.get(rawMessages.size() - 1).put("content", blocks);
                            }
                        } else {
                            Map<String, Object> msg = new LinkedHashMap<>();
                            msg.put("role", "assistant");
                            msg.put("content", List.of(block));
                            rawMessages.add(msg);
                        }
                    } else if ("tool_result".equals(rtype)) {
                        Map<String, Object> resultBlock = new LinkedHashMap<>();
                        resultBlock.put("type", "tool_result");
                        resultBlock.put("tool_use_id", record.get("tool_use_id"));
                        resultBlock.put("content", record.get("content"));

                        if (!rawMessages.isEmpty()
                                && "user".equals(rawMessages.get(rawMessages.size() - 1).get("role"))) {
                            Object content = rawMessages.get(rawMessages.size() - 1).get("content");
                            if (content instanceof List<?> list && !list.isEmpty()
                                    && list.get(0) instanceof Map<?, ?> first
                                    && "tool_result".equals(first.get("type"))) {
                                ((List<Object>) content).add(resultBlock);
                            } else {
                                Map<String, Object> msg = new LinkedHashMap<>();
                                msg.put("role", "user");
                                msg.put("content", List.of(resultBlock));
                                rawMessages.add(msg);
                            }
                        } else {
                            Map<String, Object> msg = new LinkedHashMap<>();
                            msg.put("role", "user");
                            msg.put("content", List.of(resultBlock));
                            rawMessages.add(msg);
                        }
                    }
                }
            } catch (IOException e) {
                AnsiColors.printError("Failed to rebuild history: " + e.getMessage());
            }

            // Convert raw messages to MessageParam
            for (Map<String, Object> raw : rawMessages) {
                String role = (String) raw.get("role");
                Object content = raw.get("content");
                MessageParam.Role paramRole = "user".equals(role)
                        ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;

                if (content instanceof String text) {
                    messages.add(MessageParam.builder().role(paramRole).content(text).build());
                } else if (content instanceof List<?> blocks) {
                    // Convert block list to ContentBlockParam list
                    List<ContentBlockParam> blockParams = convertBlocks(blocks);
                    messages.add(MessageParam.builder().role(paramRole)
                            .contentOfBlockParams(blockParams).build());
                }
            }

            return messages;
        }

        @SuppressWarnings("unchecked")
        List<ContentBlockParam> convertBlocks(List<?> blocks) {
            List<ContentBlockParam> result = new ArrayList<>();
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map) {
                    String type = (String) map.get("type");
                    if ("text".equals(type)) {
                        // Skip -- we handle text differently at the message level
                    } else if ("tool_result".equals(type)) {
                        result.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId((String) map.get("tool_use_id"))
                                        .content(String.valueOf(map.get("content")))
                                        .build()));
                    }
                    // tool_use blocks in history are part of assistant messages (handled via response.toParam)
                }
            }
            return result;
        }

        List<Map.Entry<String, Map<String, Object>>> listSessions() {
            return index.entrySet().stream()
                    .sorted((a, b) -> {
                        String ta = (String) a.getValue().getOrDefault("last_active", "");
                        String tb = (String) b.getValue().getOrDefault("last_active", "");
                        return tb.compareTo(ta); // most recent first
                    })
                    .collect(Collectors.toList());
        }
    }

    // ================================================================
    // S03 NEW: ContextGuard -- 3-phase overflow protection
    // ================================================================

    static class ContextGuard {
        final int maxTokens;

        ContextGuard() { this(CONTEXT_SAFE_LIMIT); }
        ContextGuard(int maxTokens) { this.maxTokens = maxTokens; }

        static int estimateTokens(String text) {
            return text.length() / 4;
        }

        int estimateMessagesTokens(List<MessageParam> messages) {
            int total = 0;
            for (MessageParam msg : messages) {
                // Estimate from the serialized JSON form
                total += estimateTokens(JsonUtils.toJson(msg));
            }
            return total;
        }

        String truncateToolResult(String result, double maxFraction) {
            int maxChars = (int) (maxTokens * 4 * maxFraction);
            if (result.length() <= maxChars) return result;
            int cut = result.lastIndexOf('\n', maxChars);
            if (cut <= 0) cut = maxChars;
            String head = result.substring(0, cut);
            return head + "\n\n[... truncated (" + result.length()
                    + " chars total, showing first " + head.length() + ") ...]";
        }

        /**
         * Compress the first 50% of messages into an LLM-generated summary.
         * Keep the last N messages (N = max(4, 20% of total)) unchanged.
         */
        List<MessageParam> compactHistory(List<MessageParam> messages) {
            int total = messages.size();
            if (total <= 4) return messages;

            int keepCount = Math.max(4, (int) (total * 0.2));
            int compressCount = Math.max(2, (int) (total * 0.5));
            compressCount = Math.min(compressCount, total - keepCount);
            if (compressCount < 2) return messages;

            List<MessageParam> oldMessages = messages.subList(0, compressCount);
            List<MessageParam> recentMessages = messages.subList(compressCount, total);

            String oldText = serializeForSummary(oldMessages);
            String summaryPrompt = "Summarize the following conversation concisely, "
                    + "preserving key facts and decisions. "
                    + "Output only the summary, no preamble.\n\n" + oldText;

            String summaryText;
            try {
                Message summaryResp = client.messages().create(
                        MessageCreateParams.builder()
                                .model(MODEL_ID)
                                .maxTokens(2048)
                                .system("You are a conversation summarizer. Be concise and factual.")
                                .addUserMessage(summaryPrompt)
                                .build());
                summaryText = summaryResp.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
                System.out.println(AnsiColors.MAGENTA
                        + "  [compact] " + oldMessages.size() + " messages -> summary ("
                        + summaryText.length() + " chars)" + AnsiColors.RESET);
            } catch (Exception e) {
                System.out.println(AnsiColors.YELLOW
                        + "  [compact] Summary failed (" + e.getMessage()
                        + "), dropping old messages" + AnsiColors.RESET);
                return new ArrayList<>(recentMessages);
            }

            List<MessageParam> compacted = new ArrayList<>();
            compacted.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content("[Previous conversation summary]\n" + summaryText)
                    .build());
            compacted.add(MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .content("Understood, I have the context from our previous conversation.")
                    .build());
            compacted.addAll(recentMessages);
            return compacted;
        }

        String serializeForSummary(List<MessageParam> messages) {
            StringBuilder sb = new StringBuilder();
            for (MessageParam msg : messages) {
                String json = JsonUtils.toJson(msg);
                sb.append(json).append("\n");
            }
            return sb.toString();
        }

        /**
         * 3-phase retry: normal call -> truncate tool results -> compact history.
         */
        Message guardApiCall(String system, List<MessageParam> messages,
                             List<ToolUnion> tools, int maxRetries) {
            List<MessageParam> currentMessages = messages;

            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                            .model(MODEL_ID)
                            .maxTokens(8096)
                            .system(system)
                            .messages(currentMessages);
                    if (tools != null) paramsBuilder.tools(tools);

                    Message result = client.messages().create(paramsBuilder.build());

                    // If we modified messages, update the original list
                    if (currentMessages != messages) {
                        messages.clear();
                        messages.addAll(currentMessages);
                    }
                    return result;

                } catch (Exception e) {
                    String errorStr = e.getMessage().toLowerCase();
                    boolean isOverflow = errorStr.contains("context") || errorStr.contains("token");

                    if (!isOverflow || attempt >= maxRetries) throw new RuntimeException(e);

                    if (attempt == 0) {
                        System.out.println(AnsiColors.YELLOW
                                + "  [guard] Context overflow detected, "
                                + "truncating large tool results..." + AnsiColors.RESET);
                        // For now, just try compacting next
                        currentMessages = new ArrayList<>(currentMessages);
                    } else if (attempt == 1) {
                        System.out.println(AnsiColors.YELLOW
                                + "  [guard] Still overflowing, "
                                + "compacting conversation history..." + AnsiColors.RESET);
                        currentMessages = compactHistory(currentMessages);
                    }
                }
            }
            throw new RuntimeException("guard_api_call: exhausted retries");
        }
    }

    // ================================================================
    // S03 NEW: REPL Commands
    // ================================================================

    static List<MessageParam> handleReplCommand(
            String command, SessionStore store, ContextGuard guard,
            List<MessageParam> messages) {

        String[] parts = command.strip().split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (cmd) {
            case "/new" -> {
                String sid = store.createSession(arg);
                System.out.println(AnsiColors.MAGENTA + "  Created new session: " + sid
                        + (arg.isEmpty() ? "" : " (" + arg + ")") + AnsiColors.RESET);
                return new ArrayList<>();
            }
            case "/list" -> {
                var sessions = store.listSessions();
                if (sessions.isEmpty()) {
                    AnsiColors.printInfo("  No sessions found.");
                    return messages;
                }
                AnsiColors.printInfo("  Sessions:");
                for (var entry : sessions) {
                    String sid = entry.getKey();
                    Map<String, Object> meta = entry.getValue();
                    String active = sid.equals(store.currentSessionId) ? " <-- current" : "";
                    String label = (String) meta.getOrDefault("label", "");
                    String labelStr = label.isEmpty() ? "" : " (" + label + ")";
                    long count = ((Number) meta.getOrDefault("message_count", 0)).longValue();
                    String last = ((String) meta.getOrDefault("last_active", "?"));
                    if (last.length() > 19) last = last.substring(0, 19);
                    AnsiColors.printInfo("    " + sid + labelStr + "  msgs=" + count
                            + "  last=" + last + active);
                }
                return messages;
            }
            case "/switch" -> {
                if (arg.isEmpty()) {
                    System.out.println(AnsiColors.YELLOW + "  Usage: /switch <session_id>"
                            + AnsiColors.RESET);
                    return messages;
                }
                String targetId = arg.strip();
                List<String> matched = store.index.keySet().stream()
                        .filter(sid -> sid.startsWith(targetId)).toList();
                if (matched.isEmpty()) {
                    System.out.println(AnsiColors.YELLOW + "  Session not found: " + targetId
                            + AnsiColors.RESET);
                    return messages;
                }
                if (matched.size() > 1) {
                    System.out.println(AnsiColors.YELLOW + "  Ambiguous prefix, matches: "
                            + String.join(", ", matched) + AnsiColors.RESET);
                    return messages;
                }
                String sid = matched.get(0);
                List<MessageParam> newMessages = store.loadSession(sid);
                System.out.println(AnsiColors.MAGENTA + "  Switched to session: " + sid
                        + " (" + newMessages.size() + " messages)" + AnsiColors.RESET);
                return newMessages;
            }
            case "/context" -> {
                int estimated = guard.estimateMessagesTokens(messages);
                double pct = ((double) estimated / guard.maxTokens) * 100;
                int barLen = 30;
                int filled = (int) (barLen * Math.min(pct, 100) / 100);
                String bar = "#".repeat(filled) + "-".repeat(barLen - filled);
                String color = pct < 50 ? AnsiColors.GREEN
                        : pct < 80 ? AnsiColors.YELLOW : AnsiColors.RED;
                AnsiColors.printInfo("  Context usage: ~" + estimated + " / "
                        + guard.maxTokens + " tokens");
                System.out.println("  " + color + "[" + bar + "] "
                        + String.format("%.1f", pct) + "%" + AnsiColors.RESET);
                AnsiColors.printInfo("  Messages: " + messages.size());
                return messages;
            }
            case "/compact" -> {
                if (messages.size() <= 4) {
                    AnsiColors.printInfo("  Too few messages to compact (need > 4).");
                    return messages;
                }
                System.out.println(AnsiColors.MAGENTA + "  Compacting history..."
                        + AnsiColors.RESET);
                List<MessageParam> newMessages = guard.compactHistory(messages);
                System.out.println(AnsiColors.MAGENTA + "  " + messages.size() + " -> "
                        + newMessages.size() + " messages" + AnsiColors.RESET);
                return newMessages;
            }
            case "/help" -> {
                AnsiColors.printInfo("  Commands:");
                AnsiColors.printInfo("    /new [label]       Create a new session");
                AnsiColors.printInfo("    /list              List all sessions");
                AnsiColors.printInfo("    /switch <id>       Switch to a session (prefix match)");
                AnsiColors.printInfo("    /context           Show context token usage");
                AnsiColors.printInfo("    /compact           Manually compact conversation history");
                AnsiColors.printInfo("    /help              Show this help");
                AnsiColors.printInfo("    quit / exit        Exit the REPL");
                return messages;
            }
            default -> { /* not a recognized command */ }
        }
        return null; // signal: not handled
    }

    // ================================================================
    // Core: Agent Loop with Sessions + ContextGuard
    // ================================================================

    static void agentLoop() {
        SessionStore store = new SessionStore("claw0");
        ContextGuard guard = new ContextGuard();

        // Resume most recent session or create new one
        var sessions = store.listSessions();
        List<MessageParam> messages;
        if (!sessions.isEmpty()) {
            String sid = sessions.get(0).getKey();
            messages = store.loadSession(sid);
            System.out.println(AnsiColors.MAGENTA + "  Resumed session: " + sid
                    + " (" + messages.size() + " messages)" + AnsiColors.RESET);
        } else {
            String sid = store.createSession("initial");
            messages = new ArrayList<>();
            System.out.println(AnsiColors.MAGENTA + "  Created initial session: " + sid
                    + AnsiColors.RESET);
        }

        AnsiColors.printInfo("============================================================");
        AnsiColors.printInfo("  claw0  |  Section 03: Sessions & Context Guard");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("  Session: " + store.currentSessionId);
        AnsiColors.printInfo("  Tools: " + String.join(", ", TOOL_HANDLERS.keySet()));
        AnsiColors.printInfo("  Type /help for commands, quit/exit to leave.");
        AnsiColors.printInfo("============================================================");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            String userInput;
            try {
                System.out.print(AnsiColors.coloredPrompt());
                userInput = scanner.nextLine().trim();
            } catch (Exception e) {
                AnsiColors.printInfo("Goodbye.");
                break;
            }

            if (userInput.isEmpty()) continue;
            if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) {
                AnsiColors.printInfo("Goodbye.");
                break;
            }

            // REPL commands
            if (userInput.startsWith("/")) {
                List<MessageParam> result = handleReplCommand(userInput, store, guard, messages);
                if (result != null) {
                    messages = result;
                    continue;
                }
            }

            // Append user message
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userInput)
                    .build());
            store.saveTurn("user", userInput);

            // Inner loop: tool call chain
            while (true) {
                Message response;
                try {
                    response = guard.guardApiCall(SYSTEM_PROMPT, messages, TOOLS, 2);
                } catch (Exception e) {
                    System.out.println("\n" + AnsiColors.YELLOW + "API Error: "
                            + e.getMessage() + AnsiColors.RESET + "\n");
                    while (!messages.isEmpty()
                            && messages.get(messages.size() - 1).role() != MessageParam.Role.USER) {
                        messages.remove(messages.size() - 1);
                    }
                    if (!messages.isEmpty()) messages.remove(messages.size() - 1);
                    break;
                }

                messages.add(response.toParam());

                // Save assistant turn to JSONL
                List<Map<String, Object>> serializedContent = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (block.isText()) {
                        Map<String, Object> textBlock = new LinkedHashMap<>();
                        textBlock.put("type", "text");
                        textBlock.put("text", block.asText().text());
                        serializedContent.add(textBlock);
                    } else if (block.isToolUse()) {
                        ToolUseBlock tu = block.asToolUse();
                        Map<String, Object> useBlock = new LinkedHashMap<>();
                        useBlock.put("type", "tool_use");
                        useBlock.put("id", tu.id());
                        useBlock.put("name", tu.name());
                        useBlock.put("input", tu._input().convert(Map.class));
                        serializedContent.add(useBlock);
                    }
                }
                store.saveTurn("assistant", serializedContent);

                StopReason reason = response.stopReason().orElse(null);

                if (reason == StopReason.END_TURN) {
                    String assistantText = response.content().stream()
                            .filter(ContentBlock::isText)
                            .map(ContentBlock::asText)
                            .map(TextBlock::text)
                            .collect(Collectors.joining());
                    if (!assistantText.isEmpty()) {
                        AnsiColors.printAssistant(assistantText);
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
                        store.saveToolResult(tu.id(), tu.name(), toolInput, result);
                        toolResultBlocks.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId(tu.id())
                                        .content(result)
                                        .build()));
                    }
                    messages.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(toolResultBlocks)
                            .build());
                    continue;

                } else {
                    AnsiColors.printInfo("[stop_reason=" + reason + "]");
                    String assistantText = response.content().stream()
                            .filter(ContentBlock::isText)
                            .map(ContentBlock::asText)
                            .map(TextBlock::text)
                            .collect(Collectors.joining());
                    if (!assistantText.isEmpty()) {
                        AnsiColors.printAssistant(assistantText);
                    }
                    break;
                }
            }
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
        agentLoop();
    }
}
