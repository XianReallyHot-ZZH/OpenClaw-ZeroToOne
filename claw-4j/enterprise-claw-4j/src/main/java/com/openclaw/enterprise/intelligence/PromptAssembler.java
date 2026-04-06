package com.openclaw.enterprise.intelligence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 系统提示组装器 — 8 层系统提示组装
 *
 * <p>将 BootstrapLoader 的文件内容、SkillsManager 的技能列表、
 * MemoryStore 的搜索结果和运行时上下文组装为完整的系统提示。</p>
 *
 * <p>8 层组装顺序：</p>
 * <ol>
 *   <li>IDENTITY.md → 身份定义</li>
 *   <li>SOUL.md → 性格特征</li>
 *   <li>TOOLS.md → 工具使用指南</li>
 *   <li>SkillsManager → 已发现技能</li>
 *   <li>MemoryStore + MEMORY.md → 相关记忆</li>
 *   <li>BOOTSTRAP.md + USER.md + AGENTS.md → 上下文信息</li>
 *   <li>运行时状态 → 当前时间、Agent、渠道</li>
 *   <li>渠道提示 → 平台特定格式建议</li>
 * </ol>
 *
 * <p>claw0 参考: s06_intelligence.py 第 636-708 行 build_system_prompt()</p>
 */
@Service
public class PromptAssembler {

    private static final Logger log = LoggerFactory.getLogger(PromptAssembler.class);

    private final BootstrapLoader bootstrapLoader;
    private final SkillsManager skillsManager;
    private final MemoryStore memoryStore;

    public PromptAssembler(BootstrapLoader bootstrapLoader,
                           SkillsManager skillsManager,
                           MemoryStore memoryStore) {
        this.bootstrapLoader = bootstrapLoader;
        this.skillsManager = skillsManager;
        this.memoryStore = memoryStore;
    }

    /**
     * 构建完整的系统提示
     *
     * @param agentId Agent ID
     * @param context 提示上下文
     * @return 完整的系统提示字符串
     */
    public String buildSystemPrompt(String agentId, PromptContext context) {
        Map<String, String> files = bootstrapLoader.loadAll(LoadMode.FULL);
        StringJoiner prompt = new StringJoiner("\n\n");

        // Layer 1: 身份定义
        appendLayer(prompt, files.get("IDENTITY.md"), "## Identity");

        // Layer 2: 性格特征
        appendLayer(prompt, files.get("SOUL.md"), "## Personality");

        // Layer 3: 工具使用指南
        appendLayer(prompt, files.get("TOOLS.md"), "## Tools Guide");

        // Layer 4: 已发现技能
        String skillsBlock = skillsManager.renderPromptBlock();
        if (!skillsBlock.isBlank()) {
            prompt.add(skillsBlock);
        }

        // Layer 5: 相关记忆
        StringBuilder memoryBlock = new StringBuilder();
        if (context.userMessage() != null && !context.userMessage().isBlank()) {
            var memories = memoryStore.hybridSearch(context.userMessage(), 3);
            if (!memories.isEmpty()) {
                memoryBlock.append("## Relevant Memories\n\n");
                for (var mem : memories) {
                    memoryBlock.append("- ").append(mem.content()).append("\n");
                }
            }
        }
        // 常青记忆
        bootstrapLoader.getFile("MEMORY.md").ifPresent(mem -> {
            if (memoryBlock.isEmpty()) {
                memoryBlock.append("## Relevant Memories\n\n");
            }
            memoryBlock.append("\n### Permanent Memory\n\n").append(mem);
        });
        if (!memoryBlock.isEmpty()) {
            prompt.add(memoryBlock.toString().trim());
        }

        // Layer 6: 上下文信息
        appendLayer(prompt, files.get("BOOTSTRAP.md"), "## Context");
        appendLayer(prompt, files.get("USER.md"), "## User Info");
        appendLayer(prompt, files.get("AGENTS.md"), "## Multi-Agent");

        // Layer 7: 运行时状态
        prompt.add("## Current Status\n\n"
            + "- Current time: " + Instant.now() + "\n"
            + "- Agent: " + agentId + "\n"
            + "- Channel: " + (context.channel() != null ? context.channel() : "unknown")
            + (context.isGroup() ? "\n- Group conversation" : ""));

        // Layer 8: 渠道提示
        String hint = getChannelHint(context.channel());
        if (!hint.isBlank()) {
            prompt.add(hint);
        }

        return prompt.toString();
    }

    private void appendLayer(StringJoiner prompt, String content, String header) {
        if (content != null && !content.isBlank()) {
            prompt.add(header + "\n\n" + content);
        }
    }

    private String getChannelHint(String channel) {
        if (channel == null) return "";
        return switch (channel) {
            case "telegram" -> "You are on Telegram. Use Markdown for formatting. Keep messages concise.";
            case "feishu" -> "You are on Feishu/Lark. Use plain text or rich text format.";
            case "cli" -> "You are in a CLI terminal. You can use ANSI formatting if helpful.";
            default -> "";
        };
    }
}
