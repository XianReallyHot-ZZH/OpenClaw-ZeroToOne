package com.openclaw.enterprise.health;

import com.openclaw.enterprise.config.AppProperties;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

import java.nio.file.Files;

/**
 * 工作空间健康检查 — 验证 workspace 目录的读写权限
 *
 * <p>Spring Boot Actuator 的健康检查端点 ({@code /actuator/health}) 会自动
 * 收集所有 {@code HealthIndicator} Bean 的状态。</p>
 *
 * <p>检查内容：</p>
 * <ul>
 *   <li>工作空间目录是否可读</li>
 *   <li>工作空间目录是否可写</li>
 * </ul>
 *
 * <p>当两者都满足时状态为 UP，否则为 DOWN。</p>
 */
@Component
public class WorkspaceHealthIndicator extends AbstractHealthIndicator {

    private final java.nio.file.Path workspacePath;

    public WorkspaceHealthIndicator(AppProperties.WorkspaceProperties workspaceProps) {
        this.workspacePath = workspaceProps.path();
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        boolean readable = Files.isReadable(workspacePath);
        boolean writable = Files.isWritable(workspacePath);

        if (readable && writable) {
            builder.up()
                .withDetail("path", workspacePath.toAbsolutePath().toString())
                .withDetail("readable", true)
                .withDetail("writable", true);
        } else {
            builder.down()
                .withDetail("path", workspacePath.toAbsolutePath().toString())
                .withDetail("readable", readable)
                .withDetail("writable", writable);
        }
    }
}
