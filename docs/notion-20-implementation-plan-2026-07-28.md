# Notion 20.x 实现状态合并审计（2026-07-29）

## 1. 结论

截至提交 `a594069f6abecd8d2118a789ee89fed9730981df`（meguri-pet）与
`ab80ed8e756e10f0ec416409cdf46752532c3543`（airi-meguri），不能把
Notion 20.0-20.7 整体标记为“已实现”。逐页复核后，20.1-20.7 均仍有真实
代码缺口，而不只是缺少生产环境证据。

2026-07-29 实施计划页中的“本地代码实现、自动化验收与提交全部完成”只适用于
该计划明确圈定的范围：20.3 的 Knowledge Base/Knowledge Graph、20.6 的
Capability/受限子 Agent 主干，以及 20.5/20.7 的协议集成。该句不能外推为整个
20.x 已完成。

当前准确口径是：

- **20.x 总体：部分实现。**
- **本轮新增重点：Knowledge/Graph 与 Capability/Agent 主干已实现并通过本地自动化。**
- **生产状态：未验收。** 真实 PostgreSQL/pgvector、MCP Server、Remote Agent、
  Provider、生产授权与跨端 E2E 尚无完整证据。

## 2. 规范基线

本次重新读取并核对以下 Notion 页面：

| 页面 | Page ID | 最后更新时间 |
| --- | --- | --- |
| 20.0 总规范 | `3aaa3636-5963-81a3-ab30-ebe83c75e3e9` | `2026-07-28T13:54:00Z` |
| 20.1 Harness/Context | `3a8a3636-5963-812f-9e84-f4e355c13b83` | `2026-07-28T13:55:00Z` |
| 20.2 长期记忆 | `3a8a3636-5963-81dd-bd54-c18814c8fc65` | `2026-07-28T13:55:00Z` |
| 20.3 Retrieval/Knowledge Graph | `3aaa3636-5963-8131-81aa-dcd3a36ada36` | `2026-07-28T13:56:00Z` |
| 20.4 Persona Runtime | `3a8a3636-5963-811a-862f-fb84f073ff4e` | `2026-07-27T07:39:00Z` |
| 20.5 Turn Runtime | `3a8a3636-5963-8144-aad4-eecda46c7c1d` | `2026-07-28T13:57:00Z` |
| 20.6 Capability Runtime | `3a8a3636-5963-81f0-9291-f82e3d2cc47e` | `2026-07-28T13:57:00Z` |
| 20.7 Adapter Protocol | `3a8a3636-5963-81be-ae29-f17d288fb552` | `2026-07-28T13:58:00Z` |

判定以各页“规范性基线、唯一推荐实施架构、不可省略验收条件/实现完成判定”为准。
测试替身、内存实现和 SQL 字符串契约不能替代规范明确要求的生产执行闭环。

## 3. 逐页状态矩阵

| 页面 | 当前状态 | 已实现的主要能力 | 阻止整页完成的真实代码缺口 |
| --- | --- | --- | --- |
| 20.0 | 部分实现 | Turn、Persona 快照、Retrieval、Capability 和 Adapter 已有可运行主干 | 20.1-20.7 尚未全部闭环，端到端调用顺序仍混用旧链路 |
| 20.1 | 部分实现 | 消息 DAG、active leaf、typed reference、摘要失效、PostgreSQL 图快照、全局 token budget | 无类型化 `ContextBundle` 和完整构建流水线；在线 Prompt 未使用持久摘要；无可复现 build trace、引用局部恢复和后台预压缩任务 |
| 20.2 | 部分实现 | Candidate 审批、L0 脱敏、不可变版本、Embedding Outbox/重试/Dead Letter、正式写入防旁路 | 缺完整状态枚举和 merge policy、三方合并、冲突分支/last stable、TOMBSTONE 删除投影、本地文件镜像与 repair queue |
| 20.3 | 部分实现 | 版本化 Knowledge、父子 Chunk、Notion 增量同步、pgvector/全文投影、Graph evidence、Weighted RRF、typed trace | 新 Retrieval Runtime 只统一 Knowledge；Lore/Memory/Web 仍走旧 Lane；Memory 仍线性融合；Web 缺 Search/Extract/过滤/rerank/typed citation 全流程 |
| 20.4 | 部分实现 | 确定性 temporal、override TTL、昼夜不改变关系、Persona revision/provenance、表现映射 | 缺 Profile/Relationship/Scene/Interaction 权威仓储、三阶段 Reducer、Scene 状态机、`EffectivePersonaState`、`PromptPolicyComposer` 和 Persona eval |
| 20.5 | 部分实现 | Turn/Event/Outbox Schema、事务追加、幂等、deadline、并发 lane、SSE replay、cursor snapshot | 主链仍等待完整 `LlmResponse` 后一次性发送 `native=false` 全文；无 Turn Outbox dispatcher；重启 failure code/retry_of 不完整；默认仍是内存 journal |
| 20.6 | 部分实现 | Catalog/Registry/Exposure/Policy/Approval/Executor/Audit、冻结 Snapshot、MCP 管理、持久幂等、Agent 状态与预算 | Prompt Skill 未进入 Context 主链；无真实 Remote Agent/A2A 网关；旧 Gateway 未全部归一；真实 MCP 认证/变更/恶意输出闭环待补 |
| 20.7 | 部分实现 | Hello、版本协商、ReplayPolicy、Snapshot/CURSOR_EXPIRED、三端 checkpoint 和恢复主链 | 共享 TypeScript 事件目录缺 Tool/Approval/Agent required 事件；AIRI/Website 幂等键重试不稳定；三端未真正运行同一 fixture 和真实跨端 E2E |

## 4. 已实现范围与代码证据

### 20.1 Context 基线

- `SessionContextStore` 已实现不可变消息 DAG、active leaf、分支路径、三类引用和摘要
  revision/digest/STALE 过滤。
- `PostgresSessionContextPersistence` 可用 revision 乐观锁持久化完整图快照。
- `GlobalPromptBudget` 与 `OpenAiProviderTokenizer` 已实现真实 tokenizer 和整块预算裁剪。

### 20.2 Memory 安全基线

- Runtime、兼容 supersede 与 bridge 均先创建 candidate，不能直接改正式记忆。
- L0 原文在第一次 repository 写入前被替换为指纹和无原文拒绝原因。
- 正式版本与 Embedding Outbox 同事务写入，Worker 支持重试与 Dead Letter。
- 普通记忆候选不能修改 `relationship_stage` 等受保护领域字段。

### 20.3 Knowledge 与 Graph 主干

- `knowledge_document/version/chunk/entity/relation`、BUILDING 到 ACTIVE 原子发布、
  失败保留上一 ACTIVE 和 tombstone 已实现。
- CHILD keyword/vector 独立检索、父块恢复、ACL/版本/有效期过滤和 Weighted RRF 已实现。
- Graph 限制 1-3 hop，每条边验证同版本可见 evidence chunk，失败确定性回退 Hybrid。
- Notion allowlist 增量同步、凭据指纹变更重建和敏感信息入库前检测已实现。

### 20.4 Persona 基线

- `RuntimeStateMachine` 保证 temporal 与 relationship 正交，并提供 debounce/cooldown。
- Turn 冻结 Persona revision 与基础 provenance；ExpressionResolver 确定性映射语义 cue。

### 20.5 Turn 与恢复基线

- `PostgresTurnJournal` 已实现 Turn、Event、幂等、sequence 和 Outbox 的事务路径。
- 事件先经 journal 持久化再被客户端轮询/回放；终态竞争和 bounded subscriber buffer 已测试。
- Session Snapshot、ReplayPolicy 与 cursor 过期恢复接口已接入协议层。

### 20.6 Capability 与子 Agent 主干

- Capability Catalog、Exposure Planner、Policy、Approval、Executor、Normalizer 与 Audit 已分层。
- Snapshot 热更新不改变在途 Turn；WRITE 绑定 approval、operation、input digest 和持久幂等。
- SkillExecution、StepExecution、AgentTask 与 Execution Resource Registry 已拆分。
- 子 Agent 的权限、deadline、token/tool/cost/depth/child/concurrency 预算只能递减。
- 漏配真实 Remote Agent 时默认 fail closed，内存替身必须显式启用。

### 20.7 Adapter 主链

- Java、TypeScript、Python 已有 Adapter Protocol v1 Schema 与协商实现。
- AIRI、AstrBot、Website 已实现 checkpoint、sequence/event_id 去重和 ONCE 副作用前持久化。
- 同步聊天入口包装同一异步 Turn，而不是维护第二套业务编排。

## 5. 当前 P0/P1 代码缺口

### P0

1. 20.5 主链必须消费 Provider 原生 `Flux<String>`，不能继续等待完整结构化响应后发送一个全文 delta。
2. 20.7 共享事件目录必须加入 Tool、Approval、Skill 和 Agent 生命周期事件，并在三端执行同一 fixture。
3. 20.4 需要落地权威 Persona/Relationship/Scene 模型与 Prompt Policy，不能只包装旧 `RuntimeStateMachine`。

### P1

1. 20.1 需要 `ContextBundle`、在线摘要选择/rehydration、来源级预算和可持久构建 trace。
2. 20.2 需要三方合并、冲突分支、last stable、TOMBSTONE 投影和本地文件镜像闭环。
3. 20.3 需要把 Lore、Memory、Knowledge、Graph、Web 全部接入同一 Planner/Bundle/Trace，Memory 改用 RRF，Web 补齐安全提取链。
4. 20.5 需要 Turn Outbox dispatcher、稳定重启 failure code、retry_of 和 PostgreSQL 故障恢复测试。
5. 20.6 需要 Prompt Skill 主链、真实 Remote Agent 传输和全部旧 Gateway 的 Capability 归一。
6. 20.7 需要稳定可复用的客户端幂等键、生成式跨语言 DTO 和跨端身份/记忆 E2E。

## 6. 自动化证据

最近一次完整记录：

| 范围 | 结果 |
| --- | --- |
| Java Maven 全量 | `287` tests，`0` failures，`0` errors，`1` skipped |
| Python 全量 | `342 passed`，`8 skipped` |
| 根 TypeScript | `42/42 passed` |
| AIRI Meguri Adapter | `22/22 passed` |
| AIRI Adapter 严格 TypeScript | passed |
| AIRI 指定 ESLint | passed |
| AIRI Stage | `405` tests passed；4 项既有 Windows/上游环境失败 |
| AstrBot 插件打包 | passed |
| 两仓库 `git diff --check` | passed |

本轮复核额外运行了 20.1/20.2 定向 `67` 项和 20.3/20.4 定向 `94` 项，均通过。
这些测试证明已存在能力没有明显回归，但不能证明上表列出的缺失组件已经实现。

## 7. 外部环境待验

- 真实 PostgreSQL + pgvector：迁移、GIN/HNSW 查询计划、锁、并发、事务失败、重启和恢复。
- 真实 Notion：分页、限流、删除、ACL 外部映射变化、定时同步与凭据轮换。
- 真实 MCP/Remote Agent：认证、断连、重复投递、恶意输出、跨进程恢复和隔离。
- 真实 Provider：原生流式、TTFT、取消、超时、部分文本与稳定 failure code。
- AIRI/AstrBot/Website：同一 Core、同一 fixture、真实身份、断线重连、过期 token、重复副作用与跨端正式记忆。
- 生产运维：授权、secret 扫描、备份恢复、监控告警、容量、回滚和故障演练。

## 8. 文档权威关系

- **本文件是 2026-07-29 起唯一最新的 20.x 实现状态文档。**
- `docs/notion-20-audit-2026-07-28.md` 是提交 `a594069` 之前的历史差距快照。
- `docs/harness-runtime-20.md` 记录接口与实现边界，不单独决定逐页完成状态。
- `docs/requirements-to-confirm-2026-07-27.md` 记录产品取舍与生产验收门槛。
- `docs/open-items-2026-07-27.md` 只维护剩余工作索引，详细证据以本文件为准。

## 9. 写入 Notion 的准确口径

在上述 P0/P1 代码缺口关闭前，Notion 20.0 顶部“尚未完整实现”的状态应继续保留。
可以写入“20.3 Knowledge/Graph 子系统和 20.6 Capability/Agent 核心框架已完成本地实现”，
但不能写成“20.0-20.7 全部已实现”或“生产验收完成”。

## 10. Notion 同步记录

2026-07-29 已把本次全量复核结论追加到以下页面，保留原规范和图片：

- 20.0 总规范：`3aaa3636-5963-81a3-ab30-ebe83c75e3e9`
- 20.x 实施与验收计划：`3aba3636-5963-8171-a373-ff8209a6c781`

同步内容明确区分“实施计划圈定范围已完成”和“20.x 全量仍部分实现”，未写入
“20.0-20.7 全部完成”或“生产验收完成”的错误状态。
