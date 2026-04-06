package com.openclaw.enterprise.common.exceptions;

import com.openclaw.enterprise.common.Claw4jException;

/**
 * 渠道异常 — 消息渠道 (Telegram/飞书/CLI) 操作中的错误
 *
 * <p>适用场景：</p>
 * <ul>
 *   <li>Telegram Bot API 调用失败</li>
 *   <li>飞书开放平台 API 调用失败</li>
 *   <li>Webhook 验证失败</li>
 *   <li>消息格式转换错误</li>
 * </ul>
 *
 * <p>错误码: CHANNEL_ERROR</p>
 *
 * <p>claw0 参考: s04_channels.py 中各渠道的 except 块</p>
 */
public class ChannelException extends Claw4jException {

    /** 出错的渠道名称 (如 "telegram", "feishu", "cli") */
    private final String channelName;

    /**
     * 构造渠道异常
     *
     * @param channelName 渠道名称
     * @param message     错误描述
     * @param cause       原始异常
     */
    public ChannelException(String channelName, String message, Throwable cause) {
        super("CHANNEL_ERROR", message, cause);
        this.channelName = channelName;
    }

    /**
     * 获取出错的渠道名称
     *
     * @return 渠道名称
     */
    public String getChannelName() {
        return channelName;
    }
}
