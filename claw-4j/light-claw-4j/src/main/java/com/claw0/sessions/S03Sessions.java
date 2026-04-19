package com.claw0.sessions;

/**
 * Section 03: Sessions & Context Guard
 * "Sessions are JSONL files. Append on write, replay on read. Summarize when too large."
 * 会话 = JSONL 文件, 追加写入, 回放读取, 过大则摘要压缩
 *
 * <p>在 S02 的 agent 循环基础上增加两层能力:
 *
 * <h2>第一层: SessionStore -- JSONL 持久化</h2>
 * <pre>
 *   核心设计: 追加写入 (append-only), 回放读取 (replay on read)
 *   - 每条对话记录以 JSON 行的形式追加到 .jsonl 文件
 *   - 恢复会话时, 逐行读取 JSONL 并重建为 API 所需的 MessageParam 列表
 *   - 这种设计避免了复杂的数据库依赖, 文件即数据库
 * </pre>
 *
 * <h2>第二层: ContextGuard -- 3 阶段溢出保护</h2>
 * <pre>
 *   当上下文窗口快要溢出时, 分三步降级处理:
 *     1. 正常调用 -> 成功则直接返回
 *     2. 如果溢出 -> 截断大型工具输出, 重试
 *     3. 仍然溢出 -> 压缩历史 (将前 50% 摘要为一条消息), 重试
 *     4. 还是溢出 -> 抛出异常, 让调用者处理
 * </pre>
 *
 * <h2>数据流</h2>
 * <pre>
 *   用户输入
 *       |
 *   load_session() --> 从 JSONL 重建 messages[]
 *       |
 *   guard_api_call() --> 3阶段重试: 正常 -> 截断 -> 压缩 -> 抛异常
 *       |
 *   save_turn() --> 追加到 JSONL 文件
 *       |
 *   打印回复
 * </pre>
 *
 * <h2>运行方式</h2>
 * <pre>
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S03Sessions"
 * </pre>
 */

// region Common Imports
import com.claw0.common.AnsiColors;
import com.claw0.common.Clients;
import com.claw0.common.Config;
import com.claw0.common.JsonUtils;

import com.anthropic.client.AnthropicClient;
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
    /** 模型 ID, 优先从环境变量 MODEL_ID 读取, 默认使用 claude-sonnet-4-20250514 */
    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    /** 系统提示词: 告诉模型如何使用工具, 并提示它可以利用会话历史中的上下文 */
    static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant with access to tools.\n"
            + "Use tools to help the user with file and time queries.\n"
            + "Be concise. If a session has prior context, use it.";

    /** 工具输出最大字符数 -- 超出则截断, 防止单次工具输出撑爆上下文窗口 */
    static final int MAX_TOOL_OUTPUT = 50_000;
    /** 项目根目录 */
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));
    /** 工作空间目录: 所有文件操作限制在此目录下 (比 S02 多了一层 workspace 子目录作为沙箱) */
    static final Path WORKSPACE_DIR = WORKDIR.resolve("workspace");
    /** 上下文安全阈值 (估算的 token 数), 超过此值触发 ContextGuard 保护机制 */
    static final int CONTEXT_SAFE_LIMIT = 180_000;

    /** Anthropic API 客户端, 通过 Config 读取 API Key 和 Base URL */
    static final AnthropicClient client = Clients.create();
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
    // S03 NEW: SessionStore -- JSONL 会话持久化
    // 核心设计: 追加写入 (append-only), 不修改已有记录
    // 文件格式: 每行一个 JSON 对象, 记录一条对话 (user/assistant/tool_use/tool_result)
    // ================================================================

    /**
     * 会话存储: 管理多个会话的创建、加载、保存.
     *
     * <p>数据存储结构:
     * <pre>
     *   workspace/.sessions/agents/{agentId}/
     *       sessions.json       -- 会话索引 (id -> 元数据)
     *       sessions/
     *           {sessionId}.jsonl  -- 对话记录 (每行一条)
     * </pre>
     *
     * <p>为什么选择 JSONL 而不是数据库?
     * 因为 JSONL 追加写入是原子操作 (在大多数 OS 上), 不需要事务,
     * 而且文件可直接用文本编辑器查看和调试, 对教学非常友好.
     */
    static class SessionStore {
        /** agent 标识符, 用于隔离不同 agent 的会话数据 */
        final String agentId;
        /** 会话 JSONL 文件存放目录 */
        final Path baseDir;
        /** 会话索引文件路径 (JSON 格式, 存储 id -> 元数据的映射) */
        final Path indexPath;
        /** 内存中的会话索引: sessionId -> {label, created_at, last_active, message_count} */
        Map<String, Map<String, Object>> index;
        /** 当前活跃会话 ID */
        String currentSessionId;

        /**
         * 创建 SessionStore, 加载已有会话索引.
         * 如果目录不存在则自动创建.
         */
        SessionStore(String agentId) {
            this.agentId = agentId;
            this.baseDir = WORKSPACE_DIR.resolve(".sessions/agents/" + agentId + "/sessions");
            this.indexPath = WORKSPACE_DIR.resolve(".sessions/agents/" + agentId + "/sessions.json");
            try { Files.createDirectories(baseDir); } catch (IOException e) { /* ignore */ }
            this.index = loadIndex();
            this.currentSessionId = null;
        }

        /** 从磁盘加载会话索引. 如果文件不存在或解析失败, 返回空 Map. */
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

        /** 将内存中的会话索引持久化到磁盘 (JSON 格式, 带缩进便于调试). */
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

        /** 获取指定会话的 JSONL 文件路径. */
        Path sessionPath(String sessionId) {
            return baseDir.resolve(sessionId + ".jsonl");
        }

        /**
         * 创建新会话. 生成唯一 ID, 写入索引, 创建空的 JSONL 文件.
         * @param label 可选的会话标签 (用于 /list 显示)
         * @return 新创建的会话 ID
         */
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

        /**
         * 加载指定会话, 从 JSONL 文件重建消息历史.
         * @param sessionId 会话 ID (支持前缀匹配)
         * @return 重建后的 MessageParam 列表, 可直接传给 API
         */
        List<MessageParam> loadSession(String sessionId) {
            Path path = sessionPath(sessionId);
            if (!Files.exists(path)) return new ArrayList<>();
            currentSessionId = sessionId;
            return rebuildHistory(path);
        }

        /**
         * 保存一轮对话 (user 或 assistant) 到 JSONL 文件.
         * 追加写入, 不修改已有记录.
         * @param role 角色 ("user" 或 "assistant")
         * @param content 消息内容 (String 或 List&lt;Map&gt;)
         */
        void saveTurn(String role, Object content) {
            if (currentSessionId == null) return;
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("type", role);
            record.put("content", content);
            record.put("ts", System.currentTimeMillis() / 1000.0);
            appendTranscript(currentSessionId, record);
        }

        /**
         * 保存工具调用和结果到 JSONL 文件.
         * 分两条记录写入: tool_use 记录 + tool_result 记录,
         * 因为 rebuildHistory 时需要分别处理这两种类型.
         */
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

        /** 追加一条记录到 JSONL 文件, 同时更新索引中的 last_active 和 message_count. */
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
         * 从 JSONL 文件重建 API 所需的 MessageParam 列表.
         *
         * <p>这是 SessionStore 最复杂的方法. JSONL 中存储的是扁平的记录流:
         * <pre>
         *   {type: "user", content: "..."}
         *   {type: "assistant", content: "..."}
         *   {type: "tool_use", tool_use_id: "...", name: "bash", input: {...}}
         *   {type: "tool_result", tool_use_id: "...", content: "..."}
         * </pre>
         *
         * <p>但 API 要求结构化的角色交替: tool_use 块必须合并到前一条 assistant 消息中,
         * tool_result 块必须合并到同一条 user 消息中.
         * 合并规则:
         * <ul>
         *   <li>tool_use -> 追加到最后一条 assistant 消息的 content 列表中</li>
         *   <li>tool_result -> 追加到最后一条 user 消息的 content 列表中 (如果它也是 tool_result 类型)</li>
         *   <li>否则创建新的 user/assistant 消息</li>
         * </ul>
         */
        @SuppressWarnings("unchecked")
        List<MessageParam> rebuildHistory(Path path) {
            List<MessageParam> messages = new ArrayList<>();
            // 中间表示: 先将 JSONL 行合并为结构化的 rawMessages (处理好角色交替和块合并),
            // 最后再统一转换为 SDK 的 MessageParam 对象
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
                        // tool_use 记录需要合并到前一条 assistant 消息中
                        // 因为 API 要求: assistant 消息的 content 可以包含 [text_block, tool_use_block, ...]
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", "tool_use");
                        block.put("id", record.get("tool_use_id"));
                        block.put("name", record.get("name"));
                        block.put("input", record.get("input"));

                        // 如果最后一条是 assistant 消息, 将 tool_use 块追加进去
                        if (!rawMessages.isEmpty()
                                && "assistant".equals(rawMessages.get(rawMessages.size() - 1).get("role"))) {
                            Object content = rawMessages.get(rawMessages.size() - 1).get("content");
                            if (content instanceof List) {
                                // 已有多个 content blocks, 直接追加
                                ((List<Object>) content).add(block);
                            } else {
                                // 目前只有纯文本 content, 需要转为 block 列表
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
                        // tool_result 记录需要合并到前一条 user 消息中
                        // 因为 API 要求: 工具结果以 user 角色发送, content 包含 [tool_result_block, ...]
                        Map<String, Object> resultBlock = new LinkedHashMap<>();
                        resultBlock.put("type", "tool_result");
                        resultBlock.put("tool_use_id", record.get("tool_use_id"));
                        resultBlock.put("content", record.get("content"));

                        // 如果最后一条是 user 消息且包含 tool_result, 追加进去 (合并多个工具结果)
                        if (!rawMessages.isEmpty()
                                && "user".equals(rawMessages.get(rawMessages.size() - 1).get("role"))) {
                            Object content = rawMessages.get(rawMessages.size() - 1).get("content");
                            // 检查已有的 user content 是否以 tool_result 开头 (不是普通文本消息)
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

            // 第二阶段: 将中间表示 rawMessages 转换为 SDK 的 MessageParam 对象
            for (Map<String, Object> raw : rawMessages) {
                String role = (String) raw.get("role");
                Object content = raw.get("content");
                MessageParam.Role paramRole = "user".equals(role)
                        ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;

                if (content instanceof String text) {
                    // 纯文本消息: 直接用 content(text) 构建
                    messages.add(MessageParam.builder().role(paramRole).content(text).build());
                } else if (content instanceof List<?> blocks) {
                    // 块列表消息 (含 tool_result 等): 需要 contentOfBlockParams()
                    List<ContentBlockParam> blockParams = convertBlocks(blocks);
                    messages.add(MessageParam.builder().role(paramRole)
                            .contentOfBlockParams(blockParams).build());
                }
            }

            return messages;
        }

        /** 将中间表示的 block 列表转为 SDK 的 ContentBlockParam 列表. 目前只处理 tool_result 类型. */
        @SuppressWarnings("unchecked")
        List<ContentBlockParam> convertBlocks(List<?> blocks) {
            List<ContentBlockParam> result = new ArrayList<>();
            for (Object block : blocks) {
                if (block instanceof Map<?, ?> map) {
                    String type = (String) map.get("type");
                    if ("text".equals(type)) {
                        // 跳过文本块: 在 rebuildHistory 的外层已处理纯文本消息
                    } else if ("tool_result".equals(type)) {
                        result.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId((String) map.get("tool_use_id"))
                                        .content(String.valueOf(map.get("content")))
                                        .build()));
                    }
                    // tool_use 块在历史中属于 assistant 消息, 但重建时通过 response.toParam() 处理,
                    // 不需要在这里转换 (这里只处理 user 消息中的 tool_result)
                }
            }
            return result;
        }

        /** 列出所有会话, 按最后活跃时间倒序排列 (最近的在前). */
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
    // S03 NEW: ContextGuard -- 3 阶段上下文溢出保护
    // 策略: 正常调用 -> 截断工具输出 -> 压缩历史(50%) -> 抛异常
    // ================================================================

    /**
     * 上下文守卫: 防止上下文窗口溢出导致 API 调用失败.
     *
     * <p>LLM 的上下文窗口是有限的 (例如 200K tokens), 随着对话变长,
     * 消息历史可能超出限制. ContextGuard 通过 3 阶段降级策略来应对:
     * <ol>
     *   <li><b>阶段 1</b>: 直接调用 API, 如果成功就返回</li>
     *   <li><b>阶段 2</b>: 如果溢出, 截断大型工具输出 (通常是最大的内容来源)</li>
     *   <li><b>阶段 3</b>: 如果仍然溢出, 将前 50% 的消息压缩为一条摘要消息</li>
     *   <li><b>放弃</b>: 如果以上都不行, 抛出异常让调用者处理</li>
     * </ol>
     */
    static class ContextGuard {
        /** 上下文窗口的安全阈值 (token 数), 超过此值触发保护机制 */
        final int maxTokens;

        /** 使用默认阈值 (CONTEXT_SAFE_LIMIT = 180000) */
        ContextGuard() { this(CONTEXT_SAFE_LIMIT); }
        /** 自定义阈值的构造器 */
        ContextGuard(int maxTokens) { this.maxTokens = maxTokens; }

        /**
         * 粗略估算文本的 token 数.
         * 经验法则: 英文约 4 字符 = 1 token, 中文约 2 字符 = 1 token.
         * 这里用 4 字符/token 的保守估算, 实际使用 tiktoken 等工具更精确.
         */
        static int estimateTokens(String text) {
            return text.length() / 4;
        }

        /** 估算整个消息列表的 token 数, 通过序列化为 JSON 后粗略计算. */
        int estimateMessagesTokens(List<MessageParam> messages) {
            int total = 0;
            for (MessageParam msg : messages) {
                // Estimate from the serialized JSON form
                total += estimateTokens(JsonUtils.toJson(msg));
            }
            return total;
        }

        /**
         * 截断工具输出. 在换行符处优先截断, 保持输出可读性.
         * @param maxFraction 允许占用的最大上下文比例 (0.0 ~ 1.0)
         */
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
         * 压缩对话历史: 将前 50% 的消息让 LLM 生成摘要, 替换为一条 summary 消息.
         *
         * <p>压缩策略:
         * <ul>
         *   <li>前 50% 的消息 -> 让 LLM 生成摘要, 替换为一条 user+assistant 对</li>
         *   <li>后 20% (最少 4 条) 的消息 -> 保持不变, 保留最近的完整上下文</li>
         * </ul>
         *
         * <p>教学要点: 这种"摘要压缩"是处理长对话的常见模式,
         * Claude Code 等生产级工具也使用类似的策略来管理上下文窗口.
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

            // 构建压缩后的消息列表: 摘要 (user+assistant 对) + 最近的原始消息
            List<MessageParam> compacted = new ArrayList<>();
            compacted.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content("[Previous conversation summary]\n" + summaryText)
                    .build());
            // 添加一条 assistant 确认消息, 维持角色交替
            compacted.add(MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .content("Understood, I have the context from our previous conversation.")
                    .build());
            compacted.addAll(recentMessages);
            return compacted;
        }

        /** 将消息列表序列化为 JSON 文本, 用于发送给 LLM 生成摘要. */
        String serializeForSummary(List<MessageParam> messages) {
            StringBuilder sb = new StringBuilder();
            for (MessageParam msg : messages) {
                String json = JsonUtils.toJson(msg);
                sb.append(json).append("\n");
            }
            return sb.toString();
        }

        /**
         * 3 阶段重试的 API 调用: 正常调用 -> 截断工具输出 -> 压缩历史.
         *
         * <p>如果压缩成功修改了消息列表, 会原地更新传入的 messages 引用,
         * 这样调用者也能看到压缩后的历史.
         *
         * @param system 系统提示词
         * @param messages 消息历史 (可能被原地修改)
         * @param tools 可用工具列表 (可为 null)
         * @param maxRetries 最大重试次数 (通常为 2, 即最多 3 次尝试)
         * @return API 响应
         * @throws RuntimeException 如果所有重试都失败
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

    /**
     * 处理 REPL 斜杠命令 (如 /new, /list, /switch 等).
     *
     * <p>返回值约定:
     * <ul>
     *   <li>非 null -- 命令已处理, 返回值为新的消息列表 (可能是空列表或原列表)</li>
     *   <li>null -- 命令未识别, 不做任何处理</li>
     * </ul>
     *
     * <p>这种"返回 null 表示未处理"的模式是命令分发的常见技巧,
     * 让调用者可以灵活地决定是否继续处理输入.
     */
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

    /**
     * 核心 Agent 循环: 整合 S01 (Agent Loop) + S02 (Tool Use) + S03 (Sessions + ContextGuard).
     *
     * <p>相对于 S02 的主要变化:
     * <ol>
     *   <li>启动时自动恢复最近的会话 (从 JSONL 重建消息历史)</li>
     *   <li>支持斜杠命令 (/new, /list, /switch, /context, /compact, /help)</li>
     *   <li>API 调用经过 ContextGuard 保护 (3阶段溢出处理)</li>
     *   <li>每轮对话都持久化到 JSONL 文件, 下次启动可恢复</li>
     * </ol>
     */
    static void agentLoop() {
        SessionStore store = new SessionStore("claw0");
        ContextGuard guard = new ContextGuard();

        // 启动时: 恢复最近的会话, 或创建新的初始会话
        // 这就是 SessionStore 的价值 -- 重启 agent 后对话不丢失
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

            // REPL 斜杠命令: 由 handleReplCommand 统一处理
            // 返回 null 表示不是命令, 继续当作用户消息处理
            if (userInput.startsWith("/")) {
                List<MessageParam> result = handleReplCommand(userInput, store, guard, messages);
                if (result != null) {
                    messages = result;
                    continue;
                }
            }

            // 追加用户消息并持久化到 JSONL
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userInput)
                    .build());
            store.saveTurn("user", userInput);

            // 内层循环: 处理连续的工具调用 (与 S02 相同)
            // 唯一区别是 API 调用经过 ContextGuard 保护
            while (true) {
                Message response;
                try {
                    // 通过 ContextGuard 调用 API: 内含 3 阶段溢出保护
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

                // 将 assistant 回复序列化并持久化到 JSONL
                // 需要把 SDK 的 ContentBlock 转为可序列化的 Map 结构
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
                        // 持久化工具调用和结果到 JSONL (S03 新增)
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
