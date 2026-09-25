# Meguri 20.x 大致需求确认稿

更新时间：2026-07-29

这份文档用于恢复丢失的 AI 会话上下文，并作为产品和技术需求讨论底稿。它描述的是“最终希望做成什么”，不是完成证明，也不是提交清单。

## 一、状态口径

后续讨论和提交统一使用三层口径：

- **代码已实现**：本地能找到完整实现，不只是接口、占位符或 mock。
- **本地已验证**：相关自动化测试在本机通过。
- **生产已证明**：已在真实数据库、真实鉴权、真实客户端和故障场景中验证。

只有三层都满足，才可以宣称对应生产能力完成。当前 20.0-20.7 已达到“代码已实现”和“本地已验证”；真实 PostgreSQL/pgvector、MCP、Remote Agent、Provider、跨端鉴权 E2E 与生产隔离仍缺环境证据，因此只能标记为“本地已实现”，不能标记为“生产已完成”。

## 二、产品目标

Meguri 最终应当是一个跨端共享、但会话严格隔离的角色系统：

```text
AIRI 桌面端 / AstrBot / Website
              |
      统一身份、Turn 与事件协议
              |
       Java Meguri Core
      /       |       \
 Context   Persona   Capability
    |          |          |
 Memory    Relationship  Tool / MCP / Agent
    |
 Lore / Knowledge / Web Retrieval
```

核心体验可以概括为：

1. 同一用户在 AIRI、AstrBot 和 Website 面对的是同一个 Meguri，长期关系和正式记忆一致。
2. 不同用户、机器人、平台账号、客户端和 session 不能串话。
3. 回复正文、表情动作、TTS 和记忆抽取彼此解耦；附属能力失败不能吞掉正文。
4. 断线、重试、重启和重复请求不能重复写记忆、重复执行工具或重复播放一次性表现。
5. 正式记忆和有副作用操作默认收紧权限，所有授权由服务端权威身份决定。
6. 每个 Turn 都能说明用了哪些上下文、检索结果、Persona 版本、工具和降级路径。

## 三、功能需求

### 20.0 统一 Harness 与 Turn Runtime

- AIRI、AstrBot、Website 和同步兼容接口必须复用同一条 Turn 编排链路。
- 对客户端提供创建 Turn、订阅事件、查询快照和取消 Turn 的稳定接口。
- Turn 采用明确阶段和唯一终态，支持作用域幂等键、请求摘要冲突检测和 session sequence。
- Context、Persona、Capability、协议和构建版本在 Turn 开始时冻结，执行期间不能漂移。
- 重连、重试和进程恢复不能重复产生正式记忆、工具调用、TTS 或一次性表现等副作用。
- 三端共享用户级 Persona、Relationship 和正式 Memory，但 session 上下文严格隔离。
- 提供统一 trace，能还原关键输入、裁剪、检索、工具 receipt 和降级决策。

### 20.1 Companion Context

- 消息使用不可变 DAG 保存，分支回答、编辑、压缩和切换 active leaf 不删除原始消息。
- `QUOTE`、`TOPIC_LINK`、`RESUME_FROM` 使用不同领域语义和拓扑，不能只是同一种通用链接换名字。
- 可选择任意已有 leaf，并按 active leaf 重建上下文，不能混入兄弟分支。
- 派生摘要保存来源 revision digest 和状态；来源编辑、删除或换分支后立即变为 `STALE`，重新生成前不得注入模型。
- Context Assembly 生成类型化 bundle 和可持久化 trace，能够精确复现最终 Prompt。
- system prompt、历史消息、摘要、RAG 和工具结果共用一个全局 token budget。
- 每个 Provider 使用对应真实 tokenizer；不得先按字符静默截断，再声称满足 token 限制。
- 可在预算接近阈值时触发持久化后台摘要或预压缩任务。

### 20.2 正式 Memory

- 所有长期记忆写入统一经过“候选 -> 风险判断 -> 审批/合并 -> 版本化持久化”，导入、兼容接口和更新接口不能旁路 candidate。
- L0 禁止内容不得把原文写入 candidate、日志、向量或文件，只保留不可逆指纹和无原文审计原因。
- Memory 记录来源、scope、风险、合并策略、base version、有效期和审计信息。
- PostgreSQL 作为唯一权威源；文件只作为单向镜像或导出，不默认支持双向编辑。
- 并发修改使用乐观锁和不可变版本；冲突产生独立分支，不静默覆盖。
- `CONFLICTED`、过期、删除或未批准的版本不得进入 Context，召回 last stable version。
- 删除使用 tombstone 和投影事件，使数据库、向量和文件最终一致，同时保留最小合规审计。
- `relationship_stage` 等保护字段不能由普通记忆抽取器修改，只能由专门领域服务推进。
- 低风险是否允许自动合并由产品策略决定；高风险默认必须人工明确批准。

### 20.3 分层 Retrieval / RAG

- 统一编排 Lore、Memory、Knowledge Base 和 Web 四个 lane。
- Planner 明确选择 `NONE`、`FAST` 或 `SLOW`；`FAST` 模式不得调用 Web 或开放 Remote Agent。
- 每个 lane 独立超时、降级和记录状态，单通道失败不能阻断其他通道与基础回复。
- 不同算法的原始分数不能直接比较；Vector、Keyword 和 rerank 使用 RRF 或经过校准的融合方式。
- 每条 Retrieval Item 保留来源类型、ID、版本、citation、信任级别、token 数和排名证据。
- Web 内容不能覆盖 Persona Constitution，也不能未经审核直接进入正式 Memory。
- Knowledge Base 使用 `BUILDING`/`ACTIVE` 等版本状态；新版本完整构建成功前不得替换当前 ACTIVE。
- trace 能复现查询改写、过滤、候选、各路排名、融合、裁剪、选中和最终注入过程，但日志不泄漏敏感正文。
- Graph 关系需要回溯到 evidence chunk；Graph 不可用时应确定性降级到 Hybrid Retrieval。

### 20.4 Persona、Relationship 与 Scene

- Persona 由版本化 Constitution、用户 Profile、Relationship、Scene、Temporal、Interaction、显式 override 和客户端能力共同解析。
- 相同输入快照与规则版本必须得到确定的 Effective Persona；每个字段保留值、来源、版本和生效原因。
- Relationship 是用户级服务端权威状态，由 AIRI、AstrBot 和 Website 共享，普通客户端不能在请求体中自报关系阶段。
- Temporal 昼夜、天气、服装和客户端显示状态不能自动改变 `relationship_stage`。
- 服装、窗口和临时表现可按客户端独立；哪些 Scene 跨端共享需由产品确认。
- override 具有优先级、TTL、过期恢复和审计；重启后仍能正确恢复。
- Temporal、Scene、服装和表现切换具备 debounce、cooldown 或 hysteresis，避免边界抖动。
- 检索内容、网页和工具输出不能覆盖 Persona Constitution，并有 Prompt Injection 契约测试。
- Persona 评测至少覆盖身份一致性、关系边界、模式一致性、场景适切性、记忆自然度、承诺一致性和矛盾修复。

### 20.5 Durable Turn

- Turn、事件、幂等记录、session sequence、快照和 outbox 使用 PostgreSQL 持久化。
- Turn 接收与 `turn.started`、阶段状态与阶段事件、终态状态与终态事件具有明确原子事务边界。
- 多实例恢复使用 owner、lease、heartbeat 和 CAS，不能把其他实例仍在执行的 Turn 误判为失败。
- Outbox 具备 dispatcher、claim、ack、retry、死信和幂等消费者闭环。
- SSE 慢消费者使用有界缓冲；事件保留期外的 cursor 返回 `CURSOR_EXPIRED` 和 Snapshot。
- Provider 支持时输出原生正文流；不支持时诚实发送完整 delta，不伪造字符切片流。
- 必须在真实 PostgreSQL 上验证并发、重启、数据库不可用和事务失败恢复。

### 20.6 Capability、MCP 与 Remote Agent

- 能力注册、授权判断和实际执行分离；Skill、Resource、Tool 和 Remote Agent 是不同类型。
- Skill 和 Resource 不能伪装成 Tool 执行；所有 Tool 必须注册并验证输入 schema。
- 每个能力声明版本、effect、风险、审批策略、超时、并发限制和实现来源，并冻结到 Turn manifest。
- 写操作审批绑定 approval ID、规范化输入 hash、审批人、有效期和目标身份，不能只传布尔值。
- Effect Ledger 使用 PostgreSQL 持久化，跨 Turn 幂等，终态单向转换，并支持查询、恢复和必要的补偿语义。
- MCP 需实现真实连接与调用，以及 initialize、能力刷新、`list_changed`、OAuth/授权和 fail-closed。
- MCP 协商需覆盖 `2025-11-25`；Prompt、Resource 和 Tool 保持不同语义，Prompt/Resource 只能显式选择并作为不可信外部内容进入 Context。
- Skills 需要 manifest 校验、签名或可信来源、健康状态和安全热更新入口。
- Remote Agent 需要真实执行器，并受进程、文件、网络、时间、成本和并发预算约束。
- Agent 顺序固定为 Retrieval/Context/Policy、主模型 proposal、服务端重新校验、审批与预算/容量检查、Agent 执行、不可信结果回注、最终 Provider；选择 SLOW 本身不等于审批。
- 公共客户端不得自报 Agent proposal、Capability scope 或审批结果，显式调用只能进入服务端认证入口。
- 策略预算不等于 sandbox；生产 Remote Agent 必须使用 OS 或容器级隔离。

### 20.7 三端协议与接入

#### 公共协议

- 三端统一使用异步 Turn + SSE，不各自维护另一套聊天语义。
- Envelope 包含协议版本、稳定 event ID、session ID、连续 sequence、required 标记、data 和 metadata。
- 按 event ID 去重，按 sequence 检测缺口；未知 optional 事件推进 checkpoint，未知 required 事件明确失败。
- 客户端先发送 Client Hello，协商协议版本、能力和 replay policy。
- 事件明确区分 `STATE`、`ONCE`、`ALWAYS` 等重放策略，避免重连后重复播放一次性表现。
- checkpoint 在分发表现、TTS 或工具结果前持久化；重启后可以从 durable checkpoint 继续。
- cursor 过期时返回结构化 Snapshot，不让客户端无限重试不存在的历史事件。

#### AIRI

- renderer 只访问本机 loopback proxy，不接触长期 Core bearer token。
- 桌面端支持聊天、重连、去重、checkpoint、TTS、表情、服装、动作、气泡和资源打开。
- 正文在 TTS、表情或记忆抽取失败时仍正常显示。
- PNG-first 可作为阶段性交付；是否必须接原生 `@proj-airi/stage-ui-live2d` 由产品确认。
- AIRI 主仓库现有 Codex、聊天、窗口和托盘能力不能因 Meguri 接入而回归。

#### AstrBot

- 插件负责消息拦截、`/meguri` 命令、身份绑定、session 隔离和 Core 转发。
- 使用平台消息稳定 ID 生成幂等键，并通过异步 Turn + SSE 消费事件。
- checkpoint 需要跨插件重启持久化，而不只在单次调用重连期间保存。
- 未绑定平台身份不得获得正式记忆权限；AstrBot 全局管理员不能自动越过 Meguri 权限边界。
- 远程操作默认关闭；启用后要求操作员绑定、任务预览、二次确认、状态查询和取消。

#### Website

- 使用同一协议 reducer、事件兼容规则和身份模型。
- checkpoint 需持久化，并覆盖刷新、断线、重复事件和 cursor 过期恢复。
- Website 不能因为运行在浏览器中而获得比 AIRI/AstrBot 更高的默认权限。

### 安全、鉴权与运维

- 用户、tenant、平台身份、客户端、session、正式记忆权限和远程操作权限由服务端 principal 决定。
- `meguri_user_id`、`platform_actor_id`、`client_instance_id` 与 `session_id` 必须保持不同语义；原始平台 actor 仅在 Adapter 边界转换，Core 只保存不可逆映射和绑定结果。
- 客户端 header 只能表达请求意图，不能自行授予正式记忆或写操作权限。
- loopback 之外强制 HTTPS；token 不进入 renderer、日志、事件正文或前端持久化。
- staging 需要备份恢复、监控告警、日志脱敏、审计、容量和回滚演练。
- 生产验收覆盖过期 token、错误 tenant、伪造身份、重连、重复事件、慢消费者、依赖超时和数据库故障。

## 四、建议交付阶段

### 阶段 A：本地可运行基线

- Java、Python、TypeScript、AstrBot 和 AIRI 相关本地自动化测试通过。
- mock 或受控 Provider 下可完成完整 Turn，三端协议契约一致。
- 已知限制写入文档，不把本地实现宣称为生产完成。

### 阶段 B：持久化与安全预发布

- 真实 PostgreSQL 验证 Turn、Context、Memory 和 Effect Ledger。
- Outbox、lease、多实例恢复、checkpoint 和 cursor snapshot 闭环完成。
- 三端连接同一套 Core/Relay 的真实鉴权 E2E 通过。
- 正式记忆旁路、L0 原文落库、伪造身份和重复副作用等安全用例通过。

### 阶段 C：生产验收

- 真实 LLM、RAG、TTS、Relay 和客户端组合完成 staging smoke test。
- 备份恢复、监控告警、审计脱敏、容量和回滚演练通过。
- 所有有副作用操作可追踪；未授权操作默认失败且不产生外部 effect。

## 五、当前实现快照

- Java 21 Maven 全量：404 tests，0 failures，0 errors，1 skipped。
- Python 全量：376 passed，8 skipped；本次变更文件 Ruff 与 compileall 通过。
- Alembic：单一 head `20260729_0007`，离线 `0001 -> 0007 -> base` 全链升降级通过。
- 根 TypeScript：48/48 passed。
- AIRI Meguri Adapter：31/31 passed，严格 TypeScript 与指定 ESLint 通过。
- Website、AstrBot、AIRI 已通过同一 canonical fixture 验证协议、终态、能力、权限与重放语义。
- AIRI 从仓库根运行 Vitest 仍会因上游配置引用不存在的 `apps/server` 在测试发现前失败；包级完整测试不受影响。
- 本机没有可用于本轮验收的真实 PostgreSQL/pgvector、MCP、Remote Agent 和生产客户端环境，因此没有生产故障与跨端鉴权证明。

当前总体判断：20.0-20.7 本地代码与自动化契约已实现，可以作为本地实现基线提交；真实 PostgreSQL/pgvector、MCP、Remote Agent、Provider、生产授权和真实三端 E2E 待验。

逐章最新实现证据见 `docs/notion-20-implementation-plan-2026-07-28.md`；`docs/notion-20-audit-2026-07-28.md` 仅为实现前历史快照。

## 六、已落地默认值与仍需确认的产品取舍

1. **交付口径**：阶段 A 的本地代码和自动化契约已完成；阶段 B/C 必须等真实环境证据齐备后再标记完成。
2. **跨端状态边界**：代码按用户级 Relationship 和正式 Memory 三端共享、Session 与客户端表现隔离实现；如产品希望 Scene 或服装跨端同步，需要另行定义权威与冲突规则。
3. **正式记忆审批**：当前支持风险分层、审批与低风险策略；仍需确认生产是否允许用户主动开启低风险自动合并。
4. **Memory 权威源**：当前实现以 PostgreSQL 为唯一权威源，向量和文件均为可修复投影。
5. **Retrieval 模式**：当前实现固定为 `NONE`/`FAST`/`SLOW`，FAST 在编排与 Capability Policy 两层禁止 Web 和 Remote Agent。
6. **AIRI 渲染**：PNG-first 是否继续作为阶段方案、何时切换原生 Live2D，仍需结合真实模型包和视觉验收决定。
7. **Remote Agent 隔离**：HTTP 传输、预算、权限、取消和持久状态已本地实现；生产是否采用容器或独立主机隔离仍需部署决策。
8. **远程操作**：当前默认关闭，并要求操作员绑定、预览、二次确认、输入 hash 和持久审计；生产权限矩阵仍需验收。

## 七、后续优先级

- **P0 环境证据与安全**：真实 PostgreSQL/pgvector、MCP、Remote Agent、Provider 和三端鉴权 E2E；同时完成 secret、日志和权限扫描。
- **P1 故障恢复与运维**：多实例、慢消费者、数据库故障、重复投递、断连、备份恢复、告警、灰度与回滚演练。
- **P2 产品体验**：AIRI 原生 Live2D、真实 TTS/动作资产、低风险记忆自动合并开关和跨端 Scene/表现策略。
