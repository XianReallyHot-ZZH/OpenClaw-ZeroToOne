package com.openclaw.enterprise.gateway;

import java.util.Map;

/**
 * 路由绑定记录 — 5 层路由表的一条规则
 *
 * <p>每条绑定定义了：在特定匹配条件下，将消息路由到哪个 Agent。</p>
 *
 * <p>5 层匹配优先级 (tier 越小优先级越高)：</p>
 * <table>
 *   <tr><th>Tier</th><th>匹配条件</th><th>含义</th></tr>
 *   <tr><td>1</td><td>peerId 精确匹配</td><td>指定用户 → 指定 Agent</td></tr>
 *   <tr><td>2</td><td>guildId 精确匹配</td><td>群组 → 指定 Agent</td></tr>
 *   <tr><td>3</td><td>accountId 精确匹配</td><td>Bot 账号 → 指定 Agent</td></tr>
 *   <tr><td>4</td><td>channel 精确匹配</td><td>渠道类型 → 指定 Agent</td></tr>
 *   <tr><td>5</td><td>"default" 匹配</td><td>全局兜底 Agent</td></tr>
 * </table>
 *
 * <p>排序规则: tier ASC, priority DESC — 层级越低优先级越高；
 * 同一层级内，priority 越大优先级越高。</p>
 *
 * <p>claw0 参考: s05_gateway_routing.py 中 Binding dataclass</p>
 *
 * @param tier     层级 (1-5)
 * @param key      匹配键值 (如 peerId, guildId, channel, "default")
 * @param agentId  目标 Agent ID
 * @param priority 同一层级内的排序优先级 (越大越高)
 * @param metadata 可选的元数据
 */
public record Binding(
    int tier,
    String key,
    String agentId,
    int priority,
    Map<String, String> metadata
) {}
