## 1. 主进程本地 Agent 运行时

- [x] 1.1 新增 `src/main/local-agents/paths.mjs`：默认路径解析、绝对本地路径校验、根目录包含判断、长度与字符上限。
- [x] 1.2 新增 `src/main/local-agents/receipts.mjs`：`confirmations` / `work_drafts` 两表、`BEGIN IMMEDIATE`、乐观 version、`dispatchAttempted`、`publicReceipt` 投影。
- [x] 1.3 新增 `src/main/local-agents/dsh-discovery.mjs`：Codex 安装/登录探测、Harness CLI 查找、Harness 凭据是否存在（只报告存在性）。
- [x] 1.4 新增 `src/main/local-agents/dsh-preset.mjs`：`meguri-work-v1` 字节级内容、`workPresetReady`、只以 `wx` 写入且不覆盖用户文件。
- [x] 1.5 新增 `src/main/local-agents/codex-bridge.mjs`：`installed` / `compatible` / `list` / `discover` / `send` / `receipt` / `close`，含审批拒绝与未知投递语义。
- [x] 1.6 新增 `src/main/local-agents/dsh-bridge.mjs`：launcher URL 解析与白名单、`/api/<method>` RPC、`foldHarnessReceipt` 纯函数折叠、`createWorkSession` / `submitWork` / `receipt`。
- [x] 1.7 新增 `src/main/local-agents/work-forwarding.mjs`：`snapshot` / `targets` / `projects` / `prepare` / `confirm` / `refresh` / `dismiss` / `close`，连接探测带 TTL 缓存。
- [x] 1.8 新增 `src/main/local-agent-config.mjs`：只保存启用开关、Harness 自动启动与可选数据目录。
- [x] 1.9 新增 `src/main/local-agent-runtime.mjs`：装配上述模块，提供 `installSnapshot` / `settings` / `updateSettings` / `connectTest` / 转交透传 / `nativeUrl` / `startHarnessHost` / `shutdown`。

## 2. 主进程接线

- [x] 2.1 `main/index.mjs` 引入运行时，安装 `localAgents*` 与 `work*` IPC，并校验 renderer 可信来源。
- [x] 2.2 启用后启动 5 秒只读观察循环；`will-quit` 清理定时器、子进程与数据库句柄。
- [x] 2.3 `openNativeWork` 只放行 `http:` / `https:` / `codex:` 三种协议。

## 3. IPC 合约与 renderer 边界

- [x] 3.1 `shared/channels.cjs` 与 `preload/index.cjs` 增加 `localAgents`（get/save/test）与 `work`（snapshot/targets/projects/prepare/confirm/refresh/dismiss/openNative/onChanged）。
- [x] 3.2 不暴露任何通用发送入口；preload smoke 断言 `work.send` 为 `undefined`。
- [x] 3.3 `shared/desktop-contract.ts` 增加 `LocalAgentSettings`、`LocalAgentProbeResult`、`ForwardPhase`、`ForwardRequest`、`WorkSnapshot`、`WorkPrepareInput` 等类型。
- [x] 3.4 `preview-bridge.ts` 补齐两个新命名空间，浏览器预览永不接触执行器。

## 4. renderer 界面

- [x] 4.1 新增 `stores/work.ts`：快照状态、`prepare` / `confirm` / `refresh` / `dismiss` / `openNative`，失败不静默成功。
- [x] 4.2 新增 `components/WorkPanel.vue`：任务卡表单、目标/项目选择、待确认卡、进行中卡、已结束卡与回执结果。
- [x] 4.3 `StageView.vue` / `AmbientStage.vue` / `ControlsIsland.vue` 增加「转交」入口与 `Ctrl+Shift+J`，并把面板加入穿透判定白名单。
- [x] 4.4 `SettingsView.vue` 增加「工作 Agent」小节：安装事实、启用开关、Harness 自动启动、Harness 数据目录、连接测试与边界说明。
- [x] 4.5 `styles.css` 增加面板与设置样式，沿用现有设计变量。

## 5. 验证

- [x] 5.1 `src/main/local-agents/receipts.test.ts`：冻结、version、跨重启持久化、原始输入不可改写、投影剥离、文本/计划校验。
- [x] 5.2 `src/main/local-agents/work-forwarding.test.ts`：prepare 不发送、目标复核、项目一致性、一次性投递、过期版本拒绝、未知投递不重发、不可用执行器阻断、Harness 会话目录、完成不被降级、丢弃退役、连接缓存。
- [x] 5.3 `src/main/local-agents/local-agents.test.ts`：Harness 折叠全部分支、预设安装不覆盖、Codex 目标过滤、路径策略。
- [x] 5.4 更新 `airi-parity.contract.test.ts` 与 preload smoke；`pnpm --filter @meguri/desktop-meguri test`、`typecheck`、`build` 全部通过。
- [x] 5.5 真实环境只读校验：本机 Codex 连接、28 个可转交任务、10 个项目目录、任务卡冻结/过期拒绝/丢弃退役；临时 DSH_HOME 中启动 Harness、安装预设、创建真实工作会话、无请求时回执为 `unknown`。

## 6. 文档

- [x] 6.1 新增 `docs/local-work-agents.md`：边界、使用步骤、两个执行器细节、配置表、数据、故障处理与验证命令。
- [x] 6.2 本 OpenSpec 变更记录 proposal / design / tasks / spec。
