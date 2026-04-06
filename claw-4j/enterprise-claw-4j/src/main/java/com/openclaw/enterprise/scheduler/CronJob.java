package com.openclaw.enterprise.scheduler;

import java.time.Instant;

/**
 * Cron 任务 — 一个可调度的定时任务实体
 *
 * <p>每个 CronJob 包含调度表达式、执行载荷和状态信息。</p>
 *
 * <p>自动禁用机制：连续错误累计达到 {@link #AUTO_DISABLE_THRESHOLD} 时，
 * 自动将 {@code enabled} 设为 false，防止故障任务持续运行。</p>
 *
 * <p>一次性任务：当 {@code deleteAfterRun} 为 true 时，执行成功后自动删除。</p>
 *
 * <p>claw0 参考: s07_heartbeat_cron.py 第 130-180 行 CronJob</p>
 */
public class CronJob {

    /** 连续错误自动禁用阈值 */
    public static final int AUTO_DISABLE_THRESHOLD = 5;

    private final String id;
    private final String label;
    private final CronSchedule schedule;
    private final CronPayload payload;
    private final boolean deleteAfterRun;

    private boolean enabled = true;
    private int errorCount = 0;
    private Instant lastRunAt;

    public CronJob(String id, String label, CronSchedule schedule,
                   CronPayload payload, boolean deleteAfterRun) {
        this.id = id;
        this.label = label;
        this.schedule = schedule;
        this.payload = payload;
        this.deleteAfterRun = deleteAfterRun;
    }

    /**
     * 错误计数 +1，达到阈值自动禁用
     */
    public void incrementError() {
        errorCount++;
        if (errorCount >= AUTO_DISABLE_THRESHOLD) {
            enabled = false;
        }
    }

    /**
     * 标记成功执行 — 重置错误计数，更新最后执行时间
     */
    public void markRun(Instant now) {
        this.lastRunAt = now;
    }

    /**
     * 重置错误计数 (成功执行后调用)
     */
    public void resetErrorCount() {
        this.errorCount = 0;
    }

    // ==================== Getters ====================

    public String getId() { return id; }
    public String getLabel() { return label; }
    public CronSchedule getSchedule() { return schedule; }
    public CronPayload getPayload() { return payload; }
    public boolean isDeleteAfterRun() { return deleteAfterRun; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getErrorCount() { return errorCount; }
    public Instant getLastRunAt() { return lastRunAt; }
}
