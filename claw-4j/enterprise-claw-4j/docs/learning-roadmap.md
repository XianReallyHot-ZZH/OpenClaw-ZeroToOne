# Learning Roadmap -- "What comes after the gateway"

本文档为完成 enterprise-claw-4j 学习后的后续进阶路线. 每个方向包含: 为什么学、学什么、练什么、参考资源.

**前置基础**: 已理解 enterprise-claw-4j 的全部 14 个模块, 包括 Agent Loop、工具系统、会话持久化、多渠道路由、提示词工程、记忆检索、弹性重试、并发控制和容器化部署.

---

## 目录

1. [流式响应 (Streaming SSE)](#1-流式响应-streaming-sse)
2. [RAG 检索增强生成](#2-rag-检索增强生成)
3. [Spring AI 框架实战](#3-spring-ai-框架实战)
4. [MCP 协议集成](#4-mcp-协议集成)
5. [多 Agent 协作](#5-多-agent-协作)
6. [可观测性与评估](#6-可观测性与评估)
7. [推荐学习顺序](#7-推荐学习顺序)

---

## 1. 流式响应 (Streaming SSE)

### 为什么

当前 AgentLoop 调用 `client.messages().create()` 等待完整响应后才返回. 用户体验差 — 一个长回复可能等待 10-30 秒无任何输出. 生产环境中流式输出是基本要求.

### 学什么

#### 1.1 Anthropic Streaming API

```java
// 当前: 阻塞等待完整响应
Message response = client.messages().create(params);

// 流式: 逐事件处理
client.messages().streamCreate(params).forEach(event -> {
    switch (event) {
        case MessageStartEvent e -> { /* 开始, 包含 model/id */ }
        case ContentBlockStartEvent e -> { /* 新内容块开始 */ }
        case ContentBlockDeltaEvent e -> {
            // 逐 token 文本增量
            if (e.delta() instanceof TextDelta textDelta) {
                emitter.send(textDelta.text());
            }
            // 或工具调用增量
            if (e.delta() instanceof ToolUseDelta toolDelta) {
                // 累积 tool input JSON
            }
        }
        case ContentBlockStopEvent e -> { /* 内容块结束 */ }
        case MessageDeltaEvent e -> { /* 最终 stop_reason */ }
        case MessageStopEvent e -> { /* 消息结束 */ }
        default -> {}
    }
});
```

> SDK 的 `streamCreate()` 返回事件流, 每个事件是 sealed interface 的变体.
> 需要手动累积文本和工具调用输入, 因为它们是增量到达的.

#### 1.2 Spring SSE 推送

```java
// 方式 A: SseEmitter (Spring MVC, 阻塞式)
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@RequestBody ChatRequest req) {
    SseEmitter emitter = new SseEmitter(120_000L);  // 2 分钟超时
    commandQueue.enqueue("main", () -> {
        agentLoop.streamTurn(req, delta -> {
            emitter.send(SseEmitter.event().data(delta));
        });
        emitter.complete();
        return null;
    });
    return emitter;
}

// 方式 B: Flux<ServerSentEvent> (WebFlux, 响应式)
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest req) {
    return Flux.create(sink -> {
        agentLoop.streamTurn(req, delta -> {
            sink.next(ServerSentEvent.builder(delta).build());
        }, () -> sink.complete());
    });
}
```

#### 1.3 流式场景的工具调用

```
用户消息 → LLM 开始流式输出文本...
         → 突然 stop_reason=TOOL_USE (工具调用嵌在流中间)
         → 执行工具, 将结果追加到消息
         → 继续流式调用 LLM
         → 最终 stop_reason=END_TURN, 流结束
```

> 流式模式下工具调用更复杂: 需要在流中间中断, 执行工具, 然后开始新的流.
> AgentLoop 的 while-true 循环不变, 但每次迭代的返回方式从同步变为事件流.

#### 1.4 WebSocket 逐帧推送

```java
// GatewayWebSocketHandler: 将流式增量推送给客户端
void handleStreamRequest(WebSocketSession session, JsonRpcRequest request) {
    agentLoop.streamTurn(context,
        delta -> session.sendMessage(new TextMessage(toJsonRpcNotification("stream.delta", delta))),
        () -> session.sendMessage(new TextMessage(toJsonRpcNotification("stream.done", null)))
    );
}
```

### 练习

1. 在 enterprise-claw-4j 中新增 `StreamingAgentLoop` 类, 封装流式调用逻辑
2. 在 `GatewayController` 添加 `POST /api/v1/stream` 端点, 返回 `SseEmitter`
3. 修改 `GatewayWebSocketHandler` 支持 `stream` JSON-RPC 方法
4. 在 CLI 渠道实现逐 token 打印效果 (打字机动画)
5. 处理流式工具调用: 检测 ContentBlockDelta 中的 InputJsonDelta, 累积后触发工具执行

### 参考资源

- Anthropic Streaming Guide: https://docs.anthropic.com/en/api/streaming
- Anthropic Java SDK `MessageStreamEvent` 源码
- Spring SseEmitter 文档: https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html

---

## 2. RAG 检索增强生成

### 为什么

当前 MemoryStore 使用 TF-IDF + Hash 向量的简化检索. 真正的 RAG 需要 Embedding 模型生成语义向量, 向量数据库存储和检索, 文档切片策略, 以及检索结果的重排序. 这是 AI Agent 最核心的能力扩展方向.

### 学什么

#### 2.1 Embedding 模型

```java
// Anthropic 不提供 Embedding API, 需要第三方:
// 选项 A: OpenAI Embedding
POST https://api.openai.com/v1/embeddings
{ "model": "text-embedding-3-small", "input": "你好世界" }
→ float[1536] 向量

// 选项 B: 本地模型 (BGE / M3E)
// 使用 ONNX Runtime 或 DJL 加载本地 Embedding 模型
// 优点: 无 API 调用开销, 数据不出境
```

> Anthropic 专注于生成模型, 没有自己的 Embedding API.
> 生产环境常用 OpenAI text-embedding-3-small (性价比高) 或本地 BGE-M3 (中英文双语).

#### 2.2 向量数据库

| 数据库 | 特点 | 适合场景 |
|--------|------|---------|
| pgvector | PostgreSQL 扩展, SQL 查询 | 已有 PG 基础设施 |
| Milvus | 分布式, 高性能 | 大规模 (亿级向量) |
| Chroma | 轻量, Python-native | 原型开发 |
| Qdrant | Rust 实现, 过滤能力强 | 需要元数据过滤 |
| Weaviate | 内置多模态 | 多媒体检索 |

```java
// pgvector 示例: 替换 MemoryStore 的检索层
@Repository
public class VectorMemoryRepository {
    private final JdbcTemplate jdbc;

    public List<MemoryEntry> search(float[] queryVector, int topK) {
        return jdbc.query(
            "SELECT id, content, category, 1 - (embedding <=> ?) AS score " +
            "FROM memories ORDER BY embedding <=> ? LIMIT ?",
            (rs, i) -> new MemoryEntry(rs.getString("content"), rs.getString("category"),
                null, "evergreen", rs.getDouble("score")),
            new PgVector(queryVector), new PgVector(queryVector), topK);
    }
}
```

#### 2.3 文档切片策略

```java
public class DocumentChunker {
    // 策略 1: 固定大小切片 (简单, 但可能切断语义)
    List<String> fixedSize(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += chunkSize - overlap) {
            chunks.add(text.substring(i, Math.min(i + chunkSize, text.length())));
        }
        return chunks;
    }

    // 策略 2: 按段落 + 递归切片 (推荐)
    // 先按 \n\n 拆分, 超长段落按句子拆分, 仍然超长则按字符拆分
    // 每层保留 overlap 字符与上下文重叠

    // 策略 3: 语义切片 (高级)
    // 用 Embedding 计算相邻句子的相似度, 在相似度骤降处切分
}
```

#### 2.4 RAG 管线

```
用户查询
  → Embedding 编码
  → 向量数据库检索 Top-K
  → (可选) 重排序: Cohere Rerank / LLM Rerank
  → 构建 Prompt: query + 检索到的上下文
  → LLM 生成回答
  → 返回 + 引用来源
```

```java
@Service
public class RagService {
    public String query(String userQuery, String agentId) {
        // 1. 编码查询
        float[] queryVector = embeddingClient.embed(userQuery);

        // 2. 检索
        List<MemoryEntry> results = vectorRepo.search(queryVector, 5);

        // 3. 构建 prompt
        StringBuilder context = new StringBuilder("## Retrieved Context\n");
        for (MemoryEntry entry : results) {
            context.append("- ").append(entry.content()).append("\n");
        }

        // 4. 注入系统提示词
        String systemPrompt = promptAssembler.assemble(ctx, agentId, model)
            + "\n\n" + context;

        // 5. 调用 LLM
        return agentLoop.runTurn(systemPrompt, messages, tools);
    }
}
```

### 练习

1. 在 enterprise-claw-4j 中添加 `embedding` 包, 封装 Embedding API 调用
2. 用 pgvector 替换 MemoryStore 的 Hash 向量检索, 保持接口不变
3. 实现文档切片器, 支持文件上传 → 切片 → Embedding → 存储
4. 在 PromptAssembler 的 Layer 5 集成 RAG 检索结果
5. 添加引用来源标注: 回答中标注信息来自哪个文档

### 参考资源

- Anthropic RAG Guide: https://docs.anthropic.com/en/docs/build-with-claude/retrieval-augmented-generation
- pgvector: https://github.com/pgvector/pgvector
- LangChain RAG 教程 (概念通用): https://python.langchain.com/docs/tutorials/rag/

---

## 3. Spring AI 框架实战

### 为什么

手写 Agent 框架是为了理解原理. 生产环境中 Spring AI 提供了开箱即用的多模型支持、RAG 管线、工具集成和可观测性. 学会对比手写实现和框架设计, 能加深理解.

### 学什么

#### 3.1 Spring AI 核心抽象

| Spring AI 概念 | enterprise-claw-4j 对应 |
|---------------|------------------------|
| ChatClient | AgentLoop |
| ChatModel | AnthropicClient |
| Advisor | PromptAssembler 的各层 |
| ToolCallback | ToolHandler |
| VectorStore | MemoryStore |
| ChatMemory | SessionStore |
| MessageChatMemoryAdvisor | ContextGuard |

```java
// Spring AI 的 ChatClient 用法
@Bean
ChatClient chatClient(ChatClient.Builder builder) {
    return builder
        .defaultSystem("You are a helpful assistant")
        .defaultAdvisors(
            MessageChatMemoryAdvisor.builder(memory).build(),
            QuestionAnswerAdvisor.builder(vectorStore).build()
        )
        .defaultTools(new ReadFileTool(), new BashTool())
        .build();
}

// 使用
String response = chatClient.prompt()
    .user("你好")
    .call()
    .content();
```

#### 3.2 核心模块对比学习

**ChatModel 抽象 — 多模型支持**:
```java
// Spring AI: 统一接口, 切换模型只改配置
@Bean
ChatModel chatModel() {
    // OpenAI, Anthropic, Ollama, Azure 都实现同一接口
    return new AnthropicChatModel(...);
}
```

**Advisor 链 — 提示词工程**:
```java
// Spring AI: Advisor 是拦截器链, 类似 Servlet Filter
// 前: 注入上下文 (记忆, 检索结果)
// 后: 处理响应 (日志, 评估)
public class MemoryAdvisor implements CallAroundAdvisor {
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest req, CallAroundAdvisorChain chain) {
        // 前置: 从 ChatMemory 加载历史
        req = req.withMessages(memory.get(sessionId));
        AdvisedResponse resp = chain.nextAroundCall(req);
        // 后置: 保存新的交互到 ChatMemory
        memory.add(sessionId, resp.response());
        return resp;
    }
}
```

**ToolCallback — 工具注册**:
```java
// Spring AI: 注解式工具定义
@Component
public class FileTools {
    @Tool(description = "Read a file from workspace")
    public String readFile(@ToolParam(description = "File path") String path) {
        return Files.readString(Path.of(path));
    }
}
```

#### 3.3 Spring AI vs enterprise-claw-4j 设计取舍

| 维度 | enterprise-claw-4j | Spring AI |
|------|-------------------|-----------|
| 学习价值 | 理解每个细节 | 快速交付业务 |
| 模型支持 | 仅 Anthropic | OpenAI/Anthropic/Ollama/Azure... |
| RAG | 手写 Hash 向量 | pgvector/Chroma/Milvus/Pinecone |
| 工具定义 | 手写 JSON Schema | @Tool 注解自动生成 |
| 提示词 | 手动 8 层组装 | Advisor 链自动注入 |
| 流式 | 未实现 | 开箱即用 |
| 可观测性 | Micrometer | Micrometer + OpenTelemetry |

### 练习

1. 用 Spring AI 重写 enterprise-claw-4j 的 AgentLoop, 对比代码量和灵活性
2. 用 Spring AI 的 VectorStore 替换 MemoryStore, 比较检索质量
3. 实现 Spring AI 的自定义 Advisor, 复刻 PromptAssembler 的 8 层逻辑
4. 使用 Spring AI 的 `@Tool` 注解重写 ToolHandler, 对比手写 JSON Schema 的优劣

### 参考资源

- Spring AI 官方文档: https://docs.spring.io/spring-ai/reference/
- Spring AI GitHub: https://github.com/spring-projects/spring-ai
- LangChain4j (对比学习): https://github.com/langchain4j/langchain4j

---

## 4. MCP 协议集成

### 为什么

MCP (Model Context Protocol) 是 Anthropic 推出的开放标准, 定义了 AI 模型与外部工具/数据源的通信协议. 类比 USB-C 统一了设备接口, MCP 统一了 Agent 工具接口. 掌握 MCP 意味着你的 Agent 可以对接生态中的任何 MCP Server.

### 学什么

#### 4.1 MCP 核心概念

```
┌─────────────┐    MCP 协议    ┌─────────────┐
│  MCP Host   │◄─────────────►│ MCP Server  │
│  (你的 Agent)│    JSON-RPC   │  (工具提供者)│
└─────────────┘               └─────────────┘

MCP Host: 发起连接, 请求工具列表, 调用工具
MCP Server: 注册工具, 响应调用, 暴露资源
传输层: stdio (本地) / SSE (远程)
```

三大能力:
- **Tools**: 模型可调用的函数 (对应现有 ToolHandler)
- **Resources**: 模型可读取的数据 (文件, API 响应)
- **Prompts**: 模板化的提示词 (预定义交互模式)

#### 4.2 MCP Server 端实现 (Java)

```java
// 将现有 ToolHandler 适配为 MCP Server
@Component
public class Claw4jMcpServer {
    // 工具注册
    @McpTool(name = "read_file", description = "读取工作区文件")
    public McpToolResult readFile(@McpParam(name = "path") String path) {
        return McpToolResult.content(toolRegistry.dispatch("read_file", Map.of("path", path)));
    }

    @McpTool(name = "bash", description = "执行 Shell 命令")
    public McpToolResult bash(@McpParam(name = "command") String command) {
        return McpToolResult.content(toolRegistry.dispatch("bash", Map.of("command", command)));
    }

    // 资源暴露
    @McpResource(uri = "memory://evergreen", name = "永久记忆")
    public String evergreenMemory() {
        return bootstrapLoader.load("MEMORY.md");
    }
}
```

#### 4.3 MCP Client 端集成

```java
// 在 AgentLoop 中集成 MCP Client
@Service
public class McpAwareAgentLoop {
    private final List<McpServerConnection> mcpServers;

    List<ToolUnion> collectAllTools() {
        List<ToolUnion> tools = new ArrayList<>(toolRegistry.getSchemas());
        for (McpServerConnection server : mcpServers) {
            // 从 MCP Server 动态拉取工具列表
            List<McpToolInfo> mcpTools = server.listTools();
            for (McpToolInfo t : mcpTools) {
                tools.add(convertToToolUnion(t));
            }
        }
        return tools;
    }

    String dispatchTool(String name, Map<String, Object> input) {
        // 先查本地 ToolRegistry
        if (toolRegistry.hasHandler(name)) {
            return toolRegistry.dispatch(name, input);
        }
        // 再查 MCP Servers
        for (McpServerConnection server : mcpServers) {
            if (server.hasTool(name)) {
                return server.callTool(name, input);
            }
        }
        throw new ToolExecutionException(name, "Unknown tool: " + name);
    }
}
```

#### 4.4 SSE 传输 + JSON-RPC

```json
// MCP 协议示例 (与现有 WebSocket JSON-RPC 类似)
→ {"jsonrpc":"2.0","method":"tools/list","id":1}
← {"jsonrpc":"2.0","result":{"tools":[{"name":"read_file","description":"...","inputSchema":{...}}]},"id":1}

→ {"jsonrpc":"2.0","method":"tools/call","params":{"name":"read_file","arguments":{"path":"README.md"}},"id":2}
← {"jsonrpc":"2.0","result":{"content":[{"type":"text","text":"# enterprise-claw-4j..."}]},"id":2}
```

### 练习

1. 在 enterprise-claw-4j 中新增 `mcp` 包, 实现 MCP Server (将 6 个内置工具暴露为 MCP Tools)
2. 实现 MCP Client, 连接外部 MCP Server (如 GitHub MCP, 文件系统 MCP)
3. 修改 AgentLoop, 统一处理本地工具和 MCP 工具
4. 通过 SSE 传输实现远程 MCP Server 连接

### 参考资源

- MCP 官方规范: https://modelcontextprotocol.io/
- MCP Java SDK: https://github.com/modelcontextprotocol/java-sdk
- Anthropic MCP 介绍: https://docs.anthropic.com/en/docs/build-with-claude/mcp

---

## 5. 多 Agent 协作

### 为什么

当前 enterprise-claw-4j 的多 Agent 是路由隔离式: 每条消息路由到独立 Agent, Agent 之间不通信. 真实场景需要 Agent 间协作: 一个 Agent 分解任务, 多个 Agent 并行执行, 汇总结果.

### 学什么

#### 5.1 协作模式

| 模式 | 说明 | 适用场景 |
|------|------|---------|
| Handoff | Agent A 转交对话给 Agent B | 客服转技术支持 |
| Supervisor | 主管 Agent 分配任务给 Worker | 复杂任务分解 |
| Pipeline | Agent 链式处理 | 写作 → 审核 → 翻译 |
| Debate | 多 Agent 辩论, 取共识 | 决策分析 |

#### 5.2 Handoff 模式

```java
// Agent A 判断需要转交, 返回特殊工具调用
@Service
public class HandoffTool implements ToolHandler {
    @Override
    public String execute(Map<String, Object> input) {
        String targetAgent = (String) input.get("agent_id");
        String reason = (String) input.get("reason");
        // 将当前 session 转交给目标 Agent
        agentManager.transferSession(currentSession, targetAgent);
        return "Handed off to " + targetAgent + ": " + reason;
    }
}
```

#### 5.3 Supervisor 模式

```java
@Service
public class SupervisorOrchestrator {
    public String execute(String task) {
        // 1. Supervisor 分解任务
        String plan = supervisorAgent.plan(task);

        // 2. 解析子任务
        List<SubTask> subTasks = parsePlan(plan);

        // 3. 并行分发给 Worker (利用 CommandQueue)
        List<CompletableFuture<String>> futures = subTasks.stream()
            .map(st -> commandQueue.enqueue("main", () ->
                workerAgent.execute(st.description())))
            .toList();

        // 4. 等待所有结果
        List<String> results = futures.stream()
            .map(f -> f.get(120, TimeUnit.SECONDS))
            .toList();

        // 5. Supervisor 汇总
        return supervisorAgent.summarize(task, results);
    }
}
```

#### 5.4 Agent 间通信

```
方案 A: 共享工作区文件
  Agent A 写入 workspace/shared/task-result.md
  Agent B 读取该文件
  优点: 简单; 缺点: 无实时性

方案 B: 消息队列
  Agent A 发布消息到 Kafka topic "agent-communication"
  Agent B 订阅该 topic
  优点: 实时, 可扩展; 缺点: 引入新依赖

方案 C: 内部 API 调用
  Agent A 调用 GatewayService.route() 发消息给 Agent B
  优点: 复用现有架构; 缺点: 耦合
```

### 练习

1. 实现 Handoff 工具: Agent 可以将对话转交给另一个 Agent
2. 实现 Supervisor 模式: 编码 Agent 分解任务, 交给执行 Agent 执行
3. 通过共享工作区文件实现 Agent 间异步通信
4. 在 GatewayWebSocketHandler 推送 Agent 间协作事件

### 参考资源

- Anthropic Agent SDK: https://docs.anthropic.com/en/docs/build-with-claude/agent-sdk
- OpenAI Swarm (概念参考): https://github.com/openai/swarm
- CrewAI (多 Agent 框架概念): https://github.com/crewAIInc/crewAI

---

## 6. 可观测性与评估

### 为什么

Agent 上线后最大的挑战不是功能, 而是知道它是否正常工作: 每次 LLM 调用花了多少钱? 工具调用是否合理? 回复质量如何? 哪些环节是瓶颈? 没有可观测性, Agent 就是黑箱.

### 学什么

#### 6.1 OpenTelemetry 链路追踪

```java
// 每次 Agent 请求的完整链路
tracer: agent-turn
  ├── span: prompt-assemble (8 层构建, 5ms)
  ├── span: resilience-runner
  │     ├── span: anthropic-api-call #1 (2.3s)
  │     ├── span: tool:read_file (12ms)
  │     ├── span: anthropic-api-call #2 (1.8s)
  │     └── span: anthropic-api-call #3 (0.9s, final)
  ├── span: session-persist (8ms)
  └── span: delivery-enqueue (3ms)

// 集成方式
@Configuration
public class TelemetryConfig {
    @Bean
    Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("enterprise-claw-4j", "0.1.0");
    }
}

// 在 AgentLoop 中添加 span
Span span = tracer.spanBuilder("agent-turn")
    .setAttribute("agent.id", agentId)
    .setAttribute("model", model)
    .startSpan();
try (var scope = span.makeCurrent()) {
    return doRunTurn(...);
} finally {
    span.end();
}
```

#### 6.2 Token 消耗与成本监控

```java
@Component
public class CostTracker {
    private final MeterRegistry registry;
    private final Counter inputTokens;
    private final Counter outputTokens;
    private final Counter totalApiCalls;

    // 每次调用后记录
    public void record(Usage usage, String model) {
        registry.counter("anthropic.tokens.input",
            "model", model).increment(usage.inputTokens());
        registry.counter("anthropic.tokens.output",
            "model", model).increment(usage.outputTokens());
    }

    // 成本估算
    // claude-sonnet: $3/1M input, $15/1M output
    // claude-haiku: $0.25/1M input, $1.25/1M output
}
```

#### 6.3 LLM-as-Judge 评估

```java
@Service
public class ResponseEvaluator {
    // 用 LLM 评估 LLM 的回复质量
    public EvaluationResult evaluate(String userQuery, String agentResponse) {
        String evalPrompt = """
            Evaluate the following AI response on a scale of 1-5 for:
            1. Relevance (是否切题)
            2. Accuracy (是否准确)
            3. Completeness (是否完整)
            4. Safety (是否安全)

            User query: %s
            AI response: %s

            Output JSON: {"relevance": N, "accuracy": N, "completeness": N, "safety": N, "comment": "..."}
            """.formatted(userQuery, agentResponse);

        String result = evaluatorClient.messages().create(/* ... */);
        return parseEvaluation(result);
    }
}
```

#### 6.4 护栏 (Guardrails)

```java
// 输入护栏: 拒绝不安全请求
@Component
public class InputGuardrail {
    public String check(String userInput) {
        // 关键词过滤
        // PII 检测 (电话/身份证/信用卡号)
        // 话题限制 (超出 Agent 能力范围的请求)
        return userInput; // 或抛出异常
    }
}

// 输出护栏: 过滤敏感信息
@Component
public class OutputGuardrail {
    public String check(String agentOutput) {
        // 隐藏 API Key / 密码
        // 隐藏内部 IP / 文件路径
        return agentOutput;
    }
}
```

### 练习

1. 集成 Micrometer, 在 AgentLoop 中记录每次调用的 token 使用和延迟
2. 用 Spring Boot Actuator + Prometheus + Grafana 搭建监控面板
3. 实现 LLM-as-Judge 评估器, 自动评估 Agent 回复质量
4. 添加输入/输出护栏, 过滤 PII 信息
5. 在 Grafana 面板展示: QPS, P50/P95/P99 延迟, Token 消耗趋势, 错误率

### 参考资源

- OpenTelemetry Java: https://opentelemetry.io/docs/languages/java/
- Micrometer 文档: https://micrometer.io/docs
- Anthropic Token 计费: https://docs.anthropic.com/en/docs/about-claude/models

---

## 7. 推荐学习顺序

```
enterprise-claw-4j (已完成)
       │
       ▼
① Streaming SSE        ← 最直接的体验提升, 1-2 周
       │
       ▼
② RAG                  ← 最核心的能力扩展, 2-3 周
       │
       ▼
③ Spring AI 框架       ← 理解工业标准实现, 1-2 周
       │
       ▼
④ MCP 协议             ← 面向未来的生态接入, 1-2 周
       │
       ▼
⑤ 多 Agent 协作        ← 复杂任务编排, 2-3 周
       │
       ▼
⑥ 可观测性与评估       ← 生产上线必备, 1-2 周
```

**第 ①② 项可以在 enterprise-claw-4j 项目上直接迭代**, 不需要开新项目:
- Streaming: 修改 AgentLoop + 新增 SSE 端点
- RAG: 替换 MemoryStore 的检索层

**第 ③ 项建议开一个独立项目**, 用 Spring AI 重写核心功能, 对比学习.

**第 ④⑤⑥ 项可以回到 enterprise-claw-4j 继续迭代**, 或在 Spring AI 项目上实验.

### 预计总时间

| 阶段 | 时间 | 产出 |
|------|------|------|
| Streaming | 1-2 周 | 流式 AgentLoop + SSE/WebSocket 推送 |
| RAG | 2-3 周 | pgvector 集成 + 文档切片 + 语义检索 |
| Spring AI | 1-2 周 | 对比报告 + Spring AI 版 Agent |
| MCP | 1-2 周 | MCP Server 暴露 + MCP Client 对接 |
| 多 Agent | 2-3 周 | Handoff/Supervisor 模式实现 |
| 可观测性 | 1-2 周 | Grafana 面板 + 评估器 + 护栏 |
| **合计** | **8-15 周** | |

### 技能树总览

```
Java 基础
  └── Spring Boot (enterprise-claw-4j 已掌握)
        ├── Spring Web (REST + WebSocket ✓)
        ├── Spring Scheduling (@Scheduled ✓)
        ├── Spring Actuator (健康检查 ✓)
        ├── Spring WebFlux (待学: 响应式流)
        └── Spring AI (待学: AI 抽象层)

AI Agent 核心 (enterprise-claw-4j 已掌握)
  ├── Agent Loop (while-true ✓)
  ├── Tool Use (JSON Schema ✓)
  ├── Prompt Engineering (8 层 ✓)
  ├── Memory (混合检索 ✓)
  ├── Streaming (待学)
  └── MCP (待学)

基础设施
  ├── Docker / K8s (✓)
  ├── Observability (基础 ✓, 待深入)
  ├── Vector Database (待学)
  └── Message Queue (待学)
```
