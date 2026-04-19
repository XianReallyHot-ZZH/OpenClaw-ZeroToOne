package com.claw0.sessions;

/**
 * Section 04: Channels -- "Same brain, many mouths" / 同一大脑, 多个嘴巴
 *
 * 渠道抽象层 (Channel Abstraction):
 *   核心思想: 把不同消息平台 (Telegram / 飞书 / CLI) 的差异封装在 Channel 接口后面,
 *   agent 循环只看到统一的 InboundMessage. 新增平台只需实现 receive() + send() 两个方法,
 *   agent 循环完全不需要改动 -- 这就是"开闭原则"在 AI agent 架构中的体现.
 *
 *   Telegram ----.                          .---- sendMessage API
 *   Feishu -------+-- InboundMessage ---+---- im/v1/messages
 *   CLI (stdin) --'    Agent Loop        '---- print(stdout)
 *
 *   [架构要点] 为什么要把 receive/send 抽象成接口?
 *     1. 每个平台的 API、认证方式、消息格式都不同, 但 agent 的推理逻辑完全一样
 *     2. 新平台接入 = 写一个新的 Channel 实现, 不碰 agent 代码
 *     3. 测试时可以用 MockChannel 替代真实 API
 *
 *   [教学要点] 本节引入的关键概念:
 *     - 消息队列 (ConcurrentLinkedQueue): 多渠道并发输入时, 用队列缓冲消息
 *     - 偏移量持久化: Telegram getUpdates 用 offset 标记已处理消息, 重启后不重复
 *     - 消息分片: Telegram 单条消息限制 4096 字符, 超长回复需要拆分
 *     - 记忆工具: MEMORY.md 持久化存储 + 每日 JSONL 日志, 给 agent 加上"长期记忆"
 *
 * Usage:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S04Channels"
 */

// region Common Imports
import com.claw0.common.AnsiColors;
import com.claw0.common.Clients;
import com.claw0.common.Config;

import com.anthropic.client.AnthropicClient;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.stream.Collectors;

public class S04Channels {

    // region Configuration -- 全局配置常量
    // [教学笔记] 所有配置项都通过 Config.get() 读取, 优先使用环境变量, 没有则使用默认值.
    // 这种"约定优于配置"的方式让开发时只需 .env 文件, 部署时用环境变量覆盖.

    /** 模型 ID: 默认使用 Claude Sonnet, 通过环境变量 MODEL_ID 可覆盖 */
    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");

    /** 系统提示词: 告诉 LLM 它连接了多个消息渠道, 并且拥有记忆工具 */
    static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant connected to multiple messaging channels.\n"
            + "You can save and search notes using the provided tools.\n"
            + "When responding, be concise and helpful.";

    /** 工具输出最大长度: 截断过长的工具返回值, 防止 LLM 上下文溢出 */
    static final int MAX_TOOL_OUTPUT = 50_000;

    /** 工作目录: 项目根目录, 所有文件操作的基础路径 */
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));

    /** 工作空间目录: 存放 agent 产生的文件 (如 MEMORY.md) */
    static final Path WORKSPACE_DIR = WORKDIR.resolve("workspace");

    /** 状态目录: 存放平台相关的持久化状态 (如 Telegram offset 文件) */
    static final Path STATE_DIR = WORKSPACE_DIR.resolve(".state");

    /** 记忆文件: agent 的长期记忆存储, 使用 Markdown 格式便于人眼阅读 */
    static final Path MEMORY_FILE = WORKSPACE_DIR.resolve("MEMORY.md");

    /** Anthropic API 客户端: 通过 Config 读取 API Key 和 Base URL */
    static final AnthropicClient client = Clients.create();
    // endregion

    // ================================================================
    // 数据结构 (Data Structures)
    // ================================================================

    /**
     * 统一的入站消息格式 -- 所有渠道的消息最终都转换为这个记录.
     *
     * [为什么需要统一格式?] 不同平台的消息结构天差地别:
     *   Telegram 有 message_id / chat_id / from (user object) ...
     *   飞书有 chat_id / msg_type / sender_id (含 open_id) ...
     *   CLI 只有纯文本.
     * 统一为 InboundMessage 后, agent 循环完全不需要知道消息来自哪个平台.
     *
     * [字段说明]
     * @param text      消息文本内容 (所有非文本消息被过滤掉)
     * @param senderId  发送者 ID (用于区分谁在说话)
     * @param channel   渠道名称 ("cli" / "telegram" / "feishu")
     * @param accountId 机器人账号 ID (同一渠道可以运行多个 bot)
     * @param peerId    对话对象 ID (私聊=用户ID, 群聊=群ID, 用于路由回复)
     * @param isGroup   是否群聊消息 (群聊和私聊的处理逻辑可能不同)
     * @param media     媒体附件列表 (本示例暂未实现, 预留扩展)
     * @param raw       平台原始数据 (调试用, 保留完整上下文)
     */
    record InboundMessage(
            String text, String senderId, String channel,
            String accountId, String peerId, boolean isGroup,
            List<Map<String, Object>> media, Map<String, Object> raw
    ) {
        InboundMessage(String text, String senderId, String channel,
                       String accountId, String peerId) {
            this(text, senderId, channel, accountId, peerId, false, List.of(), Map.of());
        }
    }

    /**
     * 机器人账号配置 -- 同一渠道类型可以运行多个 bot 实例.
     *
     * [为什么需要 accountId?] 比如你可能在同一个 Telegram Bot API 管理多个 bot,
     * 每个 bot 有不同的 token 和配置. accountId 就是区分它们的标识.
     *
     * @param channel   渠道类型 ("telegram" / "feishu")
     * @param accountId 机器人账号的唯一标识
     * @param token     认证令牌 (Telegram bot token 等)
     * @param config    额外配置 (如飞书的 app_id / app_secret / allowed_chats 等)
     */
    record ChannelAccount(
            String channel, String accountId, String token,
            Map<String, Object> config
    ) {
        ChannelAccount(String channel, String accountId, String token) {
            this(channel, accountId, token, Map.of());
        }
    }

    // ================================================================
    // 会话键 (Session Key)
    // ================================================================

    /**
     * 构建会话键: 用渠道+账号+对端ID 唯一标识一个对话上下文.
     *
     * [为什么需要会话键?] 同一个 bot 可能同时和多个用户聊天,
     * 每个对话需要独立的消息历史. 会话键就是隔离不同对话的"房间号".
     * 格式: agent:{agentId}:direct:{channel}:{peerId}
     */
    static String buildSessionKey(String channel, String accountId, String peerId) {
        return "agent:main:direct:" + channel + ":" + peerId;
    }

    // ================================================================
    // 渠道接口 (Channel Interface)
    // ================================================================

    /**
     * 渠道接口 -- 每个平台实现 receive() 和 send(), agent 循环只看到统一的消息.
     *
     * [设计意图] 这是典型的"策略模式" (Strategy Pattern):
     *   - Channel 定义"接收消息"和"发送消息"两个行为
     *   - 每个平台 (CLI / Telegram / 飞书) 是一个具体策略
     *   - agent 循环是 Context, 不关心具体策略的实现
     *
     * [教学要点] 新增一个平台 (比如 Discord) 只需:
     *   1. 创建 class DiscordChannel implements Channel
     *   2. 实现 receive() 和 send()
     *   3. 在 agentLoop() 中注册
     *   不需要修改 agent 的任何逻辑代码.
     */
    interface Channel {
        /** 返回渠道名称, 用于注册表查找和日志打印 */
        String name();

        /**
         * 接收一条入站消息.
         * @return Optional 包装的消息; 空表示没有新消息 (非阻塞模式)
         */
        Optional<InboundMessage> receive();

        /**
         * 发送消息给指定对端.
         * @param to   目标 ID (Telegram chat_id / 飞书 chat_id)
         * @param text 要发送的文本
         * @return true=发送成功, false=发送失败
         */
        boolean send(String to, String text);

        /** 关闭渠道, 释放资源 (如 HTTP 连接). 默认空实现. */
        default void close() {}
    }

    // ================================================================
    // 命令行渠道 (CLIChannel) -- 最简单的实现, 直接读写终端
    // ================================================================

    /**
     * 命令行渠道 -- 最简单的 Channel 实现, 直接通过 stdin/stdout 交互.
     *
     * [教学价值] 这个实现展示了 Channel 接口的最小公约数:
     *   receive() = 读一行 stdin
     *   send()    = print 到 stdout
     *   没有网络请求, 没有认证, 没有消息格式解析.
     * 理解了这个, 再看 TelegramChannel 和 FeishuChannel 就容易多了.
     */
    static class CLIChannel implements Channel {
        private final String accountId = "cli-local";
        private final Scanner scanner = new Scanner(System.in);

        @Override public String name() { return "cli"; }

        @Override
        public Optional<InboundMessage> receive() {
            try {
                System.out.print(AnsiColors.coloredPrompt());
                String text = scanner.nextLine().trim();
                if (text.isEmpty()) return Optional.empty();
                return Optional.of(new InboundMessage(
                        text, "cli-user", "cli", accountId, "cli-user"));
            } catch (Exception e) {
                return Optional.empty();
            }
        }

        @Override
        public boolean send(String to, String text) {
            AnsiColors.printAssistant(text);
            return true;
        }
    }

    // ================================================================
    // 偏移量持久化 (Offset Persistence)
    // ================================================================

    /**
     * 保存偏移量: 将 Telegram getUpdates 的 offset 写入文件.
     *
     * [为什么需要偏移量持久化?]
     *   Telegram getUpdates API 用 offset 参数标记"已处理到哪条消息".
     *   如果程序重启后 offset 丢失, Telegram 会重新发送所有未确认的消息,
     *   导致 agent 对同一条消息重复回复.
     *   写入文件后, 重启时从文件恢复 offset, 就不会重复处理了.
     *
     * @param path   偏移量文件路径 (每个 bot 账号一个文件)
     * @param offset 当前已处理的最大 update_id + 1
     */
    static void saveOffset(Path path, int offset) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, String.valueOf(offset));
        } catch (IOException e) { /* ignore -- 偏移量保存失败不是致命错误 */ }
    }

    /**
     * 加载偏移量: 从文件恢复上次的 offset 值.
     *
     * @param path 偏移量文件路径
     * @return 文件中的 offset 值; 文件不存在或格式错误则返回 0 (从头开始)
     */
    static int loadOffset(Path path) {
        try { return Integer.parseInt(Files.readString(path).trim()); }
        catch (Exception e) { return 0; /* 文件不存在或内容不合法, 从 0 开始 */ }
    }

    // ================================================================
    // Telegram 渠道 (TelegramChannel) -- 基于 Bot API 的长轮询实现
    // ================================================================

    /**
     * Telegram 渠道 -- 使用 Bot API 的 long polling (长轮询) 机制接收消息.
     *
     * [长轮询原理]
     *   1. 客户端调用 getUpdates(offset=X, timeout=30)
     *   2. 如果没有新消息, Telegram 服务器会保持连接 30 秒不返回
     *   3. 在这 30 秒内有新消息到达, 服务器立即返回
     *   4. 客户端处理完消息后, 用 offset=X+1 再次请求
     *   这样就实现了"准实时"的消息接收, 不需要公网 IP 或 webhook.
     *
     * [为什么选长轮询而不是 webhook?]
     *   - 长轮询不需要公网可达的服务器和 HTTPS 证书
     *   - 适合开发和测试阶段
     *   - webhook 适合生产环境 (更低的延迟, 更高的吞吐)
     */
    static class TelegramChannel implements Channel {
        /** Telegram 单条消息最大长度: 超过此长度的回复需要分片发送 */
        static final int MAX_MSG_LEN = 4096;
        private final String accountId;
        private final String baseUrl;
        private final HttpClient http;
        private final Set<String> allowedChats;
        private final Path offsetPath;
        private int offset;
        private final Set<Integer> seen = ConcurrentHashMap.newKeySet();

        /**
         * 构造 Telegram 渠道实例.
         *
         * @param account 机器人账号配置, 包含 token 和 allowed_chats 等
         */
        TelegramChannel(ChannelAccount account) {
            this.accountId = account.accountId();
            this.baseUrl = "https://api.telegram.org/bot" + account.token();
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(35))  // 35秒: 匹配长轮询的30秒超时 + 网络开销
                    .build();
            // 允许的聊天 ID 列表: 为空则接受所有聊天, 否则只处理指定 chat_id 的消息
            // [安全考虑] 防止 bot 被陌生人滥用, 限制为指定用户或群组
            String raw = (String) account.config().getOrDefault("allowed_chats", "");
            this.allowedChats = raw.isBlank() ? Set.of()
                    : Set.of(raw.split(","));
            this.offsetPath = STATE_DIR.resolve("telegram/offset-" + accountId + ".txt");
            this.offset = loadOffset(offsetPath);  // 从文件恢复上次处理到的位置
        }

        /**
         * 调用 Telegram Bot API 的通用方法.
         *
         * @param method API 方法名 (如 "getUpdates", "sendMessage", "sendChatAction")
         * @param params 请求参数 (自动过滤 null 值)
         * @return API 返回的 result 字段; 失败返回 null
         */
        @SuppressWarnings("unchecked")
        Object apiCall(String method, Map<String, Object> params) {
            // 过滤掉 null 值的参数 -- Telegram API 对 null 值的行为不一致
            Map<String, Object> filtered = new LinkedHashMap<>();
            params.forEach((k, v) -> { if (v != null) filtered.put(k, v); });
            try {
                String body = com.claw0.common.JsonUtils.toJson(filtered);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/" + method))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(35))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> data = com.claw0.common.JsonUtils.toMap(resp.body());
                if (!Boolean.TRUE.equals(data.get("ok"))) {
                    System.out.println("  " + AnsiColors.RED + "[telegram] " + method
                            + ": " + data.getOrDefault("description", "?") + AnsiColors.RESET);
                    return null;
                }
                return data.get("result");
            } catch (Exception e) {
                System.out.println("  " + AnsiColors.RED + "[telegram] " + method
                        + ": " + e.getMessage() + AnsiColors.RESET);
                return null;
            }
        }

        void sendTyping(String chatId) {
            apiCall("sendChatAction", Map.of("chat_id", chatId, "action", "typing"));
        }

        @SuppressWarnings("unchecked")
        List<InboundMessage> poll() {
            var result = apiCall("getUpdates",
                    Map.of("offset", offset, "timeout", 30));
            if (!(result instanceof List<?> list)) return new ArrayList<>();

            List<InboundMessage> messages = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> updateRaw)) continue;
                Map<String, Object> update = (Map<String, Object>) updateRaw;
                int uid = ((Number) update.getOrDefault("update_id", 0)).intValue();
                if (uid >= offset) {
                    offset = uid + 1;
                    saveOffset(offsetPath, offset);
                }
                if (!seen.add(uid)) continue;  // 去重: 同一条消息可能被 getUpdates 返回多次
                if (seen.size() > 5000) seen.clear();  // 防止内存泄漏: 超过 5000 条清空去重集合

                Map<String, Object> msg = (Map<String, Object>) update.get("message");
                if (msg == null) continue;
                InboundMessage inbound = parseMessage(msg, update);
                if (inbound == null) continue;
                if (!allowedChats.isEmpty() && !allowedChats.contains(inbound.peerId())) continue;
                messages.add(inbound);
            }
            return messages;
        }

        @SuppressWarnings("unchecked")
        InboundMessage parseMessage(Map<String, Object> msg, Map<String, Object> rawUpdate) {
            Map<String, Object> chat = (Map<String, Object>) msg.getOrDefault("chat", Map.of());
            String chatType = (String) chat.getOrDefault("type", "");
            String chatId = String.valueOf(chat.getOrDefault("id", ""));
            Map<String, Object> from = (Map<String, Object>) msg.getOrDefault("from", Map.of());
            String userId = String.valueOf(from.getOrDefault("id", ""));
            String text = (String) msg.getOrDefault("text", "");
            if (text == null) text = (String) msg.getOrDefault("caption", "");
            if (text == null || text.isBlank()) return null;

            boolean isGroup = "group".equals(chatType) || "supergroup".equals(chatType);
            String peerId = "private".equals(chatType) ? userId : chatId;

            return new InboundMessage(text, userId, "telegram",
                    accountId, peerId, isGroup, List.of(), rawUpdate);
        }

        @Override public String name() { return "telegram"; }

        @Override
        public Optional<InboundMessage> receive() {
            List<InboundMessage> msgs = poll();
            return msgs.isEmpty() ? Optional.empty() : Optional.of(msgs.get(0));
        }

        @Override
        public boolean send(String to, String text) {
            String chatId = to;
            Integer threadId = null;
            if (to.contains(":topic:")) {
                String[] parts = to.split(":topic:");
                chatId = parts[0];
                if (parts.length > 1) threadId = Integer.parseInt(parts[1]);
            }
            boolean ok = true;
            for (String chunk : chunkMessage(text)) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("chat_id", chatId);
                params.put("text", chunk);
                if (threadId != null) params.put("message_thread_id", threadId);
                if (apiCall("sendMessage", params) == null) ok = false;
            }
            return ok;
        }

        /**
         * 消息分片: 按 Telegram 4096 字符限制拆分长消息.
         *
         * [为什么需要分片?] Telegram sendMessage API 单条消息最大 4096 个字符.
         * 如果 agent 的回复超过这个长度, 直接发送会被 Telegram 截断或报错.
         * 分片策略: 优先在换行符处切割 (保持段落完整), 找不到换行符才硬切.
         *
         * [教学要点] 这是"适配器模式"中的输出适配: agent 不关心平台限制,
         * Channel 实现负责把 agent 的输出适配到平台要求.
         */
        List<String> chunkMessage(String text) {
            if (text.length() <= MAX_MSG_LEN) return List.of(text);
            List<String> chunks = new ArrayList<>();
            while (!text.isEmpty()) {
                if (text.length() <= MAX_MSG_LEN) { chunks.add(text); break; }
                int cut = text.lastIndexOf('\n', MAX_MSG_LEN);
                if (cut <= 0) cut = MAX_MSG_LEN;
                chunks.add(text.substring(0, cut));
                text = text.substring(cut).stripLeading();
            }
            return chunks;
        }
    }

    // ================================================================
    // 飞书渠道 (FeishuChannel) -- 基于 Webhook 的消息收发
    // ================================================================
    // [飞书 vs Telegram 的架构差异]
    //   Telegram: 客户端主动轮询 (long polling), receive() 返回消息
    //   飞书: 服务器推送 (webhook), 需要一个 HTTP 服务器来接收事件
    //   本示例中 FeishuChannel.receive() 始终返回空 (暂未实现 webhook 服务器),
    //   只实现了 send() 用于主动发送消息. 完整实现需要添加 HTTP 服务器接收回调.
    //
    // [飞书认证机制] 飞书 API 使用 tenant_access_token (企业自建应用):
    //   1. 用 app_id + app_secret 换取 token
    //   2. token 有效期 2 小时, 过期前 5 分钟自动刷新
    //   3. volatile 关键字确保多线程可见性 (后台刷新线程写入, 发送线程读取)

    static class FeishuChannel implements Channel {
        private final String accountId;
        private final String appId;
        private final String appSecret;
        private final String botOpenId;
        private final String apiBase;
        private final HttpClient http;
        private volatile String tenantToken = "";
        private volatile double tokenExpiresAt = 0;

        FeishuChannel(ChannelAccount account) {
            this.accountId = account.accountId();
            Map<String, Object> cfg = account.config();
            this.appId = (String) cfg.getOrDefault("app_id", "");
            this.appSecret = (String) cfg.getOrDefault("app_secret", "");
            this.botOpenId = (String) cfg.getOrDefault("bot_open_id", "");
            boolean isLark = "true".equals(String.valueOf(cfg.getOrDefault("is_lark", "false")));
            this.apiBase = isLark ? "https://open.larksuite.com/open-apis"
                    : "https://open.feishu.cn/open-apis";
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
        }

        /**
         * 获取或刷新飞书 tenant_access_token.
         *
         * [为什么用 volatile?] tenantToken 和 tokenExpiresAt 被多个线程访问:
         *   - 本方法可能被发送线程调用, 也可能被后台刷新线程调用
         *   - volatile 确保 一个线程写入后, 其他线程立即可见
         *   [教学要点] volatile 只保证可见性, 不保证原子性. 这里是"检查-然后-行动"模式,
         *     如果多个线程同时发现 token 过期, 可能会重复刷新, 但这对飞书 API 是安全的
         *     (多次获取 token 不会导致错误, 只是多了一次网络请求).
         */
        String refreshToken() {
            if (!tenantToken.isEmpty() && System.currentTimeMillis() / 1000.0 < tokenExpiresAt) {
                return tenantToken;
            }
            try {
                String body = com.claw0.common.JsonUtils.toJson(
                        Map.of("app_id", appId, "app_secret", appSecret));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(apiBase + "/auth/v3/tenant_access_token/internal"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> data = com.claw0.common.JsonUtils.toMap(resp.body());
                if (!Number.class.cast(data.getOrDefault("code", -1)).equals(0)) {
                    System.out.println("  " + AnsiColors.RED + "[feishu] Token error: "
                            + data.getOrDefault("msg", "?") + AnsiColors.RESET);
                    return "";
                }
                tenantToken = (String) data.getOrDefault("tenant_access_token", "");
                int expire = ((Number) data.getOrDefault("expire", 7200)).intValue();
                tokenExpiresAt = System.currentTimeMillis() / 1000.0 + expire - 300;
                return tenantToken;
            } catch (Exception e) {
                System.out.println("  " + AnsiColors.RED + "[feishu] Token error: "
                        + e.getMessage() + AnsiColors.RESET);
                return "";
            }
        }

        /** Parse Feishu event callback. */
        @SuppressWarnings("unchecked")
        Optional<InboundMessage> parseEvent(Map<String, Object> payload) {
            if (payload.containsKey("challenge")) return Optional.empty();
            Map<String, Object> event = (Map<String, Object>) payload.getOrDefault("event", Map.of());
            Map<String, Object> message = (Map<String, Object>) event.getOrDefault("message", Map.of());
            Map<String, Object> sender = (Map<String, Object>)
                    ((Map<String, Object>) event.getOrDefault("sender", Map.of()))
                            .getOrDefault("sender_id", Map.of());
            String userId = (String) sender.getOrDefault("open_id",
                    sender.getOrDefault("user_id", ""));
            String chatId = (String) message.getOrDefault("chat_id", "");
            String chatType = (String) message.getOrDefault("chat_type", "");
            boolean isGroup = "group".equals(chatType);

            // Parse content
            String msgType = (String) message.getOrDefault("msg_type", "text");
            Object rawContent = message.getOrDefault("content", "{}");
            Map<String, Object> content;
            try {
                content = rawContent instanceof String s
                        ? com.claw0.common.JsonUtils.toMap(s)
                        : (Map<String, Object>) rawContent;
            } catch (Exception e) { return Optional.empty(); }

            String text = "";
            if ("text".equals(msgType)) {
                text = (String) content.getOrDefault("text", "");
            }
            if (text.isEmpty()) return Optional.empty();

            String peerId = "p2p".equals(chatType) ? userId : chatId;
            return Optional.of(new InboundMessage(text, userId, "feishu",
                    accountId, peerId, isGroup, List.of(), payload));
        }

        @Override public String name() { return "feishu"; }

        @Override public Optional<InboundMessage> receive() { return Optional.empty(); }

        @Override
        public boolean send(String to, String text) {
            String token = refreshToken();
            if (token.isEmpty()) return false;
            try {
                Map<String, Object> body = Map.of(
                        "receive_id", to,
                        "msg_type", "text",
                        "content", com.claw0.common.JsonUtils.toJson(Map.of("text", text)));
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(apiBase + "/im/v1/messages?receive_id_type=chat_id"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                com.claw0.common.JsonUtils.toJson(body)))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                Map<String, Object> data = com.claw0.common.JsonUtils.toMap(resp.body());
                if (!Number.class.cast(data.getOrDefault("code", -1)).equals(0)) {
                    System.out.println("  " + AnsiColors.RED + "[feishu] Send: "
                            + data.getOrDefault("msg", "?") + AnsiColors.RESET);
                    return false;
                }
                return true;
            } catch (Exception e) {
                System.out.println("  " + AnsiColors.RED + "[feishu] Send: "
                        + e.getMessage() + AnsiColors.RESET);
                return false;
            }
        }
    }

    // ================================================================
    // 记忆工具 (Memory Tools) -- MEMORY.md 持久化存储 + 每日 JSONL 日志
    // ================================================================
    // [为什么需要记忆工具?] LLM 本身是无状态的, 每次请求都是"失忆"状态.
    // 记忆工具让 agent 可以把重要信息写入文件, 下次对话时再搜索回来.
    // 这是一种简单但有效的"长期记忆"实现方案:
    //   - MEMORY.md: Markdown 格式, 人眼可读, 适合查看和手动编辑
    //   - 每日 JSONL: 结构化日志, 适合程序化检索和统计分析
    // [教学要点] 更高级的记忆方案包括: 向量数据库 (embedding + similarity search)、
    //   知识图谱、递归摘要等. 本方案选择文件系统是因为零依赖、易于理解.

    static String toolMemoryWrite(Map<String, Object> input) {
        String content = (String) input.get("content");
        AnsiColors.printTool("memory_write", content.length() + " chars");
        try {
            Files.createDirectories(MEMORY_FILE.getParent());
            // Append to MEMORY.md (persistent)
            Files.writeString(MEMORY_FILE, "\n- " + content + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            // Append to daily JSONL
            Path dailyDir = WORKSPACE_DIR.resolve(".memory");
            Files.createDirectories(dailyDir);
            String today = java.time.LocalDate.now().toString();
            Map<String, Object> record = Map.of(
                    "content", content, "ts", Instant.now().toString());
            com.claw0.common.JsonUtils.appendJsonl(dailyDir.resolve(today + ".jsonl"), record);
            return "Written to memory: " + content.substring(0, Math.min(80, content.length())) + "...";
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    static String toolMemorySearch(Map<String, Object> input) {
        String query = (String) input.get("query");
        AnsiColors.printTool("memory_search", query);
        if (!Files.exists(MEMORY_FILE)) return "Memory file is empty.";
        try {
            String lower = query.toLowerCase();
            List<String> matches = Files.readAllLines(MEMORY_FILE).stream()
                    .filter(line -> line.toLowerCase().contains(lower))
                    .limit(20)
                    .toList();
            return matches.isEmpty()
                    ? "No matches for '" + query + "'."
                    : String.join("\n", matches);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ================================================================
    // 工具 Schema 与分发 (Tool Schema + Dispatch)
    // ================================================================
    // [教学要点] LLM 工具调用的完整流程:
    //   1. 我们用 Tool 对象描述工具的名称、描述、参数 schema (JSON Schema 格式)
    //   2. 发送给 LLM 时, 工具定义随 API 请求一起发送
    //   3. LLM 决定调用工具时, 返回 ToolUseBlock (包含工具名和参数)
    //   4. 我们执行工具, 将结果作为 ToolResultBlockParam 送回 LLM
    //   5. TOOL_HANDLERS 是"工具名 -> 处理函数"的分发表, 解耦了定义和执行

    static Tool buildTool(String name, String description,
                          Map<String, Map<String, String>> properties,
                          List<String> required) {
        Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
        properties.forEach((key, value) ->
                propsBuilder.putAdditionalProperty(key, JsonValue.from(value)));
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object"))
                        .properties(propsBuilder.build())
                        .required(required).build())
                .build();
    }

    static final List<ToolUnion> TOOLS = List.of(
            ToolUnion.ofTool(buildTool("memory_write",
                    "Save a note to long-term memory.",
                    Map.of("content", Map.of("type", "string",
                            "description", "The text to remember.")),
                    List.of("content"))),
            ToolUnion.ofTool(buildTool("memory_search",
                    "Search through saved memory notes.",
                    Map.of("query", Map.of("type", "string",
                            "description", "Search keyword.")),
                    List.of("query")))
    );

    static final Map<String, Function<Map<String, Object>, String>> TOOL_HANDLERS = new LinkedHashMap<>();
    static {
        TOOL_HANDLERS.put("memory_write", S04Channels::toolMemoryWrite);
        TOOL_HANDLERS.put("memory_search", S04Channels::toolMemorySearch);
    }

    static String processToolCall(String toolName, Map<String, Object> toolInput) {
        var handler = TOOL_HANDLERS.get(toolName);
        if (handler == null) return "Error: Unknown tool '" + toolName + "'";
        try { return handler.apply(toolInput); }
        catch (Exception e) { return "Error: " + toolName + " failed: " + e.getMessage(); }
    }

    // ================================================================
    // 渠道管理器 (ChannelManager) -- 名称到渠道实例的注册表
    // ================================================================
    // [为什么需要 ChannelManager?] 随着渠道数量增加, 需要一个统一的地方:
    //   1. 注册所有渠道实例 (register)
    //   2. 按名称查找渠道 (get)
    //   3. 列出所有已注册渠道 (listChannels)
    //   4. 关闭时统一释放资源 (closeAll)
    // 这就是"注册表模式" (Registry Pattern) 的典型应用.

    static class ChannelManager {
        final Map<String, Channel> channels = new LinkedHashMap<>();
        final List<ChannelAccount> accounts = new ArrayList<>();

        void register(Channel channel) {
            channels.put(channel.name(), channel);
            System.out.println(AnsiColors.BLUE + "  [+] Channel registered: " + channel.name()
                    + AnsiColors.RESET);
        }

        List<String> listChannels() { return new ArrayList<>(channels.keySet()); }

        Channel get(String name) { return channels.get(name); }

        void closeAll() { channels.values().forEach(Channel::close); }
    }

    // ================================================================
    // Telegram 后台轮询 (Background Polling) -- 在独立线程中持续拉取 Telegram 消息
    // ================================================================
    // [为什么需要后台线程?] Telegram 长轮询会阻塞 30 秒, 如果在主线程执行,
    //   CLI 用户在这 30 秒内无法输入. 所以轮询必须在独立线程中运行.
    // [并发安全] ConcurrentLinkedQueue 是无锁线程安全队列, 适合"单生产者-单消费者"场景:
    //   - 生产者: telegramPollLoop (后台线程)
    //   - 消费者: agentLoop 主循环 (主线程)

    static void telegramPollLoop(TelegramChannel tg, ConcurrentLinkedQueue<InboundMessage> queue,
                                  Set<?> stopFlag) {
        System.out.println(AnsiColors.BLUE + "  [telegram] Polling started for " + tg.accountId
                + AnsiColors.RESET);
        int backoff = 0;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<InboundMessage> msgs = tg.poll();
                queue.addAll(msgs);
                backoff = 0;
            } catch (Exception e) {
                System.out.println("  " + AnsiColors.RED + "[telegram] Poll error: "
                        + e.getMessage() + AnsiColors.RESET);
                backoff = Math.min(backoff + 1, 6); // 指数退避: 1s -> 2s -> 4s -> 8s -> 16s -> 32s, 最大约 64s
                try { Thread.sleep((long) Math.pow(2, backoff) * 1000); }
                catch (InterruptedException ie) { break; }
            }
        }
    }

    // ================================================================
    // REPL Commands
    // ================================================================

    static boolean handleReplCommand(String cmd, ChannelManager mgr) {
        cmd = cmd.strip().toLowerCase();
        if ("/channels".equals(cmd)) {
            for (String name : mgr.listChannels())
                System.out.println(AnsiColors.BLUE + "  - " + name + AnsiColors.RESET);
            return true;
        }
        if ("/accounts".equals(cmd)) {
            for (ChannelAccount acc : mgr.accounts) {
                String masked = acc.token().length() > 8
                        ? acc.token().substring(0, 8) + "..." : "(none)";
                System.out.println(AnsiColors.BLUE + "  - " + acc.channel() + "/"
                        + acc.accountId() + "  token=" + masked + AnsiColors.RESET);
            }
            return true;
        }
        if ("/help".equals(cmd) || "/h".equals(cmd)) {
            AnsiColors.printInfo("  /channels  /accounts  /help  quit/exit");
            return true;
        }
        return false;
    }

    // ================================================================
    // Agent 对话回合 (Agent Turn) -- 执行一次完整的 agent 对话回合, 包含工具调用循环
    // ================================================================
    // [核心流程]
    //   1. 根据入站消息的渠道/账号/对端 ID 构建会话键, 找到或创建会话
    //   2. 将用户消息加入会话历史
    //   3. 调用 LLM API 获取响应
    //   4. 如果响应包含工具调用 (TOOL_USE), 执行工具并把结果送回 LLM, 重复步骤 3
    //   5. 如果响应是最终回复 (END_TURN), 通过对应渠道发送给用户
    //
    // [教学要点] 这里的 while(true) 循环就是"ReAct 模式" (Reason-Act) 的实现:
    //   - Reason: LLM 生成文本或工具调用
    //   - Act: 如果是工具调用, 执行工具获取结果
    //   - 循环直到 LLM 给出最终回复
    // 最多循环次数没有显式限制, 依赖 LLM 的 maxTokens 和自然终止.

    static void runAgentTurn(InboundMessage inbound,
                            Map<String, List<MessageParam>> conversations,
                            ChannelManager mgr) {
        String sk = buildSessionKey(inbound.channel(), inbound.accountId(), inbound.peerId());
        conversations.putIfAbsent(sk, new ArrayList<>());
        List<MessageParam> messages = conversations.get(sk);

        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(inbound.text())
                .build());

        // Telegram "正在输入" 指示器: 让用户知道 bot 正在处理, 改善等待体验
        // [为什么只对 Telegram?] CLI 模式下用户可以看到终端输出, 不需要提示.
        // 飞书渠道暂未实现 (需要调用飞书的类似 API).
        if ("telegram".equals(inbound.channel())) {
            Channel tg = mgr.get("telegram");
            if (tg instanceof TelegramChannel tgc) tgc.sendTyping(inbound.peerId());
        }

        while (true) {
            Message response;
            try {
                response = client.messages().create(MessageCreateParams.builder()
                        .model(MODEL_ID).maxTokens(8096)
                        .system(SYSTEM_PROMPT)
                        .tools(TOOLS)
                        .messages(messages)
                        .build());
            } catch (Exception e) {
                System.out.println("\n" + AnsiColors.YELLOW + "API Error: "
                        + e.getMessage() + AnsiColors.RESET + "\n");
                // [回滚策略] API 出错时, 移除刚才添加的 assistant 响应和用户消息,
                // 保持会话历史的一致性 -- 下次重试时 LLM 不会看到一段残缺的对话
                while (!messages.isEmpty()
                        && messages.get(messages.size() - 1).role() != MessageParam.Role.USER) {
                    messages.remove(messages.size() - 1);
                }
                if (!messages.isEmpty()) messages.remove(messages.size() - 1);
                return;
            }

            messages.add(response.toParam());
            StopReason reason = response.stopReason().orElse(null);

            if (reason == StopReason.END_TURN) {
                String text = response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
                if (!text.isEmpty()) {
                    Channel ch = mgr.get(inbound.channel());
                    if (ch != null) ch.send(inbound.peerId(), text);
                    else AnsiColors.printAssistant(text);
                }
                break;
            } else if (reason == StopReason.TOOL_USE) {
                List<ContentBlockParam> toolResultBlocks = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (!block.isToolUse()) continue;
                    ToolUseBlock tu = block.asToolUse();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> toolInput = tu._input().convert(Map.class);
                    String result = processToolCall(tu.name(), toolInput);
                    toolResultBlocks.add(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(tu.id()).content(result).build()));
                }
                messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(toolResultBlocks)
                        .build());
                continue;
            } else {
                String text = response.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());
                if (!text.isEmpty()) {
                    Channel ch = mgr.get(inbound.channel());
                    if (ch != null) ch.send(inbound.peerId(), text);
                }
                break;
            }
        }
    }

    // ================================================================
    // 主循环 (Main Loop) -- agent 核心循环, 处理多渠道输入
    // ================================================================
    // [架构要点] agent 核心循环与 S02 相同, 只是增加了消息队列处理多渠道输入:
    //   1. 检查 Telegram 消息队列 (由后台轮询线程填充)
    //   2. 如果有 Telegram 消息, 立即处理
    //   3. 如果没有 Telegram 消息, 检查 CLI 输入 (stdin)
    //   4. 将消息分发给 runAgentTurn 处理
    //
    // [教学要点] 注意并发模型:
    //   - Telegram 轮询在独立虚拟线程中运行, 通过 ConcurrentLinkedQueue 传递消息
    //   - 主线程既处理 Telegram 队列, 也处理 CLI 输入
    //   - 当 Telegram 激活时, CLI 使用 System.in.available() 做非阻塞检查,
    //     避免阻塞在 scanner.nextLine() 上导致 Telegram 消息无法及时处理

    static void agentLoop() {
        try { Files.createDirectories(STATE_DIR); } catch (IOException e) { /* ignore */ }

        ChannelManager mgr = new ChannelManager();
        CLIChannel cli = new CLIChannel();
        mgr.register(cli);

        TelegramChannel tgChannel = null;
        ConcurrentLinkedQueue<InboundMessage> msgQueue = new ConcurrentLinkedQueue<>();
        Thread tgThread = null;

        String tgToken = Config.get("TELEGRAM_BOT_TOKEN", "");
        if (!tgToken.isBlank()) {
            ChannelAccount tgAcc = new ChannelAccount("telegram", "tg-primary", tgToken,
                    Map.of("allowed_chats", Config.get("TELEGRAM_ALLOWED_CHATS", "")));
            mgr.accounts.add(tgAcc);
            tgChannel = new TelegramChannel(tgAcc);
            mgr.register(tgChannel);
            TelegramChannel finalTg = tgChannel;
            tgThread = Thread.ofVirtual().name("telegram-poll").start(
                    () -> telegramPollLoop(finalTg, msgQueue, Set.of()));
        }

        String fsId = Config.get("FEISHU_APP_ID", "");
        String fsSecret = Config.get("FEISHU_APP_SECRET", "");
        if (!fsId.isBlank() && !fsSecret.isBlank()) {
            ChannelAccount fsAcc = new ChannelAccount("feishu", "feishu-primary", "",
                    Map.of("app_id", fsId, "app_secret", fsSecret,
                            "encrypt_key", Config.get("FEISHU_ENCRYPT_KEY", ""),
                            "bot_open_id", Config.get("FEISHU_BOT_OPEN_ID", ""),
                            "is_lark", Config.get("FEISHU_IS_LARK", "false")));
            mgr.accounts.add(fsAcc);
            mgr.register(new FeishuChannel(fsAcc));
        }

        AnsiColors.printInfo("============================================================");
        AnsiColors.printInfo("  claw0  |  Section 04: Channels");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("  Channels: " + String.join(", ", mgr.listChannels()));
        AnsiColors.printInfo("  Commands: /channels /accounts /help  |  quit/exit");
        AnsiColors.printInfo("============================================================");
        System.out.println();

        Map<String, List<MessageParam>> conversations = new LinkedHashMap<>();
        // [为什么用 LinkedHashMap?] 保持插入顺序, 遍历时按会话创建时间排列,
        // 方便调试时查看会话列表. 同时 O(1) 的 get/put 满足性能要求.

        while (true) {
            // 排空 Telegram 消息队列: 先处理所有积压的 Telegram 消息, 再处理 CLI
            // [为什么先处理 Telegram?] Telegram 消息是异步到达的, 如果不优先排空队列,
            // 消息会越积越多, 导致用户等待时间过长.
            InboundMessage tgMsg;
            while ((tgMsg = msgQueue.poll()) != null) {
                System.out.println(AnsiColors.BLUE + "\n  [telegram] " + tgMsg.senderId()
                        + ": " + tgMsg.text().substring(0, Math.min(80, tgMsg.text().length()))
                        + AnsiColors.RESET);
                runAgentTurn(tgMsg, conversations, mgr);
            }

            // CLI input (non-blocking when Telegram is active)
            if (tgChannel != null) {
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
                try { if (System.in.available() == 0) continue; }
                catch (IOException e) { break; }
            }

            Optional<InboundMessage> optMsg = cli.receive();
            if (optMsg.isEmpty()) break;
            String userInput = optMsg.get().text();

            if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) break;
            if (userInput.startsWith("/") && handleReplCommand(userInput, mgr)) continue;

            runAgentTurn(optMsg.get(), conversations, mgr);
        }

        AnsiColors.printInfo("Goodbye.");
        if (tgThread != null) tgThread.interrupt();
        mgr.closeAll();
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
        agentLoop();
    }
}
