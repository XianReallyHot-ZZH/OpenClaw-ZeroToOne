package com.openclaw.enterprise.config;

import com.openclaw.enterprise.config.AppProperties.*;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 应用全局配置
 *
 * <p>职责：</p>
 * <ul>
 *   <li>启用 Spring Retry 支持 (@EnableRetry)</li>
 *   <li>注册所有 ConfigurationProperties</li>
 *   <li>启动时确保工作空间子目录存在</li>
 * </ul>
 *
 * <p>注意: @EnableScheduling 不在此处启用，
 * 而是在 Sprint 5 的 SchedulingConfig 中统一管理。</p>
 */
@Configuration
@EnableRetry
@EnableConfigurationProperties({
    AnthropicProperties.class,
    GatewayProperties.class,
    HeartbeatProperties.class,
    ChannelProperties.class,
    WorkspaceProperties.class,
    DeliveryProperties.class
})
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    private final WorkspaceProperties workspaceProps;

    public AppConfig(WorkspaceProperties workspaceProps) {
        this.workspaceProps = workspaceProps;
    }

    /**
     * 启动时确保所有必要的工作空间子目录存在
     *
     * <p>创建以下目录结构：</p>
     * <pre>
     * workspace/
     * ├── .sessions/agents/     # 会话持久化
     * ├── memory/daily/         # 日志记忆
     * ├── delivery-queue/
     * │   ├── pending/          # 待投递消息
     * │   └── failed/           # 投递失败消息
     * ├── cron/                 # 定时任务日志
     * ├── skills/               # 技能文件
     * └── logs/                 # 应用日志
     * </pre>
     */
    @PostConstruct
    void ensureDirectories() {
        Path ws = workspaceProps.path();
        List.of(
            ws.resolve(".sessions/agents"),
            ws.resolve("memory/daily"),
            ws.resolve("delivery-queue/pending"),
            ws.resolve("delivery-queue/failed"),
            ws.resolve("cron"),
            ws.resolve("skills"),
            ws.resolve("logs")
        ).forEach(dir -> {
            try {
                Files.createDirectories(dir);
                log.debug("Directory ensured: {}", dir);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create directory: " + dir, e);
            }
        });
        log.info("Workspace directories initialized at: {}", ws.toAbsolutePath());
    }
}
