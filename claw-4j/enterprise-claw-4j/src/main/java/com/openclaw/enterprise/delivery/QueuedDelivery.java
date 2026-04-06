package com.openclaw.enterprise.delivery;

import java.time.Instant;

/**
 * 待投递消息记录 — 投递队列中的一个消息单元
 *
 * <p>生命周期: 创建 (enqueue) → 投递尝试 → 成功 (ack) 或 失败重试 (fail)</p>
 *
 * <p>文件系统映射：</p>
 * <ul>
 *   <li>活跃状态 → {@code workspace/delivery-queue/pending/{id}.json}</li>
 *   <li>耗尽重试 → {@code workspace/delivery-queue/failed/{id}.json}</li>
 * </ul>
 *
 * <p>claw0 参考: s08_delivery.py 第 118-156 行 QueuedDelivery</p>
 *
 * @param id          投递记录 ID (格式: "del_{uuid8}")
 * @param channel     目标渠道名称 (如 "telegram", "feishu", "cli")
 * @param to          目标标识符 (如 chat_id, peer_id)
 * @param text        消息正文
 * @param createdAt   创建时间
 * @param retryCount  已重试次数
 * @param nextRetryAt 下次重试时间
 * @param lastError   最近一次失败原因
 */
public record QueuedDelivery(
    String id,
    String channel,
    String to,
    String text,
    Instant createdAt,
    int retryCount,
    Instant nextRetryAt,
    String lastError
) {}
