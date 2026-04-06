package com.openclaw.enterprise.common.exceptions;

import com.openclaw.enterprise.common.Claw4jException;

import java.util.Map;

/**
 * 工具执行异常 — 工具调用过程中的错误
 *
 * <p>记录了失败的工具名称、输入参数和错误信息，
 * 用于日志分析和 Agent 错误反馈。</p>
 *
 * <p>注意: 在 Agent 循环中，工具执行异常通常不会直接抛出到调用者，
 * 而是被捕获并转换为错误字符串返回给 Claude。
 * 此异常用于内部日志和监控。</p>
 *
 * <p>错误码: TOOL_ERROR</p>
 *
 * <p>claw0 参考: s02_tool_use.py 中 except Exception as exc 捕获的错误</p>
 */
public class ToolExecutionException extends Claw4jException {

    /** 失败的工具名称 (如 "bash", "read_file") */
    private final String toolName;

    /** 工具调用时的输入参数 */
    private final Map<String, Object> input;

    /**
     * 构造工具执行异常（简化版，用于未知工具等场景）
     *
     * @param toolName 工具名称
     * @param message  错误描述
     */
    public ToolExecutionException(String toolName, String message) {
        super("TOOL_ERROR", message);
        this.toolName = toolName;
        this.input = Map.of();
    }

    /**
     * 构造工具执行异常
     *
     * @param toolName 工具名称
     * @param input    工具调用参数
     * @param message  错误描述
     */
    public ToolExecutionException(String toolName, Map<String, Object> input, String message) {
        super("TOOL_ERROR", message);
        this.toolName = toolName;
        this.input = input;
    }

    /**
     * 构造工具执行异常（带原始原因）
     *
     * @param toolName 工具名称
     * @param input    工具调用参数
     * @param message  错误描述
     * @param cause    原始异常
     */
    public ToolExecutionException(String toolName, Map<String, Object> input, String message, Throwable cause) {
        super("TOOL_ERROR", message, cause);
        this.toolName = toolName;
        this.input = input;
    }

    /**
     * 获取失败的工具名称
     *
     * @return 工具名称
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * 获取工具调用参数
     *
     * @return 输入参数 Map
     */
    public Map<String, Object> getInput() {
        return input;
    }
}
