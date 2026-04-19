package com.claw0.sessions;

/**
 * Section 09: Resilience -- "When one call fails, rotate and retry."
 *
 * 三层重试洋葱包裹每次 agent 执行. 每一层处理不同类别的失败:
 *
 *     Layer 1 -- 认证轮换: 在 API key 配置之间轮转, 跳过冷却中的配置.
 *     Layer 2 -- 溢出恢复: 上下文溢出时压缩消息.
 *     Layer 3 -- 工具调用循环: 标准的 while True + stop_reason 分发.
 *
 *     Profiles: [main-key, backup-key, emergency-key]
 *          |
 *     for each non-cooldown profile:          LAYER 1: Auth Rotation
 *          |
 *     create client(profile.apiKey)
 *          |
 *     for compactAttempt in 0..2:             LAYER 2: Overflow Recovery
 *          |
 *     runAttempt(client, model, ...)          LAYER 3: Tool-Use Loop
 *          |              |
 *        success       exception
 *          |              |
 *     markSuccess    classifyFailure()
 *     return result       |
 *                    overflow? --> compact, retry Layer 2
 *                    auth/rate? -> markFailure, break to Layer 1
 *                    timeout?  --> markFailure(60s), break to Layer 1
 *                         |
 *                    all profiles exhausted?
 *                         |
 *                    try fallback models
 *
 * 用法:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S09Resilience"
 *
 * REPL 命令:
 *   /profiles  /cooldowns  /simulate-failure <reason>  /fallback  /stats  /help
 */

// region Common Imports
import com.claw0.common.AnsiColors;
import com.claw0.common.Config;
import com.claw0.common.Clients;
import com.claw0.common.JsonUtils;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

public class S09Resilience {

    // ================================================================
    // 配置常量
    // ================================================================

    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");
    static final int MAX_TOOL_OUTPUT = 50_000;
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));

    static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant with access to tools.\n"
                    + "Use tools to help the user with file operations and shell commands.\n"
                    + "Be concise.";

    // 重试限制
    // 基础迭代上限 24, 每个 profile 额外 8 次, 最终限制在 32-160 之间
    // 计算: max(BASE_RETRY + PER_PROFILE * numProfiles, 32) 取 min(..., 160)
    // 例如 3 个 profile: min(max(24+8*3, 32), 160) = min(48, 160) = 48
    static final int BASE_RETRY = 24;
    static final int PER_PROFILE = 8;

    /**
     * 压缩尝试上限.
     * 3 次压缩后仍然溢出, 说明问题无法通过压缩解决 (可能是单条消息就超限),
     * 此时放弃当前 profile, 尝试下一个.
     */
    static final int MAX_OVERFLOW_COMPACTION = 3;

    /**
     * 上下文安全上限 (token 数).
     * 安全上限 180K token: 留出余量防止因 token 估算不精确 (尤其中文文本约 1-2 字符/token,
     * 远高于英文的 4 字符/token) 导致溢出. Claude 的 200K 上下文中留出 20K 余量.
     */
    static final int CONTEXT_SAFE_LIMIT = 180_000;

    // ================================================================
    // region S09-A: FailoverReason -- API 调用失败原因分类
    // ================================================================

    /**
     * API 调用失败原因枚举.
     * 每种原因对应不同的重试策略和冷却时间.
     */
    enum FailoverReason {
        /** 速率限制 (429): 短冷却后尝试下一个配置 */
        RATE_LIMIT(120),
        /** 认证失败 (401/403): 跳过此配置 */
        AUTH(300),
        /** 请求超时: 短冷却后尝试下一个配置 */
        TIMEOUT(60),
        /** 余额不足 (402): 跳过此配置 */
        BILLING(300),
        /** 上下文溢出 (token 超限): 压缩消息后重试 */
        OVERFLOW(0),
        /** 未知错误: 默认策略 */
        UNKNOWN(120);

        /** 该原因的默认冷却时间 (秒) */
        final int cooldownSeconds;

        FailoverReason(int cooldownSeconds) {
            this.cooldownSeconds = cooldownSeconds;
        }
    }

    /**
     * 检查异常消息字符串以确定失败类别.
     *
     * 分类驱动重试行为:
     *   overflow   -> 压缩消息后用相同配置重试
     *   auth       -> 跳过此配置, 尝试下一个
     *   rate_limit -> 带冷却跳过此配置, 尝试下一个
     *   timeout    -> 短冷却后尝试下一个配置
     *   billing    -> 跳过此配置, 尝试下一个
     *   unknown    -> 跳过此配置, 尝试下一个
     */
    static FailoverReason classifyFailure(Exception exc) {
        String msg = exc.getMessage();
        if (msg == null) return FailoverReason.UNKNOWN;
        msg = msg.toLowerCase();

        if (msg.contains("rate") || msg.contains("429"))
            return FailoverReason.RATE_LIMIT;
        if (msg.contains("auth") || msg.contains("401") || msg.contains("key"))
            return FailoverReason.AUTH;
        if (msg.contains("timeout") || msg.contains("timed out"))
            return FailoverReason.TIMEOUT;
        if (msg.contains("billing") || msg.contains("quota") || msg.contains("402"))
            return FailoverReason.BILLING;
        if (msg.contains("context") || msg.contains("token") || msg.contains("overflow"))
            return FailoverReason.OVERFLOW;

        return FailoverReason.UNKNOWN;
    }

    // endregion S09-A: FailoverReason

    // ================================================================
    // region S09-A: AuthProfile -- 单个 API key 及其冷却追踪
    // ================================================================

    /**
     * 表示一个可轮换使用的 API key 配置.
     *
     * @param name            可读标签 (如 "main-key")
     * @param provider        LLM 提供商 (如 "anthropic")
     * @param apiKey          实际的 API key 字符串
     * @param cooldownUntil   冷却到期时间 (epoch 秒); 在此之前跳过
     * @param failureReason   上次失败原因字符串
     * @param lastGoodAt      上次成功调用的 epoch 秒
     */
    static class AuthProfile {
        final String name;
        final String provider;
        final String apiKey;
        volatile double cooldownUntil;
        volatile String failureReason;
        volatile double lastGoodAt;

        AuthProfile(String name, String provider, String apiKey) {
            this.name = name;
            this.provider = provider;
            this.apiKey = apiKey;
            this.cooldownUntil = 0.0;
            this.failureReason = null;
            this.lastGoodAt = 0.0;
        }
    }

    // endregion S09-A: AuthProfile

    // ================================================================
    // region S09-A: ProfileManager -- 选择、标记和列出配置
    // ================================================================

    /**
     * 管理 AuthProfile 池, 支持冷却感知的选择.
     * 按顺序检查配置, 当 epochSeconds >= cooldownUntil 时可用.
     */
    static class ProfileManager {
        final List<AuthProfile> profiles;

        ProfileManager(List<AuthProfile> profiles) {
            this.profiles = profiles;
        }

        /** 返回第一个冷却已过期的配置, 全部在冷却中则返回 null */
        AuthProfile selectProfile() {
            double now = epochSeconds();
            for (AuthProfile p : profiles) {
                if (now >= p.cooldownUntil) return p;
            }
            return null;
        }

        /** 在失败后将配置置入冷却 */
        void markFailure(AuthProfile profile, FailoverReason reason, double cooldownSeconds) {
            profile.cooldownUntil = epochSeconds() + cooldownSeconds;
            profile.failureReason = reason.name().toLowerCase();
            printResilience("Profile '" + profile.name + "' -> cooldown "
                    + String.format("%.0f", cooldownSeconds) + "s (reason: " + reason.name().toLowerCase() + ")");
        }

        /** 清除失败状态并记录上次成功时间 */
        void markSuccess(AuthProfile profile) {
            profile.failureReason = null;
            profile.lastGoodAt = epochSeconds();
        }

        /** 返回所有配置的状态 */
        List<Map<String, Object>> listProfiles() {
            double now = epochSeconds();
            List<Map<String, Object>> result = new ArrayList<>();
            for (AuthProfile p : profiles) {
                double remaining = Math.max(0, p.cooldownUntil - now);
                String status = remaining == 0 ? "available" : "cooldown (" + String.format("%.0f", remaining) + "s)";
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", p.name);
                info.put("provider", p.provider);
                info.put("status", status);
                info.put("failure_reason", p.failureReason);
                info.put("last_good", p.lastGoodAt > 0
                        ? Instant.ofEpochSecond((long) p.lastGoodAt).toString()
                        : "never");
                result.add(info);
            }
            return result;
        }
    }

    // endregion S09-A: ProfileManager

    // ================================================================
    // region S09-A: SimulatedFailure -- 模拟失败触发器
    // ================================================================

    /**
     * 持有一个待触发的模拟失败, 在下次 API 调用时触发.
     * REPL 命令 /simulate-failure <reason> 设置此标志,
     * 让用户无需真实故障即可观察三层洋葱如何处理各类失败.
     */
    static class SimulatedFailure {
        static final Map<String, String> TEMPLATES = Map.of(
                "rate_limit", "Error code: 429 -- rate limit exceeded",
                "auth", "Error code: 401 -- authentication failed, invalid API key",
                "timeout", "Request timed out after 30s",
                "billing", "Error code: 402 -- billing quota exceeded",
                "overflow", "Error: context window token overflow, too many tokens",
                "unknown", "Error: unexpected internal server error"
        );

        private String pending = null;

        /** 为下次 API 调用装备一个失败 */
        String arm(String reason) {
            if (!TEMPLATES.containsKey(reason)) {
                return "Unknown reason '" + reason + "'. Valid: " + String.join(", ", TEMPLATES.keySet());
            }
            pending = reason;
            return "Armed: next API call will fail with '" + reason + "'";
        }

        /** 如果已装备, 抛出模拟错误并解除装备 */
        void checkAndFire() {
            if (pending != null) {
                String reason = pending;
                pending = null;
                throw new RuntimeException(TEMPLATES.get(reason));
            }
        }

        boolean isArmed() { return pending != null; }
        String getPendingReason() { return pending; }
    }

    // endregion S09-A: SimulatedFailure

    // ================================================================
    // region S09-B: ContextGuard -- 简化的上下文溢出保护
    // ================================================================

    /**
     * 轻量级上下文溢出保护, 用于弹性运行器的 Layer 2.
     * 提供 token 估算和消息压缩.
     */
    static class ContextGuard {
        private final int maxTokens;

        ContextGuard() { this(CONTEXT_SAFE_LIMIT); }
        ContextGuard(int maxTokens) { this.maxTokens = maxTokens; }

        /**
         * 粗略估算: 每 4 个字符约 1 个 token.
         * 注意: 此估算对中文文本偏差较大 (中文约 1-2 字符/token), 但作为溢出检测的
         * 粗略阈值已经足够. 真正的溢出会被 API 返回的 overflow 错误捕获并触发压缩.
         * 这里只是提前预警, 避免浪费一次 API 调用.
         */
        static int estimateTokens(String text) {
            return text.length() / 4;
        }

        /**
         * 截断过大的 tool_result 块以减少上下文占用.
         *
         * 简化实现: 当前直接保留原消息, 未做实际截断.
         * 完整实现需要解析 MessageParam 内容 (可能是 TextBlock 或 ToolResultBlockParam),
         * 重建每个 MessageParam 并截断过大的 tool_result 内容.
         * 当前版本保留占位, 因为主要的溢出保护由 compactHistory() 承担,
         * truncateToolResults 只是补充手段.
         */
        List<MessageParam> truncateToolResults(List<MessageParam> messages) {
            int maxChars = (int) (maxTokens * 4 * 0.3);
            List<MessageParam> result = new ArrayList<>();
            for (MessageParam msg : messages) {
                // MessageParam 内容是不可变的, 简化处理: 直接保留原消息
                result.add(msg);
            }
            return result;
        }

        /**
         * 将前 50% 的消息压缩为 LLM 生成的摘要.
         * 保留最后 20% (至少 4 条) 的消息不变.
         */
        List<MessageParam> compactHistory(List<MessageParam> messages, AnthropicClient apiClient, String model) {
            int total = messages.size();
            if (total <= 4) return messages;

            int keepCount = Math.max(4, (int) (total * 0.2));
            int compressCount = Math.max(2, (int) (total * 0.5));
            compressCount = Math.min(compressCount, total - keepCount);
            if (compressCount < 2) return messages;

            List<MessageParam> oldMessages = messages.subList(0, compressCount);
            List<MessageParam> recentMessages = messages.subList(compressCount, total);

            // 将旧消息展平为纯文本
            StringBuilder sb = new StringBuilder();
            for (MessageParam msg : oldMessages) {
                String role = msg.role().toString().toLowerCase();
                // 提取文本内容 (简化: 只取 content 的字符串表示)
                sb.append("[").append(role).append("]: ");
                sb.append(msg.content().toString());
                sb.append("\n");
            }

            String summaryPrompt = "Summarize the following conversation concisely, "
                    + "preserving key facts and decisions. "
                    + "Output only the summary, no preamble.\n\n"
                    + sb;

            try {
                Message summaryResp = apiClient.messages().create(MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(2048)
                        .system("You are a conversation summarizer. Be concise and factual.")
                        .messages(List.of(MessageParam.builder()
                                .role(MessageParam.Role.USER)
                                .content(summaryPrompt)
                                .build()))
                        .build());

                String summaryText = summaryResp.content().stream()
                        .filter(ContentBlock::isText)
                        .map(ContentBlock::asText)
                        .map(TextBlock::text)
                        .collect(Collectors.joining());

                printResilience("Compacted " + oldMessages.size() + " messages -> summary ("
                        + summaryText.length() + " chars)");

                List<MessageParam> compacted = new ArrayList<>();
                compacted.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .content("[Previous conversation summary]\n" + summaryText)
                        .build());
                compacted.add(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .content("Understood, I have the context from our previous conversation.")
                        .build());
                compacted.addAll(recentMessages);
                return compacted;
            } catch (Exception e) {
                printWarn("Summary failed (" + e.getMessage() + "), dropping old messages");
                return new ArrayList<>(recentMessages);
            }
        }
    }

    // endregion S09-B: ContextGuard

    // ================================================================
    // region S01-S05 Core: 工具定义
    // ================================================================

    static Path safePath(String raw) {
        Path target = WORKDIR.resolve(raw).normalize().toAbsolutePath();
        if (!target.startsWith(WORKDIR.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("Path traversal blocked: " + raw);
        return target;
    }

    static String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        return text.substring(0, limit) + "\n... [truncated, " + text.length() + " total chars]";
    }

    static String toolBash(Map<String, Object> input) {
        String command = (String) input.get("command");
        int timeout = input.containsKey("timeout") ? ((Number) input.get("timeout")).intValue() : 30;
        AnsiColors.printTool("bash", command);

        List<String> dangerous = List.of("rm -rf /", "mkfs", "> /dev/sd", "dd if=");
        for (String pattern : dangerous) {
            if (command.contains(pattern))
                return "Error: Refused to run dangerous command containing '" + pattern + "'";
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command)
                    .directory(WORKDIR.toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String output = new String(proc.getInputStream().readAllBytes());
            if (!proc.waitFor(timeout, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return "Error: Command timed out after " + timeout + "s";
            }
            if (proc.exitValue() != 0) output += "\n[exit code: " + proc.exitValue() + "]";
            return output.isEmpty() ? "[no output]" : truncate(output, MAX_TOOL_OUTPUT);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    static String toolReadFile(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        AnsiColors.printTool("read_file", filePath);
        try {
            Path target = safePath(filePath);
            if (!Files.exists(target)) return "Error: File not found: " + filePath;
            return truncate(Files.readString(target), MAX_TOOL_OUTPUT);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
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
            ToolUnion.ofTool(buildTool("bash",
                    "Run a shell command and return its output.",
                    Map.of("command", Map.of("type", "string", "description", "The shell command to execute."),
                            "timeout", Map.of("type", "integer", "description", "Timeout in seconds. Default 30.")),
                    List.of("command"))),
            ToolUnion.ofTool(buildTool("read_file",
                    "Read the contents of a file.",
                    Map.of("file_path", Map.of("type", "string", "description", "Path to the file.")),
                    List.of("file_path")))
    );

    static final Map<String, java.util.function.Function<Map<String, Object>, String>> TOOL_HANDLERS = new LinkedHashMap<>();
    static {
        TOOL_HANDLERS.put("bash", S09Resilience::toolBash);
        TOOL_HANDLERS.put("read_file", S09Resilience::toolReadFile);
    }

    // endregion 工具定义

    // ================================================================
    // region S09-B: ResilienceRunner -- 三层重试洋葱
    // ================================================================

    /**
     * 执行 agent 回合, 带自动故障转移、压缩和重试.
     *
     * 三层嵌套:
     *   Layer 1 (最外层): 遍历 API key 配置, 跳过冷却中的配置.
     *   Layer 2 (中间层): 上下文溢出时压缩消息历史并重试.
     *   Layer 3 (最内层): 标准的工具调用循环.
     *
     * 如果所有配置耗尽, 尝试备选模型.
     */
    static class ResilienceRunner {
        private final ProfileManager profileManager;
        private final String modelId;
        private final List<String> fallbackModels;
        private final ContextGuard guard;
        private final SimulatedFailure simulatedFailure;
        private final int maxIterations;

        // 统计计数
        int totalAttempts = 0;
        int totalSuccesses = 0;
        int totalFailures = 0;
        int totalCompactions = 0;
        int totalRotations = 0;

        ResilienceRunner(ProfileManager profileManager, String modelId,
                         List<String> fallbackModels, ContextGuard guard,
                         SimulatedFailure simulatedFailure) {
            this.profileManager = profileManager;
            this.modelId = modelId;
            this.fallbackModels = fallbackModels != null ? fallbackModels : List.of();
            this.guard = guard;
            this.simulatedFailure = simulatedFailure;

            // 防止工具调用无限循环: 模型可能因 bug 或对抗性输入反复调用工具,
            // 此上限确保最终终止. 计算方式: 基础上限 24 + 每个 profile 8 次,
            // 最终限制在 32-160 之间 (太多浪费 API 额度, 太少可能截断正常的多步任务)
            int numProfiles = profileManager.profiles.size();
            this.maxIterations = Math.min(
                    Math.max(BASE_RETRY + PER_PROFILE * numProfiles, 32), 160);
        }

        /**
         * 执行三层重试洋葱.
         *
         * @param system  系统提示词
         * @param messages 消息历史
         * @param tools   工具列表
         * @return RunResult (finalText + updatedMessages)
         */
        RunResult run(String system, List<MessageParam> messages, List<ToolUnion> tools) {
            List<MessageParam> currentMessages = new ArrayList<>(messages);
            Set<String> profilesTried = new HashSet<>();

            // ---- LAYER 1: Auth Rotation ----
            for (int rotation = 0; rotation < profileManager.profiles.size(); rotation++) {
                AuthProfile profile = profileManager.selectProfile();
                if (profile == null) {
                    printWarn("All profiles on cooldown");
                    break;
                }
                if (profilesTried.contains(profile.name)) break;
                profilesTried.add(profile.name);

                if (profilesTried.size() > 1) {
                    totalRotations++;
                    printResilience("Rotating to profile '" + profile.name + "'");
                }

                // 为该 profile 创建客户端
                AnthropicClient apiClient = Clients.create(profile.apiKey);

                // ---- LAYER 2: Overflow Recovery ----
                List<MessageParam> layer2Messages = new ArrayList<>(currentMessages);
                for (int compactAttempt = 0; compactAttempt < MAX_OVERFLOW_COMPACTION; compactAttempt++) {
                    try {
                        totalAttempts++;

                        // 检查模拟失败
                        if (simulatedFailure != null) simulatedFailure.checkAndFire();

                        // ---- LAYER 3: Tool-Use Loop ----
                        RunResult result = runAttempt(apiClient, modelId, system, layer2Messages, tools);
                        profileManager.markSuccess(profile);
                        totalSuccesses++;
                        return result;

                    } catch (Exception exc) {
                        FailoverReason reason = classifyFailure(exc);
                        totalFailures++;

                        if (reason == FailoverReason.OVERFLOW) {
                            if (compactAttempt < MAX_OVERFLOW_COMPACTION - 1) {
                                totalCompactions++;
                                printResilience("Context overflow (attempt " + (compactAttempt + 1)
                                        + "/" + MAX_OVERFLOW_COMPACTION + "), compacting...");
                                layer2Messages = guard.compactHistory(layer2Messages, apiClient, modelId);
                                continue;
                            } else {
                                AnsiColors.printError("  Overflow not resolved after "
                                        + MAX_OVERFLOW_COMPACTION + " compaction attempts");
                                profileManager.markFailure(profile, reason, 600);
                                break;
                            }
                        } else if (reason == FailoverReason.AUTH || reason == FailoverReason.BILLING) {
                            profileManager.markFailure(profile, reason, 300);
                            break;
                        } else if (reason == FailoverReason.RATE_LIMIT) {
                            profileManager.markFailure(profile, reason, 120);
                            break;
                        } else if (reason == FailoverReason.TIMEOUT) {
                            profileManager.markFailure(profile, reason, 60);
                            break;
                        } else {
                            profileManager.markFailure(profile, reason, 120);
                            break;
                        }
                    }
                }
                // Layer 2 耗尽, 继续尝试下一个 profile
            }

            // ---- Fallback models ----
            if (!fallbackModels.isEmpty()) {
                printResilience("Primary profiles exhausted, trying fallback models...");
                for (String fallbackModel : fallbackModels) {
                    AuthProfile profile = profileManager.selectProfile();
                    if (profile == null) {
                        // 尝试重置 rate_limit/timeout 的冷却
                        for (AuthProfile p : profileManager.profiles) {
                            if ("rate_limit".equals(p.failureReason) || "timeout".equals(p.failureReason)) {
                                p.cooldownUntil = 0.0;
                            }
                        }
                        profile = profileManager.selectProfile();
                    }
                    if (profile == null) continue;

                    printResilience("Fallback: model='" + fallbackModel + "', profile='" + profile.name + "'");
                    AnthropicClient apiClient = Clients.create(profile.apiKey);

                    try {
                        totalAttempts++;
                        if (simulatedFailure != null) simulatedFailure.checkAndFire();
                        RunResult result = runAttempt(apiClient, fallbackModel, system, currentMessages, tools);
                        profileManager.markSuccess(profile);
                        totalSuccesses++;
                        return result;
                    } catch (Exception exc) {
                        FailoverReason reason = classifyFailure(exc);
                        totalFailures++;
                        printWarn("Fallback model '" + fallbackModel + "' failed: "
                                + reason.name().toLowerCase() + " -- " + exc.getMessage());
                    }
                }
            }

            throw new RuntimeException("All profiles and fallback models exhausted. "
                    + "Tried " + profilesTried.size() + " profiles, "
                    + fallbackModels.size() + " fallback models.");
        }

        /**
         * Layer 3: 标准工具调用循环.
         * 运行直到 end_turn 或报错. 任何 API 异常都向外层传播 (由 Layer 2 处理).
         *
         * 内循环步骤:
         *   步骤 1: 调用 Claude API, 获取本轮回复
         *   步骤 2: 将 assistant 回复加入消息历史
         *   步骤 3a: end_turn -> 提取文本, 返回结果
         *   步骤 3b: tool_use -> 执行工具, 追加结果, 继续循环
         *   步骤 3c: 其他 stop reason -> 提取已有文本, 返回
         *   超过 maxIterations 次迭代 -> 抛异常, 防止无限循环
         */
        RunResult runAttempt(AnthropicClient apiClient, String model,
                             String system, List<MessageParam> messages,
                             List<ToolUnion> tools) {
            List<MessageParam> currentMessages = new ArrayList<>(messages);
            int iteration = 0;

            while (iteration < maxIterations) {
                iteration++;
                // 步骤 1: 调用 Claude API
                Message response = apiClient.messages().create(MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(8096)
                        .system(system)
                        .tools(tools)
                        .messages(currentMessages)
                        .build());

                // 步骤 2: 将 assistant 回复加入消息历史
                currentMessages.add(response.toParam());
                StopReason reason = response.stopReason().orElse(null);

                if (StopReason.END_TURN.equals(reason)) {
                    // 步骤 3a: 正常结束 -- 提取文本并返回
                    String text = response.content().stream()
                            .filter(ContentBlock::isText)
                            .map(ContentBlock::asText)
                            .map(TextBlock::text)
                            .collect(Collectors.joining());
                    return new RunResult(text, currentMessages);
                } else if (StopReason.TOOL_USE.equals(reason)) {
                    // 步骤 3b: 工具调用 -- 遍历所有 ToolUseBlock, 逐个执行并收集结果
                    List<ContentBlockParam> toolResults = new ArrayList<>();
                    for (ContentBlock block : response.content()) {
                        if (!block.isToolUse()) continue;
                        ToolUseBlock tu = block.asToolUse();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> toolInput = tu._input().convert(Map.class);
                        java.util.function.Function<Map<String, Object>, String> handler =
                                TOOL_HANDLERS.get(tu.name());
                        String result = handler != null
                                ? handler.apply(toolInput)
                                : "Error: Unknown tool '" + tu.name() + "'";
                        toolResults.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId(tu.id())
                                        .content(result)
                                        .build()));
                    }
                    // 步骤 4: 将工具结果作为 user 消息追加, 继续下一轮循环
                    currentMessages.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(toolResults)
                            .build());
                } else {
                    // 步骤 3c: 其他 stop reason (max_tokens 等)
                    String text = response.content().stream()
                            .filter(ContentBlock::isText)
                            .map(ContentBlock::asText)
                            .map(TextBlock::text)
                            .collect(Collectors.joining());
                    return new RunResult(text, currentMessages);
                }
            }

            throw new RuntimeException("Tool-use loop exceeded " + maxIterations + " iterations");
        }

        Map<String, Object> getStats() {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("total_attempts", totalAttempts);
            stats.put("total_successes", totalSuccesses);
            stats.put("total_failures", totalFailures);
            stats.put("total_compactions", totalCompactions);
            stats.put("total_rotations", totalRotations);
            stats.put("max_iterations", maxIterations);
            return stats;
        }
    }

    /** 运行结果: 最终文本 + 更新后的消息历史 */
    record RunResult(String text, List<MessageParam> messages) {}

    // endregion S09-B: ResilienceRunner

    // ================================================================
    // 辅助方法
    // ================================================================

    static double epochSeconds() { return System.currentTimeMillis() / 1000.0; }

    static void printResilience(String text) {
        System.out.println("  " + AnsiColors.MAGENTA + "[resilience]" + AnsiColors.RESET + " " + text);
    }

    static void printWarn(String text) {
        System.out.println("  " + AnsiColors.YELLOW + "[warn]" + AnsiColors.RESET + " " + text);
    }

    // ================================================================
    // REPL 命令处理
    // ================================================================

    static boolean handleReplCommand(String userInput, ProfileManager pm,
                                      ResilienceRunner runner, SimulatedFailure sim) {
        String[] parts = userInput.strip().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1].strip() : "";

        switch (command) {
            case "/profiles" -> {
                List<Map<String, Object>> profiles = pm.listProfiles();
                AnsiColors.printInfo("  Profiles:");
                for (Map<String, Object> p : profiles) {
                    String statusColor = "available".equals(p.get("status"))
                            ? AnsiColors.GREEN : AnsiColors.YELLOW;
                    String line = "    " + String.format("%-16s", p.get("name"))
                            + statusColor + String.format("%-20s", p.get("status")) + AnsiColors.RESET
                            + "last_good=" + p.get("last_good");
                    if (p.get("failure_reason") != null) line += "  failure=" + p.get("failure_reason");
                    AnsiColors.printInfo(line);
                }
                return true;
            }
            case "/cooldowns" -> {
                double now = epochSeconds();
                boolean anyActive = false;
                AnsiColors.printInfo("  Active cooldowns:");
                for (AuthProfile p : pm.profiles) {
                    double remaining = Math.max(0, p.cooldownUntil - now);
                    if (remaining > 0) {
                        anyActive = true;
                        AnsiColors.printInfo("    " + p.name + ": "
                                + String.format("%.0f", remaining) + "s remaining (reason: "
                                + (p.failureReason != null ? p.failureReason : "unknown") + ")");
                    }
                }
                if (!anyActive) AnsiColors.printInfo("    No active cooldowns.");
                return true;
            }
            case "/simulate-failure" -> {
                if (arg.isEmpty()) {
                    AnsiColors.printInfo("  Usage: /simulate-failure <reason>");
                    AnsiColors.printInfo("  Valid reasons: " + String.join(", ", SimulatedFailure.TEMPLATES.keySet()));
                    if (sim.isArmed()) AnsiColors.printInfo("  Currently armed: " + sim.getPendingReason());
                    return true;
                }
                printResilience(sim.arm(arg));
                return true;
            }
            case "/fallback" -> {
                if (!runner.fallbackModels.isEmpty()) {
                    AnsiColors.printInfo("  Fallback model chain:");
                    for (int i = 0; i < runner.fallbackModels.size(); i++) {
                        AnsiColors.printInfo("    " + (i + 1) + ". " + runner.fallbackModels.get(i));
                    }
                } else {
                    AnsiColors.printInfo("  No fallback models configured.");
                }
                AnsiColors.printInfo("  Primary model: " + runner.modelId);
                return true;
            }
            case "/stats" -> {
                Map<String, Object> stats = runner.getStats();
                AnsiColors.printInfo("  Resilience stats:");
                AnsiColors.printInfo("    Attempts:    " + stats.get("total_attempts"));
                AnsiColors.printInfo("    Successes:   " + stats.get("total_successes"));
                AnsiColors.printInfo("    Failures:    " + stats.get("total_failures"));
                AnsiColors.printInfo("    Compactions: " + stats.get("total_compactions"));
                AnsiColors.printInfo("    Rotations:   " + stats.get("total_rotations"));
                AnsiColors.printInfo("    Max iter:    " + stats.get("max_iterations"));
                return true;
            }
            case "/help" -> {
                AnsiColors.printInfo("  Commands:");
                AnsiColors.printInfo("    /profiles               Show all profiles");
                AnsiColors.printInfo("    /cooldowns              Show active cooldowns");
                AnsiColors.printInfo("    /simulate-failure <r>   Arm simulated failure");
                AnsiColors.printInfo("    /fallback               Show fallback chain");
                AnsiColors.printInfo("    /stats                  Resilience statistics");
                AnsiColors.printInfo("    /help                   All commands");
                AnsiColors.printInfo("    quit / exit             Exit");
                return true;
            }
            default -> { return false; }
        }
    }

    // ================================================================
    // Agent Loop
    // ================================================================

    static void agentLoop() {
        String apiKey = Config.get("ANTHROPIC_API_KEY");

        // 创建演示 profiles (实际生产中每个 profile 使用不同的 key)
        List<AuthProfile> profiles = List.of(
                new AuthProfile("main-key", "anthropic", apiKey),
                new AuthProfile("backup-key", "anthropic", apiKey),
                new AuthProfile("emergency-key", "anthropic", apiKey)
        );

        ProfileManager pm = new ProfileManager(profiles);
        SimulatedFailure sim = new SimulatedFailure();
        ContextGuard guard = new ContextGuard();

        List<String> fallbackModels = List.of("claude-haiku-4-20250514");

        ResilienceRunner runner = new ResilienceRunner(pm, MODEL_ID, fallbackModels, guard, sim);

        List<MessageParam> messages = new ArrayList<>();

        AnsiColors.printInfo("================================================================");
        AnsiColors.printInfo("  claw0  |  Section 09: Resilience");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("  Profiles: " + profiles.stream().map(p -> p.name).collect(Collectors.joining(", ")));
        AnsiColors.printInfo("  Fallback: " + String.join(", ", fallbackModels));
        AnsiColors.printInfo("  Tools: " + String.join(", ", TOOL_HANDLERS.keySet()));
        AnsiColors.printInfo("  /help for commands. quit to exit.");
        AnsiColors.printInfo("================================================================");
        System.out.println();

        Scanner scanner = new Scanner(System.in);
        while (true) {
            String userInput;
            try {
                System.out.print(AnsiColors.coloredPrompt());
                userInput = scanner.nextLine().strip();
            } catch (Exception e) { break; }

            if (userInput.isEmpty()) continue;
            if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) break;

            // REPL 命令
            if (userInput.startsWith("/")) {
                if (handleReplCommand(userInput, pm, runner, sim)) continue;
                AnsiColors.printInfo("  Unknown command: " + userInput);
                continue;
            }

            // 用户对话
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userInput)
                    .build());

            try {
                RunResult result = runner.run(SYSTEM_PROMPT, messages, TOOLS);
                messages = new ArrayList<>(result.messages());
                if (!result.text().isEmpty()) AnsiColors.printAssistant(result.text());
            } catch (RuntimeException e) {
                AnsiColors.printError("  " + e.getMessage());
                // 回滚失败的用户消息
                while (!messages.isEmpty()
                        && messages.get(messages.size() - 1).role() != MessageParam.Role.USER)
                    messages.remove(messages.size() - 1);
                if (!messages.isEmpty()) messages.remove(messages.size() - 1);
            } catch (Exception e) {
                System.out.println("\n" + AnsiColors.YELLOW + "Unexpected error: " + e.getMessage()
                        + AnsiColors.RESET + "\n");
                while (!messages.isEmpty()
                        && messages.get(messages.size() - 1).role() != MessageParam.Role.USER)
                    messages.remove(messages.size() - 1);
                if (!messages.isEmpty()) messages.remove(messages.size() - 1);
            }
        }
    }

    // ================================================================
    // 入口
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
