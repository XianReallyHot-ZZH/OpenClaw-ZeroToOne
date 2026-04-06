package com.openclaw.enterprise.channel.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.enterprise.channel.Channel;
import com.openclaw.enterprise.channel.InboundMessage;
import com.openclaw.enterprise.channel.MediaAttachment;
import com.openclaw.enterprise.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 飞书渠道 — 通过飞书开放平台 Webhook 接收消息
 *
 * <p>实现细节：</p>
 * <ul>
 *   <li><b>消息接收</b>: 由 {@link FeishuWebhookController} 接收 Webhook 推送，
 *       调用 {@link #pushMessage(InboundMessage)} 放入内部队列</li>
 *   <li><b>OAuth Token</b>: 自动获取和刷新 {@code tenant_access_token}，
 *       在过期前 5 分钟自动续期</li>
 *   <li><b>发送消息</b>: 通过飞书 {@code /im/v1/messages} API 发送</li>
 * </ul>
 *
 * <p>通过 {@code @ConditionalOnProperty(channels.feishu.enabled=true)}
 * 条件注册，默认不启用。</p>
 *
 * <p>claw0 参考: s04_channels.py 中 FeishuChannel 的实现</p>
 */
@Component
@ConditionalOnProperty(name = "channels.feishu.enabled", havingValue = "true")
public class FeishuChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(FeishuChannel.class);

    private static final String API_BASE = "https://open.feishu.cn/open-apis";
    private static final String TOKEN_URL = API_BASE + "/auth/v3/tenant_access_token/internal";
    private static final String SEND_URL = API_BASE + "/im/v1/messages?receive_id_type=chat_id";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String appId;
    private final String appSecret;

    /** 当前有效的 tenant_access_token */
    private volatile String tenantToken;

    /** token 过期时间 */
    private volatile Instant tokenExpiresAt;

    /** 内部消息队列 — Webhook 推送写入，receive() 读取 */
    private final BlockingQueue<InboundMessage> messageQueue = new LinkedBlockingQueue<>();

    /**
     * 构造飞书渠道
     *
     * @param channelProps 渠道配置
     * @param objectMapper JSON 处理器
     */
    public FeishuChannel(AppProperties.ChannelProperties channelProps,
                        ObjectMapper objectMapper) {
        this.appId = channelProps.feishu().appId();
        this.appSecret = channelProps.feishu().appSecret();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        log.info("Feishu channel initialized for app: {}", appId);
    }

    @Override
    public String getName() {
        return "feishu";
    }

    /**
     * 从内部队列取出一条消息
     *
     * @return 入站消息，无消息时返回 empty
     */
    @Override
    public Optional<InboundMessage> receive() {
        return Optional.ofNullable(messageQueue.poll());
    }

    /**
     * 发送消息到飞书
     *
     * <p>自动刷新 token，然后调用飞书消息发送 API。</p>
     *
     * @param chatId 目标 chat_id
     * @param text   消息文本
     * @return 发送成功返回 true
     */
    @Override
    public boolean send(String chatId, String text) {
        return sendInternal(chatId, text, 0);
    }

    /**
     * 带重试深度保护的内部发送方法
     *
     * <p>当收到 401 (token 过期) 时，清除 token 并递归重试，
     * 但最多重试 2 次以防止 StackOverflowError。</p>
     *
     * @param chatId     目标 chat_id
     * @param text       消息文本
     * @param retryDepth 当前重试深度
     * @return 发送成功返回 true
     */
    private boolean sendInternal(String chatId, String text, int retryDepth) {
        try {
            ensureToken();

            String body = objectMapper.writeValueAsString(Map.of(
                "receive_id", chatId,
                "msg_type", "text",
                "content", Map.of("text", text)
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SEND_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + tenantToken)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Feishu send failed: {} - {}", response.statusCode(), response.body());
                // token 可能过期，尝试刷新后重试（最多重试 2 次）
                if (response.statusCode() == 401) {
                    if (retryDepth >= 2) {
                        log.error("Feishu send retry depth exceeded ({}), giving up", retryDepth);
                        return false;
                    }
                    tenantToken = null;
                    return sendInternal(chatId, text, retryDepth + 1);
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Feishu send error", e);
            return false;
        }
    }

    @Override
    public void close() {
        log.info("Feishu channel closed");
    }

    /**
     * 推送消息到内部队列 — 由 FeishuWebhookController 调用
     *
     * @param message 入站消息
     */
    public void pushMessage(InboundMessage message) {
        messageQueue.offer(message);
    }

    // ==================== Token 管理 ====================

    /**
     * 确保 tenant_access_token 有效
     *
     * <p>如果 token 为空或将在 5 分钟内过期，则重新获取。</p>
     */
    private void ensureToken() {
        if (tenantToken != null && tokenExpiresAt != null
            && tokenExpiresAt.isAfter(Instant.now().plusSeconds(300))) {
            return;  // token 仍然有效
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "app_id", appId,
                "app_secret", appSecret
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

            JsonNode root = objectMapper.readTree(response.body());
            this.tenantToken = root.get("tenant_access_token").asText();
            int expireSeconds = root.get("expire").asInt();
            // 提前 5 分钟过期
            this.tokenExpiresAt = Instant.now().plusSeconds(expireSeconds - 300);

            log.info("Feishu tenant token refreshed, expires at: {}", tokenExpiresAt);
        } catch (Exception e) {
            throw new RuntimeException("Failed to refresh Feishu tenant token", e);
        }
    }
}
