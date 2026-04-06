package com.openclaw.enterprise.intelligence;

import java.nio.file.Path;

/**
 * 技能记录 — 描述一个已发现的技能
 *
 * <p>从 SKILL.md 文件的 YAML frontmatter 和 Markdown body 解析而来。</p>
 *
 * @param name        技能名称
 * @param description 技能描述
 * @param version     版本号
 * @param body        Markdown 正文内容
 * @param sourcePath  源文件路径
 */
public record Skill(
    String name,
    String description,
    String version,
    String body,
    Path sourcePath
) {}
