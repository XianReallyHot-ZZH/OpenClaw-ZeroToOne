package com.openclaw.enterprise.channel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.openclaw.enterprise.channel.InboundMessage;
import com.openclaw.enterprise.channel.MediaAttachment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 飞书 Webhook 控制器 — 接收飞书开放平台的事件推送
 *
 * <p>处理两类请求：</p>
 * <ol>
 *   <li><b>URL 验证</b>: 首次配置 Webhook 时，飞书发送 challenge 请求验证 URL 有效性</li>
 *   <li><b>消息事件</b>: 用户发送消息时，飞书推送事件到此端点</li>
 * </ol>
 *
 * <p>端点路径: {@code POST /webhook/feishu}</p>
 *
 * <p>通过 {@code @ConditionalOnProperty(channels.feishu.enabled=true)}
 * 条件注册，与 FeishuChannel 同时启用。</p>
 *
 * <p>claw0 参考: s04_channels.py 中飞书 Webhook 处理逻辑</p>
 */
@RestController
@ConditionalOnProperty(name = "channels.feishu.enabled", havingValue = "true")
@RequestMapping("/webhook/feishu")
public class FeishuWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookController.class);

    private final FeishuChannel feishuChannel;

    /**
     * 构造飞书 Webhook 控制器
     *
     * @param feishuChannel 飞书渠道实例 (用于推送消息到内部队列)
     */
    public FeishuWebhookController(FeishuChannel feishuChannel) {
        this.feishuChannel = feishuChannel;
    }

    /**
     * 处理飞书事件推送
     *
     * <p>根据请求中的 {@code type} 字段区分：</p>
     * <ul>
     *   <li>{@code "url_verification"} — URL 验证挑战，返回 challenge 值</li>
     *   <li>{@code "event_callback"} — 消息事件，解析后推送到 FeishuChannel</li>
     * </ul>
     *
     * @param payload 飞书推送的 JSON 事件
     * @return 处理结果
     */
    @PostMapping
    public ResponseEntity<?> handleEvent(@RequestBody JsonNode payload) {
        String type = payload.has("type") ? payload.get("type").asText() : "";

        // URL 验证挑战
        if ("url_verification".equals(type)) {
            String challenge = payload.get("challenge").asText();
            log.info("Feishu URL verification challenge received");
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }

        // 消息事件
        String eventType = payload.has("header")
            ? payload.get("header").get("event_type").asText() : "";

        if ("im.message.receive_v1".equals(eventType)) {
            handleImMessage(payload);
        }

        return ResponseEntity.ok(Map.of());
    }

    /**
     * 处理 IM 消息接收事件
     *
     * <p>从事件 payload 中提取：</p>
     * <ul>
     *   <li>发送者 ID (sender.sender_id.open_id)</li>
     *   <li>消息内容 (event.message.content)</li>
     *   <li>群组信息 (event.message.chat_id)</li>
     * </ul>
     *
     * @param payload 飞书事件 payload
     */
    private void handleImMessage(JsonNode payload) {
        try {
            JsonNode event = payload.get("event");
            JsonNode sender = event.get("sender");
            JsonNode message = event.get("message");

            String senderId = sender.get("sender_id").get("open_id").asText();
            String chatType = message.get("chat_type").asText();
            String msgType = message.get("msg_type").asText();
            String chatId = message.get("chat_id").asText();
            boolean isGroup = "group".equals(chatType);

            // 解析消息内容
            String text = extractText(message, msgType);

            if (text == null || text.isBlank()) {
                return;  // 忽略空消息
            }

            // 群组消息需要 @Bot 检测 (简化版 — 直接处理所有消息)
            InboundMessage inbound = new InboundMessage(
                text,
                senderId,
                "feishu",
                "feishu-primary",
                senderId,
                isGroup ? chatId : null,
                isGroup,
                List.of(),
                payload.toString(),
                Instant.now()
            );

            feishuChannel.pushMessage(inbound);
            log.debug("Feishu message received from {}: {} chars", senderId, text.length());

        } catch (Exception e) {
            log.error("Failed to handle Feishu message event", e);
        }
    }

    /**
     * 从飞书消息中提取文本内容
     *
     * <p>支持以下消息类型：</p>
     * <ul>
     *   <li>{@code "text"} — 纯文本，content 是 JSON: {"text": "..."}</li>
     *   <li>{@code "post"} — 富文本，content 是 JSON 结构</li>
     * </ul>
     *
     * @param message 消息节点
     * @param msgType 消息类型
     * @return 提取的文本内容
     */
    private String extractText(JsonNode message, String msgType) {
        String content = message.has("content") ? message.get("content").asText() : "{}";

        try {
            JsonNode contentNode = com.openclaw.enterprise.common.JsonUtils.fromJson(content, JsonNode.class);

            if ("text".equals(msgType)) {
                return contentNode.has("text") ? contentNode.get("text").asText() : null;
            }

            if ("post".equals(msgType)) {
                // 富文本 — 简化处理，提取所有文本
                StringBuilder sb = new StringBuilder();
                if (contentNode.has("title")) {
                    sb.append(contentNode.get("title").asText()).append("\n");
                }
                if (contentNode.has("content")) {
                    for (JsonNode paragraph : contentNode.get("content")) {
                        for (JsonNode element : paragraph) {
                            if (element.has("text")) {
                                sb.append(element.get("text").asText());
                            }
                        }
                        sb.append("\n");
                    }
                }
                return sb.toString().trim();
            }

            return null;
        } catch (Exception e) {
            log.warn("Failed to parse Feishu message content: {}", e.getMessage());
            return null;
        }
    }
}
