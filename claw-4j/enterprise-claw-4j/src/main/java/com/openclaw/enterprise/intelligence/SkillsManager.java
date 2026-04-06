package com.openclaw.enterprise.intelligence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 技能管理器 — 从多个目录发现和加载 SKILL.md 技能文件
 *
 * <p>技能发现路径 (优先级递增，后发现的同名技能覆盖先发现的)：</p>
 * <ol>
 *   <li>{@code workspace/skills/}</li>
 *   <li>{@code workspace/.skills/}</li>
 *   <li>{@code workspace/.agents/skills/}</li>
 * </ol>
 *
 * <p>SKILL.md 格式 (YAML frontmatter + Markdown body)：</p>
 * <pre>
 * ---
 * name: skill-name
 * description: What this skill does
 * version: 1.0
 * ---
 * Markdown body...
 * </pre>
 *
 * <p>claw0 参考: s06_intelligence.py 第 171-257 行 SkillsManager</p>
 */
@Service
public class SkillsManager {

    private static final Logger log = LoggerFactory.getLogger(SkillsManager.class);

    private static final int MAX_SKILLS = 150;
    private static final int MAX_TOTAL_CHARS = 30_000;

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    /**
     * 从指定目录列表发现技能
     *
     * @param dirs 目录列表 (优先级递增)
     */
    public void discover(List<Path> dirs) {
        skills.clear();
        // 使用 int[] 作为可变计数器，避免 lambda 中 "effectively final" 限制
        int[] totalChars = {0};

        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;

            try (Stream<Path> walk = Files.walk(dir, 3)) {
                walk.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .sorted()
                    .forEach(skillFile -> {
                        if (skills.size() >= MAX_SKILLS) return;
                        try {
                            Skill skill = parseSkill(skillFile);
                            if (skill != null) {
                                int bodyLen = skill.body().length();
                                if (totalChars[0] + bodyLen <= MAX_TOTAL_CHARS) {
                                    skills.put(skill.name(), skill);
                                    totalChars[0] += bodyLen;
                                }
                            }
                        } catch (Exception e) {
                            log.warn("Failed to parse skill: {}", skillFile, e);
                        }
                    });
            } catch (IOException e) {
                log.warn("Failed to scan skills dir: {}", dir, e);
            }
        }

        log.info("Discovered {} skills ({} chars)", skills.size(), totalChars[0]);
    }

    /**
     * 渲染技能为 Markdown 提示块
     */
    public String renderPromptBlock() {
        if (skills.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## Available Skills\n\n");
        for (Skill skill : skills.values()) {
            sb.append("### ").append(skill.name()).append("\n\n");
            if (skill.description() != null && !skill.description().isBlank()) {
                sb.append(skill.description()).append("\n\n");
            }
            sb.append(skill.body()).append("\n\n");
        }
        return sb.toString();
    }

    public Optional<Skill> getSkill(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    public List<Skill> listSkills() {
        return List.copyOf(skills.values());
    }

    private Skill parseSkill(Path skillFile) throws IOException {
        String content = Files.readString(skillFile);

        // 解析 YAML frontmatter
        Map<String, String> frontmatter = parseFrontmatter(content);
        String body = extractBody(content);

        String name = frontmatter.getOrDefault("name",
            skillFile.getParent().getFileName().toString());
        String description = frontmatter.getOrDefault("description", "");
        String version = frontmatter.getOrDefault("version", "1.0");

        return new Skill(name, description, version, body, skillFile);
    }

    private Map<String, String> parseFrontmatter(String content) {
        Map<String, String> result = new HashMap<>();
        if (!content.startsWith("---")) return result;

        int end = content.indexOf("---", 3);
        if (end <= 0) return result;

        String yaml = content.substring(3, end).trim();
        for (String line : yaml.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                // 去除引号
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                result.put(key, value);
            }
        }
        return result;
    }

    private String extractBody(String content) {
        if (!content.startsWith("---")) return content;
        int end = content.indexOf("---", 3);
        if (end <= 0) return content;
        return content.substring(end + 3).trim();
    }
}
