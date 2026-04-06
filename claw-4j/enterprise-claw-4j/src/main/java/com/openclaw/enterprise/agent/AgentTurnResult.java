package com.openclaw.enterprise.agent;

import java.util.List;

/**
 * Agent 一轮对话的结果 — AgentLoop.runTurn() 的返回值
 *
 * <p>封装了 Agent 处理一次用户消息后的完整结果：</p>
 * <ul>
 *   <li>{@link #text()} — Agent 的最终文本回复 (可能为空)</li>
 *   <li>{@link #toolCalls()} — 本轮执行的所有工具调用记录</li>
 *   <li>{@link #stopReason()} — Claude API 返回的停止原因 (end_turn/tool_use/max_tokens 等)</li>
 *   <li>{@link #tokenUsage()} — 本轮累计的 token 消耗</li>
 * </ul>
 *
 * <p>claw0 参考: s01_agent_loop.py 中 agent_turn() 返回的最终文本和执行统计</p>
 *
 * @param text        Agent 的最终文本回复
 * @param toolCalls   本轮执行的所有工具调用记录列表
 * @param stopReason  停止原因字符串 (如 "end_turn", "tool_use", "max_tokens")
 * @param tokenUsage  本轮累计 token 消耗统计
 */
public record AgentTurnResult(
    String text,
    List<ToolCallRecord> toolCalls,
    String stopReason,
    TokenUsage tokenUsage
) {}
