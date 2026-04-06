package com.openclaw.enterprise.tool.handlers;

import com.openclaw.enterprise.intelligence.MemoryStore;
import com.openclaw.enterprise.tool.ToolDefinition;
import com.openclaw.enterprise.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记忆写入工具 — 将内容写入 MemoryStore 的每日 JSONL 文件
 *
 * <p>Claude 通过此工具主动保存重要信息到长期记忆，例如：</p>
 * <ul>
 *   <li>用户的偏好和习惯</li>
 *   <li>对话中的关键事实</li>
 *   <li>任务执行结果摘要</li>
 * </ul>
 *
 * <p>参数：</p>
 * <ul>
 *   <li>{@code content} (必填) — 要记忆的内容文本</li>
 *   <li>{@code category} (可选，默认 "general") — 记忆分类标签</li>
 * </ul>
 *
 * <p>claw0 参考: s02_tool_use.py 中 memory_write 工具</p>
 */
@Component
public class MemoryWriteToolHandler implements ToolHandler {

    private final MemoryStore memoryStore;

    public MemoryWriteToolHandler(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public String getName() {
        return "memory_write";
    }

    @Override
    public ToolDefinition getSchema() {
        // 构建 JSON Schema — 两个参数：content (必填) 和 category (可选)
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> contentProp = new LinkedHashMap<>();
        contentProp.put("type", "string");
        contentProp.put("description", "要保存到长期记忆的内容");

        Map<String, Object> categoryProp = new LinkedHashMap<>();
        categoryProp.put("type", "string");
        categoryProp.put("description", "记忆分类标签，如 'preference', 'fact', 'task' 等");
        categoryProp.put("default", "general");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("content", contentProp);
        properties.put("category", categoryProp);
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("content"));

        return new ToolDefinition(
            "memory_write",
            "将重要信息写入长期记忆。用于保存用户偏好、关键事实、任务结果等需要跨对话持久化的内容。",
            schema
        );
    }

    @Override
    public String execute(Map<String, Object> input) {
        // 提取必填参数 content
        Object contentObj = input.get("content");
        if (contentObj == null || contentObj.toString().isBlank()) {
            return "Error: 'content' parameter is required and cannot be empty.";
        }
        String content = contentObj.toString();

        // 提取可选参数 category，默认 "general"
        String category = "general";
        Object categoryObj = input.get("category");
        if (categoryObj != null && !categoryObj.toString().isBlank()) {
            category = categoryObj.toString();
        }

        // 写入记忆存储
        memoryStore.writeMemory(content, category);

        return "Memory saved: [" + category + "] " +
               content.substring(0, Math.min(80, content.length())) +
               (content.length() > 80 ? "..." : "");
    }
}
