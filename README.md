# OpenClaw-ZeroToOne

从零到一实现 OpenClaw —— 将 Python AI Agent Gateway 教学项目 [claw0](https://github.com/shareAI-lab/claw0) 重写为 Java 版本。

## 项目概述

本项目以 [claw0](https://github.com/shareAI-lab/claw0)（一个渐进式 AI Agent Gateway 教学项目，约 7,400 行 Python，通过 S01-S10 十个 Session 逐步构建完整的 Agent Gateway）为蓝本，提供两个 Java 实现：

| 实现 | 定位 | 技术栈 |
|------|------|--------|
| **light-claw-4j** | 轻量教学版，保持「每课一文件、独立运行」的教学风格 | Java 21 + Maven + Anthropic Java SDK + Jackson |
| **enterprise-claw-4j** | 生产级企业版，面向真实部署 | Java 21 + Spring Boot 3.5 + Anthropic Java SDK + Docker + K8s |

## 架构演进（十课路线图）

| Session | 模块 | 核心能力 |
|---------|------|----------|
| S01 | Agent Loop | 核心对话循环：`while True` + `stop_reason` 分发，调用 Claude API |
| S02 | Tool Use | JSON Schema 工具定义 + 分发表，内置 bash / read_file / write_file / edit_file，多工具调用内循环 |
| S03 | Sessions & Context Guard | JSONL 会话持久化（追加写日志），三级上下文溢出恢复（截断工具结果 → LLM 压缩历史） |
| S04 | Channels | 多平台抽象：CLI / Telegram / 飞书，统一 `InboundMessage`，去重、缓冲、分片 |
| S05 | Gateway & Routing | 五级绑定表路由（peer > guild > account > channel > default），WebSocket + JSON-RPC 2.0，多 Agent 管理 |
| S06 | Intelligence | 八层系统提示词组装（Identity → Soul → Tools → Skills → Memory → Bootstrap → Runtime → Channel），混合记忆检索（TF-IDF + 哈希向量 + MMR 重排序） |
| S07 | Heartbeat & Cron | 主动式 Agent 行为：心跳检查（活跃时段 + 去重），定时任务（at / every / cron），连续 5 次错误自动禁用 |
| S08 | Delivery | WAL 磁盘队列保证消息投递安全，指数退避 + 抖动重试，平台级分片策略 |
| S09 | Resilience | 三层重试洋葱：认证轮换 → 上下文恢复 → 工具循环重试，模型降级链（sonnet → haiku） |
| S10 | Concurrency | 命名车道队列 + FIFO 排序，生成跟踪（安全重置/取消），虚拟线程，`CommandQueue` 独立管理 main/cron/heartbeat 车道 |

## 项目结构

```
OpenClaw-ZeroToOne/
├── claw-4j/
│   ├── light-claw-4j/           # 轻量教学版（Maven 项目）
│   └── enterprise-claw-4j/      # 企业生产版（Spring Boot 项目）
│       ├── Dockerfile            # 多阶段构建
│       ├── docker-compose.yml
│       └── k8s/                  # Kubernetes 部署清单
├── specs/                        # 设计规格文档
│   ├── learn-claw0-arch.md       # claw0 架构分析
│   ├── claw0-java-rewrite-analysis.md  # Java 重写可行性分析
│   ├── enterprise-claw0/         # 企业版模块设计
│   └── light-claw0/              # 轻量版模块设计
├── vendors/
│   └── claw0/                    # 原始 Python 源码（git submodule）
├── docs/
│   └── chat.md                   # 开发日志
└── README.md
```

## 快速开始

### 前置条件

- JDK 21+
- Maven 3.9+
- Anthropic API Key

### light-claw-4j

```bash
cd claw-4j/light-claw-4j
cp .env.example .env
# 编辑 .env，填入 ANTHROPIC_API_KEY

# 运行各课示例
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S01AgentLoop
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S02ToolUse
# ... 直至 S10Concurrency
```

### enterprise-claw-4j

```bash
cd claw-4j/enterprise-claw-4j
cp .env.example .env
# 编辑 .env，填入 ANTHROPIC_API_KEY 及其他配置

# 本地运行
mvn clean package -DskipTests
java -jar target/enterprise-claw-4j-0.1.0-SNAPSHOT.jar

# Docker 运行
docker compose up -d

# Kubernetes 运行
kubectl apply -f k8s/deployment.yaml
```

**服务端点**：
- REST API: `http://localhost:8080/api/v1/`
- WebSocket: `ws://localhost:8080/ws/gateway`
- 健康检查: `http://localhost:8080/actuator/health`

## 技术栈详情

### light-claw-4j

| 依赖 | 版本 | 用途 |
|------|------|------|
| Java | 21 (LTS) | 运行时 |
| Anthropic Java SDK | 2.18.0 | LLM API 调用 |
| Jackson BOM | 2.19.0 | JSON 处理 |
| dotenv-java | 3.0.0 | 环境变量加载 |
| Java-WebSocket | 1.5.7 | WebSocket 服务端 |
| cron-utils | 9.2.1 | Cron 表达式解析 |
| Logback | 1.5.18 | 日志 |
| JUnit | 5.12.2 | 测试 |

### enterprise-claw-4j

| 依赖 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.5.3 | 应用框架 |
| Anthropic Java SDK | 2.19.0 | LLM API 调用 |
| Spring Boot Actuator | — | 监控与健康检查 |
| Micrometer | — | 指标采集 |
| Spring Retry + AOP | — | 声明式重试 |
| dotenv-java | 3.2.0 | 环境变量加载 |
| cron-utils | 9.2.1 | Cron 表达式解析 |
| Logback + Logstash Encoder | 8.1 | 结构化日志 |
| Awaitility | 4.3.0 | 异步测试 |

## License

[Apache License 2.0](LICENSE)
