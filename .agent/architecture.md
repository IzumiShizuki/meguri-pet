# Architecture and Authority

## Runtime split

Meguri 不是单一服务，而是共享契约下的多运行时工作区：

```text
Website / AIRI / AstrBot / Desktop
              |
        adapters + client-sdk
              |
     Python Core (8000)  <---- Java Core (18080)
              |                       |
       memory bridge / RAG       WebFlux turn runtime
              |                       |
        local mock by default   Mock LLM by default
```

- `services/meguri_core/` 是现有 Python/FastAPI Core。它承载本地 turn、memory、RAG、身份和 bridge 路由；根 README 和 Java README 将现有 Python memory service 作为 Java 并行运行时的权威 memory 边界。
- `java/meguri-core/` 是 Java 21 的并行 JVM 边界。它使用 Spring Boot WebFlux 和 LangChain4j，默认不访问外部 LLM；Java 不是把 Python 现有权威状态静默复制成另一份权威。
- `apps/desktop-airi/`、网站客户端、AIRI 和 AstrBot 都是边缘适配层。它们负责协议转换、session/client identity、渲染或平台投递，不应自行重写核心状态机。

## Contract ownership

| 领域 | 权威来源 | Agent 修改规则 |
| --- | --- | --- |
| Adapter wire/event | `contracts/adapter-protocol/v1/`、`packages/protocol/` | 先确认 schema 和 fixtures，再同步 Java/Python/TypeScript 绑定与测试 |
| Performance v1 | `contracts/performance/v1/`、`docs/contracts/performance-contract-v1.md` | 不凭空添加延迟目标；保留 `NOT_MEASURED` 语义 |
| Python HTTP API | `services/meguri_core/app.py`、`services/meguri_core/schemas.py` | 保持认证、session、idempotency、SSE replay 和 CORS 边界 |
| Java turn/runtime | `java/meguri-core/src/main/java/` 和对应测试 | 通过领域类和现有 authority seam 扩展，不在 adapter 中旁路实现 |
| Persona/memory/RAG | Python memory/RAG bridge 与各自 OpenSpec/ADR | cache/read model 只能是可重建投影；不越过 ACL、trust、tenant/user/session 绑定 |
| Sprite/expression assets | `configs/meguri_sprite_runtime_map.json` 和 build metadata | 不直接改 canonical dataset 或绕过 build-id 校验 |
| Deployment/rollback | `ops/runbooks/`、`ops/scripts/`、release manifests | 先读 runbook 和 gate；部署不是普通本地测试 |

## Canonical request path

1. 客户端或平台 adapter 生成受约束的 client/user/session identity。
2. 共享 SDK/protocol 发送 turn，使用 `POST /v1/chat/respond`（同步）或 `POST /v1/turns` + session SSE（异步）。
3. Core 负责输入路由、状态机、检索/能力边界、provider 选择、持久化事件、重放和取消。
4. provider 产生语义回复或 delta；第一条有意义的 delta 必须遵守当前性能契约的 durable boundary。
5. adapter 依据事件和 `semantic.completed` 元数据执行平台投递或渲染，不把平台指标冒充为服务器 TTFT。

## Performance change boundaries

性能执行模式是两个正交维度：

- `TurnExecutionMode`: `FAST | THINK | AGENT`，决定推理/行动资格。
- `RetrievalMode`: `NONE | FAST | SLOW`，决定检索深度。

当前性能 rollout 的开关默认关闭。`AGENT` 还需要服务器权威决策、结构化 planner、能力 scope 和预算；一个 skill 描述、SLOW 检索或普通 provider 请求不能自行升级为 Agent。

所有 child work 都必须继承 turn 的 absolute deadline 和更小的预算。read model、检索结果、工具结果和模型观察均不能扩大权限或直接成为正式 memory/relationship 的权威写入。

## Change placement

- 新的跨语言字段放到 `contracts/`，用 fixture 驱动绑定测试。
- Java 行为放在 `java/meguri-core` 的 domain/service/controller 层，并为边界补测试。
- Python 行为放在 `services/`、`adapters/` 或 `tools/` 的现有职责目录，不在脚本中复制核心业务规则。
- 浏览器/桌面行为放在对应 `apps/` 或 `packages/`，不把 Electron 特权泄露到普通网页。
- 重要行为/API/架构变化先创建或继续 `openspec/changes/`，完成后用 verify 检查实现与任务/设计一致。
