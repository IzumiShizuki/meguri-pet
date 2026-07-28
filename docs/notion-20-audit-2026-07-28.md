# Notion 20.0～20.7 实现审计

> 历史快照：本文记录提交 `a594069` 之前的差距，不再代表最新状态。
> 2026-07-29 起请以 `docs/notion-20-implementation-plan-2026-07-28.md`
> 的逐页复核矩阵为唯一状态基线。最新复核仍判定 20.0-20.7 为部分实现，
> 但 Knowledge/Graph、Capability/Agent 和 Adapter 主干已较本文记录明显推进。

更新时间：2026-07-28

## 1. 结论口径

本次审计用于回答两个问题：Notion 20.x 的要求是否已经满足，以及哪些改动可以作为本地完成批次提交。

状态采用以下口径：

- **满足**：要求本身可以由当前代码和对应范围的测试直接证明；如果要求明确包含生产环境，仍需要真实环境证据。
- **部分满足**：主干能力已经存在，但仍缺子项、完整测试或真实环境证明。
- **未满足**：核心模型、接口或执行闭环尚未实现，不能靠现有代码合理推断完成。

20.x 大多数条目本身包含生产持久化、跨端鉴权或故障恢复要求。本机没有 Docker、PostgreSQL 和 `MEGURI_TEST_DATABASE_URL`，因此“本地测试通过”不能升级为“生产完成”。

## 2. 总体矩阵

| 章节 | 当前结论 | 已在本地形成的主干能力 | 仍未完成的硬缺口 |
|---|---|---|---|
| 20.0 Harness / Turn | 部分满足 | 三端与同步接口复用 Turn 生命周期；阶段、终态、幂等冲突、sequence、manifest 和事件回放 | Context 内容开局冻结、完整可复现 trace、持久副作用去重、真实三端 E2E |
| 20.1 Context | 部分满足 | 不可变消息 DAG、active leaf、typed reference、摘要 digest 与 `STALE`、全局 token budget | 类型化 Context Bundle、可持久 Prompt trace、后台摘要任务、Python tokenizer、真实 PG 恢复 |
| 20.2 Memory | 部分满足 | candidate 审批入口、L0 首次写入前脱敏、保护字段阻断、版本与乐观锁 | 正式版本自包含元数据、冲突分支/last stable、删除投影 outbox、低风险自动合并闭环 |
| 20.3 Retrieval | 部分满足 | 四 lane Bundle、`NONE/FAST/SLOW`、Lore/Memory/Web 独立降级、Lore RRF | 真实 KB、类型化 Retrieval Item、完整 trace、Graph evidence/Hybrid fallback、Memory 分数校准 |
| 20.4 Persona | 部分满足 | Relationship 与 temporal 解耦、用户/客户端作用域、确定性快照、TTL、时间稳定器 | 完整 Profile/Scene/Interaction 模型与持久化、override 优先级/审计、逐字段 provenance、完整 eval |
| 20.5 Durable Turn | 部分满足 | PostgreSQL journal/schema、事件与 outbox 同事务写入、有界 SSE 缓冲、重启记录恢复 | owner/lease/heartbeat/CAS、outbox dispatcher、cursor 过期 Snapshot、真实 PG 故障矩阵 |
| 20.6 Capability | 部分满足 | 注册/授权/执行分离、类型与 schema、超时/并发/策略、MCP 规范化、Agent 预算模型 | 结构化 Approval、PostgreSQL Effect Ledger、完整 MCP 生命周期、Skill 信任链、真实 Agent sandbox |
| 20.7 三端协议 | 部分满足 | v1 Envelope、事件去重/sequence、Website 与 AstrBot durable checkpoint、AIRI 主流程 | Client Hello、`STATE/ONCE/ALWAYS`、cursor Snapshot、三端真实鉴权 E2E、AIRI 安装包验证 |

总体判断：阶段 A 的本地骨架已经较完整，可以按边界提交；阶段 B 和阶段 C 尚未完成，不能将整个 Notion 20 标记为生产完成。

## 3. 已完成且可提交的本地能力

### 20.0 / 20.1

- Java Core 对外提供创建 Turn、SSE 事件、快照和取消接口，同步聊天入口复用同一生命周期。
- Turn 具备 scoped idempotency、payload hash 冲突、session sequence、明确阶段和唯一终态。
- Persona、Capability、协议和构建版本冻结在 Turn manifest 中。
- Message DAG、active leaf、分支重建、typed reference、摘要 revision digest 和 `STALE` 过滤已有本地实现与测试。
- Java Provider 使用 tokenizer 统一计算 system、历史、摘要、RAG 与工具上下文预算，不再先按字符静默截断。

### 20.2 / 20.3

- direct supersede、legacy upsert 和 Java/Python bridge 不再旁路正式 Memory candidate 流程。
- L0 内容在第一次 repository 写入前替换为指纹和无原文原因，普通候选不能修改 `relationship_stage` 等保护字段。
- RetrievalMode 固化为 `NONE`、`FAST`、`SLOW`，FAST 在编排和 Capability Policy 两层禁止 Web/Remote Agent。
- Lore 异常和超时现在降级为 `unavailable`，不会通过 `Mono.zip` 阻断正文；`retrieval.completed` 会保留正确 lane 状态。
- DashScope Lore 的 vector、keyword 和 rerank 使用 RRF，而不是直接比较异构原始分数。

### 20.4 / 20.5

- Relationship 是用户级状态，客户端请求自报值不会成为权威来源；服装和临时表现按客户端隔离。
- 昼夜、天气与服装变化不再推进 Relationship，Temporal 切换有 debounce、cooldown 和 hysteresis。
- PostgreSQL Turn Journal 已覆盖 Turn、事件、幂等、sequence、快照和 outbox 的本地代码路径。
- Website 使用 durable reducer checkpoint v2；AstrBot 使用原子 JSON checkpoint，均在副作用前推进持久 cursor。

### 20.6 / 20.7

- Capability Registry、Policy 和 Executor 已分离，Tool 输入 schema、超时、并发限制和风险策略会被执行。
- AIRI、AstrBot、Website 共享 v1 Envelope、event ID 去重、sequence 与 optional/required 兼容规则。
- AstrBot 已覆盖身份绑定、session 隔离、Turn/SSE、远程任务预览/确认/查询/取消和未绑定身份的 Memory fail-closed。
- AIRI 已覆盖 Meguri Adapter、checkpoint/reconnect、资源气泡、TTS/动作入口、服装状态和 Codex 聊天整合的定向测试。

## 4. 尚未写完的内容

### 20.0 Harness / Turn

- Context 目前只冻结 revision，Prompt 所需内容没有作为不可变快照与 Turn 一起持久化。
- Trace 只有部分阶段、lane 摘要和 Effect Receipt，不能完整复现输入、裁剪、Prompt、检索排名和降级决策。
- Effect Ledger 仍是内存实现，Core 重启后无法证明 Tool、Memory、TTS 等副作用 exactly-once。

### 20.1 Context

- 主链仍使用多个 `List<String>`，没有统一类型化 Context Bundle。
- 没有可持久化并能精确重建最终 Prompt 的 Assembly Trace。
- 没有接近预算阈值时运行的 durable summary/precompression job。
- Python Provider 仍有按条数或字符处理上下文的路径，尚未统一真实 tokenizer。

### 20.2 Memory

- 正式不可变 version 没有完整自包含 risk、merge policy、base version 等审批元数据。
- 冲突尚未产生独立 branch 或 `CONFLICTED` 状态，也没有 last stable 回退模型。
- 删除已有 tombstone，但没有 projection/outbox 事件闭环，向量和文件镜像不能证明最终一致。
- Review Policy 能识别可自动批准候选，但 Service 仍统一保存为 `PENDING_REVIEW`；是否开启低风险自动合并需要产品确认。

### 20.3 Retrieval

- Knowledge Base lane 仍固定为 `not_configured`，没有 `BUILDING/ACTIVE` 版本发布模型。
- Retrieval Item 仍是字符串，没有来源 ID、版本、citation、信任级别、token 数和排名证据。
- Trace 未记录 query rewrite、过滤、候选、分路排名、融合、裁剪和最终注入，无法完整复现。
- Graph evidence、evidence chunk 回溯和 Graph 失败后的确定性 Hybrid fallback 尚未实现。
- Memory 内部仍有异构分数直接线性融合路径，需要改为 RRF 或经验证的校准模型。

### 20.4 Persona / Relationship / Scene

- Persona 尚未完整纳入 Constitution、Profile、Relationship、Scene、Temporal、Interaction、override 和客户端能力的统一领域模型。
- Relationship、Profile、Scene、Interaction 和 override 尚未完成服务端持久化与跨端同步。
- override 缺 priority、审计和重启恢复；provenance 缺逐字段值、规则版本和生效原因。
- Prompt Injection 测试尚未覆盖恶意 Web、RAG 和 Tool 输出。
- Persona eval 尚缺场景适切、记忆自然、承诺一致和矛盾修复指标。

### 20.5 Durable Turn

- PostgreSQL 恢复没有 owner、lease、heartbeat 和 CAS；当前启动恢复会把非终态 Turn 明确标记失败。
- Outbox 只有表和事务写入，没有 dispatcher、claim、ack、retry、dead-letter 和幂等消费者。
- 没有事件 retention、`CURSOR_EXPIRED` 和结构化 Snapshot 恢复。
- Python Runtime 仍有字符切片伪流路径，尚未统一“原生流或完整 delta”的契约。
- 缺少真实 PostgreSQL 下的并发、重启、数据库不可用和事务失败测试。

### 20.6 Capability / MCP / Agent

- 写审批仍是布尔值，没有绑定 approval ID、规范化输入 hash、审批人、有效期和目标身份。
- Effect Ledger 未使用 PostgreSQL，也没有重启恢复、查询和 compensation/undo 语义。
- MCP 尚缺完整 `tools/list`、能力刷新、`list_changed`、OAuth 和真实服务验收。
- Skill 尚缺 manifest、签名或可信来源、健康状态和安全热更新入口。
- Remote Agent 尚无真实执行器和 OS/容器级进程、文件、网络隔离。

### 20.7 AIRI / AstrBot / Website

- 协议没有 Client Hello，也没有版本、客户端能力和 replay policy 协商。
- Envelope 没有明确的 `STATE/ONCE/ALWAYS` 重放策略。
- cursor 过期后的 Snapshot 尚未实现，三端也没有对应恢复测试。
- AIRI 主仓全量 `stage-tamagotchi` typecheck 仍被缺失的 `apps/server` 与 `@proj-airi/server-runtime/server` 边界阻断。
- AIRI 原生 Live2D 仍需用真实 Meguri 模型包完成导入、动作、服装和视觉验收；PNG-first 目前只能视为阶段方案。
- AstrBot 尚未在真实插件进程、平台账号、群聊和 Relay 上完成崩溃/重复投递验证。
- Website 尚未完成真实登录 principal、浏览器刷新/离线/多标签和 cursor 过期 E2E。

### 安全与运维

- 缺少三端连接同一 Core/Relay 的真实鉴权对照，包括过期 token、错误 tenant、伪造身份和管理员越权。
- Core 尚未形成统一的非 loopback HTTPS 强制策略，也没有生产日志、事件和前端存储的 secret 扫描报告。
- staging 备份恢复、监控告警、容量、脱敏、回滚和数据库故障演练尚未闭环。

## 5. 当前验证证据

- Java 21：139 项通过，1 项依赖 Everything 实机环境的测试跳过。
- Python：341 项通过，8 项真实 PostgreSQL 测试因缺少 `MEGURI_TEST_DATABASE_URL` 跳过。
- TypeScript protocol / adapter / Website：25 项通过。
- Desktop Node：13 项通过，1 项符号链接环境测试跳过。
- AstrBot 独立范围：51 项通过。
- AIRI：Meguri Adapter 12 项、Stage Tamagotchi 54 项、Stage UI Node 15 项、Stage UI Browser 4 项通过；相关 ESLint 与 `core-agent` typecheck 通过。
- AIRI 全量 `stage-tamagotchi` typecheck 未通过，剩余错误集中于仓库缺失的 server 模块边界。
- AIRI 全量 Stage Tamagotchi 测试额外运行到 405 项通过、1 项跳过；另有 4 项失败和 1 个 suite 无法加载，原因分别为 Windows symlink 权限、路径分隔符断言及未构建的 `@proj-airi/electron-vueuse` 入口。这些属于仓库基线，不在本次 Meguri 定向改动范围内。

## 6. 提交建议

已完成的本地改动可以提交，但提交说明应使用“local implementation / contract-tested”口径，不应声称 20.x production complete。建议拆为：

1. Core Turn、Context、Persona、Retrieval 与协议。
2. Memory candidate、安全、版本与迁移。
3. AstrBot Gateway、checkpoint 与渲染插件。
4. Desktop AIRI spike、资源、日报与运维工具。
5. AIRI 主仓 Meguri companion integration。
6. Notion 20 审计与未完成项文档。
