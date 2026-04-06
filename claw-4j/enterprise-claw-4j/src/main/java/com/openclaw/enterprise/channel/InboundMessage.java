package com.openclaw.enterprise.channel;

import java.time.Instant;
import java.util.List;

/**
 * 入站消息记录 — 所有渠道收到的消息的统一格式
 *
 * <p>无论是从 CLI、Telegram 还是飞书收到的消息，都转换为统一的
 * InboundMessage 格式，供 Gateway 和 Agent 循环处理。</p>
 *
 * <p>字段说明：</p>
 * <ul>
 *   <li>{@link #text()} — 消息文本内容</li>
 *   <li>{@link #senderId()} — 发送者 ID</li>
 *   <li>{@link #channel()} — 渠道名称 ("cli", "telegram", "feishu")</li>
 *   <li>{@link #accountId()} — 渠道内的 Bot 账号 ID</li>
 *   <li>{@link #peerId()} — 对话方标识符 (用于会话隔离)</li>
 *   <li>{@link #guildId()} — 群组/服务器 ID (null 表示私聊)</li>
 *   <li>{@link #isGroup()} — 是否群组消息</li>
 *   <li>{@link #media()} — 附件列表</li>
 *   <li>{@link #raw()} — 原始平台载荷 (调试用)</li>
 *   <li>{@link #timestamp()} — 消息时间戳</li>
 * </ul>
 *
 * <p>claw0 参考: s04_channels.py InboundMessage dataclass</p>
 *
 * @param text      消息文本内容
 * @param senderId  发送者 ID
 * @param channel   渠道名称
 * @param accountId Bot 账号 ID
 * @param peerId    对话方标识符
 * @param guildId   群组 ID (null 表示私聊)
 * @param isGroup   是否群组消息
 * @param media     媒体附件列表
 * @param raw       原始平台载荷
 * @param timestamp 消息时间戳
 */
public record InboundMessage(
    String text,
    String senderId,
    String channel,
    String accountId,
    String peerId,
    String guildId,
    boolean isGroup,
    List<MediaAttachment> media,
    Object raw,
    Instant timestamp
) {}
