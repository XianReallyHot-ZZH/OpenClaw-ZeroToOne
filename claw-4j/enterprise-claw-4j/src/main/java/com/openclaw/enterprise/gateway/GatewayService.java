package com.openclaw.enterprise.gateway;

import com.openclaw.enterprise.agent.AgentLoop;
import com.openclaw.enterprise.agent.AgentTurnResult;
import com.openclaw.enterprise.channel.InboundMessage;
import com.openclaw.enterprise.session.SessionMeta;
import com.openclaw.enterprise.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 网关核心服务 — 封装消息路由和 Agent 执行的共享逻辑
 *
 * <p>抽取自 {@link GatewayController} 和 {@link GatewayWebSocketHandler} 中
 * 重复的路由-会话-执行流程，统一维护。</p>
 *
 * <p>执行流程：</p>
 * <ol>
 *   <li>通过 {@link BindingTable} 解析路由绑定</li>
 *   <li>通过 {@link AgentManager} 构建会话键</li>
 *   <li>获取或创建会话</li>
 *   <li>调用 {@link AgentLoop#runTurn} 执行 Agent 对话循环</li>
 * </ol>
 */
@Service
public class GatewayService {

    private static final Logger log = LoggerFactory.getLogger(GatewayService.class);

    private final BindingTable bindingTable;
    private final AgentManager agentManager;
    private final SessionStore sessionStore;
    private final AgentLoop agentLoop;

    public GatewayService(BindingTable bindingTable, AgentManager agentManager,
                          SessionStore sessionStore, AgentLoop agentLoop) {
        this.bindingTable = bindingTable;
        this.agentManager = agentManager;
        this.sessionStore = sessionStore;
        this.agentLoop = agentLoop;
    }

    /**
     * 路由并执行消息 — 统一的路由-会话-执行流程
     *
     * @param text      消息文本
     * @param channel   渠道名称
     * @param accountId 账户 ID
     * @param peerId    对端 ID
     * @param guildId   群组 ID (可为 null)
     * @return 路由执行结果
     * @throws IllegalStateException 当没有匹配的路由绑定时
     */
    public RouteResult routeAndExecute(String text, String channel, String accountId,
                                       String peerId, String guildId) {
        // 1. 路由解析
        var resolved = bindingTable.resolve(channel, accountId, guildId, peerId)
            .orElseThrow(() -> new IllegalStateException(
                "No binding found for channel=" + channel
                    + " account=" + accountId + " peer=" + peerId));

        String agentId = resolved.agentId();

        // 2. 构建 InboundMessage
        InboundMessage msg = new InboundMessage(text, peerId, channel,
            accountId, peerId, guildId, guildId != null,
            List.of(), null, Instant.now());

        // 3. 构建 session key
        String sessionKey = agentManager.buildSessionKey(agentId, msg);

        // 4. 获取或创建会话
        String sessionId = sessionStore.getSessionMeta(sessionKey)
            .map(SessionMeta::sessionId)
            .orElseGet(() -> sessionStore.createSession(agentId, sessionKey));

        // 5. 执行 Agent 循环
        AgentTurnResult result = agentLoop.runTurn(agentId, sessionId, text);

        return new RouteResult(agentId, sessionId, result);
    }

    /**
     * 尝试路由并执行 — 绑定不存在时返回 empty 而非抛异常
     *
     * @return 路由执行结果，无绑定时返回 {@link Optional#empty()}
     */
    public Optional<RouteResult> tryRouteAndExecute(String text, String channel,
                                                     String accountId, String peerId,
                                                     String guildId) {
        try {
            return Optional.of(routeAndExecute(text, channel, accountId, peerId, guildId));
        } catch (IllegalStateException e) {
            log.warn("Route resolution failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 路由执行结果
     *
     * @param agentId   解析到的 Agent ID
     * @param sessionId 会话 ID
     * @param result    Agent 执行结果
     */
    public record RouteResult(String agentId, String sessionId, AgentTurnResult result) {}
}
