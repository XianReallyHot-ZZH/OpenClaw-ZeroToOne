package com.openclaw.enterprise.intelligence;

import java.time.Instant;

/**
 * 记忆条目记录 — 混合搜索的基本存储单元
 *
 * @param content   记忆内容
 * @param category  分类 (如 "preference", "fact", "context")
 * @param timestamp 创建时间
 * @param source    来源 ("daily" 或 "evergreen")
 * @param score     搜索相关度分数 (存储时为 0.0)
 */
public record MemoryEntry(
    String content,
    String category,
    Instant timestamp,
    String source,
    double score
) {}
