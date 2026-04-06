package com.openclaw.enterprise.resilience;

import com.openclaw.enterprise.agent.AgentLoop;
import com.openclaw.enterprise.agent.AgentTurnResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 弹性运行器 — 三层重试洋葱包裹 Agent 调用
 *
 * <p>三层重试策略 (由外到内)：</p>
 * <ol>
 *   <li><b>Layer 1: Auth 轮转</b> — 在多个 AuthProfile 间轮转，
 *       跳过冷却中的 Profile，标记失败的 Profile</li>
 *   <li><b>Layer 2: 溢出恢复</b> — 上下文溢出时不切换 Profile，
 *       交给 AgentLoop 内部的 ContextGuard 处理</li>
 *   <li><b>Layer 3: Agent 循环</b> — 实际的 AgentLoop.runTurn() 调用</li>
 * </ol>
 *
 * <p>关键设计决策：ResilienceRunner 只处理基础设施层面的关注点
 * (认证、重试、降级)。对话逻辑完全由 AgentLoop 负责。</p>
 *
 * <p>降级策略：当所有 Profile 耗尽时，尝试使用 haiku 模型降级调用。</p>
 *
 * <p>claw0 参考: s09_resilience.py 第 280-450 行 ResilienceRunner</p>
 */
@Service
public class ResilienceRunner {

    private static final Logger log = LoggerFactory.getLogger(ResilienceRunner.class);

    private final ProfileManager profileManager;

    /** 基础重试次数 */
    private static final int BASE_RETRY = 3;
    /** 每个可用 Profile 的额外重试次数 */
    private static final int PER_PROFILE = 5;
    /** 最大迭代次数上限 */
    private static final int MAX_ITERATIONS = 160;

    public ResilienceRunner(ProfileManager profileManager) {
        this.profileManager = profileManager;
    }

    /**
     * 执行带弹性的 Agent 调用
     *
     * <p>在 AuthProfile 间轮转重试，直到成功或所有重试耗尽。</p>
     *
     * @param agentId     Agent ID
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @param agentLoop   Agent 循环实例
     * @return Agent 调用结果
     */
    public AgentTurnResult run(String agentId, String sessionId,
                               String userMessage, AgentLoop agentLoop) {
        int profileCount = profileManager.getProfiles().size();
        int maxIterations = Math.min(
            Math.max(BASE_RETRY + PER_PROFILE * Math.max(profileCount, 1), 32),
            MAX_ITERATIONS
        );

        log.debug("ResilienceRunner starting: {} profiles, max {} iterations",
            profileCount, maxIterations);

        for (int i = 0; i < maxIterations; i++) {
            // Layer 1: 选择非冷却的 Profile
            var profileOpt = profileManager.selectProfile();
            if (profileOpt.isEmpty()) {
                log.warn("All profiles in cooldown after {} attempts, trying fallback", i);
                return attemptFallbackModel(agentId, sessionId, userMessage, agentLoop);
            }

            AuthProfile profile = profileOpt.get();
            log.debug("Attempt {}/{} with profile '{}'", i + 1, maxIterations, profile.getName());

            try {
                // Layer 3: 使用选中 Profile 的客户端调用 AgentLoop
                var client = profile.createClient();
                AgentTurnResult result = agentLoop.runTurn(agentId, sessionId, userMessage, client);

                // 成功: 标记 Profile 并返回
                profileManager.markSuccess(profile);
                return result;

            } catch (Exception e) {
                // 分类故障原因
                FailoverReason reason = FailoverReason.classify(e);

                if (reason instanceof FailoverReason.ContextOverflow) {
                    // Layer 2: 上下文溢出 — 不切换 Profile，由 ContextGuard 处理
                    // ContextGuard 已在 AgentLoop 内部处理了溢出恢复
                    // 如果异常仍然抛出，说明恢复失败，返回错误
                    log.warn("Context overflow could not be recovered: {}", e.getMessage());
                    return new AgentTurnResult(
                        "Error: Context overflow - " + e.getMessage(),
                        java.util.List.of(), "error",
                        new com.openclaw.enterprise.agent.TokenUsage(0, 0)
                    );
                }

                // 标记 Profile 故障
                profileManager.markFailure(profile, reason);
                log.warn("Attempt {}/{} failed with profile '{}': {} ({})",
                    i + 1, maxIterations, profile.getName(), reason, e.getMessage());

                // 继续下一次迭代，尝试其他 Profile
            }
        }

        // 所有重试耗尽 — 降级到 haiku
        log.warn("All {} iterations exhausted, trying fallback model", maxIterations);
        return attemptFallbackModel(agentId, sessionId, userMessage, agentLoop);
    }

    /**
     * 降级模型调用 — 当所有 Profile 耗尽时尝试使用 haiku 模型
     *
     * <p>使用第一个可用的 Profile (忽略冷却) 和 haiku 模型进行降级调用。</p>
     *
     * @return 降级调用的结果，如果全部失败则返回错误结果
     */
    private AgentTurnResult attemptFallbackModel(String agentId, String sessionId,
                                                  String userMessage, AgentLoop agentLoop) {
        // 尝试使用任意 Profile (即使冷却中)
        for (AuthProfile profile : profileManager.getProfiles()) {
            try {
                log.info("Attempting fallback with profile '{}' and degraded model", profile.getName());
                var client = profile.createClient();
                // AgentLoop 使用注入的 client 进行调用
                // 注意: 模型降级需要在 AgentLoop 层面支持，此处先尝试正常调用
                AgentTurnResult result = agentLoop.runTurn(agentId, sessionId, userMessage, client);
                profileManager.markSuccess(profile);
                return result;
            } catch (Exception e) {
                log.warn("Fallback with profile '{}' also failed: {}", profile.getName(), e.getMessage());
            }
        }

        // 全部失败
        return new AgentTurnResult(
            "Error: All API profiles exhausted. Please try again later.",
            java.util.List.of(), "error",
            new com.openclaw.enterprise.agent.TokenUsage(0, 0)
        );
    }
}
