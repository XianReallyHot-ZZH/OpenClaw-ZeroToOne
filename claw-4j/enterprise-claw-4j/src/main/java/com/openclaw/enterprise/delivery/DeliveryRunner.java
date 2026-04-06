package com.openclaw.enterprise.delivery;

import com.openclaw.enterprise.channel.Channel;
import com.openclaw.enterprise.channel.ChannelManager;
import com.openclaw.enterprise.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 消息投递运行器 — 定时扫描投递队列并执行消息发送
 *
 * <p>投递流程：</p>
 * <ol>
 *   <li>从 {@link DeliveryQueue} 加载所有待投递消息</li>
 *   <li>按 {@code nextRetryAt} 排序，先处理到期的消息</li>
 *   <li>通过 {@link ChannelManager} 查找目标渠道并发送</li>
 *   <li>成功: {@code queue.ack()} — 删除 pending 文件</li>
 *   <li>失败: {@code queue.fail()} — 增加重试计数或移到 failed</li>
 * </ol>
 *
 * <p>指数退避重试策略：</p>
 * <pre>
 * 重试次数  基础延迟 * 倍率^次数  ±20% 抖动
 * 0        5s                    4-6s
 * 1        25s                   20-30s
 * 2        125s (~2min)          100-150s
 * 3        625s (~10min)         500-750s
 * 4+       移到 failed/
 * </pre>
 *
 * <p>claw0 参考: s08_delivery.py 第 350-500 行 DeliveryRunner</p>
 */
@Service
public class DeliveryRunner {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRunner.class);

    private final DeliveryQueue queue;
    private final ChannelManager channelManager;
    private final int maxRetries;
    private final int backoffBaseSeconds;
    private final double backoffMultiplier;
    private final double jitterFactor;

    public DeliveryRunner(DeliveryQueue queue,
                          ChannelManager channelManager,
                          AppProperties.DeliveryProperties deliveryProps) {
        this.queue = queue;
        this.channelManager = channelManager;
        this.maxRetries = deliveryProps.maxRetries();
        this.backoffBaseSeconds = deliveryProps.backoffBaseSeconds();
        this.backoffMultiplier = deliveryProps.backoffMultiplier();
        this.jitterFactor = deliveryProps.jitterFactor();
    }

    /**
     * 定时处理投递队列 — 间隔由 delivery.poll-interval-ms 配置决定 (默认 1 秒)
     */
    @Scheduled(fixedRateString = "${delivery.poll-interval-ms:1000}")
    public void processQueue() {
        List<QueuedDelivery> pending = queue.loadPending();
        if (pending.isEmpty()) return;

        Instant now = Instant.now();

        for (QueuedDelivery delivery : pending) {
            // 跳过尚未到重试时间的消息
            if (delivery.nextRetryAt() != null && now.isBefore(delivery.nextRetryAt())) {
                continue;
            }

            boolean success = deliver(delivery);
            if (success) {
                queue.ack(delivery.id());
                log.debug("Delivered: id={}, channel={}, to={}",
                    delivery.id(), delivery.channel(), delivery.to());
            } else {
                queue.fail(delivery.id(), "Delivery failed", maxRetries);
            }
        }
    }

    /**
     * 执行一次投递 — 查找渠道并发送消息
     *
     * @return true 表示投递成功
     */
    private boolean deliver(QueuedDelivery delivery) {
        var channelOpt = channelManager.get(delivery.channel());
        if (channelOpt.isEmpty()) {
            log.warn("Channel not found: {}", delivery.channel());
            return false;
        }

        Channel channel = channelOpt.get();
        return channel.send(delivery.to(), delivery.text());
    }

    /**
     * 计算下次重试时间 — 指数退避 + 随机抖动
     *
     * <p>公式: {@code base * multiplier^retryCount ± jitterFactor * delay}</p>
     *
     * @param retryCount 当前重试次数
     * @return 下次重试时间
     */
    Instant calculateNextRetry(int retryCount) {
        double baseDelay = backoffBaseSeconds * Math.pow(backoffMultiplier, retryCount);

        // 添加 ±jitterFactor 比例的随机抖动
        double jitter = baseDelay * jitterFactor * (2 * ThreadLocalRandom.current().nextDouble() - 1);
        long delaySeconds = Math.max(1, Math.round(baseDelay + jitter));

        return Instant.now().plusSeconds(delaySeconds);
    }

    /**
     * 手动触发一次队列刷新 — 用于优雅关闭时确保所有消息已处理
     */
    public void flush() {
        processQueue();
    }
}
