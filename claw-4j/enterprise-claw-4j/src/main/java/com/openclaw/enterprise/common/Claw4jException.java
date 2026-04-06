package com.openclaw.enterprise.common;

/**
 * 业务异常抽象基类 — 所有 claw4j 自定义异常的父类
 *
 * <p>提供统一的错误码 (errorCode) 机制，用于：</p>
 * <ul>
 *   <li>REST API 错误响应的 code 字段</li>
 *   <li>日志中的结构化错误标识</li>
 *   <li>异常分类和路由处理</li>
 * </ul>
 *
 * <p>继承体系：</p>
 * <pre>
 * RuntimeException
 *   |-- Claw4jException (abstract, errorCode 字段)
 *   |     |-- AgentException              (AGENT_ERROR)
 *   |     |-- ToolExecutionException      (TOOL_ERROR)
 *   |     |-- ContextOverflowException    (CONTEXT_OVERFLOW)
 *   |     |-- ChannelException            (CHANNEL_ERROR)
 *   |     |-- DeliveryException           (DELIVERY_ERROR)
 *   |     |-- ProfileExhaustedException   (PROFILES_EXHAUSTED)
 *   |-- JsonRpcException                  (独立，协议层错误)
 * </pre>
 *
 * <p>claw0 参考: 各文件中的 Exception 类和 failover 分类逻辑</p>
 */
public abstract class Claw4jException extends RuntimeException {

    /** 结构化错误码 — 用于 API 响应和日志标识 */
    private final String errorCode;

    /**
     * 构造异常
     *
     * @param errorCode 错误码 (如 "AGENT_ERROR", "TOOL_ERROR")
     * @param message   人类可读的错误描述
     */
    protected Claw4jException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 构造异常（带原始原因）
     *
     * @param errorCode 错误码
     * @param message   人类可读的错误描述
     * @param cause     导致此异常的原始异常
     */
    protected Claw4jException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取错误码
     *
     * @return 结构化错误码字符串
     */
    public String getErrorCode() {
        return errorCode;
    }
}
