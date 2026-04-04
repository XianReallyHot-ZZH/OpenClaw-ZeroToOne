package com.claw0.sessions;

/**
 * Section 02: Tool Use
 * "Give the model hands"
 *
 * The Agent loop itself doesn't change -- we just add a dispatch table.
 * When stop_reason == "tool_use", look up the handler in TOOL_HANDLERS,
 * execute it, stuff the result back, and continue the loop. That simple.
 *
 * Architecture:
 *
 *   User --> LLM --> stop_reason == "tool_use"?
 *                        |
 *                TOOL_HANDLERS[name](**input)
 *                        |
 *                tool_result --> back to LLM
 *                        |
 *                 stop_reason == "end_turn"?
 *                        |
 *                     Print
 *
 * Tools:
 *   - bash       : run shell commands
 *   - read_file  : read file contents
 *   - write_file : write to files
 *   - edit_file  : exact string replacement (like OpenClaw's edit tool)
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S02ToolUse"
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
    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant with access to tools.\n"
            + "Use the tools to help the user with file operations and shell commands.\n"
            + "Always read a file before editing it.\n"
            + "When using edit_file, the old_string must match EXACTLY (including whitespace).";

    static final int MAX_TOOL_OUTPUT = 50_000;
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));

    static final AnthropicClient client = AnthropicOkHttpClient.builder()
            .fromEnv()
            .build();
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

    static final List<String> DANGEROUS_COMMANDS = List.of(
            "rm -rf /", "mkfs", "> /dev/sd", "dd if="
    );

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
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command)
                    .directory(WORKDIR.toFile())
                    .redirectErrorStream(true);
            Process process = pb.start();

            // Read output asynchronously to avoid deadlock
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes());
                } catch (IOException e) {
                    return "Error reading output: " + e.getMessage();
                }
            });

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            String output = outputFuture.get();

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

    static String toolEditFile(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        String oldString = (String) input.get("old_string");
        String newString = (String) input.get("new_string");
        AnsiColors.printTool("edit_file", filePath + " (replace " + oldString.length() + " chars)");
        try {
            Path target = safePath(filePath);
            if (!Files.exists(target)) return "Error: File not found: " + filePath;

            String content = Files.readString(target);
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

    /** Count non-overlapping occurrences of a substring. */
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
     * Main agent loop -- REPL with tool dispatch.
     *
     * Differences from S01:
     *   1. API call includes tools=TOOLS
     *   2. stop_reason == "tool_use" triggers tool execution and result loop-back
     *   3. Inner while loop handles consecutive tool calls
     *
     * The loop structure itself is unchanged. The essence of an agent:
     *   a while loop + a dispatch table.
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

            // --- Step 2: Append user message ---
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userInput)
                    .build());

            // --- Step 3: Agent inner loop ---
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
                    // Roll back to the last user message
                    while (!messages.isEmpty()
                            && messages.get(messages.size() - 1).role() != MessageParam.Role.USER) {
                        messages.remove(messages.size() - 1);
                    }
                    if (!messages.isEmpty()) messages.remove(messages.size() - 1);
                    break;
                }

                // Append assistant response to history
                messages.add(response.toParam());

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

                    // Append tool results as user message
                    messages.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(toolResultBlocks)
                            .build());
                    // Continue inner loop -- model will see results and decide next step
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
