package com.openclaw.enterprise.common.exceptions;

import com.openclaw.enterprise.common.Claw4jException;

/**
 * Agent 相关异常 — Agent 生命周期操作中的错误
 *
 * <p>适用场景：</p>
 * <ul>
 *   <li>Agent 不存在 (AGENT_NOT_FOUND)</li>
 *   <li>Agent ID 格式错误 (INVALID_AGENT_ID)</li>
 *   <li>Agent 已存在 (AGENT_ALREADY_EXISTS)</li>
 *   <li>Agent 忙碌/并发已满 (AGENT_BUSY)</li>
 * </ul>
 *
 * <p>错误码: AGENT_ERROR</p>
 */
public class AgentException extends Claw4jException {

    /** 关联的 Agent ID，用于日志和错误响应定位 */
    private final String agentId;

    /**
     * 构造 Agent 异常
     *
     * @param agentId 关联的 Agent ID
     * @param message 错误描述
     */
    public AgentException(String agentId, String message) {
        super("AGENT_ERROR", message);
        this.agentId = agentId;
    }

    /**
     * 构造 Agent 异常（带原始原因）
     *
     * @param agentId 关联的 Agent ID
     * @param message 错误描述
     * @param cause   原始异常
     */
    public AgentException(String agentId, String message, Throwable cause) {
        super("AGENT_ERROR", message, cause);
        this.agentId = agentId;
    }

    /**
     * 获取关联的 Agent ID
     *
     * @return Agent ID
     */
    public String getAgentId() {
        return agentId;
    }
}
