package com.openclaw.enterprise.session;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * 会话转录事件 — JSONL 持久化的基本单元
 *
 * <p>每个事件对应对话中的一个原子操作，以 JSONL 格式逐行写入会话文件。
 * 事件类型和对应的有效字段：</p>
 *
 * <table>
 *   <tr><th>type</th><th>有效字段</th></tr>
 *   <tr>
 *     <td>{@code "user"}</td>
 *     <td>content (String), timestamp</td>
 *   </tr>
 *   <tr>
 *     <td>{@code "assistant"}</td>
 *     <td>content (String), timestamp</td>
 *   </tr>
 *   <tr>
 *     <td>{@code "tool_use"}</td>
 *     <td>toolName, toolId, input, timestamp</td>
 *   </tr>
 *   <tr>
 *     <td>{@code "tool_result"}</td>
 *     <td>toolId, content (String), timestamp</td>
 *   </tr>
 * </table>
 *
 * <p>JSON 序列化时跳过 null 字段 ({@code @JsonInclude(NON_NULL)})。</p>
 *
 * <p>重要: tool_use 事件的 input 字段不能为 null。
 * Claude API 在重建历史时要求 ToolUseBlock.input 必须存在。</p>
 *
 * <p>claw0 参考: s03_sessions.py 中 JSONL 每行的事件结构</p>
 *
 * @param type      事件类型: "user", "assistant", "tool_use", "tool_result"
 * @param role      消息角色: "user" 或 "assistant"
 * @param content   消息内容 (文本)，user/assistant/tool_result 事件使用
 * @param toolName  工具名称，仅 tool_use 事件
 * @param toolId    工具调用 ID，仅 tool_use 和 tool_result 事件
 * @param input     工具调用参数，仅 tool_use 事件 (不能为 null)
 * @param timestamp 事件时间戳
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TranscriptEvent(
    String type,
    String role,
    Object content,
    String toolName,
    String toolId,
    Map<String, Object> input,
    Instant timestamp
) {}
