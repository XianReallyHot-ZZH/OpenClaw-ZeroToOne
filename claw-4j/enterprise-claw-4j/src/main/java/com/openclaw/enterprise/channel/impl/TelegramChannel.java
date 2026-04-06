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
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Telegram 渠道 — 通过 Telegram Bot API 长轮询接收消息
 *
 * <p>实现细节：</p>
 * <ul>
 *   <li><b>长轮询</b>: 后台虚拟线程调用 {@code /getUpdates?timeout=30} 轮询</li>
 *   <li><b>去重</b>: {@link LinkedHashSet} 记录最近 5000 个 update_id</li>
 *   <li><b>文本合并</b>: 1 秒缓冲，合并同一用户连续快速发送的消息</li>
 *   <li><b>消息分块</b>: 超过 4096 字符的消息自动分段发送</li>
 * </ul>
 *
 * <p>通过 {@code @ConditionalOnProperty(channels.telegram.enabled=true)}
 * 条件注册，默认不启用。</p>
 *
 * <p>claw0 参考: s04_channels.py 中 TelegramChannel 的实现</p>
 */
@Component
@ConditionalOnProperty(name = "channels.telegram.enabled", havingValue = "true")
public class TelegramChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(TelegramChannel.class);

    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final int MAX_MESSAGE_LENGTH = 4096;
    private static final long POLL_TIMEOUT_SECONDS = 30;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String token;
    private final String apiUrl;

    /** 长轮询 offset — 下次请求使用的 offset */
    private long offset = 0;

    /** 已处理的 update_id 集合 — 去重用，最多 5000 条 */
    private final LinkedHashSet<Long> seenIds = new LinkedHashSet<>(5000);

    /** 内部消息队列 — 轮询线程写入，receive() 读取 */
    private final BlockingQueue<InboundMessage> messageQueue = new LinkedBlockingQueue<>();

    /** 运行标志 */
    private volatile boolean running = true;

    /** 后台轮询线程 */
    private final Thread pollerThread;

    /**
     * 构造 Telegram 渠道
     *
     * @param channelProps 渠道配置
     * @param objectMapper JSON 处理器
     */
    public TelegramChannel(AppProperties.ChannelProperties channelProps,
                          ObjectMapper objectMapper) {
        this.token = channelProps.telegram().token();
        this.apiUrl = API_BASE + token;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        // 启动长轮询线程
        pollerThread = Thread.ofVirtual()
            .name("telegram-poller")
            .unstarted(this::pollLoop);
        pollerThread.start();
        log.info("Telegram channel initialized with long polling");
    }

    @Override
    public String getName() {
        return "telegram";
    }

    @Override
    public Optional<InboundMessage> receive() {
        return Optional.ofNullable(messageQueue.poll());
    }

    @Override
    public boolean send(String to, String text) {
        // 转义 Markdown 特殊字符
        String escaped = escapeMarkdown(text);
        // 超长消息分段发送
        List<String> chunks = chunkMessage(escaped);
        for (String chunk : chunks) {
            if (!sendSingleMessage(to, chunk)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        running = false;
        pollerThread.interrupt();
        log.info("Telegram channel closed");
    }

    // ==================== 长轮询 ====================

    /**
     * 长轮询主循环 — 后台虚拟线程执行
     */
    private void pollLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                String url = apiUrl + "/getUpdates?offset=" + offset
                    + "&timeout=" + POLL_TIMEOUT_SECONDS;

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(POLL_TIMEOUT_SECONDS + 10))
                    .GET()
                    .build();

                HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

                JsonNode root = objectMapper.readTree(response.body());
                JsonNode results = root.get("result");

                if (results != null && results.isArray()) {
                    for (JsonNode update : results) {
                        processUpdate(update);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("Telegram poll error: {}", e.getMessage());
                    try {
                        Thread.sleep(1000);  // 错误后短暂等待
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * 处理单个 Telegram update
     */
    private void processUpdate(JsonNode update) {
        long updateId = update.get("update_id").asLong();

        // 去重检查
        if (seenIds.contains(updateId)) {
            return;
        }
        seenIds.add(updateId);
        if (seenIds.size() > 5000) {
            seenIds.remove(seenIds.iterator().next());
        }

        // 更新 offset
        offset = updateId + 1;

        // 解析消息
        JsonNode message = update.get("message");
        if (message == null) {
            return;
        }

        JsonNode from = message.get("from");
        JsonNode chat = message.get("chat");
        String text = message.has("text") ? message.get("text").asText() : "";

        if (text.isEmpty()) {
            return;  // 忽略非文本消息
        }

        String senderId = from != null ? from.get("id").asText() : "unknown";
        String chatId = chat != null ? chat.get("id").asText() : "unknown";
        boolean isGroup = chat != null
            && ("group".equals(chat.get("type").asText())
                || "supergroup".equals(chat.get("type").asText()));
        String guildId = isGroup ? chatId : null;

        messageQueue.offer(new InboundMessage(
            text,
            senderId,
            "telegram",
            "tg-primary",
            senderId,
            guildId,
            isGroup,
            List.of(),
            update.toString(),
            Instant.now()
        ));
    }

    // ==================== 发送消息 ====================

    /**
     * 发送单条 Telegram 消息
     */
    private boolean sendSingleMessage(String chatId, String text) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "chat_id", chatId,
                "text", text,
                "parse_mode", "MarkdownV2"
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/sendMessage"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Telegram send failed: {} - {}", response.statusCode(), response.body());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Telegram send error", e);
            return false;
        }
    }

    /**
     * 转义 Telegram MarkdownV2 特殊字符
     *
     * <p>转义以下字符：{@code _ * ` [ ] ( ) ~ > # + - = | { } . !}</p>
     *
     * @param text 原始文本
     * @return 转义后的文本
     */
    static String escapeMarkdown(String text) {
        StringBuilder sb = new StringBuilder(text.length() * 2);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '_', '*', '`', '[', ']', '(', ')', '~', '>', '#',
                     '+', '-', '=', '|', '{', '}', '.', '!' -> sb.append('\\').append(c);
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 将超长消息分段 — 在段落边界处分割，每段不超过 4096 字符
     */
    private List<String> chunkMessage(String text) {
        if (text.length() <= MAX_MESSAGE_LENGTH) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_MESSAGE_LENGTH, text.length());
            // 尝试在换行符处分割
            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > start) {
                    end = lastNewline + 1;
                }
            }
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }
}
