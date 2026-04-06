package com.openclaw.enterprise.scheduler;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

/**
 * Cron 调度表达式 — 支持三种调度模式的密封接口
 *
 * <p>三种调度模式：</p>
 * <ul>
 *   <li>{@link At} — 一次性定时任务，在指定时刻触发</li>
 *   <li>{@link Every} — 固定间隔任务，每隔 N 秒触发一次</li>
 *   <li>{@link CronExpression} — 标准 5 字段 cron 表达式</li>
 * </ul>
 *
 * <p>CRON.json 中的 schedule 字段使用以下 JSON 格式：</p>
 * <pre>
 * // At 模式
 * {"at": "2026-04-06T14:00:00Z"}
 *
 * // Every 模式
 * {"every": 3600}
 *
 * // Cron 表达式模式
 * {"cron": "0 9 * * *"}
 * </pre>
 *
 * <p>claw0 参考: s07_heartbeat_cron.py 第 80-130 行 CronSchedule 解析</p>
 */
@JsonDeserialize(using = CronScheduleDeserializer.class)
public sealed interface CronSchedule
    permits CronSchedule.At, CronSchedule.Every, CronSchedule.CronExpression {

    /**
     * 一次性定时 — 在指定时刻触发，触发后可选删除
     *
     * @param datetime 触发时刻
     */
    record At(Instant datetime) implements CronSchedule {}

    /**
     * 固定间隔 — 每隔 intervalSeconds 秒触发一次
     *
     * @param intervalSeconds 间隔秒数
     */
    record Every(int intervalSeconds) implements CronSchedule {}

    /**
     * 标准 5 字段 cron 表达式 — 分 时 日 月 星期
     *
     * @param expression cron 表达式字符串 (如 "0 9 * * *" = 每天 9:00)
     */
    record CronExpression(String expression) implements CronSchedule {}
}
