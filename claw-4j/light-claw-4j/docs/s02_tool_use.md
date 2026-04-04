# S02 Tool Use -- "给模型装上双手"

## 1. 核心概念

Tool Use = JSON Schema 定义（告诉模型有哪些工具） + Handler 分发表（执行模型选择的工具）。
Agent loop 结构不变，只是 `stop_reason == "tool_use"` 时，查表执行工具，将结果回传给 LLM。

模型输出 tool_use 时，代码需要：
1. 从响应中提取 `ToolUseBlock`（包含 name、input、id）
2. 在 `TOOL_HANDLERS` 中按 name 查找对应的 handler
3. 执行 handler，将结果用 `ToolResultBlockParam` 包装回传
4. 继续内层循环，让模型看到工具结果后决定下一步

本节实现 4 个工具：`bash`、`read_file`、`write_file`、`edit_file`。

## 2. 架构图

```mermaid
flowchart TD
    A[用户输入] --> B[追加到 messages]
    B --> C[调用 LLM API + tools]
    C --> D{stop_reason?}
    D -->|end_turn| E[打印回复]
    D -->|tool_use| F[提取 ToolUseBlock]
    F --> G["TOOL_HANDLERS[name](input)"]
    G --> H[构建 ToolResultBlockParam]
    H --> I[追加 tool_result 到 messages]
    I --> C
```

## 3. 关键代码片段

### Java: ProcessBuilder + ToolUnion + 分发表

```java
// 工具 Schema 定义: 用 JsonValue 构建 JSON Schema
Tool tool = Tool.builder()
    .name("bash")
    .description("Run a shell command")
    .inputSchema(Tool.InputSchema.builder()
        .type(JsonValue.from("object"))
        .properties(propsBuilder.build())
        .required(List.of("command"))
        .build())
    .build();

// ToolUnion 包装: API 要求 List<ToolUnion>
static final List<ToolUnion> TOOLS = List.of(
    ToolUnion.ofTool(bashTool),
    ToolUnion.ofTool(readFileTool)
);

// 分发表: Map<String, Function<Map<String,Object>, String>>
static final Map<String, Function<Map<String, Object>, String>> TOOL_HANDLERS
    = new LinkedHashMap<>();
TOOL_HANDLERS.put("bash", S02ToolUse::toolBash);

// 执行工具调用: 用 ProcessBuilder 替代 Python 的 subprocess.run
ProcessBuilder pb = new ProcessBuilder("sh", "-c", command)
    .directory(WORKDIR.toFile())
    .redirectErrorStream(true);

// 工具结果回传: ToolResultBlockParam 必须携带 tool_use_id
toolResultBlocks.add(ContentBlockParam.ofToolResult(
    ToolResultBlockParam.builder()
        .toolUseId(toolUse.id())  // 关键: 匹配请求的 id
        .content(result)
        .build()));
```

### Python 对比

```python
# Python 的工具定义是纯 dict
tools = [{
    "name": "bash",
    "description": "Run a shell command",
    "input_schema": { "type": "object", "properties": {...} }
}]

# Python 执行命令
result = subprocess.run(command, shell=True, capture_output=True, text=True)

# Python 回传工具结果
messages.append({
    "role": "user",
    "content": [{"type": "tool_result", "tool_use_id": id, "content": result}]
})
```

**核心差异**：
- Java 用 `ToolUnion.ofTool()` 包装、`JsonValue.from()` 构建 schema；Python 直接用 dict
- Java 用 `ProcessBuilder` 执行命令；Python 用 `subprocess.run()`
- Java 用 `Function<Map<String,Object>,String>` 做分发表；Python 用 `dict[str, Callable]`

## 4. 运行方式

```bash
mvn compile exec:java -Dexec.mainClass="com.claw0.sessions.S02ToolUse"
```

## 5. REPL 命令

| 命令 | 说明 |
|------|------|
| `quit` | 退出程序 |
| `exit` | 退出程序 |
| `Ctrl+C` | 强制退出 |

## 6. 学习要点

1. **Tool 是数据（Schema）+ Handler 分发表**：模型只看到 JSON Schema 描述，不看到实现。Handler 是一个 `Map<String, Function>`，按工具名查找执行。
2. **ToolUnion 包装 Tool 对象给 API**：Anthropic Java SDK 要求 `List<ToolUnion>`，用 `ToolUnion.ofTool(tool)` 包装。这是因为 API 联合类型在 Java 中需要容器。
3. **ToolResultBlockParam 必须携带 tool_use_id**：模型可能同时调用多个工具，`tool_use_id` 用于匹配请求和结果。缺少 id 会导致 API 报错。
4. **危险命令过滤是必要的安全层**：`DANGEROUS_COMMANDS` 列表拦截 `rm -rf /`、`mkfs` 等命令。`safePath()` 防止路径遍历攻击，确保文件操作不越界。
