package com.openclaw.enterprise.agent;

/**
 * Token 用量记录 — 一次 API 调用的 token 消耗统计
 *
 * <p>记录 Anthropic API 单次调用的 input/output token 使用量。
 * AgentLoop 在每次 API 调用后累加此记录，最终汇总到
 * {@link AgentTurnResult} 中返回。</p>
 *
 * <p>claw0 参考: s01_agent_loop.py 中从 API 响应的 usage 字段提取 token 数</p>
 *
 * @param inputTokens  输入 token 数 (包含系统提示 + 消息历史 + 工具结果)
 * @param outputTokens 输出 token 数 (Claude 生成的文本 + 工具调用)
 */
public record TokenUsage(
    int inputTokens,
    int outputTokens
) {}
