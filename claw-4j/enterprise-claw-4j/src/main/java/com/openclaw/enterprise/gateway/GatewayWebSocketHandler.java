package com.openclaw.enterprise.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.enterprise.agent.AgentConfig;
import com.openclaw.enterprise.agent.DmScope;
import com.openclaw.enterprise.common.JsonUtils;
import com.openclaw.enterprise.common.exceptions.JsonRpcException;
import com.openclaw.enterprise.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 网关处理器 — JSON-RPC 2.0 协议的消息路由中心
 *
 * <p>处理 WebSocket 连接上的 JSON-RPC 2.0 请求，支持以下方法：</p>
 * <ul>
 *   <li>{@code send} — 发送消息到 Agent</li>
 *   <li>{@code bindings.set / bindings.list / bindings.remove} — 路由绑定管理</li>
 *   <li>{@code agents.list / agents.register} — Agent 管理</li>
 *   <li>{@code sessions.list} — 会话查询</li>
 *   <li>{@code status} — 网关状态查询</li>
 * </ul>
 *
 * <p>同时支持向所有连接的客户端广播通知 (typing, heartbeat.output, cron.output 等)。</p>
 *
 * <p>claw0 参考: s05_gateway_routing.py 第 359-466 行 GatewayServer</p>
 */
@Component
public class GatewayWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocketHandler.class);

    /** 已连接的 WebSocket 会话集合 */
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    private final ObjectMapper objectMapper = JsonUtils.mapper();

    /** 通过构造器注入的依赖 */
    private final BindingTable bindingTable;
    private final BindingStore bindingStore;
    private final AgentManager agentManager;
    private final AgentStore agentStore;
    private final SessionStore sessionStore;
    private final GatewayService gatewayService;

    /**
     * 构造器注入 — Spring 自动装配所有依赖
     */
    public GatewayWebSocketHandler(BindingTable bindingTable, BindingStore bindingStore,
                                   AgentManager agentManager, AgentStore agentStore,
                                   SessionStore sessionStore, GatewayService gatewayService) {
        this.bindingTable = bindingTable;
        this.bindingStore = bindingStore;
        this.agentManager = agentManager;
        this.agentStore = agentStore;
        this.sessionStore = sessionStore;
        this.gatewayService = gatewayService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket disconnected: {} ({})", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode request = objectMapper.readTree(message.getPayload());

            String id = request.has("id") ? request.get("id").asText() : null;
            String method = request.has("method") ? request.get("method").asText() : "";
            JsonNode params = request.get("params");

            Object result = dispatch(method, params);
            sendResponse(session, id, result);

        } catch (JsonRpcException e) {
            sendError(session, extractId(message.getPayload()), e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("Failed to handle WebSocket message", e);
            sendError(session, null, -32603, "Internal error: " + e.getMessage());
        }
    }

    /**
     * JSON-RPC 方法分发
     */
    private Object dispatch(String method, JsonNode params) {
        return switch (method) {
            case "send" -> handleSend(params);
            case "bindings.set" -> handleBindSet(params);
            case "bindings.list" -> bindingTable.listBindings();
            case "bindings.remove" -> handleBindRemove(params);
            case "agents.list" -> agentManager.listAgents();
            case "agents.register" -> handleAgentRegister(params);
            case "sessions.list" -> handleSessionsList(params);
            case "status" -> handleStatus();
            default -> throw new JsonRpcException(-32601, "Method not found: " + method);
        };
    }

    // ==================== 方法处理器 ====================

    /**
     * 处理 send 方法 — 路由消息到 Agent 并返回回复
     */
    private Object handleSend(JsonNode params) {
        String text = params.get("text").asText();
        String channel = params.has("channel") ? params.get("channel").asText() : "cli";
        String accountId = params.has("account_id") ? params.get("account_id").asText() : "cli";
        String peerId = params.has("peer_id") ? params.get("peer_id").asText() : "user";
        String guildId = params.has("guild_id") ? params.get("guild_id").asText() : null;

        try {
            var routeResult = gatewayService.routeAndExecute(text, channel, accountId, peerId, guildId);
            var result = routeResult.result();

            return Map.of("status", "ok", "text", result.text(),
                "agent_id", routeResult.agentId(), "session_id", routeResult.sessionId());
        } catch (IllegalStateException e) {
            throw new JsonRpcException(-32603, e.getMessage());
        }
    }

    private Object handleBindSet(JsonNode params) {
        Binding binding = new Binding(
            params.get("tier").asInt(),
            params.get("key").asText(),
            params.get("agent_id").asText(),
            params.has("priority") ? params.get("priority").asInt() : 0,
            null
        );
        bindingStore.addAndPersist(binding);
        return Map.of("status", "ok");
    }

    private Object handleBindRemove(JsonNode params) {
        boolean removed = bindingStore.removeAndPersist(
            params.get("tier").asInt(),
            params.get("key").asText());
        return Map.of("status", removed ? "ok" : "not_found");
    }

    private Object handleAgentRegister(JsonNode params) {
        AgentConfig config = new AgentConfig(
            params.get("id").asText(),
            params.get("name").asText(),
            params.has("personality") ? params.get("personality").asText() : "",
            params.has("model") ? params.get("model").asText() : "",
            params.has("dm_scope")
                ? DmScope.valueOf(params.get("dm_scope").asText())
                : DmScope.PER_PEER
        );
        agentStore.registerAndPersist(config);
        return Map.of("status", "ok");
    }

    private Object handleSessionsList(JsonNode params) {
        String agentId = params != null && params.has("agent_id")
            ? params.get("agent_id").asText() : null;
        if (agentId != null) {
            return sessionStore.listSessions(agentId);
        }
        return sessionStore.listSessions("");
    }

    private Object handleStatus() {
        return Map.of(
            "status", "running",
            "agents", agentManager.listAgents().size(),
            "bindings", bindingTable.listBindings().size(),
            "ws_connections", sessions.size()
        );
    }

    // ==================== 广播通知 ====================

    /**
     * 向所有连接的客户端发送 JSON-RPC 通知
     *
     * @param method 通知方法名
     * @param params 通知参数
     */
    public void broadcast(String method, Object params) {
        String notification = JsonUtils.toJson(Map.of(
            "jsonrpc", "2.0",
            "method", method,
            "params", params
        ));

        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(notification));
                } catch (IOException e) {
                    log.warn("Failed to broadcast to session {}", session.getId());
                }
            }
        }
    }

    /**
     * 广播 Agent 正在输入通知
     */
    public void broadcastTyping(String agentId) {
        broadcast("typing", Map.of("agent_id", agentId));
    }

    /**
     * 广播 Agent 输入停止通知
     */
    public void broadcastTypingStop(String agentId) {
        broadcast("typing.stop", Map.of("agent_id", agentId));
    }

    // ==================== JSON-RPC 响应 ====================

    private void sendResponse(WebSocketSession session, String id, Object result) {
        try {
            String response = JsonUtils.toJson(Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "result", result
            ));
            session.sendMessage(new TextMessage(response));
        } catch (IOException e) {
            log.warn("Failed to send response to session {}", session.getId());
        }
    }

    private void sendError(WebSocketSession session, String id, int code, String message) {
        try {
            String response = JsonUtils.toJson(Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "error", Map.of("code", code, "message", message)
            ));
            session.sendMessage(new TextMessage(response));
        } catch (IOException e) {
            log.warn("Failed to send error to session {}", session.getId());
        }
    }

    private String extractId(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.has("id") ? node.get("id").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
