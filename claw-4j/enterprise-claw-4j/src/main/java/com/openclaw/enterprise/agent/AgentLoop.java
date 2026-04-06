package com.openclaw.enterprise.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.anthropic.models.messages.Tool;
import com.openclaw.enterprise.common.JsonUtils;
import com.openclaw.enterprise.config.AppProperties;
import com.openclaw.enterprise.session.SessionStore;
import com.openclaw.enterprise.session.TranscriptEvent;
import com.openclaw.enterprise.tool.ToolDefinition;
import com.openclaw.enterprise.tool.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Agent 循环服务 — 核心的 Agent 对话循环实现
 *
 * <p>实现 Agent 的核心工作循环：</p>
 * <ol>
 *   <li>接收用户消息</li>
 *   <li>调用 Anthropic Claude API</li>
 *   <li>根据 stop_reason 分支处理：</li>
 *   <ul>
 *     <li>{@code END_TURN} — 提取文本回复，结束循环</li>
 *     <li>{@code TOOL_USE} — 执行工具调用，将结果反馈给 API，继续循环</li>
 *     <li>{@code MAX_TOKENS} 等 — 提取已有文本，结束循环</li>
 *   </ul>
 * </ol>
 *
 * <p>关键设计：</p>
 * <ul>
 *   <li>工具永不抛异常到调用者 — 所有错误转为字符串返回给 Claude</li>
 *   <li>使用 {@link Message#toParam()} 将 API 响应转为 MessageParam 追加到历史</li>
 *   <li>累加每次 API 调用的 token 用量</li>
 * </ul>
 *
 * <p>claw0 参考: s01_agent_loop.py 的 agent_loop() 主循环，
 * s02_tool_use.py 的 tool-use 循环增强版</p>
 */
@Service
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final AnthropicClient client;
    private final ToolRegistry toolRegistry;
    private final AppProperties.AnthropicProperties anthropicProps;
    private final SessionStore sessionStore;
    private final ContextGuard contextGuard;

    /**
     * 构造 Agent 循环服务
     *
     * @param client         Anthropic API 客户端
     * @param toolRegistry   工具注册中心
     * @param anthropicProps Anthropic 配置属性
     * @param sessionStore   会话持久化存储 (Sprint 2)
     * @param contextGuard   上下文守卫 (Sprint 2)
     */
    public AgentLoop(AnthropicClient client,
                     ToolRegistry toolRegistry,
                     AppProperties.AnthropicProperties anthropicProps,
                     SessionStore sessionStore,
                     ContextGuard contextGuard) {
        this.client = client;
        this.toolRegistry = toolRegistry;
        this.anthropicProps = anthropicProps;
        this.sessionStore = sessionStore;
        this.contextGuard = contextGuard;
    }

    /**
     * 执行一轮 Agent 对话
     *
     * <p>将用户消息加入消息列表，然后进入工具调用循环，
     * 直到 Claude 返回 end_turn 或达到停止条件。</p>
     *
     * @param agentId     Agent ID (用于日志)
     * @param sessionId   会话 ID (用于日志)
     * @param userMessage 用户输入消息
     * @return 本轮对话的完整结果
     */
    public AgentTurnResult runTurn(String agentId, String sessionId, String userMessage) {
        log.debug("[{}] Starting turn for session {}", agentId, sessionId);

        // 1. 从 JSONL 加载会话历史
        List<MessageParam> messages = new ArrayList<>(sessionStore.loadSession(sessionId));

        // 2. 追加用户消息到 JSONL
        sessionStore.appendTranscript(sessionId,
            new TranscriptEvent("user", "user", userMessage, null, null, null, java.time.Instant.now()));

        // 3. 将用户消息加入 API 消息列表
        messages.add(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content(userMessage)
            .build());

        // 4. 进入工具调用循环
        AgentTurnResult result = processToolUseLoop(agentId, sessionId, messages);

        // 5. 持久化助手回复到 JSONL
        if (result.text() != null && !result.text().isEmpty()) {
            sessionStore.appendTranscript(sessionId,
                new TranscriptEvent("assistant", "assistant", result.text(),
                    null, null, null, java.time.Instant.now()));
        }

        return result;
    }

    /**
     * 执行一轮 Agent 对话 (带已有消息历史)
     *
     * <p>用于会话恢复场景 — 加载已有的消息历史后继续对话。</p>
     *
     * @param agentId     Agent ID
     * @param sessionId   会话 ID
     * @param messages    已有的消息历史
     * @param userMessage 新的用户消息
     * @return 本轮对话的完整结果
     */
    public AgentTurnResult runTurn(String agentId, String sessionId,
                                   List<MessageParam> messages, String userMessage) {
        log.debug("[{}] Starting turn for session {} (with history, {} messages)",
            agentId, sessionId, messages.size());

        // 追加用户消息到 JSONL
        sessionStore.appendTranscript(sessionId,
            new TranscriptEvent("user", "user", userMessage, null, null, null, java.time.Instant.now()));

        // 将用户消息追加到历史
        messages.add(MessageParam.builder()
            .role(MessageParam.Role.USER)
            .content(userMessage)
            .build());

        return processToolUseLoop(agentId, sessionId, messages);
    }

    /**
     * 核心工具调用循环 — while(true) 循环处理 Claude 的 tool_use 请求
     *
     * <p>循环逻辑：</p>
     * <ol>
     *   <li>构建 MessageCreateParams 并调用 Claude API</li>
     *   <li>累加 token 用量</li>
     *   <li>检查 stopReason：</li>
     *   <ul>
     *     <li>{@code END_TURN} — 提取文本，返回结果</li>
     *     <li>{@code TOOL_USE} — 依次执行工具，收集结果，追加到消息列表，继续循环</li>
     *     <li>其他 — 提取已有文本，返回结果</li>
     *   </ul>
     * </ol>
     *
     * <p>claw0 对应: s02_tool_use.py 中 while True 循环 +
     * stop_reason 分支处理</p>
     *
     * @param agentId   Agent ID (用于日志)
     * @param sessionId 会话 ID (用于 JSONL 持久化)
     * @param messages  消息列表 (会被修改 — 追加助手消息和工具结果)
     * @return 对话结果
     */
    private AgentTurnResult processToolUseLoop(String agentId, String sessionId,
                                               List<MessageParam> messages) {
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        String finalText = "";
        String lastStopReason = "unknown";

        // 构建工具定义列表 (每次循环前获取，以支持动态注册)
        List<Tool> sdkTools = buildSdkTools();

        while (true) {
            // 构建 API 请求参数
            var paramsBuilder = MessageCreateParams.builder()
                .model(anthropicProps.modelId())
                .maxTokens((long) anthropicProps.maxTokens())
                .messages(messages);

            // 添加工具定义 (如果有)
            if (!sdkTools.isEmpty()) {
                paramsBuilder.tools(sdkTools.stream()
                    .map(ToolUnion::ofTool)
                    .toList());
            }

            MessageCreateParams params = paramsBuilder.build();

            // 调用 Claude API
            Message response;
            try {
                response = client.messages().create(params);
            } catch (Exception e) {
                log.error("[{}] API call failed: {}", agentId, e.getMessage());
                // API 错误 — 回滚消息列表并返回
                rollbackMessages(messages);
                return new AgentTurnResult(
                    "Error: API call failed - " + e.getMessage(),
                    toolCalls,
                    "error",
                    new TokenUsage(totalInputTokens, totalOutputTokens)
                );
            }

            // 累加 token 用量
            Usage usage = response.usage();
            totalInputTokens += (int) usage.inputTokens();
            totalOutputTokens += (int) usage.outputTokens();

            // 提取文本内容
            StringBuilder textBuilder = new StringBuilder();
            List<ToolUseBlock> toolUseBlocks = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                block.text().ifPresent(textBlock -> textBuilder.append(textBlock.text()));
                block.toolUse().ifPresent(toolUseBlocks::add);
            }

            String text = textBuilder.toString().trim();
            if (!text.isEmpty()) {
                finalText = text;
            }

            // 将助手响应转为 MessageParam 追加到消息列表
            messages.add(response.toParam());

            // 检查停止原因
            Optional<StopReason> stopReasonOpt = response.stopReason();
            lastStopReason = stopReasonOpt.map(StopReason::asString).orElse("unknown");

            if (stopReasonOpt.isEmpty()) {
                log.warn("[{}] No stop reason in response", agentId);
                break;
            }

            StopReason stopReason = stopReasonOpt.get();

            // END_TURN — 正常结束
            if (stopReason.known() == StopReason.Known.END_TURN) {
                log.debug("[{}] Turn ended with END_TURN", agentId);
                break;
            }

            // TOOL_USE — 执行工具并继续循环
            if (stopReason.known() == StopReason.Known.TOOL_USE) {
                log.debug("[{}] Processing {} tool calls", agentId, toolUseBlocks.size());

                // 依次执行每个工具调用
                List<ContentBlockParam> toolResults = new ArrayList<>();

                for (ToolUseBlock toolUse : toolUseBlocks) {
                    String toolName = toolUse.name();
                    String toolId = toolUse.id();

                    // 将 JsonValue input 转为 Map<String, Object>
                    Map<String, Object> toolInput;
                    try {
                        toolInput = toolUse._input().convert(
                            new TypeReference<Map<String, Object>>() {});
                    } catch (Exception e) {
                        toolInput = Map.of();
                    }

                    // 分发并执行工具
                    String result;
                    try {
                        result = toolRegistry.dispatch(toolName, toolInput);
                    } catch (Exception e) {
                        // 工具执行失败 — 返回错误信息给 Claude
                        log.warn("[{}] Tool '{}' execution failed: {}",
                            agentId, toolName, e.getMessage());
                        result = "Error: " + e.getMessage();
                    }

                    // 记录工具调用
                    toolCalls.add(new ToolCallRecord(toolName, toolId, toolInput, result));

                    // 构建 tool_result 块
                    toolResults.add(ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder()
                            .toolUseId(toolId)
                            .content(result)
                            .build()
                    ));
                }

                // 将工具结果作为 user 消息追加
                messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(toolResults)
                    .build());

                // 继续循环，让 Claude 处理工具结果
                continue;
            }

            // MAX_TOKENS / STOP_SEQUENCE / 其他 — 提取已有文本并结束
            log.debug("[{}] Turn ended with stop reason: {}", agentId, lastStopReason);
            break;
        }

        return new AgentTurnResult(
            finalText,
            toolCalls,
            lastStopReason,
            new TokenUsage(totalInputTokens, totalOutputTokens)
        );
    }

    /**
     * 将内部 ToolDefinition 列表转换为 Anthropic SDK 的 Tool 列表
     *
     * <p>遍历 {@link ToolRegistry#getSchemas()} 返回的工具定义，
     * 转换为 SDK 的 {@link Tool} 对象。inputSchema 使用 additionalProperties
     * 传递完整的 JSON Schema 对象。</p>
     *
     * @return SDK Tool 列表
     */
    private List<Tool> buildSdkTools() {
        List<ToolDefinition> definitions = toolRegistry.getSchemas();
        if (definitions.isEmpty()) {
            return List.of();
        }

        return definitions.stream()
            .map(this::toSdkTool)
            .toList();
    }

    /**
     * 将单个 ToolDefinition 转换为 SDK Tool
     *
     * <p>inputSchema 的转换策略：将 Map 中的每个键值对转为 JsonValue，
     * 通过 additionalProperties 传递给 SDK。这保留了完整的 JSON Schema 信息，
     * 包括 type、properties、required 等字段。</p>
     *
     * @param def 工具定义
     * @return SDK Tool 对象
     */
    private Tool toSdkTool(ToolDefinition def) {
        // 将 inputSchema Map 转为 JsonValue additionalProperties
        Map<String, JsonValue> schemaProps = new LinkedHashMap<>();
        for (var entry : def.inputSchema().entrySet()) {
            schemaProps.put(entry.getKey(), JsonValue.from(entry.getValue()));
        }

        return Tool.builder()
            .name(def.name())
            .description(def.description())
            .inputSchema(Tool.InputSchema.builder()
                .additionalProperties(schemaProps)
                .build())
            .build();
    }

    /**
     * 回滚消息列表 — API 调用失败时清理最后的用户消息
     *
     * <p>从消息列表末尾开始，移除非 user 消息，然后移除 user 消息。
     * 这确保下次重试时不会重复发送已失败的消息。</p>
     *
     * <p>claw0 对应: s01-s10 各文件中 messages.pop() 清理逻辑</p>
     *
     * @param messages 消息列表
     */
    private void rollbackMessages(List<MessageParam> messages) {
        // 移除末尾的 assistant/tool 消息
        while (!messages.isEmpty()
            && messages.getLast().role() != MessageParam.Role.USER) {
            messages.removeLast();
        }
        // 移除最后的 user 消息
        if (!messages.isEmpty()) {
            messages.removeLast();
        }
    }
}
