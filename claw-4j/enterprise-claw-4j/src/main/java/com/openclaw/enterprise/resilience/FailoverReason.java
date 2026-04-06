package com.openclaw.enterprise.resilience;

/**
 * 故障切换原因 — 描述 API 调用失败的具体类型
 *
 * <p>六种故障类型及其冷却时间：</p>
 * <ul>
 *   <li>{@link RateLimit} — HTTP 429，冷却 120 秒</li>
 *   <li>{@link AuthError} — HTTP 401/403，冷却 300 秒</li>
 *   <li>{@link Timeout} — 请求超时，冷却 60 秒</li>
 *   <li>{@link Billing} — 计费/额度问题，冷却 300 秒</li>
 *   <li>{@link ContextOverflow} — 上下文溢出，无需冷却 (进入压缩流程)</li>
 *   <li>{@link Unknown} — 未知错误，冷却 120 秒</li>
 * </ul>
 *
 * <p>claw0 参考: s09_resilience.py 第 60-120 行 FailoverReason</p>
 */
public sealed interface FailoverReason
    permits FailoverReason.RateLimit, FailoverReason.AuthError,
            FailoverReason.Timeout, FailoverReason.Billing,
            FailoverReason.ContextOverflow, FailoverReason.Unknown {

    /**
     * 获取此故障类型的冷却时间 (秒)
     */
    long cooldownSeconds();

    /**
     * HTTP 429 — 速率限制
     */
    record RateLimit() implements FailoverReason {
        @Override public long cooldownSeconds() { return 120; }
    }

    /**
     * HTTP 401/403 — 认证/授权错误
     */
    record AuthError() implements FailoverReason {
        @Override public long cooldownSeconds() { return 300; }
    }

    /**
     * 请求超时
     */
    record Timeout() implements FailoverReason {
        @Override public long cooldownSeconds() { return 60; }
    }

    /**
     * 计费/额度问题
     */
    record Billing() implements FailoverReason {
        @Override public long cooldownSeconds() { return 300; }
    }

    /**
     * 上下文溢出 — 不需要冷却，进入压缩流程
     */
    record ContextOverflow() implements FailoverReason {
        @Override public long cooldownSeconds() { return 0; }
    }

    /**
     * 未知错误
     */
    record Unknown() implements FailoverReason {
        @Override public long cooldownSeconds() { return 120; }
    }

    /**
     * 根据异常自动分类故障原因
     *
     * <p>分类策略 (优先级递减)：</p>
     * <ol>
     *   <li>SDK 异常类型匹配</li>
     *   <li>HTTP 状态码检测</li>
     *   <li>异常消息文本匹配 (兜底)</li>
     * </ol>
     *
     * @param ex API 调用异常
     * @return 分类后的故障原因
     */
    static FailoverReason classify(Exception ex) {
        // 策略 1: HTTP 状态码检测
        int status = extractHttpStatus(ex);
        if (status > 0) {
            return switch (status) {
                case 429 -> new RateLimit();
                case 401, 403 -> new AuthError();
                case 402 -> new Billing();
                case 408, 504 -> new Timeout();
                default -> status >= 500 ? new Timeout() : new Unknown();
            };
        }

        // 策略 2: 异常消息文本匹配
        String msg = ex.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("rate limit") || lower.contains("429")) {
                return new RateLimit();
            }
            if (lower.contains("unauthorized") || lower.contains("forbidden")
                || lower.contains("401") || lower.contains("403")
                || lower.contains("authentication") || lower.contains("api key")) {
                return new AuthError();
            }
            if (lower.contains("timeout") || lower.contains("timed out")) {
                return new Timeout();
            }
            if (lower.contains("billing") || lower.contains("credit")
                || lower.contains("quota") || lower.contains("402")) {
                return new Billing();
            }
            if (lower.contains("context") && (lower.contains("overflow")
                || lower.contains("too long") || lower.contains("too many token"))) {
                return new ContextOverflow();
            }
            if (lower.contains("overloaded")) {
                return new RateLimit();
            }
        }

        // 兜底: 未知错误
        return new Unknown();
    }

    /**
     * 从异常中提取 HTTP 状态码
     *
     * <p>尝试两种途径：</p>
     * <ol>
     *   <li>通过反射调用 {@code statusCode()} 方法</li>
     *   <li>从消息文本中正则匹配 3 位状态码</li>
     * </ol>
     */
    private static int extractHttpStatus(Exception ex) {
        // 尝试反射调用 statusCode()
        try {
            var method = ex.getClass().getMethod("statusCode");
            Object result = method.invoke(ex);
            if (result instanceof Number num) {
                return num.intValue();
            }
        } catch (Exception ignored) {}

        // 尝试从 cause 中查找
        if (ex.getCause() != null) {
            try {
                var method = ex.getCause().getClass().getMethod("statusCode");
                Object result = method.invoke(ex.getCause());
                if (result instanceof Number num) {
                    return num.intValue();
                }
            } catch (Exception ignored) {}
        }

        // 正则匹配消息中的 3 位状态码
        String msg = ex.getMessage();
        if (msg != null) {
            var matcher = java.util.regex.Pattern.compile("\\b(4\\d{2}|5\\d{2})\\b").matcher(msg);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException ignored) {}
            }
        }

        return -1;
    }
}
