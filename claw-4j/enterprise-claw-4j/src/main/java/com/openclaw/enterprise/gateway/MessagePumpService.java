package com.openclaw.enterprise.gateway;

import com.openclaw.enterprise.agent.AgentLoop;
import com.openclaw.enterprise.channel.ChannelManager;
import com.openclaw.enterprise.channel.InboundMessage;
import com.openclaw.enterprise.session.SessionStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * 消息泵服务 — 轮询所有渠道并将消息路由到 Agent
 *
 * <p>启动一个虚拟线程，持续轮询所有已注册渠道的 {@code receive()} 方法。
 * 收到消息后：</p>
 * <ol>
 *   <li>通过 {@link BindingTable} 解析路由</li>
 *   <li>通过 {@link AgentManager} 构建会话键</li>
 *   <li>获取或创建会话</li>
 *   <li>调用 {@link AgentLoop#runTurn} 执行对话</li>
 *   <li>将回复发送回对应渠道</li>
 * </ol>
 *
 * <p>如果没有匹配的路由绑定，记录警告日志并跳过 (不崩溃)。</p>
 *
 * <p>claw0 参考: s05_gateway_routing.py 中消息接收和路由逻辑</p>
 */
@Service
public class MessagePumpService {

    private static final Logger log = LoggerFactory.getLogger(MessagePumpService.class);

    /** 无消息时的轮询间隔 (毫秒) */
    private static final long POLL_INTERVAL_MS = 100;

    private final ChannelManager channelManager;
    private final BindingTable bindingTable;
    private final AgentManager agentManager;
    private final SessionStore sessionStore;
    private final AgentLoop agentLoop;

    private volatile boolean running = true;

    /**
     * 构造消息泵服务
     *
     * @param channelManager 渠道管理器
     * @param bindingTable   路由表
     * @param agentManager   Agent 管理器
     * @param sessionStore   会话存储
     * @param agentLoop      Agent 循环
     */
    public MessagePumpService(ChannelManager channelManager,
                              BindingTable bindingTable,
                              AgentManager agentManager,
                              SessionStore sessionStore,
                              AgentLoop agentLoop) {
        this.channelManager = channelManager;
        this.bindingTable = bindingTable;
        this.agentManager = agentManager;
        this.sessionStore = sessionStore;
        this.agentLoop = agentLoop;
    }

    /**
     * 启动消息泵 — 应用启动后自动开始
     */
    @PostConstruct
    void startPumping() {
        Thread.ofVirtual()
            .name("message-pump")
            .start(() -> {
                log.info("Message pump started");
                while (running && !Thread.currentThread().isInterrupted()) {
                    try {
                        boolean foundAny = false;

                        // 遍历所有渠道，接收消息
                        for (var channel : channelManager.getAll()) {
                            var msg = channel.receive();
                            if (msg.isPresent()) {
                                foundAny = true;
                                routeMessage(msg.get(), channel.getName());
                            }
                        }

                        // 无消息时短暂休眠，避免 busy-waiting
                        if (!foundAny) {
                            Thread.sleep(POLL_INTERVAL_MS);
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        log.error("Message pump error", e);
                    }
                }
                log.info("Message pump stopped");
            });
    }

    /**
     * 路由消息 — 解析绑定 → 构建会话键 → 执行 Agent 循环
     *
     * @param msg         入站消息
     * @param channelName 渠道名称
     */
    public void routeMessage(InboundMessage msg, String channelName) {
        try {
            // 1. 路由解析
            var resolved = bindingTable.resolve(
                msg.channel(), msg.accountId(), msg.guildId(), msg.peerId());

            if (resolved.isEmpty()) {
                log.warn("No binding for message from channel={} peer={}",
                    msg.channel(), msg.peerId());
                return;
            }

            String agentId = resolved.get().agentId();
            log.debug("Routing message from {} to agent {}", msg.peerId(), agentId);

            // 2. 构建会话键
            String sessionKey = agentManager.buildSessionKey(agentId, msg);

            // 3. 获取或创建会话
            String sessionId = sessionStore.getSessionMeta(sessionKey)
                .map(com.openclaw.enterprise.session.SessionMeta::sessionId)
                .orElseGet(() -> sessionStore.createSession(agentId, sessionKey));

            // 4. 执行 Agent 循环
            var result = agentLoop.runTurn(agentId, sessionId, msg.text());

            // 5. 发送回复到渠道
            if (result.text() != null && !result.text().isEmpty()) {
                channelManager.get(msg.channel()).ifPresent(ch -> {
                    // 使用 peerId 或 guildId 作为发送目标
                    String target = msg.isGroup() ? msg.guildId() : msg.peerId();
                    ch.send(target, result.text());
                });
            }

        } catch (Exception e) {
            log.error("Failed to route message from {}: {}", msg.peerId(), e.getMessage(), e);
        }
    }

    /**
     * 停止消息泵
     */
    public void stop() {
        running = false;
        log.info("Message pump stopping");
    }
}
