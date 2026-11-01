# 2026-11-01 对话验收缺陷跟进

本轮在 `fix/dialogue-reliability-20261028`、基线 `d4850fc14a3f5b3653fcfc0bd19f491bf62edfac` 上完成最小修复和只读诊断。没有部署、重启服务、操作独立 EXE、写生产 Todo、启用管理权限、导入第三方 Skill 或创建凭据。原 15 个用户文件逐一 SHA256 保持不变。

## 已修复：自然单句要求漏识别

真实验收的“用一句话说明”和“自然地接一句话就好”没有进入旧版只识别“只用一句话”的约束路径，因此模型的两句回答直接交付。`ReplyConstraints` 现在识别这些当前用户直接要求及“用一个句子”等有限变体，继续使用既有单次重生成和验证后交付机制。

引用、代码、转述、否定、同轮取消不新增约束。分别回答每项、多条独立单句要求属于歧义，保守地不施加全局单句限制。普通聊天流式、人格解析、超时和取消语义没有变更；不截断或拼接模型回答。该识别器不是任意自然语言格式要求的通用解析器。

新增 27 项回归在旧代码上复现 13 失败，修复后全部通过；另外新增 1 项事实上下文输入边界测试。天气文档和自然聊天使用脚本模型验证至多一次重生成及有效输出交付，不是新版真实模型验收。

## 已定位但未宣称修复：783 被附加句说成 780

只读检查隔离会话 `restart-acceptance-f09f3250be3b-facts` 的实际上下文记录。错误轮 `trace_2b743bf7ce3e45eeb406` 收到完整顺序：预算846/材料137 → 预算920/材料137 → 正确余额783 → 当前另一个项目530/62。RECENT_RAW 消耗162 tokens，没有裁剪、补载或检索命中。780 首次出现在模型主动附加的“旧项目余额没有动”一句中；下轮切回仍能计算出783。

目前证据支持生成阶段附加事实漂移，不支持“召回把783变成780”。现有提示已经要求以最新用户修正为准、区分项目、不把助手旧回答当事实、重算派生值。新测试证明这些规则和正确历史被交付给模型，没有硬编码预算答案，也没有以再次添加同类提示宣称问题已解决。

剩余工作：加载本次补丁后重跑真实相似项目切换，并检查整条回答。若该生成偏差持续复现，再单独设计有证据来源和失败回退的事实校验机制；不能用仅匹配正确数字出现的评分掩盖附加错误。

## Skills 与 MCP：路由和配置证据

| 检查 | 实际证据与结论 |
|---|---|
| `/internal/v1/skills` | 已导入本地目录接口，原实测200空数组。不是魔搭远程发现列表。只读数据库核对 `external_skill`、revision、binding、audit 四表均0，符合尚未导入。没有找到加载后被界面丢失的记录。 |
| `/internal/v1/skill-sources/modelscope/skills?q=duckduckgo&page=1&page_size=1` | 本轮真实HTTP200，返回1条、总计14。公开发现可用；没有下载、导入、激活或执行这些候选。 |
| `/internal/v1/capabilities/mcp/sources` | 这是正确管理路由。本轮404是框架通用响应，没有业务 `code` 字段。控制器需要 `meguri.capability.admin.enabled=true`，默认false，正规启动脚本没有设置此开关；符合路由未注册。 |
| 404的限制 | 控制器在已启用但缺少/错误管理凭据时也可用 `CAPABILITY_ADMIN_DISABLED` 返回404。因此不能仅凭状态码断言开关状态。本轮没有读取进程环境或新增管理凭据，启用状态仍以配置和通用响应证据判断。 |

核对文件：`web/SkillManagementController.java`、`skill/SkillRuntimeConfiguration.java`、`skill/SkillManagementService.java`、`web/CapabilityRuntimeController.java`、`application.yml`、`ops/scripts/start-meguri-core.ps1`。相关路由、目录生命周期、配置和权限单测均包含在135项专项中。

本轮未发现需要改路由或让空目录自动下载代码的确定性缺陷。MCP真实调用仍需一个明确选定且经授权的服务及现有允许的接入配置；公开发现成功不等于工具执行成功。

## 桌面565/567的两项失败

1. `src/main/shizuki-write-journal.test.ts > TimePrism durable creation and lifecycle > verifies the real HTTP transport with a temporary loopback test server`：沙箱临时HTTP回环连接得到 `network_error`。上轮正常宿主同文件重跑15/15通过，使用虚构数据及临时服务器；这是环境差异，未改产品代码，也不是生产同步验收。
2. `src/main/voice-recognition.test.ts > Windows voice recognition boundary > loads the PowerShell helper and speech grammar without opening the microphone`：沙箱返回 `unknown/engine_unavailable`。上轮正常宿主直接以普通 `-File .../voice-recognition.ps1 -DiagnosticOnly` 得到 `voice-helper-ready`；本轮普通沙箱执行则在入口被 AuthorizationManager 拒绝，尚未进入语音引擎。未重复已有 `-ExecutionPolicy Bypass` 调用路径、未改变执行策略、未开麦克风。原 Vitest 的全部内部原因没有独立重现到同一错误，不能将直接诊断成功写成567/567通过。

这两项没有证明桌面业务代码缺陷。本轮没有修改桌面文件或为刷绿绕过系统策略。

## 验证与运行边界

| 检查 | 结果 |
|---|---|
| 新单句失败回归 | 旧版27项中13失败；最终27/27通过 |
| 专项组合 | 135通过、0失败、0跳过 |
| 全量 Java | 1113总计：1108通过、0失败/错误、5跳过；175测试套件 |
| OpenSpec严格检查、git diff whitespace | 通过 |
| 隔离源码一致性 | 795个源码/资源文件与主树逐字节一致 |
| 原用户文件 | 15/15哈希保持不变 |
| 当前服务 | 12:05 UTC Core PID42296、桌宠PID5420未变；health/live/ready全部200 |

五项跳过是 Everything实机、两个物理PostgreSQL重启集成、Shizuki窗口控制、公开ModelScope下载导入；缺少对应显式启用条件。本轮没有为运行这些测试扩大权限。

为避免改写运行Core的 `target`，所有Java测试使用既有 `reports/capability-integration-20261030/isolated` 副本、缓存Maven依赖、隔离home和已验证 `jdk.net.unixdomain.tmpdir` TCP回退参数。该参数是JDK测试环境方案，不是修复AF_UNIX底层问题。清除测试进程的外部集成环境变量并将天气/记忆URL指向不可用的回环测试地址。

初次组合测试的 PowerShell 5.1 包装器把普通JVM stderr警告视为终止错误，缺少最终结果。确认进程已结束后改用Python直接保存stdout/stderr，重新取得上述完整退出码和测试汇总；没有改变应用行为或安全策略。

复现命令（在仓库根，现有隔离副本需先同步当前源码）：

```text
python reports/post-acceptance-repair-20261101/run-isolated-regression.py --label targeted-green --tests "ReplyConstraints*Test,ReplyConstraintProviderRegressionTest,IntegratedRetrievalReplyTest,SkillManagementControllerTest,SkillRuntimeConfigurationTest,SkillManagementServiceTest,CapabilityRuntimeControllerTest"
python reports/post-acceptance-repair-20261101/run-isolated-regression.py --label full-green
openspec.cmd validate enforce-explicit-reply-constraints --strict
git diff --check
```

本机证据目录：`D:\program\meguri-pet\reports\post-acceptance-repair-20261101`，含完整命令/退出码、测试日志/XML汇总、公开发现响应摘要、隔离合成对话上下文、用户文件哈希与健康摘要。报告不包含凭据或真实用户对话。

本次修复还未加载到运行进程。此前11:15:12 UTC Core提升权限提示取消、独立EXE应用访问审批超时的边界保持；没有重试或换路径启动。新版真实模型、桌宠UI、跨EXE同步、MCP真实接入不能以离线单测替代。生产主站和真实Todo数据没有变化。
