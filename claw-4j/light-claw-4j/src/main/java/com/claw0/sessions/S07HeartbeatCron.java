package com.claw0.sessions;

/**
 * Section 07: Heartbeat & Cron -- "Not just reactive -- proactive"
 *
 * 定时线程检查"是否应该运行?", 然后将工作放入与用户消息相同的管道中.
 * Lane 互斥机制给予用户消息优先权.
 *
 *     Main Lane:      User Input --> lock.acquire() -------> LLM --> Print
 *     Heartbeat Lane: Timer tick --> lock.tryLock() -----+
 *                                                        |
 *                                   acquired? --no--> skip (user has priority)
 *                                      |yes
 *                                  run agent --> dedup --> queue
 *     Cron Service:   CRON.json --> tick() --> due? --> run_agent --> log
 *
 * 用法:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S07HeartbeatCron"
 *
 * REPL 命令:
 *   /heartbeat  /trigger  /cron  /cron-trigger <id>  /lanes  /help
 */

// region Common Imports
import com.claw0.common.AnsiColors;
import com.claw0.common.Config;
import com.claw0.common.Clients;
import com.claw0.common.JsonUtils;

import com.anthropic.client.AnthropicClient;
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

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

public class S07HeartbeatCron {

    // ================================================================
    // 配置常量
    // ================================================================

    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    static final int MAX_TOOL_OUTPUT = 50_000;
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));
    static final Path WORKSPACE_DIR = WORKDIR.resolve("workspace");
    static final Path CRON_DIR = WORKSPACE_DIR.resolve("cron");

    /**
     * 连续错误达到此阈值后自动禁用 cron 任务.
     * 5 是经验值: 太小容易误杀 (瞬时网络抖动导致), 太大浪费资源 (持续失败的任务
     * 会反复消耗 API 调用额度). 5 次通常意味着问题不是瞬时的.
     * 选择 5 的理由: 网络瞬时错误一般 1-2 次就恢复; 5 次连续失败基本可断定是配置
     * 或服务端问题, 此时继续重试只会白白消耗额度, 不如静默等待人工介入.
     */
    static final int CRON_AUTO_DISABLE_THRESHOLD = 5;

    /** Anthropic API 客户端 */
    static final AnthropicClient client = Clients.create();

    // ================================================================
    // region S01-S06 Core (从前序 Session 复制的核心代码, 精简版)
    // ================================================================

    // --- 简化的 Soul 系统 ---

    /**
     * 灵魂系统: 加载 SOUL.md 构建 agent 人格.
     * S07 使用简化版, 仅加载 SOUL.md + MEMORY.md.
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
                } catch (IOException e) {
                    // SOUL.md 不存在或无法读取时不中断: 使用默认 prompt 继续运行
                    // 这是可接受的降级 -- 没有 SOUL.md 的 agent 只是缺少个性化, 仍然可用
                }
            }
            return "";
        }

        String buildSystemPrompt(String extra) {
            StringBuilder sb = new StringBuilder(load());
            if (extra != null && !extra.isEmpty()) {
                sb.append("\n\n").append(extra);
            }
            return sb.toString();
        }
    }

    // --- 简化的 MemoryStore (仅 MEMORY.md + 简单搜索) ---

    /**
     * 简化的记忆存储: 仅操作 MEMORY.md 文件.
     * 保留 memory_write / memory_search 工具接口, 内部实现精简.
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
                    // CRON_DIR 创建失败不影响后续功能: loadJobs() 会再次检查目录是否存在,
                    // 最坏情况是首次启动无法加载任务, 但不会导致进程崩溃
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

    // --- 工具定义 ---

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

    /** memory 工具 Schema */
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

    // endregion S01-S06 Core

    // ================================================================
    // region S07-A: HeartbeatRunner -- 心跳运行器
    // ================================================================

    /**
     * 心跳运行器: 定时线程检查"是否应该运行?", 用非阻塞 tryLock 获取锁,
     * 如果用户正在使用 agent 则自动跳过 (用户优先).
     *
     * 4 项前置检查:
     *   1. HEARTBEAT.md 文件存在
     *   2. HEARTBEAT.md 内容非空
     *   3. 距上次运行间隔足够 (默认 30 分钟)
     *   4. 在活跃时段内 (默认 9:00-22:00)
     *
     * 输出去重: 与上次输出相同则不推送, 避免重复打扰用户.
     */
    static class HeartbeatRunner {
        private final Path workspace;
        private final Path heartbeatPath;
        private final ReentrantLock laneLock;     // 与用户对话共享的锁
        private final double intervalSeconds;      // 心跳间隔 (秒)
        private final int activeHourStart;         // 活跃时段开始 (24h)
        private final int activeHourEnd;           // 活跃时段结束 (24h)
        private final SoulSystem soul;
        private final SimpleMemoryStore memory;

        // 状态
        volatile boolean running = false;          // 是否正在执行心跳
        private volatile boolean stopped = false;   // 是否已停止
        private double lastRunAt = 0.0;            // 上次运行时间 (epoch 秒)
        private String lastOutput = "";             // 上次输出去重用

        // 输出队列: heartbeat 产生的消息会通过此队列传递给 REPL 主循环打印
        private final ConcurrentLinkedQueue<String> outputQueue = new ConcurrentLinkedQueue<>();

        // 后台调度器
        private ScheduledExecutorService scheduler;

        HeartbeatRunner(Path workspace, ReentrantLock laneLock,
                        double intervalSeconds, int activeHourStart, int activeHourEnd) {
            this.workspace = workspace;
            this.heartbeatPath = workspace.resolve("HEARTBEAT.md");
            this.laneLock = laneLock;
            this.intervalSeconds = intervalSeconds;
            this.activeHourStart = activeHourStart;
            this.activeHourEnd = activeHourEnd;
            this.soul = new SoulSystem(workspace);
            this.memory = new SimpleMemoryStore(workspace);
        }

        /**
         * 4 项前置检查. 锁的检测在 _execute() 中单独处理.
         *
         * @return [是否应该运行, 原因说明]
         */
        ShouldRunResult shouldRun() {
            // 检查 1: HEARTBEAT.md 是否存在
            if (!Files.exists(heartbeatPath)) {
                return new ShouldRunResult(false, "HEARTBEAT.md not found");
            }
            // 检查 2: HEARTBEAT.md 内容是否非空
            try {
                if (Files.readString(heartbeatPath).strip().isEmpty()) {
                    return new ShouldRunResult(false, "HEARTBEAT.md is empty");
                }
            } catch (IOException e) {
                return new ShouldRunResult(false, "HEARTBEAT.md read error");
            }
            // 检查 3: 距上次运行间隔是否足够
            double now = epochSeconds();
            double elapsed = now - lastRunAt;
            if (elapsed < intervalSeconds) {
                double remaining = intervalSeconds - elapsed;
                return new ShouldRunResult(false,
                        "interval not elapsed (" + String.format("%.0f", remaining) + "s remaining)");
            }
            // 检查 4: 是否在活跃时段内
            int hour = LocalDateTime.now().getHour();
            boolean inHours = activeHourStart <= activeHourEnd
                    ? (activeHourStart <= hour && hour < activeHourEnd)
                    : !(activeHourEnd <= hour && hour < activeHourStart);
            if (!inHours) {
                return new ShouldRunResult(false,
                        "outside active hours (" + activeHourStart + ":00-" + activeHourEnd + ":00)");
            }
            // 检查 5: 是否已经在运行
            if (running) {
                return new ShouldRunResult(false, "already running");
            }
            return new ShouldRunResult(true, "all checks passed");
        }

        /**
         * 解析心跳响应.
         * "HEARTBEAT_OK" 表示没有需要报告的内容.
         *
         * @param response LLM 返回的文本
         * @return 有意义的内容, 或 null (表示无需报告)
         */
        String parseResponse(String response) {
            if (response.contains("HEARTBEAT_OK")) {
                String stripped = response.replace("HEARTBEAT_OK", "").strip();
                return stripped.length() > 5 ? stripped : null;
            }
            return response.strip().isEmpty() ? null : response.strip();
        }

        /**
         * 构建心跳 prompt: HEARTBEAT.md 指令 + 记忆上下文 + 当前时间.
         */
        String[] buildHeartbeatPrompt() {
            String instructions;
            try {
                instructions = Files.readString(heartbeatPath).strip();
            } catch (IOException e) {
                instructions = "";
            }
            String mem = memory.loadEvergreen();
            String extra = "";
            if (!mem.isEmpty()) {
                extra = "## Known Context\n\n" + mem + "\n\n";
            }
            extra += "Current time: " + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return new String[]{instructions, soul.buildSystemPrompt(extra)};
        }

        /**
         * 执行一次心跳运行.
         * 非阻塞获取锁 (tryLock); 如果忙则跳过 (用户优先).
         */
        void execute() {
            // 非阻塞获取锁: 用户正在使用 agent 时自动跳过
            boolean acquired = laneLock.tryLock();
            if (!acquired) return;

            running = true;
            try {
                String[] prompts = buildHeartbeatPrompt();
                String instructions = prompts[0];
                if (instructions.isEmpty()) return;

                // 调用 LLM 执行心跳检查
                String response = runAgentSingleTurn(instructions, prompts[1]);
                String meaningful = parseResponse(response);

                if (meaningful == null) return;  // HEARTBEAT_OK, 无需报告

                // 去重: 与上次输出相同则不推送
                if (meaningful.strip().equals(lastOutput)) return;
                lastOutput = meaningful.strip();
                outputQueue.add(meaningful);
            } catch (Exception e) {
                outputQueue.add("[heartbeat error: " + e.getMessage() + "]");
            } finally {
                running = false;
                lastRunAt = epochSeconds();
                laneLock.unlock();
            }
        }

        /**
         * 心跳循环: 每秒检查一次 shouldRun, 通过则执行.
         */
        void loop() {
            while (!stopped) {
                try {
                    ShouldRunResult check = shouldRun();
                    if (check.shouldRun) {
                        execute();
                    }
                } catch (Exception e) {
                    // 静默忽略, 不影响主循环
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        /**
         * 启动心跳后台线程.
         *
         * 注意: 本类同时提供了 start() (基于 ScheduledExecutorService) 和 loop() (基于 while+sleep)
         * 两种调度方式. start() 是正式实现, 使用 JDK 调度器更高效; loop() 是简化备选,
         * 便于理解调度逻辑. 实际运行时只使用 start().
         */
        void start() {
            if (scheduler != null) return;
            stopped = false;
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Thread.ofVirtual().name("heartbeat-loop").unstarted(r);
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    ShouldRunResult check = shouldRun();
                    if (check.shouldRun) {
                        execute();
                    }
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

        /** 排空输出队列, 返回所有待输出的消息 */
        List<String> drainOutput() {
            List<String> items = new ArrayList<>();
            while (!outputQueue.isEmpty()) {
                String item = outputQueue.poll();
                if (item != null) items.add(item);
            }
            return items;
        }

        /**
         * 手动触发心跳: 绕过间隔检查, 立即执行一次.
         *
         * @return 触发结果描述
         */
        String trigger() {
            boolean acquired = laneLock.tryLock();
            if (!acquired) return "main lane occupied, cannot trigger";

            running = true;
            try {
                String[] prompts = buildHeartbeatPrompt();
                if (prompts[0].isEmpty()) return "HEARTBEAT.md is empty";
                String response = runAgentSingleTurn(prompts[0], prompts[1]);
                String meaningful = parseResponse(response);
                if (meaningful == null) return "HEARTBEAT_OK (nothing to report)";
                if (meaningful.strip().equals(lastOutput)) return "duplicate content (skipped)";
                lastOutput = meaningful.strip();
                outputQueue.add(meaningful);
                return "triggered, output queued (" + meaningful.length() + " chars)";
            } catch (Exception e) {
                return "trigger failed: " + e.getMessage();
            } finally {
                running = false;
                lastRunAt = epochSeconds();
                laneLock.unlock();
            }
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
            s.put("running", running);
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

    // endregion S07-A: HeartbeatRunner

    // ================================================================
    // region S07-B: CronJob + CronService
    // ================================================================

    /**
     * 定时任务定义.
     *
     * 调度类型:
     *   "at"    -- 一次性: 在指定时间运行一次, 运行后删除
     *   "every" -- 固定间隔: 从 anchor 开始每隔 N 秒运行一次
     *   "cron"  -- cron 表达式: 5 字段标准 cron (分 时 日 月 周)
     *
     * 连续错误达到 CRON_AUTO_DISABLE_THRESHOLD 后自动禁用.
     */
    static class CronJob {
        String id;
        String name;
        boolean enabled;
        String scheduleKind;            // "at" | "every" | "cron"
        Map<String, Object> scheduleConfig;
        Map<String, Object> payload;
        boolean deleteAfterRun;
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
            this.consecutiveErrors = 0;
            this.lastRunAt = 0.0;
            this.nextRunAt = 0.0;
        }
    }

    /**
     * Cron 服务: 从 CRON.json 加载定时任务, 每秒检查是否有到期任务.
     *
     * 运行日志追加到 workspace/cron/cron-runs.jsonl.
     * 连续错误达到阈值后自动禁用任务.
     */
    static class CronService {
        private final Path cronFile;
        private final Path runLog;
        final List<CronJob> jobs = new ArrayList<>();
        private final SoulSystem soul;

        // 输出队列: cron 产生的消息会通过此队列传递给 REPL 主循环打印
        private final ConcurrentLinkedQueue<String> outputQueue = new ConcurrentLinkedQueue<>();

        // Cron 表达式解析器 (5 字段标准格式: 分 时 日 月 周)
        private static final CronParser CRON_PARSER = new CronParser(
                CronDefinitionBuilder.instanceDefinitionFor(CronType.CRON4J));

        CronService(Path cronFile) {
            this.cronFile = cronFile;
            this.runLog = CRON_DIR.resolve("cron-runs.jsonl");
            this.soul = new SoulSystem(WORKSPACE_DIR);
            try {
                Files.createDirectories(CRON_DIR);
            } catch (IOException e) { /* ignore */ }
            loadJobs();
        }

        /**
         * 从 CRON.json 加载任务定义.
         */
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
            List<Map<String, Object>> jobDefs = (List<Map<String, Object>>) raw.getOrDefault("jobs", List.of());

            for (Map<String, Object> jd : jobDefs) {
                Map<String, Object> sched = (Map<String, Object>) jd.getOrDefault("schedule", Map.of());
                String kind = (String) sched.getOrDefault("kind", "");
                if (!List.of("at", "every", "cron").contains(kind)) continue;

                CronJob job = new CronJob(
                        (String) jd.getOrDefault("id", ""),
                        (String) jd.getOrDefault("name", ""),
                        (Boolean) jd.getOrDefault("enabled", true),
                        kind,
                        sched,
                        (Map<String, Object>) jd.getOrDefault("payload", Map.of()),
                        (Boolean) jd.getOrDefault("delete_after_run", false)
                );
                job.nextRunAt = computeNext(job, now);
                jobs.add(job);
            }
        }

        /**
         * 计算下次运行时间戳.
         * 如果没有后续调度则返回 0.0.
         */
        double computeNext(CronJob job, double now) {
            switch (job.scheduleKind) {
                case "at" -> {
                    // 一次性: 解析 ISO 时间戳
                    try {
                        String atStr = (String) job.scheduleConfig.getOrDefault("at", "");
                        double ts = Instant.parse(atStr).getEpochSecond();
                        return ts > now ? ts : 0.0;
                    } catch (Exception e) {
                        return 0.0;
                    }
                }
                case "every" -> {
                    // 固定间隔: anchor + N * interval
                    double every = ((Number) job.scheduleConfig.getOrDefault("every_seconds", 3600)).doubleValue();
                    try {
                        String anchorStr = (String) job.scheduleConfig.get("anchor");
                        double anchor = anchorStr != null
                                ? Instant.parse(anchorStr).getEpochSecond()
                                : now;
                        if (now < anchor) return anchor;
                        long steps = (long) ((now - anchor) / every) + 1;
                        return anchor + steps * every;
                    } catch (Exception e) {
                        return now + every;
                    }
                }
                case "cron" -> {
                    // Cron 表达式: 使用 cron-utils 解析
                    String expr = (String) job.scheduleConfig.getOrDefault("expr", "");
                    if (expr.isEmpty()) return 0.0;
                    try {
                        var cron = CRON_PARSER.parse(expr);
                        ExecutionTime execTime = ExecutionTime.forCron(cron);
                        var next = execTime.nextExecution(ZonedDateTime.now(ZoneId.systemDefault()));
                        return next.map(zdt -> (double) zdt.toEpochSecond()).orElse(0.0);
                    } catch (Exception e) {
                        return 0.0;
                    }
                }
                default -> { return 0.0; }
            }
        }

        /**
         * 每秒调用一次; 检查并执行到期的任务.
         * 使用延迟移除 (deferred-removal) 模式: 先收集待删除 ID, 遍历结束后统一移除.
         * 为什么不边遍历边删除? 因为在迭代过程中修改 ArrayList 会导致
         * ConcurrentModificationException 或跳过元素, 先收集再删除是安全的做法.
         */
        void tick() {
            double now = epochSeconds();
            List<String> removeIds = new ArrayList<>();

            for (CronJob job : jobs) {
                if (!job.enabled || job.nextRunAt <= 0 || now < job.nextRunAt) continue;
                runJob(job, now);
                // "at" 类型且 delete_after_run 的任务执行后标记为待移除
                if (job.deleteAfterRun && "at".equals(job.scheduleKind)) {
                    removeIds.add(job.id);
                }
            }

            // 统一移除: 遍历结束后安全地删除已执行的 "at" 任务
            if (!removeIds.isEmpty()) {
                jobs.removeIf(j -> removeIds.contains(j.id));
            }
        }

        /**
         * 执行单个 cron 任务.
         */
        void runJob(CronJob job, double now) {
            String kind = (String) job.payload.getOrDefault("kind", "");
            String output = "";
            String status = "ok";
            String error = "";

            try {
                if ("agent_turn".equals(kind)) {
                    // agent_turn: 调用 LLM 执行后台任务
                    String msg = (String) job.payload.getOrDefault("message", "");
                    if (msg.isEmpty()) {
                        output = "[empty message]";
                        status = "skipped";
                    } else {
                        String sysPrompt = "You are performing a scheduled background task. Be concise. "
                                + "Current time: " + LocalDateTime.now()
                                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                        output = runAgentSingleTurn(msg, sysPrompt);
                    }
                } else if ("system_event".equals(kind)) {
                    // system_event: 直接输出文本
                    output = (String) job.payload.getOrDefault("text", "");
                    if (output.isEmpty()) status = "skipped";
                } else {
                    output = "[unknown kind: " + kind + "]";
                    status = "error";
                    error = "unknown kind: " + kind;
                }
            } catch (Exception e) {
                status = "error";
                error = e.getMessage();
                output = "[cron error: " + e.getMessage() + "]";
            }

            // 更新任务状态
            job.lastRunAt = now;

            if ("error".equals(status)) {
                job.consecutiveErrors++;
                if (job.consecutiveErrors >= CRON_AUTO_DISABLE_THRESHOLD) {
                    job.enabled = false;
                    String msg = "Job '" + job.name + "' auto-disabled after "
                            + job.consecutiveErrors + " consecutive errors: " + error;
                    AnsiColors.printError("  " + msg);
                    outputQueue.add(msg);
                }
            } else {
                job.consecutiveErrors = 0;
            }

            // 计算下次运行时间
            job.nextRunAt = computeNext(job, now);

            // 写入运行日志
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("job_id", job.id);
            entry.put("run_at", Instant.ofEpochSecond((long) now).toString());
            entry.put("status", status);
            entry.put("output_preview", output.length() > 200 ? output.substring(0, 200) : output);
            if (!error.isEmpty()) entry.put("error", error);
            try {
                JsonUtils.appendJsonl(runLog, entry);
            } catch (IOException e) { /* ignore */ }

            // 将输出加入队列 (由 REPL 主循环打印)
            if (!output.isEmpty() && !"skipped".equals(status)) {
                outputQueue.add("[" + job.name + "] " + output);
            }
        }

        /**
         * 手动触发指定任务.
         */
        String triggerJob(String jobId) {
            for (CronJob job : jobs) {
                if (job.id.equals(jobId)) {
                    runJob(job, epochSeconds());
                    return "'" + job.name + "' triggered (errors=" + job.consecutiveErrors + ")";
                }
            }
            return "Job '" + jobId + "' not found";
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
                info.put("kind", j.scheduleKind);
                info.put("errors", j.consecutiveErrors);
                info.put("last_run", j.lastRunAt > 0
                        ? Instant.ofEpochSecond((long) j.lastRunAt).toString()
                        : "never");
                info.put("next_run", j.nextRunAt > 0
                        ? Instant.ofEpochSecond((long) j.nextRunAt).toString()
                        : "n/a");
                info.put("next_in", nxt != null ? Math.round(nxt) : null);
                result.add(info);
            }
            return result;
        }
    }

    // endregion S07-B: CronService

    // ================================================================
    // Agent 辅助函数
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

    // ================================================================
    // REPL 命令处理
    // ================================================================

    /** 打印 REPL 帮助信息 */
    static void printReplHelp() {
        AnsiColors.printInfo("REPL commands:");
        AnsiColors.printInfo("  /heartbeat         -- heartbeat status");
        AnsiColors.printInfo("  /trigger           -- force heartbeat now");
        AnsiColors.printInfo("  /cron              -- list cron jobs");
        AnsiColors.printInfo("  /cron-trigger <id> -- trigger a cron job");
        AnsiColors.printInfo("  /lanes             -- lane lock status");
        AnsiColors.printInfo("  /help              -- this help");
        AnsiColors.printInfo("  quit / exit        -- exit");
    }

    /** 打印 heartbeat 消息 (蓝色标记) */
    static void printHeartbeat(String text) {
        System.out.println(AnsiColors.BLUE + AnsiColors.BOLD + "[heartbeat]" + AnsiColors.RESET + " " + text);
    }

    /** 打印 cron 消息 (紫色标记) */
    static void printCron(String text) {
        System.out.println(AnsiColors.MAGENTA + AnsiColors.BOLD + "[cron]" + AnsiColors.RESET + " " + text);
    }

    // ================================================================
    // Agent Loop (带 Heartbeat + Cron 的完整 REPL 循环)
    // ================================================================

    /**
     * 完整的 Agent 循环, 集成 Heartbeat 和 Cron.
     *
     * 启动阶段:
     *   1. 创建共享 ReentrantLock (用户和 heartbeat 竞争)
     *   2. 初始化 HeartbeatRunner (后台线程)
     *   3. 初始化 CronService (后台线程)
     *   4. 注册 ShutdownHook (Ctrl+C 优雅退出)
     *
     * REPL 循环:
     *   每轮先排空 heartbeat/cron 的输出队列
     *   用户对话时阻塞获取锁 (用户始终优先)
     */
    static void agentLoop() {
        // ---- 初始化 ----

        // 共享锁: 用户对话 (阻塞获取) 和 heartbeat (非阻塞获取) 竞争
        ReentrantLock laneLock = new ReentrantLock();
        SimpleMemoryStore memory = new SimpleMemoryStore(WORKSPACE_DIR);

        // HeartbeatRunner
        HeartbeatRunner heartbeat = new HeartbeatRunner(
                WORKSPACE_DIR, laneLock,
                Double.parseDouble(Config.get("HEARTBEAT_INTERVAL", "1800")),
                Integer.parseInt(Config.get("HEARTBEAT_ACTIVE_START", "9")),
                Integer.parseInt(Config.get("HEARTBEAT_ACTIVE_END", "22"))
        );

        // CronService
        CronService cronSvc = new CronService(WORKSPACE_DIR.resolve("CRON.json"));

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
                cronSvc.tick();
            } catch (Exception e) { /* ignore */ }
        }, 1, 1, TimeUnit.SECONDS);

        // 注册 ShutdownHook: Ctrl+C 时优雅关闭 heartbeat 和 cron
        Thread shutdownHook = new Thread(() -> {
            AnsiColors.printInfo("\n  Shutting down...");
            heartbeat.stop();
            cronScheduler.shutdown();
            try {
                cronScheduler.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            AnsiColors.printInfo("  Goodbye.");
        }, "shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        // 构建系统提示词
        List<MessageParam> messages = new ArrayList<>();
        String memText = memory.loadEvergreen();
        String extra = memText.isEmpty() ? "" : "## Long-term Memory\n\n" + memText;
        String systemPrompt = new SoulSystem(WORKSPACE_DIR).buildSystemPrompt(extra);

        // 工具处理函数
        Function<String, Function<Map<String, Object>, String>> toolHandlerFactory = toolName -> input -> {
            if ("memory_write".equals(toolName)) {
                return memory.writeMemory((String) input.getOrDefault("content", ""));
            }
            if ("memory_search".equals(toolName)) {
                return memory.searchMemory((String) input.getOrDefault("query", ""));
            }
            return "Unknown tool: " + toolName;
        };

        // 打印 banner
        Map<String, Object> hbStatus = heartbeat.status();
        AnsiColors.printInfo("============================================================");
        AnsiColors.printInfo("  claw0  |  Section 07: Heartbeat & Cron");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("  Heartbeat: " + ((boolean) hbStatus.get("enabled") ? "on" : "off")
                + " (" + hbStatus.get("interval") + ")");
        AnsiColors.printInfo("  Cron jobs: " + cronSvc.jobs.size());
        AnsiColors.printInfo("  /help for commands. quit to exit.");
        AnsiColors.printInfo("============================================================");
        System.out.println();

        // ---- REPL 循环 ----
        Scanner scanner = new Scanner(System.in);
        while (true) {
            // 排空 heartbeat 和 cron 的输出
            for (String msg : heartbeat.drainOutput()) printHeartbeat(msg);
            for (String msg : cronSvc.drainOutput()) printCron(msg);

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
                String[] cmdParts = userInput.split("\\s+", 2);
                String cmd = cmdParts[0].toLowerCase();
                String arg = cmdParts.length > 1 ? cmdParts[1].strip() : "";

                switch (cmd) {
                    case "/help" -> printReplHelp();
                    case "/heartbeat" -> {
                        for (Map.Entry<String, Object> e : heartbeat.status().entrySet()) {
                            AnsiColors.printInfo("  " + e.getKey() + ": " + e.getValue());
                        }
                    }
                    case "/trigger" -> {
                        AnsiColors.printInfo("  " + heartbeat.trigger());
                        for (String m : heartbeat.drainOutput()) printHeartbeat(m);
                    }
                    case "/cron" -> {
                        List<Map<String, Object>> jobs = cronSvc.listJobs();
                        if (jobs.isEmpty()) {
                            AnsiColors.printInfo("  No cron jobs.");
                        }
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
                    case "/cron-trigger" -> {
                        if (arg.isEmpty()) {
                            System.out.println(AnsiColors.YELLOW
                                    + "  Usage: /cron-trigger <job_id>" + AnsiColors.RESET);
                        } else {
                            AnsiColors.printInfo("  " + cronSvc.triggerJob(arg));
                            for (String m : cronSvc.drainOutput()) printCron(m);
                        }
                    }
                    case "/lanes" -> {
                        // 尝试非阻塞获取锁来检查状态
                        boolean locked = !laneLock.tryLock();
                        if (!locked) laneLock.unlock();
                        AnsiColors.printInfo("  main_locked: " + locked
                                + "  heartbeat_running: " + heartbeat.running);
                    }
                    default -> System.out.println(AnsiColors.YELLOW
                            + "  Unknown: " + cmd + ". /help for commands." + AnsiColors.RESET);
                }
                continue;
            }

            // ---- 用户对话: 阻塞获取锁 (用户始终优先) ----
            laneLock.lock();
            try {
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(userInput)
                        .build());

                // Agent 内循环: 处理连续的工具调用直到 end_turn
                // 流程: 调用 LLM → 检查 stop_reason → end_turn 则结束, tool_use 则执行工具后继续循环
                while (true) {
                    // 步骤 1: 调用 Claude API, 获取本轮回复
                    Message response;
                    try {
                        response = client.messages().create(MessageCreateParams.builder()
                                .model(MODEL_ID)
                                .maxTokens(8096)
                                .system(systemPrompt)
                                .tools(MEMORY_TOOLS)
                                .messages(messages)
                                .build());
                    } catch (Exception e) {
                        System.out.println("\n" + AnsiColors.YELLOW + "API Error: " + e.getMessage()
                                + AnsiColors.RESET + "\n");
                        // 回滚消息: Claude API 要求 user/assistant 严格交替,
                        // 残留的 user 消息会导致下一轮 400 错误, 因此需要清理到最后一条 user 之前
                        while (!messages.isEmpty()
                                && messages.get(messages.size() - 1).role() != MessageParam.Role.USER) {
                            messages.remove(messages.size() - 1);
                        }
                        if (!messages.isEmpty()) messages.remove(messages.size() - 1);
                        break;
                    }

                    // 步骤 2: 将 assistant 的完整回复加入消息历史 (保持 user/assistant 交替)
                    messages.add(response.toParam());
                    StopReason reason = response.stopReason().orElse(null);

                    if (StopReason.END_TURN.equals(reason)) {
                        // 步骤 3a: 正常结束 -- 提取所有 TextBlock, 拼接后打印给用户
                        String text = response.content().stream()
                                .filter(ContentBlock::isText)
                                .map(ContentBlock::asText)
                                .map(TextBlock::text)
                                .collect(Collectors.joining());
                        if (!text.isEmpty()) AnsiColors.printAssistant(text);
                        break;
                    } else if (StopReason.TOOL_USE.equals(reason)) {
                        // 步骤 3b: 工具调用 -- 遍历所有 ToolUseBlock, 逐个执行并收集结果
                        List<ContentBlockParam> results = new ArrayList<>();
                        for (ContentBlock block : response.content()) {
                            if (!block.isToolUse()) continue;
                            ToolUseBlock tu = block.asToolUse();
                            @SuppressWarnings("unchecked")
                            Map<String, Object> toolInput = tu._input().convert(Map.class);
                            AnsiColors.printTool(tu.name(), "");
                            String result = toolHandlerFactory.apply(tu.name()).apply(toolInput);
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
                    } else {
                        // 步骤 3c: 其他 stop reason (max_tokens 等) -- 尽量提取已有文本
                        AnsiColors.printInfo("[stop_reason=" + reason + "]");
                        String text = response.content().stream()
                                .filter(ContentBlock::isText)
                                .map(ContentBlock::asText)
                                .map(TextBlock::text)
                                .collect(Collectors.joining());
                        if (!text.isEmpty()) AnsiColors.printAssistant(text);
                        break;
                    }
                }
            } finally {
                laneLock.unlock();
            }
        }

        // ---- 清理 ----
        heartbeat.stop();
        cronScheduler.shutdown();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) { /* JVM 已在关闭 */ }
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
