package com.openclaw.enterprise.tool;

import java.util.Map;

/**
 * 工具定义记录 — 描述一个工具的名称、用途和参数格式
 *
 * <p>对应 Anthropic Claude API 的 tool 定义结构：</p>
 * <pre>
 * {
 *   "name": "bash",
 *   "description": "Execute a bash command...",
 *   "input_schema": { ... }    // JSON Schema 对象
 * }
 * </pre>
 *
 * <p>注意: Anthropic API 使用 input_schema (snake_case)，不是 parameters。</p>
 *
 * <p>claw0 参考: s02_tool_use.py 第 215-298 行 TOOLS 列表中的各个工具定义字典</p>
 *
 * @param name        工具名称 (如 "bash", "read_file")
 * @param description 工具描述 — 告知 Claude 此工具的用途和使用方式
 * @param inputSchema JSON Schema 对象 — 定义工具参数的名称、类型和描述
 */
public record ToolDefinition(
    String name,
    String description,
    Map<String, Object> inputSchema
) {}
