package com.openclaw.enterprise.health;

import com.openclaw.enterprise.channel.ChannelManager;
import com.openclaw.enterprise.common.JsonUtils;
import com.openclaw.enterprise.concurrency.CommandQueue;
import com.openclaw.enterprise.delivery.DeliveryRunner;
import com.openclaw.enterprise.gateway.GatewayWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * 优雅关闭管理器 — 确保所有正在处理的任务完成后再关闭应用
 *
 * <p>关闭流程 (严格顺序)：</p>
 * <ol>
 *   <li>停止所有渠道的消息接收</li>
 *   <li>广播 shutdown 通知给所有 WebSocket 客户端</li>
 *   <li>等待所有通道队列中的任务完成 (最多 30 秒)</li>
 *   <li>刷新投递队列 — 处理剩余的待投递消息</li>
 *   <li>关闭所有渠道连接</li>
 * </ol>
 *
 * <p>实现 {@link SmartLifecycle}，phase 设为 {@code Integer.MAX_VALUE}，
 * 确保在所有其他 Bean 之后再关闭。</p>
 */
@Component
public class GracefulShutdownManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownManager.class);

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    private final ChannelManager channelManager;
    private final CommandQueue commandQueue;
    private final DeliveryRunner deliveryRunner;
    private final GatewayWebSocketHandler wsHandler;

    private volatile boolean running = false;

    public GracefulShutdownManager(ChannelManager channelManager,
                                   CommandQueue commandQueue,
                                   DeliveryRunner deliveryRunner,
                                   GatewayWebSocketHandler wsHandler) {
        this.channelManager = channelManager;
        this.commandQueue = commandQueue;
        this.deliveryRunner = deliveryRunner;
        this.wsHandler = wsHandler;
    }

    @Override
    public void start() {
        running = true;
        log.info("GracefulShutdownManager started");
    }

    @Override
    public void stop(Runnable callback) {
        log.info("Graceful shutdown initiated...");

        // 1. 停止接收新消息
        try {
            channelManager.stopReceiving();
            log.info("Step 1/5: Stopped receiving new messages");
        } catch (Exception e) {
            log.warn("Failed to stop receiving", e);
        }

        // 2. 广播 shutdown 通知给 WebSocket 客户端
        broadcastShutdown();
        log.info("Step 2/5: Broadcast shutdown notification");

        // 3. 等待所有通道中的任务完成
        try {
            boolean allIdle = commandQueue.waitForAll(SHUTDOWN_TIMEOUT);
            if (allIdle) {
                log.info("Step 3/5: All lanes idle");
            } else {
                log.warn("Step 3/5: Shutdown timeout — some lanes still active");
            }
        } catch (Exception e) {
            log.warn("Step 3/5: Error waiting for lanes", e);
        }

        // 4. 刷新投递队列
        try {
            deliveryRunner.flush();
            log.info("Step 4/5: Delivery queue flushed");
        } catch (Exception e) {
            log.warn("Step 4/5: Failed to flush delivery queue", e);
        }

        // 5. 关闭所有渠道
        try {
            channelManager.closeAll();
            log.info("Step 5/5: All channels closed");
        } catch (Exception e) {
            log.warn("Step 5/5: Failed to close channels", e);
        }

        running = false;
        log.info("Graceful shutdown complete");
        callback.run();
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE; // 最后关闭
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 广播 shutdown 通知给所有 WebSocket 客户端
     */
    private void broadcastShutdown() {
        wsHandler.broadcast("server.shutdown", Map.of(
            "message", "Server is shutting down gracefully",
            "timeout_seconds", SHUTDOWN_TIMEOUT.toSeconds()
        ));
    }
}
