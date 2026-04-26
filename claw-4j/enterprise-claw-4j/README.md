# enterprise-claw-4j

From Zero to One: Enterprise-grade AI Agent Gateway (Java Edition)

enterprise-claw-4j 是 [claw0](https://github.com/shareAI-lab/claw0) 项目的企业级 Java 实现. 将 light-claw-4j 的 10 个渐进式 Session 整合为一个基于 Spring Boot 3.5 的生产级应用, 采用 14 个模块化包, 87 个类, 支持多渠道接入、多 Agent 路由、会话持久化、智能记忆、弹性重试、可靠投递和容器化部署.

## 快速开始

### 前置要求

- Java 21+
- Maven 3.9+
- Anthropic API Key

### 配置

```bash
cp .env.example .env
# 编辑 .env, 填入你的 ANTHROPIC_API_KEY
```

### 本地运行

```bash
# 编译
mvn clean package -DskipTests

# 运行
java -jar target/enterprise-claw-4j-*.jar

# 或使用 Maven
mvn spring-boot:run
```

### Docker 部署

```bash
# 配置
cp .env.example .env

# 构建并启动
docker compose up -d

# 检查状态
curl http://localhost:8080/actuator/health
```

### Kubernetes 部署

```bash
kubectl create secret generic claw4j-secrets \
  --from-literal=ANTHROPIC_API_KEY=sk-ant-xxx

kubectl apply -f k8s/deployment.yaml
```

## 架构概览

```
┌──────────────────────────────────────────────────────────────────┐
│                        API Layer                                  │
│  GatewayController (REST /api/v1)  │  WebSocket (/ws/gateway)    │
├──────────────────────────────────────────────────────────────────┤
│                        Gateway                                    │
│  GatewayService → BindingTable (5 层路由) → AgentManager          │
├──────────────────────────────────────────────────────────────────┤
│                        Agent Core                                 │
│  AgentLoop (while-true) → ToolRegistry → ContextGuard            │
├──────────────────────────────────────────────────────────────────┤
│                     Intelligence                                  │
│  PromptAssembler (8 层) → MemoryStore (5 阶段检索) → SkillsManager│
├──────────────────────────────────────────────────────────────────┤
│                     Infrastructure                                │
│  ResilienceRunner │ CommandQueue │ DeliveryQueue │ Scheduler     │
├──────────────────────────────────────────────────────────────────┤
│                        Channels                                   │
│  CliChannel │ TelegramChannel │ FeishuChannel                    │
├──────────────────────────────────────────────────────────────────┤
│                     Session & Health                              │
│  SessionStore (JSONL) │ GracefulShutdown │ Actuator              │
└──────────────────────────────────────────────────────────────────┘
```

## 模块概览

| 模块 | 包 | 核心组件 | 说明 |
|------|---|---------|------|
| Agent Core | `agent/` | AgentLoop, ContextGuard, ToolRegistry | while-true 工具循环 + 3 阶段上下文保护 |
| Auth | `auth/` | AuthFilter, DefaultAuthFilter | 认证扩展点 (默认放行, 可覆盖) |
| Channels | `channel/` | Channel, ChannelManager, CLI/Telegram/Feishu | 多渠道消息统一抽象 |
| Common | `common/` | FileUtils, JsonUtils, TokenEstimator, 7 种异常 | 公共工具 + 异常层次 |
| Concurrency | `concurrency/` | CommandQueue, LaneQueue | 命名 Lane 并发 + generation 取消 |
| Config | `config/` | AnthropicConfig, AppProperties, WebSocketConfig | Spring 配置外部化 |
| Delivery | `delivery/` | DeliveryQueue, DeliveryRunner, MessageChunker | WAL 投递队列 + 指数退避 |
| Gateway | `gateway/` | BindingTable, GatewayService, Controller, WebSocket | 5 层路由 + REST/WebSocket API |
| Health | `health/` | GracefulShutdownManager, WorkspaceHealthIndicator | 5 步优雅关闭 + Actuator |
| Intelligence | `intelligence/` | PromptAssembler, MemoryStore, BootstrapLoader, SkillsManager | 8 层提示词 + 5 阶段检索 |
| Resilience | `resilience/` | ResilienceRunner, ProfileManager, AuthProfile | 三层重试洋葱 + 配置轮换 |
| Scheduler | `scheduler/` | HeartbeatService, CronJobService | 心跳 + Cron 定时任务 |
| Session | `session/` | SessionStore, TranscriptEvent, SessionMeta | JSONL 会话持久化 + DmScope 隔离 |
| Tool | `tool/` | ToolRegistry, 6 个内置 Handler | 可插拔工具系统 |

## 项目结构

```
enterprise-claw-4j/
├── pom.xml                              # Spring Boot 3.5.3 + Java 21
├── Dockerfile                           # 多阶段构建 (JDK → JRE Alpine)
├── docker-compose.yml                   # 单节点部署
├── k8s/                                 # Kubernetes 部署清单
│   └── deployment.yaml
├── .env.example                         # 环境变量模板
├── docs/                                # 教学文档
│   ├── overview.md                      # 架构概览
│   ├── agent-core.md                    # Agent 核心循环
│   ├── sessions.md                      # 会话持久化
│   ├── channels.md                      # 多渠道消息
│   ├── gateway.md                       # 网关路由 + API
│   ├── intelligence.md                  # 提示词 + 记忆
│   ├── resilience.md                    # 弹性重试
│   ├── concurrency.md                   # Lane 并发
│   ├── delivery.md                      # 可靠投递
│   ├── scheduler.md                     # 心跳 + Cron
│   └── deployment.md                    # 部署与运维
├── workspace/                           # 运行时工作区
│   ├── IDENTITY.md                      # 身份定义
│   ├── SOUL.md                          # 人格 (Luna)
│   ├── TOOLS.md                         # 工具指南
│   ├── MEMORY.md                        # 永久记忆
│   ├── HEARTBEAT.md                     # 心跳指令
│   ├── BOOTSTRAP.md                     # 启动上下文
│   ├── USER.md                          # 用户信息
│   ├── AGENTS.md                        # 多 Agent 说明
│   ├── CRON.json                        # 定时任务配置
│   └── skills/                          # 技能定义
│       └── example-skill/
│           └── SKILL.md
└── src/main/
    ├── java/com/openclaw/enterprise/
    │   ├── EnterpriseClaw4jApplication.java
    │   ├── agent/          # Agent 核心 (7 个类)
    │   ├── auth/           # 认证 (2 个类)
    │   ├── channel/        # 渠道 (5 个类 + 3 实现)
    │   ├── common/         # 公共工具 (4 个类 + 7 种异常)
    │   ├── concurrency/    # 并发控制 (4 个类)
    │   ├── config/         # Spring 配置 (6 个类)
    │   ├── delivery/       # 投递队列 (4 个类)
    │   ├── gateway/        # 网关路由 (11 个类)
    │   ├── health/         # 可观测性 (2 个类)
    │   ├── intelligence/   # 智能层 (8 个类)
    │   ├── resilience/     # 弹性重试 (4 个类)
    │   ├── scheduler/      # 定时任务 (7 个类)
    │   ├── session/        # 会话持久化 (3 个类)
    │   └── tool/           # 工具系统 (3 个类 + 6 Handler)
    └── resources/
        ├── application.yml             # 主配置
        ├── application-dev.yml         # 开发环境
        ├── application-prod.yml        # 生产环境
        └── logback-spring.xml          # 日志配置
```

## 依赖说明

| 依赖 | 版本 | 用途 |
|------|------|------|
| spring-boot-starter-web | 3.5.3 | REST API + 内嵌 Tomcat |
| spring-boot-starter-websocket | 3.5.3 | WebSocket 支持 |
| spring-boot-starter-actuator | 3.5.3 | 健康检查 + 指标 |
| spring-boot-starter-validation | 3.5.3 | 参数校验 |
| spring-retry | - | 声明式重试 |
| anthropic-java | 2.19.0 | Anthropic Claude API SDK |
| jackson-dataformat-yaml | - | YAML 配置解析 |
| dotenv-java | 3.2.0 | .env 文件加载 |
| cron-utils | 9.2.1 | Cron 表达式解析 |
| logstash-logback-encoder | 8.1 | JSON 结构化日志 |

## API 参考

### REST API (`/api/v1`)

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /agents | 注册 Agent |
| GET | /agents | 列出所有 Agent |
| GET | /agents/{id} | 获取 Agent 详情 |
| DELETE | /agents/{id} | 删除 Agent |
| POST | /bindings | 添加路由规则 |
| GET | /bindings | 列出路由规则 |
| DELETE | /bindings/{id} | 删除路由规则 |
| GET | /sessions | 列出会话 |
| DELETE | /sessions/{id} | 删除会话 |
| POST | /send | 发送消息 (同步) |
| GET | /status | 系统状态 |

### WebSocket (`/ws/gateway`)

JSON-RPC 2.0 协议, 支持方法: `send`, `bindings.set/list/remove`, `agents.list/register`, `sessions.list`, `status`.

服务器推送通知: `typing`, `heartbeat.output`, `cron.output`, `server.shutdown`.

### Actuator 端点

| 端点 | 说明 |
|------|------|
| /actuator/health | 健康状态 (含工作区检查) |
| /actuator/health/liveness | 存活探针 |
| /actuator/health/readiness | 就绪探针 |
| /actuator/metrics | Micrometer 指标 |

## 渠道配置

### CLI (默认启用)

终端交互, 无需额外配置.

### Telegram

```yaml
channels:
  telegram:
    enabled: true
    token: ${TELEGRAM_BOT_TOKEN}
```

### 飞书

```yaml
channels:
  feishu:
    enabled: true
    app-id: ${FEISHU_APP_ID}
    app-secret: ${FEISHU_APP_SECRET}
    verification-token: ${FEISHU_VERIFICATION_TOKEN}
```

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| ANTHROPIC_API_KEY | (必需) | Anthropic API Key |
| MODEL_ID | claude-sonnet-4-20250514 | 主模型 |
| MAX_TOKENS | 8096 | 最大生成 token 数 |
| SERVER_PORT | 8080 | 服务端口 |
| WORKSPACE_PATH | ./workspace | 工作空间路径 |
| CONTEXT_BUDGET | 180000 | 上下文 token 预算 |
| DEFAULT_AGENT | luna | 默认 Agent |
| HEARTBEAT_INTERVAL | 1800 | 心跳间隔 (秒) |
| TELEGRAM_ENABLED | false | 启用 Telegram |
| FEISHU_ENABLED | false | 启用飞书 |
| LANE_MAIN_CONCURRENCY | 3 | main lane 并发数 |
| DELIVERY_MAX_RETRIES | 5 | 最大投递重试次数 |

完整变量列表见 `.env.example`.

## 内置工具

| 工具 | 功能 | 安全措施 |
|------|------|---------|
| read_file | 读取工作区文件 | 路径遍历保护 |
| write_file | 写入文件 | 自动创建父目录 |
| edit_file | 精确字符串替换 | 唯一性校验 |
| bash | 执行 Shell 命令 | 危险命令检测 |
| memory_write | 写入记忆 | 分类存储 |
| memory_search | 搜索记忆 | TF-IDF + 向量混合检索 |

自定义工具只需: 实现 `ToolHandler` 接口 + 添加 `@Component` 注解.

## 设计亮点

- **Java 21 现代特性**: sealed interface + pattern matching, virtual threads, records
- **Strategy + Spring 自动收集**: Channel / ToolHandler 接口, @Component 自动注册
- **5 层路由 + 懒排序缓存**: CopyOnWriteArrayList + volatile cache, 读多写少优化
- **5 阶段混合记忆检索**: TF-IDF (30%) + Hash 向量 (70%) + 时间衰减 + MMR 重排序
- **三层重试洋葱**: Auth 轮换 → 上下文恢复 → 工具循环 + Fallback 模型降级
- **WAL 投递队列**: tmp + fsync + atomic rename, 崩溃不丢消息
- **Generation-based 取消**: Lane 队列支持 reset, 旧任务自动失效
- **5 步优雅关闭**: SmartLifecycle (phase=MAX_VALUE), 有序停机
- **条件注册**: @ConditionalOnProperty 按需启用渠道, 零开销

## 与 light-claw-4j 的关系

| 维度 | light-claw-4j | enterprise-claw-4j |
|------|--------------|-------------------|
| 定位 | 渐进式教学 (10 个 Session) | 生产级应用 (14 个包) |
| 代码量 | ~10,000 行 (单文件) | ~10,000 行 (87 个类) |
| 框架 | Maven + 手动管理 | Spring Boot 3.5.3 |
| 渠道 | CLI | CLI + Telegram + 飞书 |
| API | 无 | REST + WebSocket |
| 部署 | mvn exec:java | Docker / K8s |
| 配置 | .env | application.yml + Profiles |
| 线程 | 手动虚拟线程 | Spring 虚拟线程 + @Scheduled |

light-claw-4j 适合学习概念, enterprise-claw-4j 展示如何将概念落地为生产代码.

## 许可证

本项目仅供学习参考. 基于 claw0 (Anthropic) 项目的教学理念, 使用 Java + Spring Boot 重新实现.
