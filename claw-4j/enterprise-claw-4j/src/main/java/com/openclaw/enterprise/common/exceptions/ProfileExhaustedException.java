package com.openclaw.enterprise.common.exceptions;

import com.openclaw.enterprise.common.Claw4jException;

/**
 * Profile 耗尽异常 — 所有 Anthropic API 认证 Profile 均处于冷却期
 *
 * <p>在 Auth Profile 轮转机制中，当所有配置的 Profile 都因为
 * 认证失败、限流等原因进入冷却期时抛出。</p>
 *
 * <p>触发后的处理策略：</p>
 * <ul>
 *   <li>返回 503 Service Unavailable 给上游</li>
 *   <li>等待最短冷却期结束后自动恢复</li>
 * </ul>
 *
 * <p>错误码: PROFILES_EXHAUSTED</p>
 *
 * <p>claw0 参考: s09_resilience.py 中 ProfileManager 所有 key 耗尽的处理</p>
 */
public class ProfileExhaustedException extends Claw4jException {

    /** 配置的 Profile 总数 */
    private final int profileCount;

    /**
     * 构造 Profile 耗尽异常
     *
     * @param message 错误描述，通常包含各 Profile 的冷却状态
     */
    public ProfileExhaustedException(String message) {
        super("PROFILES_EXHAUSTED", message);
        this.profileCount = 0;
    }

    /**
     * 构造 Profile 耗尽异常（带 Profile 数量信息）
     *
     * @param message      错误描述
     * @param profileCount 配置的 Profile 总数
     */
    public ProfileExhaustedException(String message, int profileCount) {
        super("PROFILES_EXHAUSTED", message);
        this.profileCount = profileCount;
    }

    /**
     * 获取配置的 Profile 总数
     *
     * @return Profile 数量
     */
    public int getProfileCount() {
        return profileCount;
    }
}
