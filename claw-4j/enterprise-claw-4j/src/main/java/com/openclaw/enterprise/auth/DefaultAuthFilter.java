package com.openclaw.enterprise.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 默认认证过滤器 — 允许所有请求通过
 *
 * <p>当没有其他 {@link AuthFilter} 实现注册时，使用此默认实现。
 * 所有请求均返回 {@code true} (放行)。</p>
 *
 * <p>要覆盖此默认行为，注册一个实现 {@link AuthFilter} 的 {@code @Component}，
 * 并标注 {@code @Primary}。</p>
 */
@Component
@Primary
@ConditionalOnMissingBean(AuthFilter.class)
public class DefaultAuthFilter implements AuthFilter {

    @Override
    public boolean allow(String token, String path) {
        return true;  // 默认允许所有请求
    }
}
