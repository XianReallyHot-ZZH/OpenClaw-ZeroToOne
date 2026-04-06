package com.openclaw.enterprise.session;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.openclaw.enterprise.common.JsonUtils;
import com.openclaw.enterprise.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * 会话存储服务 — 管理会话的创建、加载、追加和索引
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>会话生命周期管理 (创建、查询、索引)</li>
 *   <li>JSONL 格式的转录事件持久化</li>
 *   <li>JSONL → Claude API {@link MessageParam} 的历史重建</li>
 * </ul>
 *
 * <p>文件系统布局：</p>
 * <pre>
 * workspace/.sessions/agents/{agentId}/
 *   sessions.json                # 会话索引 (JSON)
 *   sessions/
 *     {sessionId}.jsonl          # 会话转录 (逐行 JSON)
 * </pre>
 *
 * <p>并发安全：每个 Agent 一把 {@link ReentrantLock}，保证同一 Agent 下的
 * 会话写入串行化，不同 Agent 之间互不阻塞。</p>
 *
 * <p>claw0 参考: s03_sessions.py 的 SessionStore 类</p>
 */
@Service
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    /** 会话 ID 前缀 */
    private static final String SESSION_PREFIX = "sess_";

    /** 会话索引文件名 */
    private static final String INDEX_FILE = "sessions.json";

    /** 会话根目录 */
    private final Path sessionsDir;

    /** 会话索引: sessionId → SessionMeta */
    private final Map<String, SessionMeta> index = new ConcurrentHashMap<>();

    /** 会话 → Agent 映射: sessionId → agentId */
    private final Map<String, String> sessionAgentMap = new ConcurrentHashMap<>();

    /** Agent 级别的写入锁 */
    private final ConcurrentHashMap<String, ReentrantLock> agentLocks = new ConcurrentHashMap<>();

    /**
     * 构造会话存储服务
     *
     * @param workspaceProps 工作空间配置
     */
    public SessionStore(AppProperties.WorkspaceProperties workspaceProps) {
        this.sessionsDir = workspaceProps.path().resolve(".sessions/agents");
    }

    // ==================== 会话生命周期 ====================

    /**
     * 创建新会话
     *
     * <p>生成唯一会话 ID (格式: {@code sess_{uuid8}})，创建 JSONL 文件，
     * 更新内存索引并持久化索引文件。</p>
     *
     * @param agentId Agent ID
     * @param label   会话标签 (可为 null)
     * @return 新创建的会话 ID
     */
    public String createSession(String agentId, String label) {
        // 生成会话 ID: sess_ + UUID 前 8 位
        String sessionId = SESSION_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Instant now = Instant.now();
        SessionMeta meta = new SessionMeta(sessionId, agentId, label, now, now, 0);

        // 获取 Agent 级别锁
        ReentrantLock lock = agentLocks.computeIfAbsent(agentId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 创建 JSONL 文件
            Path jsonlPath = resolveJsonlPath(agentId, sessionId);
            Files.createDirectories(jsonlPath.getParent());
            Files.createFile(jsonlPath);

            // 更新内存索引
            index.put(sessionId, meta);
            sessionAgentMap.put(sessionId, agentId);

            // 持久化索引
            saveIndex(agentId);

            log.info("Session created: {} for agent: {}", sessionId, agentId);
            return sessionId;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create session: " + sessionId, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 加载会话历史 — 将 JSONL 转录重建为 Claude API MessageParam 列表
     *
     * <p>读取 JSONL 文件，解析每行为 {@link TranscriptEvent}，
     * 然后通过 {@link #rebuildHistory(List)} 转换为 Claude API 消息格式。</p>
     *
     * <p>关键: JSONL 中的 assistant 文本和 tool_use 是分开存储的事件，
     * 但 Claude API 要求合并到同一个 assistant MessageParam 中。
     * rebuildHistory 处理此合并逻辑。</p>
     *
     * @param sessionId 会话 ID
     * @return Claude API 消息列表，会话不存在时返回空列表
     */
    public List<MessageParam> loadSession(String sessionId) {
        String agentId = getAgentId(sessionId);
        if (agentId == null) {
            return List.of();
        }

        Path jsonlPath = resolveJsonlPath(agentId, sessionId);
        if (!Files.exists(jsonlPath)) {
            return List.of();
        }

        List<TranscriptEvent> events = JsonUtils.readJsonl(jsonlPath, TranscriptEvent.class);
        return rebuildHistory(events);
    }

    /**
     * 追加转录事件到会话文件
     *
     * <p>获取 Agent 级别锁，将事件序列化为 JSON 并追加到 JSONL 文件。
     * 同时更新会话元数据 (lastActive, messageCount)。</p>
     *
     * @param sessionId 会话 ID
     * @param event     转录事件
     */
    public void appendTranscript(String sessionId, TranscriptEvent event) {
        String agentId = getAgentId(sessionId);
        if (agentId == null) {
            log.warn("Cannot append: unknown session {}", sessionId);
            return;
        }

        ReentrantLock lock = agentLocks.computeIfAbsent(agentId, k -> new ReentrantLock());
        lock.lock();
        try {
            Path jsonlPath = resolveJsonlPath(agentId, sessionId);
            JsonUtils.appendJsonl(jsonlPath, event);

            // 更新元数据
            updateSessionMeta(sessionId);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 列出指定 Agent 的所有会话
     *
     * @param agentId Agent ID
     * @return 会话元数据列表
     */
    public List<SessionMeta> listSessions(String agentId) {
        return index.values().stream()
            .filter(m -> m.agentId().equals(agentId))
            .sorted(Comparator.comparing(SessionMeta::lastActive).reversed())
            .toList();
    }

    /**
     * 获取会话元数据
     *
     * @param sessionId 会话 ID
     * @return 会话元数据 (可能为空)
     */
    public Optional<SessionMeta> getSessionMeta(String sessionId) {
        return Optional.ofNullable(index.get(sessionId));
    }

    // ==================== 历史重建 ====================

    /**
     * 将 JSONL 转录事件列表重建为 Claude API MessageParam 列表
     *
     * <p>这是会话持久化中最关键的方法。JSONL 格式将 assistant 文本和 tool_use
     * 存储为独立事件，但 Claude API 要求同一个 assistant 消息中同时包含
     * 文本和工具调用块。重建逻辑：</p>
     *
     * <pre>
     * JSONL events:  user → assistant → tool_use → tool_result → assistant
     * API messages:  user → assistant[text+tool_use] → user[tool_result] → assistant
     * </pre>
     *
     * @param events JSONL 转录事件列表
     * @return Claude API MessageParam 列表
     */
    List<MessageParam> rebuildHistory(List<TranscriptEvent> events) {
        List<MessageParam> messages = new ArrayList<>();
        List<ContentBlockParam> currentAssistantBlocks = null;

        for (TranscriptEvent event : events) {
            switch (event.type()) {
                case "user" -> {
                    // 先 flush 之前的 assistant 消息
                    currentAssistantBlocks = flushAssistantMessage(messages, currentAssistantBlocks);
                    // 创建 user 消息
                    String text = event.content() != null ? event.content().toString() : "";
                    messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content(text)
                        .build());
                }
                case "assistant" -> {
                    // 先 flush 之前的 assistant 消息
                    currentAssistantBlocks = flushAssistantMessage(messages, currentAssistantBlocks);
                    // 开始新的 assistant 消息块收集
                    currentAssistantBlocks = new ArrayList<>();
                    if (event.content() != null && !event.content().toString().isEmpty()) {
                        currentAssistantBlocks.add(
                            ContentBlockParam.ofText(
                                TextBlockParam.builder()
                                    .text(event.content().toString())
                                    .build()
                            )
                        );
                    }
                }
                case "tool_use" -> {
                    // tool_use 合并到当前的 assistant 消息中
                    if (currentAssistantBlocks == null) {
                        currentAssistantBlocks = new ArrayList<>();
                    }
                    // 构建 ToolUseBlockParam
                    var toolUseBuilder = ToolUseBlockParam.builder()
                        .id(event.toolId())
                        .name(event.toolName());

                    // input 必须存在 — 通过 JsonValue 传递
                    if (event.input() != null) {
                        toolUseBuilder.input(JsonValue.from(event.input()));
                    } else {
                        // input 不能为 null，使用空 Map
                        toolUseBuilder.input(JsonValue.from(Map.of()));
                    }

                    currentAssistantBlocks.add(
                        ContentBlockParam.ofToolUse(toolUseBuilder.build())
                    );
                }
                case "tool_result" -> {
                    // 先 flush 之前的 assistant 消息
                    currentAssistantBlocks = flushAssistantMessage(messages, currentAssistantBlocks);
                    // 创建包含 tool_result 的 user 消息
                    String resultText = event.content() != null ? event.content().toString() : "";
                    messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(List.of(
                            ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                    .toolUseId(event.toolId())
                                    .content(resultText)
                                    .build()
                            )
                        ))
                        .build());
                }
                default -> log.warn("Unknown transcript event type: {}", event.type());
            }
        }

        // flush 最后的 assistant 消息
        flushAssistantMessage(messages, currentAssistantBlocks);

        return messages;
    }

    /**
     * flush 当前收集的 assistant 内容块为一个 MessageParam
     *
     * <p>如果 currentAssistantBlocks 不为空，构建一个 ASSISTANT MessageParam
     * 并追加到 messages 列表中。返回 null 表示当前没有未 flush 的 assistant 消息。</p>
     *
     * @param messages              消息列表 (会被追加)
     * @param currentAssistantBlocks 当前收集的 assistant 内容块
     * @return null (表示没有未 flush 的 assistant 消息)
     */
    private List<ContentBlockParam> flushAssistantMessage(
            List<MessageParam> messages,
            List<ContentBlockParam> currentAssistantBlocks) {
        if (currentAssistantBlocks != null && !currentAssistantBlocks.isEmpty()) {
            messages.add(MessageParam.builder()
                .role(MessageParam.Role.ASSISTANT)
                .contentOfBlockParams(currentAssistantBlocks)
                .build());
        }
        return null;
    }

    // ==================== 索引管理 ====================

    /**
     * 加载指定 Agent 的会话索引
     *
     * <p>从 sessions.json 文件加载索引到内存。
     * 如果文件不存在，初始化为空索引。</p>
     *
     * @param agentId Agent ID
     */
    public void loadIndex(String agentId) {
        Path indexFile = getAgentDir(agentId).resolve(INDEX_FILE);
        if (!Files.exists(indexFile)) {
            return;
        }

        try {
            String json = Files.readString(indexFile);
            List<SessionMeta> metas = JsonUtils.fromJson(json,
                new TypeReference<List<SessionMeta>>() {});

            for (SessionMeta meta : metas) {
                index.put(meta.sessionId(), meta);
                sessionAgentMap.put(meta.sessionId(), meta.agentId());
            }
            log.info("Loaded {} sessions for agent: {}", metas.size(), agentId);
        } catch (Exception e) {
            log.error("Failed to load session index for agent: {}", agentId, e);
        }
    }

    /**
     * 持久化指定 Agent 的会话索引
     *
     * @param agentId Agent ID
     */
    private void saveIndex(String agentId) {
        Path indexFile = getAgentDir(agentId).resolve(INDEX_FILE);
        try {
            List<SessionMeta> agentSessions = index.values().stream()
                .filter(m -> m.agentId().equals(agentId))
                .toList();
            Files.createDirectories(indexFile.getParent());
            Files.writeString(indexFile, JsonUtils.toJson(agentSessions));
        } catch (IOException e) {
            log.error("Failed to save session index for agent: {}", agentId, e);
        }
    }

    /**
     * 更新会话元数据 — 递增 messageCount 并更新 lastActive
     *
     * @param sessionId 会话 ID
     */
    private void updateSessionMeta(String sessionId) {
        SessionMeta current = index.get(sessionId);
        if (current != null) {
            SessionMeta updated = new SessionMeta(
                current.sessionId(),
                current.agentId(),
                current.label(),
                current.createdAt(),
                Instant.now(),
                current.messageCount() + 1
            );
            index.put(sessionId, updated);
            // 异步保存索引 (不阻塞当前操作)
            String agentId = current.agentId();
            saveIndex(agentId);
        }
    }

    // ==================== 路径解析 ====================

    /**
     * 获取 Agent 的会话根目录
     *
     * @param agentId Agent ID
     * @return Agent 会话目录路径
     */
    private Path getAgentDir(String agentId) {
        return sessionsDir.resolve(agentId);
    }

    /**
     * 解析 JSONL 文件路径
     *
     * @param agentId   Agent ID
     * @param sessionId 会话 ID
     * @return JSONL 文件路径
     */
    private Path resolveJsonlPath(String agentId, String sessionId) {
        return getAgentDir(agentId).resolve("sessions").resolve(sessionId + ".jsonl");
    }

    /**
     * 通过 sessionId 查找 agentId
     *
     * @param sessionId 会话 ID
     * @return Agent ID，未找到返回 null
     */
    private String getAgentId(String sessionId) {
        return sessionAgentMap.get(sessionId);
    }
}
