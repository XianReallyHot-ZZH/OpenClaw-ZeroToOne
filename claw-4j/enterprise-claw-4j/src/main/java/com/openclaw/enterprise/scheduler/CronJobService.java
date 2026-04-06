package com.openclaw.enterprise.scheduler;

import com.openclaw.enterprise.agent.AgentLoop;
import com.openclaw.enterprise.agent.AgentTurnResult;
import com.openclaw.enterprise.common.JsonUtils;
import com.openclaw.enterprise.config.AppProperties;
import com.openclaw.enterprise.gateway.GatewayWebSocketHandler;
import com.openclaw.enterprise.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Cron 任务服务 — 管理定时任务的加载、调度和执行
 *
 * <p>职责：</p>
 * <ul>
 *   <li>从 {@code workspace/CRON.json} 加载定时任务定义</li>
 *   <li>每 60 秒 tick 一次，检查是否有任务到期</li>
 *   <li>执行到期任务 (AgentTurn 或 SystemEvent)</li>
 *   <li>运行时增删任务，自动持久化到 CRON.json</li>
 *   <li>错误累计达到阈值自动禁用</li>
 *   <li>执行日志记录到 {@code workspace/cron/cron-runs.jsonl}</li>
 * </ul>
 *
 * <p>claw0 参考: s07_heartbeat_cron.py 第 200-400 行 CronService</p>
 */
@Service
public class CronJobService {

    private static final Logger log = LoggerFactory.getLogger(CronJobService.class);

    private final List<CronJob> jobs = new CopyOnWriteArrayList<>();
    private final AgentLoop agentLoop;
    private final SessionStore sessionStore;
    private final GatewayWebSocketHandler wsHandler;
    private final Path cronFile;
    private final Path cronRunsDir;

    public CronJobService(AgentLoop agentLoop,
                          SessionStore sessionStore,
                          GatewayWebSocketHandler wsHandler,
                          AppProperties.WorkspaceProperties workspaceProps) {
        this.agentLoop = agentLoop;
        this.sessionStore = sessionStore;
        this.wsHandler = wsHandler;
        this.cronFile = workspaceProps.path().resolve("CRON.json");
        this.cronRunsDir = workspaceProps.path().resolve("cron");
    }

    /**
     * 启动时加载 CRON.json 中的任务定义
     */
    @PostConstruct
    void init() {
        loadJobs();
    }

    /**
     * 定时 tick — 每 60 秒检查并执行到期任务
     */
    @Scheduled(fixedRate = 60_000)
    public void tick() {
        Instant now = Instant.now();
        for (CronJob job : jobs) {
            if (!job.isEnabled()) continue;
            if (isJobDue(job, now)) {
                executeJob(job, now);
            }
        }
    }

    /**
     * 判断任务是否到期 — 根据 CronSchedule 子类型匹配
     */
    boolean isJobDue(CronJob job, Instant now) {
        CronSchedule schedule = job.getSchedule();

        // 使用 sealed interface pattern matching
        if (schedule instanceof CronSchedule.At(var datetime)) {
            // At 模式: 当前时间已过指定时刻
            return !now.isBefore(datetime);
        } else if (schedule instanceof CronSchedule.Every(var intervalSec)) {
            // Every 模式: 距上次执行超过间隔时间
            if (job.getLastRunAt() == null) return true;
            long elapsed = now.getEpochSecond() - job.getLastRunAt().getEpochSecond();
            return elapsed >= intervalSec;
        } else if (schedule instanceof CronSchedule.CronExpression(var expr)) {
            // Cron 表达式模式: 简化实现 — 基于分钟匹配
            return isCronDue(expr, now, job.getLastRunAt());
        }

        return false;
    }

    /**
     * 简化的 cron 表达式匹配 — 检查当前分钟是否匹配 cron 表达式
     *
     * <p>支持 5 字段格式: 分 时 日 月 星期</p>
     * <p>仅支持数字和 '*'，不支持范围/步进等高级语法。</p>
     */
    private boolean isCronDue(String expr, Instant now, Instant lastRunAt) {
        String[] parts = expr.trim().split("\\s+");
        if (parts.length != 5) return false;

        // 转换为 UTC 时间分量
        var zdt = now.atZone(java.time.ZoneOffset.UTC);
        int minute = zdt.getMinute();
        int hour = zdt.getHour();
        int day = zdt.getDayOfMonth();
        int month = zdt.getMonthValue();
        int dow = zdt.getDayOfWeek().getValue(); // 1=Mon ... 7=Sun

        // 逐字段匹配 ('*' 匹配任意值)
        if (!matchesCronField(parts[0], minute)) return false;
        if (!matchesCronField(parts[1], hour)) return false;
        if (!matchesCronField(parts[2], day)) return false;
        if (!matchesCronField(parts[3], month)) return false;
        if (!matchesCronField(parts[4], dow == 7 ? 0 : dow)) return false;

        // 避免同一分钟内重复触发
        if (lastRunAt != null) {
            long secondsSinceLastRun = now.getEpochSecond() - lastRunAt.getEpochSecond();
            if (secondsSinceLastRun < 60) return false;
        }

        return true;
    }

    private boolean matchesCronField(String field, int value) {
        if ("*".equals(field)) return true;
        try {
            return Integer.parseInt(field) == value;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 执行一个到期任务
     */
    private void executeJob(CronJob job, Instant now) {
        try {
            CronPayload payload = job.getPayload();

            if (payload instanceof CronPayload.AgentTurn(var agentId, var prompt)) {
                // AgentTurn: 创建/复用会话并执行 Agent 循环
                String sessionKey = "cron_" + job.getId();
                String sessionId = sessionStore.getSessionMeta(sessionKey)
                    .map(sm -> sm.sessionId())
                    .orElseGet(() -> sessionStore.createSession(agentId, sessionKey));

                AgentTurnResult result = agentLoop.runTurn(agentId, sessionId, prompt);

                // 通知 WebSocket 客户端
                wsHandler.broadcast("cron.output", Map.of(
                    "job_id", job.getId(),
                    "agent_id", agentId,
                    "text", result.text() != null ? result.text() : ""
                ));

                logCronRun(job, true, null);
            } else if (payload instanceof CronPayload.SystemEvent(var message)) {
                // SystemEvent: 直接记录日志
                log.info("Cron system event [{}]: {}", job.getId(), message);
                wsHandler.broadcast("cron.output", Map.of(
                    "job_id", job.getId(),
                    "message", message
                ));
                logCronRun(job, true, null);
            }

            // 成功: 标记执行、重置错误
            job.markRun(now);
            job.resetErrorCount();

            // 一次性任务: 执行后移除
            if (job.isDeleteAfterRun()) {
                removeJobAndPersist(job.getId());
            }

        } catch (Exception e) {
            log.error("Cron job {} failed", job.getId(), e);
            job.incrementError();
            logCronRun(job, false, e.getMessage());

            if (!job.isEnabled()) {
                log.warn("Cron job {} auto-disabled after {} consecutive errors",
                    job.getId(), CronJob.AUTO_DISABLE_THRESHOLD);
            }
        }
    }

    /**
     * 记录 cron 执行日志到 cron-runs.jsonl
     */
    private void logCronRun(CronJob job, boolean success, String error) {
        try {
            Files.createDirectories(cronRunsDir);
            Path logFile = cronRunsDir.resolve("cron-runs.jsonl");
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("job_id", job.getId());
            entry.put("ts", Instant.now().toString());
            entry.put("success", success);
            if (error != null) {
                entry.put("error", error);
            }
            JsonUtils.appendJsonl(logFile, entry);
        } catch (Exception e) {
            log.warn("Failed to log cron run for job {}", job.getId(), e);
        }
    }

    /**
     * 从 CRON.json 加载任务列表
     */
    public void loadJobs() {
        if (!Files.exists(cronFile)) {
            log.info("No CRON.json found at {}", cronFile);
            return;
        }

        try {
            String json = Files.readString(cronFile);
            if (json.isBlank()) return;

            // 使用 Jackson 反序列化，CronSchedule 通过 @JsonDeserialize 自动处理
            List<CronJob> loaded = JsonUtils.mapper().readValue(json,
                JsonUtils.mapper().getTypeFactory()
                    .constructCollectionType(List.class, CronJob.class));

            jobs.clear();
            jobs.addAll(loaded);
            log.info("Loaded {} cron jobs from CRON.json", loaded.size());
        } catch (Exception e) {
            log.error("Failed to load CRON.json", e);
        }
    }

    /**
     * 添加任务并持久化到 CRON.json
     */
    public void addJobAndPersist(CronJob job) {
        jobs.add(job);
        persistJobs();
    }

    /**
     * 移除任务并持久化到 CRON.json
     */
    public void removeJobAndPersist(String jobId) {
        jobs.removeIf(j -> j.getId().equals(jobId));
        persistJobs();
    }

    /**
     * 持久化当前任务列表到 CRON.json (原子写入)
     */
    private void persistJobs() {
        try {
            String json = JsonUtils.mapper()
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(jobs);
            com.openclaw.enterprise.common.FileUtils.writeAtomically(cronFile, json);
        } catch (Exception e) {
            log.error("Failed to persist CRON.json", e);
        }
    }

    /**
     * 获取所有任务列表 (只读)
     */
    public List<CronJob> listJobs() {
        return List.copyOf(jobs);
    }
}
