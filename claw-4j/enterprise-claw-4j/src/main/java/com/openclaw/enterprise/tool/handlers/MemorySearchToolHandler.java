package com.openclaw.enterprise.tool.handlers;

import com.openclaw.enterprise.intelligence.MemoryEntry;
import com.openclaw.enterprise.intelligence.MemoryStore;
import com.openclaw.enterprise.tool.ToolDefinition;
import com.openclaw.enterprise.tool.ToolHandler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记忆搜索工具 — 混合搜索 (TF-IDF + Hash Vector) 长期记忆
 *
 * <p>Claude 通过此工具在长期记忆中检索与当前对话相关的信息，例如：</p>
 * <ul>
 *   <li>用户之前提到的偏好</li>
 *   <li>历史对话中的关键决策</li>
 *   <li>之前任务的结果摘要</li>
 * </ul>
 *
 * <p>搜索使用混合算法：</p>
 * <ul>
 *   <li>关键词路径 (TF-IDF，权重 30%) — 精确匹配词语</li>
 *   <li>向量路径 (Hash Vector，权重 70%) — 语义近似匹配</li>
 *   <li>时间衰减 — 近期记忆权重更高</li>
 *   <li>MMR 重排序 — 去除冗余结果</li>
 * </ul>
 *
 * <p>参数：</p>
 * <ul>
 *   <li>{@code query} (必填) — 搜索查询文本</li>
 *   <li>{@code top_k} (可选，默认 5) — 返回结果数量上限</li>
 * </ul>
 *
 * <p>claw0 参考: s02_tool_use.py 中 memory_search 工具</p>
 */
@Component
public class MemorySearchToolHandler implements ToolHandler {

    private final MemoryStore memoryStore;

    public MemorySearchToolHandler(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public String getName() {
        return "memory_search";
    }

    @Override
    public ToolDefinition getSchema() {
        // 构建 JSON Schema — 两个参数：query (必填) 和 top_k (可选)
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "搜索查询文本，用于在长期记忆中查找相关内容");

        Map<String, Object> topKProp = new LinkedHashMap<>();
        topKProp.put("type", "integer");
        topKProp.put("description", "返回结果数量上限");
        topKProp.put("default", 5);
        topKProp.put("minimum", 1);
        topKProp.put("maximum", 20);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", queryProp);
        properties.put("top_k", topKProp);
        schema.put("properties", properties);
        schema.put("required", List.of("query"));

        return new ToolDefinition(
            "memory_search",
            "在长期记忆中搜索相关内容。使用混合搜索算法（关键词 + 语义向量），"
                + "支持时间衰减和去重。用于回忆用户偏好、历史事实或之前的对话内容。",
            schema
        );
    }

    @Override
    public String execute(Map<String, Object> input) {
        // 提取必填参数 query
        Object queryObj = input.get("query");
        if (queryObj == null || queryObj.toString().isBlank()) {
            return "Error: 'query' parameter is required and cannot be empty.";
        }
        String query = queryObj.toString();

        // 提取可选参数 top_k，默认 5，上限 20
        int topK = 5;
        Object topKObj = input.get("top_k");
        if (topKObj != null) {
            try {
                topK = Math.min(Math.max(Integer.parseInt(topKObj.toString()), 1), 20);
            } catch (NumberFormatException e) {
                topK = 5;
            }
        }

        // 执行混合搜索
        List<MemoryEntry> results = memoryStore.hybridSearch(query, topK);

        // 格式化结果
        if (results.isEmpty()) {
            return "No memories found for query: " + query;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" memory(es):\n\n");

        for (int i = 0; i < results.size(); i++) {
            MemoryEntry entry = results.get(i);
            sb.append(i + 1).append(". [").append(entry.category()).append("] ")
              .append(entry.content()).append("\n");
        }

        return sb.toString().trim();
    }
}
