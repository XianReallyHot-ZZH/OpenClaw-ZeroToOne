package com.claw0.sessions;

/**
 * Section 02: Tool Use —— 给模型装上手
 * "Give the model hands"
 * 给模型装上手
 *
 * <p>Agent 循环本身不变 -- 只是增加了一个工具分发表.
 * 当 stop_reason == "tool_use" 时, 在 TOOL_HANDLERS 中查找处理函数,
 * 执行工具, 将结果塞回消息历史, 继续循环. 就这么简单.
 *
 * <h2>架构总览</h2>
 * <pre>
 *   用户输入 --> LLM API --> stop_reason == "tool_use"?
 *                                |
 *                        TOOL_HANDLERS[name](input)
 *                                |
 *                        tool_result --> 送回 LLM
 *                                |
 *                         stop_reason == "end_turn"?
 *                                |
 *                             打印回复
 * </pre>
 *
 * <h2>工具列表</h2>
 * <ul>
 *   <li><b>bash</b>: 执行 shell 命令</li>
 *   <li><b>read_file</b>: 读取文件内容</li>
 *   <li><b>write_file</b>: 写入文件</li>
 *   <li><b>edit_file</b>: 精确字符串替换 (与 Claude Code 的 edit 行为一致)</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S02ToolUse"
 * </pre>
 */

// region Common Imports
import com.claw0.common.AnsiColors;
import com.claw0.common.Clients;
import com.claw0.common.Config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.Model;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class S02ToolUse {

    // region Configuration
    /** 模型 ID, 可通过环境变量 MODEL_ID 覆盖 */
    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    /**
     * 系统提示词: 告诉模型如何使用工具.
     * 关键指令: "先读后写" (Always read before edit), "精确匹配" (EXACT match)
     */
    static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant with access to tools.\n"
            + "Use the tools to help the user with file operations and shell commands.\n"
            + "Always read a file before editing it.\n"
            + "When using edit_file, the old_string must match EXACTLY (including whitespace).";

    /** 工具输出最大字符数 -- 超出则截断, 防止单次工具输出撑爆上下文窗口 */
    static final int MAX_TOOL_OUTPUT = 50_000;
    /** 工作目录: 所有文件操作都限制在此目录下 (安全沙箱) */
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));

    /** Anthropic API 客户端, 通过 Config 读取 API Key 和 Base URL */
    static final AnthropicClient client = Clients.create();
    // endregion

    // ================================================================
    // Safety Helpers
    // ================================================================

    /**
     * Resolve a raw path to a safe absolute path within WORKDIR.
     * Prevents path traversal attacks.
     */
    static Path safePath(String raw) {
        Path target = WORKDIR.resolve(raw).normalize().toAbsolutePath();
        Path workdirAbs = WORKDIR.toAbsolutePath().normalize();
        if (!target.startsWith(workdirAbs)) {
            throw new IllegalArgumentException(
                    "Path traversal blocked: " + raw + " resolves outside WORKDIR");
        }
        return target;
    }

    /**
     * Truncate text exceeding the limit, appending a notice.
     */
    static String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        return text.substring(0, limit) + "\n... [truncated, " + text.length() + " total chars]";
    }

    static String truncate(String text) {
        return truncate(text, MAX_TOOL_OUTPUT);
    }

    // ================================================================
    // Tool Implementations
    // ================================================================

    /** 危险命令黑名单 -- 这是教学级安全检查, 生产环境应使用容器沙箱 (如 Docker) */
    static final List<String> DANGEROUS_COMMANDS = List.of(
            "rm -rf /", "mkfs", "> /dev/sd", "dd if="
    );

    /**
     * 执行 shell 命令并返回输出. 包含超时控制和危险命令过滤.
     *
     * <p>关键设计决策:
     * <ul>
     *   <li>redirectErrorStream(true) 合并 stderr 到 stdout, 避免分别读取时的死锁</li>
     *   <li>异步读取进程输出, 避免 waitFor() 和 readAllBytes() 互相阻塞</li>
     *   <li>超时后 destroyForcibly() 发送 SIGKILL 强制终止</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    static String toolBash(Map<String, Object> input) {
        String command = (String) input.get("command");
        int timeout = input.containsKey("timeout")
                ? ((Number) input.get("timeout")).intValue()
                : 30;

        for (String pattern : DANGEROUS_COMMANDS) {
            if (command.contains(pattern)) {
                return "Error: Refused to run dangerous command containing '" + pattern + "'";
            }
        }

        AnsiColors.printTool("bash", command);
        try {
            // redirectErrorStream(true) 将 stderr 合并到 stdout, 避免分别读取时的死锁问题
            // 原因: 如果 stderr 和 stdout 分开缓冲, 一方满了会阻塞, 而另一方也在等读取
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command)
                    .directory(WORKDIR.toFile())
                    .redirectErrorStream(true);
            Process process = pb.start();

            // 异步读取进程输出 -- 避免 waitFor() 和 readAllBytes() 之间的死锁:
            // 如果进程输出缓冲区满了, 它会阻塞等待读取, 而 waitFor 又在等进程结束
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes());
                } catch (IOException e) {
                    return "Error reading output: " + e.getMessage();
                }
            });

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            String output = outputFuture.get();

            // 超时后强制终止进程: destroyForcibly() 发送 SIGKILL (不可捕获, 进程立即终止)
            if (!finished) {
                process.destroyForcibly();
                return "Error: Command timed out after " + timeout + "s";
            }

            if (output.isEmpty()) {
                output = "[no output]";
            }
            if (process.exitValue() != 0) {
                output += "\n[exit code: " + process.exitValue() + "]";
            }
            return truncate(output);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    static String toolReadFile(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        AnsiColors.printTool("read_file", filePath);
        try {
            Path target = safePath(filePath);
            if (!Files.exists(target)) return "Error: File not found: " + filePath;
            if (!Files.isRegularFile(target)) return "Error: Not a file: " + filePath;
            String content = Files.readString(target);
            return truncate(content);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    static String toolWriteFile(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        String content = (String) input.get("content");
        AnsiColors.printTool("write_file", filePath);
        try {
            Path target = safePath(filePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return "Successfully wrote " + content.length() + " chars to " + filePath;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 精确字符串替换工具. 要求 old_string 在文件中唯一匹配 (与 Claude Code 的 edit 行为一致).
     *
     * <p>设计理念: 为什么要求"唯一匹配"而不是"全部替换"?
     * 因为 AI 生成替换时, 全部替换容易误伤相似代码. 唯一匹配更安全.
     * 如果不唯一, 模型需要提供更多上下文来精确定位目标位置.
     */
    static String toolEditFile(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        String oldString = (String) input.get("old_string");
        String newString = (String) input.get("new_string");
        AnsiColors.printTool("edit_file", filePath + " (replace " + oldString.length() + " chars)");
        try {
            Path target = safePath(filePath);
            if (!Files.exists(target)) return "Error: File not found: " + filePath;

            String content = Files.readString(target);
            // 统计匹配次数: 0次=找不到, 1次=替换, >1次=不唯一拒绝执行
            int count = countOccurrences(content, oldString);

            if (count == 0) {
                return "Error: old_string not found in file. Make sure it matches exactly.";
            }
            if (count > 1) {
                return "Error: old_string found " + count + " times. "
                       + "It must be unique. Provide more surrounding context.";
            }

            // Use literal replacement (Pattern.quote to avoid regex interpretation)
            String newContent = content.replaceFirst(
                    Pattern.quote(oldString), Matcher.quoteReplacement(newString));
            Files.writeString(target, newContent);
            return "Successfully edited " + filePath;
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /** 统计子字符串在文本中出现的非重叠次数. 用于确保 old_string 唯一匹配. */
    static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    // ================================================================
    // Tool Schema Definitions
    // ================================================================

    /**
     * 构建 SDK 的 Tool 对象.
     * 将自定义的 properties Map 转为 SDK 要求的 JsonValue 格式,
     * 因为 Anthropic SDK 的 InputSchema API 设计较为复杂 --
     * 它要求用 putAdditionalProperty + JsonValue.from() 来定义每个参数,
     * 而不是直接传 JSON 字符串.
     */
    static Tool buildTool(String name, String description,
                          Map<String, Map<String, String>> properties,
                          List<String> required) {
        Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
        properties.forEach((key, value) ->
                propsBuilder.putAdditionalProperty(key, JsonValue.from(value)));

        Tool.InputSchema schema = Tool.InputSchema.builder()
                .type(JsonValue.from("object"))
                .properties(propsBuilder.build())
                .required(required)
                .build();

        return Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(schema)
                .build();
    }

    static final List<ToolUnion> TOOLS = List.of(
            ToolUnion.ofTool(buildTool("bash",
                    "Run a shell command and return its output. Use for system commands, git, package managers, etc.",
                    Map.of(
                            "command", Map.of("type", "string", "description", "The shell command to execute."),
                            "timeout", Map.of("type", "integer", "description", "Timeout in seconds. Default 30.")
                    ),
                    List.of("command"))),
            ToolUnion.ofTool(buildTool("read_file",
                    "Read the contents of a file.",
                    Map.of(
                            "file_path", Map.of("type", "string", "description", "Path to the file (relative to working directory).")
                    ),
                    List.of("file_path"))),
            ToolUnion.ofTool(buildTool("write_file",
                    "Write content to a file. Creates parent directories if needed. Overwrites existing content.",
                    Map.of(
                            "file_path", Map.of("type", "string", "description", "Path to the file (relative to working directory)."),
                            "content", Map.of("type", "string", "description", "The content to write.")
                    ),
                    List.of("file_path", "content"))),
            ToolUnion.ofTool(buildTool("edit_file",
                    "Replace an exact string in a file with a new string. The old_string must appear exactly once in the file. Always read the file first to get the exact text to replace.",
                    Map.of(
                            "file_path", Map.of("type", "string", "description", "Path to the file (relative to working directory)."),
                            "old_string", Map.of("type", "string", "description", "The exact text to find and replace. Must be unique."),
                            "new_string", Map.of("type", "string", "description", "The replacement text.")
                    ),
                    List.of("file_path", "old_string", "new_string")))
    );

    // ================================================================
    // Tool Dispatch Table
    // ================================================================

    // 工具分发表: 工具名 -> 处理函数. 使用 LinkedHashMap 保持注册顺序
    static final Map<String, Function<Map<String, Object>, String>> TOOL_HANDLERS = new LinkedHashMap<>();
    static {
        TOOL_HANDLERS.put("bash", S02ToolUse::toolBash);
        TOOL_HANDLERS.put("read_file", S02ToolUse::toolReadFile);
        TOOL_HANDLERS.put("write_file", S02ToolUse::toolWriteFile);
        TOOL_HANDLERS.put("edit_file", S02ToolUse::toolEditFile);
    }

    /**
     * Dispatch a tool call by name with the given input map.
     */
    static String processToolCall(String toolName, Map<String, Object> toolInput) {
        var handler = TOOL_HANDLERS.get(toolName);
        if (handler == null) return "Error: Unknown tool '" + toolName + "'";
        try {
            return handler.apply(toolInput);
        } catch (Exception e) {
            return "Error: " + toolName + " failed: " + e.getMessage();
        }
    }

    // ================================================================
    // Core: Agent Loop with Tool Dispatch
    // ================================================================

    /**
     * 带 Tool 分发的 Agent 循环 -- 在 S01 基础上增加了工具调用能力.
     *
     * <p>与 S01 的关键区别:
     * <ol>
     *   <li>API 调用新增 tools=TOOLS 参数, 告诉模型可以使用哪些工具</li>
     *   <li>stop_reason == "tool_use" 时, 触发工具执行并将结果送回模型</li>
     *   <li>内层 while 循环处理连续的工具调用 (模型可能一次请求多个工具, 全部执行后再送回)</li>
     * </ol>
     *
     * <p>循环结构本身不变 -- Agent 的本质就是: while 循环 + 分发表.
     */
    static void agentLoop() {
        List<MessageParam> messages = new ArrayList<>();

        AnsiColors.printInfo("============================================================");
        AnsiColors.printInfo("  claw0  |  Section 02: Tool Use");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("  Workdir: " + WORKDIR);
        AnsiColors.printInfo("  Tools: " + String.join(", ", TOOL_HANDLERS.keySet()));
        AnsiColors.printInfo("  Type 'quit' or 'exit' to leave. Ctrl+C also works.");
        AnsiColors.printInfo("============================================================");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            // --- Step 1: Get user input ---
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

            // --- Step 2: 将用户消息追加到历史列表 ---
            // API 要求严格的 user/assistant 角色交替, 所以必须按顺序追加
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userInput)
                    .build());

            // --- Step 3: agent 内循环 ---
            // 处理连续的工具调用: 模型可能一次返回多个工具调用, 全部执行后再把结果送回模型.
            // 这个内循环是 S02 相对于 S01 的核心新增部分.
            while (true) {
                Message response;
                try {
                    MessageCreateParams params = MessageCreateParams.builder()
                            .model(MODEL_ID)
                            .maxTokens(8096)
                            .system(SYSTEM_PROMPT)
                            .tools(TOOLS)
                            .messages(messages)
                            .build();
                    response = client.messages().create(params);
                } catch (Exception e) {
                    System.out.println("\n" + AnsiColors.YELLOW
                            + "API Error: " + e.getMessage() + AnsiColors.RESET + "\n");
                    // API 异常时回滚: 移除刚添加的用户消息及后续消息,
                    // 避免下次重试时历史中出现孤立的 user 消息 (API 要求角色交替)
                    while (!messages.isEmpty()
                            && messages.get(messages.size() - 1).role() != MessageParam.Role.USER) {
                        messages.remove(messages.size() - 1);
                    }
                    if (!messages.isEmpty()) messages.remove(messages.size() - 1);
                    break;
                }

                // 将 API 响应转为 MessageParam 并追加到历史 --
                // response.toParam() 是保持对话状态的关键: 它保留完整的 content blocks (文本 + 工具调用)
                messages.add(response.toParam());

                // stop_reason 是 Claude 告诉你"我做完了吗"的方式:
                //   end_turn  = 正常结束, 模型认为已经完整回答了用户的问题
                //   tool_use  = 模型想调用工具, 需要执行工具并把结果送回
                StopReason reason = response.stopReason().orElse(null);

                if (StopReason.END_TURN.equals(reason)) {
                    String assistantText = response.content().stream()
                            .filter(ContentBlock::isText)
                            .map(ContentBlock::asText)
                            .map(TextBlock::text)
                            .collect(Collectors.joining());
                    if (!assistantText.isEmpty()) {
                        AnsiColors.printAssistant(assistantText);
                    }
                    break;

                } else if (StopReason.TOOL_USE.equals(reason)) {
                    // stop_reason == tool_use: 模型请求执行工具
                    // 注意: 工具结果必须作为 USER 角色消息发送 (API 要求),
                    // 因为从模型的视角看, 工具结果就像"用户提供了新信息"
                    // Collect tool results from all ToolUseBlocks
                    List<ContentBlockParam> toolResultBlocks = new ArrayList<>();
                    for (ContentBlock block : response.content()) {
                        if (!block.isToolUse()) continue;
                        ToolUseBlock toolUse = block.asToolUse();

                        // Extract input as Map
                        @SuppressWarnings("unchecked")
                        Map<String, Object> toolInput = toolUse._input()
                                .convert(Map.class);
                        String result = processToolCall(toolUse.name(), toolInput);

                        toolResultBlocks.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId(toolUse.id())
                                        .content(result)
                                        .build()));
                    }

                    // 将工具结果作为 user 消息追加 -- API 协议要求工具结果放在 user 角色中
                    // 每个 tool_result 通过 tool_use_id 与对应的 tool_use 关联
                    messages.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(toolResultBlocks)
                            .build());
                    // 继续内循环: 模型看到工具结果后, 会决定是继续调用工具还是 end_turn
                    continue;

                } else {
                    // max_tokens or other
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
