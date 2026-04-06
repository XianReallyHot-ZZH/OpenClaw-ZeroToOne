package com.openclaw.enterprise.channel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.openclaw.enterprise.channel.InboundMessage;
import com.openclaw.enterprise.channel.MediaAttachment;
import com.openclaw.enterprise.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    /** Webhook 签名验证密钥 (verificationToken 或 appSecret 作为回退) */
    private final String verificationKey;

    /**
     * 构造飞书 Webhook 控制器
     *
     * @param feishuChannel 飞书渠道实例 (用于推送消息到内部队列)
     * @param channelProps  渠道配置 (用于获取签名验证密钥)
     */
    public FeishuWebhookController(FeishuChannel feishuChannel,
                                   AppProperties.ChannelProperties channelProps) {
        this.feishuChannel = feishuChannel;
        // 优先使用 verificationToken，回退到 appSecret
        String key = channelProps.feishu().verificationToken();
        if (key == null || key.isBlank()) {
            key = channelProps.feishu().appSecret();
        }
        this.verificationKey = (key != null && !key.isBlank()) ? key : null;
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
     * <p>签名验证：通过 {@link #verifySignature} 验证请求来源合法性，
     * 未配置验证密钥时跳过验证（开发模式）。</p>
     *
     * @param payload 飞书推送的 JSON 事件
     * @param request HTTP 原始请求 (用于签名验证)
     * @return 处理结果
     */
    @PostMapping
    public ResponseEntity<?> handleEvent(@RequestBody JsonNode payload,
                                         HttpServletRequest request) {
        // 读取原始请求体用于签名验证
        String body = payload.toString();

        // 签名验证
        if (!verifySignature(request, body)) {
            log.warn("Feishu webhook signature verification failed");
            return ResponseEntity.status(401).body(Map.of("error", "signature verification failed"));
        }

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

    // ==================== 签名验证 ====================

    /**
     * 验证飞书 Webhook 请求签名
     *
     * <p>飞书使用 HMAC-SHA256 签名验证请求来源的合法性：</p>
     * <ol>
     *   <li>从请求头获取 {@code X-Lark-Signature} 和 {@code X-Lark-Request-Timestamp}</li>
     *   <li>以验证密钥为 key，对 {@code timestamp + "\\n" + body} 计算 HMAC-SHA256</li>
     *   <li>将计算结果与 {@code X-Lark-Signature} 进行常量时间比较</li>
     * </ol>
     *
     * <p>如果未配置验证密钥 (verificationToken 或 appSecret)，则跳过验证（开发模式）。</p>
     *
     * @param request HTTP 请求
     * @param body    请求体字符串
     * @return 签名有效或未配置验证密钥时返回 true；签名无效返回 false
     */
    private boolean verifySignature(HttpServletRequest request, String body) {
        // 未配置验证密钥，跳过验证（开发模式）
        if (verificationKey == null) {
            log.debug("Feishu webhook verification key not configured, skipping signature check");
            return true;
        }

        String signature = request.getHeader("X-Lark-Signature");
        String timestamp = request.getHeader("X-Lark-Request-Timestamp");

        if (signature == null || timestamp == null) {
            log.warn("Feishu webhook missing signature headers (X-Lark-Signature or X-Lark-Request-Timestamp)");
            return false;
        }

        try {
            // 构造签名内容: timestamp + "\n" + body
            String contentToSign = timestamp + "\n" + body;

            // HMAC-SHA256 计算
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                verificationKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(contentToSign.getBytes(StandardCharsets.UTF_8));

            // 转为十六进制字符串
            StringBuilder hexBuilder = new StringBuilder(hmacBytes.length * 2);
            for (byte b : hmacBytes) {
                hexBuilder.append(String.format("%02x", b));
            }
            String computedSignature = hexBuilder.toString();

            // 常量时间比较，防止时序攻击
            return MessageDigest.isEqual(
                computedSignature.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Feishu webhook signature verification error", e);
            return false;
        }
    }
}
