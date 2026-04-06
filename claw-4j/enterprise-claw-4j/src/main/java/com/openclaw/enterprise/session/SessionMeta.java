package com.openclaw.enterprise.session;

import java.time.Instant;

/**
 * 会话元数据 — 描述一个会话的基本信息
 *
 * <p>存储在 sessions.json 索引文件中，用于快速查询会话列表，
 * 无需加载完整的 JSONL 转录文件。</p>
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>{@link #sessionId()} — 会话唯一 ID，格式: {@code sess_{uuid8}}</li>
 *   <li>{@link #agentId()} — 所属 Agent ID</li>
 *   <li>{@link #label()} — 会话标签 (可选，用于标记对话主题)</li>
 *   <li>{@link #createdAt()} — 创建时间</li>
 *   <li>{@link #lastActive()} — 最后活跃时间 (每次追加事件时更新)</li>
 *   <li>{@link #messageCount()} — 消息数量 (用户消息计数)</li>
 * </ul>
 *
 * <p>claw0 参考: s03_sessions.py 中 SessionIndex 的每个条目</p>
 *
 * @param sessionId    会话 ID
 * @param agentId      所属 Agent ID
 * @param label        会话标签
 * @param createdAt    创建时间
 * @param lastActive   最后活跃时间
 * @param messageCount 消息数量
 */
public record SessionMeta(
    String sessionId,
    String agentId,
    String label,
    Instant createdAt,
    Instant lastActive,
    int messageCount
) {}
