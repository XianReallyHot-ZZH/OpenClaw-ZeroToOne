package com.openclaw.enterprise.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

/**
 * 应用配置属性集合
 *
 * <p>包含所有 @ConfigurationProperties record，通过 application.yml 或环境变量绑定。
 * 零硬编码 — 所有可配置项都通过这些 record 暴露。</p>
 *
 * <p>各 record 的配置前缀对应 application.yml 中的顶级键。</p>
 */
public final class AppProperties {

    private AppProperties() {}  // 工具类，禁止实例化

    // ---- Anthropic API 配置 ----

    /**
     * Anthropic API 配置
     * 支持多个认证 Profile (用于 Sprint 6 的 Auth 轮转)
     */
    @ConfigurationProperties(prefix = "anthropic")
    public record AnthropicProperties(
        String modelId,
        int maxTokens,
        List<ProfileEntry> profiles
    ) {
        /**
         * 认证 Profile 条目
         * name: Profile 名称 (如 main, backup)
         * apiKey: Anthropic API 密钥
         * baseUrl: 可选的自定义 API 端点
         */
        public record ProfileEntry(
            String name,
            String apiKey,
            String baseUrl
        ) {}
    }

    // ---- 网关配置 ----

    /**
     * 网关路由配置
     */
    @ConfigurationProperties(prefix = "gateway")
    public record GatewayProperties(
        String defaultAgent,
        int maxConcurrentAgents
    ) {}

    // ---- 心跳配置 ----

    /**
     * 心跳服务配置
     */
    @ConfigurationProperties(prefix = "heartbeat")
    public record HeartbeatProperties(
        int intervalSeconds,
        int activeStartHour,
        int activeEndHour
    ) {}

    // ---- 渠道配置 ----

    /**
     * 多渠道配置
     */
    @ConfigurationProperties(prefix = "channels")
    public record ChannelProperties(
        TelegramConfig telegram,
        FeishuConfig feishu
    ) {
        public record TelegramConfig(
            boolean enabled,
            String token
        ) {}

        public record FeishuConfig(
            boolean enabled,
            String appId,
            String appSecret,
            String verificationToken
        ) {}
    }

    // ---- 工作空间配置 ----

    /**
     * 工作空间配置
     * path 类型为 Path，Spring Boot 自动转换
     */
    @ConfigurationProperties(prefix = "workspace")
    public record WorkspaceProperties(
        Path path,
        int contextBudget
    ) {}

    // ---- CORS 配置 ----

    /**
     * CORS 跨域配置
     */
    @ConfigurationProperties(prefix = "cors")
    public record CorsProperties(
        List<String> allowedOrigins
    ) {
        /** 默认允许的来源列表 */
        public List<String> effectiveOrigins() {
            return allowedOrigins != null && !allowedOrigins.isEmpty()
                ? allowedOrigins
                : List.of("http://localhost:8080", "ws://localhost:8080");
        }
    }

    // ---- 投递队列配置 ----

    /**
     * 消息投递队列配置
     */
    @ConfigurationProperties(prefix = "delivery")
    public record DeliveryProperties(
        int pollIntervalMs,
        int maxRetries,
        int backoffBaseSeconds,
        double backoffMultiplier,
        double jitterFactor
    ) {}
}
