package com.openclaw.enterprise.tool.handlers;

import com.openclaw.enterprise.common.FileUtils;
import com.openclaw.enterprise.config.AppProperties;
import com.openclaw.enterprise.tool.ToolDefinition;
import com.openclaw.enterprise.tool.ToolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 读取文件工具处理器 — 读取工作目录中的文件内容
 *
 * <p>功能：</p>
 * <ul>
 *   <li>读取指定路径的文件内容 (UTF-8)</li>
 *   <li>路径安全检查 — 防止路径遍历攻击</li>
 *   <li>大文件截断 — 超过 50,000 字符自动截断</li>
 * </ul>
 *
 * <p>claw0 参考: s02_tool_use.py 第 149-163 行 tool_read_file() 函数</p>
 */
@Component
public class ReadFileToolHandler implements ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(ReadFileToolHandler.class);

    /** 最大输出字符数 */
    private static final int MAX_OUTPUT = 50_000;

    /** 工具 Schema 定义 */
    private static final ToolDefinition SCHEMA = new ToolDefinition(
        "read_file",
        "Read the contents of a file. "
            + "Path is relative to the workspace directory. "
            + "Output is truncated if the file exceeds 50,000 characters.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of(
                    "type", "string",
                    "description", "Path to the file (relative to working directory)."
                )
            ),
            "required", List.of("file_path")
        )
    );

    /** 工作目录 — 从 WorkspaceProperties 注入 */
    private final Path workDir;

    /**
     * 构造读取文件工具处理器
     *
     * @param workspaceProps 工作空间配置属性
     */
    public ReadFileToolHandler(AppProperties.WorkspaceProperties workspaceProps) {
        this.workDir = workspaceProps.path();
    }

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public ToolDefinition getSchema() {
        return SCHEMA;
    }

    /**
     * 执行文件读取
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>提取 file_path 参数</li>
     *   <li>安全路径解析 — 防止路径遍历</li>
     *   <li>检查文件是否存在、是否为常规文件</li>
     *   <li>读取文件内容 (UTF-8)</li>
     *   <li>截断过长内容</li>
     * </ol>
     *
     * @param input 工具调用参数 (必须包含 "file_path")
     * @return 文件内容文本或错误信息
     */
    @Override
    public String execute(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        if (filePath == null || filePath.isBlank()) {
            return "Error: 'file_path' parameter is required.";
        }

        try {
            // 安全路径解析 — 防止路径遍历攻击
            Path target = FileUtils.safePath(workDir, filePath);

            // 检查文件是否存在
            if (!Files.exists(target)) {
                return "Error: File not found: " + filePath;
            }

            // 检查是否为常规文件
            if (!Files.isRegularFile(target)) {
                return "Error: Not a file: " + filePath;
            }

            // 读取文件内容
            String content = Files.readString(target);

            // 截断过长内容
            return truncate(content);

        } catch (SecurityException e) {
            // 路径遍历被拦截
            log.warn("Path traversal blocked: {}", filePath);
            return e.getMessage();
        } catch (Exception e) {
            log.error("Failed to read file: {}", filePath, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 截断过长的文件内容
     *
     * @param text 原始文件内容
     * @return 截断后的文本
     */
    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT)
            + "\n... [truncated, " + text.length() + " total chars]";
    }
}
