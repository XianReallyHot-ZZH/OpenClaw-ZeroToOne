package com.claw0.sessions;

/**
 * Section 08: Delivery -- "Write to disk first, then try to send"
 *
 * 所有出站消息都经过可靠的投递队列.
 * 如果发送失败, 使用退避策略重试. 如果进程崩溃, 重启时扫描磁盘恢复.
 *
 *     Agent Reply / Heartbeat / Cron
 *               |
 *         chunkMessage()       -- 按平台限制分片
 *               |
 *         DeliveryQueue.enqueue()  -- 写入磁盘 (预写日志)
 *               |
 *         DeliveryRunner (后台线程)
 *               |
 *          deliver_fn(channel, to, text)
 *             /     \
 *          success    failure
 *            |           |
 *          ack()      fail() + backoff
 *            |           |
 *          delete      retry or move_to_failed/
 *
 *     指数退避: [5s, 25s, 2min, 10min]
 *     最大重试次数: 5
 *
 * 用法:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S08Delivery"
 *
 * REPL 命令:
 *   /queue  /failed  /retry  /simulate-failure  /heartbeat  /trigger  /stats
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class S08Delivery {

    // ================================================================
    // 配置常量
    // ================================================================

    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));
    static final Path WORKSPACE_DIR = WORKDIR.resolve("workspace");
    static final Path QUEUE_DIR = WORKSPACE_DIR.resolve("delivery-queue");
    static final Path FAILED_DIR = QUEUE_DIR.resolve("failed");

    /** 退避时间表 (毫秒): [5s, 25s, 2min, 10min] */
    static final int[] BACKOFF_MS = {5_000, 25_000, 120_000, 600_000};

    /** 最大重试次数 */
    static final int MAX_RETRIES = 5;

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
    // region S08-A: QueuedDelivery -- 队列条目数据结构
    // ================================================================

    /**
     * 投递队列中的一条待发送消息.
     *
     * @param id           唯一 ID (12 位 hex)
     * @param channel      目标渠道 (console / telegram / discord 等)
     * @param to           目标接收者
     * @param text         消息文本
     * @param retryCount   已重试次数
     * @param lastError    最近一次错误信息
     * @param enqueuedAt   入队时间 (epoch 秒)
     * @param nextRetryAt  下次重试时间 (epoch 秒), 0 表示立即
     */
    record QueuedDelivery(
            String id, String channel, String to, String text,
            int retryCount, String lastError,
            double enqueuedAt, double nextRetryAt
    ) {
        /** 转为 Map 以便 JSON 序列化 */
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("channel", channel);
            m.put("to", to);
            m.put("text", text);
            m.put("retry_count", retryCount);
            m.put("last_error", lastError);
            m.put("enqueued_at", enqueuedAt);
            m.put("next_retry_at", nextRetryAt);
            return m;
        }

        /** 从 Map (JSON 反序列化) 构造实例 */
        static QueuedDelivery fromMap(Map<String, Object> data) {
            return new QueuedDelivery(
                    (String) data.getOrDefault("id", ""),
                    (String) data.getOrDefault("channel", ""),
                    (String) data.getOrDefault("to", ""),
                    (String) data.getOrDefault("text", ""),
                    ((Number) data.getOrDefault("retry_count", 0)).intValue(),
                    (String) data.getOrDefault("last_error", null),
                    ((Number) data.getOrDefault("enqueued_at", 0.0)).doubleValue(),
                    ((Number) data.getOrDefault("next_retry_at", 0.0)).doubleValue()
            );
        }
    }

    // endregion S08-A: QueuedDelivery

    // ================================================================
    // region S08-A: AtomicFileWriter -- 原子文件写入
    // ================================================================

    /**
     * 原子文件写入: 先写临时文件 → fsync → 原子 rename.
     * 保证崩溃安全: 要么旧文件完整存在, 要么新文件完整替换.
     *
     * @param target  目标文件路径
     * @param content 要写入的内容
     */
    static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(".tmp." + ProcessHandle.current().pid()
                + "." + target.getFileName().toString());
        try {
            Files.writeString(tmp, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            // 强制刷盘, 确保数据落盘后再 rename
            try (var fos = Files.newOutputStream(tmp, StandardOpenOption.SYNC)) {
                fos.flush();
            }
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // 文件系统不支持原子移动, 回退到普通替换
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // 清理可能残留的临时文件
            try { Files.deleteIfExists(tmp); } catch (IOException e) { /* ignore */ }
        }
    }

    // endregion S08-A: AtomicFileWriter

    // ================================================================
    // region S08-A: DeliveryQueue -- 磁盘持久化可靠投递队列
    // ================================================================

    /**
     * 磁盘持久化的可靠投递队列.
     *
     * 核心设计: 预写日志 (Write-Ahead Log)
     *   1. 先将消息写入磁盘 (JSON 文件)
     *   2. 再尝试投递
     *   3. 投递成功则删除文件 (ack)
     *   4. 投递失败则更新重试计数 (fail)
     *   5. 重试耗尽则移入 failed/ 目录
     *
     * 崩溃恢复: 重启时扫描队列目录, 恢复所有未完成的消息.
     */
    static class DeliveryQueue {
        private final Path queueDir;
        private final Path failedDir;

        DeliveryQueue() {
            this(QUEUE_DIR);
        }

        DeliveryQueue(Path queueDir) {
            this.queueDir = queueDir;
            this.failedDir = queueDir.resolve("failed");
            try {
                Files.createDirectories(queueDir);
                Files.createDirectories(failedDir);
            } catch (IOException e) { /* ignore */ }
        }

        /**
         * 入队: 创建队列条目并原子写入磁盘.
         *
         * @param channel 渠道名
         * @param to      接收者
         * @param text    消息文本
         * @return delivery ID
         */
        String enqueue(String channel, String to, String text) {
            String deliveryId = generateId();
            QueuedDelivery entry = new QueuedDelivery(
                    deliveryId, channel, to, text,
                    0, null, epochSeconds(), 0.0);
            writeEntry(entry);
            return deliveryId;
        }

        /** 原子写入队列条目到磁盘 */
        void writeEntry(QueuedDelivery entry) {
            Path finalPath = queueDir.resolve(entry.id() + ".json");
            try {
                String json = JsonUtils.toJson(entry.toMap());
                writeAtomically(finalPath, json);
            } catch (IOException e) {
                AnsiColors.printError("  [delivery] write error: " + e.getMessage());
            }
        }

        /** 从磁盘读取单个条目 */
        QueuedDelivery readEntry(String deliveryId) {
            Path file = queueDir.resolve(deliveryId + ".json");
            if (!Files.isRegularFile(file)) return null;
            try {
                Map<String, Object> data = JsonUtils.toMap(Files.readString(file));
                return QueuedDelivery.fromMap(data);
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * 确认投递成功: 删除队列文件.
         */
        void ack(String deliveryId) {
            try {
                Files.deleteIfExists(queueDir.resolve(deliveryId + ".json"));
            } catch (IOException e) { /* ignore */ }
        }

        /**
         * 投递失败: 递增重试计数, 计算下次重试时间.
         * 重试耗尽时移入 failed/ 目录.
         */
        void fail(String deliveryId, String error) {
            QueuedDelivery entry = readEntry(deliveryId);
            if (entry == null) return;

            int newRetryCount = entry.retryCount() + 1;

            if (newRetryCount >= MAX_RETRIES) {
                moveToFailed(deliveryId);
                return;
            }

            // 计算退避时间
            long backoffMs = computeBackoffMs(newRetryCount);
            double nextRetryAt = epochSeconds() + backoffMs / 1000.0;

            // 更新条目
            QueuedDelivery updated = new QueuedDelivery(
                    entry.id(), entry.channel(), entry.to(), entry.text(),
                    newRetryCount, error, entry.enqueuedAt(), nextRetryAt);
            writeEntry(updated);
        }

        /** 移入 failed/ 目录 */
        void moveToFailed(String deliveryId) {
            Path src = queueDir.resolve(deliveryId + ".json");
            Path dst = failedDir.resolve(deliveryId + ".json");
            try {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) { /* ignore */ }
        }

        /**
         * 加载所有待处理条目, 按入队时间排序.
         * 启动时用于恢复崩溃前的未完成消息.
         */
        List<QueuedDelivery> loadPending() {
            return loadFromDir(queueDir);
        }

        /** 加载所有失败条目 */
        List<QueuedDelivery> loadFailed() {
            return loadFromDir(failedDir);
        }

        /** 从指定目录加载所有 JSON 文件为 QueuedDelivery */
        List<QueuedDelivery> loadFromDir(Path dir) {
            List<QueuedDelivery> entries = new ArrayList<>();
            if (!Files.isDirectory(dir)) return entries;
            try {
                for (Path file : Files.newDirectoryStream(dir, "*.json")) {
                    if (!Files.isRegularFile(file)) continue;
                    try {
                        Map<String, Object> data = JsonUtils.toMap(Files.readString(file));
                        entries.add(QueuedDelivery.fromMap(data));
                    } catch (Exception e) { /* skip corrupted files */ }
                }
            } catch (IOException e) { /* ignore */ }
            entries.sort(Comparator.comparingDouble(QueuedDelivery::enqueuedAt));
            return entries;
        }

        /**
         * 将所有 failed/ 条目移回队列, 重置重试计数.
         *
         * @return 移回的条目数
         */
        int retryFailed() {
            int count = 0;
            List<QueuedDelivery> failed = loadFailed();
            for (QueuedDelivery entry : failed) {
                QueuedDelivery reset = new QueuedDelivery(
                        entry.id(), entry.channel(), entry.to(), entry.text(),
                        0, null, entry.enqueuedAt(), 0.0);
                writeEntry(reset);
                try {
                    Files.deleteIfExists(failedDir.resolve(entry.id() + ".json"));
                } catch (IOException e) { /* ignore */ }
                count++;
            }
            return count;
        }
    }

    /**
     * 计算退避时间 (毫秒).
     * 指数退避 + ±20% 随机抖动, 避免惊群效应.
     *
     * @param retryCount 当前重试次数 (从 1 开始)
     * @return 退避时间 (毫秒)
     */
    static long computeBackoffMs(int retryCount) {
        if (retryCount <= 0) return 0;
        int idx = Math.min(retryCount - 1, BACKOFF_MS.length - 1);
        long base = BACKOFF_MS[idx];
        // ±20% 抖动
        long jitter = (long) ((Math.random() - 0.5) * 2 * (base * 0.2));
        return Math.max(0, base + jitter);
    }

    /** 生成 12 位 hex 随机 ID */
    static String generateId() {
        long id = (long) (Math.random() * Long.MAX_VALUE);
        return Long.toHexString(id).substring(0, Math.min(12, Long.toHexString(id).length()));
    }

    // endregion S08-A: DeliveryQueue

    // ================================================================
    // region S08-B: 渠道感知的消息分片
    // ================================================================

    /** 各渠道消息长度限制 */
    static final Map<String, Integer> CHANNEL_LIMITS = Map.of(
            "telegram", 4096,
            "telegram_caption", 1024,
            "discord", 2000,
            "whatsapp", 4096,
            "default", 4096
    );

    /**
     * 将消息按平台限制分片.
     * 两级拆分: 先按段落 (\n\n) 拆分, 然后对超长段落硬切.
     *
     * @param text    原始文本
     * @param channel 渠道名
     * @return 分片后的消息列表
     */
    static List<String> chunkMessage(String text, String channel) {
        if (text == null || text.isEmpty()) return List.of();
        int limit = CHANNEL_LIMITS.getOrDefault(channel, CHANNEL_LIMITS.get("default"));
        if (text.length() <= limit) return List.of(text);

        List<String> chunks = new ArrayList<>();
        for (String para : text.split("\n\n")) {
            // 尝试追加到当前块
            if (!chunks.isEmpty()
                    && chunks.get(chunks.size() - 1).length() + para.length() + 2 <= limit) {
                chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + "\n\n" + para);
            } else {
                // 超长段落硬切
                while (para.length() > limit) {
                    chunks.add(para.substring(0, limit));
                    para = para.substring(limit);
                }
                if (!para.isEmpty()) chunks.add(para);
            }
        }
        return chunks.isEmpty() ? List.of(text.substring(0, limit)) : chunks;
    }

    // endregion S08-B: 消息分片

    // ================================================================
    // region S08-B: MockDeliveryChannel -- 模拟投递渠道
    // ================================================================

    /**
     * 可配置失败率的模拟投递渠道, 用于测试退避和重试逻辑.
     * fail_rate = 0.0 时永远成功, fail_rate = 1.0 时永远失败.
     */
    static class MockDeliveryChannel {
        private final String name;
        private volatile double failRate;
        final List<Map<String, Object>> sent = new ArrayList<>();

        MockDeliveryChannel(String name, double failRate) {
            this.name = name;
            this.failRate = Math.max(0.0, Math.min(1.0, failRate));
        }

        /**
         * 模拟发送. 按配置的失败率抛出 RuntimeException.
         *
         * @param to   接收者
         * @param text 消息文本
         */
        void send(String to, String text) {
            if (Math.random() < failRate) {
                throw new RuntimeException("[" + name + "] Simulated delivery failure to " + to);
            }
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("to", to);
            record.put("text", text);
            record.put("time", epochSeconds());
            sent.add(record);
            String preview = text.substring(0, Math.min(60, text.length())).replace("\n", " ");
            printDelivery("[" + name + "] -> " + to + ": " + preview + "...");
        }

        void setFailRate(double rate) {
            this.failRate = Math.max(0.0, Math.min(1.0, rate));
        }
    }

    // endregion S08-B: MockDeliveryChannel

    // ================================================================
    // region S08-B: DeliveryRunner -- 后台投递线程
    // ================================================================

    /**
     * 后台投递运行器.
     *
     * 启动时先扫描磁盘恢复未完成的消息 (recovery_scan).
     * 之后每秒轮询队列目录, 处理 nextRetryAt <= now 的条目:
     *   - 发送成功 → ack (删除文件)
     *   - 发送失败 → fail (递增重试计数 + 退避)
     *   - 重试耗尽 → moveToFailed
     */
    static class DeliveryRunner {
        private final DeliveryQueue queue;
        private final MockDeliveryChannel channel;
        private ScheduledExecutorService scheduler;

        // 统计计数
        final AtomicInteger totalAttempted = new AtomicInteger();
        final AtomicInteger totalSucceeded = new AtomicInteger();
        final AtomicInteger totalFailed = new AtomicInteger();

        DeliveryRunner(DeliveryQueue queue, MockDeliveryChannel channel) {
            this.queue = queue;
            this.channel = channel;
        }

        /** 启动: 恢复扫描 + 后台线程 */
        void start() {
            recoveryScan();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Thread.ofVirtual().name("delivery-runner").unstarted(r);
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::processPending, 1, 1, TimeUnit.SECONDS);
        }

        /** 启动时统计待处理和失败条目 */
        void recoveryScan() {
            List<QueuedDelivery> pending = queue.loadPending();
            List<QueuedDelivery> failed = queue.loadFailed();
            List<String> parts = new ArrayList<>();
            if (!pending.isEmpty()) parts.add(pending.size() + " pending");
            if (!failed.isEmpty()) parts.add(failed.size() + " failed");
            if (!parts.isEmpty()) {
                printDelivery("Recovery: " + String.join(", ", parts));
            } else {
                printDelivery("Recovery: queue is clean");
            }
        }

        /** 处理所有 nextRetryAt <= now 的待处理条目 */
        void processPending() {
            List<QueuedDelivery> pending = queue.loadPending();
            double now = epochSeconds();

            for (QueuedDelivery entry : pending) {
                if (entry.nextRetryAt() > now) continue;

                totalAttempted.incrementAndGet();
                try {
                    channel.send(entry.to(), entry.text());
                    queue.ack(entry.id());
                    totalSucceeded.incrementAndGet();
                } catch (Exception e) {
                    String errorMsg = e.getMessage();
                    queue.fail(entry.id(), errorMsg);
                    totalFailed.incrementAndGet();

                    int retryNum = entry.retryCount() + 1;
                    if (retryNum >= MAX_RETRIES) {
                        printWarn("Delivery " + entry.id().substring(0, Math.min(8, entry.id().length()))
                                + "... -> failed/ (retry " + retryNum + "/" + MAX_RETRIES + "): " + errorMsg);
                    } else {
                        long backoff = computeBackoffMs(retryNum);
                        printWarn("Delivery " + entry.id().substring(0, Math.min(8, entry.id().length()))
                                + "... failed (retry " + retryNum + "/" + MAX_RETRIES + "), "
                                + "next retry in " + backoff / 1000 + "s: " + errorMsg);
                    }
                }
            }
        }

        void stop() {
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    scheduler.awaitTermination(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /** 获取投递统计 */
        Map<String, Object> getStats() {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("pending", queue.loadPending().size());
            stats.put("failed", queue.loadFailed().size());
            stats.put("total_attempted", totalAttempted.get());
            stats.put("total_succeeded", totalSucceeded.get());
            stats.put("total_failed", totalFailed.get());
            return stats;
        }
    }

    // endregion S08-B: DeliveryRunner

    // ================================================================
    // region S08-B: 简化的 HeartbeatRunner (通过 DeliveryQueue 入队)
    // ================================================================

    /**
     * 简化的心跳运行器: 定时生成心跳文本并入队到 DeliveryQueue.
     * 不直接调用 LLM, 仅生成系统消息模拟心跳.
     */
    static class HeartbeatRunner {
        private final DeliveryQueue queue;
        private final String channel;
        private final String to;
        private final double intervalSeconds;
        private ScheduledExecutorService scheduler;
        private volatile boolean enabled = false;
        volatile int runCount = 0;
        volatile double lastRun = 0.0;

        HeartbeatRunner(DeliveryQueue queue, String channel, String to, double intervalSeconds) {
            this.queue = queue;
            this.channel = channel;
            this.to = to;
            this.intervalSeconds = intervalSeconds;
        }

        void start() {
            enabled = true;
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Thread.ofVirtual().name("heartbeat-runner").unstarted(r);
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(this::tick,
                    (long) intervalSeconds, (long) intervalSeconds, TimeUnit.SECONDS);
        }

        void tick() {
            if (!enabled) return;
            runCount++;
            lastRun = epochSeconds();
            String heartbeatText = "[Heartbeat #" + runCount + "] "
                    + "System check at " + Instant.now().toString().replace("T", " ").substring(0, 19)
                    + " -- all OK.";
            for (String chunk : chunkMessage(heartbeatText, channel)) {
                queue.enqueue(channel, to, chunk);
            }
            AnsiColors.printInfo("  " + AnsiColors.MAGENTA + "[heartbeat]" + AnsiColors.RESET
                    + " triggered #" + runCount);
        }

        /** 手动触发心跳 */
        void trigger() {
            tick();
        }

        void stop() {
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    scheduler.awaitTermination(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        Map<String, Object> getStatus() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("enabled", enabled);
            s.put("interval", intervalSeconds + "s");
            s.put("run_count", runCount);
            s.put("last_run", lastRun > 0
                    ? Instant.ofEpochSecond((long) lastRun).toString()
                    : "never");
            return s;
        }
    }

    // endregion S08-B: HeartbeatRunner

    // ================================================================
    // region S01-S07 Core: 简化的 Soul + Memory
    // ================================================================

    static class SoulSystem {
        private final String personality;

        SoulSystem() {
            String loaded = "";
            Path soulPath = WORKSPACE_DIR.resolve("SOUL.md");
            if (Files.isRegularFile(soulPath)) {
                try {
                    loaded = Files.readString(soulPath);
                } catch (IOException e) { /* use default */ }
            }
            personality = loaded;
        }

        String getSystemPrompt() {
            return personality.isEmpty() ? SYSTEM_PROMPT : personality + "\n\n" + SYSTEM_PROMPT;
        }
    }

    static class SimpleMemoryStore {
        private final Path memoryFile;

        SimpleMemoryStore() {
            this.memoryFile = WORKSPACE_DIR.resolve("memory.jsonl");
            if (!Files.exists(memoryFile)) {
                try { Files.writeString(memoryFile, ""); } catch (IOException e) { /* ignore */ }
            }
        }

        String write(String content) {
            Map<String, Object> entry = Map.of("content", content, "time", epochSeconds());
            try {
                JsonUtils.appendJsonl(memoryFile, entry);
                return "Saved: " + content.substring(0, Math.min(50, content.length()));
            } catch (IOException e) {
                return "Error: " + e.getMessage();
            }
        }

        String search(String query) {
            if (!Files.exists(memoryFile)) return "No memories found.";
            String lower = query.toLowerCase();
            List<String> results = new ArrayList<>();
            try {
                for (String line : Files.readAllLines(memoryFile)) {
                    if (line.isBlank()) continue;
                    try {
                        Map<String, Object> entry = JsonUtils.toMap(line);
                        String content = (String) entry.getOrDefault("content", "");
                        if (content.toLowerCase().contains(lower)) {
                            results.add(content);
                        }
                    } catch (Exception e) { /* skip */ }
                }
            } catch (IOException e) { /* ignore */ }
            if (results.isEmpty()) return "No memories found.";
            return results.stream().skip(Math.max(0, results.size() - 5))
                    .map(r -> "- " + r)
                    .collect(Collectors.joining("\n"));
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

    static final List<ToolUnion> TOOLS = List.of(
            ToolUnion.ofTool(buildTool("memory_write",
                    "Save an important fact or preference to long-term memory.",
                    Map.of("content", Map.of("type", "string",
                            "description", "The fact or preference to remember.")),
                    List.of("content"))),
            ToolUnion.ofTool(buildTool("memory_search",
                    "Search long-term memory for relevant facts.",
                    Map.of("query", Map.of("type", "string",
                            "description", "Search query.")),
                    List.of("query")))
    );

    // endregion S01-S07 Core

    // ================================================================
    // 辅助方法
    // ================================================================

    static double epochSeconds() {
        return System.currentTimeMillis() / 1000.0;
    }

    static void printDelivery(String text) {
        System.out.println("  " + AnsiColors.BLUE + "[delivery]" + AnsiColors.RESET + " " + text);
    }

    static void printWarn(String text) {
        System.out.println("  " + AnsiColors.YELLOW + "[warn]" + AnsiColors.RESET + " " + text);
    }

    // ================================================================
    // REPL 命令处理
    // ================================================================

    static boolean handleReplCommand(String cmd, DeliveryQueue queue,
                                      DeliveryRunner runner, HeartbeatRunner heartbeat,
                                      MockDeliveryChannel mockChannel) {
        switch (cmd) {
            case "/queue" -> {
                List<QueuedDelivery> pending = queue.loadPending();
                if (pending.isEmpty()) {
                    AnsiColors.printInfo("  Queue is empty.");
                    return true;
                }
                AnsiColors.printInfo("  Pending deliveries (" + pending.size() + "):");
                double now = epochSeconds();
                for (QueuedDelivery entry : pending) {
                    String wait = "";
                    if (entry.nextRetryAt() > now) {
                        double remaining = entry.nextRetryAt() - now;
                        wait = ", wait " + String.format("%.0f", remaining) + "s";
                    }
                    String preview = entry.text().substring(0, Math.min(40, entry.text().length()))
                            .replace("\n", " ");
                    AnsiColors.printInfo("    " + entry.id().substring(0, Math.min(8, entry.id().length()))
                            + "... retry=" + entry.retryCount() + wait + " \"" + preview + "\"");
                }
                return true;
            }
            case "/failed" -> {
                List<QueuedDelivery> failed = queue.loadFailed();
                if (failed.isEmpty()) {
                    AnsiColors.printInfo("  No failed deliveries.");
                    return true;
                }
                AnsiColors.printInfo("  Failed deliveries (" + failed.size() + "):");
                for (QueuedDelivery entry : failed) {
                    String preview = entry.text().substring(0, Math.min(40, entry.text().length()))
                            .replace("\n", " ");
                    String err = entry.lastError() != null ? entry.lastError() : "unknown";
                    String errPreview = err.substring(0, Math.min(30, err.length()));
                    AnsiColors.printInfo("    " + entry.id().substring(0, Math.min(8, entry.id().length()))
                            + "... retries=" + entry.retryCount() + " error=\"" + errPreview
                            + "\" \"" + preview + "\"");
                }
                return true;
            }
            case "/retry" -> {
                int count = queue.retryFailed();
                AnsiColors.printInfo("  Moved " + count + " entries back to queue.");
                return true;
            }
            case "/simulate-failure" -> {
                if (mockChannel.failRate > 0) {
                    mockChannel.setFailRate(0.0);
                    AnsiColors.printInfo("  " + mockChannel.name + " fail rate -> 0% (reliable)");
                } else {
                    mockChannel.setFailRate(0.5);
                    AnsiColors.printInfo("  " + mockChannel.name + " fail rate -> 50% (unreliable)");
                }
                return true;
            }
            case "/heartbeat" -> {
                Map<String, Object> status = heartbeat.getStatus();
                AnsiColors.printInfo("  Heartbeat: enabled=" + status.get("enabled")
                        + ", interval=" + status.get("interval")
                        + ", runs=" + status.get("run_count")
                        + ", last=" + status.get("last_run"));
                return true;
            }
            case "/trigger" -> {
                heartbeat.trigger();
                return true;
            }
            case "/stats" -> {
                Map<String, Object> stats = runner.getStats();
                AnsiColors.printInfo("  Delivery stats: "
                        + "pending=" + stats.get("pending")
                        + ", failed=" + stats.get("failed")
                        + ", attempted=" + stats.get("total_attempted")
                        + ", succeeded=" + stats.get("total_succeeded")
                        + ", errors=" + stats.get("total_failed"));
                return true;
            }
            default -> { return false; }
        }
    }

    // ================================================================
    // Agent Loop (带 Delivery 的完整 REPL 循环)
    // ================================================================

    static void agentLoop() {
        SoulSystem soul = new SoulSystem();
        SimpleMemoryStore memory = new SimpleMemoryStore();
        String systemPrompt = soul.getSystemPrompt();

        // 投递系统
        MockDeliveryChannel mockChannel = new MockDeliveryChannel("console", 0.0);
        String defaultChannel = "console";
        String defaultTo = "user";

        DeliveryQueue queue = new DeliveryQueue();
        DeliveryRunner runner = new DeliveryRunner(queue, mockChannel);
        runner.start();

        // 心跳
        HeartbeatRunner heartbeat = new HeartbeatRunner(queue, defaultChannel, defaultTo, 120.0);
        heartbeat.start();

        List<MessageParam> messages = new ArrayList<>();

        // 打印 banner
        AnsiColors.printInfo("============================================================");
        AnsiColors.printInfo("  claw0  |  Section 08: Delivery");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("  Queue: " + QUEUE_DIR);
        AnsiColors.printInfo("  Commands:");
        AnsiColors.printInfo("    /queue             - show pending deliveries");
        AnsiColors.printInfo("    /failed            - show failed deliveries");
        AnsiColors.printInfo("    /retry             - retry all failed");
        AnsiColors.printInfo("    /simulate-failure  - toggle 50% failure rate");
        AnsiColors.printInfo("    /heartbeat         - heartbeat status");
        AnsiColors.printInfo("    /trigger           - manually trigger heartbeat");
        AnsiColors.printInfo("    /stats             - delivery statistics");
        AnsiColors.printInfo("  Type 'quit' or 'exit' to leave.");
        AnsiColors.printInfo("============================================================");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (true) {
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

            // REPL 斜杠命令
            if (userInput.startsWith("/")) {
                String[] cmdParts = userInput.split("\\s+", 2);
                String cmd = cmdParts[0].toLowerCase();
                if (handleReplCommand(cmd, queue, runner, heartbeat, mockChannel)) {
                    continue;
                }
                AnsiColors.printInfo("  Unknown command: " + userInput);
                continue;
            }

            // 用户对话
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userInput)
                    .build());

            // Agent 内循环: 处理连续的工具调用直到 end_turn
            while (true) {
                Message response;
                try {
                    response = client.messages().create(MessageCreateParams.builder()
                            .model(MODEL_ID)
                            .maxTokens(4096)
                            .system(systemPrompt)
                            .tools(TOOLS)
                            .messages(messages)
                            .build());
                } catch (Exception e) {
                    System.out.println("\n" + AnsiColors.YELLOW + "API Error: " + e.getMessage()
                            + AnsiColors.RESET + "\n");
                    while (!messages.isEmpty()
                            && messages.get(messages.size() - 1).role() != MessageParam.Role.USER) {
                        messages.remove(messages.size() - 1);
                    }
                    if (!messages.isEmpty()) messages.remove(messages.size() - 1);
                    break;
                }

                messages.add(response.toParam());
                StopReason reason = response.stopReason().orElse(null);

                if (reason == StopReason.END_TURN) {
                    String text = response.content().stream()
                            .filter(ContentBlock::isText)
                            .map(ContentBlock::asText)
                            .map(TextBlock::text)
                            .collect(Collectors.joining());
                    if (!text.isEmpty()) {
                        AnsiColors.printAssistant(text);
                        // 将回复入队投递
                        for (String chunk : chunkMessage(text, defaultChannel)) {
                            queue.enqueue(defaultChannel, defaultTo, chunk);
                        }
                    }
                    break;
                } else if (reason == StopReason.TOOL_USE) {
                    List<ContentBlockParam> results = new ArrayList<>();
                    for (ContentBlock block : response.content()) {
                        if (!block.isToolUse()) continue;
                        ToolUseBlock tu = block.asToolUse();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> toolInput = tu._input().convert(Map.class);
                        AnsiColors.printTool(tu.name(), "");
                        String result;
                        if ("memory_write".equals(tu.name())) {
                            result = memory.write((String) toolInput.getOrDefault("content", ""));
                        } else if ("memory_search".equals(tu.name())) {
                            result = memory.search((String) toolInput.getOrDefault("query", ""));
                        } else {
                            result = "Error: Unknown tool '" + tu.name() + "'";
                        }
                        results.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId(tu.id())
                                        .content(result)
                                        .build()));
                    }
                    messages.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(results)
                            .build());
                } else {
                    AnsiColors.printInfo("[stop_reason=" + reason + "]");
                    String text = response.content().stream()
                            .filter(ContentBlock::isText)
                            .map(ContentBlock::asText)
                            .map(TextBlock::text)
                            .collect(Collectors.joining());
                    if (!text.isEmpty()) {
                        AnsiColors.printAssistant(text);
                        for (String chunk : chunkMessage(text, defaultChannel)) {
                            queue.enqueue(defaultChannel, defaultTo, chunk);
                        }
                    }
                    break;
                }
            }
        }

        heartbeat.stop();
        runner.stop();
        AnsiColors.printInfo("Delivery runner stopped. Queue state preserved on disk.");
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
