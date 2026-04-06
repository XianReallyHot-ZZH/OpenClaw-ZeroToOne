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
 * 写入文件工具处理器 — 将内容写入工作目录中的文件
 *
 * <p>功能：</p>
 * <ul>
 *   <li>将内容写入指定路径的文件 (UTF-8)</li>
 *   <li>自动创建父目录</li>
 *   <li>覆盖已有文件内容</li>
 *   <li>路径安全检查 — 防止路径遍历攻击</li>
 * </ul>
 *
 * <p>claw0 参考: s02_tool_use.py 第 166-177 行 tool_write_file() 函数</p>
 */
@Component
public class WriteFileToolHandler implements ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(WriteFileToolHandler.class);

    /** 工具 Schema 定义 */
    private static final ToolDefinition SCHEMA = new ToolDefinition(
        "write_file",
        "Write content to a file. Creates parent directories if needed. "
            + "Overwrites existing content.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of(
                    "type", "string",
                    "description", "Path to the file (relative to working directory)."
                ),
                "content", Map.of(
                    "type", "string",
                    "description", "The content to write."
                )
            ),
            "required", List.of("file_path", "content")
        )
    );

    /** 工作目录 — 从 WorkspaceProperties 注入 */
    private final Path workDir;

    /**
     * 构造写入文件工具处理器
     *
     * @param workspaceProps 工作空间配置属性
     */
    public WriteFileToolHandler(AppProperties.WorkspaceProperties workspaceProps) {
        this.workDir = workspaceProps.path();
    }

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public ToolDefinition getSchema() {
        return SCHEMA;
    }

    /**
     * 执行文件写入
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>提取 file_path 和 content 参数</li>
     *   <li>安全路径解析 — 防止路径遍历</li>
     *   <li>创建父目录 (如果不存在)</li>
     *   <li>写入文件内容 (UTF-8)</li>
     * </ol>
     *
     * @param input 工具调用参数 (必须包含 "file_path" 和 "content")
     * @return 操作结果描述
     */
    @Override
    public String execute(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        String content = (String) input.get("content");

        if (filePath == null || filePath.isBlank()) {
            return "Error: 'file_path' parameter is required.";
        }
        if (content == null) {
            return "Error: 'content' parameter is required.";
        }

        try {
            // 安全路径解析 — 防止路径遍历攻击
            Path target = FileUtils.safePath(workDir, filePath);

            // 创建父目录 (如果不存在)
            Files.createDirectories(target.getParent());

            // 写入文件内容
            Files.writeString(target, content);

            log.debug("Wrote {} chars to {}", content.length(), filePath);
            return "Successfully wrote " + content.length() + " chars to " + filePath;

        } catch (SecurityException e) {
            // 路径遍历被拦截
            log.warn("Path traversal blocked: {}", filePath);
            return e.getMessage();
        } catch (Exception e) {
            log.error("Failed to write file: {}", filePath, e);
            return "Error: " + e.getMessage();
        }
    }
}
