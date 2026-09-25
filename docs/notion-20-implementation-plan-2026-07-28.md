# Notion 20.x 本地实现合并记录（2026-07-29）

## 1. 状态结论

截至 2026-07-29，20.0-20.7 本地代码与自动化契约已实现。20.x 可以作为“本地已实现”能力基线，不再沿用此前“仍有 P0/P1 代码缺口”的判断。

这里的“已实现”严格限定为：

- 20.1-20.7 的领域对象、持久化适配器、主链装配、故障降级和自动化测试均已落地。
- 20.0 的唯一同步主链已经接通，不再由旧 Lore、Memory、Web、Knowledge 或双层 Capability 编排并行决定正文。
- AIRI、AstrBot、Website 已使用同一 Adapter Protocol v1 fixture 验证协议语义。
- 真实 PostgreSQL/pgvector、外部 MCP Server、Remote Agent、模型 Provider、生产身份授权和真实三端进程仍属于环境验收，不能写成“生产已完成”。

因此，统一口径为：

| 范围 | 状态 |
| --- | --- |
| 20.0-20.7 本地代码 | 已实现 |
| 自动化契约与离线验收 | 已完成 |
| 真实外部环境 E2E | 待验 |
| 生产发布与运维验收 | 未执行 |

## 2. 唯一端到端主链

当前 Turn 正文只有以下一条权威调用顺序：

```text
Client Adapter
-> Turn 接受、身份、幂等、deadline、Client Capability
-> 当前 user message 以稳定 message ID 写入 Session DAG
-> 冻结 Persona / Relationship / Scene / Policy
-> 冻结 Knowledge Snapshot 与 Capability Snapshot
-> Unified Retrieval（Lore / Memory / Knowledge / 条件 Graph / SLOW Web）
-> RetrievalBundle
-> ContextBuilder（分支 / Topic / Summary / Reference / Rehydration）
-> ContextBundle + 可回放 Context Trace
-> PromptPolicyComposer
-> 可选 Agent Planning（只在 SLOW 或服务端显式入口）
-> 服务端 Agent allowlist / Policy / Schema / Approval / Budget / Capacity 复核
-> Remote Agent 结果作为不可信外部块回注 Context
-> 最终 typed ProviderRequest + Provider 原生流
-> text.delta 先持久化再由 Adapter 消费
-> PresentationResolver
-> turn.completed
-> 异步 Post-Reply Memory Job
```

关键不变量：

- 当前用户输入在 Retrieval 和 Context 构建前进入消息 DAG，助手消息使用稳定 ID 并绑定正确父节点。
- Persona、Knowledge、Context、Capability、Provider 和事件共享同一 Turn trace 与绝对 deadline。
- `ProviderRequest` 显式携带 `knowledgeSnapshotId`、`retrievalTraceId`、`contextTraceId` 和 `capabilitySnapshotId`。
- RAG、Web、Tool、MCP 和 Remote Agent 输出只能作为不可信数据，不能覆盖 Persona 或 System Policy。
- `turn.completed` 先成为不可变终态，再入队 Memory Job；后台失败不能反向把回复改成失败。

## 3. 逐页实现矩阵

| 页面 | 本地状态 | 已实现闭环 |
| --- | --- | --- |
| 20.1 Harness / Context | 已实现 | 不可变消息 DAG、分支与 active leaf、Topic、Summary、typed reference、局部 rehydration、来源预算、`ContextBundle`、可持久 build trace、后台预压缩任务 |
| 20.2 长期记忆 | 已实现 | Candidate 风险分层、审批、不可变版本、三方 merge、冲突分支与 last stable、TOMBSTONE、Embedding Outbox、Dead Letter、本地镜像与 repair |
| 20.3 Retrieval / Knowledge Graph | 已实现 | 统一 Planner/Gate、Query Rewrite、Lore/Memory/Knowledge/Graph/Web 多路召回、Weighted RRF、父子 Chunk、typed citation、1-3 hop graph evidence、ACTIVE 版本原子发布与 Hybrid fallback |
| 20.4 Persona Runtime | 已实现 | Profile、Relationship、Scene、Interaction、override 权威模型与仓储，Reducer、TTL/cooldown/hysteresis、`EffectivePersonaState`、`PromptPolicyComposer`、Presentation 边界与注入隔离 |
| 20.5 Turn Runtime | 已实现 | Turn/Event 持久化、owner lease/heartbeat/CAS、绝对 deadline、原生流、取消、重试与 `retry_of`、SSE replay、Snapshot、Outbox claim/ack/retry/dead-letter、后台 Worker 生命周期 |
| 20.6 Capability / Sub-agent | 已实现 | 五类 Capability、Catalog/Registry/Exposure/Policy/Approval/Executor/Audit、冻结 Snapshot、MCP `2025-11-25` 与 `list_changed`、Prompt/Resource 显式选择、Prompt Skill Context、Legacy Gateway adapter、真实 HTTP Remote Agent、Execution Resource Registry、Skill/Step/AgentTask 持久状态与预算递减 |
| 20.7 Adapter Protocol | 已实现 | Client Hello、身份与能力协商、平台 actor 不可逆映射与绑定防漂移、稳定幂等键、required/optional 与 STATE/ONCE/ALWAYS、CURSOR_EXPIRED + Snapshot、AIRI/AstrBot/Website durable checkpoint、统一 fixture |

## 4. 本轮主链收口

### 4.1 Provider 输入冻结

- `CanonicalTurnPipeline` 是 Persona、Retrieval、Context、Prompt 和 typed Provider 的唯一同步准备入口。
- Knowledge Snapshot 在 Turn 接受时只冻结一次；可由自描述 snapshot ID 恢复，失败时只降级为空快照，不扩大权限。
- `HarnessManifest` 记录 Relationship、Scene、Policy、Persona、Retrieval、Provider 和 Model trace。
- `LangChain4jLlmProvider` 只把可信 Persona/Policy 放入 System Prompt；外部块保持 `UNTRUSTED USER_DATA`。

### 4.2 Capability 单一权威

- Turn 正文中的 Weather 和显式 Agent 调用只经过 `CapabilityRuntimeFacade`。
- 旧 Harness `CapabilityExecutor` 已退出正文执行路径，不再出现双重 Policy、双重 Schema 或双重审计。
- `effectReceipts()` 仅保留为 Capability Audit 的只读兼容投影，不参与授权和执行。
- `ExistingGatewayAdapter` 保证旧 Gateway Publisher 只订阅一次；`LegacyGatewayCapabilityPack` 提供受控迁移入口。
- Capability 回调受冻结 Snapshot、父 Turn 剩余 deadline、审批上下文和审计约束，审计摘要来自真实结果而不是占位值。
- `SLOW` 只允许主模型提出 Agent proposal，不等于用户审批；proposal 必须重新经过服务端 allowlist、Policy、Schema、Approval、预算和容量检查。
- 公共 Turn JSON 不能自报 `agent_proposal` 或 Capability scope；显式 Agent 入口只接受服务端已认证上下文。
- MCP 支持截至 `2025-11-25` 的协议协商、Session/JSON/SSE、分页和 `list_changed`；Prompt 与 Resource 只在 Turn 显式选择后读取，并始终按不可信外部内容处理。

### 4.3 持久事件与后台任务

- PostgreSQL Journal 在同一事务中追加 Turn 状态、事件与 Outbox；执行 owner 通过 lease/heartbeat/CAS 防止僵尸 Worker 继续写入。
- Outbox 只有存在真实 `TurnOutboxDispatcher.Delivery` 时才启动。没有消费者时不会使用 noop 错误 ack。
- Post-Reply Memory Job 在正文完成后独立 claim、heartbeat、retry 和 dead-letter，候选抽取也不占正文关键路径。
- Spring 生命周期使用有界轮询，单次任务异常不会杀死 Worker，关闭时会释放自有资源。

### 4.4 Adapter 快照健壮性

- `AdapterSessionSnapshotResponse` 会过滤 expression 事件中的可选 null 字段，再冻结不可变 Map。
- AIRI、AstrBot、Website 均在触发 ONCE 副作用前持久 checkpoint，重连不会重复 TTS、动画、通知或工具副作用。
- 三端的事件目录、终态、能力与权限均从同一 canonical fixture 对齐。
- `meguri_user_id`、`platform_actor_id`、`client_instance_id` 和 `session_id` 保持独立语义；平台原始 actor ID 只停留在 Adapter 边界，Core 只接收并绑定不可逆映射值。
- Client Binding 会拒绝 actor 换绑或从已绑定状态降级，客户端请求体不能覆盖已认证用户和服务端 Capability scope。

## 5. 自动化证据

本轮最终验收记录：

| 范围 | 结果 |
| --- | --- |
| Java 21 Maven 全量 | `404` tests，`0` failures，`0` errors，`1` skipped |
| Python 全量 | `376 passed`，`8 skipped` |
| 本次变更 Python Ruff | passed |
| Python compileall | passed |
| Alembic heads | 单一 head：`20260729_0007` |
| Alembic 离线升降级 | `0001 -> 0007 -> base` passed |
| 根 TypeScript | `48/48 passed` |
| 跨端 Adapter fixture | Website/协议 `21/21`、Python Adapter/AstrBot passed、AIRI `31/31` |
| AIRI Adapter 包级 Vitest | `31/31 passed` |
| AIRI Adapter 严格 TypeScript | passed |
| AIRI 指定 ESLint | passed |
| AstrBot 插件 ZIP | passed |

补充边界：

- 全仓 Ruff 仍报告 41 个历史问题，位于本轮未触碰的旧插件、脚本和既有桥接模块；本次所有新增/修改 Python 文件均通过 Ruff。
- 从 AIRI 仓库根启动 Vitest 会因上游配置引用不存在的 `apps/server` 而在测试发现前失败；从 `packages/meguri-airi-adapter` 包目录运行的完整测试为 `31/31`。
- Java 唯一 skipped 项和 Python 8 个 skipped 项依赖本机未提供的真实外部/数据库环境。

## 6. 外部环境待验

以下事项不再是本地代码缺口，但仍是上线前不可省略的验收：

1. 真实 PostgreSQL + pgvector：迁移、GIN/HNSW 查询计划、锁竞争、事务失败、进程重启和恢复。
2. 真实 Notion：分页、限流、删除、ACL 变化、定时同步和凭据轮换。
3. 真实 MCP Server：认证、`list_changed`、断连、恶意输出、版本 drain 和最小权限。
4. 真实 Remote Agent：跨进程 callback/polling、长任务、重复投递、取消和网络故障。
5. 真实 Provider：原生流 TTFT、流中断、部分文本、取消、超时和稳定 failure code。
6. AIRI、AstrBot、Website：真实身份、同一 Core、断线重连、过期 token、跨端正式记忆与 session 隔离。
7. 生产运维：secret 扫描、备份恢复、容量、监控告警、灰度、回滚和故障演练。

## 7. 文档权威关系

- 本文件是 2026-07-29 起唯一最新的 20.x 本地实现状态文档。
- `docs/notion-20-audit-2026-07-28.md` 仅保留为实现前历史差距快照。
- `docs/harness-runtime-20.md` 记录 Turn/Harness 接口与运行时边界。
- Notion 20.0-20.7 继续作为规范来源；本文件记录代码实现和自动化证据，不覆盖原规范。

## 8. Notion 同步口径

应写入：

> 20.0-20.7 本地代码与自动化契约已实现。唯一 Turn 主链已接通；真实 PostgreSQL/pgvector、MCP、Remote Agent、Provider、生产授权和真实三端 E2E 待验。

不得写入：

> 20.x 已完成生产验收或已经可以直接发布生产。
