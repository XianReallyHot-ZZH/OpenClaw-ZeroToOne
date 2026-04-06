package com.openclaw.enterprise.scheduler;

import com.openclaw.enterprise.agent.AgentLoop;
import com.openclaw.enterprise.agent.AgentTurnResult;
import com.openclaw.enterprise.config.AppProperties;
import com.openclaw.enterprise.delivery.DeliveryQueue;
import com.openclaw.enterprise.gateway.GatewayWebSocketHandler;
import com.openclaw.enterprise.intelligence.BootstrapLoader;
import com.openclaw.enterprise.intelligence.PromptAssembler;
import com.openclaw.enterprise.intelligence.PromptContext;
import com.openclaw.enterprise.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

/**
 * 心跳服务 — 定期触发 Agent 心跳检查，主动推送 Agent 的自发性输出
 *
 * <p>心跳流程 (4 个前置检查 + 执行)：</p>
 * <ol>
 *   <li>检查 HEARTBEAT.md 文件是否存在</li>
 *   <li>检查 HEARTBEAT.md 内容是否非空</li>
 *   <li>检查当前时间是否在活跃时段内</li>
 *   <li>执行心跳: 构建系统提示 + HEARTBEAT.md 指令，调用 Agent</li>
 *   <li>去重: 与上次输出相同则跳过投递</li>
 *   <li>特殊标记: 输出为 "HEARTBEAT_OK" 时不投递</li>
 * </ol>
 *
 * <p>claw0 参考: s07_heartbeat_cron.py 第 400-580 行 HeartbeatRunner</p>
 */
@Service
public class HeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatService.class);

    /** 心跳正常标记 — Agent 返回此文本时跳过投递 */
    private static final String HEARTBEAT_OK = "HEARTBEAT_OK";

    private final PromptAssembler promptAssembler;
    private final BootstrapLoader bootstrapLoader;
    private final AgentLoop agentLoop;
    private final SessionStore sessionStore;
    private final DeliveryQueue deliveryQueue;
    private final GatewayWebSocketHandler wsHandler;
    private final Path workspacePath;
    private final int activeStartHour;
    private final int activeEndHour;
    private final String defaultAgent;

    /** 上次心跳输出，用于去重 */
    private volatile String lastOutput = null;

    public HeartbeatService(PromptAssembler promptAssembler,
                            BootstrapLoader bootstrapLoader,
                            AgentLoop agentLoop,
                            SessionStore sessionStore,
                            DeliveryQueue deliveryQueue,
                            GatewayWebSocketHandler wsHandler,
                            AppProperties.WorkspaceProperties workspaceProps,
                            AppProperties.HeartbeatProperties heartbeatProps,
                            AppProperties.GatewayProperties gatewayProps) {
        this.promptAssembler = promptAssembler;
        this.bootstrapLoader = bootstrapLoader;
        this.agentLoop = agentLoop;
        this.sessionStore = sessionStore;
        this.deliveryQueue = deliveryQueue;
        this.wsHandler = wsHandler;
        this.workspacePath = workspaceProps.path();
        this.activeStartHour = heartbeatProps.activeStartHour();
        this.activeEndHour = heartbeatProps.activeEndHour();
        this.defaultAgent = gatewayProps.defaultAgent();
    }

    /**
     * 心跳定时触发 — 间隔由 heartbeat.interval-seconds 配置决定
     *
     * <p>fixedRateString 支持 Spring 属性占位符，默认 1800 秒 (30 分钟)。</p>
     */
    @Scheduled(fixedRateString = "#{${heartbeat.interval-seconds:1800} * 1000}")
    public void heartbeat() {
        // 前置检查 1: HEARTBEAT.md 是否存在
        Path heartbeatFile = workspacePath.resolve("HEARTBEAT.md");
        if (!Files.exists(heartbeatFile)) {
            log.debug("HEARTBEAT.md not found, skipping heartbeat");
            return;
        }

        // 前置检查 2: HEARTBEAT.md 内容是否非空
        var heartbeatContent = bootstrapLoader.getFile("HEARTBEAT.md");
        if (heartbeatContent.isEmpty() || heartbeatContent.get().isBlank()) {
            log.debug("HEARTBEAT.md is empty, skipping heartbeat");
            return;
        }

        // 前置检查 3: 是否在活跃时段内
        if (!isWithinActiveHours()) {
            log.debug("Outside active hours ({}-{}), skipping heartbeat",
                activeStartHour, activeEndHour);
            return;
        }

        // 执行心跳
        runHeartbeat(heartbeatContent.get());
    }

    /**
     * 检查当前时间是否在活跃时段内
     */
    private boolean isWithinActiveHours() {
        int currentHour = Instant.now().atZone(ZoneId.systemDefault()).getHour();
        return currentHour >= activeStartHour && currentHour < activeEndHour;
    }

    /**
     * 执行心跳 — 构建 Agent 调用并处理结果
     */
    private void runHeartbeat(String heartbeatContent) {
        try {
            String agentId = defaultAgent != null ? defaultAgent : "default";

            // 构建系统提示 (isHeartbeat=true)
            PromptContext ctx = new PromptContext("heartbeat", false, true, heartbeatContent);

            // 构建会话: 使用固定 key 复用心跳会话
            String sessionKey = "heartbeat_" + agentId;
            String sessionId = sessionStore.getSessionMeta(sessionKey)
                .map(sm -> sm.sessionId())
                .orElseGet(() -> sessionStore.createSession(agentId, sessionKey));

            // 调用 Agent
            AgentTurnResult result = agentLoop.runTurn(agentId, sessionId, heartbeatContent);
            String output = result.text();

            if (output == null || output.isBlank()) {
                log.debug("Heartbeat returned empty output");
                return;
            }

            // 去重: 与上次输出相同则跳过
            if (output.equals(lastOutput)) {
                log.debug("Heartbeat output unchanged, skipping delivery");
                return;
            }

            // 特殊标记: HEARTBEAT_OK 不投递
            if (output.trim().equals(HEARTBEAT_OK)) {
                log.debug("Heartbeat OK, no delivery needed");
                lastOutput = HEARTBEAT_OK;
                return;
            }

            // 更新去重缓存
            lastOutput = output;

            // 入队投递
            deliveryQueue.enqueue("cli", "heartbeat", output);

            // WebSocket 通知
            wsHandler.broadcast("heartbeat.output", Map.of(
                "agent_id", agentId,
                "text", output
            ));

            log.info("Heartbeat output delivered: {} chars", output.length());

        } catch (Exception e) {
            log.error("Heartbeat execution failed", e);
        }
    }
}
