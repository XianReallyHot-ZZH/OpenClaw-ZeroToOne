package com.claw0.sessions;

/**
 * Section 01: Agent Loop
 * "An Agent is just while(true) + stop_reason"
 *
 *   User input --> [messages[]] --> LLM API --> stop_reason?
 *                                              /        \
 *                                        "end_turn"  "tool_use"
 *                                            |           |
 *                                         Print reply  (next section)
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S01AgentLoop"
 *
 * Requires .env:
 *   ANTHROPIC_API_KEY=sk-ant-xxxxx
 *   MODEL_ID=claude-sonnet-4-20250514
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
    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    static final String SYSTEM_PROMPT = "You are a helpful AI assistant. Answer questions directly.";

    static final AnthropicClient client = AnthropicOkHttpClient.builder()
            .fromEnv()
            .build();
    // endregion

    // region Core: Agent Loop
    /**
     * Main agent loop -- conversational REPL.
     *
     *   1. Collect user input, append to messages
     *   2. Call API
     *   3. Check stop_reason to decide next step
     *
     *   This section: stop_reason is always "end_turn" (no tools).
     *   Next section adds "tool_use" -- loop structure stays the same.
     */
    static void agentLoop() {
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
                // KeyboardInterrupt or EOF
                AnsiColors.printInfo("Goodbye.");
                break;
            }

            if (userInput.isEmpty()) continue;
            if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) {
                AnsiColors.printInfo("Goodbye.");
                break;
            }

            // --- Append to history ---
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userInput)
                    .build());

            // --- Call LLM ---
            try {
                MessageCreateParams params = MessageCreateParams.builder()
                        .model(MODEL_ID)
                        .maxTokens(8096)
                        .system(SYSTEM_PROMPT)
                        .messages(messages)
                        .build();

                Message response = client.messages().create(params);

                // --- Check stop_reason ---
                StopReason reason = response.stopReason().orElse(null);

                if (reason == StopReason.END_TURN) {
                    String assistantText = response.content().stream()
                            .filter(ContentBlock::isText)
                            .map(ContentBlock::asText)
                            .map(TextBlock::text)
                            .collect(Collectors.joining());

                    AnsiColors.printAssistant(assistantText);
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
                // Rollback last user message to maintain proper role alternation
                messages.remove(messages.size() - 1);
            }
        }
    }
    // endregion

    // region Entry Point
    public static void main(String[] args) {
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
