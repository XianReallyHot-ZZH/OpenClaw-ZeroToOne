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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 编辑文件工具处理器 — 精确替换文件中的字符串
 *
 * <p>功能：</p>
 * <ul>
 *   <li>在文件中查找并替换精确匹配的字符串</li>
 *   <li>old_string 必须在文件中唯一出现 (恰好一次)</li>
 *   <li>建议先读取文件以获取精确的替换文本</li>
 *   <li>路径安全检查 — 防止路径遍历攻击</li>
 * </ul>
 *
 * <p>claw0 参考: s02_tool_use.py 第 180-205 行 tool_edit_file() 函数</p>
 */
@Component
public class EditFileToolHandler implements ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(EditFileToolHandler.class);

    /** 工具 Schema 定义 */
    private static final ToolDefinition SCHEMA = new ToolDefinition(
        "edit_file",
        "Replace an exact string in a file with a new string. "
            + "The old_string must appear exactly once in the file. "
            + "Always read the file first to get the exact text to replace.",
        Map.of(
            "type", "object",
            "properties", Map.of(
                "file_path", Map.of(
                    "type", "string",
                    "description", "Path to the file (relative to working directory)."
                ),
                "old_string", Map.of(
                    "type", "string",
                    "description", "The exact text to find and replace. Must be unique in the file."
                ),
                "new_string", Map.of(
                    "type", "string",
                    "description", "The replacement text."
                )
            ),
            "required", List.of("file_path", "old_string", "new_string")
        )
    );

    /** 工作目录 — 从 WorkspaceProperties 注入 */
    private final Path workDir;

    /**
     * 构造编辑文件工具处理器
     *
     * @param workspaceProps 工作空间配置属性
     */
    public EditFileToolHandler(AppProperties.WorkspaceProperties workspaceProps) {
        this.workDir = workspaceProps.path();
    }

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public ToolDefinition getSchema() {
        return SCHEMA;
    }

    /**
     * 执行文件编辑 (精确字符串替换)
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>提取 file_path, old_string, new_string 参数</li>
     *   <li>安全路径解析 — 防止路径遍历</li>
     *   <li>检查文件是否存在</li>
     *   <li>读取文件内容</li>
     *   <li>统计 old_string 出现次数 — 必须恰好为 1</li>
     *   <li>替换 old_string → new_string</li>
     *   <li>写回文件</li>
     * </ol>
     *
     * @param input 工具调用参数 (必须包含 file_path, old_string, new_string)
     * @return 操作结果描述
     */
    @Override
    public String execute(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        String oldString = (String) input.get("old_string");
        String newString = (String) input.get("new_string");

        if (filePath == null || filePath.isBlank()) {
            return "Error: 'file_path' parameter is required.";
        }
        if (oldString == null) {
            return "Error: 'old_string' parameter is required.";
        }
        if (newString == null) {
            return "Error: 'new_string' parameter is required.";
        }

        try {
            // 安全路径解析 — 防止路径遍历攻击
            Path target = FileUtils.safePath(workDir, filePath);

            // 检查文件是否存在
            if (!Files.exists(target)) {
                return "Error: File not found: " + filePath;
            }

            // 读取文件内容
            String content = Files.readString(target);

            // 统计 old_string 出现次数
            int count = countOccurrences(content, oldString);

            if (count == 0) {
                return "Error: old_string not found in file. "
                    + "Make sure it matches exactly.";
            }

            if (count > 1) {
                return "Error: old_string found " + count
                    + " times. It must be unique. Provide more surrounding context.";
            }

            // 精确替换 (仅替换第一个匹配项)
            String newContent = content.replaceFirst(
                Pattern.quote(oldString), Matcher.quoteReplacement(newString));

            // 写回文件
            Files.writeString(target, newContent);

            log.debug("Edited {}: replaced {} chars with {} chars",
                filePath, oldString.length(), newString.length());
            return "Successfully edited " + filePath;

        } catch (SecurityException e) {
            // 路径遍历被拦截
            log.warn("Path traversal blocked: {}", filePath);
            return e.getMessage();
        } catch (Exception e) {
            log.error("Failed to edit file: {}", filePath, e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 统计子字符串在文本中出现的次数
     *
     * @param text    要搜索的文本
     * @param subStr  要查找的子字符串
     * @return 出现次数
     */
    private int countOccurrences(String text, String subStr) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(subStr, idx)) != -1) {
            count++;
            idx += subStr.length();
        }
        return count;
    }
}
