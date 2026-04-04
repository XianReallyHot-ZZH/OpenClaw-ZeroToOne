package com.claw0.sessions;

/**
 * Section 01: Agent Loop —— Agent 循环
 * "An Agent is just while(true) + stop_reason"
 * Agent 就是 while(true) + stop_reason
 *
 * <h2>架构总览</h2>
 * <pre>
 *   用户输入 --> [messages 消息历史] --> 调用 LLM API --> stop_reason?
 *                                                        /          \
 *                                                  "end_turn"    "tool_use"
 *                                                      |              |
 *                                                  打印回复     (S02 再实现)
 *
 *   核心思想: Agent 的本质就是一个无限循环, 每轮:
 *     1. 收集用户输入, 追加到消息历史
 *     2. 携带完整历史调用 API
 *     3. 根据 stop_reason 决定下一步动作
 * </pre>
 *
 * <h2>运行方式</h2>
 * <pre>
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S01AgentLoop"
 * </pre>
 *
 * <h2>环境变量 (.env)</h2>
 * <pre>
 *   ANTHROPIC_API_KEY=sk-ant-xxxxx
 *   MODEL_ID=claude-sonnet-4-20250514
 * </pre>
 */

// region Common Imports
import com.claw0.common.AnsiColors;
import com.claw0.common.Config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
// endregion

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.stream.Collectors;

public class S01AgentLoop {

    // region Configuration
    /** 模型 ID, 优先从环境变量 MODEL_ID 读取, 默认使用 claude-sonnet-4-20250514 */
    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    /** 系统提示词: 告诉模型它的角色和行为准则, 所有对话共享同一个 system prompt */
    static final String SYSTEM_PROMPT = "You are a helpful AI assistant. Answer questions directly.";

    /**
     * Anthropic API 客户端.
     * fromEnv() 会自动从环境变量读取 ANTHROPIC_API_KEY 并配置认证.
     * 底层使用 OkHttp 发送 HTTP 请求.
     */
    static final AnthropicClient client = AnthropicOkHttpClient.builder()
            .fromEnv()
            .build();
    // endregion

    // region Core: Agent Loop
    /**
     * 核心 Agent 循环 -- 对话式 REPL (Read-Eval-Print Loop).
     *
     * <p>每轮循环执行 3 个步骤:
     * <ol>
     *   <li><b>收集输入</b>: 读取用户输入, 追加到 messages 列表</li>
     *   <li><b>调用 API</b>: 携带完整消息历史发送给 Claude</li>
     *   <li><b>检查 stop_reason</b>: 根据停止原因决定下一步动作</li>
     * </ol>
     *
     * <p>本节中 stop_reason 只有 "end_turn" (没有工具可用).
     * 下一节 S02 会增加 "tool_use" 的处理, 但循环结构不变.
     */
    static void agentLoop() {
        // 消息历史列表: 每次 API 调用都会携带完整的对话历史,
        // 这是 LLM "无状态" 特性的体现 -- 服务端不保存对话状态, 全靠客户端维护
        List<MessageParam> messages = new ArrayList<>();

        AnsiColors.printInfo("============================================================");
        AnsiColors.printInfo("  claw0  |  Section 01: Agent Loop");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("  Type 'quit' or 'exit' to leave. Ctrl+C also works.");
        AnsiColors.printInfo("============================================================");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            // --- Get user input ---
            String userInput;
            try {
                System.out.print(AnsiColors.coloredPrompt());
                userInput = scanner.nextLine().trim();
            } catch (Exception e) {
                // Java 中没有 KeyboardInterrupt. Scanner.nextLine() 在输入流关闭时
                // (例如用户按 Ctrl+D / Ctrl+Z) 会抛出 NoSuchElementException
                AnsiColors.printInfo("Goodbye.");
                break;
            }

            if (userInput.isEmpty()) continue;
            if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) {
                AnsiColors.printInfo("Goodbye.");
                break;
            }

            // --- 将用户消息追加到历史列表 ---
            // API 要求严格的 user/assistant 角色交替, 所以必须按顺序追加
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userInput)
                    .build());

            // --- 调用 Claude API ---
            // 这是整个 agent 的核心: 每轮对话都是一次完整的 API 调用,
            // 携带从开始到现在的所有消息历史
            try {
                MessageCreateParams params = MessageCreateParams.builder()
                        .model(MODEL_ID)
                        .maxTokens(8096)
                        .system(SYSTEM_PROMPT)
                        .messages(messages)
                        .build();

                Message response = client.messages().create(params);

                // --- 检查 stop_reason ---
                // stop_reason 是 Claude 告诉你"我做完了吗"的方式:
                //   end_turn  = 正常结束, 模型认为已经完整回答了用户的问题
                //   tool_use  = 模型想调用工具 (本节暂不处理)
                StopReason reason = response.stopReason().orElse(null);

                if (reason == StopReason.END_TURN) {
                    // 模型正常结束: 提取文本内容并打印, 同时将完整回复加入历史以保持上下文
                    String assistantText = response.content().stream()
                            .filter(ContentBlock::isText)
                            .map(ContentBlock::asText)
                            .map(TextBlock::text)
                            .collect(Collectors.joining());

                    AnsiColors.printAssistant(assistantText);
                    // response.toParam() 将 API 响应转换回可发送的 MessageParam --
                    // 这是保持对话状态的关键步骤: 下次调用 API 时模型能看到自己的历史回复
                    messages.add(response.toParam());

                } else if (reason == StopReason.TOOL_USE) {
                    AnsiColors.printInfo("[stop_reason=tool_use] No tools available in this section.");
                    AnsiColors.printInfo("See S02ToolUse for tool support.");
                    messages.add(response.toParam());

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
                    messages.add(response.toParam());
                }

            } catch (Exception e) {
                System.out.println("\n" + AnsiColors.YELLOW + "API Error: " + e.getMessage() + AnsiColors.RESET + "\n");
                // API 异常时回滚: 移除刚添加的用户消息, 避免下次重试时历史中出现
                // 孤立的 user 消息 (API 要求角色交替: user -> assistant -> user -> ...)
                messages.remove(messages.size() - 1);
            }
        }
    }
    // endregion

    // region Entry Point
    public static void main(String[] args) {
        // API Key 校验: 检查是否已设置且不是示例占位符 ("sk-ant-x" 是文档中的示例前缀)
        String apiKey = Config.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.startsWith("sk-ant-x")) {
            AnsiColors.printError("Error: ANTHROPIC_API_KEY not set.");
            AnsiColors.printInfo("Copy .env.example to .env and fill in your key.");
            System.exit(1);
        }

        agentLoop();
    }
    // endregion
}
