# 统一 Todo 中心本地开发交付（2026-10-31）

本轮完成主站、桌宠、独立客户端和 MCP 的统一合同实现与隔离回归。**尚未部署新站点、执行生产数据库迁移、激活 MCP 或通过真实账号跨端同步验收。** 没有改凭据、生产任务、正式记忆或系统权限。

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

## MCP 测试集合澄清

- **85项**是Meguri **Java Core专项**：69项 `McpSafeErrorDetailsTest` + 8项 `HttpMcpAdapterTest` + 6项 `McpFailureClassificationTest` + 2项 `McpReactApprovalGateTest`。它们包含在Java总计1085项（1080通过/5跳过）之内，并非另一套“最终全量”。
- **57项**是独立 **Node TimePrism MCP适配器仓库全量**：原32项 + 本轮合同兼容25项，全部通过。它们与Java专项属于不同运行时与测试集合。
- 两组分别验证Core审批/结果处理和MCP适配器合同；均不代表已部署服务或真实账号端到端同步。不得把85→57描述为删减或失败，也不将两个数字合成统一端到端通过数。

## 制品与证据

独立客户端目录：`C:\Users\IzumiShizuki\Documents\Codex\2026-10-30\task-3\timeprism-client`。

- APK已上传且没有重复上传：`library_file_id=libfile_441c7401866481918a65481c7762b681`，[下载](https://chatgpt.com/api/library/files/libfile_441c7401866481918a65481c7762b681/download)。
- `dist/TimePrism-Todo-0.2.1-debug.apk`：SHA256 `edad79b00b3bb997bee18ca75cec3d97def41368c5bce6fc0525511a5f4c34bd`。
- Windows完整便携ZIP已上传：[下载](https://chatgpt.com/api/library/files/libfile_b3405a84e0cc8191a6a87664141d1293/download)，`library_file_id=libfile_b3405a84e0cc8191a6a87664141d1293`，144299684字节、89文件逐一核验，SHA256 `7ba9391f279ab1ad8b03c22862fead81dc490a2ea8d869b27e7bbdb6b940c226`。
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

1. Todo部署已有授权，不需再泛泛批准。继续核对并使用既有连接开展隔离PostgreSQL验证；不得自行创建长期凭据。记录实际生产应用制品、配置版本、可恢复数据库备份与 Flyway 状态。本轮已在隔离库完成 `V1028__timeprism_unified_center.sql`、真实并发锁、事务回滚及PG进程重启回执验证；生产发布前仍须最新备份与旧镜像未来迁移兼容预检。源码目标 PG、打包驱动 42.6.2，不代表已检查生产实例。
2. 按正规流程部署站点新接口，回读 capabilities 后启用客户端保护写。回退只回退应用并保留新增 notes/receipt 表及全部数据，不能删表清回执。
3. 重新确认本机启动动作后加载准确提交对应 Core/桌宠制品。MCP 持久连接凭据与具体服务批准仍未完成，不擅自激活。
4. 用户自行复用/建立获准登录后，用明确标记且日期限定当天的临时数据做网站→桌宠→独立客户端→MCP 往返、离线/重连/重复/冲突及暂停验收。保留原任务，测试后暂停规则，不建立真实日报。APK 另做设备安装/键盘/后台生命周期验收。

MVP 不承诺关闭应用推送或跨设备恰好一次提醒。网站 sessionStorage 不保证关页后的草稿恢复；桌宠本地日志有限额，达到上限会阻止新写并保留记录。以上是交付边界，不以演示数据冒充云同步。


## 部署前置条件复核（后续补交付）

既有SSH认证与严格known_hosts已实测可用；个人服务器111.228.35.186的网站API健康200/UP、前端200。远端已有healthy postgres:16容器，不需要为了数据库验证安装新软件或创建长期凭据。本机PATH未找到docker/pg_ctl/initdb/psql；此前44项数据库测试主动关闭远端入口，不能描述成认证被拒或部署未获授权。

常规build-push-deploy全流程带clean master/origin一致与push要求；这不是唯一发布方式。已发现并审阅task-2原有deploy_todo_frontend.py与deploy_todo_fullscreen.py采用的scoped Compose fallback：核验旧文件hash与镜像、备份限定文件、保留旧镜像、单服务build/up、health/revision检查、失败恢复，不需push或合并主分支。旧脚本覆盖范围不能直接代表本轮后端+V1028；需先完成精确远端差异清单、物理PG验收和新的完整备份。

已验证历史完整快照snapshot-20261029-040603-12f7e96afe83有READY；两个Todo overlay目录不能当完整数据库备份。当前仍未做任何生产部署/生产库迁移；正在准备与验证上述具体前置条件。前述Core UAC取消只限制对应本机启动动作，不是站点部署的权限阻碍。


物理PG验证现已完成：2026-10-31，使用远端现有PostgreSQL16.13镜像、network none/无端口/384MiB内存/tmpfs的临时容器，完整还原生产shizuki_app纯结构89表（账号/任务/规则0业务行），实际V1028及15项断言全部通过。包括旧null版本回填、双连接CAS一胜一败、同请求阻塞后原回执、不同摘要可识别且不覆盖、资源与回执事务回滚、便签版本、周期墓碑时区、PG进程重启后保留回执。容器按ID/label核验后删除，没有新长期凭据、生产库写入或服务权限变更。

证据：reports/recurring-drafts-20261030/site-physical-pg-results.json。这里证明SQL/PostgreSQL协议语义，不冒充Java/HTTP全链路；不同payload的HTTP409仍由原Controller测试支持。测试是PG进程restart，不是容器销毁后的数据持久性。原站点Java的44个DB门控测试仍为跳过，不能把本轮15项加写成那44项已通过。首轮夹具缺字段及一次命令引号问题已独立记录，不归为产品失败。

一次动态执行准备脚本前缀的补充命令被自动审查拒绝（可能重复初始化），该命令没有执行，也未重试。所需结构信息通过已恢复状态和明确只读查询取得，因此不是当前部署阻塞。


远端发布范围也已核对：30个生产payload（backend22、site8）及20个只校验guard，合计50/50通过；18覆盖文件匹配已知baseline/overlay，12新增路径确实不存在，无未知差异或符号链接，原05f272e9提醒修复保持。清单SHA256 `e8cf6ad410cec6eac029a24fe22a41b4e0b6237029de7cb118c583f70be11194`。对应site-scoped-payload-manifest.json与site-scoped-remote-verification.json只描述明确范围，不是整库发布；运行镜像和服务仍未切换。


## 最新备份与准确剩余步骤

新完整快照已生成：`/opt/shizuki-site-backups/snapshot-20261031-200318-12f7e96afe83`。READY存在，database.dump的pg_restore清单检查通过，app及3个卷归档读取通过；7个文件SHA256记录在 `reports/recurring-drafts-20261030/site-fresh-backup-result.json`。剩余13,562,516 KiB（约12.93 GiB）。这证明已生成并检查备份，不等于完整灾难恢复演练。frontend05f272e9/backend12f7e96的container/image ID未变，API仍UP，QA容器为空。

站点没有凭据/UAC/部署授权阻塞；无需重新批准Todo部署。剩余均为具体技术工作：

1. 在隔离PG验证旧backend镜像 `sha256:0f7657b669d29987b7d1459f31127ab63c37ac2a489b12cdb4b8f6684b079fcd` 对新增V1028的兼容。核对原spring.flyway的schema/table/locations/ignore-migration-patterns与validate；预检明确关闭 `FLYWAY_AUTO_REPAIR_ON_VALIDATE_ERROR`（`shizuki.flyway.auto-repair-on-validate-error=false`），运行旧classpath的info()/validateWithResult()，证明history未变，再做旧制品smoke。不能假定docker profile与prod profile默认相同，不能靠repair掩盖差异。
2. 将已验证的scoped Compose fallback落实到本轮30文件执行清单，复核运行时漂移和本轮备份，先完成backend/site两个镜像构建，再仅对这两个服务受控切换。后端会短暂不可用；Flyway/健康检查通过后再切前端。原数据库、中间件及其他服务不重启。
3. 上线后验收实际capabilities/纯snapshot、真实账号跨客户端同步和客户端UI。回退保留新增表、回执及切换后用户写入，不能自动用旧数据库备份覆盖。

本轮补交付没有扩建发布执行器或改变生产配置，未执行生产切换。既有Todo部署授权足以继续上述受控发布；真正需要用户具体确认的独立事项仍是此前取消的本机Core UAC重发，以及若要新增MCP长期凭据/管理权限时对应的具体连接。它们不阻塞站点发布准备，也没有在本轮被触发。

补充证据索引：`reports/recurring-drafts-20261030/site-deployment-readiness-20261031.md`、`site-physical-pg-results.json`、`site-scoped-remote-verification.json`、`site-fresh-backup-result.json`。APK Library身份保持 `libfile_441c7401866481918a65481c7762b681`，未重复上传。
