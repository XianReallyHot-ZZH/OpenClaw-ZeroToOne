# light-claw-4j

From Zero to One: Build an AI Agent Gateway (Java Edition)

light-claw-4j 是 [claw0](https://github.com/shareAI-lab/claw0) 项目的 Java 轻量级重写. 通过 10 个渐进式 Session, 从零构建一个完整的 AI Agent 网关.

每个 Session 是一个自包含的 Java 文件, 复制前序 Session 的核心代码并添加新功能, 可以独立编译和运行.

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

### 编译

```bash
mvn compile
```

### 运行

```bash
# S01: 最简 Agent 循环
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S01AgentLoop

# S02: 工具使用
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S02ToolUse

# S03: 会话持久化 + 上下文保护
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S03Sessions

# S04: 多渠道 (CLI + Telegram + 飞书)
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S04Channels

# S05: Gateway 网关 + 路由
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S05GatewayRouting

# S06: 智能层 (灵魂 + 记忆 + 技能)
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S06Intelligence

# S07: 心跳 + 定时任务
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S07HeartbeatCron

# S08: 可靠投递队列
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S08Delivery

# S09: 弹性重试 (三层重试洋葱)
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S09Resilience

# S10: 命名 Lane 并发
mvn compile exec:java -Dexec.mainClass=com.claw0.sessions.S10Concurrency
```

## Session 概览

| Session | 主题 | 核心概念 | 代码行数 |
|---------|------|---------|---------|
| S01 | Agent Loop | while True + stop_reason 分发 | ~194 |
| S02 | Tool Use | JSON Schema + handler 分发表 | ~536 |
| S03 | Sessions | JSONL 追加/重放 + 3 阶段上下文保护 | ~1081 |
| S04 | Channels | 统一渠道抽象 (CLI/Telegram/飞书) | ~1230 |
| S05 | Gateway & Routing | 5 层路由绑定表 + WebSocket 网关 | ~1064 |
| S06 | Intelligence | 8 层系统提示词 + 混合记忆检索 | ~1649 |
| S07 | Heartbeat & Cron | 心跳运行器 + Cron 定时任务 | ~1165 |
| S08 | Delivery | 磁盘持久化投递队列 + 指数退避 | ~1082 |
| S09 | Resilience | 三层重试洋葱 (认证轮换/溢出恢复/工具循环) | ~1059 |
| S10 | Concurrency | 命名 Lane 并发 + generation 取消 | ~1538 |

## 项目结构

```
light-claw-4j/
├── pom.xml                          # Maven 配置 (Java 21)
├── .env.example                     # 环境变量模板
├── README.md                        # 本文件
├── docs/                            # 各 Session 文档
│   ├── s01_agent_loop.md
│   ├── s02_tool_use.md
│   ├── s03_sessions.md
│   ├── s04_channels.md
│   ├── s05_gateway_routing.md
│   ├── s06_intelligence.md
│   ├── s07_heartbeat_cron.md
│   ├── s08_delivery.md
│   ├── s09_resilience.md
│   └── s10_concurrency.md
├── workspace/                       # 运行时工作区
│   ├── SOUL.md                      # Agent 人格定义
│   ├── MEMORY.md                    # 长期记忆
│   ├── IDENTITY.md                  # 身份描述
│   ├── TOOLS.md                     # 工具使用指南
│   ├── HEARTBEAT.md                 # 心跳指令
│   ├── CRON.json                    # 定时任务配置
│   ├── sessions/                    # 会话 JSONL 文件
│   ├── memory/daily/                # 每日记忆日志
│   └── delivery-queue/              # 投递队列
└── src/main/java/com/claw0/
    ├── common/                      # 公共工具类
    │   ├── AnsiColors.java          # ANSI 颜色输出
    │   ├── Clients.java             # API 客户端工厂
    │   ├── Config.java              # .env 配置加载
    │   └── JsonUtils.java           # Jackson JSON 工具
    └── sessions/                    # 10 个 Session 实现
        ├── S01AgentLoop.java
        ├── S02ToolUse.java
        ├── S03Sessions.java
        ├── S04Channels.java
        ├── S05GatewayRouting.java
        ├── S06Intelligence.java
        ├── S07HeartbeatCron.java
        ├── S08Delivery.java
        ├── S09Resilience.java
        └── S10Concurrency.java
```

## 依赖说明

| 依赖 | 版本 | 用途 |
|------|------|------|
| anthropic-java | 2.18.0 | Anthropic Claude API SDK |
| jackson-bom | 2.19.0 | JSON 序列化 (BOM 统一版本管理) |
| dotenv-java | 3.0.0 | .env 文件加载 |
| Java-WebSocket | 1.5.7 | WebSocket 服务端 (S05 Gateway) |
| cron-utils | 9.2.1 | Cron 表达式解析 (S07 Cron) |
| logback-classic | 1.5.18 | 日志框架 |
| junit-jupiter | 5.12.2 | 单元测试 |

## 架构演进

```
S01  Agent Loop          ─── while True + stop_reason
 │
S02  Tool Use            ─── JSON Schema + handler map
 │
S03  Sessions            ─── JSONL 持久化 + 上下文保护
 │
S04  Channels            ─── 多渠道适配 (CLI/Telegram/飞书)
 │
S05  Gateway & Routing   ─── 5 层路由 + WebSocket 网关
 │
S06  Intelligence        ─── 8 层提示词 + 记忆检索
 │
S07  Heartbeat & Cron    ─── 主动式后台任务
 │
S08  Delivery            ─── 可靠投递 (WAL + 退避)
 │
S09  Resilience          ─── 三层重试洋葱
 │
S10  Concurrency         ─── 命名 Lane 并发控制
```

## 许可证

本项目仅供学习参考. 基于 claw0 (Anthropic) 项目的教学理念, 使用 Java 重新实现.
