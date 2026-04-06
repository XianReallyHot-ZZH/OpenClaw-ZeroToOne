package com.openclaw.enterprise.tool;

import java.util.Map;

/**
 * 工具处理器接口 — 所有工具处理器的统一契约
 *
 * <p>每个工具处理器实现此接口，提供：</p>
 * <ul>
 *   <li>{@link #getName()} — 工具名称，与 Claude API 的 tool_name 对应</li>
 *   <li>{@link #getSchema()} — JSON Schema 定义，发送给 Claude 让其理解工具参数</li>
 *   <li>{@link #execute(Map)} — 执行工具逻辑并返回结果文本</li>
 * </ul>
 *
 * <p>实现类标注 {@code @Component}，由 Spring 自动注入到 {@link ToolRegistry}。</p>
 *
 * <p>工具永不向调用者抛出异常 — 所有错误都转换为错误字符串返回给 Claude，
 * 让 Claude 根据错误信息自行决定下一步操作（重试、换参数、告知用户等）。</p>
 *
 * <p>claw0 参考: s02_tool_use.py 中 TOOL_HANDLERS 字典的各个工具函数</p>
 */
public interface ToolHandler {

    /**
     * 获取工具名称
     *
     * <p>名称必须是唯一的，在 {@link ToolRegistry} 中作为注册键。
     * 例如: "bash", "read_file", "write_file", "edit_file"。</p>
     *
     * @return 工具名称字符串
     */
    String getName();

    /**
     * 获取工具的 JSON Schema 定义
     *
     * <p>Schema 包含工具名称、描述和参数定义 (input_schema)，
     * 发送给 Claude API 作为 tools 参数的一部分。</p>
     *
     * <p>Anthropic API 使用 input_schema (snake_case)，而非 parameters。</p>
     *
     * @return 工具定义
     */
    ToolDefinition getSchema();

    /**
     * 执行工具逻辑
     *
     * <p>接收 Claude API 传入的工具调用参数，执行工具逻辑，
     * 返回结果文本。结果文本将作为 tool_result 返回给 Claude。</p>
     *
     * <p>约定: 工具永不抛出异常到调用者。所有错误都转换为
     * "Error: ..." 格式的字符串返回。</p>
     *
     * @param input 工具调用参数 (从 Claude API tool_use block 解析)
     * @return 执行结果文本
     */
    String execute(Map<String, Object> input);
}
