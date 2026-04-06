package com.openclaw.enterprise.concurrency;

import com.openclaw.enterprise.config.AppProperties;
import com.openclaw.enterprise.config.ConcurrencyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 命令队列服务 — 管理多个命名通道 (Lane) 的任务调度
 *
 * <p>每个命名通道独立维护 FIFO 顺序和并发控制：</p>
 * <ul>
 *   <li>{@code main} — 用户交互 (maxConcurrency 由配置决定)</li>
 *   <li>{@code cron} — 定时任务 (通常 maxConcurrency=1)</li>
 *   <li>{@code heartbeat} — 心跳检查 (通常 maxConcurrency=1)</li>
 * </ul>
 *
 * <p>不同通道之间互不阻塞，同一通道内的任务严格 FIFO。</p>
 *
 * <p>claw0 参考: s10_concurrency.py 第 260-380 行 CommandQueue</p>
 */
@Service
public class CommandQueue {

    private static final Logger log = LoggerFactory.getLogger(CommandQueue.class);

    private final Map<String, LaneQueue> lanes = new ConcurrentHashMap<>();
    private final ConcurrencyProperties concurrencyProps;

    public CommandQueue(ConcurrencyProperties concurrencyProps) {
        this.concurrencyProps = concurrencyProps;
    }

    /**
     * 将任务入队到指定通道
     *
     * <p>如果通道不存在，则自动创建。通过 {@code computeIfAbsent}
     * 保证线程安全的一次性创建。</p>
     *
     * @param laneName 通道名称
     * @param task     要执行的任务
     * @return 任务的 Future
     */
    public CompletableFuture<Object> enqueue(String laneName, Callable<Object> task) {
        LaneQueue lane = lanes.computeIfAbsent(laneName,
            name -> new LaneQueue(name, getMaxConcurrency(name)));
        return lane.enqueue(task);
    }

    /**
     * 重置指定通道 — 取消所有排队任务
     *
     * @param laneName 通道名称
     */
    public void resetLane(String laneName) {
        LaneQueue lane = lanes.get(laneName);
        if (lane != null) {
            lane.reset();
            log.info("Reset lane: {}", laneName);
        }
    }

    /**
     * 重置所有通道
     */
    public void resetAll() {
        lanes.values().forEach(LaneQueue::reset);
        log.info("Reset all {} lanes", lanes.size());
    }

    /**
     * 等待所有通道空闲
     *
     * @param timeout 等待超时
     * @return true 表示所有通道已空闲
     */
    public boolean waitForAll(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (LaneQueue lane : lanes.values()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return false;
            if (!lane.waitForIdle(Duration.ofNanos(remaining))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取指定通道的状态
     *
     * @param laneName 通道名称
     * @return 通道状态，如果通道不存在返回 null
     */
    public LaneStatus getLaneStatus(String laneName) {
        LaneQueue lane = lanes.get(laneName);
        return lane != null ? lane.getStatus() : null;
    }

    /**
     * 获取指定通道的最大并发数 — 从配置读取，默认 1
     */
    private int getMaxConcurrency(String laneName) {
        if (concurrencyProps.lanes() != null) {
            ConcurrencyProperties.LaneConfig config = concurrencyProps.lanes().get(laneName);
            if (config != null) {
                return config.maxConcurrency();
            }
        }
        return 1; // 默认串行执行
    }
}
