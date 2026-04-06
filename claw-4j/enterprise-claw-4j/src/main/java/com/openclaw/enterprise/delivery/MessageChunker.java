package com.openclaw.enterprise.delivery;

import java.util.*;

/**
 * 消息分块器 — 将超长消息按平台限制拆分为多个分块
 *
 * <p>不同平台有不同的单条消息字符上限：</p>
 * <ul>
 *   <li>Telegram: 4096 字符</li>
 *   <li>Discord: 2000 字符</li>
 *   <li>WhatsApp: 4096 字符</li>
 *   <li>CLI: 无限制 (Integer.MAX_VALUE)</li>
 * </ul>
 *
 * <p>拆分策略 (优先级递减)：</p>
 * <ol>
 *   <li>按段落拆分 ({@code "\n\n"})</li>
 *   <li>合并短段落</li>
 *   <li>超长段落硬切分</li>
 * </ol>
 *
 * <p>claw0 参考: s08_delivery.py 第 500-600 行 chunk_message()</p>
 */
public final class MessageChunker {

    /** 各平台单条消息字符上限 */
    private static final Map<String, Integer> PLATFORM_LIMITS = Map.of(
        "telegram", 4096,
        "telegram_caption", 1024,
        "discord", 2000,
        "whatsapp", 4096,
        "cli", Integer.MAX_VALUE
    );

    private MessageChunker() {} // 工具类，禁止实例化

    /**
     * 将消息按平台限制拆分为多个分块
     *
     * @param text     消息文本
     * @param platform 平台名称
     * @return 分块列表 (如果未超限则只包含一个元素)
     */
    public static List<String> chunk(String text, String platform) {
        int limit = PLATFORM_LIMITS.getOrDefault(platform, 4096);

        if (text.length() <= limit) {
            return List.of(text);
        }

        return splitByParagraph(text, limit);
    }

    /**
     * 按段落拆分 — 优先在 {@code "\n\n"} 处切分，合并短段落
     */
    private static List<String> splitByParagraph(String text, int limit) {
        String[] paragraphs = text.split("\n\n");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String para : paragraphs) {
            // 如果当前段落本身超限，先硬切分
            if (para.length() > limit) {
                // 先保存已有的积累内容
                if (!current.isEmpty()) {
                    chunks.add(current.toString().trim());
                    current.setLength(0);
                }
                // 硬切分超长段落
                chunks.addAll(hardSplit(para, limit));
            } else if (current.length() + para.length() + 2 > limit) {
                // 加入此段落会超限: 保存当前积累，开始新块
                if (!current.isEmpty()) {
                    chunks.add(current.toString().trim());
                }
                current = new StringBuilder(para);
            } else {
                // 合并段落
                if (!current.isEmpty()) {
                    current.append("\n\n");
                }
                current.append(para);
            }
        }

        // 保存最后一块
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }

        return chunks;
    }

    /**
     * 硬切分 — 在字符边界处切断超长文本
     */
    private static List<String> hardSplit(String text, int limit) {
        List<String> parts = new ArrayList<>();
        int offset = 0;

        while (offset < text.length()) {
            int end = Math.min(offset + limit, text.length());

            // 尝试在最近的换行符处切分 (避免从单词中间切断)
            if (end < text.length()) {
                int lastNewline = text.lastIndexOf('\n', end);
                if (lastNewline > offset) {
                    end = lastNewline;
                }
            }

            parts.add(text.substring(offset, end).trim());
            offset = end;
        }

        return parts;
    }
}
