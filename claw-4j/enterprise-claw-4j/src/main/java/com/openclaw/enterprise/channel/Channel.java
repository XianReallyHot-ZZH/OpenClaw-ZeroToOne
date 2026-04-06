package com.openclaw.enterprise.channel;

import java.util.Optional;

/**
 * 渠道接口 — 所有消息渠道的统一契约
 *
 * <p>定义渠道的四个核心操作：</p>
 * <ul>
 *   <li>{@link #getName()} — 渠道名称标识</li>
 *   <li>{@link #receive()} — 非阻塞接收消息</li>
 *   <li>{@link #send(String, String)} — 发送消息</li>
 *   <li>{@link #close()} — 关闭渠道连接</li>
 * </ul>
 *
 * <p>实现类：</p>
 * <ul>
 *   <li>{@code CliChannel} — 终端标准输入/输出</li>
 *   <li>{@code TelegramChannel} — Telegram Bot API (长轮询)</li>
 *   <li>{@code FeishuChannel} — 飞书开放平台 (Webhook 推送)</li>
 * </ul>
 *
 * <p>claw0 参考: s04_channels.py Channel 基类</p>
 */
public interface Channel {

    /**
     * 获取渠道名称
     *
     * <p>返回小写的渠道标识符，用于路由和日志。</p>
     *
     * @return 渠道名称 (如 "cli", "telegram", "feishu")
     */
    String getName();

    /**
     * 非阻塞接收消息
     *
     * <p>从渠道的内部队列中取出一条待处理的消息。
     * 如果队列为空则返回 {@link Optional#empty()}。</p>
     *
     * <p>不同渠道的队列填充方式：</p>
     * <ul>
     *   <li>CLI — 后台 stdin 读取线程填充</li>
     *   <li>Telegram — 后台长轮询线程填充</li>
     *   <li>飞书 — Webhook Controller 推送填充</li>
     * </ul>
     *
     * @return 入站消息，无消息时返回 empty
     */
    Optional<InboundMessage> receive();

    /**
     * 发送消息到指定目标
     *
     * @param to   目标标识符 (如 Telegram chat_id, 飞书 chat_id)
     * @param text 消息文本
     * @return 发送成功返回 true
     */
    boolean send(String to, String text);

    /**
     * 关闭渠道连接
     *
     * <p>停止后台轮询线程，释放资源。</p>
     */
    void close();
}
