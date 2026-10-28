# 记忆运行时只读审计（2026-10-12）

范围：核对 `docs/native-desktop-memory.md`、`docs/p2-runtime-map-2026-10-08.md`、`docs/memory-schema.md` 与当前 Java Core、Python memory bridge、PostgreSQL/pgvector 实现和测试。本文只记录代码现状与第一期切入建议；未运行测试，未修改源码。仓库中大量改动和未跟踪文件均为已有工作。

## 当前边界

权威记忆的正式持久层是 Python `NativePgvectorMemoryProvider` / `MemoryService` / SQLAlchemy repository 与 PostgreSQL/pgvector。Java Core 通过受保护的 `PythonMemoryGateway` 调用内部 API；桌面端通过 loopback gateway、Java 管理 API进入候选审核、查看、删除、恢复和导出流程。文档中的调用链见 `docs/native-desktop-memory.md:15-23,53-55`；运行配置说明见 `docs/p2-runtime-map-2026-10-08.md:8-29,41-62`。

需要注意文档版本漂移：`docs/native-desktop-memory.md:5,47` 记载部署迁移 head `20260729_0007`，而 `docs/memory-schema.md:3,20-25` 写到 `20260728_0006`。后者对当前部署不是最新状态。

## 入库、审核、版本和遗忘

运行时 LLM 候选的当前 schema 是 `services/meguri_core/schemas.py:63-71`：只含类型、摘要、置信度、敏感级别和来源范围。Java `PythonMemoryGateway.java:84-107` 将响应中的候选发送至 Python `/internal/memory/write`；`services/meguri_core/memory_bridge.py:180-218` 通过旧策略检查后调用权威候选入口。候选映射在 `services/meguri_core/memory_service/native_pgvector.py:474-518`。桌面周期整理由 `java/meguri-core/src/main/java/com/meguri/core/memory/DesktopMemoryService.java:73-103` 执行，最多读取最近 20 条消息、对疑似凭据做替换、最多提取 3 条候选；Python bridge 以 session/checkpoint 维持幂等（`services/meguri_core/desktop_memory_bridge.py:131-155`）。候选审核、冲突解决和审计在 `services/meguri_core/memory_service/service.py:129-239,253-496`，安全规则在 `services/meguri_core/memory_service/review_policy.py:96-179`。

记忆版本为不可变内容链，current/last stable pointer、重要度、置信度、生效与到期时间归属记忆聚合；参见 `services/meguri_core/memory_service/models.py:142-204`。删除和恢复走软删除/tombstone、幂等和审计（`service.py:682-760`）；检索只允许 active、未删除、生效且未过期的版本（`retrieval.py:33-42`；SQL过滤见 `repository.py:1137-1145,1212-1225`）。系统没有按访问频率或认知状态自动遗忘；到期是显式 `expires_at`。

可复用验证已经覆盖候选—版本—删除恢复工作流：`tests/integration/memory/test_native_workflow.py:74`；软删除恢复与受限硬删除：`tests/unit/memory/test_export_delete_restore.py:182-212`。不建议首期重做这些已有能力。

## 为什么当前在线召回以文本为主

数据库和召回代码都具备语义向量能力：embedding 存储与工作队列见 `docs/memory-schema.md:11,15,23-25`；`native_pgvector.py:78-107,206-235` 装配查询 embedding 和 reranker；`repository.py:1117-1163` 执行 pgvector cosine 距离查询。但 `docs/native-desktop-memory.md:47` 记录实际本地部署将 embedding 与 reranking 都设为 disabled。无 embedding provider 时 `_with_query_embedding` 返回没有向量的原 query（`native_pgvector.py:206-235`），`MemoryService.search` 因而跳过 vector lane，只保留 keyword lane（`service.py:498-556`）。PostgreSQL keyword lane 对 `content_text` 使用 `to_tsvector('simple')`、`plainto_tsquery('simple', query)` 与 `ts_rank_cd`，并用 substring contains 兜底（`repository.py:1199-1237`）。因此当前运行时是 PostgreSQL 全文文本检索加子串补充，而非语义向量召回。代码在 embedding/reranker 启用后可进入 hybrid 路径，但这不是当前部署事实。

已实现的排序信号为 semantic、keyword、importance、confidence、recency；默认权重分别为 0.45、0.15、0.15、0.15、0.10，recency 半衰期默认 180 天（`services/meguri_core/memory_service/retrieval.py:12-30,59-71,83-142`）。这些权重不会让当前关闭的 semantic lane 自动生效。

## 情感、认知和敏感度字段

长期记忆类型只有 user profile/preference、important person、long-term project、commitment、relationship fact、recurring habit、corrected fact（`services/meguri_core/memory_service/enums.py:6-15`）。没有专门的 emotion、cognition、mood 或 mental-state memory type/column。旧兼容入口直接拒绝把 `recent_emotion` 和 `session_summary` 晋升为长期事实（`native_pgvector.py:520-522`）；策略也会拒绝短时情绪、模型推断和敏感属性推断（`review_policy.py:104-123,146-164`）。`LlmResponse` 的 expression tag/intensity/voice style 是本轮输出/表现元数据，不是长期情绪记忆（`services/meguri_core/schemas.py:73-80`）。

有 `sensitivity` 字段，枚举为 normal/private/sensitive（`services/meguri_core/memory_service/enums.py:17-21`），候选模型有此字段（`models.py:95-127`），初始 schema 在 `migrations/versions/20260714_0002_create_memory_tables.py:62-88`，ORM 候选行亦保留（`orm.py:98-123`）。但权威 `MemoryItem` / `MemoryVersion` 没有 sensitivity（`models.py:163-204`；对应 ORM item/version 定义约 `orm.py:149-215`）；批准后这一维度不进入可检索项，旧 facade 输出甚至固定成 normal（`native_pgvector.py:626-631`）。桌面 memory contract/UI也没有显示候选 sensitivity（`apps/desktop-meguri/src/shared/memory-contract.ts:1-40`、`components/MemorySettings.vue:118-134`）。这意味着 private 标签目前对候选审核/策略有意义，但批准后无法继续指导召回边界。

存在 `content_json` JSONB 扩展槽（候选、更新和版本模型分别见 `models.py:95-117,142-160,163-175`），验证仅排除部分受保护 relationship state key（`models.py:46-87`）。它在存储层可承载额外结构，但当前 Java 候选 DTO 与 LLM JSON schema都要求精确字段，没有明确的情绪/认知子结构（`schemas.py:63-71`；`java/meguri-core/src/main/java/com/meguri/core/llm/LangChain4jLlmProvider.java:894-963,1215-1221`）。因此这是可扩展容器，不是已建好的情绪/认知领域模型。

## 两种反馈系统及其断点

权威记忆反馈表/API支持 helpful、wrong、stale、corrected、user_deleted、false_recall，并且记录 exact memory_id/version_id/query_text/hit_rank/details：API 在 `services/meguri_core/memory_api.py:311-329`，模型在 `memory_service/models.py:231-246`，持久化校验在 `repository.py:408-432`，幂等提交及 false-recall metric 在 `service.py:573-608`。但 Java 在线 recall 把搜索命中降为 `canonical_text` 字符串（`java/meguri-core/src/main/java/com/meguri/core/memory/PythonMemoryGateway.java:43-62`），不向 UI/反馈通道暴露 memory/version/rank，也未自动写反馈。feedback 记录后除 false_recall metric 外不影响召回打分、置信度或遗忘；这是已建 schema 尚未接通的闭环。

训练反馈是另一套：对回复采样 A/B 或单条观测，用户明确同意后归档，支持 pending、撤回与离线审核导出；参见 `java/meguri-core/src/main/java/com/meguri/core/training/TrainingFeedbackService.java:73-120,128-214,216-263` 和 `TrainingFeedbackController.java:32-67`，训练数据构建在 `training/llm/scripts/feedback_dataset.py`。它监督回复风格/内容，不是记忆召回反馈，不能将训练样本评价当作 memory item 的使用信号。

## Windows 观察、隐私策略与受控操作现状

仓库已有 OpenSpec change `openspec/changes/add-desktop-observation-runtime/`，任务 1–4 标记完成，但它明确是领域层和 mock 契约，不是可用的 Windows 感知产品：proposal/design 的目标是 attachment/window 模型、隐私 gate、语义 context/TTL、semantic diff 和 provider 接口；明确不接 HWND 发现、Windows Graphics Capture、PixPin、OCR、VLM 或 Screenpipe（`design.md` Goals/Non-Goals 与 Decision 6）。协议事件 `packages/protocol/src/observation-events.ts:5-8,11-18,109-110` 也是类型契约；注释明确 `screen_context`/`screen_read` 仍关闭。

核心已有模块：

- 窗口 attachment reducer 只允许同一 window identity 更新几何，最小化后不再具备 observation eligibility；不自主切窗（`java/meguri-core/src/main/java/com/meguri/core/observation/window/WindowAttachmentReducer.java:6-52`）。
- `ObservationPolicyEvaluator` 在调用 provider 前检查全局开关、隐私模式、attachment 可用性、应用/标题黑名单和敏感窗口（`.../observation/privacy/ObservationPolicyEvaluator.java:9-42`）；`ScreenObservationPipeline` 先做 policy，再调用 provider，并校验 scope/confidence/TTL，拒绝/失败 fail-closed（`.../observation/provider/ScreenObservationPipeline.java:15-75`）。
- `ScreenContextFreshness` 对过期 context 标成 recent history；semantic diff 限定同 scope（`.../observation/screen/ScreenContextFreshness.java:6-17`、`SemanticDiffCalculator.java:10-...`）。
- 当前唯一 provider 为 `MockScreenObservationProvider`（`.../observation/provider/MockScreenObservationProvider.java:1-...`）；`DesktopObservationRuntimeTest.java` 验证上述纯逻辑，尤其隐私拒绝、敏感窗口、TTL、mock provider 不被拒绝请求触发。测试数据不构成真实桌面成功率或真实隐私识别率。

全局操作审批也已有基础能力（capability approval service/action validator/受控 ReAct 执行器），但这并不表示屏幕定位和目标应用操作已经接入 Windows 执行器。观察管线当前返回语义 context/diff，不触发定位、点击或写操作。需将“观察成功”和“用户批准后的有界动作”分开验收。

## 跨端身份、一致性与事件顺序

权威记忆对象以 `(tenant_id,user_id)` 为查询边界；显式 identity binding 才能把平台身份映射到统一 `user_id`。`services/meguri_core/memory_service/identity.py:32-86` 在没有绑定时生成隔离 opaque ID 并令 `formal_memory_allowed=False`；数据库 identity_bindings 与同一用户可绑定多客户端（schema 简述见 `docs/memory-schema.md:7`）。已有集成用例 `tests/integration/memory/test_native_workflow.py:279` 验证跨 client identity 和 session summary isolation。桌面 Java 仍用受信桌面身份和 allowlist (`docs/native-desktop-memory.md:19,49`)，所以不同入口共享记忆的前提是绑定与授权，不是仅 session 名字相同。

Turn event envelope 有 protocol_version、event_id、session_id、sequence、replay_policy 与 metadata（`java/meguri-core/src/main/java/com/meguri/core/dto/EventEnvelope.java:14-60`）。Postgres turn journal 通过 session 序列表原子递增 `last_sequence`（`.../runtime/PostgresTurnJournal.java:561-571`）；DDL 对 `(session_id,sequence)` 唯一（`.../resources/db/turn-runtime.sql:49-67`），SSE 支持 `after_sequence` 并按 session replay。这个保证是单 session 内事件序号/重放顺序，不是跨 session 的全局因果顺序或 memory replication log。记忆跨端共享依赖查询同一个 PostgreSQL 权威库；它没有通过 turn event stream 分发记忆变更。更新冲突另由 candidate base-version / optimistic merge 防护。

## 研究附件目标和现有 evaluator 的覆盖差距

附件指标包含记忆 150 样本 Recall@5 ≥85%、旧版本误召回 ≤2%、凭据入库 0；GUI 3 类应用/60 场景/定位 ≥85%/任务成功率 ≥75%/未授权敏感操作成功 0；跨端 50 组/已授权记忆一致 ≥99%/同步 P95 ≤3s/串用 0。当前代码只能部分支撑评估：

- `services/meguri_core/memory_service/recovery.py:30-50,132-203` 有固定 recall corpus evaluator，可指定 expected memory IDs/version IDs、query、limit/modes 并算命中率；Recall@5 可通过 `limit=5` 测正例，但不是附件定义的完整评估协议。该 evaluator 默认要求至少一条 EXACT_VECTOR case (`recovery.py:73-76,187-192`)，而当前本机部署 embedding disabled；应提供按真实运行模式执行的文本 corpus，并把该前置做成明确 corpus profile，不能拿 synthetic vector 结果冒充运行时。
- evaluator 的分子只计算 expected IDs/version IDs 命中，**不统计错误/额外命中**，没有旧版误召回率、凭据入库率或分母规则。可给 expected_version_ids 指定正确版本，将旧版本作为 miss，但错误版本率仍未独立报告；也不会检查候选/版本文本中凭据。
- `memory_service/benchmark.py:49-100` 的 exact 性能测试是进程内人工合成单位向量；HNSW 返回 `not_enabled`，注释称不是 PostgreSQL 网络时延。因此不能证明 150 条中文自然语言记忆的召回，也不能替代 P95 同步时间。
- 60 场景定位率、任务成功率和“未授权敏感操作成功为零”目前没有 Windows provider/GUI action integration evaluator；现有 Java observation test 全部是 mock/pure model，测的是策略逻辑而非屏幕定位或真实操作。
- 50 组跨端一致率、P95 和串用率没有统一跨端实验 runner。现有身份绑定、用户/tenant 过滤和 per-session event replay 是安全基础，不等于附件规模的现场一致性测量。

目前 PostgreSQL keyword 召回使用 simple-config FTS 加连续子串兜底 (`repository.py:1199-1237`)；当前配置关闭 embedding (`docs/native-desktop-memory.md:47`)，非连续改写或同义表达通常没有可靠匹配路径。对中文而言，`simple` 不提供中文分词词典；具体影响需在目标 Postgres 版本用 150 条盲测语料量化，当前不能声称某个召回百分比。开启已实现的 embedding/hybrid 路径也必须在目标实际 provider/revision 上评估，不能用合成向量算真实检索效果。

## 最小、独立且可评估的首期建议

先不把情绪作为首期；附件重点是三条运行链路与实测指标。最稳妥的首期代码切入建议为 **完善 memory fixed-corpus evaluator 与 150 条盲测样本的接入，使它能直接按附件口径同时报告 Recall@5、旧版本误召回率、凭据入库数**。这项范围独立于桌面 UI 和真实屏幕权限，不触碰既有 dirty 源码之外需保留的行为，能先回答当前中文关键词检索到底差多少，并防止后续把模拟结果写进论文。优先走现有 `RecoveryRecallCorpus`/`validate_fixed_recall` 扩展，设计 corpus 包含中文连续匹配、非连续复述、同义改写、版本更新后新旧版本冲突、用户/租户隔离、凭据候选拒绝。

验收必须把测量来源分开标记：

1. **真实本地/真实权威库**：目标 PostgreSQL 数据库、真实表数据和实际启用的检索配置，生成 Recall@5 与 P95；不开 embedding 就如实报告 text-only。
2. **模拟/离线**：mock policy/provider 或合成 embedding 仅报告逻辑覆盖、契约行为或算法上界，不能宣称真实观察/数据库指标。
3. **未测**：真实 Windows capture/GUI task success、跨端 50 组 P95 在尚未接 provider/runtime 时写 `not_measured`，不填预计值。

可验收门槛：输入 150 样本输出精确分子/分母、Recall@5；对版本错配记录 independently `stale_version_hits / returned_memory_hits` 并验证 ≤2%；逐条 candidate/create/approved item scan credentials 并验证 count 0；输出 mode/provider/model/revision/database revision/measurement origin。若当前 evaluator 的默认 exact-vector requirement 与 text-only 本地运行不符，应显式区分 text-only corpus 与 vector corpus，不能伪造 exact-vector case。

之后再以已有 observation runtime 为基础单独接本地 Windows provider：先有真实 HWND/window enumeration + freshness + permission UI/audit，再走 OCR/semantic localization 和经 approval 的少量 read-only/low-impact actions。复用现有 policy-first pipeline；实测附件 60 场景前建立应用/权限矩阵和敏感窗禁测集。跨端 50 组与 event/version ordering 应作为独立集成验收。
