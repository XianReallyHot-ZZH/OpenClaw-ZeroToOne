package com.openclaw.enterprise.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.*;
import com.openclaw.enterprise.common.TokenEstimator;
import com.openclaw.enterprise.common.exceptions.ContextOverflowException;
import com.openclaw.enterprise.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 上下文守卫 — 上下文溢出的三阶段恢复机制
 *
 * <p>当 Claude API 调用因上下文过长而失败时，ContextGuard 依次尝试：</p>
 * <ol>
 *   <li><b>Stage 1 — 正常调用</b>: 直接调用 API，无溢出则立即返回</li>
 *   <li><b>Stage 2 — 截断工具结果</b>: 将所有 tool_result 内容截断到原始长度的 30%</li>
 *   <li><b>Stage 3 — LLM 压缩历史</b>: 用 LLM 摘要最旧的 50% 消息，保留最新 50%</li>
 * </ol>
 *
 * <p>三阶段均失败后抛出 {@link ContextOverflowException}。</p>
 *
 * <p>设计决策: ContextGuard 不持有 {@link AnthropicClient} 引用，
 * 而是通过方法参数传入。这允许 Sprint 6 的 ResilienceRunner 在
 * Auth Profile 轮转时注入不同的客户端。</p>
 *
 * <p>claw0 参考: s09_resilience.py 中 ContextGuard / context_guard 相关逻辑</p>
 */
@Service
public class ContextGuard {

    private static final Logger log = LoggerFactory.getLogger(ContextGuard.class);

    /** 最大压缩轮次 — Stage 3 最多尝试 3 轮 */
    private static final int MAX_COMPACT_ROUNDS = 3;

    /** Stage 2 工具结果截断比例 — 保留前 30% */
    private static final double TOOL_RESULT_KEEP_RATIO = 0.3;

    /** Stage 3 历史保留比例 — 保留最新 50% */
    private static final double HISTORY_KEEP_RATIO = 0.5;

    private final TokenEstimator tokenEstimator;
    private final AppProperties.AnthropicProperties anthropicProps;

    /**
     * 构造上下文守卫
     *
     * @param tokenEstimator Token 估算器
     * @param anthropicProps Anthropic 配置属性
     */
    public ContextGuard(TokenEstimator tokenEstimator,
                        AppProperties.AnthropicProperties anthropicProps) {
        this.tokenEstimator = tokenEstimator;
        this.anthropicProps = anthropicProps;
    }

    /**
     * 带守卫的 API 调用 — 自动处理上下文溢出
     *
     * <p>执行三阶段恢复：</p>
     * <ol>
     *   <li>Stage 1: 直接调用 API</li>
     *   <li>Stage 2: 如果溢出，截断工具结果后重试</li>
     *   <li>Stage 3: 如果仍溢出，用 LLM 压缩历史后重试</li>
     * </ol>
     *
     * @param client Anthropic API 客户端 (通过参数传入，支持 Profile 轮转)
     * @param params API 调用参数
     * @return Claude API 响应
     */
    public Message guardApiCall(AnthropicClient client, MessageCreateParams params) {
        // Stage 1 — 正常调用
        try {
            return client.messages().create(params);
        } catch (Exception e) {
            if (!isContextOverflow(e)) {
                throw e;  // 非溢出错误，直接抛出
            }
            log.warn("Context overflow detected, entering Stage 2 (truncate tool results)");
        }

        // Stage 2 — 截断工具结果
        MessageCreateParams truncatedParams = stage2TruncateToolResults(params);
        try {
            return client.messages().create(truncatedParams);
        } catch (Exception e) {
            if (!isContextOverflow(e)) {
                throw e;
            }
            log.warn("Stage 2 failed, entering Stage 3 (LLM compact history)");
        }

        // Stage 3 — LLM 压缩历史 (最多 MAX_COMPACT_ROUNDS 轮)
        return stage3CompactHistory(client, params);
    }

    /**
     * Stage 2 — 截断工具结果内容
     *
     * <p>遍历所有消息，找到包含 tool_result 块的 user 消息，
     * 将每个 tool_result 的内容截断到原始长度的 30%。</p>
     *
     * @param params 原始 API 参数
     * @return 截断后的 API 参数
     */
    private MessageCreateParams stage2TruncateToolResults(MessageCreateParams params) {
        List<MessageParam> messages = new ArrayList<>(params.messages());
        List<MessageParam> truncatedMessages = new ArrayList<>();

        for (MessageParam msg : messages) {
            Object content = msg.content();
            if (content instanceof List<?> blocks) {
                // 内容块列表 — 检查是否包含 tool_result
                List<ContentBlockParam> newBlocks = new ArrayList<>();
                boolean modified = false;

                for (Object block : blocks) {
                    if (block instanceof ContentBlockParam cbp) {
                        Optional<ToolResultBlockParam> toolResult = cbp.toolResult();
                        if (toolResult.isPresent()) {
                            // 截断 tool_result 内容
                            var tr = toolResult.get();
                            String originalContent = tr.content()
                                .flatMap(c -> c.string())
                                .orElse("");
                            int keepLen = Math.max(100,
                                (int) (originalContent.length() * TOOL_RESULT_KEEP_RATIO));
                            String truncated = originalContent.substring(0,
                                Math.min(keepLen, originalContent.length()));
                            if (originalContent.length() > keepLen) {
                                truncated += "\n... [truncated by ContextGuard, "
                                    + originalContent.length() + " total chars]";
                            }
                            newBlocks.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                    .toolUseId(tr.toolUseId())
                                    .content(truncated)
                                    .build()
                            ));
                            modified = true;
                        } else {
                            newBlocks.add(cbp);
                        }
                    } else {
                        // 保持原样
                        break;
                    }
                }

                if (modified) {
                    truncatedMessages.add(MessageParam.builder()
                        .role(msg.role())
                        .contentOfBlockParams(newBlocks)
                        .build());
                } else {
                    truncatedMessages.add(msg);
                }
            } else {
                // 字符串内容 — 不需要截断
                truncatedMessages.add(msg);
            }
        }

        log.info("Stage 2: truncated tool results in {} messages", truncatedMessages.size());

        // 重建参数
        return MessageCreateParams.builder()
            .model(params.model())
            .maxTokens(params.maxTokens())
            .messages(truncatedMessages)
            .build();
    }

    /**
     * Stage 3 — LLM 压缩历史
     *
     * <p>将消息历史分为最旧的 50% 和最新的 50%。用 LLM 为旧部分生成摘要，
     * 然后用 [user: "[Context Summary]\n{summary}"] + [最新 50%] 重建历史。
     * 最多尝试 {@link #MAX_COMPACT_ROUNDS} 轮。</p>
     *
     * <p>claw0 对应: s09_resilience.py 中 stage3_compact_history() 逻辑</p>
     *
     * @param client Anthropic API 客户端
     * @param params 原始 API 参数
     * @return Claude API 响应
     * @throws ContextOverflowException 如果压缩后仍然溢出
     */
    private Message stage3CompactHistory(AnthropicClient client, MessageCreateParams params) {
        List<MessageParam> messages = new ArrayList<>(params.messages());

        for (int round = 1; round <= MAX_COMPACT_ROUNDS; round++) {
            log.info("Stage 3: compaction round {}/{}", round, MAX_COMPACT_ROUNDS);

            // 分割消息: 最旧的 50% 和最新的 50%
            int splitIdx = (int) (messages.size() * HISTORY_KEEP_RATIO);
            if (splitIdx < 1) {
                splitIdx = 1;  // 至少保留第一条
            }

            List<MessageParam> oldPart = messages.subList(0, splitIdx);
            List<MessageParam> recentPart = messages.subList(splitIdx, messages.size());

            // 用 LLM 生成摘要
            String summary = summarizeMessages(client, oldPart);
            log.info("Stage 3 round {}: summarized {} messages into {} chars",
                round, oldPart.size(), summary.length());

            // 重建消息: [user: summary] + [recent part]
            List<MessageParam> rebuilt = new ArrayList<>();
            rebuilt.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content("[Context Summary from previous conversation]\n" + summary)
                .build());
            rebuilt.add(MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .content("Understood. I'll continue based on this summary.")
                .build());
            rebuilt.addAll(recentPart);

            // 估算 token 数
            int estimated = tokenEstimator.estimateMessages(rebuilt);
            log.info("Stage 3 round {}: estimated tokens after compaction: {}",
                round, estimated);

            // 尝试调用
            MessageCreateParams compactedParams = MessageCreateParams.builder()
                .model(params.model())
                .maxTokens(params.maxTokens())
                .messages(rebuilt)
                .build();

            try {
                return client.messages().create(compactedParams);
            } catch (Exception e) {
                if (!isContextOverflow(e)) {
                    throw e;
                }
                // 仍然溢出 — 用截断后的消息继续下一轮
                messages = rebuilt;
                log.warn("Stage 3 round {} still overflow, retrying...", round);
            }
        }

        // 三轮压缩后仍溢出 — 抛出异常
        int estimated = tokenEstimator.estimateMessages(messages);
        throw new ContextOverflowException(estimated, anthropicProps.maxTokens());
    }

    /**
     * 用 LLM 为消息列表生成摘要
     *
     * <p>将消息列表序列化为文本，然后调用 LLM 生成简洁摘要。</p>
     *
     * @param client   Anthropic API 客户端
     * @param messages 要摘要的消息列表
     * @return LLM 生成的摘要文本
     */
    private String summarizeMessages(AnthropicClient client, List<MessageParam> messages) {
        // 构建摘要请求
        StringBuilder prompt = new StringBuilder();
        prompt.append("Please summarize the following conversation history concisely. ");
        prompt.append("Preserve key facts, decisions, and any important context. ");
        prompt.append("Keep the summary under 1000 tokens.\n\n");

        for (MessageParam msg : messages) {
            String role = msg.role() == MessageParam.Role.USER ? "User" : "Assistant";
            String content = msg.content().isString()
                ? msg.content().asString()
                : msg.content().toString();
            prompt.append(role).append(": ").append(content).append("\n\n");
        }

        try {
            MessageCreateParams summaryParams = MessageCreateParams.builder()
                .model(anthropicProps.modelId())
                .maxTokens(1024L)
                .addUserMessage(prompt.toString())
                .build();

            Message response = client.messages().create(summaryParams);
            StringBuilder result = new StringBuilder();
            for (ContentBlock block : response.content()) {
                block.text().ifPresent(tb -> result.append(tb.text()));
            }
            return result.toString();
        } catch (Exception e) {
            log.error("Failed to summarize messages", e);
            // 摘要失败 — 使用简单的文本截断作为 fallback
            return "Previous conversation (" + messages.size()
                + " messages). Summary generation failed: " + e.getMessage();
        }
    }

    /**
     * 判断异常是否为上下文溢出
     *
     * <p>通过检查异常消息中的关键词来判断：</p>
     * <ul>
     *   <li>"context" + "overflow"</li>
     *   <li>"too many tokens"</li>
     *   <li>"max_context"</li>
     *   <li>异常类名包含 "Overloaded"</li>
     * </ul>
     *
     * @param e 异常
     * @return 如果是上下文溢出异常返回 true
     */
    private boolean isContextOverflow(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            // 检查异常类名
            return e.getClass().getSimpleName().contains("Overloaded");
        }
        String lower = msg.toLowerCase();
        return (lower.contains("context") && lower.contains("overflow"))
            || lower.contains("too many tokens")
            || lower.contains("max_context")
            || lower.contains("request too large")
            || e.getClass().getSimpleName().contains("Overloaded");
    }
}
