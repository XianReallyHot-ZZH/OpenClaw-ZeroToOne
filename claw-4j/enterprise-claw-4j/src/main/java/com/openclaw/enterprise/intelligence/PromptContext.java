package com.openclaw.enterprise.intelligence;

/**
 * 提示上下文记录 — 描述当前对话的运行时环境
 *
 * @param channel     渠道名称 ("telegram", "feishu", "cli")
 * @param isGroup     是否群组对话
 * @param isHeartbeat 是否心跳触发
 * @param userMessage 用户消息文本 (用于记忆搜索)
 */
public record PromptContext(
    String channel,
    boolean isGroup,
    boolean isHeartbeat,
    String userMessage
) {}
