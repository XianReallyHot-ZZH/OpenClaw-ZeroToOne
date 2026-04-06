package com.openclaw.enterprise.delivery;

import com.openclaw.enterprise.common.FileUtils;
import com.openclaw.enterprise.common.JsonUtils;
import com.openclaw.enterprise.common.exceptions.DeliveryException;
import com.openclaw.enterprise.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * 消息投递队列 — WAL (Write-Ahead Log) 风格的持久化投递队列
 *
 * <p>存储布局：</p>
 * <pre>
 * workspace/delivery-queue/
 *   pending/          # 待投递消息 (每条一个 JSON 文件)
 *     del_a1b2c3d4.json
 *   failed/           # 重试耗尽的消息
 *     del_e5f6g7h8.json
 * </pre>
 *
 * <p>WAL 原子写入保证：</p>
 * <ol>
 *   <li>写入临时文件 {@code .tmp.{uuid}}</li>
 *   <li>fsync 确保数据落盘</li>
 *   <li>原子移动到目标路径</li>
 * </ol>
 *
 * <p>如果进程在写入过程中崩溃，临时文件会残留但不影响已有数据完整性。
 * 最多丢失正在写入的那一条消息。</p>
 *
 * <p>claw0 参考: s08_delivery.py 第 100-350 行 DeliveryQueue</p>
 */
@Service
public class DeliveryQueue {

    private static final Logger log = LoggerFactory.getLogger(DeliveryQueue.class);

    private final Path pendingDir;
    private final Path failedDir;

    public DeliveryQueue(AppProperties.WorkspaceProperties workspaceProps) {
        this.pendingDir = workspaceProps.path().resolve("delivery-queue/pending");
        this.failedDir = workspaceProps.path().resolve("delivery-queue/failed");
    }

    /**
     * 启动时确保目录存在
     */
    @PostConstruct
    void init() {
        try {
            Files.createDirectories(pendingDir);
            Files.createDirectories(failedDir);
        } catch (IOException e) {
            log.error("Failed to create delivery queue directories", e);
        }
    }

    /**
     * 入队一条待投递消息
     *
     * @param channel 目标渠道
     * @param to      目标标识符
     * @param text    消息正文
     * @return 投递记录 ID
     */
    public String enqueue(String channel, String to, String text) {
        // 生成 ID: "del_" + UUID 前 8 字符
        String id = "del_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        QueuedDelivery delivery = new QueuedDelivery(
            id, channel, to, text,
            Instant.now(), 0, Instant.now(), null
        );

        // 原子写入到 pending 目录
        Path target = pendingDir.resolve(id + ".json");
        String json = JsonUtils.toJson(delivery);
        writeQueueFile(target, json, id);

        log.debug("Enqueued delivery: id={}, channel={}, to={}", id, channel, to);
        return id;
    }

    /**
     * 确认投递成功 — 删除 pending 文件
     */
    public void ack(String deliveryId) {
        Path file = pendingDir.resolve(deliveryId + ".json");
        try {
            Files.deleteIfExists(file);
            log.debug("Acked delivery: {}", deliveryId);
        } catch (IOException e) {
            log.warn("Failed to ack delivery {}: {}", deliveryId, e.getMessage());
        }
    }

    /**
     * 标记投递失败 — 增加重试计数或移到 failed 目录
     *
     * @param deliveryId 投递 ID
     * @param error      错误信息
     * @param maxRetries 最大重试次数
     */
    public void fail(String deliveryId, String error, int maxRetries) {
        Path pendingFile = pendingDir.resolve(deliveryId + ".json");
        if (!Files.exists(pendingFile)) {
            log.warn("Pending delivery not found: {}", deliveryId);
            return;
        }

        try {
            String json = Files.readString(pendingFile);
            QueuedDelivery delivery = JsonUtils.fromJson(json, QueuedDelivery.class);

            if (delivery.retryCount() + 1 >= maxRetries) {
                // 重试耗尽: 移到 failed 目录
                QueuedDelivery failedDelivery = new QueuedDelivery(
                    delivery.id(), delivery.channel(), delivery.to(), delivery.text(),
                    delivery.createdAt(), delivery.retryCount() + 1,
                    null, error
                );
                Path failedFile = failedDir.resolve(deliveryId + ".json");
                writeQueueFile(failedFile, JsonUtils.toJson(failedDelivery), deliveryId);
                Files.deleteIfExists(pendingFile);
                log.warn("Delivery exhausted retries, moved to failed: {}", deliveryId);
            } else {
                // 增加重试计数，保持 pending
                QueuedDelivery updated = new QueuedDelivery(
                    delivery.id(), delivery.channel(), delivery.to(), delivery.text(),
                    delivery.createdAt(), delivery.retryCount() + 1,
                    null, error
                );
                writeQueueFile(pendingFile, JsonUtils.toJson(updated), deliveryId);
                log.debug("Delivery retry {} for {}: {}",
                    delivery.retryCount() + 1, deliveryId, error);
            }
        } catch (Exception e) {
            log.error("Failed to process delivery failure for {}", deliveryId, e);
        }
    }

    /**
     * 加载所有待投递消息 (按创建时间排序)
     */
    public List<QueuedDelivery> loadPending() {
        return loadFromDir(pendingDir);
    }

    /**
     * 加载所有失败消息
     */
    public List<QueuedDelivery> loadFailed() {
        return loadFromDir(failedDir);
    }

    /**
     * 将所有失败消息重新入队 (重置重试计数)
     *
     * @return 重新入队的消息数量
     */
    public int retryFailed() {
        List<QueuedDelivery> failed = loadFailed();
        for (QueuedDelivery del : failed) {
            // 从 failed 删除
            try {
                Files.deleteIfExists(failedDir.resolve(del.id() + ".json"));
            } catch (IOException e) {
                log.warn("Failed to delete failed delivery: {}", del.id());
            }

            // 以重置后的状态重新入队
            QueuedDelivery retried = new QueuedDelivery(
                del.id(), del.channel(), del.to(), del.text(),
                Instant.now(), 0, Instant.now(), null
            );
            writeQueueFile(pendingDir.resolve(del.id() + ".json"),
                JsonUtils.toJson(retried), del.id());
        }

        log.info("Retried {} failed deliveries", failed.size());
        return failed.size();
    }

    // ==================== 内部方法 ====================

    private List<QueuedDelivery> loadFromDir(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();

        List<QueuedDelivery> deliveries = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                .forEach(file -> {
                    try {
                        String json = Files.readString(file);
                        deliveries.add(JsonUtils.fromJson(json, QueuedDelivery.class));
                    } catch (Exception e) {
                        log.warn("Failed to load delivery file: {}", file, e);
                    }
                });
        } catch (IOException e) {
            log.warn("Failed to list delivery dir: {}", dir, e);
        }

        // 按创建时间排序
        deliveries.sort(Comparator.comparing(QueuedDelivery::createdAt));
        return deliveries;
    }

    /**
     * 原子写入投递文件 — tmp + fsync + atomic move
     */
    private void writeQueueFile(Path target, String content, String deliveryId) {
        Path tmp = target.resolveSibling(".tmp." + UUID.randomUUID());
        try {
            Files.writeString(tmp, content);
            try (var ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            throw new DeliveryException(deliveryId,
                "Failed to write delivery file: " + target, e);
        }
    }
}
