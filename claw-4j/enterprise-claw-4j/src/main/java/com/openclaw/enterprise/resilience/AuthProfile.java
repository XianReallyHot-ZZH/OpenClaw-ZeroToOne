package com.openclaw.enterprise.resilience;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import java.time.Instant;

/**
 * 认证 Profile — 一个 Anthropic API 认证配置，支持冷却和故障标记
 *
 * <p>每个 Profile 包含独立的 API Key 和可选的 Base URL，
 * 拥有自己的冷却状态和故障记录。</p>
 *
 * <p>冷却机制：当 API 调用失败时，根据 {@link FailoverReason} 类型
 * 设置不同的冷却时长。在冷却期内，此 Profile 不会被选择使用。</p>
 *
 * <p>注意: 使用 class 而非 record，因为包含 volatile 可变状态字段。</p>
 *
 * <p>claw0 参考: s09_resilience.py 第 130-200 行 AuthProfile</p>
 */
public class AuthProfile {

    private final String name;
    private final String apiKey;
    private final String baseUrl;

    /** 冷却截止时间 — 在此之前此 Profile 不会被选择 */
    private volatile Instant cooldownUntil = Instant.MIN;

    /** 最近一次失败原因 */
    private volatile FailoverReason failureReason = null;

    /** 最近一次成功时间 */
    private volatile Instant lastGoodAt = Instant.now();

    /** 缓存的 AnthropicClient 实例 — 避免每次调用都创建新客户端 */
    private volatile AnthropicClient cachedClient;

    public AuthProfile(String name, String apiKey, String baseUrl) {
        this.name = name;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    /**
     * 获取或创建 AnthropicClient 实例
     *
     * <p>使用此 Profile 的 apiKey 和可选 baseUrl 构建 SDK 客户端。
     * 客户端实例会被缓存，避免每次调用都创建新客户端。</p>
     *
     * @return AnthropicClient 实例 (缓存)
     */
    public AnthropicClient createClient() {
        if (cachedClient == null) {
            synchronized (this) {
                if (cachedClient == null) {
                    var builder = AnthropicOkHttpClient.builder()
                        .apiKey(apiKey);
                    if (baseUrl != null && !baseUrl.isBlank()) {
                        builder.baseUrl(baseUrl);
                    }
                    cachedClient = builder.build();
                }
            }
        }
        return cachedClient;
    }

    /**
     * 检查此 Profile 是否在冷却期内
     *
     * @return 如果当前时间早于冷却截止时间则返回 true
     */
    public boolean isInCooldown() {
        return Instant.now().isBefore(cooldownUntil);
    }

    /**
     * 标记此 Profile 发生故障
     *
     * <p>根据故障类型设置冷却截止时间：</p>
     * <ul>
     *   <li>{@code FailoverReason} 的 cooldownSeconds() 决定冷却时长</li>
     *   <li>{@code ContextOverflow} 冷却时间为 0 (不冷却)</li>
     * </ul>
     *
     * @param reason 故障原因
     */
    public void markFailure(FailoverReason reason) {
        this.failureReason = reason;
        if (reason.cooldownSeconds() > 0) {
            this.cooldownUntil = Instant.now().plusSeconds(reason.cooldownSeconds());
        }
    }

    /**
     * 标记此 Profile 调用成功 — 重置所有故障状态
     */
    public void markSuccess() {
        this.lastGoodAt = Instant.now();
        this.cooldownUntil = Instant.MIN;
        this.failureReason = null;
    }

    // ==================== Getters ====================

    public String getName() { return name; }
    public String getApiKey() { return apiKey; }
    public String getBaseUrl() { return baseUrl; }
    public FailoverReason getFailureReason() { return failureReason; }
    public Instant getLastGoodAt() { return lastGoodAt; }
    public Instant getCooldownUntil() { return cooldownUntil; }
}
