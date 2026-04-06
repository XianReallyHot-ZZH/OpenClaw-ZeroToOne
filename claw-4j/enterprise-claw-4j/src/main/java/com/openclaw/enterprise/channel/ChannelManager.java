package com.openclaw.enterprise.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渠道管理器 — 管理所有已注册的消息渠道
 *
 * <p>通过 Spring 构造注入自动收集所有 {@link Channel} 实现，
 * 提供统一的渠道查询、消息接收和生命周期管理。</p>
 *
 * <p>注册的渠道取决于 Spring 配置：</p>
 * <ul>
 *   <li>{@code CliChannel} — 始终注册</li>
 *   <li>{@code TelegramChannel} — 当 {@code channels.telegram.enabled=true} 时注册</li>
 *   <li>{@code FeishuChannel} — 当 {@code channels.feishu.enabled=true} 时注册</li>
 * </ul>
 *
 * <p>claw0 参考: s04_channels.py 中的渠道管理逻辑</p>
 */
@Service
public class ChannelManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

    /** 渠道名称 → 渠道实例的映射 */
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    /** 已停止接收的渠道名称集合 */
    private final Set<String> stoppedChannels = ConcurrentHashMap.newKeySet();

    /**
     * 构造渠道管理器
     *
     * <p>Spring 自动注入所有 {@link Channel} 实现类的列表。
     * 以渠道名称为键注册到内部 Map 中。</p>
     *
     * @param channelList Spring 自动收集的所有 Channel 实现
     */
    public ChannelManager(List<Channel> channelList) {
        channelList.forEach(c -> {
            channels.put(c.getName(), c);
            log.info("Registered channel: {}", c.getName());
        });
        log.info("ChannelManager initialized with {} channels", channels.size());
    }

    /**
     * 手动注册渠道
     *
     * @param channel 渠道实例
     */
    public void register(Channel channel) {
        channels.put(channel.getName(), channel);
        log.info("Manually registered channel: {}", channel.getName());
    }

    /**
     * 获取指定名称的渠道
     *
     * @param name 渠道名称
     * @return 渠道实例 (可能为空)
     */
    public Optional<Channel> get(String name) {
        return Optional.ofNullable(channels.get(name));
    }

    /**
     * 获取所有已注册的渠道
     *
     * @return 渠道集合
     */
    public Collection<Channel> getAll() {
        return channels.values();
    }

    /**
     * 停止所有渠道的消息接收
     *
     * <p>在应用关闭时调用，通知所有渠道停止接收新消息。
     * 不会移除渠道注册 — 那是 closeAll 的职责。</p>
     */
    public void stopReceiving() {
        channels.forEach((name, channel) -> {
            channel.close();
            stoppedChannels.add(name);
        });
        log.info("All channels stopped receiving ({} channels)", stoppedChannels.size());
    }

    /**
     * 关闭所有渠道连接
     *
     * <p>先停止接收，再清空渠道注册表，释放所有渠道资源。</p>
     */
    public void closeAll() {
        stopReceiving();
        channels.clear();
        stoppedChannels.clear();
        log.info("All channels closed");
    }
}
