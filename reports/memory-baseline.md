# Meguri 权威记忆服务基线审计

审计日期：2026-07-14（Asia/Shanghai）  
代码基线：`ad8d405`（原分支 `feat/framework-bootstrap`）  
实施分支：`codex/feat/native-pgvector-memory`  
数据 build ID：`meguri_v2_02c3db0c507d7c2d`

## 结论

当前仓库已经具备可替换 `MemoryProvider`、本地 `FakeMemoryProvider`、短期会话隔离、记忆故障不阻断文本回复，以及 Existing MemoryOS 兼容适配器；但尚未具备 PostgreSQL/pgvector 数据模型、迁移、事务、权威候选审核、持久化身份绑定、可靠 embedding outbox、正式 REST API、审计指标和恢复验证。实施必须在保留现有导入路径与运行时调用语义的前提下扩展，不能把旧 MemoryOS 写接口继续视为正式能力。

权威依据为 [16｜PostgreSQL + pgvector 权威记忆服务实施计划](https://app.notion.com/p/39da36365963818b904ad4960dd3addc)。环境契约来自 [15｜开发、Staging 与生产隔离实施计划](https://app.notion.com/p/39da363659638157a494e897cedef86f)，代码实际基线由 [13｜框架开发进度与第一阶段交付](https://app.notion.com/p/39ca3636596381deb589ef796d8375cf) 补充。旧页冲突时以 16 和当前代码公共契约为准。

## 当前 `MemoryProvider` 接口

位置：`services/meguri_core/memory.py`

```python
class MemoryProvider(Protocol):
    async def extract_candidates(input) -> list[MemoryCandidate]: ...
    async def search(input) -> list[MemoryHit]: ...
    async def upsert(input) -> MemoryRecord: ...
    async def supersede(old_id, next) -> MemoryRecord: ...
    async def delete(memory_id) -> None: ...
    async def summarize_session(input) -> SessionSummary: ...
    async def list_records(user_id, include_deleted=False) -> list[MemoryRecord]: ...
```

现有接口使用 Pydantic 领域对象，没有泄漏 ORM、SQLAlchemy Row 或 pgvector 私有类型。与 16 的主要差异是缺少显式 `create_candidate`、`review_candidate`、`get`、`restore`、`export_user`、actor、request ID、tenant/environment 和稳定错误模型。

## 当前调用链

```text
POST /v1/chat/respond 或 POST /v1/turns
  -> TurnOrchestrator
  -> MemoryProvider.search(user_id, query)
  -> LLM（注入已召回 canonical_text）
  -> LLM memory_candidates 或 provider.extract_candidates
  -> CompanionMemoryPolicy.review
  -> accepted 时 MemoryProvider.upsert
  -> memory.write.completed 事件
```

读取、候选处理和写入异常都被运行时捕获；文本回复继续，`memory_status` 降级为 `unavailable`。这是必须保留的稳定性契约。当前 runtime 仍可能把策略自动接受的候选直接 `upsert`，未先持久化候选、审核记录、审计和 outbox，不满足 16 的原子事务要求。

## 当前身份模型

- `TurnRequest` 直接接收 `user_id + client_id + session_id`；`client_id` 受控为 `astrbot / desktop_pet / website`。
- `SessionContextStore` 已按 `(user_id, client_id, session_id)` 隔离最近上下文。
- AstrBot adapter 使用进程内 `IdentityMapper`；显式绑定后可把多个平台身份映射到同一 `meguri_user_id`，私聊和群聊 session ID 分离。
- Website/AIRI 由客户端或可信上游直接传统一 `user_id`；core 当前没有持久化、验证状态、环境 namespace、解绑审计或越权检查。
- 当前没有 `tenant_id`，也没有数据库级 `(tenant_id, platform, platform_user_id)` 唯一约束。

因此现有身份结构只能作为客户端兼容输入，不能作为正式跨端授权依据。权威实现必须引入持久化 `identity_bindings`，只有 verified/active 绑定才能解析到可跨端共享的统一用户；显示名不得触发合并，解绑不得级联删除记忆。

## 当前 Fake 与 MemoryOS 实现

### `FakeMemoryProvider`

- 进程内字典保存 `MemoryRecord`。
- 支持简单文本去重、搜索、supersede、软删除、session summary 和列表。
- `supersede` 当前创建新的 memory ID，而 16 要求一个 memory item 下的不可变 version chain；这需要兼容转换。
- 没有候选实体、restore、审计、outbox、request ID 幂等或环境隔离。

### `ExistingMemoryOSAdapter`

- 使用加盐 HMAC scope 隔离用户，检索和 journal 导出映射为 Meguri 领域对象。
- 不调用 MemoryOS `/respond` LLM；supersede 和 delete 已显式拒绝。
- 当前仍实现 `upsert`，会调用第三方 `/records`。这与计划 16 的“只读导入/候选/shadow”边界冲突。
- 迁移后保留只读搜索、journal 导入候选和 shadow 对照；任何正式写方法继续存在仅用于兼容类型检查，但必须明确抛出 unsupported，不能改变第三方实例。

## 环境依赖

| 检查项 | 当前结果 | 影响 |
|---|---|---|
| `memory-environment-handoff.md` | 未找到 | 无 dev/staging 连接、migration job 或恢复入口 |
| `MEGURI_DATABASE_URL` / PostgreSQL 环境变量 | 未配置 | 不能执行真实数据库集成测试 |
| Docker CLI | 未找到 | 不能在本机启动隔离 pgvector 容器 |
| `psql` / PostgreSQL binaries | 未找到 | 不能执行本地真实 upgrade/restore |
| SQLAlchemy / Alembic / asyncpg / pgvector Python 包 | 未安装 | 需要加入项目依赖并安装到既有 `py314` 环境 |
| dev PostgreSQL + pgvector | 未交付 | 仅可做静态、单元和无数据库契约验证 |
| staging PostgreSQL + pgvector | 未交付 | staging 准入保持阻断 |
| 数据 build ID | 已验证 | `meguri_v2_02c3db0c507d7c2d` |

不得连接或修改现有 PostgreSQL 16.13；后续真实验证只能使用环境 Agent 提供的隔离 dev/staging 数据库。

## 与计划 16 的差异

| 范畴 | 当前代码 | 计划 16 要求 |
|---|---|---|
| 领域模型 | 少量 `Literal` 与扁平 `MemoryRecord` | 严格枚举、候选、item、不可变 version、embedding、actor、export、identity、audit |
| Schema/migration | 无数据库依赖和迁移目录 | pgvector extension、9 张核心表、约束、基础索引、upgrade/downgrade |
| 事务 | 内存原地修改 | item/version/audit/outbox 原子提交，行锁与并发版本分配 |
| 候选 | LLM 后立即内存审核 | candidate 持久化、敏感过滤、去重/冲突、审核状态机 |
| embedding | 无 | 固定模型 revision、内容 hash、outbox worker、重试/死信 |
| 检索 | 词项重合 | tenant/user/status/expiry 过滤、结构化/关键词/exact vector、重排/token budget |
| 身份 | 客户端/进程内映射 | verified 持久绑定、解绑审计、环境隔离、跨端共享 |
| API | 旧 `/v1/memories*` 子集 | `/v1/memory/candidates*`、search/get/supersede/restore/export、identity routes |
| 审计/指标 | 无 | 只追加审计、稳定指标且不含高基数/正文标签 |
| 第三方记忆 | MemoryOS 仍可 append | MemoryOS/Mem0 仅只读导入或 shadow |
| 恢复/性能 | 无 | 全新实例恢复完整性、固定召回、p50/p95/p99/错误率/recall |

## 迁移兼容方案

1. 保留 `services.meguri_core.memory` 的现有名称和旧领域对象，避免破坏 runtime、测试和第三方 adapter 导入。
2. 在新的 `services/meguri_core/memory_service/` 包实现 16 的枚举、模型、ORM、repository、service、native provider、检索、identity、audit、metrics 和 worker；数据库对象不向应用层泄漏。
3. `NativePgvectorMemoryProvider` 同时实现权威新协议和旧 `MemoryProvider` 协议。旧 `upsert/list_records` 作为兼容 facade，内部仍走候选/版本/审计/outbox 事务或受控管理查询。
4. 扩展旧 Pydantic 输入为可选 `tenant_id`/request metadata 时提供安全默认值；运行时配置使用显式 `MEGURI_ENV`/tenant，不根据端口推断环境。
5. REST 新路由与现有 `/v1/memories`、`/v1/memories/review` 并存。旧路由委托新 service，完成调用方迁移后再考虑 contract 阶段。
6. 数据库采用 expand -> migrate -> contract；第一阶段只新增表、约束和索引，不删除旧公共字段。Schema 保留 N/N-1 应用过渡窗口。
7. MemoryOS 的 append 写行为被关闭；导入只生成带 `source_kind=memoryos_import` provenance 的 pending candidate。
8. provider/database 故障继续返回明确的无记忆降级，不自动切换 MemoryOS 为权威源。

## 第一阶段任务范围

第一阶段按 M-001 至 M-012 实施，但环境依赖分成两类：

- 本仓库可完成：领域与 ORM 模型、Alembic revisions、repository/service/provider、候选审核、版本/删除/恢复/导出、outbox worker、检索与 token budget、identity/session summary、REST/audit/metrics、MemoryOS/Mem0 shadow 边界、Fake/native contract tests、静态恢复校验器、API/Schema/runbook/报告。
- 必须等待环境交付后完成：真实 PostgreSQL + pgvector migration upgrade/downgrade、并发 `SKIP LOCKED`、真实向量 SQL、dev/staging 备份恢复演练、数据库账号/网络隔离、真实性能 p50/p95/p99 和 ANN recall。

在后一类验证全部通过前：

- 不宣称权威记忆服务完成；
- 不满足 staging 准入；
- production `MEGURI_MUTATION_ALLOWED` 必须保持 `false`；
- production 候选批准、supersede、删除/恢复、身份绑定和导入写操作全部禁止。

## Sources

- [16｜PostgreSQL + pgvector 权威记忆服务实施计划（Agent 执行版）](https://app.notion.com/p/39da36365963818b904ad4960dd3addc)
- [15｜开发、Staging 与生产隔离实施计划（Agent 执行版）](https://app.notion.com/p/39da363659638157a494e897cedef86f)
- [13｜框架开发进度与第一阶段交付](https://app.notion.com/p/39ca3636596381deb589ef796d8375cf)
- [06｜RAG、共享记忆与上下文组装提示词](https://app.notion.com/p/39aa363659638149a119e8ad33167e8a)
- [08｜长期记忆架构决策：成熟框架与陪伴型特化](https://app.notion.com/p/39ba3636596381abb425d6b1fae80154)
- [05｜运行时系统提示词与 JSON 输出契约](https://app.notion.com/p/39aa3636596381f78e81e707f3373db9)
- [07｜AstrBot／桌宠／网站多端接入实施提示词](https://app.notion.com/p/39aa3636596381adadbdcdfd535b32c0)
- [00｜Meguri AI 提示词索引与项目约定](https://app.notion.com/p/39aa36365963817eb300ee42c7dff346)
- [rules｜Meguri 数据对齐与项目数据契约](https://app.notion.com/p/39ba3636596381bb92e8dac2e4356576)
- [12｜训练数据仓二次预处理结果（GO）](https://app.notion.com/p/39ba3636596381a08d18c5da37266e81)
- [11｜shizuki-site 中间件服务器部署与 AstrBot 共存方案](https://app.notion.com/p/39ba3636596381588204e4e7ef9b698c)
- [14.2｜PostgreSQL + pgvector 权威记忆服务实施规范](https://app.notion.com/p/39da363659638179bd67c051079dfbf5)
