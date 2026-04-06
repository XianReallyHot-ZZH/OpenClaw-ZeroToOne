package com.openclaw.enterprise.common.exceptions;

import com.openclaw.enterprise.common.Claw4jException;

/**
 * 上下文溢出异常 — 上下文 token 数超出预算且无法继续压缩
 *
 * <p>在 Agent 循环中，当经过多轮上下文压缩 (compaction) 后，
 * 估算的 token 数仍然超过上下文预算 (contextBudget) 时抛出。</p>
 *
 * <p>此异常触发 Resilience 层的 Context Overflow 恢复策略：</p>
 * <ol>
 *   <li>Stage 1: 压缩系统提示 (丢弃低优先级层)</li>
 *   <li>Stage 2: 截断会话历史 (保留最近 N 轮)</li>
 *   <li>Stage 3: 重置会话 (只保留系统提示和当前用户输入)</li>
 * </ol>
 *
 * <p>错误码: CONTEXT_OVERFLOW</p>
 *
 * <p>claw0 参考: s09_resilience.py 中 Context overflow 相关逻辑</p>
 */
public class ContextOverflowException extends Claw4jException {

    /** 溢出时的估算 token 数量 */
    private final int estimatedTokens;

    /** 配置的 token 预算上限 */
    private final int budget;

    /**
     * 构造上下文溢出异常
     *
     * <p>自动生成错误信息: "Context overflow after max compaction rounds:
     * estimated={estimatedTokens}, budget={budget}"</p>
     *
     * @param estimatedTokens 溢出时的估算 token 数
     * @param budget          配置的 token 预算
     */
    public ContextOverflowException(int estimatedTokens, int budget) {
        super("CONTEXT_OVERFLOW",
            "Context overflow after max compaction rounds: estimated="
                + estimatedTokens + ", budget=" + budget);
        this.estimatedTokens = estimatedTokens;
        this.budget = budget;
    }

    /**
     * 获取溢出时的估算 token 数
     *
     * @return 估算 token 数
     */
    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    /**
     * 获取配置的 token 预算
     *
     * @return token 预算
     */
    public int getBudget() {
        return budget;
    }
}
