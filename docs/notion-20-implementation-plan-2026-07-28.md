# Notion 20.x 新增规范实施审计（2026-07-28）

## 1. 本轮范围与规范来源

本轮以 2026-07-28 最新读取的以下 Notion 页面为规范基线：

- 20.3《分层 Retrieval Runtime：Lore、Personal Memory、Knowledge Base、Knowledge Graph 与 Web》
  - Page ID：`3aaa3636-5963-8131-81aa-dcd3a36ada36`
  - Notion 最后更新时间：`2026-07-28T13:56:00Z`
- 20.6《技能系统、工具调用、MCP、资源注册、持久执行与远程 Agent》
  - Page ID：`3a8a3636-5963-81f0-9291-f82e3d2cc47e`
  - Notion 最后更新时间：`2026-07-28T13:57:00Z`

本轮完整实现的新增重点是：父子 Chunk 与版本化 Knowledge、pgvector/全文双路召回、轻量 Knowledge Graph、Notion 增量同步、可复现 Retrieval Trace、Capability/Tool/MCP 注册与冻结快照、WRITE 审批/幂等/审计，以及受限子 Agent。20.0～20.7 中不属于这些新增点的既有能力没有在本轮重写。

## 2. 已完成实现

### 2.1 Knowledge 与 Notion

- PostgreSQL 使用 `knowledge_document`、`knowledge_document_version`、`knowledge_chunk`、`knowledge_entity`、`knowledge_relation` 五张核心表，并提供非破坏迁移。
- `knowledge_document` 保存稳定身份、source/canonical URI、语言、项目、父页面、链接页面、ACL 与当前 ACTIVE 版本指针。
- 文档版本保存严格 SHA-256、parser/chunker/embedding revision；Chunk 保存 section path、heading、源偏移、内容 SHA-256、token count，并在 CHILD 上保存 embedding revision。
- Markdown/Notion 标题章节生成 PARENT，章节内段落生成 CHILD；召回只排名 CHILD，再按命中位置恢复父章节或相邻段落并执行 Token Budget 裁剪。
- CHILD 全文投影与 pgvector 投影内联在 `knowledge_chunk`，使用 GIN 与 HNSW 索引；Keyword 与 Vector 独立调用、独立异常和独立本地回退。
- 新版本先 BUILDING，Chunk、Keyword、Embedding、Entity、Relation、Evidence 与 ACL 全部校验后原子切换 ACTIVE；失败保留上一 ACTIVE，删除先 tombstone。
- Graph 实体和关系与 Chunk 使用同一文档版本、ACL 和有效期；每条边必须绑定可见 evidence chunk。
- Notion 每个 allowlist 页面映射独立文档，保留页面层级和链接；每轮读取页面元数据，`last_edited_time`、标题、canonical URI、父级、ACL、同步配置或凭据指纹变化时重新下载 Blocks，否则复用快照。
- 默认 `application.yml` 显式导入 Notion 配置；仅设置 `MEGURI_NOTION_*` 环境变量即可启用，不要求额外激活 `notion` profile。
- 入库前检测 Bearer、敏感 Cookie、Notion/GitHub/AWS 凭据、私钥、常见 API Key 与含凭据数据库 URI；不把凭据写入文档或日志。

### 2.2 Retrieval 与 Knowledge Graph

- `RetrievalGate`、Planner、`NONE/FAST/SLOW`、独立来源 Lane、来源预算/席位和 Weighted RRF 已接入 Turn。
- Query Rewrite 显式输出 `relationType` 与 `NONE/RELATION/TIMELINE/CALL_CHAIN`；仅明确关系、时间线或调用链问题启用 Graph，最大三跳。
- Graph 路径按关系意图过滤，并逐项验证实体、边、版本、ACL、有效期和 evidence；失败、超时或无有效证据时确定性回退 Hybrid。
- Citation 已从字符串升级为结构化对象，可保存多个 canonical URI、标题、文档版本、Chunk ID 和源偏移；旧字符串 Trace 仍可兼容读取。
- Retrieval Trace 保存冻结 snapshot/revision、`validAt`、算法 revision、完整 Lane、Rank Trace、组装前候选及 `selected/source_duplicate/bundle_budget_or_seat_limit` 决定，并可 JSONB 往返和精确重放。
- Lane 与 Graph 超时会实际取消底层 Future，不再让已超时任务继续占用线程或数据库连接。

### 2.3 Capability、Tool 与 MCP

- Capability Catalog、Exposure Planner、Policy、Approval、Executor、Result Normalizer 与 Audit 分层实现。
- 每个 Turn 冻结 Capability Snapshot；热更新只影响新 Turn，旧版本按引用计数 drain。
- MCP 远端描述、Prompt 和风险声明均按不可信输入处理；本地风险下限、scope、审批和策略不可被远端描述提升，调用固定到已审批版本。
- 审批绑定 Capability 版本、Snapshot、Turn、trace、租户、用户、客户端、operation、幂等键和输入 digest，不能跨请求挪用。
- WRITE 调用要求审批、operation 与持久幂等；相同幂等键但 payload 不同返回 `IDEMPOTENCY_PAYLOAD_MISMATCH`，超时进入 `UNKNOWN_OUTCOME` 并要求人工/业务核验，禁止盲目重试。
- 暴露、拒绝、审批、执行、失败、超时和重放均写审计，保存输入/结果 digest 而不是泄露原始敏感载荷。

### 2.4 SkillExecution 与子 Agent

- Capability Registry、Execution Resource Registry、SkillExecution/StepExecution Store、AgentTask Store 相互独立。
- 子 Agent 只继承裁剪后的 Context；deadline、trace parent、取消、幂等、Capability、token/tool/cost/depth/child/concurrency 预算只能缩小或从父级扣除。
- 兄弟任务执行聚合预算校验，提交容量与 in-flight 容量分别受限。
- `AWAIT` 使用非阻塞恢复；`DURABLE_ASYNC` 持久化父 Skill 状态并释放本地执行资源。
- 父 Turn 取消向子 Agent 传播；终态幂等重放返回原结果，不重复远端副作用。
- Agent 结果执行递归 Schema、来源和敏感数据校验；不合规结果 fail closed，不能直接注入 Prompt。
- 可选 Agent 在服务不健康或容量不足时持久化为 `SKIPPED` 并由主模型继续；required Agent 明确返回不可用。
- Spring 未配置真实 Remote Agent 时安装 fail-closed 网关并返回 `AgentUnavailableException`；确定性内存替身仅能通过显式 `MEGURI_AGENT_GATEWAY_MODE=in-memory` 启用，不会在生产漏配时伪造成功结果。

### 2.5 Adapter 与 AIRI/AstrBot/Website

- Java、TypeScript、Python 共用 Adapter Protocol v1 Schema 与 fixtures。
- Hello/版本协商、`STATE/ONCE/ALWAYS`、稳定 `event_id + sequence` 去重、410 `CURSOR_EXPIRED` + Snapshot、SSE 续传和未知 optional/required 事件语义已实现。
- SSE 回放边界只由客户端 `after_sequence`/`Last-Event-ID` 决定；首次订阅可收到连接前产生的 ONCE 事件，重连去重由客户端持久 checkpoint 保证。
- AIRI required 事件白名单已覆盖 Retrieval/Knowledge、Tool/Approval、Skill/Agent、TTS/媒体事件；ONCE 事件在页面副作用前持久化 checkpoint。
- AIRI checkpoint 与 Reducer 按 Session 隔离，并可迁移旧版单 checkpoint 状态；同一 Desktop Runtime 切换会话不会串用 sequence 或 ONCE 去重集合。
- AstrBot 与 Website 使用同一异步 Turn、恢复、取消和 ONCE 去重语义；AstrBot 插件可确定性打包。

## 3. 自动化验证

| 范围 | 结果 |
| --- | --- |
| Java Maven 全量 | `287` tests，`0` failures，`0` errors，`1` skipped |
| Python 全量 | `342 passed`，`8 skipped` |
| 根 TypeScript 协议/Adapter | `42/42 passed` |
| AIRI Meguri Adapter | `22/22 passed` |
| AIRI Adapter 严格 TypeScript | passed |
| AIRI 指定 6 文件 ESLint | passed |
| AIRI Stage 测试 | `60` files、`405` tests passed；另有 4 项既有 Windows/上游环境失败 |
| AstrBot 插件打包 | passed，归档内容已核对 |
| 两仓库 `git diff --check` | passed |

Java 唯一 skipped 项依赖真实 PostgreSQL。Python 的 skipped 项是环境/可选依赖测试，不是本轮失败。

## 4. 环境待验与诚实边界

- 本机没有可用 PostgreSQL + pgvector 实例，因此 SQL 已通过契约测试和 Java 适配器测试，但真实迁移、索引计划、事务并发和故障注入必须在集成环境补跑。
- Notion API 不提供页面完整成员 ACL 查询。本实现采用固定配置 ACL 外部映射，并在配置变化时强制重建；不能声称已从 Notion 自动同步完整成员权限。
- 本轮没有真实外部 MCP Server 与 Remote Agent 服务，网络断连、远端重复投递和跨进程恢复仍需集成环境验证。
- AIRI 仓库缺少既有 `apps/server` 与 `@proj-airi/server-runtime/server` 入口，导致根 Vitest、`stage-ui`/`stage-tamagotchi` 全量 typecheck 无法完成；Meguri Adapter 自身定向测试、严格 typecheck 和 lint 已通过。
- AIRI Stage 剩余失败来自 Windows symlink 权限、路径断言和既有包入口，不由本轮 6 个文件引入。

## 5. 完成判定

本轮用户特别说明的 Knowledge Graph、工具/MCP 注册和子 Agent 功能已完成代码实现、内存/契约/跨语言自动化验证和适配层接入。真实 PostgreSQL、真实 Notion 权限映射、外部 MCP/Agent 网络以及 AIRI 上游缺失模块列为环境待验，不伪报为已验证。
