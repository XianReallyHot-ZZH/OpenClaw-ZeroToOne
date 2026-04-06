package com.openclaw.enterprise.tool;

import com.openclaw.enterprise.common.exceptions.ToolExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 工具注册中心 — 管理所有工具处理器的注册、查询和分发
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>自动收集所有 {@link ToolHandler} 实现类 (通过 Spring 构造注入)</li>
 *   <li>按工具名称分发调用请求到对应的处理器</li>
 *   <li>收集所有工具的 Schema 定义，用于构建 Claude API 的 tools 参数</li>
 * </ul>
 *
 * <p>使用 {@link LinkedHashMap} 存储处理器，保持注册顺序。</p>
 *
 * <p>claw0 参考: s02_tool_use.py 中的 TOOLS 列表 + TOOL_HANDLERS 字典 + process_tool_call() 函数</p>
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /** 工具名称 → 工具处理器的映射，LinkedHashMap 保持注册顺序 */
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();

    /**
     * 构造工具注册中心
     *
     * <p>Spring 自动注入所有 {@link ToolHandler} 实现类的列表。
     * 遍历列表，以工具名称为键注册到内部 Map 中。</p>
     *
     * @param handlerList Spring 自动收集的所有 ToolHandler 实现
     */
    public ToolRegistry(List<ToolHandler> handlerList) {
        handlerList.forEach(h -> {
            handlers.put(h.getName(), h);
            log.info("Registered tool: {}", h.getName());
        });
        log.info("ToolRegistry initialized with {} handlers", handlers.size());
    }

    /**
     * 分发工具调用请求
     *
     * <p>根据工具名称查找对应的处理器并执行。
     * 如果工具名称未注册，抛出 {@link ToolExecutionException}。</p>
     *
     * <p>claw0 对应: process_tool_call(tool_name, tool_input) 函数</p>
     *
     * @param name  工具名称
     * @param input 工具调用参数
     * @return 工具执行结果文本
     * @throws ToolExecutionException 如果工具名称未注册
     */
    public String dispatch(String name, Map<String, Object> input) {
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            throw new ToolExecutionException(name, "Unknown tool: " + name);
        }
        return handler.execute(input);
    }

    /**
     * 检查是否存在指定名称的工具处理器
     *
     * @param name 工具名称
     * @return 如果已注册返回 true
     */
    public boolean hasHandler(String name) {
        return handlers.containsKey(name);
    }

    /**
     * 获取所有已注册工具的 Schema 定义
     *
     * <p>用于构建发送给 Claude API 的 tools 参数列表。</p>
     *
     * @return 不可修改的 ToolDefinition 列表
     */
    public List<ToolDefinition> getSchemas() {
        return handlers.values().stream()
            .map(ToolHandler::getSchema)
            .toList();
    }
}
