package com.openclaw.enterprise.channel;

/**
 * 媒体附件记录 — 消息中携带的媒体文件信息
 *
 * <p>描述一个媒体附件的类型、URL 和 MIME 类型。
 * 支持图片、视频、文档、音频等媒体类型。</p>
 *
 * <p>claw0 参考: s04_channels.py 中 InboundMessage.media 列表的元素</p>
 *
 * @param type     媒体类型 ("image", "video", "document", "audio")
 * @param url      媒体文件 URL 或本地路径
 * @param mimeType MIME 类型 (如 "image/png", "application/pdf")
 */
public record MediaAttachment(
    String type,
    String url,
    String mimeType
) {}
