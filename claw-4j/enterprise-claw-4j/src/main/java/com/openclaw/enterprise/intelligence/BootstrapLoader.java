package com.openclaw.enterprise.intelligence;

import com.openclaw.enterprise.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 引导加载器 — 从工作空间加载 Markdown 文件作为系统提示素材
 *
 * <p>加载顺序 (对应 8 层系统提示中的 Layer 1/2/3/6)：</p>
 * <ol>
 *   <li>IDENTITY.md — 身份定义</li>
 *   <li>SOUL.md — 性格特征</li>
 *   <li>TOOLS.md — 工具使用指南</li>
 *   <li>MEMORY.md — 记忆指令</li>
 *   <li>HEARTBEAT.md — 心跳指令</li>
 *   <li>BOOTSTRAP.md — 启动上下文</li>
 *   <li>USER.md — 用户信息</li>
 *   <li>AGENTS.md — 多 Agent 配置</li>
 * </ol>
 *
 * <p>claw0 参考: s06_intelligence.py 第 106-147 行 BootstrapLoader</p>
 */
@Service
public class BootstrapLoader {

    private static final Logger log = LoggerFactory.getLogger(BootstrapLoader.class);

    /** 工作空间文件加载顺序 */
    private static final List<String> FILE_NAMES = List.of(
        "IDENTITY.md", "SOUL.md", "TOOLS.md", "MEMORY.md",
        "HEARTBEAT.md", "BOOTSTRAP.md", "USER.md", "AGENTS.md"
    );

    /** MINIMAL 模式只加载这两个文件 */
    private static final List<String> MINIMAL_FILES = List.of("AGENTS.md", "TOOLS.md");

    private static final int MAX_FILE_CHARS = 10_000;
    private static final int MAX_TOTAL_CHARS = 50_000;

    private final Path workspacePath;
    private final Map<String, String> fileCache = new ConcurrentHashMap<>();

    public BootstrapLoader(AppProperties.WorkspaceProperties workspaceProps) {
        this.workspacePath = workspaceProps.path();
    }

    /**
     * 按模式加载工作空间文件
     *
     * @param mode 加载模式
     * @return 文件名 → 内容的有序 Map
     */
    public Map<String, String> loadAll(LoadMode mode) {
        if (mode == LoadMode.NONE) {
            return Map.of();
        }

        List<String> targets = mode == LoadMode.MINIMAL ? MINIMAL_FILES : FILE_NAMES;
        Map<String, String> result = new LinkedHashMap<>();
        int totalChars = 0;

        for (String name : targets) {
            String content = loadFile(name);
            if (content == null || content.isBlank()) {
                continue;
            }

            // 单文件截断
            if (content.length() > MAX_FILE_CHARS) {
                int cut = content.lastIndexOf('\n', MAX_FILE_CHARS);
                if (cut <= 0) cut = MAX_FILE_CHARS;
                content = content.substring(0, cut) + "\n\n[... truncated ...]";
            }

            // 总量截断
            if (totalChars + content.length() > MAX_TOTAL_CHARS) {
                int remaining = MAX_TOTAL_CHARS - totalChars;
                if (remaining > 100) {
                    content = content.substring(0, remaining) + "\n\n[... truncated ...]";
                    result.put(name, content);
                }
                break;
            }

            result.put(name, content);
            totalChars += content.length();
        }

        log.debug("Loaded {} files ({} chars) with mode {}", result.size(), totalChars, mode);
        return result;
    }

    /**
     * 获取单个缓存文件
     */
    public Optional<String> getFile(String name) {
        return Optional.ofNullable(loadFile(name));
    }

    /**
     * 清除缓存，强制重新读取
     */
    public void reload() {
        fileCache.clear();
        log.info("Bootstrap file cache cleared");
    }

    private String loadFile(String name) {
        return fileCache.computeIfAbsent(name, n -> {
            try {
                Path file = workspacePath.resolve(n);
                if (Files.exists(file)) {
                    return Files.readString(file);
                }
            } catch (IOException e) {
                log.warn("Failed to load bootstrap file: {}", name, e);
            }
            return null;
        });
    }
}
