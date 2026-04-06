package com.openclaw.enterprise.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 命名通道队列 — FIFO 顺序保证 + 代数跟踪 + 虚拟线程工作器
 *
 * <p>核心保证：</p>
 * <ul>
 *   <li><b>FIFO 顺序</b> — 同一通道的任务严格按入队顺序执行</li>
 *   <li><b>代数跟踪</b> — {@code reset()} 后旧任务被取消，新任务正常执行</li>
 *   <li><b>虚拟线程</b> — 工作器在虚拟线程上运行，不受平台线程数限制</li>
 * </ul>
 *
 * <p>代数机制：每次 {@code reset()} 递增代数。入队时记录当前代数。
 * 当 pump 取出任务时，如果任务的代数不等于当前代数，则跳过并取消。
 * 活跃任务的完成回调也检查代数，过期结果被丢弃。</p>
 *
 * <p>claw0 参考: s10_concurrency.py 第 80-250 行 LaneQueue</p>
 */
public class LaneQueue {

    private static final Logger log = LoggerFactory.getLogger(LaneQueue.class);

    private final String name;
    private final int maxConcurrency;

    private final Deque<QueuedItem> deque = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idleCondition = lock.newCondition();
    private final AtomicInteger generation = new AtomicInteger(0);
    private int activeCount = 0;

    public LaneQueue(String name, int maxConcurrency) {
        this.name = name;
        this.maxConcurrency = maxConcurrency;
    }

    /**
     * 将任务入队 — FIFO 尾部追加
     *
     * <p>入队后触发 pump 启动工作器。返回的 CompletableFuture
     * 在任务完成 (或取消) 时被设置。</p>
     *
     * @param task 要执行的任务
     * @return 任务的 Future
     */
    public CompletableFuture<Object> enqueue(Callable<Object> task) {
        lock.lock();
        try {
            CompletableFuture<Object> future = new CompletableFuture<>();
            int gen = generation.get();
            deque.addLast(new QueuedItem(task, future, gen));
            log.debug("Lane '{}': enqueued task (gen={}, queueSize={})",
                name, gen, deque.size());
            pump();
            return future;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重置通道 — 递增代数并取消所有排队任务
     *
     * <p>正在执行的任务会完成，但其结果在 {@code taskDone()} 中
     * 因代数不匹配而被丢弃。新的入队操作使用新的代数。</p>
     */
    public void reset() {
        lock.lock();
        try {
            int oldGen = generation.getAndIncrement();
            log.info("Lane '{}': reset (gen {} -> {}), cancelling {} queued tasks",
                name, oldGen, generation.get(), deque.size());

            // 取消所有排队任务的 Future
            QueuedItem item;
            while ((item = deque.pollFirst()) != null) {
                item.future().cancel(false);
            }

            // 如果当前无活跃任务，通知等待者
            if (activeCount == 0) {
                idleCondition.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 等待通道空闲 — 阻塞直到无活跃任务且队列为空，或超时
     *
     * @param timeout 等待超时
     * @return true 表示通道已空闲，false 表示超时
     */
    public boolean waitForIdle(Duration timeout) {
        lock.lock();
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (activeCount > 0 || !deque.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return false;
                long awaitResult = idleCondition.awaitNanos(remaining);
                if (awaitResult <= 0 && (activeCount > 0 || !deque.isEmpty())) {
                    return false;
                }
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取通道状态快照
     */
    public LaneStatus getStatus() {
        lock.lock();
        try {
            return new LaneStatus(name, activeCount, deque.size(), generation.get());
        } finally {
            lock.unlock();
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 启动工作器 — 必须持有锁时调用
     *
     * <p>当 activeCount < maxConcurrency 且队列非空时，
     * 取出队头任务并启动虚拟线程工作器。</p>
     */
    private void pump() {
        // assert lock.isHeldByCurrentThread();
        while (activeCount < maxConcurrency && !deque.isEmpty()) {
            QueuedItem item = deque.pollFirst();

            // 跳过过期代数的任务
            if (item.generation() != generation.get()) {
                item.future().cancel(false);
                log.debug("Lane '{}': skipped stale task (gen={}, current={})",
                    name, item.generation(), generation.get());
                continue;
            }

            activeCount++;
            int expectedGen = item.generation();

            // 启动虚拟线程工作器
            Thread.ofVirtual().name("lane-" + name + "-worker").start(() -> {
                try {
                    Object result = item.task().call();
                    item.future().complete(result);
                } catch (Throwable t) {
                    item.future().completeExceptionally(t);
                } finally {
                    taskDone(expectedGen);
                }
            });
        }
    }

    /**
     * 任务完成回调 — 减少活跃计数，可能启动下一个排队任务
     *
     * @param expectedGeneration 任务入队时的代数
     */
    private void taskDone(int expectedGeneration) {
        lock.lock();
        try {
            activeCount--;

            if (activeCount == 0 && deque.isEmpty()) {
                // 通道空闲 — 通知等待者
                idleCondition.signalAll();
            } else {
                // 尝试启动下一个排队任务
                pump();
            }
        } finally {
            lock.unlock();
        }
    }
}
