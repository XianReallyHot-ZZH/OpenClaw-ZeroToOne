package com.openclaw.enterprise.auth;

/**
 * 认证过滤器接口 — WebSocket 和 REST API 的认证扩展点
 *
 * <p>默认实现 {@link DefaultAuthFilter} 允许所有请求。
 * 用户可以通过注册自己的 {@link AuthFilter} Bean 来覆盖默认行为，
 * 实现 Token 验证、IP 白名单等认证逻辑。</p>
 */
public interface AuthFilter {

    /**
     * 判断请求是否允许通过
     *
     * @param token 请求中的认证 Token (可能为 null)
     * @param path  请求路径
     * @return true 表示允许通过
     */
    boolean allow(String token, String path);
}
