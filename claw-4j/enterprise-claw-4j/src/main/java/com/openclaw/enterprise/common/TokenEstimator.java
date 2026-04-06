package com.openclaw.enterprise.common;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token 估算器 — 基于 heuristics 的 token 数量估算
 *
 * <p>由于不使用 Tiktoken 等精确分词器，采用以下启发式规则：</p>
 * <ul>
 *   <li>英文字符: 4 字符 ≈ 1 token</li>
 *   <li>CJK 字符 (中日韩): 1 字符 ≈ 1.5 token</li>
 *   <li>结果向上取整 (ceil)</li>
 * </ul>
 *
 * <p>提供两个层级的估算：</p>
 * <ul>
 *   <li>{@link #estimate(String)} — 纯文本 token 估算</li>
 *   <li>{@link #estimateMessages(List)} — 消息列表 token 估算，
 *       支持字符串内容和结构化内容块 (tool_use, tool_result 等)</li>
 * </ul>
 *
 * <p>claw0 参考: s03_sessions.py 第 342-370 行 estimate_tokens / estimate_messages_tokens</p>
 */
@Component
public class TokenEstimator {

    /**
     * 估算纯文本的 token 数量
     *
     * <p>遍历文本的每个字符，分别统计 ASCII 和 CJK 字符数量，
     * 然后按各自的系数计算 token 数。</p>
     *
     * <p>claw0 对应: estimate_tokens(text) — len(text) // 4 (简化版)</p>
     * <p>Java 版增强了 CJK 支持，因为中文场景下简单的字符数/4 严重低估。</p>
     *
     * @param text 要估算的文本，null 或空字符串返回 0
     * @return 估算的 token 数量
     */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        int asciiChars = 0;
        int cjkChars = 0;

        for (char c : text.toCharArray()) {
            if (isCJK(c)) {
                cjkChars++;
            } else {
                asciiChars++;
            }
        }

        // 英文: 4 字符 ≈ 1 token；CJK: 1 字符 ≈ 1.5 token；向上取整
        return (int) Math.ceil(asciiChars / 4.0 + cjkChars * 1.5);
    }

    /**
     * 估算消息列表的总 token 数量
     *
     * <p>遍历消息列表中的每条消息，处理不同类型的内容：</p>
     * <ul>
     *   <li>字符串内容 — 直接估算文本</li>
     *   <li>内容块列表 — 逐块处理：
     *     <ul>
     *       <li>TextBlock — 估算文本内容</li>
     *       <li>ToolUseBlock — 估算 JSON 输入</li>
     *       <li>ToolResultBlockParam — 估算工具结果文本</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>claw0 对应: s03_sessions.py estimate_messages_tokens() —
     * 遍历消息列表，处理 str 和 list[dict] 两种内容类型</p>
     *
     * @param messages 消息参数列表
     * @return 估算的总 token 数量
     */
    public int estimateMessages(List<MessageParam> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }

        int total = 0;

        for (MessageParam msg : messages) {
            // MessageParam 的 content 可能是 String 或 List<ContentBlockParam>
            Object content = msg.content();
            if (content instanceof String text) {
                total += estimate(text);
            } else if (content instanceof List<?> blocks) {
                total += estimateContentBlocks(blocks);
            }
        }

        return total;
    }

    /**
     * 估算内容块列表的 token 数量
     *
     * <p>处理不同类型的内容块：</p>
     * <ul>
     *   <li>TextBlock / 含 text 字段的块 — 估算文本内容</li>
     *   <li>ToolUseBlock — 估算工具调用的 JSON 输入</li>
     *   <li>ToolResultBlockParam — 估算工具结果</li>
     * </ul>
     *
     * @param blocks 内容块列表
     * @return 估算的 token 数量
     */
    private int estimateContentBlocks(List<?> blocks) {
        int total = 0;

        for (Object block : blocks) {
            if (block instanceof ToolUseBlock toolUse) {
                // 工具调用块 — 估算输入参数的 JSON
                // ToolUseBlock._input() 返回 JsonValue，通过 convert 转为 Map 后序列化
                var inputMap = toolUse._input().convert(new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                total += estimate(JsonUtils.toJson(inputMap));
            } else if (block instanceof ToolResultBlockParam toolResult) {
                // 工具结果块 — 估算结果文本
                total += estimate(toolResult.content().toString());
            } else if (block instanceof ContentBlock contentBlock) {
                // 通用内容块 — 尝试提取文本
                total += estimate(contentBlock.toString());
            } else {
                // 其他未知块类型 — 按字符串表示估算
                total += estimate(block.toString());
            }
        }

        return total;
    }

    /**
     * 判断字符是否为 CJK (中日韩) 字符
     *
     * <p>覆盖以下 Unicode 范围：</p>
     * <ul>
     *   <li>CJK Unified Ideographs: U+4E00 - U+9FFF</li>
     *   <li>CJK Unified Ideographs Extension A: U+3400 - U+4DBF</li>
     *   <li>CJK Compatibility Ideographs: U+F900 - U+FAFF</li>
     *   <li>Hiragana: U+3040 - U+309F</li>
     *   <li>Katakana: U+30A0 - U+30FF</li>
     *   <li>Hangul Syllables: U+AC00 - U+D7AF</li>
     * </ul>
     *
     * @param c 要判断的字符
     * @return 如果是 CJK 字符返回 true
     */
    private boolean isCJK(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')    // CJK Unified Ideographs
            || (c >= '\u3400' && c <= '\u4DBF')     // CJK Extension A
            || (c >= '\uF900' && c <= '\uFAFF')     // CJK Compatibility
            || (c >= '\u3040' && c <= '\u309F')     // Hiragana
            || (c >= '\u30A0' && c <= '\u30FF')     // Katakana
            || (c >= '\uAC00' && c <= '\uD7AF');    // Hangul Syllables
    }
}
