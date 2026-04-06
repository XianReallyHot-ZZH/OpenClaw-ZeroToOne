package com.openclaw.enterprise.gateway;

import com.openclaw.enterprise.agent.AgentConfig;
import com.openclaw.enterprise.agent.AgentLoop;
import com.openclaw.enterprise.channel.InboundMessage;
import com.openclaw.enterprise.session.SessionStore;
import com.openclaw.enterprise.session.SessionMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 网关 REST 控制器 — 提供 HTTP API 接口
 *
 * <p>所有端点以 {@code /api/v1} 为前缀，提供与 WebSocket 网关相同的功能。</p>
 *
 * <p>端点列表：</p>
 * <ul>
 *   <li>{@code GET /api/v1/agents} — 列出所有 Agent</li>
 *   <li>{@code GET /api/v1/agents/{id}} — 获取单个 Agent</li>
 *   <li>{@code POST /api/v1/agents} — 注册 Agent</li>
 *   <li>{@code DELETE /api/v1/agents/{id}} — 注销 Agent</li>
 *   <li>{@code GET /api/v1/bindings} — 列出所有绑定</li>
 *   <li>{@code POST /api/v1/bindings} — 添加绑定</li>
 *   <li>{@code DELETE /api/v1/bindings/{tier}/{key}} — 删除绑定</li>
 *   <li>{@code GET /api/v1/sessions} — 列出会话</li>
 *   <li>{@code POST /api/v1/sessions} — 创建会话</li>
 *   <li>{@code POST /api/v1/send} — 发送消息 (同步)</li>
 *   <li>{@code GET /api/v1/status} — 网关状态</li>
 * </ul>
 *
 * <p>claw0 参考: s05_gateway_routing.py 中 REST API 端点</p>
 */
@RestController
@RequestMapping("/api/v1")
public class GatewayController {

    private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

    private final AgentManager agentManager;
    private final AgentStore agentStore;
    private final BindingTable bindingTable;
    private final BindingStore bindingStore;
    private final SessionStore sessionStore;
    private final AgentLoop agentLoop;

    public GatewayController(AgentManager agentManager, AgentStore agentStore,
                             BindingTable bindingTable, BindingStore bindingStore,
                             SessionStore sessionStore, AgentLoop agentLoop) {
        this.agentManager = agentManager;
        this.agentStore = agentStore;
        this.bindingTable = bindingTable;
        this.bindingStore = bindingStore;
        this.sessionStore = sessionStore;
        this.agentLoop = agentLoop;
    }

    // ==================== Agent 管理 ====================

    @GetMapping("/agents")
    public List<AgentConfig> listAgents() {
        return agentManager.listAgents();
    }

    @GetMapping("/agents/{id}")
    public ResponseEntity<AgentConfig> getAgent(@PathVariable String id) {
        return agentManager.getAgent(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/agents")
    public ResponseEntity<Map<String, String>> registerAgent(@RequestBody AgentConfig config) {
        agentStore.registerAndPersist(config);
        return ResponseEntity.ok(Map.of("status", "ok", "agent_id", config.id()));
    }

    @DeleteMapping("/agents/{id}")
    public ResponseEntity<Map<String, String>> unregisterAgent(@PathVariable String id) {
        boolean removed = agentStore.unregisterAndPersist(id);
        return removed
            ? ResponseEntity.ok(Map.of("status", "ok"))
            : ResponseEntity.notFound().build();
    }

    // ==================== 绑定管理 ====================

    @GetMapping("/bindings")
    public List<com.openclaw.enterprise.gateway.Binding> listBindings() {
        return bindingTable.listBindings();
    }

    @PostMapping("/bindings")
    public ResponseEntity<Map<String, String>> addBinding(@RequestBody Binding binding) {
        bindingStore.addAndPersist(binding);
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @DeleteMapping("/bindings/{tier}/{key}")
    public ResponseEntity<Map<String, String>> removeBinding(
            @PathVariable int tier, @PathVariable String key) {
        boolean removed = bindingStore.removeAndPersist(tier, key);
        return removed
            ? ResponseEntity.ok(Map.of("status", "ok"))
            : ResponseEntity.ok(Map.of("status", "not_found"));
    }

    // ==================== 会话管理 ====================

    @GetMapping("/sessions")
    public List<SessionMeta> listSessions(
            @RequestParam(value = "agent_id", required = false) String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            return sessionStore.listSessions(agentId);
        }
        return List.of();
    }

    @PostMapping("/sessions")
    public ResponseEntity<Map<String, String>> createSession(
            @RequestBody Map<String, String> body) {
        String agentId = body.get("agent_id");
        String label = body.get("label");
        String sessionId = sessionStore.createSession(agentId, label);
        return ResponseEntity.ok(Map.of("status", "ok", "session_id", sessionId));
    }

    @GetMapping("/sessions/{id}/history")
    public ResponseEntity<List<Object>> getSessionHistory(@PathVariable String id) {
        // 返回简化的历史记录
        var messages = sessionStore.loadSession(id);
        return ResponseEntity.ok(List.of(messages.toString()));
    }

    // ==================== 消息发送 ====================

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> sendMessage(@RequestBody Map<String, String> body) {
        String text = body.get("text");
        String channel = body.getOrDefault("channel", "cli");
        String accountId = body.getOrDefault("account_id", "cli");
        String peerId = body.getOrDefault("peer_id", "user");
        String guildId = body.get("guild_id");

        // 路由解析
        var resolved = bindingTable.resolve(channel, accountId, guildId, peerId)
            .orElse(null);

        if (resolved == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", Map.of("code", "NO_BINDING",
                    "message", "No binding found for the given routing info")));
        }

        String agentId = resolved.agentId();

        // 构建会话键
        InboundMessage msg = new InboundMessage(text, peerId, channel,
            accountId, peerId, guildId, guildId != null,
            List.of(), null, Instant.now());
        String sessionKey = agentManager.buildSessionKey(agentId, msg);

        // 获取或创建会话
        String sessionId = sessionStore.getSessionMeta(sessionKey)
            .map(SessionMeta::sessionId)
            .orElseGet(() -> sessionStore.createSession(agentId, sessionKey));

        // 执行
        var result = agentLoop.runTurn(agentId, sessionId, text);

        return ResponseEntity.ok(Map.of(
            "status", "ok",
            "text", result.text(),
            "agent_id", agentId,
            "session_id", sessionId,
            "stop_reason", result.stopReason()
        ));
    }

    // ==================== 状态查询 ====================

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        return Map.of(
            "status", "running",
            "agents", agentManager.listAgents().size(),
            "bindings", bindingTable.listBindings().size()
        );
    }
}
