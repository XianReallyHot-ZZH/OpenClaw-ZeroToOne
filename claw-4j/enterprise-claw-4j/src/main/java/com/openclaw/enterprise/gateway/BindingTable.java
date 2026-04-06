package com.openclaw.enterprise.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 5 层路由表 — 消息路由的核心组件
 *
 * <p>维护一组 {@link Binding} 规则，根据入站消息的渠道信息按优先级匹配。
 * 匹配顺序：tier 1 (peer) → tier 2 (guild) → tier 3 (account) → tier 4 (channel) → tier 5 (default)。
 * 同一层级内按 priority 降序排列，第一个匹配即返回。</p>
 *
 * <p>使用 {@link CopyOnWriteArrayList} 保证线程安全 (读多写少场景)。</p>
 *
 * <p>claw0 参考: s05_gateway_routing.py 第 101-139 行 BindingTable 类</p>
 */
@Service
public class BindingTable {

    private static final Logger log = LoggerFactory.getLogger(BindingTable.class);

    /** 绑定规则列表 — CopyOnWriteArrayList 保证读多写少场景的线程安全 */
    private final List<Binding> bindings = new CopyOnWriteArrayList<>();

    /** 排序缓存 — addBinding/removeBinding 时置 null，resolve 时惰性计算 */
    private volatile List<Binding> sortedCache = null;

    /**
     * 解析路由 — 根据入站消息信息匹配最佳 Agent
     *
     * <p>按 tier 升序遍历，同 tier 内按 priority 降序。
     * 第一个匹配的规则即返回 (first match wins)。</p>
     *
     * @param channel   渠道名称 (如 "cli", "telegram", "feishu")
     * @param accountId Bot 账号 ID
     * @param guildId   群组 ID (私聊时为 null)
     * @param peerId    对话方 ID
     * @return 匹配结果，无匹配时返回 empty
     */
    public Optional<ResolvedBinding> resolve(String channel, String accountId,
                                              String guildId, String peerId) {
        List<Binding> sorted = sortedCache;
        if (sorted == null) {
            sorted = bindings.stream()
                .sorted(Comparator
                    .comparingInt(Binding::tier)
                    .thenComparing(Comparator.comparingInt(Binding::priority).reversed()))
                .toList();
            sortedCache = sorted;
        }
        return sorted.stream()
            .filter(b -> matches(b, channel, accountId, guildId, peerId))
            .findFirst()
            .map(b -> new ResolvedBinding(b.agentId(), b));
    }

    /**
     * 添加绑定规则
     *
     * @param binding 绑定规则
     */
    public void addBinding(Binding binding) {
        bindings.add(binding);
        sortedCache = null;
        log.info("Binding added: tier={} key={} agent={}", binding.tier(), binding.key(), binding.agentId());
    }

    /**
     * 移除绑定规则
     *
     * @param tier 层级
     * @param key  匹配键
     * @return 是否成功移除
     */
    public boolean removeBinding(int tier, String key) {
        boolean removed = bindings.removeIf(
            b -> b.tier() == tier && b.key().equals(key));
        if (removed) {
            sortedCache = null;
            log.info("Binding removed: tier={} key={}", tier, key);
        }
        return removed;
    }

    /**
     * 列出所有绑定规则
     *
     * @return 绑定规则列表 (已排序)
     */
    public List<Binding> listBindings() {
        return bindings.stream()
            .sorted(Comparator
                .comparingInt(Binding::tier)
                .thenComparing(Comparator.comparingInt(Binding::priority).reversed()))
            .collect(Collectors.toList());
    }

    /**
     * 判断绑定规则是否匹配
     *
     * <p>按 tier 分别匹配不同的字段：</p>
     * <ul>
     *   <li>tier 1: peerId 精确匹配</li>
     *   <li>tier 2: guildId 精确匹配</li>
     *   <li>tier 3: accountId 精确匹配</li>
     *   <li>tier 4: channel 精确匹配</li>
     *   <li>tier 5: key == "default"</li>
     * </ul>
     *
     * @param b         绑定规则
     * @param channel   渠道名称
     * @param accountId 账号 ID
     * @param guildId   群组 ID
     * @param peerId    对话方 ID
     * @return 是否匹配
     */
    private boolean matches(Binding b, String channel, String accountId,
                            String guildId, String peerId) {
        return switch (b.tier()) {
            case 1 -> peerId != null && peerId.equals(b.key());
            case 2 -> guildId != null && guildId.equals(b.key());
            case 3 -> accountId != null && accountId.equals(b.key());
            case 4 -> channel != null && channel.equals(b.key());
            case 5 -> "default".equals(b.key());
            default -> false;
        };
    }
}
