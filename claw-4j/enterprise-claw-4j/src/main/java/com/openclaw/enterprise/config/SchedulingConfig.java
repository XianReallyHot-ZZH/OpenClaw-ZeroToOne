package com.openclaw.enterprise.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executors;

/**
 * 调度配置 — 启用 Spring @Scheduled 支持，使用虚拟线程调度池
 *
 * <p>调度池大小为 4，使用 Java 21 虚拟线程。
 * 所有 @Scheduled 方法 (CronJobService.tick, HeartbeatService.heartbeat,
 * DeliveryRunner.processQueue) 共享此调度池。</p>
 *
 * <p>claw0 参考: s07_heartbeat_cron.py 中的定时调度机制</p>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setScheduler(Executors.newScheduledThreadPool(4,
            Thread.ofVirtual().name("scheduler-").factory()));
    }
}
