# Eidolon-SC 与 Meguri 的能力对照及搭话改进依据

调研日期：2026-10-22（Asia/Shanghai）。参考仓库已存在于 `D:\program\eidolon-sc`，本轮直接读取，不重复安装或部署。参考提交：[`4b8695390591c23acff3fa65b10044dfa26ef8ef`](https://github.com/Sallyn0225/eidolon-sc/tree/4b8695390591c23acff3fa65b10044dfa26ef8ef)。Meguri 的工作区含其他会话的未提交修改，以下按本轮开始时的实际源码评估，不把旧文档的默认值当作当前生产状态。

## 判断

用户记得的前三项确实已有实现：Meguri 有剧情检索、正式长期记忆、文字与语音共用对话发送路径。值得借鉴的是这些能力的产品闭环，不需要为了借鉴而加入多智能体。最直接的缺口是：桌面已有主动通知的策略和交付链路，却尚未看到“空闲时自己先说一句”的内容生产与调度。既有调度器只检查通知队列和用户手工记下的承诺。[M1][M2][M3][M4]

| 方向 | Meguri 已有 | Eidolon-SC 的具体长处 | 适合下一步的改进 |
|---|---|---|---|
| 剧情 / 人设 | reviewed JSONL、构建版本核验、中日文与关系过滤、BM25 / CJK 词元、意图别名、相关性阈值、去重；另有知识库的版本、ACL、混合检索与图检索链路 | 以 episode / card / event 为目录，按角色出场限制回忆；向量与重排；专门回忆完整剧情的工具；重导入复用未变文本的向量 | 先补剧情目录、场景出处与前后段定位，再评估语义召回；保留本地低成本兜底 |
| 长期记忆 | 候选审核、版本替换、冲突、作用域、反馈、软删除 / 恢复 / 导出 / 可选硬删除、pgvector；回复后的记忆工作有独立 job / 重试 | 自动抽取链路轻；用户可看出“谁得知了什么”；新旧记忆替代关系在列表保留；搭话能读取相关记忆 | 保留现有治理；增强召回出处、版本与用户纠错反馈的可见闭环 |
| 文字 / 通话 | 语音识别后调用同一 `conversation.send(text)`；相同会话、RAG、记忆及消息记录；有取消和播放控制 | Pipecat / WebRTC、分段字幕与 TTS、通话插话与打断；文本与通话都调用同一 `engine.turn` | 承认已有共享状态；后续专项升级流式语音和插话，不重写聊天后端 |
| 主动搭话 | 新闻、提醒、承诺跟进；专注 / 活跃话轮 / 正在说话的抑制；冷却、队列、播放凭据、跨窗口防重复 | 单独搭话记录、持久调度、夜间抑制、每日上限、未回应前不重复、生成后竞态核对 | 复用 Director / notice delivery，新增有界空闲搭话生产者；不用伪造用户消息 |

剧情证据：[E1][E2][M1][M5]；记忆证据：[E3][M6][M7][M8]；语音证据：[E4][M2]；搭话证据：[E5][E6][M3][M4]。

## 剧情库与人设：不是缺少 RAG，而是回忆粒度和出处仍可加强

Meguri 的 Java `MockRagProvider` 实际继承 `CanonicalRagRetriever`，这个兼容名称不代表返回伪造内容。它读取经过构建核验的剧情数据，按语言、关系阶段硬过滤，再使用 BM25、CJK 词元和人工审核的意图别名排名，弱相关时返回空结果，按 scene 和文本相似度去重。Python harness 中同名检索器也有构建核验和相关性门限。不能仅凭类名推断“我们还没有真实剧情库”。[M1]

除了 Lore 召回，Meguri 已有独立的通用知识模块和统一 `LORE / MEMORY / KNOWLEDGE / WEB` 检索装配、图查询与超时降级。当前装配使用的 `DeterministicSearchProjector` 明确是 `meguri-feature-hash-v1`、64 维特征哈希。它是稳定的本地向量投影，不能直接等同于 Eidolon 使用的外部语义 embedding + reranker，也不能把已有混合检索链路说成不存在。[M5]

Eidolon 的优势在剧情本身的组织：episode 以来源 ID 保存；有出场角色和卡片 / 活动目录；片段只在角色出场的剧情中搜索；重导入时只为改变的文本生成向量。检索器联合角色资料、世界事实和剧情片段；判断器决定是否需要资料或回忆；另有 `recall-story` 工具让模型查找具体事件，补回一段剧情而不只拿几个短句。[E1][E2]

建议优先补“场景标题 → 来源 → 相邻片段 → 正文”的可追溯召回，以及“你刚才提到那段剧情”一类连续指代评测。是否增配 embedding / reranker 应由评测说明，而不是把接口多当作效果好；本轮没有运行双方同数据的对照实验，不能断言哪边召回质量更高。

## 记忆：Meguri 治理能力更细，Eidolon 的用户解释更直接

Eidolon 在回复后异步抽取用户透露的事实；每项记录用户、来源角色、对话、时间。下一轮开始时短暂等待后台写入，然后召回相关事实。新事实可标记取代旧事实；旧项仍能列出，但不再召回；删除取代者时其前项按替代链恢复。所有角色共享同一用户记忆，异角色事实以转述口吻出现，用户间隔离。无 embedding / reranker 时不继续读写记忆，但仍可列出和删除已有内容。[E3]

Meguri 不是只有一个记忆列表：正式服务有候选审核、版本、状态、scope、过期时间、冲突处理、删除恢复与导出；检索融合语义、关键词、重要度、置信度和新近度，受 token 预算约束。回复后的写入还有独立任务租约、重试与失败终态，不必卡住已完成的回复。[M6][M7][M8]

尚可加强的是“召回后发生了什么”的反馈链。当前 Java `PythonMemoryGateway.recall` 将命中项降为 `canonical_text` 字符串；正式服务的 feedback API 虽保留 memory / version / query / rank 等字段，`record_feedback` 本身主要保存反馈并增加 false-recall 计数，未在该方法直接改变召回权重。应保留版本和出处，供界面解释及精确纠错；不要把训练反馈和记忆纠错混为一套。[M9]

当前用户不需要跨角色交流，因而无需移植共享角色记忆。更有价值的是在一只 Meguri 身上清楚表达“什么时候得知、事实是否更新、这次是否记错”。

## 文字与通话：已有统一对话，差在实时传输与轮次控制

Meguri 的 `ChatDock.vue` 将语音识别文本直接交给 `conversation.send(text)`。`voice-conversation.ts` 以 `recognize → send → waitForSpeech → recognize` 循环控制通话，源码明确是 half-duplex，并有 generation 取消旧循环。它与文字使用同一会话后端，因此不能重复建设另一套“语音记忆”。[M2]

Eidolon 的文字 HTTP 入口与 Pipecat 通话均调用 `ConversationEngine.turn`，再由通话层负责实时识别、分段语音、字幕和插话。源码包含中断后停止旧 TTS、回复分段编号等状态管理。这是语音体验成熟度上的具体差别，不是 Meguri 完全没有通话。[E4]

建议把可打断 TTS、边生成边播、回声 / 端点判断作为独立后续专项，并使用真实麦克风与音箱验收。本轮源码对照不能证明某台设备上的识别率、端到端延迟或双工稳定性。

## 搭话：借鉴规则，复用现有交付链路

Eidolon 的搭话为独立记录，既不是用户回合也不是普通旁注；因此不触发假用户输入的好感判断、限额和记忆抽取。生成使用同一人设、历史、当前时间、最近联系时间和少量相关记忆，不提供工具，也不检索其他资料。[E5][E6]

调度边界十分具体：[E5]

- 后台每分钟检查；仅在最近联系后的 7 天窗口内考虑搭话。
- 同一次联系之后已主动说过而用户尚未回应，就不再来一句。
- 正在文本话轮或通话时跳过；用户关闭、受限、冷却等状态也会跳过。
- 依用户时区，22:00–08:00 不搭话；超出每日限额延至次日。
- 每日默认上限 3 次；不同关系档位使用不同的小时级随机间隔。
- 失败延后，进程重启不补发过期计划；生成完毕存储前再次检查最近联系，拒绝覆盖期间发生的新交流。

这些数字对应“用户隔几小时不来聊天”的产品，而桌面常驻宠物的节奏不同。应采用 Meguri 自己的 `initiative` 档位、明确的开关和专注状态，不必复制相同时间。

Meguri 已有 `CompanionDirector`，其通知类型原为 `news / reminder / commitment_followup`；调度器每 15 秒执行 `flushDeferred` 和承诺检查。`companion-policy` 已覆盖专注、activeTurn、speaking、冷却和队列容量。`deliverCompanionNotice` 已经在真正展示前重评估，利用 playback ledger 去重，然后写入助理消息和按设置播报。缺口是空闲搭话的资格判断与内容生产，并非再建一套通知系统。[M3][M4]

建议本轮先完成一个可测试闭环：闲置达到档位阈值 → 检查专注 / 通话 / 输入 / 隐私等状态 → 生成或选择一句有界开场白 → 独立 notice 交付 → 记录本次已搭话 → 等用户回应再恢复资格。主动文本不要进入假用户回合，不开放工具写操作；会话切换、清历史、隐私撤回或用户开始发言时应取消在途内容。后续再加入基于已审核记忆的自然话题和可衡量的模型生成，避免先做没有验收边界的常驻 agent。

## 联网现场核验

2026-10-22 本轮通过只读 HTTP GET 实测（不读取密钥、不发送用户消息）：

| 请求 | 结果 | 能证明什么 |
|---|---|---|
| GitHub repository API | HTTP 200 | 当前机器能访问 GitHub API |
| raw GitHub `reach_outs.py` | HTTP 200 | 当前机器能取得源文件 |
| Bing 普通查询 `Eidolon SC` | HTTP 200 | 当前机器可取得搜索响应；未据此推断结果解析一定正确 |
| `127.0.0.1:18080/health` | 连接异常 | 探测时 Java Core 未响应；不是“已跑通桌宠联网”的证据 |
| `127.0.0.1:15173/health` | HTTP 404 | 有 HTTP 服务响应，这个路径不是可用健康路由 |
| `127.0.0.1:8000/health` | HTTP 200 | Python harness / memory bridge 在响应 |

8000 的公开健康数据为：`mode=local-mock`、`llm_provider=mock`、`memory_provider=native_pgvector`、`rag_provider=MockRagProvider`、`rag_chunks=174`。这说明 Python 服务的 LLM 当时仍是 mock，记忆 provider 已选 native_pgvector；单一健康响应不能证明正式数据库读写、桌面 Java 对话或搜索端到端成功。[M10]

桌面启动器显式开启 `MEGURI_WEB_SEARCH_ENABLED=true`、Bing 搜索和有界结果 / 超时，存在主模型密钥文件时选择兼容模型；应用基础配置则默认 `local-mock`、LLM mock、web search 关闭。结论是：**当前会话与机器可以联网，桌宠代码也有联网能力和启动配置，但探测时不能声称桌宠的真实 AI 联网问答已经验证通过。**后续需在 Java Core 启动后，用普通实时查询检查检索 trace 与可打开来源。[M11]

## 一手证据索引

### Eidolon-SC（固定提交）

- [E1] [corpus.py:1](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/backend/src/eidolon/corpus.py#L1)：剧情来源、出场角色、向量增量重建、卡片 / 活动目录。
- [E2] [retrieval.py:1](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/backend/src/eidolon/retrieval.py#L1)：按需判断、联合候选、重排、回忆工具与失败退化。
- [E3] [memory.py:1](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/backend/src/eidolon/memory.py#L1)、[memory.py:625](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/backend/src/eidolon/memory.py#L625)：异步写入、来源、替代与删除语义。
- [E4] [app.py:588](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/backend/src/eidolon/app.py#L588)、[call.py:1681](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/backend/src/eidolon/call.py#L1681)、[call.py:328](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/backend/src/eidolon/call.py#L328)：共同引擎与可中断 TTS。
- [E5] [reach_outs.py:1](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/backend/src/eidolon/reach_outs.py#L1)、[config.py:92](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/backend/src/eidolon/config.py#L92)：调度规则、默认上限与启用方式。
- [E6] [engine.py:790](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/backend/src/eidolon/engine.py#L790)、[ADR-0026](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/docs/adr/0026-reach-outs-are-not-turns.md)：搭话上下文和独立记录设计。

### Meguri（本地工作树）

- [M1] [CanonicalRagRetriever.java](../../java/meguri-core/src/main/java/com/meguri/core/rag/CanonicalRagRetriever.java)、[MockRagProvider.java](../../java/meguri-core/src/main/java/com/meguri/core/rag/MockRagProvider.java)、[providers.py:440](../../services/meguri_core/providers.py#L440)。
- [M2] [ChatDock.vue:91](../../apps/desktop-meguri/src/renderer/components/ChatDock.vue#L91)、[voice-conversation.ts](../../apps/desktop-meguri/src/renderer/services/voice-conversation.ts)。
- [M3] [companion-director.ts](../../apps/desktop-meguri/src/renderer/stores/companion-director.ts)、[companion-contract.ts](../../apps/desktop-meguri/src/shared/companion-contract.ts)。
- [M4] [companion-policy.ts](../../apps/desktop-meguri/src/renderer/services/companion-policy.ts)、[companion-notice-delivery.ts](../../apps/desktop-meguri/src/renderer/services/companion-notice-delivery.ts)、[playback-ledger.ts](../../apps/desktop-meguri/src/renderer/services/playback-ledger.ts)。
- [M5] [KnowledgeRetrievalConfiguration.java](../../java/meguri-core/src/main/java/com/meguri/core/retrieval/KnowledgeRetrievalConfiguration.java)、[RetrievalRuntime.java:165](../../java/meguri-core/src/main/java/com/meguri/core/retrieval/RetrievalRuntime.java#L165)、[DeterministicSearchProjector.java:21](../../java/meguri-core/src/main/java/com/meguri/core/knowledge/DeterministicSearchProjector.java#L21)。
- [M6] [memory_service/models.py](../../services/meguri_core/memory_service/models.py)、[memory_service/service.py](../../services/meguri_core/memory_service/service.py)、[native_pgvector.py](../../services/meguri_core/memory_service/native_pgvector.py)。
- [M7] [memory_service/retrieval.py](../../services/meguri_core/memory_service/retrieval.py)：权重、RRF、状态过滤与预算。
- [M8] [PostReplyMemoryJobWorker.java](../../java/meguri-core/src/main/java/com/meguri/core/memory/job/PostReplyMemoryJobWorker.java)。
- [M9] [PythonMemoryGateway.java:49](../../java/meguri-core/src/main/java/com/meguri/core/memory/PythonMemoryGateway.java#L49)、[service.py:573](../../services/meguri_core/memory_service/service.py#L573)。
- [M10] [app.py:80](../../services/meguri_core/app.py#L80)：Python 健康数据字段。
- [M11] [start-meguri-core.ps1:35](../../ops/scripts/start-meguri-core.ps1#L35)、[application.yml](../../java/meguri-core/src/main/resources/application.yml)、[memory-runtime-config.ps1](../../ops/scripts/memory-runtime-config.ps1)。

本文归纳设计和事实，没有复制参考项目代码；参考项目代码许可为 [AGPL-3.0](https://github.com/Sallyn0225/eidolon-sc/blob/4b8695390591c23acff3fa65b10044dfa26ef8ef/LICENSE)。本轮桌宠改动与验收结果由对应 OpenSpec 变更记录，不由这份调研报告代替。
