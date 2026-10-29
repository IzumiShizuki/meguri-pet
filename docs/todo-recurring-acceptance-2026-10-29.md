# Meguri 与新版 TimePrism 周期待办合同验收

本轮只修改 Meguri；站点工作区只读核对到 `ec29b37c`，功能版本 `7960c5d3`、全屏提醒修复 `05f272e9`。没有新增真实任务、修改用户任务、创建日报或读取凭据。

## 已修复

- 普通待办保留 `recurringRuleId` / `recurringInstanceAt`，另读 `/api/v1/light-apps/todo-recurring-rules` 获取原始 Cron 与规则时区。
- 桌面周期编辑器提供星期多选、周一至周五快捷项、时间、时区、提前分钟/天和启用状态；已有高级 Cron、备注、项目、日期边界及逾期策略保留。
- 明确点击确认后，经过受信任 stage IPC、当前账号校验、既有加密登录身份写规则；保存后 GET 回读确认字段。创建超时保留独立请求 journal，恢复时只核对原记录，避免重复 POST。
- 隔离真实 renderer 验收发现 Vue `ref` 中的请求被包装为 Proxy，导致真实 Electron IPC 抛出 `An object could not be cloned`。周期编辑器现在复制成普通 DTO（包括星期数组）后提交，保留原请求身份供不确定结果重试。
- 新建规则显式使用规则时区当天 00:00 作为站点默认开始时间，并在请求 journal 保存首次值，跨日重试不改变原请求。关闭提醒按站点合同发送并严格校验 `null/null` 提前量；其余回读字段继续严格校验。确定的服务端拒绝允许编辑后重新确认，不确定结果保持原请求。
- 生成的日期实例仍按自身 todoId 完成，核验 ruleId / instanceAt / dueAt 未被替换；不把一个日期的完成变成整条规则完成。普通提醒编辑入口不把周期实例改成上海时区的单次提醒。
- 提前提醒按规则 IANA 时区解析，DST 不存在时刻跳过、重叠时刻取首次；缺失规则时区不静默猜测。提醒身份包含规则和实例日期，既有本机持久化去重与失败恢复继续使用。
- 日历按已保存的星期规则及完整开始/结束时间补充只读预测，已生成或已完成的日期不重复预测；预测标明“尚未生成”，不产生提醒或写操作。
- 聊天中的每周/工作日/周一三五请求明确导向周期编辑器；本轮没有实现自然语言直接生成周期规则，不能宣称聊天已自动创建。

## 验收矩阵

| 项目 | 已有实现/修复 | 配置启用 | 实际触发与结果 | 权限/回执/恢复 |
|---|---|---|---|---|
| 站点规则合同 | 已核对 DTO/Controller/前端规格 | 不改站点 | mock 请求 POST/PUT 与 GET 回读通过 | 未向生产写入 |
| 星期多选与 Mon-Fri | 新编辑器与 Cron 双向映射 | 需新桌面构建加载 | 纯逻辑合同测试及真实 renderer 隔离 UI 通过 | 真实账号保存未测 |
| 日期实例完成 | 保留实例元数据并仅更新一个 todoId | 复用现有登录 | 虚构周三完成后周四仍未完成 | 未操作真实任务 |
| 提前提醒/时区 | 规则时区、DST 和提前量接线 | 复用提醒运行时 | 隔离测试通过 | 既有去重持久化/网络恢复测试通过 |
| 双端数据同步 | API 权威、60 秒可见面板刷新、75 秒提醒快照 | 现有账号连接状态待核验 | fixture 保存/回读验证 | 生产双端/UI同步未测 |
| 重复提醒 | 本机按日期实例持久化去重 | 新代码待加载 | restart/重复扫描/同实例键测试通过 | **无跨设备共享投递回执，两端可能各提醒一次** |
| 认证 | 既有邮箱登录、主进程 safeStorage 加密 refresh token | 不创建/修改凭据 | fixture 认证/轮换原测试通过 | 当前真实账号连接未检验 |

## 实际执行

```powershell
$env:TEMP='D:\program\meguri-pet\tmp\todo-tests'
$env:TMP='D:\program\meguri-pet\tmp\todo-tests'
pnpm --filter @meguri/desktop-meguri exec vitest run src/main/shizuki-recurring.test.ts src/main/shizuki-timeprism.test.ts src/main/shizuki-reminders.test.ts src/main/shizuki-write-journal.test.ts src/renderer/utils/todo-calendar.test.ts src/shared/reminder-intent.test.ts
pnpm --filter @meguri/desktop-meguri typecheck
git diff --check
```

最终：6 个文件、123 项测试通过；类型检查通过。最终测试日志位于 `reports/todo-recurring-20261029/verification-review.log`。新增 19 项周期合同测试和 5 项聊天周期边界用例；fixture 按站点实现规范化默认日期与关闭提醒字段，覆盖跨日请求恢复、400/403/422 确定拒绝后重新确认、非预期回读拒绝和同日开始/结束时间边界。最初沙箱默认临时目录重命名失败；改用工作区临时目录后仅已有 loopback HTTP 测试受网络沙箱限制，获受限测试执行权限后全部通过。测试没有生产网络请求。

## 真实 renderer 的隔离界面验收

2026-10-29 15:09:53–15:10:01 UTC，新增 `apps/desktop-meguri/scripts/recurring-todo-smoke.mjs` 加载真实生产构建 Vue TodoPanel / RecurringTodoEditor 和 sandbox preload。独立临时 userData、隐藏窗口、mock IPC 主机，所有非 file/data 请求被阻止；没有读取现有 Electron 用户数据、连接站点或重启桌面。

```powershell
$env:TEMP='D:\program\meguri-pet\tmp\todo-tests'
$env:TMP=$env:TEMP
Remove-Item Env:ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
pnpm --filter @meguri/desktop-meguri typecheck
pnpm --filter @meguri/desktop-meguri build
pnpm --filter @meguri/desktop-meguri exec electron scripts/recurring-todo-smoke.mjs
```

7 项通过：周一三五已有规则回读；星期多选及 Mon-Fri 只改变草稿；显式提交的 21:15/提前 25 分钟经真实 IPC 到达 mock 并显示回执；关闭重开后回读；明确拒绝后解锁且下一次使用新请求 ID；不确定结果锁定草稿、重试保持原请求、确认成功后解锁；窗口始终隐藏且无真实站点访问。共 5 次 mock 保存调用（包含拒绝和重试）。类型检查和 renderer 构建通过；构建只有既有大 chunk 提示。

修复前后证据位于 `reports/companion-acceptance-20261029/`：`recurring-todo-before-fix.json/png`、`recurring-todo-smoke.json`，以及 `recurring-todo-draft.png`、`recurring-todo-rejected-editable.png`、`recurring-todo-uncertain-locked.png`、`recurring-todo-success.png`。已检查失败恢复截图的实际渲染内容。通过构建 JS 为 `index-Bb2y3xB0.js`，SHA-256 `a78071616106f66a3f017b05669e23ba2155b32cfad0f8ab605926bd63b1deba`。

这里的保存、回读是模拟服务器；真实客户端 HTTP 合同由前述 123 项测试覆盖。本次不能代替真实账号 UI 保存、站点双端同步或已运行旧桌面的部署验收。

## 真实验收方式与剩余边界

新源码不代表正在运行的 Electron 已加载；单独部署 Core 也不会更新 Electron 主进程。协调者完成桌面构建和正常重启后，使用现有设置登录状态验收，不能从浏览器或磁盘另行抽取 token。

受支持的 renderer bridge 为 `window.meguriDesktop.shizuki.status()`、`listRecurringRules()` 和 `listTodos()`。它们仅对已信任的本地 stage 窗口开放。注意现有 `listTodos` IPC 会更新提醒快照并扫描到期提醒；只要核对站点合同而不触发通知时，应在现有主进程使用 client 的只读列表方法并仅输出计数/字段完整性，避免调用 `useSnapshot`。不要另起客户端共享 refresh-token 文件，以免并发续期破坏当前登录。

只读验收应记录连接是否就绪、HTTP 状态、规则/实例数量、实例字段完整率、时区是否合法，不输出标题、备注、账号、token。实际 UI 周一至周五选择、保存、重新打开、日历与完成按钮，必须单列为 UI 验收，不能以接口结果代替。

站点提醒目前是网页内卡片（约 30 秒检查），需要页面打开；不是系统闹钟，也无关闭网页后的推送或声音保证。桌面提醒需要 Meguri 运行。两端各自去重，跨端全局单次投递仍缺服务端共享回执，不在本轮伪造实现。
