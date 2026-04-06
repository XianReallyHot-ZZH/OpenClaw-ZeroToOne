package com.openclaw.enterprise.agent;

import java.util.Map;

/**
 * 工具调用记录 — 捕获一次完整的工具调用信息
 *
 * <p>记录工具调用的完整生命周期：从 Claude 发起的 tool_use 请求，
 * 到工具执行器返回的结果。由 {@code AgentLoop} 在工具调用循环中创建。</p>
 *
 * <p>此记录包含在 {@code AgentTurnResult} 中，同时被 Sprint 2 的
 * {@code TranscriptEvent} 用于 JSONL 持久化 tool_use 事件。</p>
 *
 * <p>claw0 参考: s02_tool_use.py 中工具调用的 process_tool_call 返回值</p>
 *
 * @param toolName 工具名称 (如 "bash", "read_file", "write_file")
 * @param toolId   工具调用 ID (Claude API 返回的 "toolu_xxx" 标识符)
 * @param input    工具调用参数 (从 Claude API 的 tool_use block 解析)
 * @param result   工具执行结果文本 (成功时为输出，失败时为错误信息)
 */
public record ToolCallRecord(
    String toolName,
    String toolId,
    Map<String, Object> input,
    String result
) {}
