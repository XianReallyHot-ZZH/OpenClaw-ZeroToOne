package com.openclaw.enterprise.resilience;

import com.openclaw.enterprise.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Profile 管理器 — 管理 Anthropic API 认证 Profile 池
 *
 * <p>职责：</p>
 * <ul>
 *   <li>从配置加载多个 API Key Profile</li>
 *   <li>选择第一个非冷却的 Profile</li>
 *   <li>标记 Profile 的成功/失败状态</li>
 * </ul>
 *
 * <p>当主 Key 触发限流 (429) 或认证错误 (401) 时，自动切换到备用 Key。
 * 每个 Key 独立维护冷却状态，冷却期结束后自动恢复可用。</p>
 *
 * <p>claw0 参考: s09_resilience.py 第 200-280 行 ProfileManager</p>
 */
@Service
public class ProfileManager {

    private static final Logger log = LoggerFactory.getLogger(ProfileManager.class);

    private final List<AuthProfile> profiles;

    /**
     * 构造 Profile 管理器 — 从配置过滤有效 Profile
     *
     * <p>只保留 apiKey 非空的 Profile 条目。</p>
     *
     * @param props Anthropic 配置属性
     */
    public ProfileManager(AppProperties.AnthropicProperties props) {
        this.profiles = props.profiles().stream()
            .filter(p -> p.apiKey() != null && !p.apiKey().isBlank())
            .map(p -> new AuthProfile(p.name(), p.apiKey(), p.baseUrl()))
            .toList();

        log.info("Initialized ProfileManager with {} profiles", profiles.size());
        for (AuthProfile profile : profiles) {
            log.info("  Profile: {} (baseUrl={})",
                profile.getName(),
                profile.getBaseUrl() != null ? profile.getBaseUrl() : "default");
        }
    }

    /**
     * 选择第一个非冷却的 Profile
     *
     * @return 可用的 Profile，如果全部冷却则返回 empty
     */
    public Optional<AuthProfile> selectProfile() {
        for (AuthProfile profile : profiles) {
            if (!profile.isInCooldown()) {
                return Optional.of(profile);
            }
        }
        // 所有 Profile 都在冷却 — 打印警告
        log.warn("All {} profiles are in cooldown", profiles.size());
        return Optional.empty();
    }

    /**
     * 标记 Profile 故障 — 委托给 Profile 自身的 markFailure
     *
     * @param profile 故障的 Profile
     * @param reason  故障原因
     */
    public void markFailure(AuthProfile profile, FailoverReason reason) {
        profile.markFailure(reason);
        log.warn("Profile '{}' marked as failed: {} (cooldown: {}s)",
            profile.getName(), reason, reason.cooldownSeconds());
    }

    /**
     * 标记 Profile 成功 — 委托给 Profile 自身的 markSuccess
     *
     * @param profile 成功的 Profile
     */
    public void markSuccess(AuthProfile profile) {
        profile.markSuccess();
    }

    /**
     * 获取当前可用的 (非冷却) Profile 数量
     *
     * @return 可用 Profile 数量
     */
    public int getAvailableCount() {
        return (int) profiles.stream()
            .filter(p -> !p.isInCooldown())
            .count();
    }

    /**
     * 获取所有 Profile (只读)
     *
     * @return Profile 列表
     */
    public List<AuthProfile> getProfiles() {
        return List.copyOf(profiles);
    }
}
