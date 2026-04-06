package com.openclaw.enterprise.gateway;

import com.openclaw.enterprise.agent.AgentConfig;
import com.openclaw.enterprise.agent.DmScope;
import com.openclaw.enterprise.channel.InboundMessage;
import com.openclaw.enterprise.common.exceptions.AgentException;
import com.openclaw.enterprise.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Agent 管理器 — 管理 Agent 的注册、查询和会话键构建
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>Agent 注册/注销 (内存 Map)</li>
 *   <li>根据 DmScope 构建会话键</li>
 *   <li>Agent ID 格式校验</li>
 * </ul>
 *
 * <p>claw0 参考: s05_gateway_routing.py 第 169-216 行 AgentManager 类</p>
 */
@Service
public class AgentManager {

    private static final Logger log = LoggerFactory.getLogger(AgentManager.class);

    /** Agent ID 合法格式: [a-z0-9][a-z0-9_-]{0,63} */
    private static final Pattern AGENT_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    private final Map<String, AgentConfig> agents = new ConcurrentHashMap<>();
    private final AppProperties.GatewayProperties gatewayProps;

    /**
     * 构造 Agent 管理器
     *
     * @param gatewayProps 网关配置
     */
    public AgentManager(AppProperties.GatewayProperties gatewayProps) {
        this.gatewayProps = gatewayProps;
    }

    /**
     * 注册 Agent
     *
     * @param config Agent 配置
     * @throws AgentException 如果 Agent ID 格式错误或已存在
     */
    public void register(AgentConfig config) {
        validateAgentId(config.id());

        if (agents.containsKey(config.id())) {
            throw new AgentException(config.id(), "Agent already exists: " + config.id());
        }

        agents.put(config.id(), config);
        log.info("Agent registered: {} (scope={})", config.id(), config.dmScope());
    }

    /**
     * 注销 Agent
     *
     * @param agentId Agent ID
     * @throws AgentException 如果 Agent 不存在
     */
    public void unregister(String agentId) {
        AgentConfig removed = agents.remove(agentId);
        if (removed == null) {
            throw new AgentException(agentId, "Agent not found: " + agentId);
        }
        log.info("Agent unregistered: {}", agentId);
    }

    /**
     * 获取 Agent 配置
     *
     * @param agentId Agent ID
     * @return Agent 配置 (可能为空)
     */
    public Optional<AgentConfig> getAgent(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    /**
     * 列出所有已注册的 Agent
     *
     * @return Agent 配置列表
     */
    public List<AgentConfig> listAgents() {
        return List.copyOf(agents.values());
    }

    /**
     * 获取默认 Agent ID
     *
     * @return 默认 Agent ID
     */
    public String getDefaultAgentId() {
        return gatewayProps.defaultAgent();
    }

    /**
     * 根据入站消息构建会话键
     *
     * <p>根据 Agent 的 DmScope 配置，构建不同粒度的会话键：</p>
     * <table>
     *   <tr><th>DmScope</th><th>格式</th><th>示例</th></tr>
     *   <tr><td>MAIN</td><td>agent:{id}:main</td><td>agent:luna:main</td></tr>
     *   <tr><td>PER_PEER</td><td>agent:{id}:peer:{peerId}</td><td>agent:luna:peer:12345</td></tr>
     *   <tr><td>PER_CHANNEL_PEER</td><td>agent:{id}:{channel}:{peerId}</td><td>agent:luna:telegram:12345</td></tr>
     *   <tr><td>PER_ACCOUNT_CHANNEL_PEER</td><td>agent:{id}:{accountId}:{channel}:{peerId}</td><td>agent:luna:bot1:telegram:12345</td></tr>
     * </table>
     *
     * @param agentId Agent ID
     * @param msg     入站消息
     * @return 会话键字符串
     */
    public String buildSessionKey(String agentId, InboundMessage msg) {
        AgentConfig config = agents.get(agentId);
        DmScope scope = config != null ? config.dmScope() : DmScope.PER_PEER;

        return switch (scope) {
            case MAIN -> "agent:" + agentId + ":main";
            case PER_PEER -> "agent:" + agentId + ":peer:" + msg.peerId();
            case PER_CHANNEL_PEER -> "agent:" + agentId + ":" + msg.channel() + ":" + msg.peerId();
            case PER_ACCOUNT_CHANNEL_PEER -> "agent:" + agentId + ":"
                + msg.accountId() + ":" + msg.channel() + ":" + msg.peerId();
        };
    }

    /**
     * 校验 Agent ID 格式
     *
     * @param agentId Agent ID
     * @throws AgentException 如果格式不合法
     */
    private void validateAgentId(String agentId) {
        if (agentId == null || !AGENT_ID_PATTERN.matcher(agentId).matches()) {
            throw new AgentException(agentId,
                "Invalid agent ID: must match [a-z0-9][a-z0-9_-]{0,63}");
        }
    }

    /**
     * 规范化 Agent ID — 将原始字符串转为合法的 Agent ID 格式
     *
     * <p>转换规则：</p>
     * <ul>
     *   <li>转换为小写</li>
     *   <li>空格和特殊字符替换为连字符</li>
     *   <li>移除开头非字母数字字符</li>
     *   <li>截断至 64 字符</li>
     *   <li>匹配模式 {@code [a-z0-9][a-z0-9_-]{0,63}}</li>
     * </ul>
     *
     * @param raw 原始 Agent ID 字符串
     * @return 规范化后的 Agent ID
     */
    public String normalizeAgentId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "agent";
        }
        // 转小写，空格和特殊字符替换为连字符
        String normalized = raw.toLowerCase()
            .replaceAll("[^a-z0-9_-]", "-");
        // 移除开头的非字母数字字符
        normalized = normalized.replaceFirst("^[^a-z0-9]+", "");
        // 压缩连续连字符
        normalized = normalized.replaceAll("-+", "-");
        // 去除首尾连字符
        normalized = normalized.replaceAll("^-|-$", "");
        // 截断至 64 字符
        if (normalized.length() > 64) {
            normalized = normalized.substring(0, 64);
        }
        // 确保至少有一个有效字符
        if (normalized.isEmpty()) {
            normalized = "agent";
        }
        return normalized;
    }
}
