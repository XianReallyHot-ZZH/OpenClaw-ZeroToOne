package com.claw0.sessions;

/**
 * Section 05: Gateway & Routing -- "Every message finds its home" / 每条消息都能找到归宿
 *
 * 网关与路由层 (Gateway & Routing):
 *   核心思想: 一条消息进来后, 需要回答两个问题: "谁来处理?" (agent_id) 和 "在哪个上下文中处理?" (session_key).
 *   网关是消息的集散中心, 路由系统通过 5 级绑定表从最具体到最一般逐级匹配, 找到最合适的 agent.
 *
 *     Inbound message (channel, account_id, peer_id, text)
 *            |
 *     +------v------+     +----------+
 *     |   Gateway    | <-- | WS/REPL  |  JSON-RPC 2.0
 *     +------+------+
 *            |
 *     +------v------+
 *     |   Routing    |  5 tiers: peer > guild > account > channel > default
 *     +------+------+
 *            |
 *      (agent_id, session_key)
 *            |
 *     +------v------+
 *     | AgentManager |  per-agent config / workspace / sessions
 *     +------+------+
 *            |
 *         LLM API
 *
 * [5 级路由详解]
 *   Tier 1 - peer_id:     特定用户 -> 特定 agent (如管理员总是路由到 Sage)
 *   Tier 2 - guild_id:    特定服务器/群组 -> 特定 agent
 *   Tier 3 - account_id:  特定 bot 账号 -> 特定 agent
 *   Tier 4 - channel:     特定渠道类型 -> 特定 agent (如所有 Telegram 消息路由到 Sage)
 *   Tier 5 - default:     默认兜底 agent (没有匹配时使用)
 *   匹配规则: 从 Tier 1 开始, 第一个匹配的规则胜出 (first match wins).
 *
 * [架构要点] 为什么需要路由?
 *   - 多 agent 系统: 不同 agent 有不同的性格、能力和职责
 *   - 灵活分配: 管理员可能需要严肃的 Sage, 普通用户更喜欢温暖的 Luna
 *   - 渐进式复杂度: 从单 agent 到多 agent, 只需添加绑定规则, 不改 agent 代码
 *
 * [教学要点] 本节引入的关键概念:
 *   - 绑定表 (BindingTable): 路由规则的存储和匹配引擎
 *   - dm_scope: 会话隔离粒度控制 -- main / per-peer / per-channel-peer / per-account-channel-peer
 *   - AgentManager: 每个 agent 拥有独立的配置、工作区和会话存储
 *   - WebSocket 网关: 支持 JSON-RPC 2.0 协议的实时通信
 *   - Semaphore: 限制同时运行的最大 agent 数量, 防止 API 过载
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S05GatewayRouting"
 */

// region Common Imports
import com.claw0.common.AnsiColors;
import com.claw0.common.Config;
import com.claw0.common.JsonUtils;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.core.JsonValue;
// endregion

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

public class S05GatewayRouting {

    // region Configuration -- 全局配置常量
    // [教学笔记] 本节在 S04 的基础上增加了多 agent 相关的配置:
    //   AGENTS_DIR 是每个 agent 独立工作区的根目录, 每个 agent 有自己的 sessions/ 子目录.
    //   VALID_ID 正则确保 agent ID 格式统一, 避免文件系统路径注入等问题.

    /** 模型 ID: 默认使用 Claude Sonnet, 通过环境变量 MODEL_ID 可覆盖 */
    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");

    /** 工具输出最大长度: 截断过长的工具返回值, 防止 LLM 上下文溢出 */
    static final int MAX_TOOL_OUTPUT = 30_000;

    /** 工作目录: 项目根目录 */
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));

    /** 工作空间目录: 存放 agent 产生的文件 */
    static final Path WORKSPACE_DIR = WORKDIR.resolve("workspace");

    /** Agent 目录: 每个 agent 拥有独立的子目录, 存放各自的 sessions 和数据 */
    static final Path AGENTS_DIR = WORKSPACE_DIR.resolve(".agents");

    /** Anthropic API 客户端: 所有 agent 共享同一个客户端实例 (线程安全) */
    static final AnthropicClient client = AnthropicOkHttpClient.builder()
            .fromEnv()
            .build();

    /**
     * Agent ID 校验正则: 小写字母数字开头, 允许连字符和下划线, 最长 64 字符.
     * [为什么需要校验?] Agent ID 会被用作文件系统路径的一部分,
     * 如果包含特殊字符 (如 ../) 可能导致路径遍历漏洞.
     */
    static final Pattern VALID_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    /** 非法字符正则: 用于将用户输入的 agent ID 清洗为合法格式 */
    static final Pattern INVALID_CHARS = Pattern.compile("[^a-z0-9_-]+");

    /** 默认 Agent ID: 当路由未匹配到任何绑定时使用此 agent */
    static final String DEFAULT_AGENT_ID = "main";
    // endregion

    // ================================================================
    // Agent ID 规范化 (Agent ID Normalization)
    // ================================================================
    // [为什么需要规范化?] 用户可能输入各种格式的 agent ID: "My Agent", "agent-123", "AGENT_NAME" 等.
    // 规范化确保: 1) 统一为小写, 2) 替换非法字符为连字符, 3) 截断到 64 字符.
    // 这样无论用户怎么输入, 都能正确匹配到已注册的 agent.

    /**
     * 将用户输入的 agent ID 规范化为合法格式.
     * 规则: 小写化 -> 替换非法字符为连字符 -> 去除首尾连字符 -> 截断到 64 字符.
     * 如果清洗后为空, 返回默认 agent ID ("main").
     */
    static String normalizeAgentId(String value) {
        if (value == null || value.isBlank()) return DEFAULT_AGENT_ID;
        String trimmed = value.strip();
        if (VALID_ID.matcher(trimmed).matches()) return trimmed.toLowerCase();
        String cleaned = INVALID_CHARS.matcher(trimmed.toLowerCase()).replaceAll("-")
                .replaceAll("^-+|-+$", "");
        if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64);
        return cleaned.isBlank() ? DEFAULT_AGENT_ID : cleaned;
    }

    // ================================================================
    // 绑定规则 (Binding) -- 5 级路由解析的核心数据结构
    // ================================================================
    // [5 级路由详解 -- 从最具体到最一般]
    //   Tier 1: peer_id    -- 将特定用户路由到特定 agent (最具体, 优先级最高)
    //   Tier 2: guild_id   -- 服务器/群组级别路由 (如 Discord 的某个 server)
    //   Tier 3: account_id -- bot 账号级别路由 (同一渠道多个 bot 的情况)
    //   Tier 4: channel    -- 整个渠道类型路由 (如所有 Telegram 消息)
    //   Tier 5: default    -- 默认兜底路由 (没有任何匹配时使用)
    //
    // [匹配规则]
    //   - 同 tier 内按 priority 降序排列 (priority 越大越优先)
    //   - 不同 tier 之间, tier 数字越小越优先 (Tier 1 > Tier 5)
    //   - 遍历时第一个匹配的规则胜出 (first match wins)

    /**
     * 绑定记录: 一条路由规则.
     *
     * @param agentId    目标 agent ID -- 匹配到这条规则时, 消息将路由到这个 agent
     * @param tier       路由层级 (1-5), 数字越小越具体, 优先匹配
     * @param matchKey   匹配字段名 ("peer_id" / "guild_id" / "account_id" / "channel" / "default")
     * @param matchValue 匹配值 (如 peer_id 的具体值, 或 "*" 表示通配)
     * @param priority   同层级内的优先级, 数值越大越优先
     */
    record Binding(String agentId, int tier, String matchKey, String matchValue, int priority)
            implements Comparable<Binding> {

        /** 人类可读的绑定描述, 用于日志和调试 */
        String display() {
            String[] names = {"", "peer", "guild", "account", "channel", "default"};
            String label = tier >= 1 && tier <= 5 ? names[tier] : "tier-" + tier;
            return "[" + label + "] " + matchKey + "=" + matchValue
                    + " -> agent:" + agentId + " (pri=" + priority + ")";
        }

        /**
         * 排序比较: 先按 tier 升序 (1 最优先), 再按 priority 降序 (大的优先).
         * 这样遍历时按最优匹配顺序排列.
         */
        @Override
        public int compareTo(Binding o) {
            int cmp = Integer.compare(this.tier, o.tier);
            return cmp != 0 ? cmp : Integer.compare(o.priority, this.priority);
        }
    }

    /**
     * 绑定表 -- 路由规则的存储和匹配引擎.
     *
     * [为什么用排序的 List 而不是 Map?]
     *   路由匹配需要按 tier 优先级遍历所有规则, 每次添加绑定后排序,
     *   这样 resolve() 只需线性遍历即可, 第一个匹配就是最优匹配.
     *   如果用多层 Map 嵌套虽然查找更快, 但代码复杂度大增, 5 级路由的规则数量通常很少 (<100),
     *   线性遍历完全够用.
     */
    static class BindingTable {
        private final List<Binding> bindings = new ArrayList<>();

        /** 添加绑定规则并重新排序 -- 每次添加后保持列表有序 */
        void add(Binding b) {
            bindings.add(b);
            bindings.sort(Comparator.naturalOrder());
        }

        /** 删除指定绑定规则 -- 返回是否确实删除了至少一条 */
        boolean remove(String agentId, String matchKey, String matchValue) {
            int before = bindings.size();
            bindings.removeIf(b ->
                    b.agentId().equals(agentId)
                    && b.matchKey().equals(matchKey)
                    && b.matchValue().equals(matchValue));
            return bindings.size() < before;
        }

        List<Binding> listAll() { return new ArrayList<>(bindings); }

        /**
         * 路由解析: 遍历 5 个层级, 第一个匹配的绑定胜出.
         *
         * [匹配逻辑详解]
         *   Tier 1 (peer_id): 支持 "channel:peerId" 格式的精确匹配, 或纯 peerId 匹配
         *     为什么支持两种格式? 因为同一个 peerId 可能在不同渠道有不同的含义
         *     (Telegram 的 user_id 123 和 Discord 的 user_id 123 是不同的人)
         *   Tier 2-4: 精确匹配 guild_id / account_id / channel
         *   Tier 5 (default): 无条件匹配, 兜底方案
         *
         * @return 匹配到的绑定; 如果没有匹配则返回 empty
         */
        Optional<Binding> resolve(String channel, String accountId,
                                  String guildId, String peerId) {
            for (Binding b : bindings) {
                boolean match = switch (b.tier()) {
                    case 1 -> b.matchKey().equals("peer_id") && (
                            (b.matchValue().contains(":")
                                    ? b.matchValue().equals(channel + ":" + peerId)
                                    : b.matchValue().equals(peerId)));
                    case 2 -> b.matchKey().equals("guild_id") && b.matchValue().equals(guildId);
                    case 3 -> b.matchKey().equals("account_id") && b.matchValue().equals(accountId);
                    case 4 -> b.matchKey().equals("channel") && b.matchValue().equals(channel);
                    case 5 -> b.matchKey().equals("default");
                    default -> false;
                };
                if (match) return Optional.of(b);
            }
            return Optional.empty();
        }
    }

    // ================================================================
    // 会话键构建器 (Session Key Builder) -- dm_scope 隔离粒度控制
    // ================================================================
    // [什么是 dm_scope?] dm_scope 控制私聊 (DM) 会话的隔离粒度:
    //   决定"哪些对话共享同一个上下文". 粒度越细, 隔离越强, 但 agent 对用户的了解也越少.
    //
    //   main                      -> agent:{id}:main
    //     所有对话共享同一个会话 -- agent 能记住和所有用户的对话历史
    //   per-peer                  -> agent:{id}:direct:{peer}
    //     每个用户有独立会话 -- 最常用的隔离级别
    //   per-channel-peer          -> agent:{id}:{ch}:direct:{peer}
    //     同一用户在不同渠道有独立会话 -- Telegram 上的对话和 CLI 上的对话互不干扰
    //   per-account-channel-peer  -> agent:{id}:{ch}:{acc}:direct:{peer}
    //     最细粒度: 同一渠道不同 bot 账号也有独立会话
    //
    // [教学要点] 会话隔离是 AI agent 系统中的重要设计决策:
    //   - 共享会话 = agent 了解更多上下文, 但不同用户的对话可能互相干扰
    //   - 隔离会话 = 隐私更好, 但 agent 需要为每个用户重新建立上下文

    /**
     * 根据 dm_scope 配置构建会话键.
     *
     * [会话键格式] agent:{agentId}:{scope_specific_parts}
     *   不同 dm_scope 产生不同格式的键, 确保会话隔离级别正确.
     *
     * @param agentId   Agent ID (已规范化)
     * @param channel   渠道名称 ("cli" / "telegram" / "feishu")
     * @param accountId Bot 账号 ID
     * @param peerId    对话对象 ID
     * @param dmScope   会话隔离粒度 ("main" / "per-peer" / "per-channel-peer" / "per-account-channel-peer")
     * @return 格式化的会话键字符串
     */
    static String buildSessionKey(String agentId, String channel, String accountId,
                                  String peerId, String dmScope) {
        String aid = normalizeAgentId(agentId);
        String ch = (channel == null || channel.isBlank()) ? "unknown" : channel.strip().toLowerCase();
        String acc = (accountId == null || accountId.isBlank()) ? "default" : accountId.strip().toLowerCase();
        String pid = (peerId == null) ? "" : peerId.strip().toLowerCase();

        if ("per-account-channel-peer".equals(dmScope) && !pid.isEmpty())
            return "agent:" + aid + ":" + ch + ":" + acc + ":direct:" + pid;
        if ("per-channel-peer".equals(dmScope) && !pid.isEmpty())
            return "agent:" + aid + ":" + ch + ":direct:" + pid;
        if ("per-peer".equals(dmScope) && !pid.isEmpty())
            return "agent:" + aid + ":direct:" + pid;
        return "agent:" + aid + ":main";
    }

    // ================================================================
    // Agent 配置与管理 (AgentConfig & AgentManager)
    // ================================================================
    // [核心概念] 每个 agent 拥有独立的配置、工作区和会话存储:
    //   - 配置: 名字、性格、使用的模型、会话隔离粒度
    //   - 工作区: 独立的文件目录, agent 的工具只能访问自己的工作区
    //   - 会话: ConcurrentHashMap 存储, key 是会话键, value 是消息历史
    // [为什么用 ConcurrentHashMap?] 网关模式下, 多个 WebSocket 连接可能同时触发 agent 调用,
    //   需要线程安全的会话存储.

    /**
     * Agent 配置记录 -- 定义一个 agent 的全部属性.
     *
     * @param id         Agent 唯一标识 (规范化后的小写字符串)
     * @param name       Agent 显示名称 (如 "Luna", "Sage")
     * @param personality Agent 性格描述 (注入到系统提示词中, 影响 agent 的回复风格)
     * @param model      使用的 LLM 模型 (为空则使用全局默认 MODEL_ID)
     * @param dmScope    会话隔离粒度
     */
    record AgentConfig(String id, String name, String personality,
                       String model, String dmScope) {
        /** 获取实际使用的模型 ID -- 如果未指定则使用全局默认 */
        String effectiveModel() { return (model == null || model.isBlank()) ? MODEL_ID : model; }

        /**
         * 生成系统提示词 -- 将 agent 的名字和性格注入到提示词中.
         * [设计决策] 为什么不用模板引擎? 因为 agent 的性格描述是自然语言,
         *   直接字符串拼接足够简单, 避免引入额外依赖.
         */
        String systemPrompt() {
            StringBuilder sb = new StringBuilder("You are ").append(name).append(".");
            if (personality != null && !personality.isBlank())
                sb.append(" Your personality: ").append(personality);
            sb.append(" Answer questions helpfully and stay in character.");
            return sb.toString();
        }
    }

    /**
     * Agent 管理器 -- 负责注册、查找 agent, 以及管理所有会话.
     *
     * [设计要点]
     *   - agents: 用 LinkedHashMap 保持注册顺序, 方便按序展示
     *   - sessions: 用 ConcurrentHashMap 支持多线程并发访问会话
     *   - agentsBase: 每个 agent 在文件系统上有独立目录, 存放 sessions 和数据
     *
     * [教学要点] 这是"多租户" (multi-tenancy) 模式的简化版:
     *   每个 agent 就是一个"租户", 有自己的配置和数据, 互不干扰.
     */
    static class AgentManager {
        private final Map<String, AgentConfig> agents = new LinkedHashMap<>();
        private final Map<String, List<MessageParam>> sessions = new ConcurrentHashMap<>();
        private final Path agentsBase;

        AgentManager() { this(AGENTS_DIR); }
        AgentManager(Path agentsBase) {
            this.agentsBase = agentsBase;
            try { Files.createDirectories(agentsBase); } catch (IOException e) { /* ignore */ }
        }

        /**
         * 注册一个 agent: 存储配置, 创建工作目录.
         * [为什么注册时创建目录?] 提前创建避免运行时首次调用工具时才发现目录不存在.
         */
        void register(AgentConfig config) {
            String aid = normalizeAgentId(config.id());
            agents.put(aid, new AgentConfig(aid, config.name(), config.personality(),
                    config.model(), config.dmScope()));
            // Create agent directories
            try {
                Files.createDirectories(agentsBase.resolve(aid).resolve("sessions"));
                Files.createDirectories(WORKSPACE_DIR.resolve("workspace-" + aid));
            } catch (IOException e) { /* ignore */ }
        }

        AgentConfig getAgent(String agentId) {
            return agents.get(normalizeAgentId(agentId));
        }

        List<AgentConfig> listAgents() { return new ArrayList<>(agents.values()); }

        /** 获取或创建会话: computeIfAbsent 确保线程安全地懒初始化 */
        List<MessageParam> getSession(String sessionKey) {
            return sessions.computeIfAbsent(sessionKey, k -> new ArrayList<>());
        }

        Map<String, Integer> listSessions(String agentId) {
            String prefix = agentId.isBlank() ? "agent:" : "agent:" + normalizeAgentId(agentId) + ":";
            return sessions.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(prefix))
                    .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
        }
    }

    // ================================================================
    // 路由解析 (Route Resolution) -- 通过绑定表查找消息应该由哪个 agent 处理
    // ================================================================
    // [核心流程] 一条消息进来的路由解析过程:
    //   1. 携带 4 个维度信息: channel (渠道), peerId (发送者), accountId (bot账号), guildId (群组)
    //   2. 绑定表按 tier 1-5 逐级匹配
    //   3. 匹配到 -> 使用该绑定的 agentId
    //   4. 未匹配 -> 使用默认 agent ("main")
    //   5. 根据 agent 的 dmScope 配置构建会话键
    //   6. 返回 [agentId, sessionKey]

    /**
     * 解析路由: 将消息的来源信息与绑定表匹配, 确定 agent 和会话.
     *
     * @return 长度为 2 的数组: [agentId, sessionKey]
     */
    static String[] resolveRoute(BindingTable bindings, AgentManager mgr,
                                String channel, String peerId,
                                String accountId, String guildId) {
        Optional<Binding> matched = bindings.resolve(channel, accountId, guildId, peerId);
        String agentId;
        if (matched.isEmpty()) {
            agentId = DEFAULT_AGENT_ID;
            AnsiColors.printInfo("  [route] No binding matched, default: " + agentId);
        } else {
            Binding b = matched.get();
            agentId = b.agentId();
            AnsiColors.printInfo("  [route] Matched: " + b.display());
        }
        AgentConfig agent = mgr.getAgent(agentId);
        String dmScope = agent != null ? agent.dmScope() : "per-peer";
        String sessionKey = buildSessionKey(agentId, channel, accountId, peerId, dmScope);
        return new String[]{agentId, sessionKey};
    }

    // ================================================================
    // 工具定义 (Tools) -- read_file 和 get_current_time
    // ================================================================
    // [教学笔记] 与 S04 的记忆工具不同, S05 提供的是通用的文件读取和时间查询工具.
    // 每个 agent 共享同一套工具定义, 但通过 safePath() 确保只能访问自己的工作区.

    /** 工作空间目录引用: 用于 safePath() 路径校验 */
    static final Path WORKSPACE_DIR_RESOLVE = WORKSPACE_DIR;

    /**
     * 安全路径解析: 将相对路径解析为绝对路径, 并检查是否在工作空间内.
     *
     * [为什么需要路径安全检查?] 防止路径遍历攻击 (Path Traversal):
     *   如果用户通过 agent 请求读取 "../../../etc/passwd",
     *   normalize() 后会变成绝对路径, 但不一定是工作空间内的路径.
     *   startsWith 检查确保只能访问工作空间目录下的文件.
     *
     * @throws IllegalArgumentException 如果路径试图逃逸出工作空间目录
     */
    static Path safePath(String raw) {
        Path target = WORKSPACE_DIR_RESOLVE.resolve(raw).normalize().toAbsolutePath();
        if (!target.startsWith(WORKSPACE_DIR_RESOLVE.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Path traversal blocked: " + raw);
        return target;
    }

    static String toolReadFile(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        AnsiColors.printTool("read_file", filePath);
        try {
            Path target = safePath(filePath);
            if (!Files.exists(target)) return "Error: File not found: " + filePath;
            String content = Files.readString(target);
            return content.length() > MAX_TOOL_OUTPUT
                    ? content.substring(0, MAX_TOOL_OUTPUT) + "\n... [truncated]"
                    : content;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    static String toolGetCurrentTime(Map<String, Object> input) {
        AnsiColors.printTool("get_current_time", "");
        return Instant.now().toString().replace("T", " ").replace("Z", " UTC");
    }

    static Tool buildTool(String name, String description,
                          Map<String, Map<String, String>> properties,
                          List<String> required) {
        Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> pb.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object")).properties(pb.build())
                        .required(required).build())
                .build();
    }

    static final List<ToolUnion> TOOLS = List.of(
            ToolUnion.ofTool(buildTool("read_file",
                    "Read the contents of a file under the workspace directory.",
                    Map.of("file_path", Map.of("type", "string",
                            "description", "Path relative to workspace directory.")),
                    List.of("file_path"))),
            ToolUnion.ofTool(buildTool("get_current_time",
                    "Get the current date and time in UTC.",
                    Map.of(), List.of()))
    );

    static final Map<String, Function<Map<String, Object>, String>> TOOL_HANDLERS = new LinkedHashMap<>();
    static {
        TOOL_HANDLERS.put("read_file", S05GatewayRouting::toolReadFile);
        TOOL_HANDLERS.put("get_current_time", S05GatewayRouting::toolGetCurrentTime);
    }

    // ================================================================
    // Agent 运行器 (Agent Runner) -- 带并发限制的 agent 调用
    // ================================================================

    /**
     * 信号量: 限制同时运行的最大 agent 数量, 防止 API 过载.
     *
     * [为什么是 4?] 这取决于你的 API 速率限制和预算.
     *   每个并发请求都会消耗一个 API 配额, 同时也产生费用.
     *   设置上限可以防止单个网关实例拖垮整个 API 配额.
     *   [教学要点] tryAcquire() 是非阻塞的 -- 如果信号量已满, 立即返回 false,
     *     不会让请求排队等待, 避免用户长时间无响应.
     */
    static final Semaphore agentSemaphore = new Semaphore(4);

    /**
     * 运行 agent: 将用户消息加入会话, 调用 LLM, 返回回复.
     *
     * [并发控制流程]
     *   1. 将用户消息添加到会话历史
     *   2. 尝试获取信号量 (tryAcquire)
     *   3. 如果获取失败 -> 回滚用户消息, 返回"服务器繁忙"
     *   4. 如果获取成功 -> 调用 agentLoop, finally 释放信号量
     *
     * [为什么先添加消息再获取信号量?] 如果获取信号量失败需要回滚,
     *   顺序很重要: 先添加消息, 如果信号量满了就移除, 保证会话状态一致.
     */
    static String runAgent(AgentManager mgr, String agentId, String sessionKey,
                          String userText) {
        AgentConfig agent = mgr.getAgent(agentId);
        if (agent == null) return "Error: agent '" + agentId + "' not found";

        List<MessageParam> messages = mgr.getSession(sessionKey);
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER).content(userText).build());

        if (!agentSemaphore.tryAcquire()) {
            // 信号量获取失败: 回滚刚才添加的用户消息, 保持会话一致性
            messages.remove(messages.size() - 1);
            return "Error: Server busy, try again later.";
        }

        try {
            return agentLoop(agent.effectiveModel(), agent.systemPrompt(), messages);
        } finally {
            agentSemaphore.release();
        }
    }

    /**
     * Agent 核心循环: ReAct 模式, 最多 15 轮工具调用.
     *
     * [为什么限制 15 轮?] 防止 agent 陷入无限循环 (例如工具调用返回错误, agent 不断重试).
     *   15 轮对绝大多数场景足够, 超过则强制终止并返回提示信息.
     *
     * [错误回滚] API 调用失败时, 移除本次循环添加的所有非 USER 消息 (assistant 响应和工具结果),
     *   以及触发本次循环的 USER 消息, 保持会话历史干净.
     */
    static String agentLoop(String model, String system, List<MessageParam> messages) {
        for (int i = 0; i < 15; i++) {
            Message response;
            try {
                response = client.messages().create(MessageCreateParams.builder()
                        .model(model).maxTokens(4096)
                        .system(system).tools(TOOLS)
                        .messages(messages).build());
            } catch (Exception e) {
                // 回滚: 移除 assistant 响应和工具结果, 直到遇到 USER 消息, 然后也移除 USER 消息
                while (!messages.isEmpty()
                        && messages.get(messages.size() - 1).role() != MessageParam.Role.USER)
                    messages.remove(messages.size() - 1);
                if (!messages.isEmpty()) messages.remove(messages.size() - 1);
                return "API Error: " + e.getMessage();
            }

            messages.add(response.toParam());
            StopReason reason = response.stopReason().orElse(null);

            if (reason == StopReason.END_TURN) {
                return response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
            } else if (reason == StopReason.TOOL_USE) {
                List<ContentBlockParam> results = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (!block.isToolUse()) continue;
                    ToolUseBlock tu = block.asToolUse();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> toolInput = tu._input().convert(Map.class);
                    String result = TOOL_HANDLERS.getOrDefault(tu.name(),
                                    (m) -> "Error: Unknown tool '" + tu.name() + "'")
                            .apply(toolInput);
                    results.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(tu.id()).content(result).build()));
                }
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(results).build());
            } else {
                return response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
            }
        }
        return "[max iterations reached]";
    }

    // ================================================================
    // 网关服务器 (GatewayServer) -- WebSocket + JSON-RPC 2.0 实时通信
    // ================================================================
    // [为什么用 WebSocket?] 相比 HTTP REST, WebSocket 支持:
    //   - 全双工通信: 服务器可以主动推送消息给客户端
    //   - 低延迟: 建立连接后, 后续消息无需 HTTP 握手
    //   - 适合实时场景: 聊天、通知、流式输出
    //
    // [为什么用 JSON-RPC 2.0?] 统一的请求-响应协议:
    //   - 每个请求有 id, 可以追踪对应的响应
    //   - method + params 的模式简洁清晰
    //   - 错误有标准化的 code + message 格式
    //
    // [支持的方法]
    //   send           -- 发送消息给 agent (可选指定 agent_id 或走路由)
    //   bindings.set   -- 添加路由绑定规则
    //   bindings.list  -- 列出所有绑定规则
    //   sessions.list  -- 列出会话
    //   agents.list    -- 列出所有 agent
    //   status         -- 查看网关状态

    /**
     * WebSocket 网关服务器: 接收 JSON-RPC 2.0 请求, 分发给对应处理器.
     */
    static class GatewayServer extends WebSocketServer {
        private final AgentManager mgr;
        private final BindingTable bindings;
        private final long startTime;

        GatewayServer(AgentManager mgr, BindingTable bindings, int port) {
            super(new InetSocketAddress(port));
            this.mgr = mgr;
            this.bindings = bindings;
            this.startTime = System.currentTimeMillis();
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            AnsiColors.printInfo("  [ws] Client connected: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            AnsiColors.printInfo("  [ws] Client disconnected: " + conn.getRemoteSocketAddress());
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            AnsiColors.printError("  [ws] Error: " + ex.getMessage());
        }

        @Override
        public void onStart() {
            System.out.println(AnsiColors.GREEN + "  Gateway started on ws://localhost:"
                    + getPort() + AnsiColors.RESET);
        }

        /**
         * 处理 WebSocket 消息: 解析 JSON-RPC 2.0 请求, 分发给对应方法处理器.
         * [错误处理] 任何异常都转化为 JSON-RPC 错误响应, 确保客户端总能收到回复.
         */
        @Override
        public void onMessage(WebSocket conn, String message) {
            Object id = null;
            try {
                Map<String, Object> req = JsonUtils.toMap(message);
                id = req.get("id");
                String method = (String) req.getOrDefault("method", "");
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) req.getOrDefault("params", Map.of());

                // JSON-RPC 2.0 方法分发: 根据 method 名称路由到对应处理器
                Object result = switch (method) {
                    case "send" -> handleSend(params);
                    case "bindings.set" -> handleBindSet(params);
                    case "bindings.list" -> handleBindList();
                    case "sessions.list" -> handleSessionsList(params);
                    case "agents.list" -> handleAgentsList();
                    case "status" -> handleStatus();
                    default -> throw new RuntimeException("Unknown method: " + method);
                };

                conn.send(JsonUtils.toJson(Map.of("jsonrpc", "2.0", "result", result, "id", id)));
            } catch (Exception e) {
                conn.send(JsonUtils.toJson(Map.of("jsonrpc", "2.0",
                        "error", Map.of("code", -32000, "message", e.getMessage()), "id", id)));
            }
        }

        /**
         * 处理 send 请求: 接收用户消息, 路由到对应 agent, 返回回复.
         *
         * [两种路由模式]
         *   1. 指定 agent_id: 直接路由到指定 agent (跳过绑定表匹配)
         *   2. 自动路由: 通过绑定表匹配, 找到最合适的 agent
         * [为什么支持两种模式?] 指定模式适合测试和管理命令, 自动模式适合正常使用.
         */
        @SuppressWarnings("unchecked")
        Map<String, Object> handleSend(Map<String, Object> p) {
            String text = (String) p.getOrDefault("text", "");
            if (text.isEmpty()) throw new RuntimeException("text is required");
            String ch = (String) p.getOrDefault("channel", "websocket");
            String pid = (String) p.getOrDefault("peer_id", "ws-client");

            String agentId, sessionKey;
            if (p.containsKey("agent_id")) {
                agentId = normalizeAgentId((String) p.get("agent_id"));
                AgentConfig a = mgr.getAgent(agentId);
                sessionKey = buildSessionKey(agentId, ch, null, pid,
                        a != null ? a.dmScope() : "per-peer");
            } else {
                String[] route = resolveRoute(bindings, mgr, ch, pid, null, null);
                agentId = route[0];
                sessionKey = route[1];
            }

            String reply = runAgent(mgr, agentId, sessionKey, text);
            return Map.of("agent_id", agentId, "session_key", sessionKey, "reply", reply);
        }

        Map<String, Object> handleBindSet(Map<String, Object> p) {
            Binding b = new Binding(
                    normalizeAgentId((String) p.getOrDefault("agent_id", "")),
                    ((Number) p.getOrDefault("tier", 5)).intValue(),
                    (String) p.getOrDefault("match_key", "default"),
                    (String) p.getOrDefault("match_value", "*"),
                    ((Number) p.getOrDefault("priority", 0)).intValue());
            bindings.add(b);
            return Map.of("ok", true, "binding", b.display());
        }

        List<Map<String, Object>> handleBindList() {
            return bindings.listAll().stream()
                    .map(b -> Map.<String, Object>of(
                            "agent_id", b.agentId(), "tier", b.tier(),
                            "match_key", b.matchKey(), "match_value", b.matchValue(),
                            "priority", b.priority()))
                    .toList();
        }

        Map<String, Integer> handleSessionsList(Map<String, Object> p) {
            return mgr.listSessions((String) p.getOrDefault("agent_id", ""));
        }

        List<Map<String, Object>> handleAgentsList() {
            return mgr.listAgents().stream()
                    .map(a -> Map.<String, Object>of(
                            "id", a.id(), "name", a.name(),
                            "model", a.effectiveModel(), "dm_scope", a.dmScope()))
                    .toList();
        }

        Map<String, Object> handleStatus() {
            return Map.<String, Object>of(
                    "running", true,
                    "uptime_seconds", (System.currentTimeMillis() - startTime) / 1000,
                    "connected_clients", getConnections().size(),
                    "agent_count", mgr.listAgents().size(),
                    "binding_count", bindings.listAll().size());
        }
    }

    // ================================================================
    // REPL 交互命令 (REPL Commands) -- 用于调试和演示的命令行接口
    // ================================================================
    // [支持的命令]
    //   /bindings          列出所有路由绑定规则
    //   /route <ch> <peer> 测试路由解析 (模拟一条消息, 查看匹配结果)
    //   /agents            列出所有已注册 agent
    //   /sessions          列出所有活跃会话
    //   /switch <id>       强制使用指定 agent (跳过路由)
    //   /switch off        恢复自动路由模式
    //   /gateway           启动 WebSocket 网关服务器

    /** 打印所有绑定规则, 用不同颜色区分不同层级 */
    static void cmdBindings(BindingTable bt) {
        List<Binding> all = bt.listAll();
        if (all.isEmpty()) { AnsiColors.printInfo("  (no bindings)"); return; }
        System.out.println(AnsiColors.BOLD + "\nRoute Bindings (" + all.size() + "):" + AnsiColors.RESET);
        String[] colors = {AnsiColors.MAGENTA, AnsiColors.BLUE, AnsiColors.CYAN, AnsiColors.GREEN, AnsiColors.DIM};
        for (Binding b : all) {
            String c = colors[Math.min(b.tier() - 1, 4)];
            System.out.println("  " + c + b.display() + AnsiColors.RESET);
        }
        System.out.println();
    }

    /** 测试路由解析: 模拟一条消息, 查看会被路由到哪个 agent 和会话 */
    static void cmdRoute(BindingTable bt, AgentManager mgr, String args) {
        String[] parts = args.strip().split("\\s+");
        if (parts.length < 2 || parts[0].isBlank()) {
            System.out.println(AnsiColors.YELLOW + "  Usage: /route <channel> <peer_id> [account_id] [guild_id]"
                    + AnsiColors.RESET);
            return;
        }
        String ch = parts[0], pid = parts[1];
        String acc = parts.length > 2 ? parts[2] : "";
        String gid = parts.length > 3 ? parts[3] : "";
        String[] route = resolveRoute(bt, mgr, ch, pid, acc, gid);
        AgentConfig a = mgr.getAgent(route[0]);
        System.out.println(AnsiColors.BOLD + "\nRoute Resolution:" + AnsiColors.RESET);
        AnsiColors.printInfo("  Input:   ch=" + ch + " peer=" + pid
                + " acc=" + (acc.isEmpty() ? "-" : acc) + " guild=" + (gid.isEmpty() ? "-" : gid));
        System.out.println(AnsiColors.CYAN + "  Agent:   " + route[0]
                + " (" + (a != null ? a.name() : "?") + ")" + AnsiColors.RESET);
        System.out.println(AnsiColors.GREEN + "  Session: " + route[1] + AnsiColors.RESET + "\n");
    }

    /** 打印所有已注册 agent 的信息: ID、名称、模型、会话隔离粒度、性格预览 */
    static void cmdAgents(AgentManager mgr) {
        List<AgentConfig> agents = mgr.listAgents();
        if (agents.isEmpty()) { AnsiColors.printInfo("  (no agents)"); return; }
        System.out.println(AnsiColors.BOLD + "\nAgents (" + agents.size() + "):" + AnsiColors.RESET);
        for (AgentConfig a : agents) {
            System.out.println(AnsiColors.CYAN + "  " + a.id() + AnsiColors.RESET
                    + " (" + a.name() + ")  model=" + a.effectiveModel() + "  dm_scope=" + a.dmScope());
            if (a.personality() != null && !a.personality().isEmpty()) {
                String preview = a.personality().length() > 70
                        ? a.personality().substring(0, 70) + "..." : a.personality();
                AnsiColors.printInfo("    " + preview);
            }
        }
        System.out.println();
    }

    /** 打印所有活跃会话: 会话键和消息数量, 按键名排序 */
    static void cmdSessions(AgentManager mgr) {
        Map<String, Integer> sessions = mgr.listSessions("");
        if (sessions.isEmpty()) { AnsiColors.printInfo("  (no sessions)"); return; }
        System.out.println(AnsiColors.BOLD + "\nSessions (" + sessions.size() + "):" + AnsiColors.RESET);
        sessions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.println(AnsiColors.GREEN + "  " + e.getKey()
                        + AnsiColors.RESET + " (" + e.getValue() + " msgs)"));
        System.out.println();
    }

    // ================================================================
    // 演示配置 (Demo Setup) -- 双 Agent (Luna + Sage) + 路由绑定规则
    // ================================================================
    // [Luna 的性格] 温暖、好奇、鼓励型. 喜欢追问, 适合作为通用助手.
    //   默认 agent -- 所有未被特定规则匹配的消息都路由给 Luna.
    // [Sage 的性格] 直接、分析型、简洁. 偏好事实而非观点, 适合技术和管理场景.
    //   通过绑定规则被分配给 Telegram 渠道和 Discord 管理员.
    //
    // [路由规则说明]
    //   1. default -> Luna (Tier 5, 兜底: 所有未匹配的消息归 Luna)
    //   2. channel=telegram -> Sage (Tier 4: Telegram 渠道的消息归 Sage)
    //   3. peer_id=discord:admin-001 -> Sage (Tier 1, 最高优先级: Discord 管理员归 Sage)
    //   注意: Tier 1 的 priority=10, 即使将来添加更多 Tier 1 规则, 这条也优先.

    /**
     * 创建并注册演示 agent: Luna (温暖型) 和 Sage (分析型).
     * 两个 agent 都使用 per-peer 会话隔离, 即每个用户有独立的对话上下文.
     */
    static AgentManager setupAgents() {
        AgentManager mgr = new AgentManager();
        // Luna: 温暖的好奇型助手, 适合日常对话
        mgr.register(new AgentConfig("luna", "Luna",
                "warm, curious, and encouraging. You love asking follow-up questions.",
                "", "per-peer"));
        // Sage: 理性分析型助手, 适合技术和决策场景
        mgr.register(new AgentConfig("sage", "Sage",
                "direct, analytical, and concise. You prefer facts over opinions.",
                "", "per-peer"));
        return mgr;
    }

    /**
     * 配置演示路由绑定规则:
     *   - 默认 -> Luna (所有未匹配的消息)
     *   - Telegram 渠道 -> Sage (Tier 4)
     *   - Discord 管理员 -> Sage (Tier 1, 最高优先级)
     */
    static BindingTable setupBindings() {
        BindingTable bt = new BindingTable();
        bt.add(new Binding("luna", 5, "default", "*", 0));         // Tier 5: 兜底, 所有消息默认归 Luna
        bt.add(new Binding("sage", 4, "channel", "telegram", 0));  // Tier 4: Telegram 渠道归 Sage
        bt.add(new Binding("sage", 1, "peer_id", "discord:admin-001", 10)); // Tier 1: Discord 管理员归 Sage
        return bt;
    }

    // ================================================================
    // 主交互循环 (Main REPL) -- 命令行交互入口
    // ================================================================
    // [工作流程]
    //   1. 初始化 agent 管理器和绑定表
    //   2. 进入主循环: 读取用户输入
    //   3. 如果是 / 开头, 处理为命令
    //   4. 否则走路由解析 -> 找到 agent -> 执行 agent -> 输出回复
    //   5. /switch 命令可以强制指定 agent, 跳过路由匹配

    static void repl() {
        AgentManager mgr = setupAgents();
        BindingTable bindings = setupBindings();

        AnsiColors.printInfo("================================================================");
        AnsiColors.printInfo("  claw0  |  Section 05: Gateway & Routing");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("================================================================");
        AnsiColors.printInfo("  /bindings  /route <ch> <peer>  /agents  /sessions  /switch <id>  /gateway");
        System.out.println();

        String ch = "cli", pid = "repl-user";
        String forceAgent = "";
        GatewayServer gateway = null;

        Scanner scanner = new Scanner(System.in);
        while (true) {
            String userInput;
            try {
                System.out.print(AnsiColors.coloredPrompt());
                userInput = scanner.nextLine().strip();
            } catch (Exception e) {
                AnsiColors.printInfo("Goodbye.");
                break;
            }

            if (userInput.isEmpty()) continue;
            if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) {
                AnsiColors.printInfo("Goodbye.");
                break;
            }

            if (userInput.startsWith("/")) {
                String[] cmdParts = userInput.split("\\s+", 2);
                String cmd = cmdParts[0].toLowerCase();
                String cmdArgs = cmdParts.length > 1 ? cmdParts[1] : "";

                switch (cmd) {
                    case "/bindings" -> cmdBindings(bindings);
                    case "/route" -> cmdRoute(bindings, mgr, cmdArgs);
                    case "/agents" -> cmdAgents(mgr);
                    case "/sessions" -> cmdSessions(mgr);
                    case "/switch" -> {
                        if (cmdArgs.isEmpty()) {
                            AnsiColors.printInfo("  force=" + (forceAgent.isEmpty() ? "(off)" : forceAgent));
                        } else if ("off".equalsIgnoreCase(cmdArgs)) {
                            forceAgent = "";
                            AnsiColors.printInfo("  Routing mode restored.");
                        } else {
                            String aid = normalizeAgentId(cmdArgs);
                            if (mgr.getAgent(aid) != null) {
                                forceAgent = aid;
                                System.out.println(AnsiColors.GREEN + "  Forcing: " + aid + AnsiColors.RESET);
                            } else {
                                System.out.println(AnsiColors.YELLOW + "  Not found: " + aid + AnsiColors.RESET);
                            }
                        }
                    }
                    case "/gateway" -> {
                        // 启动 WebSocket 网关: 后台运行, 接受远程 JSON-RPC 2.0 连接
                        // 网关启动后, 可以通过 WebSocket 客户端 (如 wscat) 发送请求
                        if (gateway != null) {
                            AnsiColors.printInfo("  Already running.");
                        } else {
                            int port = Integer.parseInt(Config.get("GATEWAY_PORT", "8765"));
                            gateway = new GatewayServer(mgr, bindings, port);
                            gateway.start();
                            System.out.println(AnsiColors.GREEN
                                    + "  Gateway running in background on ws://localhost:" + port
                                    + AnsiColors.RESET + "\n");
                        }
                    }
                    default -> System.out.println(AnsiColors.YELLOW + "  Unknown: " + cmd + AnsiColors.RESET);
                }
                continue;
            }

            // 路由决策: 强制模式 or 自动路由?
            // forceAgent 非空 -> 跳过绑定表匹配, 直接使用指定 agent
            // forceAgent 为空 -> 正常走路由解析流程
            String agentId, sessionKey;
            if (!forceAgent.isEmpty()) {
                agentId = forceAgent;
                AgentConfig a = mgr.getAgent(agentId);
                sessionKey = buildSessionKey(agentId, ch, null, pid,
                        a != null ? a.dmScope() : "per-peer");
            } else {
                String[] route = resolveRoute(bindings, mgr, ch, pid, null, null);
                agentId = route[0];
                sessionKey = route[1];
            }

            AgentConfig agent = mgr.getAgent(agentId);
            String name = agent != null ? agent.name() : agentId;
            AnsiColors.printInfo("  -> " + name + " (" + agentId + ") | " + sessionKey);

            String reply = runAgent(mgr, agentId, sessionKey, userInput);
            System.out.println("\n" + AnsiColors.GREEN + AnsiColors.BOLD + name + ":" + AnsiColors.RESET
                    + " " + reply + "\n");
        }

        if (gateway != null) {
            try { gateway.stop(); } catch (Exception e) { /* ignore */ }
        }
    }

    // ================================================================
    // Entry Point
    // ================================================================

    public static void main(String[] args) {
        String apiKey = Config.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.startsWith("sk-ant-x")) {
            AnsiColors.printError("Error: ANTHROPIC_API_KEY not set.");
            AnsiColors.printInfo("Copy .env.example to .env and fill in your key.");
            System.exit(1);
        }
        repl();
    }
}
