package com.openclaw.enterprise.gateway;

/**
 * 解析后的绑定结果 — BindingTable.resolve() 的返回值
 *
 * <p>包含匹配到的 Agent ID 和导致匹配的原始 Binding。
 * Optional<ResolvedBinding> 为空表示无匹配规则。</p>
 *
 * @param agentId 目标 Agent ID
 * @param binding 匹配到的绑定规则
 */
public record ResolvedBinding(
    String agentId,
    Binding binding
) {}
