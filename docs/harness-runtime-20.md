# Meguri Harness Runtime 20

状态说明（2026-07-29）：Harness 20 的本地代码和自动化契约已经实现；真实 PostgreSQL、外部 Provider、MCP/Remote Agent、真实客户端进程和生产运维仍待环境验收。逐页状态与证据以 `docs/notion-20-implementation-plan-2026-07-28.md` 为准。

## 对外接口

客户端只依赖一个最小 Turn Runtime：

```java
public interface TurnRuntime {
    Mono<TurnSnapshot> submit(TurnCommand command);
    Flux<EventEnvelope> events(EventCursor cursor);
    Mono<TurnSnapshot> snapshot(String turnId);
}
```

`TurnCommand` 只表达开始和取消。AIRI、AstrBot、Website 与同步兼容接口复用同一 Turn 生命周期；健康检查、Persona 临时覆盖和管理操作位于独立 Control Plane，不进入聊天业务协议。

## Canonical Turn Pipeline

```text
Adapter identity / idempotency / deadline
-> immutable current user message
-> EffectivePersonaState
-> frozen Knowledge + Capability snapshots
-> UnifiedRetrievalFacade
-> RetrievalBundle
-> CompanionContextRuntime
-> ContextBundle + ContextBuildTrace
-> PromptPolicyComposer
-> optional main-model Agent proposal
-> server revalidation / approval / budget / capacity
-> untrusted Agent result rehydration
-> final ProviderRequest
-> native provider stream
-> durable Turn events
-> PresentationResolver
-> terminal Turn
-> asynchronous Memory Job
```

`CanonicalTurnPipeline` 是 Persona、Retrieval、Context、Prompt 和 ProviderRequest 的唯一同步准备入口。Turn 接受后，快照只能读取或恢复，不能在生成中途重新解析出另一套 Persona、Knowledge 或 Capability 状态。

## 冻结对象

每个 Turn 固定以下对象或版本：

- `EffectivePersonaState`：Persona、Relationship、Scene、Interaction、Temporal、Client Capability 与 Policy revision。
- `FrozenKnowledgeSnapshot`：本轮可见的 ACTIVE Knowledge 文档版本。
- `CapabilityRuntimeFacade.TurnCapabilities`：本轮暴露的能力 ID、版本和 Registry snapshot。
- `RetrievalBundle`：Query Rewrite、各 lane 状态、排名证据、citation、graph evidence 和降级。
- `ContextBundle`：分支原文、摘要、rehydration、正式记忆、检索项、工具结果、预算和裁剪。
- `ProviderRequest`：上述 Provider 可见输入及 Knowledge/Retrieval/Context/Capability trace ID。
- `HarnessManifest`：协议、构建、Persona、Relationship、Scene、Policy、Knowledge、Capability、Provider 与 Model 版本。

## Context 与 Persona

- 原始消息是由稳定 message ID 和 parent ID 连接的不可变 DAG。
- 当前用户消息在 Context 构建前写入；助手回复绑定到该用户消息，幂等重试不会重复追加。
- Summary 是派生版本，source digest 变化后变为 STALE，不能进入 Context。
- typed reference 支持引用、恢复和 Topic 关联；rehydration 只恢复可见且被引用的局部原文。
- Context 使用 Provider tokenizer 和来源预算，保留当前输入与关键来源的最低席位，并持久化可 replay trace。
- Persona、Relationship、Scene 与长期记忆是独立权威状态；客户端不能通过请求体自报关系升级。
- `PromptPolicyComposer` 只把可信 Persona/Policy 写入 System/Developer block。RAG、Web、Tool、MCP、Graph 和 Agent 结果保持不可信数据角色。

## Retrieval

- `UnifiedRetrievalFacade` 统一 Lore、正式 Memory、Knowledge、条件式 Graph 和 SLOW Web。
- `NONE` 不检索；`FAST` 不调用 Web 或 Remote Agent；`SLOW` 只开放 Web 和 Agent 规划资格，不会自动授予 Agent 审批。
- Query Rewrite 同时输出规范查询、关系意图和实体候选；Graph 只用于 1-3 hop 关系问题。
- Graph 每条边绑定同 ACTIVE 文档版本的可见 evidence chunk；失败时确定性回退 Hybrid，不增加权限。
- 各 lane 有独立超时和降级，单路失败不会阻断普通回复。
- `retrieval.completed` 只暴露 trace、状态、provider、数量和降级，不记录原始用户消息或检索正文。

## Capability 与 Agent

- Capability 分为 `PROMPT_SKILL`、`RESOURCE`、`READ_TOOL`、`WRITE_TOOL` 和 `REMOTE_AGENT`。
- Catalog、Registry、Exposure、Policy、Approval、Executor、Normalizer 与 Audit 保持独立职责。
- 未暴露、越权、Schema 错误、网络策略错误、缺审批或审批上下文漂移均 fail closed。
- 写操作绑定 operation ID、idempotency key 和 payload digest；未知结果不能按“未执行”自动重试。
- Turn 主链的 Weather 和显式 Agent 调用只经过 `CapabilityRuntimeFacade`，不再嵌套旧 Harness Executor。
- MCP 支持截至 `2025-11-25` 的协议协商、Session/JSON/SSE、分页与 `list_changed`；工具、资源和 Prompt 先转换为本地 Descriptor 并重新授权。
- MCP Prompt/Resource 只在 Turn 显式选择后读取，外部内容始终是 `UNTRUSTED USER_DATA`，不会提升为可信 Prompt Skill。
- SLOW 模式下主模型只负责提出 proposal；服务端再次校验 Agent allowlist、Policy、Schema、Approval、预算与容量后才执行。
- 公共 Turn JSON 不能自报 Agent proposal、Capability scope 或审批布尔值。
- Remote Agent 继承并缩减父级 deadline、trace、取消、Capability、token、tool、cost、depth 和 child budget。
- Agent 的 submit concurrency 与 in-flight capacity 分开限制；等待状态持久化并释放本地执行资源。

## Turn、事件与恢复

- 接受使用 tenant/user/client/session 作用域幂等和 payload hash；同 key 不同请求会冲突。
- Stages 为 `created -> planning -> retrieving -> generating -> finalizing -> terminal`。
- 所有关键模块共享 Turn 绝对 deadline，只能使用剩余预算。
- Provider 原生流的每个 `text.delta` 先写 Journal，再被 SSE/Adapter 读取；结构化 Provider 只发送一个完整 delta，不伪造 token stream。
- `PostgresTurnJournal` 在同一事务中写 Turn 生命周期、事件、sequence 和 Outbox。
- execution owner 使用 lease、heartbeat 和 CAS；过期 Worker 不能继续追加事件或终态。
- SSE 通过持久 sequence 回放；cursor 过期返回稳定错误和 Session Snapshot，不重新执行 Turn。
- Outbox 支持 claim、heartbeat、ack、retry 与 dead letter，只有真实 Delivery Bean 存在时才启动消费者。
- 终态追加成功后完成 `record.done` 并释放 Capability snapshot；任何后续 Memory Job 故障不改变终态。

## Adapter Protocol v1

- Envelope 包含协议版本、稳定 event ID、required 标记、ReplayPolicy、session sequence 和 metadata。
- 未知 optional 事件只推进 checkpoint；未知 required 事件明确失败。
- AIRI、AstrBot、Website 在执行 ONCE 副作用前持久 event ID 与 sequence。
- 三端共享 Client Hello、能力/权限协商、终态目录、错误码、Snapshot 和 canonical fixture。
- Adapter 只映射本地表现资产，不决定 Persona、关系、检索、正式记忆或服务端权限。
- `meguri_user_id`、平台 actor、client instance 和 session 使用独立标识；原始平台 actor 只在 Adapter 边界出现，Core 只持久化不可逆映射与绑定结果。
- Client Binding 拒绝 actor 换绑和绑定降级；请求体身份不能覆盖认证 principal。

## 异步 Memory 边界

- 正文不执行同步候选抽取或长期记忆写入。
- `turn.completed` 后只入队 Post-Reply Memory Job。
- Worker 独立执行抽取、风险/审批流程和正式写入，并具有 lease、heartbeat、retry、Dead Letter 和取消策略。
- 正式 Memory 的 PostgreSQL 版本是权威；向量与本地文件只是可修复投影。

## 生产验收门槛

本地实现不替代以下真实环境证据：

1. PostgreSQL/pgvector 的迁移、查询计划、并发、事务故障、重启与恢复。
2. Provider 的真实原生流、TTFT、中断、取消、deadline 和部分文本行为。
3. MCP 与 Remote Agent 的认证、断连、重复投递、恶意输出、版本 drain 和跨进程恢复。
4. AIRI、AstrBot、Website 对同一 Core 的真实身份、跨端正式记忆、session 隔离与重连。
5. Outbox 的真实消费者幂等、监控告警和 Dead Letter 运维。
6. 生产 secret、备份恢复、容量、灰度、回滚和故障演练。

## 最新自动化快照

- Java 21：404 tests，0 failures，0 errors，1 skipped。
- Python：376 passed，8 skipped；本次变更文件 Ruff 与 compileall 通过。
- Alembic：单一 head `20260729_0007`，离线 `0001 -> 0007 -> base` 全链通过。
- 根 TypeScript：48/48 passed。
- 跨端 fixture：Website/协议、AstrBot/Python、AIRI 全部通过。
- AIRI Adapter：31/31、严格 TypeScript、指定 ESLint 通过。
- AstrBot 插件 ZIP 打包通过。
