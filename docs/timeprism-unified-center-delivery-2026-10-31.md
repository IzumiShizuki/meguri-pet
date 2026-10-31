# 统一 Todo 中心本地开发交付（2026-10-31）

本轮完成主站、桌宠、独立客户端和 MCP 的统一合同实现与隔离回归。**尚未部署新站点、执行数据库迁移、激活 MCP 或通过真实账号跨端同步验收。** 没有改凭据、生产任务、正式记忆或系统权限。

## 实现与本地版本

- Meguri：`fix/dialogue-reliability-20261028`，实现提交 `5ad1be7cb9a6a25e3fa575e72aac12c087d69305`，排序收尾 `9e7cfc2fdb4d306a7a15191d6ce8bdebb674d969`。原 `41bb8dd`、`91d07fd`、`93e90eb` 均保留；用户原有 15 个修改/未跟踪文件 SHA256 不变，未纳入提交。
- 主站：`codex/todo-weekdays-production`，提交 `80655ccd7db5294e0d800e85d10f2550b7107cd6`，工作树干净。
- 独立 APK/Windows：`0.2.1`，目录不是 Git 仓库，不虚构提交。原制品保留。
- MCP：基于 `1e04e28`，连接关闭修复 `9680c898352474b79a7ebc11a8d8331f5c77dd07`；最终合同兼容提交 `9ebb4db38783496506971e7d88994de985bd4e79`。原 6 处候选运行探针/证据改动保留。
- 未 push、未合并主分支。

共享合同：[timeprism-unified-center-v1.md](contracts/timeprism-unified-center-v1.md)。Shizuki site 是唯一权威；同一用户、任务 ID 和版本跨端复用。约定作为普通任务正文保存，截止可空且不显示逾期；LOW/MEDIUM/HIGH 表示手动紧急程度。便签独立，无强制截止。纯查询不派发提醒。

站点实现能力发现、独立便签、SQL CAS 和事务幂等回执；旧客户端省略保护字段仍兼容，但写入递增版本，显式空/半保护字段拒绝。日期实例与周期规则分离；删除规则保留历史时区依据。网站保留冲突草稿和原请求，既有本地队列采用 sessionStorage。

桌宠原 Todo 按钮接入新中心，支持任务/约定/便签及保护后的周期编辑。缓存、草稿与不可变写入日志按 origin/user 隔离，能力缺失只读；丢响应保留原 UUID/正文/版本，409 不自动覆盖。核对回执时接受站点已知规范化（空正文、DAY 时间、提醒清空），拒绝错 ID、缺字段、旧版本及异常删除回执。周期实例的权威时区不被活动规则覆盖；时区未知时保留服务器组内顺序；不会在纯读时改掉原提醒字段。

MCP 错误只透传有匹配 UUID、受限代码和正确类型的结构化摘要，作为不可信数据。远端声称“可重试”或“无不确定性”不能替代本地授权/结果判断；MCP 写错误保留 UNKNOWN_OUTCOME 与 VERIFY_BEFORE_RETRY。无 request_id 的现有读工具仍返回通用错误。

## 验证结果

| 范围 | 结果 | 证明范围 |
|---|---:|---|
| Meguri Java | 1080 通过，0 失败/错误，5 跳过 | 旧 1016 项状态不变，新增 69 全过；隔离配置/HTTP fixture |
| MCP 安全错误专项 | 85/85 | 新增 69 项旧实现 35 失败，修改后 69 全过；含实际 executor、审批和操作回放 |
| 桌宠单元测试 | 567/567 | 缓存、账号、幂等、冲突、回执、排序和既有功能 |
| 桌宠渲染 | 6/6 | 实际构建 Vue + sandbox preload，隐藏窗口、假 IPC、外网阻断；约定/便签/冲突/丢响应 |
| 桌宠 typecheck/build | 通过 | 已知大 chunk 警告仍存在 |
| Python | 393/393 | 项目要求的本地回归 |
| TS 协议 | 52/52 | 本地合同测试 |
| adapter-contract | 网站 21、Python 38、AIRI 34 全过 | 与上述测试有重叠，不相加刷量 |
| 站点 Java | 602 通过，0 失败/错误，44 跳过 | 旧 584 项状态不变，新增 62 全过；未连接真实 PG |
| 站点前端 | 2103/2103 | 单元/组件合同测试；构建和整站离线 package 通过 |
| MCP 适配器 | 57/57 | 空正文/时区/严格回执修复专项旧19失败→25全过；不自动重试 |
| 独立客户端 | 44/44 | 严格回执、离线、账号、时区等 fixture |
| Windows 打包制品 | 通过 | 隐藏原生窗口置顶、透明度 35%→100%、托盘、纯文本与隔离展示 |
| Android APK | 构建、zipalign、v2/v3 签名通过 | 复用原 debug key；未安装设备、未实机验收 |

原 preload smoke 的内部断言曾输出成功，但执行器未自然结束，已中断该测试会话；不标为完整通过。独立 instance/tray smoke 正常退出。新中心的真实 preload 已在上述 6 项隔离渲染检查中覆盖。首次回执专项受沙箱 EPERM 影响未执行，不把它冒充业务失败；提升测试执行权限后通过。

原 5 个 Java opt-in 跳过保留，涉及真实外部服务/PG 等；站点 44 个 DB 门控跳过保留。离线 tests 不能证明物理数据库锁、真实服务、模型或登录账号同步。

## 制品与证据

独立客户端目录：`C:\Users\IzumiShizuki\Documents\Codex\2026-10-30\task-3\timeprism-client`。

- `dist/TimePrism-Todo-0.2.1-debug.apk`：SHA256 `edad79b00b3bb997bee18ca75cec3d97def41368c5bce6fc0525511a5f4c34bd`。
- `dist/TimePrism-Todo-0.2.1-win-x64/TimePrism Todo.exe`：须使用完整目录，EXE 是 Electron 宿主，应用资源树 SHA256 `b16fd16b3b115094c3b9da0fb54498b7678480f7b4a9ff7ff9aa849cb5a468fd`。
- `dist/TimePrism-Todo-0.2.1-source.zip`：SHA256 `0cbaee964cbcca6762bee46ab5281604780befda474d42b92ab7f4f06ac06b64`。
- 该目录 `reports/v1-review-20261031/artifact-manifest.json` 记录逐文件哈希、构建时间和 APK/Windows 同源资源校验。

Meguri 本地证据：`reports/unified-center-20261030/`（全量 JSON、XML ZIP、类型检查、构建、隔离渲染截图、源文件清单）。Java运行日志另在 `reports/capability-integration-20261030/center-*.log`。站点报告：`reports/recurring-drafts-20261030/site-center-verification-20261031.md`。原始日志/构建产物不纳入源码提交。

复现主要命令：

```powershell
# 仓库根；脚本隔离 user.home/provider，并使用已验证 JDK 测试回退
.\reports\capability-integration-20261030\run-isolated-tests.ps1 -LogName center-full-final.log
D:\environment\anaconda3\envs\py314\python.exe -m unittest discover -v
pnpm test:ts
pnpm test:adapter-contract
# apps/desktop-meguri
pnpm exec vitest run
pnpm run typecheck
pnpm run build
pnpm exec electron scripts/todo-center-smoke.mjs
```

## 未完成验收与部署顺序

2026-10-31 11:28 UTC 的只读检查未观察到本机 18080 监听。旧的 PID/版本只是历史记录，不能称当前 Core 正在运行。本轮没有启动/停止它。此前 UAC 被用户取消，未重发；若正常启动仍要求 UAC，需要该具体动作重新确认。

1. 先确认获准的隔离 PostgreSQL 连接；不得自行创建长期凭据。记录实际生产应用制品、配置版本、可恢复数据库备份与 Flyway 状态。在隔离库验证 `V1028__timeprism_unified_center.sql`、真实并发锁、事务回滚、幂等重启恢复。源码目标 PG、打包驱动 42.6.2，不代表已检查生产实例。
2. 按正规流程部署站点新接口，回读 capabilities 后启用客户端保护写。回退只回退应用并保留新增 notes/receipt 表及全部数据，不能删表清回执。
3. 重新确认本机启动动作后加载准确提交对应 Core/桌宠制品。MCP 持久连接凭据与具体服务批准仍未完成，不擅自激活。
4. 用户自行复用/建立获准登录后，用明确标记且日期限定当天的临时数据做网站→桌宠→独立客户端→MCP 往返、离线/重连/重复/冲突及暂停验收。保留原任务，测试后暂停规则，不建立真实日报。APK 另做设备安装/键盘/后台生命周期验收。

MVP 不承诺关闭应用推送或跨设备恰好一次提醒。网站 sessionStorage 不保证关页后的草稿恢复；桌宠本地日志有限额，达到上限会阻止新写并保留记录。以上是交付边界，不以演示数据冒充云同步。
