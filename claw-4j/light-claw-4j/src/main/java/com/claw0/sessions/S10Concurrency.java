package com.claw0.sessions;

/**
 * Section 10: Concurrency -- "Named lanes serialize the chaos"
 *
 * 用命名 lane 系统替换第 07 节中的单个 ReentrantLock. 每个 lane 是一个
 * FIFO 队列, 带可配置的 maxConcurrency. 任务以 Callable 入队, 在虚拟线程
 * 中执行, 通过 CompletableFuture 返回结果.
 *
 *     Incoming Work
 *         |
 *     CommandQueue.enqueue(lane, fn)
 *         |
 *     +--------+    +--------+    +-----------+
 *     | main   |    |  cron  |    | heartbeat |
 *     | max=1  |    | max=1  |    |   max=1   |
 *     | FIFO   |    | FIFO   |    |   FIFO    |
 *     +---+----+    +---+----+    +-----+-----+
 *         |             |               |
 *     [active]      [active]        [active]
 *         |             |               |
 *     taskDone      taskDone        taskDone
 *         |             |               |
 *     pump()        pump()          pump()
 *     (dequeue      (dequeue        (dequeue
 *      next if       next if         next if
 *      active<max)   active<max)     active<max)
 *
 * 用法:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S10Concurrency"
 *
 * REPL 命令:
 *   /lanes  /queue  /enqueue <lane> <msg>  /concurrency <lane> <N>
 *   /generation  /reset  /heartbeat  /trigger  /cron  /help
 */

// region Common Imports
import com.claw0.common.AnsiColors;
import com.claw0.common.Config;
import com.claw0.common.JsonUtils;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.core.JsonValue;
// endregion

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class S10Concurrency {

    // ================================================================
    // 配置常量
    // ================================================================

    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));
    static final Path WORKSPACE_DIR = WORKDIR.resolve("workspace");

    /** 连续错误达到此阈值后自动禁用 cron 任务 */
    static final int CRON_AUTO_DISABLE_THRESHOLD = 5;

    /** Anthropic API 客户端 */
    static final AnthropicClient client = AnthropicOkHttpClient.builder()
            .fromEnv()
            .build();

    /** 默认系统提示词 */
    static final String SYSTEM_PROMPT =
            "You are Luna, a warm and curious AI companion. "
                    + "Keep replies concise and helpful. "
                    + "Use memory_write to save important facts. "
                    + "Use memory_search to recall past context.";

    // ================================================================
    // 标准 Lane 名称
    // ================================================================

    static final String LANE_MAIN = "main";
    static final String LANE_CRON = "cron";
    static final String LANE_HEARTBEAT = "heartbeat";

    // ================================================================
    // region S10-A: QueuedItem -- 队列条目
    // ================================================================

    /**
     * LaneQueue 中的队列条目.
     *
     * @param task   待执行的任务 (Callable)
     * @param future 用于返回结果的 CompletableFuture
     * @param gen    任务入队时的 generation 编号
     */
    record QueuedItem(
            Callable<Object> task,
            CompletableFuture<Object> future,
            int gen
    ) {}

    // endregion S10-A: QueuedItem

    // ================================================================
    // region S10-A: LaneQueue -- 单个命名 FIFO lane, 带并发控制
    // ================================================================

    /**
     * 命名 FIFO 队列, 最多并行运行 maxConcurrency 个任务.
     *
     * 每个入队的 Callable 在自己的虚拟线程中运行, 结果通过 CompletableFuture 投递.
     * generation 计数器支持重启恢复: 当 generation 递增后, 来自旧 generation 的
     * 过期任务完成时不会重新泵送队列.
     *
     * 线程安全: 所有状态变更都在 lock 保护下进行.
     */
    static class LaneQueue {
        private final String name;
        private volatile int maxConcurrency;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition idle = lock.newCondition();

        /**
         * 内部有界队列, 存储 (callable, future, generation) 三元组.
         * 容量 1024: 防止 OOM. 使用非阻塞的 offer/poll 而非阻塞的 put/take,
         * 因为 pump() 在 lock 保护范围内调用, 如果 put 阻塞会死锁.
         * 1024 远超实际需求 (每个 lane 通常只有个位数任务), 主要作为安全阀.
         */
        private final ArrayBlockingQueue<QueuedItem> deque;

        /** 当前活跃执行的任务数 */
        private int activeCount = 0;

        /** generation 计数器, 递增后使旧任务失效 */
        private int generation = 0;

        LaneQueue(String name, int maxConcurrency) {
            this.name = name;
            this.maxConcurrency = Math.max(1, maxConcurrency);
            this.deque = new ArrayBlockingQueue<>(1024);
        }

        /** 获取当前 generation */
        int getGeneration() {
            lock.lock();
            try {
                return generation;
            } finally {
                lock.unlock();
            }
        }

        /** 设置 generation (用于 reset_all) */
        void setGeneration(int value) {
            lock.lock();
            try {
                generation = value;
                idle.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /**
         * 将 callable 加入队列. 返回结果的 CompletableFuture.
         *
         * @param task 待执行的任务
         * @return CompletableFuture, 任务完成后可获取结果
         */
        CompletableFuture<Object> enqueue(Callable<Object> task) {
            CompletableFuture<Object> future = new CompletableFuture<>();
            lock.lock();
            try {
                QueuedItem item = new QueuedItem(task, future, generation);
                deque.offer(item);
                pump();
            } finally {
                lock.unlock();
            }
            return future;
        }

        /**
         * 从 deque 弹出任务并运行, 直到 activeCount >= maxConcurrency.
         *
         * 调用时必须持有 lock.
         */
        @SuppressWarnings("unchecked")
        void pump() {
            // assert lock.isHeldByCurrentThread(); // 仅调试用
            while (activeCount < maxConcurrency) {
                QueuedItem item = deque.poll();
                if (item == null) break;

                // 检查 generation: 过期任务直接取消
                // 场景: resetAll() 已递增 generation, 但旧任务仍在队列中.
                // 例如: 用户输入了 /reset 命令, resetAll() 将 generation 从 0 变为 1,
                // 但队列中可能还有 generation=0 的旧 heartbeat/cron 任务.
                // 此时旧任务应该被取消而非执行, 否则会与新生命周期的任务竞争.
                if (item.gen() != generation) {
                    item.future().cancel(false);
                    continue;
                }

                activeCount++;
                final QueuedItem fi = item;

                // 使用虚拟线程执行任务
                Thread.ofVirtual()
                        .name("lane-" + name + "-worker-" + fi.gen())
                        .start(() -> runTask(fi));
            }
        }

        /**
         * 执行单个任务: 运行 callable, 设置 future 结果, 然后调用 taskDone.
         */
        void runTask(QueuedItem item) {
            try {
                Object result = item.task().call();
                item.future().complete(result);
            } catch (Exception exc) {
                item.future().completeExceptionally(exc);
            } finally {
                taskDone(item.gen());
            }
        }

        /**
         * 递减活跃计数. 仅在 generation 匹配时重新泵送.
         *
         * 避免竞态条件: 如果没有 generation 检查, 旧生命周期的任务完成时会错误地触发 pump(),
         * 唤醒不应执行的新任务. 场景: resetAll() 已递增 generation 并清空了队列意图,
         * 但旧任务仍在执行中; 旧任务完成时若触发 pump, 会从空的队列中取出 null 直接返回,
         * 虽然不会出错但会做无用功; 更危险的是, 如果此时有新任务入队 (新 generation),
         * 旧任务的 taskDone 可能会错误地触发新任务的提前执行.
         * 因此只在 expectedGen == generation 时才 pump, 确保只有当前生命周期的任务
         * 才能驱动队列前进.
         */
        void taskDone(int expectedGen) {
            lock.lock();
            try {
                activeCount--;
                if (expectedGen == generation) {
                    pump();
                }
                idle.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /**
         * 阻塞直到 activeCount == 0 且 deque 为空.
         *
         * @param timeoutMs 最大等待时间 (毫秒)
         * @return true 表示达到空闲, false 表示超时
         */
        boolean waitForIdle(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            lock.lock();
            try {
                while (activeCount > 0 || !deque.isEmpty()) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) return false;
                    try {
                        idle.await(remaining, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        /**
         * 递增 generation, 使旧任务失效.
         * 用于重启恢复场景.
         */
        void resetGeneration() {
            lock.lock();
            try {
                generation++;
                idle.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** 获取 lane 的统计信息 */
        Map<String, Object> stats() {
            lock.lock();
            try {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("name", name);
                s.put("queue_depth", deque.size());
                s.put("active", activeCount);
                s.put("max_concurrency", maxConcurrency);
                s.put("generation", generation);
                return s;
            } finally {
                lock.unlock();
            }
        }

        String getName() { return name; }

        int getMaxConcurrency() { return maxConcurrency; }

        void setMaxConcurrency(int value) {
            this.maxConcurrency = Math.max(1, value);
            // 设置后可能需要泵送更多任务
            lock.lock();
            try {
                pump();
            } finally {
                lock.unlock();
            }
        }
    }

    // endregion S10-A: LaneQueue

    // ================================================================
    // region S10-B: CommandQueue -- 将工作路由到命名 lane
    // ================================================================

    /**
     * 中央调度器, 将 Callable 路由到命名的 LaneQueue.
     *
     * Lane 在首次使用时惰性创建. resetAll() 递增所有 generation 计数器,
     * 使得来自上一个生命周期的过期任务不会重新泵送队列.
     */
    static class CommandQueue {
        private final Map<String, LaneQueue> lanes = new ConcurrentHashMap<>();
        private final Object createLock = new Object();

        /**
         * 获取已有 lane 或创建新的.
         *
         * @param name           lane 名称
         * @param maxConcurrency 最大并发数
         * @return 命名的 LaneQueue
         */
        LaneQueue getOrCreateLane(String name, int maxConcurrency) {
            synchronized (createLock) {
                return lanes.computeIfAbsent(name,
                        n -> new LaneQueue(n, maxConcurrency));
            }
        }

        /** 便捷方法: 默认 maxConcurrency=1 */
        LaneQueue getOrCreateLane(String name) {
            return getOrCreateLane(name, 1);
        }

        /**
         * 将 callable 路由到指定 lane. 返回 CompletableFuture.
         *
         * @param laneName 目标 lane 名称
         * @param task     待执行的任务
         * @return CompletableFuture, 任务完成后可获取结果
         */
        CompletableFuture<Object> enqueue(String laneName, Callable<Object> task) {
            LaneQueue lane = getOrCreateLane(laneName);
            return lane.enqueue(task);
        }

        /**
         * 递增所有 lane 的 generation. 用于重启恢复.
         *
         * @return lane 名称 -> 新 generation 的 Map
         */
        Map<String, Integer> resetAll() {
            Map<String, Integer> result = new LinkedHashMap<>();
            for (Map.Entry<String, LaneQueue> entry : lanes.entrySet()) {
                entry.getValue().resetGeneration();
                result.put(entry.getKey(), entry.getValue().getGeneration());
            }
            return result;
        }

        /**
         * 等待所有 lane 变为空闲.
         *
         * @param timeoutMs 最大等待时间 (毫秒)
         * @return true 表示全部空闲
         */
        boolean waitForAll(long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            for (LaneQueue lane : lanes.values()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return false;
                if (!lane.waitForIdle(remaining)) return false;
            }
            return true;
        }

        /** 汇总所有 lane 的统计信息 */
        Map<String, Map<String, Object>> stats() {
            Map<String, Map<String, Object>> result = new LinkedHashMap<>();
            for (Map.Entry<String, LaneQueue> entry : lanes.entrySet()) {
                result.put(entry.getKey(), entry.getValue().stats());
            }
            return result;
        }

        /** 返回所有 lane 名称 */
        List<String> laneNames() {
            return new ArrayList<>(lanes.keySet());
        }
    }

    // endregion S10-B: CommandQueue

    // ================================================================
    // region S10-B: DeadlockDetector -- 死锁检测看门狗
    // ================================================================

    /**
     * 死锁检测看门狗.
     * 使用 ThreadMXBean.findDeadlockedThreads() 定期检查是否有线程死锁.
     * 检测到死锁时通过回调通知.
     */
    static class DeadlockDetector {
        private final ScheduledExecutorService scheduler;
        private final Consumer<String> onDeadlock;

        DeadlockDetector(Consumer<String> onDeadlock) {
            this.onDeadlock = onDeadlock;
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Thread.ofVirtual().name("deadlock-detector").unstarted(r);
                t.setDaemon(true);
                return t;
            });
        }

        /** 启动定期检测 (每 10 秒检查一次) */
        void start() {
            scheduler.scheduleWithFixedDelay(this::check, 10, 10, TimeUnit.SECONDS);
        }

        /** 执行一次死锁检测 */
        void check() {
            try {
                ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
                long[] deadlocked = tmx.findDeadlockedThreads();
                if (deadlocked != null && deadlocked.length > 0) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("DEADLOCK DETECTED! Thread IDs: ");
                    for (long tid : deadlocked) {
                        sb.append(tid).append(" ");
                    }
                    // 打印死锁线程的堆栈
                    for (java.lang.management.ThreadInfo info : tmx.getThreadInfo(deadlocked)) {
                        if (info != null) {
                            sb.append("\n  Thread '").append(info.getThreadName())
                                    .append("' state=").append(info.getThreadState());
                        }
                    }
                    onDeadlock.accept(sb.toString());
                }
            } catch (Exception e) {
                // ThreadMXBean 可能不可用, 静默忽略
            }
        }

        void stop() {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // endregion S10-B: DeadlockDetector

    // ================================================================
    // region S01-S07 Core: Soul + Memory
    // ================================================================

    /**
     * 灵魂系统: 加载 SOUL.md 构建 agent 人格.
     */
    static class SoulSystem {
        private final Path soulPath;

        SoulSystem(Path workspace) {
            this.soulPath = workspace.resolve("SOUL.md");
        }

        /** 加载 SOUL.md 内容, 不存在时返回默认 prompt */
        String load() {
            if (Files.isRegularFile(soulPath)) {
                try {
                    return Files.readString(soulPath).strip();
                } catch (IOException e) { /* ignore */ }
            }
            return "You are a helpful AI assistant.";
        }

        /** 构建系统提示词: SOUL.md + 额外上下文 */
        String buildSystemPrompt(String extra) {
            StringBuilder sb = new StringBuilder(load());
            if (extra != null && !extra.isEmpty()) {
                sb.append("\n\n").append(extra);
            }
            return sb.toString();
        }
    }

    /**
     * 简化的记忆存储: 仅操作 MEMORY.md 文件.
     */
    static class SimpleMemoryStore {
        private final Path memoryPath;

        SimpleMemoryStore(Path workspace) {
            this.memoryPath = workspace.resolve("MEMORY.md");
        }

        /** 加载 MEMORY.md 全文 */
        String loadEvergreen() {
            if (Files.isRegularFile(memoryPath)) {
                try {
                    return Files.readString(memoryPath).strip();
                } catch (IOException e) { /* ignore */ }
            }
            return "";
        }

        /** 追加内容到 MEMORY.md */
        String writeMemory(String content) {
            String existing = loadEvergreen();
            String updated = existing.isEmpty()
                    ? content.strip()
                    : existing + "\n\n" + content.strip();
            try {
                Files.writeString(memoryPath, updated);
                return "Memory saved (" + content.length() + " chars)";
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        /** 简单的行级搜索 */
        String searchMemory(String query) {
            String text = loadEvergreen();
            if (text.isEmpty()) return "No memories found.";
            String lower = query.toLowerCase();
            List<String> matches = text.lines()
                    .filter(line -> line.toLowerCase().contains(lower))
                    .limit(10)
                    .collect(Collectors.toList());
            return matches.isEmpty()
                    ? "No memories matching '" + query + "'."
                    : String.join("\n", matches);
        }
    }

    // 工具定义
    static Tool buildTool(String name, String description,
                          Map<String, Map<String, String>> properties,
                          List<String> required) {
        Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> pb.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object")).properties(pb.build())
                        .required(required).build())
                .build();
    }

    static final List<ToolUnion> MEMORY_TOOLS = List.of(
            ToolUnion.ofTool(buildTool("memory_write",
                    "Save an important fact or preference to long-term memory.",
                    Map.of("content", Map.of("type", "string",
                            "description", "The fact or preference to remember.")),
                    List.of("content"))),
            ToolUnion.ofTool(buildTool("memory_search",
                    "Search long-term memory for relevant information.",
                    Map.of("query", Map.of("type", "string",
                            "description", "Search query.")),
                    List.of("query")))
    );

    // endregion S01-S07 Core

    // ================================================================
    // region Agent 辅助函数
    // ================================================================

    /**
     * 单轮 LLM 调用: 不使用工具, 返回纯文本.
     * Heartbeat 和 Cron 共用此方法执行后台任务.
     */
    static String runAgentSingleTurn(String prompt, String systemPrompt) {
        String sys = systemPrompt != null ? systemPrompt
                : "You are a helpful assistant performing a background check.";
        try {
            Message response = client.messages().create(MessageCreateParams.builder()
                    .model(MODEL_ID)
                    .maxTokens(2048)
                    .system(sys)
                    .messages(List.of(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(prompt)
                            .build()))
                    .build());
            return response.content().stream()
                    .filter(ContentBlock::isText)
                    .map(ContentBlock::asText)
                    .map(TextBlock::text)
                    .collect(Collectors.joining()).strip();
        } catch (Exception e) {
            return "[agent error: " + e.getMessage() + "]";
        }
    }

    /** 获取当前 epoch 秒 */
    static double epochSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }

    // endregion Agent 辅助函数

    // ================================================================
    // region S10-B: HeartbeatRunner -- 通过 CommandQueue 入队
    // ================================================================

    /**
     * 后台心跳运行器, 将工作入队到 heartbeat lane.
     *
     * 每次 tick 检查前置条件. 如果 heartbeat lane 已有活跃工作,
     * 则跳过该 tick (非阻塞语义). 这用 lane 感知的检查替代了
     * 第 07 节中原始的 ReentrantLock.tryLock() 模式.
     */
    static class HeartbeatRunner {
        private final Path workspace;
        private final Path heartbeatPath;
        private final CommandQueue commandQueue;
        private final double intervalSeconds;
        private final int activeHourStart;
        private final int activeHourEnd;
        private final SoulSystem soul;
        private final SimpleMemoryStore memory;

        // 状态
        private volatile boolean stopped = false;
        private volatile double lastRunAt = 0.0;
        private volatile String lastOutput = "";
        private final ConcurrentLinkedQueue<String> outputQueue = new ConcurrentLinkedQueue<>();
        private ScheduledExecutorService scheduler;

        HeartbeatRunner(Path workspace, CommandQueue commandQueue,
                        double intervalSeconds, int activeHourStart, int activeHourEnd) {
            this.workspace = workspace;
            this.heartbeatPath = workspace.resolve("HEARTBEAT.md");
            this.commandQueue = commandQueue;
            this.intervalSeconds = intervalSeconds;
            this.activeHourStart = activeHourStart;
            this.activeHourEnd = activeHourEnd;
            this.soul = new SoulSystem(workspace);
            this.memory = new SimpleMemoryStore(workspace);
        }

        /**
         * 心跳尝试前的前置条件检查.
         *
         * @return [是否应该运行, 原因说明]
         */
        ShouldRunResult shouldRun() {
            if (!Files.exists(heartbeatPath))
                return new ShouldRunResult(false, "HEARTBEAT.md not found");
            try {
                if (Files.readString(heartbeatPath).strip().isEmpty())
                    return new ShouldRunResult(false, "HEARTBEAT.md is empty");
            } catch (IOException e) {
                return new ShouldRunResult(false, "HEARTBEAT.md read error");
            }
            double now = epochSeconds();
            double elapsed = now - lastRunAt;
            if (elapsed < intervalSeconds) {
                double remaining = intervalSeconds - elapsed;
                return new ShouldRunResult(false,
                        "interval not elapsed (" + String.format("%.0f", remaining) + "s remaining)");
            }
            int hour = LocalDateTime.now().getHour();
            boolean inHours = activeHourStart <= activeHourEnd
                    ? (activeHourStart <= hour && hour < activeHourEnd)
                    : !(activeHourEnd <= hour && hour < activeHourStart);
            if (!inHours)
                return new ShouldRunResult(false,
                        "outside active hours (" + activeHourStart + ":00-" + activeHourEnd + ":00)");
            return new ShouldRunResult(true, "all checks passed");
        }

        /** 构建心跳 prompt */
        String[] buildHeartbeatPrompt() {
            String instructions;
            try {
                instructions = Files.readString(heartbeatPath).strip();
            } catch (IOException e) {
                instructions = "";
            }
            String mem = memory.loadEvergreen();
            String extra = "";
            if (!mem.isEmpty()) extra = "## Known Context\n\n" + mem + "\n\n";
            extra += "Current time: " + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return new String[]{instructions, soul.buildSystemPrompt(extra)};
        }

        /** 解析心跳响应 */
        String parseResponse(String response) {
            if (response.contains("HEARTBEAT_OK")) {
                String stripped = response.replace("HEARTBEAT_OK", "").strip();
                return stripped.length() > 5 ? stripped : null;
            }
            return response.strip().isEmpty() ? null : response.strip();
        }

        /**
         * 一次心跳 tick. 仅在 lane 不忙时入队工作.
         * 用 lane 感知的检查替代 ReentrantLock.tryLock().
         */
        void heartbeatTick() {
            ShouldRunResult check = shouldRun();
            if (!check.shouldRun) return;

            // 检查 heartbeat lane 是否已有活跃工作 (非阻塞语义)
            LaneQueue lane = commandQueue.getOrCreateLane(LANE_HEARTBEAT);
            Map<String, Object> laneStats = lane.stats();
            if ((int) laneStats.get("active") > 0) return;

            // 将心跳工作入队到 heartbeat lane
            CompletableFuture<Object> future = commandQueue.enqueue(LANE_HEARTBEAT, () -> {
                String[] prompts = buildHeartbeatPrompt();
                if (prompts[0].isEmpty()) return null;
                String response = runAgentSingleTurn(prompts[0], prompts[1]);
                return parseResponse(response);
            });

            // 注册完成回调
            future.whenComplete((result, exc) -> {
                lastRunAt = epochSeconds();
                if (exc != null) {
                    outputQueue.add("[heartbeat error: " + exc.getMessage() + "]");
                    return;
                }
                if (result == null) return;
                String meaningful = result.toString();
                if (meaningful.strip().equals(lastOutput)) return;
                lastOutput = meaningful.strip();
                outputQueue.add(meaningful);
                printLane(LANE_HEARTBEAT, "output queued (" + meaningful.length() + " chars)");
            });
        }

        /** 启动心跳后台线程 */
        void start() {
            if (scheduler != null) return;
            stopped = false;
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Thread.ofVirtual().name("heartbeat-timer").unstarted(r);
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    heartbeatTick();
                } catch (Exception e) { /* ignore */ }
            }, 1, 1, TimeUnit.SECONDS);
        }

        /** 停止心跳后台线程 */
        void stop() {
            stopped = true;
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    scheduler.awaitTermination(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                scheduler = null;
            }
        }

        /** 排空输出队列 */
        List<String> drainOutput() {
            List<String> items = new ArrayList<>();
            while (!outputQueue.isEmpty()) {
                String item = outputQueue.poll();
                if (item != null) items.add(item);
            }
            return items;
        }

        /** 获取心跳状态信息 */
        Map<String, Object> status() {
            double now = epochSeconds();
            Double elapsed = lastRunAt > 0 ? now - lastRunAt : null;
            double nextIn = elapsed != null
                    ? Math.max(0.0, intervalSeconds - elapsed)
                    : intervalSeconds;
            ShouldRunResult check = shouldRun();

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("enabled", Files.exists(heartbeatPath));
            s.put("should_run", check.shouldRun);
            s.put("reason", check.reason);
            s.put("last_run", lastRunAt > 0
                    ? Instant.ofEpochSecond((long) lastRunAt).toString()
                    : "never");
            s.put("next_in", String.format("%.0f", nextIn) + "s");
            s.put("interval", String.format("%.0f", intervalSeconds) + "s");
            s.put("active_hours", activeHourStart + ":00-" + activeHourEnd + ":00");
            s.put("queue_size", outputQueue.size());
            return s;
        }
    }

    /** shouldRun() 返回值 */
    record ShouldRunResult(boolean shouldRun, String reason) {}

    // endregion S10-B: HeartbeatRunner

    // ================================================================
    // region S10-B: CronService -- 通过 CommandQueue 入队
    // ================================================================

    /**
     * 简化的 cron 服务, 将任务入队到 cron lane.
     * 从 CRON.json 加载定时任务, 每秒检查是否有到期任务, 入队到 cron lane 执行.
     */
    static class CronService {
        private final Path cronFile;
        private final CommandQueue commandQueue;
        final List<CronJob> jobs = new ArrayList<>();
        private final ConcurrentLinkedQueue<String> outputQueue = new ConcurrentLinkedQueue<>();

        CronService(Path cronFile, CommandQueue commandQueue) {
            this.cronFile = cronFile;
            this.commandQueue = commandQueue;
            loadJobs();
        }

        /** 从 CRON.json 加载任务定义 */
        @SuppressWarnings("unchecked")
        void loadJobs() {
            jobs.clear();
            if (!Files.isRegularFile(cronFile)) return;

            Map<String, Object> raw;
            try {
                raw = JsonUtils.toMap(Files.readString(cronFile));
            } catch (Exception e) {
                AnsiColors.printError("  CRON.json load error: " + e.getMessage());
                return;
            }

            double now = epochSeconds();
            List<Map<String, Object>> jobDefs =
                    (List<Map<String, Object>>) raw.getOrDefault("jobs", List.of());

            for (Map<String, Object> jd : jobDefs) {
                Map<String, Object> sched =
                        (Map<String, Object>) jd.getOrDefault("schedule", Map.of());
                String kind = (String) sched.getOrDefault("kind", "");
                // 支持 "every" 和 "cron" 类型, 这里简化为 every_seconds
                double everySeconds = ((Number) sched.getOrDefault("every_seconds", 0)).doubleValue();
                if (everySeconds <= 0 && !"at".equals(kind)) continue;

                CronJob job = new CronJob(
                        (String) jd.getOrDefault("id", ""),
                        (String) jd.getOrDefault("name", ""),
                        (Boolean) jd.getOrDefault("enabled", true),
                        kind,
                        sched,
                        (Map<String, Object>) jd.getOrDefault("payload", Map.of()),
                        (Boolean) jd.getOrDefault("delete_after_run", false)
                );

                if ("every".equals(kind) && everySeconds > 0) {
                    job.nextRunAt = now + everySeconds;
                    job.everySeconds = everySeconds;
                }
                jobs.add(job);
            }
        }

        /** 每秒调用一次; 检查并执行到期的任务 */
        void cronTick() {
            double now = epochSeconds();
            List<String> removeIds = new ArrayList<>();

            for (CronJob job : jobs) {
                if (!job.enabled || job.nextRunAt <= 0 || now < job.nextRunAt) continue;
                enqueueJob(job, now);
                if (job.deleteAfterRun && "at".equals(job.scheduleKind)) {
                    removeIds.add(job.id);
                }
            }

            if (!removeIds.isEmpty()) {
                jobs.removeIf(j -> removeIds.contains(j.id));
            }
        }

        /**
         * 将到期任务入队到 cron lane.
         */
        void enqueueJob(CronJob job, double now) {
            String message = (String) job.payload.getOrDefault("message", "");
            String jobName = job.name;

            if (message.isEmpty()) {
                if (job.everySeconds > 0) {
                    job.nextRunAt = now + job.everySeconds;
                }
                return;
            }

            // 将任务入队到 cron lane
            CompletableFuture<Object> future = commandQueue.enqueue(LANE_CRON, () -> {
                String sysPrompt = "You are performing a scheduled background task. Be concise. "
                        + "Current time: " + LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return runAgentSingleTurn(message, sysPrompt);
            });

            // 注册完成回调
            future.whenComplete((result, exc) -> {
                job.lastRunAt = epochSeconds();
                if (job.everySeconds > 0) {
                    job.nextRunAt = epochSeconds() + job.everySeconds;
                }

                if (exc != null) {
                    job.consecutiveErrors++;
                    outputQueue.add("[" + jobName + "] error: " + exc.getMessage());
                    if (job.consecutiveErrors >= CRON_AUTO_DISABLE_THRESHOLD) {
                        job.enabled = false;
                        String msg = "Job '" + jobName + "' auto-disabled after "
                                + job.consecutiveErrors + " consecutive errors";
                        AnsiColors.printError("  " + msg);
                        outputQueue.add(msg);
                    }
                } else {
                    job.consecutiveErrors = 0;
                    String output = result != null ? result.toString() : "";
                    if (!output.isEmpty()) {
                        outputQueue.add("[" + jobName + "] " + output);
                        printLane(LANE_CRON, "job '" + jobName + "' completed");
                    }
                }
            });

            // 立即设置下次运行时间 (不等任务完成)
            if (job.everySeconds > 0) {
                job.nextRunAt = now + job.everySeconds;
            }
        }

        /** 排空输出队列 */
        List<String> drainOutput() {
            List<String> items = new ArrayList<>();
            while (!outputQueue.isEmpty()) {
                String item = outputQueue.poll();
                if (item != null) items.add(item);
            }
            return items;
        }

        /** 列出所有任务的状态 */
        List<Map<String, Object>> listJobs() {
            double now = epochSeconds();
            List<Map<String, Object>> result = new ArrayList<>();
            for (CronJob j : jobs) {
                Double nxt = j.nextRunAt > 0 ? Math.max(0.0, j.nextRunAt - now) : null;
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", j.id);
                info.put("name", j.name);
                info.put("enabled", j.enabled);
                info.put("every_seconds", j.everySeconds);
                info.put("errors", j.consecutiveErrors);
                info.put("last_run", j.lastRunAt > 0
                        ? Instant.ofEpochSecond((long) j.lastRunAt).toString()
                        : "never");
                info.put("next_in", nxt != null ? Math.round(nxt) : null);
                result.add(info);
            }
            return result;
        }
    }

    /**
     * 定时任务定义 (简化版, 仅支持 "every" 类型).
     */
    static class CronJob {
        String id;
        String name;
        boolean enabled;
        String scheduleKind;
        Map<String, Object> scheduleConfig;
        Map<String, Object> payload;
        boolean deleteAfterRun;
        double everySeconds;
        int consecutiveErrors;
        double lastRunAt;
        double nextRunAt;

        CronJob(String id, String name, boolean enabled, String scheduleKind,
                Map<String, Object> scheduleConfig, Map<String, Object> payload,
                boolean deleteAfterRun) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.scheduleKind = scheduleKind;
            this.scheduleConfig = scheduleConfig;
            this.payload = payload;
            this.deleteAfterRun = deleteAfterRun;
            this.everySeconds = 0;
            this.consecutiveErrors = 0;
            this.lastRunAt = 0.0;
            this.nextRunAt = 0.0;
        }
    }

    // endregion S10-B: CronService

    // ================================================================
    // 辅助方法
    // ================================================================

    /** 打印 lane 消息 (带颜色标记) */
    static void printLane(String laneName, String text) {
        String color = switch (laneName) {
            case "main" -> AnsiColors.CYAN;
            case "cron" -> AnsiColors.MAGENTA;
            case "heartbeat" -> AnsiColors.BLUE;
            default -> AnsiColors.YELLOW;
        };
        System.out.println(color + AnsiColors.BOLD + "[" + laneName + "]"
                + AnsiColors.RESET + " " + text);
    }

    // ================================================================
    // REPL 命令处理
    // ================================================================

    /** 打印 REPL 帮助信息 */
    static void printReplHelp() {
        AnsiColors.printInfo("REPL commands:");
        AnsiColors.printInfo("  /lanes                    -- show all lanes with stats");
        AnsiColors.printInfo("  /queue                    -- show pending items per lane");
        AnsiColors.printInfo("  /enqueue <lane> <message> -- manually enqueue work into a lane");
        AnsiColors.printInfo("  /concurrency <lane> <N>   -- change max_concurrency for a lane");
        AnsiColors.printInfo("  /generation               -- show generation counters");
        AnsiColors.printInfo("  /reset                    -- simulate restart (reset_all)");
        AnsiColors.printInfo("  /heartbeat                -- heartbeat status");
        AnsiColors.printInfo("  /trigger                  -- force heartbeat now");
        AnsiColors.printInfo("  /cron                     -- list cron jobs");
        AnsiColors.printInfo("  /help                     -- this help");
        AnsiColors.printInfo("  quit / exit               -- exit");
    }

    // ================================================================
    // Agent Loop (带 Concurrency 的完整 REPL 循环)
    // ================================================================

    /**
     * 完整的 Agent 循环, 集成 CommandQueue, HeartbeatRunner, CronService.
     *
     * 启动阶段:
     *   1. 创建 CommandQueue, 预注册三个 lane (main/cron/heartbeat), maxConcurrency=1
     *   2. 初始化 HeartbeatRunner (通过 CommandQueue 入队)
     *   3. 初始化 CronService (通过 CommandQueue 入队)
     *   4. 启动死锁检测看门狗
     *   5. 注册 ShutdownHook
     *
     * REPL 循环:
     *   用户对话通过 main lane 序列化执行, 等待 future.get() 返回结果.
     *   Heartbeat 和 Cron 独立在各自的 lane 中运行, 互不干扰.
     */
    static void agentLoop() {
        // ---- 初始化 ----

        // 创建 CommandQueue 和默认 lane
        CommandQueue cmdQueue = new CommandQueue();
        cmdQueue.getOrCreateLane(LANE_MAIN, 1);
        cmdQueue.getOrCreateLane(LANE_CRON, 1);
        cmdQueue.getOrCreateLane(LANE_HEARTBEAT, 1);

        SoulSystem soul = new SoulSystem(WORKSPACE_DIR);
        SimpleMemoryStore memory = new SimpleMemoryStore(WORKSPACE_DIR);

        // HeartbeatRunner
        HeartbeatRunner heartbeat = new HeartbeatRunner(
                WORKSPACE_DIR, cmdQueue,
                Double.parseDouble(Config.get("HEARTBEAT_INTERVAL", "1800")),
                Integer.parseInt(Config.get("HEARTBEAT_ACTIVE_START", "9")),
                Integer.parseInt(Config.get("HEARTBEAT_ACTIVE_END", "22"))
        );

        // CronService
        CronService cronSvc = new CronService(WORKSPACE_DIR.resolve("CRON.json"), cmdQueue);

        // 启动 HeartbeatRunner 后台线程
        heartbeat.start();

        // 启动 Cron tick 后台线程
        ScheduledExecutorService cronScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().name("cron-tick").unstarted(r);
            t.setDaemon(true);
            return t;
        });
        cronScheduler.scheduleWithFixedDelay(() -> {
            try {
                cronSvc.cronTick();
            } catch (Exception e) { /* ignore */ }
        }, 1, 1, TimeUnit.SECONDS);

        // 启动死锁检测看门狗
        DeadlockDetector deadlockDetector = new DeadlockDetector(msg -> {
            AnsiColors.printError("  " + msg);
        });
        deadlockDetector.start();

        // 注册 ShutdownHook
        Thread shutdownHook = new Thread(() -> {
            AnsiColors.printInfo("\n  Shutting down...");
            heartbeat.stop();
            cronScheduler.shutdown();
            try {
                cronScheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            deadlockDetector.stop();
            cmdQueue.waitForAll(3000);
            AnsiColors.printInfo("  Goodbye.");
        }, "shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        // 构建系统提示词
        List<MessageParam> messages = new ArrayList<>();
        String memText = memory.loadEvergreen();
        String extra = memText.isEmpty() ? "" : "## Long-term Memory\n\n" + memText;
        String systemPrompt = soul.buildSystemPrompt(extra);

        // 打印 banner
        Map<String, Map<String, Object>> laneStats = cmdQueue.stats();
        Map<String, Object> hbStatus = heartbeat.status();
        AnsiColors.printInfo("============================================================");
        AnsiColors.printInfo("  claw0  |  Section 10: Concurrency");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("  Lanes: " + String.join(", ", laneStats.keySet()));
        AnsiColors.printInfo("  Heartbeat: " + ((boolean) hbStatus.get("enabled") ? "on" : "off")
                + " (" + hbStatus.get("interval") + ")");
        AnsiColors.printInfo("  Cron jobs: " + cronSvc.jobs.size());
        AnsiColors.printInfo("  Deadlock detector: active");
        AnsiColors.printInfo("  /help for commands. quit to exit.");
        AnsiColors.printInfo("============================================================");
        System.out.println();

        // ---- REPL 循环 ----
        Scanner scanner = new Scanner(System.in);
        while (true) {
            // 排空 heartbeat 和 cron 的输出
            for (String msg : heartbeat.drainOutput()) printLane(LANE_HEARTBEAT, msg);
            for (String msg : cronSvc.drainOutput()) printLane(LANE_CRON, msg);

            String userInput;
            try {
                System.out.print(AnsiColors.coloredPrompt());
                userInput = scanner.nextLine().strip();
            } catch (Exception e) {
                break;
            }

            if (userInput.isEmpty()) continue;
            if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) {
                break;
            }

            // ---- REPL 斜杠命令 ----
            if (userInput.startsWith("/")) {
                String[] cmdParts = userInput.split("\\s+", 3);
                String cmd = cmdParts[0].toLowerCase();

                switch (cmd) {
                    case "/help" -> printReplHelp();

                    case "/lanes" -> {
                        Map<String, Map<String, Object>> allStats = cmdQueue.stats();
                        if (allStats.isEmpty()) {
                            AnsiColors.printInfo("  No lanes.");
                        }
                        for (Map.Entry<String, Map<String, Object>> entry : allStats.entrySet()) {
                            Map<String, Object> st = entry.getValue();
                            int active = (int) st.get("active");
                            int maxC = (int) st.get("max_concurrency");
                            StringBuilder bar = new StringBuilder();
                            for (int i = 0; i < maxC; i++) {
                                bar.append(i < active ? "*" : ".");
                            }
                            AnsiColors.printInfo(
                                    String.format("  %-12s  active=[%s]  queued=%d  max=%d  gen=%d",
                                            entry.getKey(), bar,
                                            st.get("queue_depth"),
                                            st.get("max_concurrency"),
                                            st.get("generation")));
                        }
                    }

                    case "/queue" -> {
                        Map<String, Map<String, Object>> allStats = cmdQueue.stats();
                        int total = allStats.values().stream()
                                .mapToInt(st -> (int) st.get("queue_depth"))
                                .sum();
                        if (total == 0) {
                            AnsiColors.printInfo("  All lanes empty.");
                        } else {
                            for (Map.Entry<String, Map<String, Object>> entry : allStats.entrySet()) {
                                Map<String, Object> st = entry.getValue();
                                if ((int) st.get("queue_depth") > 0 || (int) st.get("active") > 0) {
                                    AnsiColors.printInfo("  " + entry.getKey() + ": "
                                            + st.get("queue_depth") + " queued, "
                                            + st.get("active") + " active");
                                }
                            }
                        }
                    }

                    case "/enqueue" -> {
                        if (cmdParts.length < 3) {
                            System.out.println(AnsiColors.YELLOW
                                    + "  Usage: /enqueue <lane> <message>" + AnsiColors.RESET);
                        } else {
                            String laneName = cmdParts[1];
                            String message = cmdParts[2];
                            AnsiColors.printInfo("  Enqueueing into '" + laneName + "': "
                                    + message.substring(0, Math.min(60, message.length())) + "...");

                            String finalMessage = message;
                            CompletableFuture<Object> future = cmdQueue.enqueue(laneName, () -> {
                                return runAgentSingleTurn(finalMessage, null);
                            });

                            future.whenComplete((result, exc) -> {
                                if (exc != null) {
                                    printLane(laneName, "error: " + exc.getMessage());
                                } else {
                                    String text = result != null ? result.toString() : "(no output)";
                                    String preview = text.substring(0, Math.min(200, text.length()));
                                    printLane(laneName, "result: " + preview);
                                }
                            });
                        }
                    }

                    case "/concurrency" -> {
                        if (cmdParts.length < 3) {
                            System.out.println(AnsiColors.YELLOW
                                    + "  Usage: /concurrency <lane> <N>" + AnsiColors.RESET);
                        } else {
                            String laneName = cmdParts[1];
                            try {
                                int newMax = Math.max(1, Integer.parseInt(cmdParts[2]));
                                LaneQueue lane = cmdQueue.getOrCreateLane(laneName);
                                int oldMax = lane.getMaxConcurrency();
                                lane.setMaxConcurrency(newMax);
                                AnsiColors.printInfo("  " + laneName + ": max_concurrency "
                                        + oldMax + " -> " + newMax);
                            } catch (NumberFormatException e) {
                                System.out.println(AnsiColors.YELLOW
                                        + "  N must be an integer." + AnsiColors.RESET);
                            }
                        }
                    }

                    case "/generation" -> {
                        Map<String, Map<String, Object>> allStats = cmdQueue.stats();
                        for (Map.Entry<String, Map<String, Object>> entry : allStats.entrySet()) {
                            AnsiColors.printInfo("  " + entry.getKey()
                                    + ": generation=" + entry.getValue().get("generation"));
                        }
                    }

                    case "/reset" -> {
                        Map<String, Integer> result = cmdQueue.resetAll();
                        AnsiColors.printInfo("  Generation incremented on all lanes:");
                        for (Map.Entry<String, Integer> entry : result.entrySet()) {
                            AnsiColors.printInfo("    " + entry.getKey()
                                    + ": generation -> " + entry.getValue());
                        }
                        AnsiColors.printInfo("  Stale tasks from the old generation will be ignored.");
                    }

                    case "/heartbeat" -> {
                        for (Map.Entry<String, Object> e : heartbeat.status().entrySet()) {
                            AnsiColors.printInfo("  " + e.getKey() + ": " + e.getValue());
                        }
                    }

                    case "/trigger" -> {
                        heartbeat.heartbeatTick();
                        AnsiColors.printInfo("  Heartbeat tick triggered.");
                        // 临时方案: 等待 500ms 让异步心跳完成后再排空输出队列.
                        // 正式做法应使用 CompletableFuture.get(timeout) 等待完成,
                        // 但 heartbeatTick 内部入队到 CommandQueue, 涉及多层异步调用,
                        // 简化为固定等待. 500ms 足够覆盖绝大多数网络延迟.
                        try { Thread.sleep(500); } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        for (String m : heartbeat.drainOutput()) printLane(LANE_HEARTBEAT, m);
                    }

                    case "/cron" -> {
                        List<Map<String, Object>> jobs = cronSvc.listJobs();
                        if (jobs.isEmpty()) AnsiColors.printInfo("  No cron jobs.");
                        for (Map<String, Object> j : jobs) {
                            String tag = (boolean) j.get("enabled")
                                    ? AnsiColors.GREEN + "ON" + AnsiColors.RESET
                                    : AnsiColors.RED + "OFF" + AnsiColors.RESET;
                            String err = (int) j.get("errors") > 0
                                    ? " " + AnsiColors.YELLOW + "err:" + j.get("errors") + AnsiColors.RESET
                                    : "";
                            Object nextIn = j.get("next_in");
                            String nxt = nextIn != null ? " in " + nextIn + "s" : "";
                            System.out.println("  [" + tag + "] " + j.get("id") + " - "
                                    + j.get("name") + err + nxt);
                        }
                    }

                    default -> System.out.println(AnsiColors.YELLOW
                            + "  Unknown: " + cmd + ". /help for commands." + AnsiColors.RESET);
                }
                continue;
            }

            // ---- 用户对话: 入队到 main lane 并等待结果 ----
            printLane(LANE_MAIN, "processing...");

            String finalUserInput = userInput;
            CompletableFuture<Object> future = cmdQueue.enqueue(LANE_MAIN, () -> {
                return executeUserTurn(finalUserInput, messages, systemPrompt, memory);
            });

            try {
                Object result = future.get(120, TimeUnit.SECONDS);
                String resultText = result != null ? result.toString() : "";
                if (!resultText.isEmpty()) {
                    AnsiColors.printAssistant(resultText);
                }
            } catch (java.util.concurrent.TimeoutException e) {
                System.out.println("\n" + AnsiColors.YELLOW + "Request timed out."
                        + AnsiColors.RESET + "\n");
            } catch (Exception e) {
                System.out.println("\n" + AnsiColors.YELLOW + "Error: " + e.getMessage()
                        + AnsiColors.RESET + "\n");
            }
        }

        // ---- 清理 ----
        heartbeat.stop();
        cronScheduler.shutdown();
        try {
            cronScheduler.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deadlockDetector.stop();
        cmdQueue.waitForAll(3000);

        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) { /* JVM 已在关闭 */ }
    }

    /**
     * 执行一次完整的用户对话回合 (在 main lane 的虚拟线程中运行).
     *
     * 包含完整的工具调用循环, 直到 end_turn 或错误.
     * 内循环步骤:
     *   步骤 1: 调用 Claude API, 获取本轮回复
     *   步骤 2: 将 assistant 回复加入消息历史 (保持 user/assistant 交替)
     *   步骤 3a: end_turn -> 提取文本, 返回给用户
     *   步骤 3b: tool_use -> 执行工具, 将结果作为 user 消息追加, 回到步骤 1
     *   步骤 3c: 其他 stop reason -> 尽量提取已有文本, 返回
     *
     * @param userMsg     用户输入消息
     * @param messages    消息历史 (会被修改)
     * @param systemPrompt 系统提示词
     * @param memory      记忆存储
     * @return 最终的文本回复, 或错误信息
     */
    static String executeUserTurn(String userMsg, List<MessageParam> messages,
                                   String systemPrompt, SimpleMemoryStore memory) {
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(userMsg)
                .build());

        String finalText = "";

        // 工具调用内循环: 连续处理 LLM 的工具调用请求, 直到 end_turn
        while (true) {
            // 步骤 1: 调用 Claude API
            Message response;
            try {
                response = client.messages().create(MessageCreateParams.builder()
                        .model(MODEL_ID)
                        .maxTokens(8096)
                        .system(systemPrompt)
                        .tools(MEMORY_TOOLS)
                        .messages(messages)
                        .build());
            } catch (Exception exc) {
                // API 异常: 回滚消息
                while (!messages.isEmpty()
                        && messages.get(messages.size() - 1).role() != MessageParam.Role.USER) {
                    messages.remove(messages.size() - 1);
                }
                if (!messages.isEmpty()) messages.remove(messages.size() - 1);
                return "[API Error: " + exc.getMessage() + "]";
            }

            // 步骤 2: 将 assistant 回复加入消息历史 (保持 user/assistant 交替)
            messages.add(response.toParam());
            StopReason reason = response.stopReason().orElse(null);

            if (reason == StopReason.END_TURN) {
                // 步骤 3a: 正常结束 -- 提取所有 TextBlock, 拼接为最终回复
                finalText = response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
                break;
            } else if (reason == StopReason.TOOL_USE) {
                // 步骤 3b: 工具调用 -- 遍历所有 ToolUseBlock, 逐个执行并收集结果
                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (!block.isToolUse()) continue;
                    ToolUseBlock tu = block.asToolUse();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> toolInput = tu._input().convert(Map.class);
                    AnsiColors.printTool(tu.name(), "");
                    String result;
                    if ("memory_write".equals(tu.name())) {
                        result = memory.writeMemory((String) toolInput.getOrDefault("content", ""));
                    } else if ("memory_search".equals(tu.name())) {
                        result = memory.searchMemory((String) toolInput.getOrDefault("query", ""));
                    } else {
                        result = "Error: Unknown tool '" + tu.name() + "'";
                    }
                    results.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(tu.id())
                                    .content(result)
                                    .build()));
                }
                // 步骤 4: 将工具结果作为 user 消息追加, LLM 将在下一轮看到结果
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(results)
                        .build());
                // 回到步骤 1, 继续循环
            } else {
                // 步骤 3c: 其他 stop reason (max_tokens 等) -- 尽量提取已有文本
                AnsiColors.printInfo("[stop_reason=" + reason + "]");
                finalText = response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
                break;
            }
        }

        return finalText;
    }

    // ================================================================
    // 入口
    // ================================================================

    public static void main(String[] args) {
        String apiKey = Config.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.startsWith("sk-ant-x")) {
            AnsiColors.printError("Error: ANTHROPIC_API_KEY not set.");
            AnsiColors.printInfo("Copy .env.example to .env and fill in your key.");
            System.exit(1);
        }
        agentLoop();
    }
}
