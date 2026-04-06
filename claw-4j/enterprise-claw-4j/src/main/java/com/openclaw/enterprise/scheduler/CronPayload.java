package com.openclaw.enterprise.scheduler;

/**
 * Cron 任务载荷 — 描述定时任务执行的具体动作
 *
 * <p>两种载荷类型：</p>
 * <ul>
 *   <li>{@link AgentTurn} — 触发完整的 Agent 对话轮次</li>
 *   <li>{@link SystemEvent} — 发送系统消息文本</li>
 * </ul>
 *
 * <p>claw0 参考: s07_heartbeat_cron.py 第 140-160 行 CronPayload</p>
 */
public sealed interface CronPayload
    permits CronPayload.AgentTurn, CronPayload.SystemEvent {

    /**
     * Agent 对话轮次 — 触发完整的 AgentLoop.runTurn()
     *
     * @param agentId 要执行的 Agent ID
     * @param prompt  发送给 Agent 的提示文本
     */
    record AgentTurn(String agentId, String prompt) implements CronPayload {}

    /**
     * 系统事件 — 直接发送消息文本，不经过 Agent 循环
     *
     * @param message 系统消息内容
     */
    record SystemEvent(String message) implements CronPayload {}
}
