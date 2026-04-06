package com.openclaw.enterprise.concurrency;

/**
 * 通道状态记录 — 描述一个命名通道 (Lane) 的当前状态
 *
 * @param name         通道名称 (如 "main", "cron", "heartbeat")
 * @param activeCount  当前活跃任务数
 * @param queueSize    队列中等待的任务数
 * @param generation   当前代数 (每次 reset 时递增)
 */
public record LaneStatus(String name, int activeCount, int queueSize, int generation) {}
