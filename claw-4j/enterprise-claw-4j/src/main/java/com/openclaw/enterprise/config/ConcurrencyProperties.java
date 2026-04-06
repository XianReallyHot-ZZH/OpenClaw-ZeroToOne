package com.openclaw.enterprise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * 并发控制配置属性 — 命名通道的并发限制
 *
 * <p>每个命名通道 (lane) 配置独立的最大并发数。</p>
 *
 * <p>配置示例 (application.yml)：</p>
 * <pre>
 * concurrency:
 *   lanes:
 *     main:
 *       max-concurrency: 3
 *     cron:
 *       max-concurrency: 1
 *     heartbeat:
 *       max-concurrency: 1
 * </pre>
 */
@ConfigurationProperties(prefix = "concurrency")
public record ConcurrencyProperties(
    Map<String, LaneConfig> lanes
) {
    /**
     * 单个通道的并发配置
     *
     * @param maxConcurrency 最大并发数
     */
    public record LaneConfig(
        int maxConcurrency
    ) {}
}
