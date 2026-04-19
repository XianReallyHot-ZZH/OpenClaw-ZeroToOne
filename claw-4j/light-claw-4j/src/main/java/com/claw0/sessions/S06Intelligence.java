package com.claw0.sessions;

/**
 * Section 06: Intelligence (智能) -- "赋予灵魂, 教会记忆"
 *
 * 本节是整个教学项目的核心集成点, 演示系统提示词的分层构建过程.
 * 在 s01-s02 中, 系统提示词是硬编码的字符串;
 * 在真实的 agent 框架中, 系统提示词由多个层级动态组装:
 *   Identity / 灵魂 / Tools / 技能 / Memory / Bootstrap / Runtime / Channel
 *
 * 架构:
 *
 *     [SOUL.md]  [IDENTITY.md]  [TOOLS.md]  [MEMORY.md]  ...
 *          \          |            |           /
 *           v         v            v          v
 *         +-------------------------------+
 *         |     BootstrapLoader           |
 *         |  (load, truncate, cap)        |
 *         +-------------------------------+
 *                     |
 *                     v
 *         +-------------------------------+        +-------------------+
 *         |   buildSystemPrompt()         | <----> | SkillsManager     |
 *         |   (8 层组装)                  |        | (discover, parse) |
 *         +-------------------------------+        +-------------------+
 *                     |                                     ^
 *                     v                                     |
 *         +-------------------------------+        +-------------------+
 *         |   Agent Loop (每轮)           | <----> | MemoryStore       |
 *         |   search -> build -> call LLM |        | (write, search)   |
 *         +-------------------------------+        +-------------------+
 *
 * 用法:
 *   mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S06Intelligence"
 *
 * REPL 命令:
 *   /soul /skills /memory /search <q> /prompt /bootstrap
 */

// region Common Imports
import com.claw0.common.AnsiColors;
import com.claw0.common.Clients;
import com.claw0.common.Config;
import com.claw0.common.JsonUtils;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.core.JsonValue;
// endregion

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class S06Intelligence {

    // ================================================================
    // 配置常量
    // ================================================================

    /** 默认使用的模型 ID, 可通过环境变量 MODEL_ID 覆盖 */
    static final String MODEL_ID = Config.get("MODEL_ID", "claude-sonnet-4-20250514");

    /** 工具输出最大字符数, 超出则截断 */
    static final int MAX_TOOL_OUTPUT = 50_000;

    /** 工作目录 (项目根目录) */
    static final Path WORKDIR = Path.of(System.getProperty("user.dir"));

    /** workspace 目录, 存放所有配置和状态文件 */
    static final Path WORKSPACE_DIR = WORKDIR.resolve("workspace");

    /**
     * 单个 Bootstrap 文件最大字符数.
     * 单个文件上限: 约 5000 token (按 4 字符/token 估算), 超出截断.
     * 这是为了在有限的上下文窗口中为其他内容 (记忆/技能/对话) 留出空间,
     * 避免单个配置文件独占整个 prompt.
     */
    static final int MAX_FILE_CHARS = 20_000;

    /**
     * 所有 Bootstrap 文件总字符数上限.
     * 所有 Bootstrap 文件总上限: Claude 上下文约 200K token, 预留 50K 给对话历史和工具输出,
     * 其余分配给系统提示词. 此上限确保 Bootstrap 文件不会把对话空间挤占殆尽.
     * 150_000 字符 ≈ 37_500 token, 留出了足够的对话空间.
     */
    static final int MAX_TOTAL_CHARS = 150_000;

    /**
     * 最大技能数量.
     * 技能上限: 避免扫描过多目录影响启动速度. 150 是实际项目中的经验上限:
     * 技能过多时扫描耗时增加, 且系统提示词膨胀会导致模型在指令跟随时出现"注意力稀释".
     * 如需更多技能, 应使用按需加载而非全量注入.
     */
    static final int MAX_SKILLS = 150;

    /**
     * 技能 prompt 块最大字符数.
     * 技能提示词上限: 约 7500 token (按 4 字符/token 估算), 在上下文预算中的合理占比.
     * 技能注入只是系统提示词的一部分, 还需为 Identity/Soul/Tools/Memory 等层留出空间.
     */
    static final int MAX_SKILLS_PROMPT = 30_000;

    /**
     * Bootstrap 文件名列表 -- 每个 agent 启动时按此顺序加载这 8 个文件.
     * 越靠前的文件在系统提示词中影响力越强.
     */
    static final List<String> BOOTSTRAP_FILES = List.of(
            "SOUL.md", "IDENTITY.md", "TOOLS.md", "USER.md",
            "HEARTBEAT.md", "BOOTSTRAP.md", "AGENTS.md", "MEMORY.md"
    );

    /** Anthropic API 客户端, 通过 Config 读取 API Key 和 Base URL */
    static final AnthropicClient client = Clients.create();

    // ================================================================
    // region S01-S05 Core (从前序 Session 复制的核心代码)
    // ================================================================

    // --- 工具函数: 文件路径安全检查 ---

    /**
     * 安全路径解析: 确保路径在 workspace 目录内, 防止路径穿越攻击.
     * @param raw 用户输入的相对路径
     * @return workspace 下的绝对路径
     * @throws IllegalArgumentException 如果路径试图逃逸 workspace
     */
    static Path safePath(String raw) {
        Path target = WORKSPACE_DIR.resolve(raw).normalize().toAbsolutePath();
        if (!target.startsWith(WORKSPACE_DIR.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Path traversal blocked: " + raw);
        }
        return target;
    }

    /**
     * 工具输出截断: 超过 limit 字符时在行边界处截断并追加提示.
     * @param text 原始文本
     * @param limit 最大字符数
     * @return 截断后的文本
     */
    static String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        // 优先在换行符处截断, 避免截断到一行中间
        int cut = text.lastIndexOf('\n', limit);
        if (cut <= 0) cut = limit;
        return text.substring(0, cut) + "\n... [truncated, total " + text.length() + " chars]";
    }

    // --- 工具实现 ---

    /** read_file 工具: 读取 workspace 下的文件内容 */
    static String toolReadFile(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        AnsiColors.printTool("read_file", filePath);
        try {
            Path target = safePath(filePath);
            if (!Files.exists(target)) return "Error: File not found: " + filePath;
            String content = Files.readString(target);
            return truncate(content, MAX_TOOL_OUTPUT);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /** write_file 工具: 将内容写入 workspace 下的文件, 自动创建父目录 */
    static String toolWriteFile(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        String content = (String) input.get("content");
        AnsiColors.printTool("write_file", filePath);
        try {
            Path target = safePath(filePath);
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            return "OK: Written " + content.length() + " chars to " + filePath;
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /** get_current_time 工具: 返回当前 UTC 时间 */
    static String toolGetCurrentTime(@SuppressWarnings("unused") Map<String, Object> input) {
        AnsiColors.printTool("get_current_time", "");
        return Instant.now().toString().replace("T", " ").replace("Z", " UTC");
    }

    /**
     * 构建 SDK Tool 对象的辅助方法.
     * 将自定义的 properties Map 转换为 SDK 需要的 JsonValue 格式.
     */
    static Tool buildTool(String name, String description,
                          Map<String, Map<String, String>> properties,
                          List<String> required) {
        Tool.InputSchema.Properties.Builder pb = Tool.InputSchema.Properties.builder();
        properties.forEach((k, v) -> pb.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder()
                        .type(JsonValue.from("object")).properties(pb.build())
                        .required(required).build())
                .build();
    }

    // endregion S01-S05 Core

    // ================================================================
    // region S06-A: BootstrapLoader -- Bootstrap 文件加载器
    // ================================================================

    /**
     * Bootstrap 文件加载器.
     *
     * 在 agent 启动时加载工作区下的 Bootstrap 文件 (IDENTITY, SOUL, TOOLS 等).
     * 不同加载模式 (full/minimal/none) 适用于不同场景:
     *   full    = 主 agent, 加载全部文件
     *   minimal = 子 agent / cron, 仅加载 AGENTS.md + TOOLS.md
     *   none    = 最小化, 不加载任何文件
     */
    static class BootstrapLoader {
        private final Path workspaceDir;

        BootstrapLoader(Path workspaceDir) {
            this.workspaceDir = workspaceDir;
        }

        /**
         * 加载单个文件的内容.
         * 文件不存在或读取失败时返回空字符串 (不抛异常).
         */
        String loadFile(String name) {
            Path path = workspaceDir.resolve(name);
            if (!Files.isRegularFile(path)) return "";
            try {
                return Files.readString(path);
            } catch (Exception e) {
                return "";
            }
        }

        /**
         * 截断超长文件内容.
         * 仅保留头部, 优先在换行符处截断, 避免截断到一行中间.
         *
         * @param content  原始内容
         * @param maxChars 最大字符数
         * @return 截断后的内容
         */
        String truncateFile(String content, int maxChars) {
            if (content.length() <= maxChars) return content;
            // 在 maxChars 范围内找最后一个换行符作为截断点
            int cut = content.lastIndexOf('\n', maxChars);
            if (cut <= 0) cut = maxChars;
            return content.substring(0, cut)
                    + "\n\n[... truncated (" + content.length()
                    + " chars total, showing first " + cut + ") ...]";
        }

        /**
         * 加载所有 Bootstrap 文件, 按模式过滤.
         * 每个文件单独截断, 总字符数不超过 MAX_TOTAL_CHARS.
         *
         * @param mode 加载模式: "full" / "minimal" / "none"
         * @return 文件名 -> 内容的 Map
         */
        Map<String, String> loadAll(String mode) {
            if ("none".equals(mode)) return Map.of();

            // minimal 模式只加载必要的文件
            List<String> names = "minimal".equals(mode)
                    ? List.of("AGENTS.md", "TOOLS.md")
                    : BOOTSTRAP_FILES;

            Map<String, String> result = new LinkedHashMap<>();
            int total = 0;

            for (String name : names) {
                String raw = loadFile(name);
                if (raw.isEmpty()) continue;

                String truncated = truncateFile(raw, MAX_FILE_CHARS);

                // 检查总字符数是否超限
                if (total + truncated.length() > MAX_TOTAL_CHARS) {
                    int remaining = MAX_TOTAL_CHARS - total;
                    if (remaining > 0) {
                        truncated = truncateFile(raw, remaining);
                    } else {
                        break;  // 已达上限, 停止加载
                    }
                }
                result.put(name, truncated);
                total += truncated.length();
            }
            return result;
        }
    }

    // endregion S06-A

    // ================================================================
    // region S06-A: SkillsManager -- 技能发现与注入
    // ================================================================

    /**
     * 技能管理器.
     *
     * 一个"技能" = 一个包含 SKILL.md (带 YAML frontmatter) 的目录.
     * 按优先级顺序扫描 5 个目录; 同名技能会被后发现的覆盖.
     * 扫描目录 (按优先级从低到高):
     *   1. workspace/skills/           -- 内置技能
     *   2. workspace/.skills/          -- 托管技能
     *   3. workspace/.agents/skills/   -- 个人 agent 技能
     *   4. .agents/skills/             -- 项目 agent 技能
     *   5. skills/                     -- 工作区技能
     */
    static class SkillsManager {
        private final Path workspaceDir;

        /** 已发现的技能列表 */
        List<Map<String, String>> skills = new ArrayList<>();

        SkillsManager(Path workspaceDir) {
            this.workspaceDir = workspaceDir;
        }

        /**
         * 解析简单的 YAML frontmatter (不依赖 SnakeYAML).
         * 只支持 `---` 包裹的 key: value 格式.
         *
         * @param text SKILL.md 的完整内容
         * @return 解析出的 frontmatter 键值对
         */
        Map<String, String> parseFrontmatter(String text) {
            Map<String, String> meta = new LinkedHashMap<>();
            if (!text.startsWith("---")) return meta;

            // 用 "---" 分割, 取中间部分作为 frontmatter
            String[] parts = text.split("---", 3);
            if (parts.length < 3) return meta;

            for (String line : parts[1].strip().split("\n")) {
                int colonIdx = line.indexOf(':');
                if (colonIdx < 0) continue;
                String key = line.substring(0, colonIdx).strip();
                String value = line.substring(colonIdx + 1).strip();
                meta.put(key, value);
            }
            return meta;
        }

        /**
         * 扫描指定目录下的所有子目录, 寻找包含 SKILL.md 的技能目录.
         *
         * @param base 要扫描的父目录
         * @return 发现的技能列表
         */
        List<Map<String, String>> scanDir(Path base) {
            List<Map<String, String>> found = new ArrayList<>();
            if (!Files.isDirectory(base)) return found;

            try {
                // 按名称排序, 保证扫描顺序一致
                List<Path> children = Files.list(base)
                        .sorted()
                        .collect(Collectors.toList());

                for (Path child : children) {
                    if (!Files.isDirectory(child)) continue;
                    Path skillMd = child.resolve("SKILL.md");
                    if (!Files.isRegularFile(skillMd)) continue;

                    try {
                        String content = Files.readString(skillMd);
                        Map<String, String> meta = parseFrontmatter(content);

                        // name 是必须的 frontmatter 字段
                        if (!meta.containsKey("name") || meta.get("name").isBlank()) continue;

                        // 提取 frontmatter 之后的内容 (技能指令体)
                        String body = "";
                        if (content.startsWith("---")) {
                            String[] parts = content.split("---", 3);
                            if (parts.length >= 3) body = parts[2].strip();
                        }

                        Map<String, String> skill = new LinkedHashMap<>();
                        skill.put("name", meta.getOrDefault("name", ""));
                        skill.put("description", meta.getOrDefault("description", ""));
                        skill.put("invocation", meta.getOrDefault("invocation", ""));
                        skill.put("body", body);
                        skill.put("path", child.toString());
                        found.add(skill);
                    } catch (IOException e) {
                        // 跳过无法读取的文件
                    }
                }
            } catch (IOException e) {
                // 目录不存在或无法访问, 返回空列表
            }
            return found;
        }

        /**
         * 按优先级顺序扫描技能目录.
         * 同名技能后者覆盖前者 (后面的目录优先级更高).
         *
         * @param extraDirs 额外的扫描目录 (可选)
         */
        void discover(List<Path> extraDirs) {
            List<Path> scanOrder = new ArrayList<>();
            if (extraDirs != null) scanOrder.addAll(extraDirs);
            scanOrder.add(workspaceDir.resolve("skills"));              // 内置技能
            scanOrder.add(workspaceDir.resolve(".skills"));             // 托管技能
            scanOrder.add(workspaceDir.resolve(".agents").resolve("skills"));  // 个人 agent 技能
            scanOrder.add(WORKDIR.resolve(".agents").resolve("skills"));       // 项目 agent 技能
            scanOrder.add(WORKDIR.resolve("skills"));                          // 工作区技能

            // 用 Map 实现同名覆盖
            Map<String, Map<String, String>> seen = new LinkedHashMap<>();
            for (Path dir : scanOrder) {
                for (Map<String, String> skill : scanDir(dir)) {
                    seen.put(skill.get("name"), skill);
                }
            }
            skills = new ArrayList<>(seen.values());
            // 限制最大数量
            if (skills.size() > MAX_SKILLS) {
                skills = skills.subList(0, MAX_SKILLS);
            }
        }

        /** 便捷方法: 无额外目录的技能发现 */
        void discover() {
            discover(null);
        }

        /**
         * 将已发现的技能格式化为 prompt 块, 注入到系统提示词中.
         * 输出格式:
         *   ## Available Skills
         *   ### Skill: example-skill
         *   Description: ...
         *   Invocation: /example
         *   (技能指令体)
         *
         * @return 格式化的技能 prompt 块, 如果没有技能则返回空字符串
         */
        String formatPromptBlock() {
            if (skills.isEmpty()) return "";

            List<String> lines = new ArrayList<>();
            lines.add("## Available Skills");
            lines.add("");
            int total = 0;

            for (Map<String, String> skill : skills) {
                StringBuilder block = new StringBuilder();
                block.append("### Skill: ").append(skill.get("name")).append("\n");
                block.append("Description: ").append(skill.get("description")).append("\n");
                block.append("Invocation: ").append(skill.get("invocation")).append("\n");
                String body = skill.get("body");
                if (body != null && !body.isEmpty()) {
                    block.append("\n").append(body).append("\n");
                }
                block.append("\n");

                if (total + block.length() > MAX_SKILLS_PROMPT) {
                    lines.add("(... more skills truncated)");
                    break;
                }
                lines.add(block.toString());
                total += block.length();
            }
            return String.join("\n", lines);
        }
    }

    // endregion S06-A: SkillsManager

    // ================================================================
    // region S06-B: MemoryStore -- 记忆系统
    // ================================================================

    /**
     * 记忆存储系统.
     *
     * 两层存储:
     *   MEMORY.md = 长期事实 (手动维护, 按段落拆分)
     *   memory/daily/{date}.jsonl = 每日日志 (通过 agent 工具自动写入)
     *
     * 搜索管线: TF-IDF 关键词 + Hash 向量投影 → 加权合并 → 时间衰减 → MMR 重排
     * 纯 Java 实现, 不引入任何外部 NLP/ML 库.
     */
    static class MemoryStore {
        private final Path workspaceDir;
        private final Path memoryDir;  // memory/daily 目录

        MemoryStore(Path workspaceDir) {
            this.workspaceDir = workspaceDir;
            this.memoryDir = workspaceDir.resolve("memory").resolve("daily");
            try {
                Files.createDirectories(memoryDir);
            } catch (IOException e) {
                // 目录创建失败时不中断初始化: 后续 readAllChunks/writeMemory 会再次尝试,
                // 或在那时向用户报告具体错误. 静默忽略比在这里 crash 更友好.
            }
        }

        // ---- 写入层 ----

        /**
         * 写入一条记忆到当日的 JSONL 文件.
         * 格式: {"ts": "...", "category": "...", "content": "..."}
         *
         * @param content  记忆内容
         * @param category 分类 (preference / fact / context / general)
         * @return 操作结果描述
         */
        String writeMemory(String content, String category) {
            String today = LocalDateTime.now(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_LOCAL_DATE);
            Path path = memoryDir.resolve(today + ".jsonl");

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("ts", Instant.now().toString());
            entry.put("category", category != null ? category : "general");
            entry.put("content", content);

            try {
                JsonUtils.appendJsonl(path, entry);
                return "Memory saved to " + today + ".jsonl (" + category + ")";
            } catch (IOException e) {
                return "Error writing memory: " + e.getMessage();
            }
        }

        // ---- 读取层 ----

        /**
         * 加载长期记忆文件 (MEMORY.md) 的内容.
         * @return MEMORY.md 的内容, 文件不存在时返回空字符串
         */
        String loadEvergreen() {
            Path path = workspaceDir.resolve("MEMORY.md");
            if (!Files.isRegularFile(path)) return "";
            try {
                return Files.readString(path).strip();
            } catch (Exception e) {
                return "";
            }
        }

        /**
         * 加载所有记忆并拆分为块.
         * MEMORY.md 按段落 (\n\n) 拆分, 每日 JSONL 每条记录作为一个块.
         *
         * @return 记忆块列表, 每个块包含 path (来源标识) 和 text (文本内容)
         */
        List<Map<String, String>> loadAllChunks() {
            List<Map<String, String>> chunks = new ArrayList<>();

            // 1. 按段落拆分长期记忆
            String evergreen = loadEvergreen();
            if (!evergreen.isEmpty()) {
                for (String para : evergreen.split("\n\n")) {
                    para = para.strip();
                    if (!para.isEmpty()) {
                        Map<String, String> chunk = new LinkedHashMap<>();
                        chunk.put("path", "MEMORY.md");
                        chunk.put("text", para);
                        chunks.add(chunk);
                    }
                }
            }

            // 2. 每日记忆: 每条 JSONL 记录作为一个块
            if (Files.isDirectory(memoryDir)) {
                try {
                    List<Path> jsonlFiles = Files.list(memoryDir)
                            .filter(p -> p.toString().endsWith(".jsonl"))
                            .sorted()
                            .collect(Collectors.toList());

                    for (Path jf : jsonlFiles) {
                        try {
                            for (String line : Files.readAllLines(jf)) {
                                if (line.isBlank()) continue;
                                try {
                                    Map<String, Object> entry = JsonUtils.toMap(line);
                                    String text = (String) entry.getOrDefault("content", "");
                                    if (text.isEmpty()) continue;
                                    String cat = (String) entry.getOrDefault("category", "");
                                    String label = cat.isEmpty()
                                            ? jf.getFileName().toString()
                                            : jf.getFileName() + " [" + cat + "]";

                                    Map<String, String> chunk = new LinkedHashMap<>();
                                    chunk.put("path", label);
                                    chunk.put("text", text);
                                    chunks.add(chunk);
                                } catch (Exception e) {
                                    // 跳过无法解析的行
                                }
                            }
                        } catch (IOException e) {
                            // 跳过无法读取的文件
                        }
                    }
                } catch (IOException e) {
                    // memoryDir 无法列出
                }
            }
            return chunks;
        }

        // ---- 分词 ----

        /**
         * 分词器: 小写英文单词 + 单个 CJK 字符, 过滤短 token.
         * 支持中英文混合文本, 是 TF-IDF 和向量检索的基础.
         *
         * @param text 输入文本
         * @return token 数组
         */
        static String[] tokenize(String text) {
            // 匹配: 连续小写字母数字 或 单个 CJK 字符
            Pattern p = Pattern.compile("[a-z0-9\\u4e00-\\u9fff]+");
            Matcher m = p.matcher(text.toLowerCase());
            List<String> tokens = new ArrayList<>();
            while (m.find()) {
                String t = m.group();
                // 过滤: 英文 token 长度 > 1, CJK 单字符也保留
                if (t.length() > 1 || (t.length() == 1 && t.charAt(0) >= '\u4e00' && t.charAt(0) <= '\u9fff')) {
                    tokens.add(t);
                }
            }
            return tokens.toArray(new String[0]);
        }

        // ---- TF-IDF 关键词搜索 ----

        /**
         * TF-IDF + 余弦相似度关键词搜索.
         * 纯 Java 实现, 不依赖外部库.
         *
         * 计算过程:
         *   1. 统计文档频率 (DF): 每个词出现在多少个文档中
         *   2. 计算 TF-IDF 向量: tf * log((N+1)/(df+1)) + 1
         *   3. 余弦相似度排序
         *
         * @param query 查询字符串
         * @param chunks 候选记忆块列表
         * @param topK 返回的最大结果数
         * @return 排序后的搜索结果, 每项包含 chunk, score
         */
        List<Map<String, Object>> keywordSearch(String query, List<Map<String, String>> chunks, int topK) {
            String[] queryTokens = tokenize(query);
            if (queryTokens.length == 0) return List.of();

            // 对每个 chunk 进行分词
            List<String[]> chunkTokensList = new ArrayList<>();
            for (Map<String, String> c : chunks) {
                chunkTokensList.add(tokenize(c.get("text")));
            }

            // 统计文档频率 (DF)
            Map<String, Integer> df = new HashMap<>();
            for (String[] tokens : chunkTokensList) {
                Set<String> unique = new HashSet<>(Arrays.asList(tokens));
                for (String t : unique) {
                    df.merge(t, 1, Integer::sum);
                }
            }
            int n = chunks.size();

            // 计算 query 的 TF-IDF 向量
            Map<String, Double> qVec = tfidf(queryTokens, df, n);

            // 对每个 chunk 计算相似度
            List<Map<String, Object>> scored = new ArrayList<>();
            for (int i = 0; i < chunkTokensList.size(); i++) {
                String[] tokens = chunkTokensList.get(i);
                if (tokens.length == 0) continue;
                Map<String, Double> cVec = tfidf(tokens, df, n);
                double score = cosineSimilarity(qVec, cVec);
                if (score > 0.0) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("chunk", chunks.get(i));
                    result.put("score", score);
                    scored.add(result);
                }
            }

            // 按 score 降序排列, 取 topK
            scored.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
            return scored.size() > topK ? scored.subList(0, topK) : scored;
        }

        /**
         * 计算 TF-IDF 向量.
         * TF = 词频 (词在文档中出现的次数)
         * IDF = log((N+1)/(DF+1)) + 1  (平滑 IDF, 避免除零)
         *
         * @param tokens 文档的 token 数组
         * @param df     文档频率 Map
         * @param n      总文档数
         * @return token -> TF-IDF 值 的向量
         */
        static Map<String, Double> tfidf(String[] tokens, Map<String, Integer> df, int n) {
            // 计算词频 (TF)
            Map<String, Integer> tf = new HashMap<>();
            for (String t : tokens) {
                tf.merge(t, 1, Integer::sum);
            }
            // TF * IDF
            Map<String, Double> vec = new HashMap<>();
            for (Map.Entry<String, Integer> e : tf.entrySet()) {
                String t = e.getKey();
                double idf = Math.log((double) (n + 1) / (df.getOrDefault(t, 0) + 1)) + 1;
                vec.put(t, e.getValue() * idf);
            }
            return vec;
        }

        /**
         * 计算两个稀疏向量的余弦相似度.
         * cosine(A, B) = (A · B) / (|A| * |B|)
         */
        static double cosineSimilarity(Map<String, Double> a, Map<String, Double> b) {
            Set<String> common = new HashSet<>(a.keySet());
            common.retainAll(b.keySet());
            if (common.isEmpty()) return 0.0;

            double dot = 0;
            for (String k : common) dot += a.get(k) * b.get(k);

            double na = Math.sqrt(a.values().stream().mapToDouble(v -> v * v).sum());
            double nb = Math.sqrt(b.values().stream().mapToDouble(v -> v * v).sum());
            return (na > 0 && nb > 0) ? dot / (na * nb) : 0.0;
        }

        // ---- Hash 向量投影搜索 ----

        /**
         * 模拟向量嵌入: 使用 Hash 随机投影.
         * 不依赖外部 API, 教学目的 -- 展示第二搜索通道的模式.
         *
         * 原理: 每个 token 的 hash 值被映射到 dim 维空间中的 +1/-1,
         * 所有 token 的投影累加后归一化, 生成一个伪向量.
         *
         * @param text 输入文本
         * @param dim  向量维度 (默认 64)
         * @return 归一化后的浮点向量
         */
        static double[] hashVector(String text, int dim) {
            String[] tokens = tokenize(text);
            double[] vec = new double[dim];
            for (String token : tokens) {
                long h = token.hashCode();
                for (int i = 0; i < dim; i++) {
                    // 取 hash 的第 i 位作为 +/-1 投影
                    int bit = (int) ((h >> (i % 62)) & 1);
                    vec[i] += (bit == 1) ? 1.0 : -1.0;
                }
            }
            // L2 归一化
            double norm = Math.sqrt(Arrays.stream(vec).map(v -> v * v).sum());
            if (norm > 0) {
                for (int i = 0; i < dim; i++) vec[i] /= norm;
            }
            return vec;
        }

        /** 便捷方法: 使用默认 64 维 */
        static double[] hashVector(String text) {
            return hashVector(text, 64);
        }

        /**
         * 计算两个浮点向量的余弦相似度.
         */
        static double vectorCosine(double[] a, double[] b) {
            double dot = 0, na = 0, nb = 0;
            for (int i = 0; i < Math.min(a.length, b.length); i++) {
                dot += a[i] * b[i];
                na += a[i] * a[i];
                nb += b[i] * b[i];
            }
            na = Math.sqrt(na);
            nb = Math.sqrt(nb);
            return (na > 0 && nb > 0) ? dot / (na * nb) : 0.0;
        }

        /**
         * 基于向量相似度的搜索.
         * 将 query 和每个 chunk 都转为 hash 向量, 计算余弦距离.
         *
         * @param query  查询字符串
         * @param chunks 候选记忆块列表
         * @param topK   返回的最大结果数
         * @return 排序后的搜索结果
         */
        List<Map<String, Object>> vectorSearch(String query, List<Map<String, String>> chunks, int topK) {
            double[] qVec = hashVector(query);
            List<Map<String, Object>> scored = new ArrayList<>();

            for (Map<String, String> chunk : chunks) {
                double[] cVec = hashVector(chunk.get("text"));
                double score = vectorCosine(qVec, cVec);
                if (score > 0.0) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("chunk", chunk);
                    result.put("score", score);
                    scored.add(result);
                }
            }

            scored.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
            return scored.size() > topK ? scored.subList(0, topK) : scored;
        }

        // ---- 混合检索管线 ----

        /**
         * 合并向量搜索和关键词搜索的结果.
         * 使用加权分数组合: vector_weight * vector_score + text_weight * keyword_score.
         * 以 chunk 文本前 100 字符作为去重键.
         *
         * @param vectorResults 向量搜索结果
         * @param keywordResults 关键词搜索结果
         * @param vectorWeight   向量权重 (默认 0.7)
         * @param textWeight     关键词权重 (默认 0.3)
         * @return 合并后的结果列表
         */
        static List<Map<String, Object>> mergeHybridResults(
                List<Map<String, Object>> vectorResults,
                List<Map<String, Object>> keywordResults,
                double vectorWeight, double textWeight) {

            // 以 chunk 文本前 100 字符作为去重键
            Map<String, Map<String, Object>> merged = new LinkedHashMap<>();

            for (Map<String, Object> r : vectorResults) {
                @SuppressWarnings("unchecked")
                Map<String, String> chunk = (Map<String, String>) r.get("chunk");
                String key = chunk.get("text").substring(0, Math.min(100, chunk.get("text").length()));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("chunk", chunk);
                entry.put("score", (double) r.get("score") * vectorWeight);
                merged.put(key, entry);
            }

            for (Map<String, Object> r : keywordResults) {
                @SuppressWarnings("unchecked")
                Map<String, String> chunk = (Map<String, String>) r.get("chunk");
                String key = chunk.get("text").substring(0, Math.min(100, chunk.get("text").length()));
                if (merged.containsKey(key)) {
                    // 已存在: 累加关键词权重分
                    Map<String, Object> existing = merged.get(key);
                    existing.put("score", (double) existing.get("score") + (double) r.get("score") * textWeight);
                } else {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("chunk", chunk);
                    entry.put("score", (double) r.get("score") * textWeight);
                    merged.put(key, entry);
                }
            }

            List<Map<String, Object>> result = new ArrayList<>(merged.values());
            result.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
            return result;
        }

        /**
         * 时间衰减: 根据记忆块的"年龄"对分数施加指数衰减.
         * 从路径中提取日期 (YYYY-MM-DD), 计算距今天数.
         * score *= exp(-decayRate * ageDays)
         *
         * @param results   搜索结果列表 (会原地修改)
         * @param decayRate 衰减率 (默认 0.01, 即每天衰减约 1%)
         * @return 衰减后的结果列表
         */
        static List<Map<String, Object>> temporalDecay(List<Map<String, Object>> results, double decayRate) {
            Instant now = Instant.now();
            Pattern datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

            for (Map<String, Object> r : results) {
                @SuppressWarnings("unchecked")
                Map<String, String> chunk = (Map<String, String>) r.get("chunk");
                String pathStr = chunk.getOrDefault("path", "");
                double ageDays = 0.0;

                Matcher dm = datePattern.matcher(pathStr);
                if (dm.find()) {
                    try {
                        LocalDateTime chunkDate = LocalDateTime.parse(dm.group(1) + "T00:00:00",
                                DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                        ageDays = ChronoUnit.SECONDS.between(
                                chunkDate.toInstant(ZoneOffset.UTC), now) / 86400.0;
                    } catch (Exception e) {
                        // 日期解析失败, 使用 0 (不衰减)
                    }
                }
                r.put("score", (double) r.get("score") * Math.exp(-decayRate * ageDays));
            }
            return results;
        }

        /**
         * Jaccard 相似度: 两个 token 集合的交集 / 并集.
         * 用于 MMR 重排时衡量两个结果的冗余度.
         */
        static double jaccardSimilarity(String[] tokensA, String[] tokensB) {
            Set<String> setA = new HashSet<>(Arrays.asList(tokensA));
            Set<String> setB = new HashSet<>(Arrays.asList(tokensB));
            int intersection = 0;
            for (String t : setA) {
                if (setB.contains(t)) intersection++;
            }
            int union = setA.size() + setB.size() - intersection;
            return union > 0 ? (double) intersection / union : 0.0;
        }

        /**
         * MMR (Maximal Marginal Relevance) 重排.
         * 在相关性和多样性之间取平衡, 避免返回高度重复的结果.
         *
         * MMR = lambda * relevance - (1-lambda) * max_similarity_to_selected
         *
         * @param results      输入结果列表 (已按分数排序)
         * @param lambdaParam  平衡参数 (默认 0.7, 越大越偏重相关性)
         * @return 重排后的结果列表
         */
        static List<Map<String, Object>> mmrRerank(List<Map<String, Object>> results, double lambdaParam) {
            if (results.size() <= 1) return results;

            // 预先对所有结果分词
            List<String[]> tokenized = new ArrayList<>();
            for (Map<String, Object> r : results) {
                @SuppressWarnings("unchecked")
                Map<String, String> chunk = (Map<String, String>) r.get("chunk");
                tokenized.add(tokenize(chunk.get("text")));
            }

            List<Integer> selected = new ArrayList<>();
            Set<Integer> remaining = new LinkedHashSet<>();
            for (int i = 0; i < results.size(); i++) remaining.add(i);

            List<Map<String, Object>> reranked = new ArrayList<>();

            while (!remaining.isEmpty()) {
                int bestIdx = -1;
                double bestMmr = Double.NEGATIVE_INFINITY;

                for (int idx : remaining) {
                    double relevance = (double) results.get(idx).get("score");
                    // 计算与已选结果的最大相似度
                    double maxSim = 0.0;
                    for (int selIdx : selected) {
                        double sim = jaccardSimilarity(tokenized.get(idx), tokenized.get(selIdx));
                        if (sim > maxSim) maxSim = sim;
                    }
                    double mmr = lambdaParam * relevance - (1 - lambdaParam) * maxSim;
                    if (mmr > bestMmr) {
                        bestMmr = mmr;
                        bestIdx = idx;
                    }
                }

                selected.add(bestIdx);
                remaining.remove(bestIdx);
                reranked.add(results.get(bestIdx));
            }
            return reranked;
        }

        /**
         * 完整的混合搜索管线:
         *   keyword → vector → merge (70%/30%) → temporal decay → MMR rerank → topK
         *
         * @param query 查询字符串
         * @param topK  返回的最大结果数
         * @return 搜索结果列表, 每项包含 path, score, snippet
         */
        List<Map<String, Object>> hybridSearch(String query, int topK) {
            List<Map<String, String>> chunks = loadAllChunks();
            if (chunks.isEmpty()) return List.of();

            // 1. 双通道搜索
            List<Map<String, Object>> keywordResults = keywordSearch(query, chunks, 10);
            List<Map<String, Object>> vectorResults = vectorSearch(query, chunks, 10);

            // 2. 加权合并 (70% 向量 + 30% 关键词)
            List<Map<String, Object>> merged = mergeHybridResults(vectorResults, keywordResults, 0.7, 0.3);

            // 3. 时间衰减
            List<Map<String, Object>> decayed = temporalDecay(merged, 0.01);

            // 4. MMR 重排
            List<Map<String, Object>> reranked = mmrRerank(decayed, 0.7);

            // 5. 格式化输出, 截断过长的 snippet
            List<Map<String, Object>> result = new ArrayList<>();
            int count = 0;
            for (Map<String, Object> r : reranked) {
                if (count >= topK) break;
                @SuppressWarnings("unchecked")
                Map<String, String> chunk = (Map<String, String>) r.get("chunk");
                String snippet = chunk.get("text");
                if (snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("path", chunk.get("path"));
                item.put("score", Math.round((double) r.get("score") * 10000.0) / 10000.0);
                item.put("snippet", snippet);
                result.add(item);
                count++;
            }
            return result;
        }

        /**
         * 兼容原 TF-IDF 单通道搜索 (保留给需要简化场景使用).
         */
        List<Map<String, Object>> searchMemory(String query, int topK) {
            return hybridSearch(query, topK);
        }

        /**
         * 获取记忆统计信息.
         */
        Map<String, Object> getStats() {
            String evergreen = loadEvergreen();
            int dailyFiles = 0;
            int dailyEntries = 0;

            if (Files.isDirectory(memoryDir)) {
                try {
                    List<Path> files = Files.list(memoryDir)
                            .filter(p -> p.toString().endsWith(".jsonl"))
                            .collect(Collectors.toList());
                    dailyFiles = files.size();
                    for (Path f : files) {
                        try {
                            dailyEntries += (int) Files.readAllLines(f).stream()
                                    .filter(line -> !line.isBlank())
                                    .count();
                        } catch (IOException e) { /* skip */ }
                    }
                } catch (IOException e) { /* skip */ }
            }

            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("evergreen_chars", evergreen.length());
            stats.put("daily_files", dailyFiles);
            stats.put("daily_entries", dailyEntries);
            return stats;
        }
    }

    // endregion S06-B: MemoryStore

    // ================================================================
    // region S06-C: 记忆工具 + 工具注册
    // ================================================================

    /** 全局 MemoryStore 实例 */
    static final MemoryStore memoryStore = new MemoryStore(WORKSPACE_DIR);

    /**
     * memory_write 工具: 将重要事实写入长期记忆.
     * 调用 MemoryStore 写入当日 JSONL 文件.
     */
    static String toolMemoryWrite(Map<String, Object> input) {
        String content = (String) input.get("content");
        String category = (String) input.getOrDefault("category", "general");
        AnsiColors.printTool("memory_write", "[" + category + "] " + truncate(content, 60));
        return memoryStore.writeMemory(content, category);
    }

    /**
     * memory_search 工具: 搜索存储的记忆, 按相似度排序.
     */
    static String toolMemorySearch(Map<String, Object> input) {
        String query = (String) input.get("query");
        int topK = input.containsKey("top_k")
                ? ((Number) input.get("top_k")).intValue() : 5;
        AnsiColors.printTool("memory_search", query);

        List<Map<String, Object>> results = memoryStore.hybridSearch(query, topK);
        if (results.isEmpty()) return "No relevant memories found.";

        return results.stream()
                .map(r -> "[" + r.get("path") + "] (score: " + r.get("score") + ") " + r.get("snippet"))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 处理单个工具调用: 根据 toolName 查找 handler 并执行.
     */
    static String processToolCall(String toolName, Map<String, Object> toolInput) {
        Function<Map<String, Object>, String> handler = TOOL_HANDLERS.get(toolName);
        if (handler == null) return "Error: Unknown tool '" + toolName + "'";
        try {
            return handler.apply(toolInput);
        } catch (Exception e) {
            return "Error: " + toolName + " failed: " + e.getMessage();
        }
    }

    // ---- 工具 Schema 定义 ----

    /** 所有工具的 Schema 列表, 传递给 Claude API */
    static final List<ToolUnion> TOOLS = List.of(
            // --- S02 核心工具 ---
            ToolUnion.ofTool(buildTool("read_file",
                    "Read the contents of a file under the workspace directory.",
                    Map.of("file_path", Map.of("type", "string",
                            "description", "Path relative to workspace directory.")),
                    List.of("file_path"))),
            ToolUnion.ofTool(buildTool("write_file",
                    "Write content to a file under the workspace directory.",
                    Map.of("file_path", Map.of("type", "string",
                                    "description", "Path relative to workspace directory."),
                            "content", Map.of("type", "string",
                                    "description", "Content to write.")),
                    List.of("file_path", "content"))),
            ToolUnion.ofTool(buildTool("get_current_time",
                    "Get the current date and time in UTC.",
                    Map.of(), List.of())),
            // --- S06 记忆工具 ---
            ToolUnion.ofTool(buildTool("memory_write",
                    "Save an important fact or observation to long-term memory. "
                            + "Use when you learn something worth remembering about the user or context.",
                    Map.of("content", Map.of("type", "string",
                                    "description", "The fact or observation to remember."),
                            "category", Map.of("type", "string",
                                    "description", "Category: preference, fact, context, general.")),
                    List.of("content"))),
            ToolUnion.ofTool(buildTool("memory_search",
                    "Search stored memories for relevant information, ranked by similarity.",
                    Map.of("query", Map.of("type", "string",
                                    "description", "Search query."),
                            "top_k", Map.of("type", "integer",
                                    "description", "Max results. Default: 5.")),
                    List.of("query")))
    );

    /** 工具名 -> 处理函数的分发表 */
    static final Map<String, Function<Map<String, Object>, String>> TOOL_HANDLERS = new LinkedHashMap<>();
    static {
        TOOL_HANDLERS.put("read_file", S06Intelligence::toolReadFile);
        TOOL_HANDLERS.put("write_file", S06Intelligence::toolWriteFile);
        TOOL_HANDLERS.put("get_current_time", S06Intelligence::toolGetCurrentTime);
        TOOL_HANDLERS.put("memory_write", S06Intelligence::toolMemoryWrite);
        TOOL_HANDLERS.put("memory_search", S06Intelligence::toolMemorySearch);
    }

    // endregion S06-C: 工具注册

    // ================================================================
    // region S06-C: 8 层系统提示词组装
    // ================================================================

    /**
     * 构建系统提示词 -- 8 层分层组装.
     *
     * 每轮重建 (记忆可能在上一轮被更新).
     * 模式:
     *   full    = 主 agent, 加载全部 8 层
     *   minimal = 子 agent / cron, 加载部分层
     *   none    = 最小化
     *
     * 层级结构:
     *   L1: Identity (身份)        -- 来自 IDENTITY.md
     *   L2: Soul (灵魂)            -- 来自 SOUL.md (仅 full 模式)
     *   L3: Tools (工具使用指南)    -- 来自 TOOLS.md
     *   L4: Skills (技能)           -- 来自 SkillsManager (仅 full 模式)
     *   L5: Memory (记忆)           -- 来自 MEMORY.md + 自动搜索结果 (仅 full 模式)
     *   L6: Bootstrap (启动上下文)  -- 来自 HEARTBEAT/BOOTSTRAP/AGENTS/USER.md
     *   L7: Runtime Context (运行时) -- 动态生成
     *   L8: Channel Hints (渠道提示) -- 根据渠道类型定制
     *
     * @param mode           加载模式
     * @param bootstrap      BootstrapLoader 加载的文件内容
     * @param skillsBlock    SkillsManager 格式化的技能 prompt 块
     * @param memoryContext  本轮自动搜索召回的记忆上下文
     * @param agentId        Agent ID
     * @param channel        当前渠道 (terminal / telegram / discord / slack)
     * @return 完整的系统提示词
     */
    static String buildSystemPrompt(String mode, Map<String, String> bootstrap,
                                     String skillsBlock, String memoryContext,
                                     String agentId, String channel) {
        List<String> sections = new ArrayList<>();

        // --- L1: 身份 -- 来自 IDENTITY.md 或默认值
        String identity = bootstrap.getOrDefault("IDENTITY.md", "").strip();
        sections.add(identity.isEmpty()
                ? "You are a helpful personal AI assistant."
                : identity);

        // --- L2: 灵魂 -- 人格注入, 越靠前影响力越强 (仅 full 模式)
        if ("full".equals(mode)) {
            String soul = bootstrap.getOrDefault("SOUL.md", "").strip();
            if (!soul.isEmpty()) {
                sections.add("## Personality\n\n" + soul);
            }
        }

        // --- L3: 工具使用指南
        String toolsMd = bootstrap.getOrDefault("TOOLS.md", "").strip();
        if (!toolsMd.isEmpty()) {
            sections.add("## Tool Usage Guidelines\n\n" + toolsMd);
        }

        // --- L4: 技能 (仅 full 模式)
        if ("full".equals(mode) && skillsBlock != null && !skillsBlock.isEmpty()) {
            sections.add(skillsBlock);
        }

        // --- L5: 记忆 -- 长期记忆 + 本轮自动搜索结果 (仅 full 模式)
        if ("full".equals(mode)) {
            String memMd = bootstrap.getOrDefault("MEMORY.md", "").strip();
            List<String> parts = new ArrayList<>();
            if (!memMd.isEmpty()) {
                parts.add("### Evergreen Memory\n\n" + memMd);
            }
            if (memoryContext != null && !memoryContext.isEmpty()) {
                parts.add("### Recalled Memories (auto-searched)\n\n" + memoryContext);
            }
            if (!parts.isEmpty()) {
                sections.add("## Memory\n\n" + String.join("\n\n", parts));
            }
            sections.add(
                    "## Memory Instructions\n\n"
                            + "- Use memory_write to save important user facts and preferences.\n"
                            + "- Reference remembered facts naturally in conversation.\n"
                            + "- Use memory_search to recall specific past information."
            );
        }

        // --- L6: Bootstrap 上下文 -- 剩余的 Bootstrap 文件
        if ("full".equals(mode) || "minimal".equals(mode)) {
            for (String name : List.of("HEARTBEAT.md", "BOOTSTRAP.md", "AGENTS.md", "USER.md")) {
                String content = bootstrap.getOrDefault(name, "").strip();
                if (!content.isEmpty()) {
                    sections.add("## " + name.replace(".md", "") + "\n\n" + content);
                }
            }
        }

        // --- L7: 运行时上下文
        String now = LocalDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));
        sections.add(
                "## Runtime Context\n\n"
                        + "- Agent ID: " + agentId + "\n"
                        + "- Model: " + MODEL_ID + "\n"
                        + "- Channel: " + channel + "\n"
                        + "- Current time: " + now + "\n"
                        + "- Prompt mode: " + mode
        );

        // --- L8: 渠道提示
        Map<String, String> hints = Map.of(
                "terminal", "You are responding via a terminal REPL. Markdown is supported.",
                "telegram", "You are responding via Telegram. Keep messages concise.",
                "discord", "You are responding via Discord. Keep messages under 2000 characters.",
                "slack", "You are responding via Slack. Use Slack mrkdwn formatting."
        );
        String channelHint = hints.getOrDefault(channel,
                "You are responding via " + channel + ".");
        sections.add("## Channel\n\n" + channelHint);

        return String.join("\n\n", sections);
    }

    // endregion S06-C: 8 层系统提示词

    // ================================================================
    // region S06-C: 自动记忆召回
    // ================================================================

    /**
     * 根据用户消息自动搜索相关记忆, 注入到系统提示词中.
     * 每轮用户消息都会触发 Top3 检索, 语义相关的记忆会自动被包含在上下文中.
     *
     * @param userMessage 用户的输入消息
     * @return 格式化的记忆上下文字符串, 无结果时返回空字符串
     */
    static String autoRecall(String userMessage) {
        List<Map<String, Object>> results = memoryStore.hybridSearch(userMessage, 3);
        if (results.isEmpty()) return "";
        return results.stream()
                .map(r -> "- [" + r.get("path") + "] " + r.get("snippet"))
                .collect(Collectors.joining("\n"));
    }

    // endregion S06-C: 自动记忆召回

    // ================================================================
    // region S06-C: Agent Loop (带 Intelligence 的完整 Agent 循环)
    // ================================================================

    /**
     * 完整的 Agent 循环.
     *
     * 启动阶段:
     *   1. 加载 Bootstrap 文件 (8 个 workspace 配置文件)
     *   2. 发现技能 (扫描 5 个目录, 启动时发现一次)
     *   3. 进入 REPL 循环
     *
     * 每轮对话:
     *   1. 检查 REPL 斜杠命令
     *   2. autoRecall: 根据用户消息自动搜索相关记忆
     *   3. buildSystemPrompt: 组装 8 层系统提示词
     *   4. 调用 LLM API (含 tool dispatch loop)
     *   5. 更新消息历史
     */
    static void agentLoop() {
        // ---- 启动阶段 ----

        // 加载 Bootstrap 文件
        BootstrapLoader loader = new BootstrapLoader(WORKSPACE_DIR);
        Map<String, String> bootstrapData = loader.loadAll("full");

        // 发现技能
        SkillsManager skillsMgr = new SkillsManager(WORKSPACE_DIR);
        skillsMgr.discover();
        String skillsBlock = skillsMgr.formatPromptBlock();

        // 消息历史
        List<MessageParam> messages = new ArrayList<>();

        // 打印 banner
        AnsiColors.printInfo("============================================================");
        AnsiColors.printInfo("  claw0  |  Section 06: Intelligence");
        AnsiColors.printInfo("  Model: " + MODEL_ID);
        AnsiColors.printInfo("  Workspace: " + WORKSPACE_DIR);
        AnsiColors.printInfo("  Bootstrap 文件: " + bootstrapData.size());
        AnsiColors.printInfo("  已发现技能: " + skillsMgr.skills.size());
        Map<String, Object> stats = memoryStore.getStats();
        AnsiColors.printInfo("  记忆: 长期 " + stats.get("evergreen_chars") + " 字符, "
                + stats.get("daily_files") + " 个每日文件");
        AnsiColors.printInfo("  命令: /soul /skills /memory /search /prompt /bootstrap");
        AnsiColors.printInfo("  输入 'quit' 或 'exit' 退出.");
        AnsiColors.printInfo("============================================================");
        System.out.println();

        // ---- REPL 循环 ----
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String userInput;
            try {
                System.out.print(AnsiColors.coloredPrompt());
                userInput = scanner.nextLine().strip();
            } catch (Exception e) {
                AnsiColors.printInfo("再见.");
                break;
            }

            if (userInput.isEmpty()) continue;
            if (userInput.equalsIgnoreCase("quit") || userInput.equalsIgnoreCase("exit")) {
                AnsiColors.printInfo("再见.");
                break;
            }

            // 处理 REPL 斜杠命令
            if (userInput.startsWith("/")) {
                if (handleReplCommand(userInput, bootstrapData, skillsMgr, skillsBlock)) {
                    continue;
                }
            }

            // 自动记忆搜索 -- 将相关记忆注入系统提示词
            String memoryContext = autoRecall(userInput);
            if (!memoryContext.isEmpty()) {
                AnsiColors.printInfo("  [自动召回] 找到相关记忆");
            }

            // 每轮重建系统提示词 (记忆可能在上一轮被更新)
            String systemPrompt = buildSystemPrompt(
                    "full", bootstrapData, skillsBlock, memoryContext, "main", "terminal");

            // 添加用户消息
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content(userInput)
                    .build());

            // ---- Agent 内循环: 处理连续的工具调用直到 end_turn ----
            // 流程: 调用 LLM → 检查 stop_reason → end_turn 则结束, tool_use 则执行工具后继续循环
            while (true) {
                // 步骤 1: 调用 Claude API, 获取本轮回复
                Message response;
                try {
                    response = client.messages().create(MessageCreateParams.builder()
                            .model(MODEL_ID)
                            .maxTokens(8096)
                            .system(systemPrompt)
                            .tools(TOOLS)
                            .messages(messages)
                            .build());
                } catch (Exception e) {
                    // API 异常: 回滚最后一条消息, 避免下一轮 role 交替规则失败
                    // Claude API 要求 user/assistant 严格交替, 残留的 user 消息会导致下一轮 400 错误
                    System.out.println("\n" + AnsiColors.YELLOW + "API Error: " + e.getMessage()
                            + AnsiColors.RESET + "\n");
                    // 先移除非 user 消息
                    while (!messages.isEmpty()
                            && messages.get(messages.size() - 1).role() != MessageParam.Role.USER) {
                        messages.remove(messages.size() - 1);
                    }
                    // 再移除最后的 user 消息
                    if (!messages.isEmpty()) messages.remove(messages.size() - 1);
                    break;
                }

                // 步骤 2: 将 assistant 的完整回复加入消息历史 (保持 user/assistant 交替)
                messages.add(response.toParam());
                StopReason reason = response.stopReason().orElse(null);

                if (StopReason.END_TURN.equals(reason)) {
                    // 步骤 3a: 正常结束 -- 提取所有 TextBlock, 拼接后打印给用户
                    String text = response.content().stream()
                            .filter(ContentBlock::isText)
                            .map(ContentBlock::asText)
                            .map(TextBlock::text)
                            .collect(Collectors.joining());
                    if (!text.isEmpty()) {
                        AnsiColors.printAssistant(text);
                    }
                    break;

                } else if (StopReason.TOOL_USE.equals(reason)) {
                    // 步骤 3b: 工具调用 -- 遍历所有 ToolUseBlock, 逐个执行并收集结果
                    List<ContentBlockParam> results = new ArrayList<>();
                    for (ContentBlock block : response.content()) {
                        if (!block.isToolUse()) continue;
                        ToolUseBlock tu = block.asToolUse();
                        @SuppressWarnings("unchecked")
                        Map<String, Object> toolInput = tu._input().convert(Map.class);
                        String result = processToolCall(tu.name(), toolInput);
                        results.add(ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                        .toolUseId(tu.id())
                                        .content(result)
                                        .build()));
                    }
                    // 步骤 4: 将工具结果作为 user 消息追加, LLM 将在下一轮看到结果
                    messages.add(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(results)
                            .build());
                    continue;  // 回到步骤 1, 继续循环

                } else {
                    // 步骤 3c: 其他 stop reason (max_tokens 等) -- 尽量提取已有文本
                    AnsiColors.printInfo("[stop_reason=" + reason + "]");
                    String text = response.content().stream()
                            .filter(ContentBlock::isText)
                            .map(ContentBlock::asText)
                            .map(TextBlock::text)
                            .collect(Collectors.joining());
                    if (!text.isEmpty()) {
                        AnsiColors.printAssistant(text);
                    }
                    break;
                }
            }
        }
    }

    // endregion S06-C: Agent Loop

    // ================================================================
    // region S06-C: REPL 命令处理
    // ================================================================

    /**
     * 处理 REPL 斜杠命令.
     * 返回 true 表示命令已被处理, false 表示未识别.
     *
     * 支持的命令:
     *   /soul          -- 显示 SOUL.md 内容
     *   /skills        -- 显示已发现的技能列表
     *   /memory        -- 显示记忆统计信息
     *   /search <q>    -- 搜索记忆
     *   /prompt        -- 显示完整系统提示词
     *   /bootstrap     -- 显示 Bootstrap 文件加载状态
     */
    static boolean handleReplCommand(String cmd, Map<String, String> bootstrapData,
                                      SkillsManager skillsMgr, String skillsBlock) {
        String[] parts = cmd.strip().split("\\s+", 2);
        String command = parts[0].toLowerCase();
        String arg = parts.length > 1 ? parts[1] : "";

        switch (command) {
            case "/soul" -> {
                printSection("SOUL.md");
                String soul = bootstrapData.getOrDefault("SOUL.md", "");
                System.out.println(soul.isEmpty()
                        ? AnsiColors.DIM + "(未找到 SOUL.md)" + AnsiColors.RESET
                        : soul);
                return true;
            }
            case "/skills" -> {
                printSection("已发现的技能");
                if (skillsMgr.skills.isEmpty()) {
                    System.out.println(AnsiColors.DIM + "(未找到技能)" + AnsiColors.RESET);
                } else {
                    for (Map<String, String> s : skillsMgr.skills) {
                        System.out.println("  " + AnsiColors.BLUE + s.get("invocation")
                                + AnsiColors.RESET + "  " + s.get("name")
                                + " - " + s.get("description"));
                        System.out.println("    " + AnsiColors.DIM + "path: "
                                + s.get("path") + AnsiColors.RESET);
                    }
                }
                return true;
            }
            case "/memory" -> {
                printSection("记忆统计");
                Map<String, Object> stats = memoryStore.getStats();
                System.out.println("  长期记忆 (MEMORY.md): " + stats.get("evergreen_chars") + " 字符");
                System.out.println("  每日文件: " + stats.get("daily_files"));
                System.out.println("  每日条目: " + stats.get("daily_entries"));
                return true;
            }
            case "/search" -> {
                if (arg.isEmpty()) {
                    System.out.println(AnsiColors.YELLOW + "用法: /search <query>"
                            + AnsiColors.RESET);
                    return true;
                }
                printSection("记忆搜索: " + arg);
                List<Map<String, Object>> results = memoryStore.hybridSearch(arg, 5);
                if (results.isEmpty()) {
                    System.out.println(AnsiColors.DIM + "(无结果)" + AnsiColors.RESET);
                } else {
                    for (Map<String, Object> r : results) {
                        String color = (double) r.get("score") > 0.3
                                ? AnsiColors.GREEN : AnsiColors.DIM;
                        System.out.println("  " + color + "[" + r.get("score") + "]"
                                + AnsiColors.RESET + " " + r.get("path"));
                        System.out.println("    " + r.get("snippet"));
                    }
                }
                return true;
            }
            case "/prompt" -> {
                printSection("完整系统提示词");
                String prompt = buildSystemPrompt(
                        "full", bootstrapData, skillsBlock,
                        autoRecall("show prompt"), "main", "terminal");
                if (prompt.length() > 3000) {
                    System.out.println(prompt.substring(0, 3000));
                    System.out.println("\n" + AnsiColors.DIM + "... ("
                            + (prompt.length() - 3000) + " more chars, total "
                            + prompt.length() + ")" + AnsiColors.RESET);
                } else {
                    System.out.println(prompt);
                }
                System.out.println("\n" + AnsiColors.DIM + "提示词总长度: "
                        + prompt.length() + " 字符" + AnsiColors.RESET);
                return true;
            }
            case "/bootstrap" -> {
                printSection("Bootstrap 文件");
                if (bootstrapData.isEmpty()) {
                    System.out.println(AnsiColors.DIM + "(未加载 Bootstrap 文件)"
                            + AnsiColors.RESET);
                } else {
                    for (Map.Entry<String, String> e : bootstrapData.entrySet()) {
                        System.out.println("  " + AnsiColors.BLUE + e.getKey()
                                + AnsiColors.RESET + ": " + e.getValue().length() + " chars");
                    }
                }
                int total = bootstrapData.values().stream()
                        .mapToInt(String::length).sum();
                System.out.println("\n  " + AnsiColors.DIM + "总计: " + total
                        + " 字符 (上限: " + MAX_TOTAL_CHARS + ")" + AnsiColors.RESET);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 打印分节标题 */
    static void printSection(String title) {
        System.out.println("\n" + AnsiColors.MAGENTA + AnsiColors.BOLD
                + "--- " + title + " ---" + AnsiColors.RESET);
    }

    // endregion S06-C: REPL 命令

    // ================================================================
    // 入口
    // ================================================================

    public static void main(String[] args) {
        String apiKey = Config.get("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.startsWith("sk-ant-x")) {
            AnsiColors.printError("Error: ANTHROPIC_API_KEY not set.");
            AnsiColors.printInfo("Copy .env.example to .env and fill in your key.");
            System.exit(1);
        }
        if (!Files.isDirectory(WORKSPACE_DIR)) {
            AnsiColors.printError("Error: Workspace directory not found: " + WORKSPACE_DIR);
            AnsiColors.printInfo("Please run from the light-claw-4j project root.");
            System.exit(1);
        }
        agentLoop();
    }
}
