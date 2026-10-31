# 统一 Todo 中心开发与主站发布交付（2026-10-31）

统一 Todo 主站已完成生产部署，V1028 已事务提交，新后端与前端均健康运行，公网制品核验通过。桌宠、独立客户端与 MCP 的统一合同实现及隔离回归已完成；**真实账号跨端同步、实际桌面/手机界面和 MCP 激活仍未验收，Windows 聊天附件也尚未交付成功。** 本轮没有创建生产测试事项、新长期凭据或修改系统权限，未重发本机 Core UAC。

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
- Windows完整便携ZIP已保存到Library，但发送聊天附件时因单文件大小上限失败，**不能算已成功交付**。原完整包保留为 `libfile_b3405a84e0cc8191a6a87664141d1293`，144299684字节、89文件逐一核验，SHA256 `7ba9391f279ab1ad8b03c22862fead81dc490a2ea8d869b27e7bbdb6b940c226`。可发送的小分卷见下表。
- `dist/TimePrism-Todo-0.2.1-win-x64/TimePrism Todo.exe`：须使用完整目录，EXE 是 Electron 宿主，应用资源树 SHA256 `b16fd16b3b115094c3b9da0fb54498b7678480f7b4a9ff7ff9aa849cb5a468fd`。
- `dist/TimePrism-Todo-0.2.1-source.zip`：SHA256 `0cbaee964cbcca6762bee46ab5281604780befda474d42b92ab7f4f06ac06b64`。
- 该目录 `reports/v1-review-20261031/artifact-manifest.json` 记录逐文件哈希、构建时间和 APK/Windows 同源资源校验。

### Windows 分卷补交付

每份为标准 ZIP，前三份39846082字节，第四份24762214字节，均小于40MiB；恢复包3367字节。四个ZIP分别封装原完整ZIP的连续字节块，不是删减程序。恢复脚本先检查顺序、单卷SHA256和长度，按序拼接，核对最终SHA256后才生成完整文件；不同内容的既有输出不会被覆盖。脚本不联网、不启动EXE、不改执行策略。若系统禁止脚本执行，应停止并联系管理员。

| 文件 | Library ID |
|---|---|
| TimePrism-Todo-0.2.1-part01.zip | `libfile_ef720d94b2388191a4ffa88cc8e779c8` |
| TimePrism-Todo-0.2.1-part02.zip | `libfile_511eebd282388191add9cc354bdb1de1` |
| TimePrism-Todo-0.2.1-part03.zip | `libfile_425242e55fc881918d02f45eb3f17030` |
| TimePrism-Todo-0.2.1-part04.zip | `libfile_281bf8ec85488191aa9a68ebd4048f78` |
| TimePrism-Todo-0.2.1-Restore-Kit.zip | `libfile_2734ec1fd9e0819183ca9837fe398a61` |

下载五份后，解压恢复包，把其中脚本与manifest放到四份分卷同目录，在PowerShell运行 `./Restore-TimePrism.ps1`；验证成功后再解压生成的完整ZIP，保留全部依赖并运行 `TimePrism Todo.exe`。分卷本身不用解压。恢复脚本已真实运行两次：首次重组及ZIP CRC/89文件校验通过，第二次识别相同输出安全返回，最终SHA256与原包完全一致。

Library五项保存均成功；父会话随后发送四个小于40MiB的分卷加恢复包，仍明确失败：`A Library file exceeds the ChatGPT upload limit`。**Windows聊天附件尚未交付成功**，不能用Library保存结果代替发送结果。已按协调要求停止继续拆分或上传，等待父会话核对实际附件限制；现有完整ZIP、分卷和恢复包全部保留。Windows不支持当前身份helper使用的xattrs写入，云端保存不受影响，完整身份保存在本地sidecar，没有重复上传。证据在 `reports/unified-center-20261030/portable-parts/` 的 `verification.json`、`library-identities.json` 与 `parts-manifest.json`。APK未重复上传。

后续经明确授权，只生成一个精确8MiB（8388608字节）的首卷做附件限制诊断：`TimePrism-Todo-0.2.1-8MiB-part01.zip`，Library ID `libfile_f3b2f852ae2c81918bd1e2e6b711c008`，SHA256 `bedac43c05c828aa4f928ba98252b01b5a41f53b09d0dc62070f20ed1c9a7793`。Library保存成功，实际附件发送待父会话测试；该首卷不是完整程序，也不能与旧38MiB分卷混用。其余8MiB分卷没有生成或上传。

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

## 本轮生产发布

站点：`https://site.shizuki.online`，个人服务器 `111.228.35.186`。发布源为站点仓库 `80655ccd7db5294e0d800e85d10f2550b7107cd6`，分支 `codex/todo-weekdays-production`，工作树干净，未push/merge。

本次是限定范围发布：30个生产文件（后端22、前端8）及20个只校验guard全部通过，保留原05f272e9提醒修复等既有overlay。清单SHA256 `e8cf6ad410cec6eac029a24fe22a41b4e0b6237029de7cb118c583f70be11194`。服务器原 `.deployed-commit=12f7e96` 基线标记保留，不能把它或镜像revision标签当整库部署状态；本轮范围由清单与release账本记录。

- V1028提交：2026-10-31 **13:32:37 UTC / 上海21:32:37**。SQL SHA256 `31ae4f8412b7fc3c5967a15244db7896df1864659dd95f29f7101aadcf0b2429`。
- 后端健康核验：**13:34:24 UTC / 上海21:34:24**。运行镜像 `sha256:32e30b616a8c6d59996b4c50d5fa4684b1d804fb43af3ccc97b294cd5cd49e45`；容器 `1b38a5b2ae129c332e3db9351d414694ae58d5906a2fec22273396ec13ba0fc0`。
- 前端健康核验：**13:35:44 UTC / 上海21:35:44**。运行镜像 `sha256:99d773956484af6a3dc521350fbcbf882fe5988248dea1ed8cb66dbe1d27a1de`；容器 `472ed696f0a1f67687e226d92f2d2fb93b871eed55bcdaf100f463cc6275576d`。
- 13:43 UTC最终只读复核：后端UP、首页200，剩余磁盘约10.06GiB、可用内存约4.79GiB；其他25个运行容器ID不变，QA容器残留0。

生产原配置关闭Flyway，历史仅有baseline0一条，旧1000–1027资源仍显示PENDING。因此本轮保持自动迁移禁用，使用既有应用角色只执行V1028显式事务：5秒锁等待、30秒语句上限；不repair、不补写虚假历史、不更改权限。新后端启动后，history/catalog双快照仍与提交证据一致：便签与回执表存在、两张原表version_num均非空且默认0、空版本数0。未来如需开启全量Flyway，必须另行核对既有DDL与历史，不能直接打开开关。

首轮镜像构建在依赖下载/打包阶段触达20分钟上限（124），当时未迁移、未切换。核实原PID消失、两次无活动worker、锁可取得、BuildKit缓存不在使用后，归档首轮日志并只启动一次缓存续建。第二轮后端BUILD SUCCESS（保留testCompile，使用-DskipTests不等于执行单元测试），前端随后成功；两个镜像均核验后才迁移和切换。没有删除缓存、备份或改变依赖源。

## 本轮发布验收证据

| 检查 | 结果与证明范围 |
|---|---|
| 物理PostgreSQL16.13 | 生产完整schema-only的89表隔离副本，业务行0；V1028/CAS/同请求并发/事务回滚/进程重启等15项通过。仅SQL/PG语义，不能替代Java/HTTP全链路；原44项DB门控仍跳过。 |
| 旧镜像回退预检 | 使用真实旧/app/app.jar启动、数据库健康UP、迁移后旧Todo24列/Rule16列投影可读、history未变。QA仅排除不存在的外部邮件/Redis/Kafka健康和OSS桶启动校验，未改生产；不宣称旧版支持新便签功能或所有新数据写入语义。 |
| 生产迁移 | 一次事务提交，catalog核验通过，启动后history仍仅baseline0，无repair/自动迁移。 |
| 生产运行 | 新backend/site实际image ID及revision/manifest标签匹配，后端UP、前端200；这是SHA/标签校验，未做镜像签名验证。 |
| 公网静态制品 | 首页及11个静态资源与容器内SHA一致，合计981917字节（含首页）。从入口代码可到达真实Todo/Notes共用chunk。 |
| 本机独立核验 | 首页、3个入口资源、App及Todo/Notes共用chunk从本机HTTPS读取，均与已部署容器哈希对应；无token/cookie。 |
| 现有公开API | login-appearance与quote/alternatives在切换前后均200/OK、结构及缓存头正确；选择了无业务写入/外部provider调用的纯读路径。 |
| 匿名权限边界 | capabilities、snapshot、notes为401；只证明未授权访问被拒，不能算登录后功能调用成功。 |
| 发布辅助脚本 | 范围执行器29项、迁移执行器21项、后端只读核验器18项离线测试通过；公网检查器原30项及新增2个EOF回归通过。均与产品/真实账号验收分开。 |

公网HTML SHA256：`8732865f9bceee70c325a6512a44dcaaf0b1a15d89637bbb0ea84e4e8c1efe60`。

实际Todo/便签页面共用资源：`/assets/TimePrismTodoSuiteWindow-Dp8OuwgT.js`，117210字节，SHA256 `06653e8767175924006b0d0a74b06f345b8e374590086614c61e4c2601bfd312`。静态引用链为 `index-DapqZxm8.js → App-BdaNHg_U.js → TimePrismTodoSuiteWindow-Dp8OuwgT.js`。这证明新版页面代码实际可获取，不冒充真实浏览器渲染、保存或同步验收。

一次懒加载资源哈希不一致已确认为验收脚本误报：SSH读取在退出状态先到、EOF尚未到达时过早返回，将344881字节截成262144字节。容器内wc/sha256sum、本机和服务器公网读取三方一致；两个反例旧版2失败→修复后2通过，原30项仍通过，完整同范围验收重跑通过。没有因此修改生产资源或回退镜像。

本轮未创建生产测试任务、便签、日报或规则；隔离QA已按ID/label清理。上述公网检查不读取用户事项。既有测试源码未变化，不重复刷此前的602/2103、1080/567、57/44等产品测试数字。

## 剩余验收与交付阻塞

1. **真实账号/UI验收未测。** Edge存在站点标签页，但唯一绑定尝试报 `Timed out after 10000ms waiting for CDP command Emulation.setFocusEmulationEnabled.`。登录状态未知，没有复制token、读取密码或输入/刷新页面。需要恢复该浏览器通道或提供可操作的既有会话后，再验证网站→桌宠→独立客户端→MCP的真实账号往返、离线/重连、重复/冲突和暂停；生产原事项保留，临时规则须当天结束并在测试后暂停。
2. **本机Core启动与新版桌面实际运行仍未验收。** 2026-10-31 12:46:36 UTC两种只读检查均未见18080 TCP监听；13:47:58 UTC再次netstat复核仍无该端口记录。此前UAC取消未重发，本轮没有启动/停止本机Core、Harness或无关应用；桌面mock IPC渲染通过不等于真实UI通过。
3. **MCP实际接入未激活。** 新长期凭据或敏感管理权限的具体批准仍待完成；57项Node合同与85项Core专项都不等于真实账号端到端成功。没有为验收开放无认证服务。
4. **Windows附件仍受交付通道限制。** 完整ZIP、38MiB分卷及单独约24MiB分卷的聊天附件发送均明确失败。唯一8MiB首卷已保存，等待父会话实际发送测试；其余8MiB分卷未生成/上传。原完整包与全部依赖保留。APK沿用已确认Library ID，未重复上传；debug APK仍未安装真机验收。

网站MVP仍不承诺关闭应用后的系统推送或跨设备恰好一次提醒；网站sessionStorage不保证关页草稿恢复。桌宠本地日志有界，达到上限保留已有记录并阻止新写。真实界面需要加载新版本，未自动接管/刷新现有标签页。

## 回退与交付证据

完整快照：`/opt/shizuki-site-backups/snapshot-20261031-200318-12f7e96afe83`。READY、database.dump的pg_restore目录及app/三个卷归档已检查，7个文件SHA记录保留；这不是完整灾难恢复演练。最终只读检查确认备份仍存在。

发布账本及逐文件备份：`/opt/shizuki-site-backups/timeprism-20261031-80655ccd-e8cf6ad410ce`。原两个镜像仍保留：

- `shizuki-site/backend:rollback-timeprism-80655ccd-e8cf6ad410ce` → `sha256:0f7657b669d29987b7d1459f31127ab63c37ac2a489b12cdb4b8f6684b079fcd`。
- `shizuki-site/site:rollback-timeprism-80655ccd-e8cf6ad410ce` → `sha256:62c0c320229f4c94e47c9f52ce872e44c63a1402560ab43433da2b245276637e`。

仅在需要回退时，复核当前版本/文件无新漂移后使用同一受控脚本：

```powershell
& 'D:\environment\anaconda3\envs\py314\python.exe' '.\reports\recurring-drafts-20261030\deploy-site-scoped-20261031.py' rollback --execute-reviewed-phase
```

它先恢复旧前端、再旧后端，恢复本次限定源文件，并验证健康。**不自动恢复旧数据库、不删除新增notes/receipt表或切换后的用户写入。** 本轮未执行回退。旧应用对迁移后结构的启动/读取已预检，新功能在旧应用中不可用。

证据均在 `reports/recurring-drafts-20261030/`：`site-old-image-qa-result.json`、`site-physical-pg-results.json`、`site-fresh-backup-result.json`、`site-scoped-remote-verification.json`、`timeprism-20261031-80655ccd-e8cf6ad410ce-ledger.json`、`site-production-v1028-evidence.json`、`site-backend-postswitch-readonly.json`、`site-public-artifact-acceptance.json`、`site-release-final-readonly.json`、`site-local-api-post-backend.json`、`site-local-todo-notes-chunk.json`、`site-independent-artifact-comparison.json`。原始私有配置、备份、用户数据与日志不纳入Git或Library交付。
