# Meguri Module Index

本文档回答两个问题：某项功能应该先看哪些文件，以及修改后应该运行哪些契约/测试。目录职责是导航摘要，代码和契约仍是事实来源；新增模块后要同步更新这里的入口和链路。

## 1. 快速定位矩阵

| 任务 | 第一批文件 | 继续查看 | 最小验证 |
| --- | --- | --- | --- |
| 修改 turn API、请求字段或事件字段 | [Python schemas](../services/meguri_core/schemas.py)、[Java DTO](../java/meguri-core/src/main/java/com/meguri/core/dto/TurnRequest.java)、[protocol DTO](../packages/protocol/src/dto.ts) | [adapter schema](../contracts/adapter-protocol/v1/adapter-protocol.schema.json)、Java/Python/TS 对应 controller 和 fixtures | `pnpm test:ts`、Python pytest、Java DTO/adapter tests |
| 修改 turn 生命周期、SSE 或 replay | [Python app](../services/meguri_core/app.py)、[Python runtime](../services/meguri_core/runtime.py)、[Java RuntimeWebController](../java/meguri-core/src/main/java/com/meguri/core/web/RuntimeWebController.java)、[Java TurnOrchestrator](../java/meguri-core/src/main/java/com/meguri/core/runtime/TurnOrchestrator.java) | [turn events](../packages/protocol/src/turn-events.ts)、[reducer](../packages/protocol/src/turn-reducer.ts)、[desktop stream](../apps/desktop-airi/web/turn-stream.mjs) | Python runtime tests、Java runtime tests、TS protocol/client tests |
| 修改 memory、身份绑定或正式 memory | [Python memory API](../services/meguri_core/memory_api.py)、[memory service contracts](../services/meguri_core/memory_service/contracts.py)、[Python auth](../services/meguri_core/api_auth.py) | [identity API](../services/meguri_core/identity_api.py)、[internal bridge](../services/meguri_core/memory_bridge.py)、[Java PythonMemoryGateway](../java/meguri-core/src/main/java/com/meguri/core/memory/PythonMemoryGateway.java) | `tests/` memory/identity tests、Java memory tests |
| 修改 LLM provider、planner 或 streaming | [Python providers](../services/meguri_core/providers.py)、[Java provider factory](../java/meguri-core/src/main/java/com/meguri/core/llm/LlmProviderFactory.java)、[Java stream](../java/meguri-core/src/main/java/com/meguri/core/llm/UserVisibleReplyStream.java) | [delta aggregator](../java/meguri-core/src/main/java/com/meguri/core/llm/NativeTextDeltaAggregator.java)、[multimodal route](../java/meguri-core/src/main/java/com/meguri/core/llm/MultimodalModelRoute.java)、[performance contract](../contracts/performance/v1/performance-contract.schema.json) | Java LLM tests、Python provider tests、performance fixtures |
| 修改 Agent、ReAct、能力或审批 | [Java agent package](../java/meguri-core/src/main/java/com/meguri/core/agent/)、[ReAct runtime](../java/meguri-core/src/main/java/com/meguri/core/react/LimitedReActRuntime.java)、[capability controller](../java/meguri-core/src/main/java/com/meguri/core/web/CapabilityRuntimeController.java) | [capability facade](../java/meguri-core/src/main/java/com/meguri/core/capability/CapabilityRuntimeFacade.java)、[agent controller](../java/meguri-core/src/main/java/com/meguri/core/web/AgentRuntimeController.java)、相关 OpenSpec | Java agent/capability/react tests |
| 修改检索、RAG、Notion 或知识库 | [Java retrieval package](../java/meguri-core/src/main/java/com/meguri/core/retrieval/)、[knowledge package](../java/meguri-core/src/main/java/com/meguri/core/knowledge/)、[Python RAG API](../services/meguri_core/rag_api.py) | [Python RAG bridge](../services/meguri_core/rag_bridge.py)、[Java RAG gateway](../java/meguri-core/src/main/java/com/meguri/core/rag/PythonRagGateway.java)、[Notion controller](../java/meguri-core/src/main/java/com/meguri/core/knowledge/notion/NotionSyncController.java) | retrieval/knowledge tests、契约 fixtures |
| 修改桌面、文件 artifact 或资源选择 | [desktop web server](../apps/desktop-airi/src/web-server.mjs)、[artifact feed](../apps/desktop-airi/src/artifact-feed.mjs)、[Java resource controller](../java/meguri-core/src/main/java/com/meguri/core/resources/ResourceSearchController.java) | [Everything gateway](../java/meguri-core/src/main/java/com/meguri/core/resource/EverythingResourceSearchGateway.java)、[path policy](../java/meguri-core/src/main/java/com/meguri/core/resource/LocalResourcePathPolicy.java)、[Electron IPC](../apps/desktop-airi/src/electron-main.mjs) | desktop security tests、Java resource/document tests |
| 修改 AIRI、网站或渲染 | [AIRI adapter](../adapters/airi/src/meguri-api-adapter.ts)、[website session](../adapters/website/src/website-session.ts)、[renderer contracts](../packages/renderer-contracts/src/index.ts) | [AIRI desktop runtime](../adapters/airi/src/desktop-runtime.ts)、[client SDK](../packages/client-sdk/src/meguri-api-client.ts)、[desktop page](../apps/desktop-airi/web/index.html) | `tests-ts/airi-adapter.test.ts`、`tests-ts/website-adapter.test.ts`、renderer tests |
| 修改 AstrBot、命令或每日报告 | [AstrBot entrypoint](../adapters/astrbot/astrbot_plugin_meguri_gateway/main.py)、[gateway](../adapters/astrbot/astrbot_plugin_meguri_gateway/gateway.py) | [commands](../adapters/astrbot/astrbot_plugin_meguri_gateway/commands.py)、[identity](../adapters/astrbot/astrbot_plugin_meguri_gateway/identity.py)、[daily reports](../adapters/astrbot/astrbot_plugin_meguri_gateway/daily_reports.py)、[card renderer](../adapters/astrbot/astrbot_plugin_meguri_gateway/bilibili_daily_card.py) | AstrBot Python tests、plugin package test、adapter contract |
| 修改天气、账单、Bilibili 或日报 | [Java daily controllers](../java/meguri-core/src/main/java/com/meguri/core/daily/)、[weather](../java/meguri-core/src/main/java/com/meguri/core/weather/)、[Bilibili](../java/meguri-core/src/main/java/com/meguri/core/bilibili/) | [Python report generator](../tools/generate_bilibili_browser_report.py)、[publisher](../tools/publish_daily_report.py)、[ops runner](../ops/scripts/run-bilibili-daily.ps1) | domain tests、`tests/test_bilibili_daily_card.py`、tool tests |
| 修改本地 TTS | [TTS adapter](../local-services/tts-adapter/src/index.ts)、[TTS runtime](../local-services/tts-runtime/server.py) | [TTS README](../local-services/tts-runtime/README.md)、[start script](../ops/scripts/start_local_tts.ps1) | `tests-ts/tts-adapter.test.ts` |
| 修改部署、备份、发布或 staging | [ops README](../ops/README.md)、[runbooks](../ops/runbooks/) | `ops/scripts/check_*`、`deploy_staging.py`、`rollback_staging.py` | 先使用只读 gate/preview；不要把部署当作单元测试 |
| 修改训练、数据集或评估 | [training README](../training/llm/README.md)、[training scripts](../training/llm/scripts/) | [registry](../training/llm/registry/)、[eval](../training/llm/eval/) | 对应 `tests/test_llm_*.py`，避免提交模型产物 |

## 2. Python Core（端口 8000）

Python Core 是本地 FastAPI 运行时和现有 memory/RAG bridge 组合点。入口是 [services/meguri_core/app.py](../services/meguri_core/app.py)，默认使用 mock provider 和进程内实现；配置真实 provider 或 native memory 时必须经过认证和显式环境变量。

### 2.1 组合层和 turn 生命周期

| 文件 | 功能 | 连接到 |
| --- | --- | --- |
| `services/meguri_core/app.py` | 创建 FastAPI app、CORS、健康检查、同步/异步 turn、SSE、取消、runtime override，并挂载各 router | `runtime.py`、`memory_api.py`、`identity_api.py`、`memory_bridge.py`、`rag_bridge.py` |
| `services/meguri_core/runtime.py` | `TurnOrchestrator`、`TurnRecord`、`RuntimeStateMachine`、表达式解析、mock delta 分片和 turn 事件推进 | `providers.py`、`memory.py`、`schemas.py` |
| `services/meguri_core/schemas.py` | Python HTTP/domain DTO：`TurnRequest`、`EventEnvelope`、`LlmResponse`、`ChatResponse`、memory/runtime 状态 | `app.py`、测试 fixtures、Java/TS wire binding |
| `services/meguri_core/config.py` | build id、数据根目录和环境路径解析 | `app.py`、RAG、部署 readiness |
| `services/meguri_core/deployment.py` | provider/database/readiness 评估和构建/adapter revision 校验 | `/health/ready`、staging smoke |
| `services/meguri_core/providers.py` | `LlmProvider`/`RagProvider` 协议、mock provider、OpenAI-compatible provider、环境工厂 | `runtime.py` |
| `services/meguri_core/secrets.py` | 只从仓库外文件读取 secret，并拒绝不合规配置 | provider、bridge、部署脚本 |

### 2.2 HTTP 路由

| 路由 | 处理文件 | 作用 |
| --- | --- | --- |
| `GET /health`、`/health/live`、`/health/ready` | `app.py` | liveness/readiness、build/provider/memory/RAG 状态 |
| `POST /v1/chat/respond` | `app.py` | 同步执行一个 turn |
| `POST /v1/turns`、`GET /v1/turns/{id}` | `app.py` | 创建异步 turn、查询状态 |
| `GET /v1/sessions/{id}/events` | `app.py` | SSE 事件流，支持 `after_sequence` 和 `Last-Event-ID` replay |
| `POST /v1/turns/{id}/cancel` | `app.py` | 请求取消 turn |
| `GET/POST/DELETE /v1/runtime/*` | `app.py` | 读取状态、设置/清除 runtime override |
| legacy `/v1/memories*` | `app.py` | fake memory 下的本地兼容接口；native provider 时拒绝旁路修改 |
| `/v1/memory/*`、`/v1/memories/*` | [memory_api.py](../services/meguri_core/memory_api.py) | authenticated candidate review/search/export/restore/delete |
| `/v1/identity-bindings/*` | [identity_api.py](../services/meguri_core/identity_api.py) | memory identity binding 的查看、创建、删除 |
| `/internal/memory/*` | [memory_bridge.py](../services/meguri_core/memory_bridge.py) | Java/内部 memory search、extract、write、session summary bridge |
| `/internal/rag/search` | [rag_bridge.py](../services/meguri_core/rag_bridge.py) | Java/内部 RAG 查询 bridge |
| `GET /metrics` | `app.py` | memory metrics 文本输出 |

### 2.3 Memory service 子域

`services/meguri_core/memory_service/` 是正式 memory contract 的实现层，不要把它和 `memory.py` 的 fake/local convenience provider 混为一谈。

- [contracts.py](../services/meguri_core/memory_service/contracts.py)：provider protocol、错误和授权边界。
- [service.py](../services/meguri_core/memory_service/service.py)：candidate、review、search、version、feedback、export/delete 的 service facade。
- [repository.py](../services/meguri_core/memory_service/repository.py)、[orm.py](../services/meguri_core/memory_service/orm.py)、[database.py](../services/meguri_core/memory_service/database.py)：PostgreSQL/SQLAlchemy 持久化。
- [identity.py](../services/meguri_core/memory_service/identity.py)：tenant/user/client/session identity resolve。
- [review_policy.py](../services/meguri_core/memory_service/review_policy.py)、[conflict_resolver.py](../services/meguri_core/memory_service/conflict_resolver.py)、[merge.py](../services/meguri_core/memory_service/merge.py)：审核、冲突、版本合并。
- [retrieval.py](../services/meguri_core/memory_service/retrieval.py)、[rerank.py](../services/meguri_core/memory_service/rerank.py)、[embedding.py](../services/meguri_core/memory_service/embedding.py)：检索、rerank、embedding worker/provider。
- [file_mirror.py](../services/meguri_core/memory_service/file_mirror.py)、[recovery.py](../services/meguri_core/memory_service/recovery.py)、[export.py](../services/meguri_core/memory_service/export.py)：文件投影、恢复校验和脱敏导出。
- [metrics.py](../services/meguri_core/memory_service/metrics.py)、[release.py](../services/meguri_core/memory_service/release.py)：指标和 release/build boundary。

## 3. Java Core（JDK 21，端口 18080）

Java 运行时入口是 [MeguriCoreApplication.java](../java/meguri-core/src/main/java/com/meguri/core/MeguriCoreApplication.java)，配置组合在 [MeguriRuntimeConfiguration.java](../java/meguri-core/src/main/java/com/meguri/core/MeguriRuntimeConfiguration.java)。默认 profile 是 `local-mock`；`/v1/hello` 和异步 turn/SSE 是 Java adapter 的主要接入面。

### 3.1 Web/API 入口

| Controller | 路由/功能 |
| --- | --- |
| [RuntimeWebController.java](../java/meguri-core/src/main/java/com/meguri/core/web/RuntimeWebController.java) | `/v1/hello`、`/v1/clients:hello`、capabilities、health、`/v1/chat/respond`、`/v1/turns`、status/cancel、SSE、snapshot、runtime state/override |
| [AgentRuntimeController.java](../java/meguri-core/src/main/java/com/meguri/core/web/AgentRuntimeController.java) | `/v1/agent/tasks` 的创建、状态、callback、cancel |
| [CapabilityRuntimeController.java](../java/meguri-core/src/main/java/com/meguri/core/web/CapabilityRuntimeController.java) | `/internal/v1/capabilities`、MCP source、enable/disable/drain/health、approval、audit |
| [CapabilityApprovalController.java](../java/meguri-core/src/main/java/com/meguri/core/web/CapabilityApprovalController.java) | `/v1/approvals/{approvalId}:resolve` |
| [InputController.java](../java/meguri-core/src/main/java/com/meguri/core/input/InputController.java) | `/v1/input/resolve` 的 `#`/`~`/`@` 前缀确定性路由 |
| [ResourceSearchController.java](../java/meguri-core/src/main/java/com/meguri/core/resources/ResourceSearchController.java) | `/v1/resources/search` 的 Everything metadata picker |
| [DocumentEditController.java](../java/meguri-core/src/main/java/com/meguri/core/document/DocumentEditController.java) | `/v1/documents/edits/pending` 和 approved edit apply |
| [DailyReportController.java](../java/meguri-core/src/main/java/com/meguri/core/daily/DailyReportController.java) | `/v1/daily/reports` upload/latest/markdown |
| [BilibiliBrowserReportController.java](../java/meguri-core/src/main/java/com/meguri/core/bilibili/BilibiliBrowserReportController.java) | Bilibili briefing/report metadata |
| [WeatherController.java](../java/meguri-core/src/main/java/com/meguri/core/weather/WeatherController.java) | `/v1/daily/weather` location/briefing/refresh/notice |
| [SleepMemoryController.java](../java/meguri-core/src/main/java/com/meguri/core/memory/SleepMemoryController.java) | `/v1/memory/sleep-consolidation` status/run |
| [PromptCacheMetricsController.java](../java/meguri-core/src/main/java/com/meguri/core/metrics/PromptCacheMetricsController.java) | `/v1/runtime/metrics/prompt-cache` |
| [TrainingFeedbackController.java](../java/meguri-core/src/main/java/com/meguri/core/training/TrainingFeedbackController.java) | `/v1/training/feedback` |
| [ConversationBoundaryController.java](../java/meguri-core/src/main/java/com/meguri/core/conversation/ConversationBoundaryController.java) | conversation boundary decision |
| [NotionSyncController.java](../java/meguri-core/src/main/java/com/meguri/core/knowledge/notion/NotionSyncController.java) | internal Notion sync |

### 3.2 Domain package map

| Package | 主要功能 | 代表入口/边界 |
| --- | --- | --- |
| `adapter` | client handshake、protocol version/capability、identity binding、replay policy | `ClientHandshakeService`, `AdapterTurnCreateRequest`, `ClientBindingRepository` |
| `runtime` | turn journal、orchestrator、canonical pipeline、session context、outbox、status/state | `TurnOrchestrator`, `CanonicalTurnPipeline`, `TurnJournal`, `SessionContextStore` |
| `harness` | 运行时 control plane、turn snapshot/replay、capability/persona/retrieval 的组合接口 | `HarnessControlPlane`, `TurnRuntime`, `SessionReplayWindow` |
| `execution` | `FAST/THINK/AGENT` 决策、绝对 deadline、预算和 server clipping | `DeterministicExecutionModeResolver`, `ExecutionBudgetPolicy` |
| `llm` | mock/OpenAI-compatible provider、planner route、tokenizer、multimodal、delta stream | `LlmProviderFactory`, `LangChain4jLlmProvider`, `AgentPlannerRoute`, `UserVisibleReplyStream` |
| `persona` | persona/profile/relationship/scene/runtime state、presentation intent、prompt policy | `PersonaRuntimeFacade`, `EffectivePersonaState`, `PersonaStateReducer` |
| `context` | conversation context、summary、topic/branch/reference、precompression、token budget | `CompanionContextRuntime`, `ContextAssembler`, `ContextRehydrationService` |
| `retrieval` | retrieval planning、lane/provider、knowledge/memory/web fusion、ACL、citation、trace | `UnifiedRetrievalFacade`, `KnowledgeTurnRetrievalRuntime`, `BundleAssembler`, `RetrievalGate` |
| `knowledge` | Notion/source registry、ingestion、document/chunk/version、ACL、projection、Postgres repository | `KnowledgeIngestionService`, `KnowledgeRepository`, `NotionSyncCoordinator` |
| `rag` | canonical RAG provider 和 Python RAG gateway | `PythonRagGateway`, `CanonicalRagRetriever`, `LangChain4jRagProvider` |
| `memory` | Python memory gateway、sleep consolidation、post-reply memory job/outbox | `PythonMemoryGateway`, `SleepMemoryConsolidationService`, `PostReplyMemoryJobWorker` |
| `capability` | capability catalog/registry、MCP source、policy、approval、audit、effect/operation persistence | `CapabilityRuntimeFacade`, `DefaultCapabilityPolicy`, `McpSourceManager` |
| `agent` | AgentTask 生命周期、skill/step dispatch、remote agent、durable recovery、execution domain | `AgentRuntime`, `AgentRuntimeAssembly`, `HttpRemoteAgentGateway` |
| `react` | AGENT-only有限 ReAct loop、action validation、observation normalization、termination、trace | `LimitedReActRuntime`, `ActionProposalValidator`, `CapabilityRuntimeReactActionExecutor` |
| `resource` + `resources` | Everything search、路径 allow-list、metadata candidate、prompt context、resource API | `EverythingResourceSearchGateway`, `LocalResourcePathPolicy`, `ResourceSearchController` |
| `document` + `artifact` | approved document edit proposal/preview/apply、DOCX codec、model-generated artifact resolution | `DocumentEditService`, `DocxDocumentCodec`, `ModelGeneratedArtifactResolver` |
| `websearch` | explicit web-search policy、safe URL、Bing/DuckDuckGo gateway、content extraction | `WebSearchPolicy`, `SafeWebUrlPolicy`, `WebRetrievalProvider` |
| `weather` + `bilibili` + `billing` + `daily` | 天气提醒、Bilibili metadata digest/selected-summary boundary、账单 briefing、日报 receipt/store/upload | 各 package 的 `*Controller` 和 `*Service` |
| `observability` + `metrics` | TTFT trace、missing reasons、统计、prompt-cache metrics、publisher | `TurnLatencyTraceRecorder`, `PromptCacheMetricsService` |
| `security` + `lifecycle` | Core identity verification、background worker 配置和 resilient polling | `CoreIdentityVerifier`, `SafePollingLifecycle` |
| `training` | feedback DTO、controller、service | `TrainingFeedbackController`, `TrainingFeedbackService` |
| `dto` | Java wire/domain DTO 和 stable enum values | `TurnRequest`, `EventEnvelope`, `LlmResponse`, `WireValues` |

## 4. TypeScript packages and platform edges

| 模块 | 入口 | 功能和连接 |
| --- | --- | --- |
| `packages/protocol` | [src/index.ts](../packages/protocol/src/index.ts) | 聚合 DTO、schema validation、capability negotiation、SSE parser、event replay policy、turn reducer；所有 adapter 的共享 wire vocabulary |
| `packages/client-sdk` | [meguri-api-client.ts](../packages/client-sdk/src/meguri-api-client.ts) | Core URL allow-list、hello、create/get/cancel turn、SSE/reconnect、cursor/snapshot recovery；被 website/AIRI/桌面边界复用 |
| `packages/renderer-contracts` | [src/index.ts](../packages/renderer-contracts/src/index.ts) | `CharacterRenderer`、`PngRenderer`、expression/outfit/motion/speech contract；不实现 AIRI Live2D |
| `adapters/website` | [website-session.ts](../adapters/website/src/website-session.ts) | 注入 host-bound user identity、持久化 session checkpoint、浏览器 SSE/reconnect/cancel；不拥有 persona/memory policy |
| `adapters/airi` | [meguri-api-adapter.ts](../adapters/airi/src/meguri-api-adapter.ts)、[desktop-runtime.ts](../adapters/airi/src/desktop-runtime.ts) | AIRI hello/identity、turn create/SSE/replay/cancel、ONCE checkpoint、renderer/TTS/notification side-effect boundary |
| `apps/website-client` | [demo.ts](../apps/website-client/src/demo.ts) | website adapter 的最小演示，不是新的 Core 实现 |
| `apps/desktop-airi` | [web-server.mjs](../apps/desktop-airi/src/web-server.mjs)、[electron-main.mjs](../apps/desktop-airi/src/electron-main.mjs) | 本地 HTTP/asset/report server、`/core/*` proxy、artifact feed、Electron overlay、受限 IPC/文件打开；[web/turn-stream.mjs](../apps/desktop-airi/web/turn-stream.mjs) 负责桌面 turn stream/recovery |
| `local-services/tts-adapter` | [src/index.ts](../local-services/tts-adapter/src/index.ts) | `LocalTtsAdapter` 和 deterministic mock TTS/cache key |
| `local-services/tts-runtime` | [server.py](../local-services/tts-runtime/server.py) | loopback `127.0.0.1:9880` GPT-SoVITS bridge；只允许桌面/AIRI 本地调用 |

### 4.1 Desktop HTTP surface

- `/core/*`：代理到配置的 Core；多模态和 document/resource 请求有额外 body/path policy。
- `/artifacts/recent`：只返回 `reports/`、`output/` 下允许扩展名的 metadata，不返回文件内容。
- `/assets/*`：从本地 asset root 提供静态资源。
- `/reports/*`、`/output/*`：提供 allow-list 后的被引用 artifact。
- `/` 和其他静态路径：服务 `apps/desktop-airi/web/` 页面。
- Electron `preload.cjs`/`electron-main.mjs` 只暴露受限 artifact/file operation；普通浏览器不获得 Electron 权限。

## 5. AstrBot and other Python adapters

### 5.1 AstrBot gateway

| 文件 | 功能 |
| --- | --- |
| [main.py](../adapters/astrbot/astrbot_plugin_meguri_gateway/main.py) | AstrBot `Star` entrypoint、message intercept、chat/report/relay command dispatch、Core event delivery |
| [gateway.py](../adapters/astrbot/astrbot_plugin_meguri_gateway/gateway.py) | Core hello/turn/SSE/snapshot/reconnect/cancel client boundary |
| [client.py](../adapters/astrbot/astrbot_plugin_meguri_gateway/client.py) | HTTP/JSON client 和 token/header handling |
| [commands.py](../adapters/astrbot/astrbot_plugin_meguri_gateway/commands.py) | `/meguri chat/status/mode/outfit/relation/reset/devices/run/confirm/task/cancel` parsing |
| [identity.py](../adapters/astrbot/astrbot_plugin_meguri_gateway/identity.py) | platform/bot/sender HMAC identity binding |
| [dedupe.py](../adapters/astrbot/astrbot_plugin_meguri_gateway/dedupe.py) | platform message id deduplication |
| [turn_checkpoints.py](../adapters/astrbot/astrbot_plugin_meguri_gateway/turn_checkpoints.py) | selected protocol/capability/sequence checkpoint before ONCE side effects |
| [relay.py](../adapters/astrbot/astrbot_plugin_meguri_gateway/relay.py) | remote device/task preview-confirm-status-cancel; Relay owns approval/task state |
| [daily_reports.py](../adapters/astrbot/astrbot_plugin_meguri_gateway/daily_reports.py) | Core daily report polling, deduplicated delivery and text/image fallback |
| [bilibili_daily_card.py](../adapters/astrbot/astrbot_plugin_meguri_gateway/bilibili_daily_card.py) | bounded Pillow card rendering from validated `visual_payload` |
| [package_plugin.py](../adapters/astrbot/package_plugin.py) | standalone plugin archive builder; does not include local tokens/config |

`adapters/memoryos/` 是旧/外部 MemoryOS integration boundary，不能作为默认 memory authority；先读其 [README](../adapters/memoryos/README.md) 和当前 Python memory provider selection。

## 6. Contracts, docs, operations, and training

| 路径 | 作用 | 入口 |
| --- | --- | --- |
| `contracts/adapter-protocol/v1/` | adapter hello、identity、event、replay、capability canonical schema/fixtures | [schema](../contracts/adapter-protocol/v1/adapter-protocol.schema.json)、`scripts/run_adapter_contract_fixtures.py` |
| `contracts/performance/v1/` | execution mode、TTFT trace、delta、limited ReAct performance contract/fixtures | [schema](../contracts/performance/v1/performance-contract.schema.json) |
| `docs/adr/` | 架构决策记录 | [ADR-009](../docs/adr/009-performance-contract-v1.md) |
| `docs/contracts/` | 面向 agent/开发者的契约说明 | [performance contract](../docs/contracts/performance-contract-v1.md) |
| `docs/evidence/`、`docs/templates/` | benchmark evidence 和对比报告模板 | 只读证据，不把数字当成 rollout 目标 |
| `scripts/` | dataset/RAG、contract、memory、TTFT benchmark 和本地运行脚本 | [scripts list](../scripts/) |
| `tools/` | Bilibili/browser report、daily billing、publisher 等工具 | [report generator](../tools/generate_bilibili_browser_report.py) |
| `ops/runbooks/` | staging deploy/rollback、backup/restore、production approval | [ops README](../ops/README.md) |
| `ops/scripts/` | local start、read-only gates、release/deploy/task scheduler | 先区分 `check_*` 和 deploy/install/rollback |
| `training/llm/` | dataset builder、training/resume/inference、eval、registry、gateway | [training README](../training/llm/README.md) |

## 7. Link chains by feature

### Turn and event chain

`adapter` → `packages/client-sdk`/`packages/protocol` → Python `app.py` or Java `RuntimeWebController` → `TurnOrchestrator` → persona/context/retrieval/capability → `LlmProvider` → `TurnJournal`/SSE → client reducer/checkpoint → platform renderer.

### Memory chain

`TurnOrchestrator` → Python `memory.py` or Java `MemoryGateway` → authenticated Python `/internal/memory/*` / `/v1/memory/*` → memory service policy/repository/embedding/rerank → candidate/review/versioned record. Java and adapters must not bypass the Python authority boundary for formal memory.

### Retrieval/knowledge chain

`TurnOrchestrator` → Java `UnifiedRetrievalFacade`/`KnowledgeTurnRetrievalRuntime` → knowledge repository/Notion source, Python RAG gateway, memory adapter, web adapter → ACL/trust/citation/trace → `RetrievalBundle` → prompt/provider. A retrieval cache or read model is not authoritative.

### Agent/capability chain

server execution-mode decision → Java `AgentRuntime` or `LimitedReActRuntime` → `CapabilityRuntimeFacade`/policy/snapshot/approval/idempotency → capability or remote-agent gateway → untrusted observation → bounded next decision → durable agent/turn event. A client or skill cannot self-grant AGENT permission.

### Desktop artifact chain

Core/tool/report writes under `reports/` or `output/` → desktop `/artifacts/recent` metadata feed → web chat log/bubble → Electron IPC or browser artifact URL → Windows default application. Contents and executable/script extensions are not exposed through the feed.
