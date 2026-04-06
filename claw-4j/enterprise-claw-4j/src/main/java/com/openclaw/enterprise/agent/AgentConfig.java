package com.openclaw.enterprise.agent;

/**
 * Agent 配置记录 — 定义一个 Agent 实例的完整配置
 *
 * <p>每个 Agent 实例由以下属性定义：</p>
 * <ul>
 *   <li>{@link #id()} — 唯一标识符，格式: [a-z0-9][a-z0-9_-]{0,63}</li>
 *   <li>{@link #name()} — 显示名称</li>
 *   <li>{@link #personality()} — 个性描述，用于系统提示生成</li>
 *   <li>{@link #model()} — 使用的模型 ID (如 "claude-sonnet-4-20250514")</li>
 *   <li>{@link #dmScope()} — 会话隔离粒度</li>
 * </ul>
 *
 * <p>claw0 参考: s01_agent_loop.py 中 Agent 相关的配置参数</p>
 *
 * @param id          Agent 唯一标识符
 * @param name        显示名称
 * @param personality 个性描述
 * @param model       模型 ID
 * @param dmScope     会话隔离粒度
 */
public record AgentConfig(
    String id,
    String name,
    String personality,
    String model,
    DmScope dmScope
) {}
