# Meguri 改进顺序与 TimePrism 修复验收

日期：2026-10-30 UTC。主树起点 `1d5b00dc`；站点只读核对版本 `ec29b37c`。本轮无生产 Todo 请求、无外发提醒、无服务重启、无 UAC 重试、无新凭据、无 GitHub 推送。

## 按执行顺序落实六项改进

| 顺序 | 改进动作 | 已有实现直接复用 | 本轮状态 / 剩余条件 |
|---|---|---|---|
| 1 | 让已修代码与实际运行版本一致，按小批隔离对话验收 | 既有构建、已验证回滚 JAR、运行制品哈希、17 个串行真实用例脚本 | 组合代码已在主树；加载窗口因此前 UAC 取消仍待确认，未重发 |
| 2 | 搜索保留真实查询/域名/RFC，补真正正文读取和无结果诚实反馈 | 已合入检索与格式约束；独立安全 web_fetch 候选 | `a2db597` 已关闭长链接挤占正文问题；917 通过/5 跳过是候选独立测试，主树尚未导入，公网地址被拦保持停止 |
| 3 | Skills/MCP 展示来源、依赖、可用/未启用/被阻止/执行失败的不同状态 | 现有 discovery/inspect/validator、冻结 capability、审批/地址检查 | 独立作者负责候选；MCP `d136576` 已复审合理。DDG 许可证与 web_fetch 依赖不能用“兼容”标签代替实际执行，不下载未知代码 |
| 4 | TimePrism 查询与派发分离，统一规则与日期实例语义 | 多星期、Mon–Fri、提前量、规则时区、readback、创建 journal、owner/expectedAccount | 本轮实现下述桌面修复；站点纯数据库查询和 AstrBot 周期扩展有具体设计，仍未实施 |
| 5 | 人格/原作/会话上下文/长期记忆/训练分别验收 | speechStyle→SYSTEM、LORE→不可信 canon-examples、权限门控、隔离假记忆测试 | 不增强调情/昵称，不开正式记忆训练；新版实际模型与隔离持久化尚未验收 |
| 6 | B站内容理解按字幕优先、授权音频转写、关键帧辅助补齐 | 现有报告与媒体上下文边界 | 独立专用任务负责；本轮未下载视频、音频或字幕，也未访问账户资料 |

## 已修改的桌面行为

1. `shizuki-reminders.mjs`：`useSnapshot` 只接收缓存、淘汰旧在途同步，不立即 scan、不写防重回执、不调用派发回调。原先已启用的 30 秒调度、75 秒同步以及已确认 mutation 仍可扫描。列表调用不会自己触发提醒；这不代表已启用的调度器永久停止。
2. 同文件：附带 `recurringRule.enabled=false` 或规则不可用的周期实例不参与提醒计算。完成状态、规则时区、提前量和逻辑实例去重沿用原实现。
3. `shizuki-timeprism.mjs`：接入显式 `startAt/endAt`，按规则时区检查真实日期与 DST 缺口，分钟规范化到秒，拒绝倒置边界。未提供值保留已有值；新建空开始默认规则时区当天零点，显式空结束清除结束限制。
4. `desktop-contract.ts` / `RecurringTodoEditor.vue`：起止时间输入与明确时区说明接入原确认保存流程，保留既有秒精度、请求身份和账号核对。没有修改用户正在开发的 ChatDock、样式、GalgameStage 或时间戳组件。

## 红绿证据与命令

新增 `shizuki-query-boundary.test.ts` 10 项：原代码实际运行 **0 通过/10 失败**；修复后全部通过。最初沙箱 Vite 临时缓存 rename 报 EPERM、0 用例，记录为环境失败，不计入红灯。正常隔离测试进程仅使用假 HTTP、虚构账号、临时时钟与独立临时文件。

在 `apps/desktop-meguri`：

```powershell
pnpm exec vitest run src/main/shizuki-query-boundary.test.ts --reporter=json --outputFile=../../reports/timeprism-parity-20261030/red-executed.json
pnpm exec vitest run src/main/shizuki-query-boundary.test.ts src/main/shizuki-recurring.test.ts src/main/shizuki-reminders.test.ts src/main/shizuki-timeprism.test.ts src/main/shizuki-write-journal.test.ts --reporter=json --outputFile=../../reports/timeprism-parity-20261030/targeted.json
pnpm exec vitest run --reporter=json --outputFile=../../reports/timeprism-parity-20261030/desktop-full.json
pnpm run typecheck
pnpm run build
```

首条是修改前红灯命令，当前代码重跑应为绿。专项 **57/57**，桌面全量 **534/534**，均零失败/跳过。类型检查先发现新测试 fetch 参数类型过窄，改为 `string | URL | Request` 后通过；只改类型，无运行时变化。构建通过，保留大 chunk 警告。OpenSpec strict、`git diff --check` 通过。原有用户文件 15 项哈希一致。

报告原始证据在 `reports/timeprism-parity-20261030/`；未上传原始日志。Java、真实模型、生产双端 UI 不因桌面单元通过而视为通过。

## 已证实的后续缺口与最小设计

| 项目 | 代码证据 | 修改与兼容策略 | 验收 |
|---|---|---|---|
| 服务端纯查询 | 站点 `LightAppServiceImpl:907–910` 的 GET 发布 TodoListRequestedEvent 并 materialize；可插入实例、处理 SHIFT | 站点另增只读快照入口，保证不发布写相关事件/不生成实例；实例同步改为明确幂等命令。旧 GET 保持兼容，新桌面不能向旧接口加无效参数后声称纯读 | mock mapper/event publisher：纯读写次数 0；同步重复不重复实例；旧客户端行为不变 |
| 规则暂停 | 站点提醒 Host `:224` 跳过暂停，桌面旧算法未检查 | 已修桌面附带暂停规则时的抑制；真实跨端竞态另验 | 本轮红→绿；后续站点暂停→桌面回读→零派发，恢复后仅正确实例可触发 |
| 日期边界预览 | 站点 `recurringTodoState.js:62–65` 只比较日期；服务端和桌面检查完整 LocalDateTime | 站点后续补时间边界比较，含等号。不得改服务器规则语义 | 同日 20:00 开始/18:00 计划应排除；次日应包含；等时应包含 |
| 一次性时区 | 站点 Host `:69–74` 直接 Date.parse 无偏移时间；桌面一次性按上海解释 | 后续明确账号/任务时区元数据与旧数据默认；现有上海一次性 DTO 继续保留，不能静默按浏览器时区迁移历史任务 | 主机 UTC/上海/纽约下同一明确时区触发点一致；历史无元数据策略单列 |
| 更新请求不确定 | 桌面 `saveRecurringRule` 仅为创建写 journal；编辑 PUT 无原请求持久回读 | 后续为更新记录目标 ruleId、原 payload 指纹/版本。丢响应先核对原 payload；他端已修改时返回冲突，禁止盲目覆盖。站点版本/CAS合同需同步设计 | PUT 已提交后超时，重试 PUT 次数不增加；另一端修改被保留；同 ID 改内容冲突 |
| AstrBot 周期/提前量 | `timeprism_todo_creation.py:81–84` 拒绝重复和非上海表达；Core CreateRequest 仅3字段，writer 提前量固定0 | 复用 owner 私聊、草稿编辑/取消、明确 confirm、writer journal。另加周期 DTO/endpoint，包含 weekdays/time/timeZoneId/advance/start/end；一次性接口默认不变 | confirm 前写0；多星期/提前10分钟回读一致；取消/非owner/群聊拒绝；重复confirm不增写 |

这些站点改动本轮未写入另一个仓库，不将设计当实现。网页提醒仍要求页面可见/打开，桌面提醒要求应用运行；现有防重按设备实现，不能承诺跨设备只提醒一次。

## 最小真实双端验收（待加载窗口）

使用已有授权账号，明确标记虚构临时规则；日期按执行当天的规则时区设置，并当日结束。不得用生产列表 GET 充当无副作用探针。

1. 站点建有界临时规则→桌面回读 weekday、Cron、zone、advance、start/end 完全一致。
2. 桌面修改多星期与提前量→站点回读，随后只完成指定日期实例，其他日期和规则不变。
3. 按已启用的调度时钟验证到点与提前提醒、查询本身不派发；断线后在12分钟窗口内恢复最多一次。
4. 保存丢响应保持原 requestId；核对已知结果；账号切换/规则读取失败停止写入和推测。
5. 最后明确暂停测试规则并回读确认，记录临时数据 ID 和状态；不删除原用户任务，不创建真实日报。

真实窗口输入、点击保存、任务渲染与用户可见回执独立列为 UI 验收；不能用本轮 API 夹具或构建成功代替。

## web_fetch 复审收尾

修订 `a2db59735a0a38de20a891a8e46c47d49191e0d8` 将来源/链接/正文分配独立额度，URL 完整输出或明确省略。已核对源码及归档：原914项状态不变、新8项通过；共917通过/5跳过。此前“长链接挤掉正文”审查问题关闭。本轮未导入 `d136576` / `db75cd5` / `a2db597`，不能称组合回归或上线成功。公网 RFC 解析到198.18.0.82的拦截仍有效，没有重试、更换代理或放宽策略。

## 人格/记忆并行核查

现有角色配置为 Meguri、中文显示名“爱莉”，不能推定为 dot 紫月。speechStyle 已入可信 SYSTEM；LORE 包装为转义的不可信 canon-examples；上下文经 context_blocks 单次注入。`formal_memory_allowed=false` 禁止真实记忆召回/正式写入但不关闭人格、LORE、当前会话；`training_mode=false` 独立关闭训练候选和归档。

已读主树既有归档：PersonaRecallIsolationIntegrationTest 2/2、PersonaRuntime20Test 12/12、LoreQueryFocusTest 15/15、RagProviderTest 7/7、TrainingFeedbackServiceTest 7/7、PostReplyMemoryJobWorkerTest 6/6。未重跑或发送模型请求。假模型/预置虚构记忆证明接线和隔离，不能证明真实持久库 ACL、重启恢复或实际口吻；后续应分别验 work/private、停止昵称偏好、长原作问题、跨虚构 session/跨身份、失败恢复，真实持久化必须使用物理隔离临时存储。
