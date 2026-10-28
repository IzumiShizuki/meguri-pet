# P2 优化交付与验收（2026-10-09）

本轮三个 P2 项目的实现和自动验收已完成。三个 gpt-6-luna 代理编写代码，主代理负责计划、OpenSpec、接口协调、交叉审查和最终验收。计划见 [P2 优化计划](p2-optimization-plan-2026-10-08.md)，规格核对见 [verification.md](../openspec/changes/optimize-p2-companion-presentation-and-core/verification.md)。工作保留在当前工作区，没有提交、部署或归档 OpenSpec。

## 交付内容

| 项目 | 已实现行为 | 主要入口 / 编码负责人 |
| --- | --- | --- |
| 陪伴导演与主动性 | 用户显式专注；关闭/低/普通/高主动性；复用静音、音量及舞台语音设置；有限队列、冷却和过期；对话、合成和播放中暂缓主动表达；本机承诺确认、完成、取消、到期一次跟进；表现设置重置与撤销 | 设置 → 陪伴与主动性；companion-policy.ts / companion-director.ts / CompanionSettings.vue；director_luna |
| 表情与播放一致性 | 文字不等待 TTS；静态表情随消息更新，动作等待实际起播或无语音时的完成文字；取消代次隔离旧回调；持久播放账本在副作用前认领；重连/重载不自动重播；手动回放使用新身份 | playback-ledger.ts / conversation.ts / speech-playback.ts / companion-notice-delivery.ts；playback_luna |
| 核心与文档整理 | 从 TurnOrchestrator 提取响应生成、流式净化/聚合、时延标记、天气响应补充及可选训练监督；保留构造器、协议和生命周期；区分 Python mock、Java 默认值与本机启动脚本覆盖值 | TurnResponseCoordinator.java；根目录、Core、桌面 README 及 [运行路径说明](p2-runtime-map-2026-10-08.md)；core_luna |

承诺只来自用户在设置页明确确认的内容，不从模型回复自动提取。保存历史开启时内容由主进程偏好保存；关闭时清除持久内容，新事项仅在同源设置与舞台窗口的内存间同步。撤销只恢复主动性、专注、表情和动作四个表现字段，不复活旧承诺状态，不修改正式记忆或训练权重。到期跟进仍需要桌面舞台正在运行；TimePrism 继续承担已有服务器提醒。

本轮完成一个职责完整的 Core 拆分，TurnOrchestrator 从 2,723 行降为 2,640 行，新的协调器为 148 行。剩余的接入/幂等、检索上下文、生命周期及日志恢复仍由原类管理，后续拆分边界已记录；本次没有更换协议或日志结构。

## 验收证据

| 检查 | 实际结果 | 覆盖与限制 |
| --- | --- | --- |
| 桌面完整单元测试 | 62 个文件、385 项通过 | 包含现有回归及本轮策略、隐私同步、账本、音频取消与节拍测试 |
| 桌面类型检查 / 构建 | vue-tsc、Vite 均通过 | 构建仍有既有的 >500 kB 分块提示 |
| Java runtime/training/controller 回归 | 17 个套件、77 项：75 通过、2 条件跳过，0 失败/错误 | 两项 PostgreSQL 进程重启集成测试缺少 MEGURI_RECOVERY_TEST_DSN_FILE，本轮不据此声称真实服务器恢复通过 |
| Java 提取聚焦回归 | 14 项通过 | native/fallback/空流/取消/顺序，以及第二候选生成或监督归档失败仍保留首个回复 |
| Electron preload / instance / tray | 均通过 | 检查现有原生接入回归 |
| P2 双窗口 Electron | 自行启动及清理 Vite，退出码 0、ok=true | 实际设置控件、撤销、承诺新增/完成/取消、内存跨窗状态、真实 15 秒调度、专注延后、单次文字投递、账本 completed；语音调用 0 次、历史无测试文本、无控制台错误或其他外部请求 |
| Git diff 检查 | 无空白错误 | 只出现已有 LF/CRLF 提示 |

P2 Electron 使用隐藏窗口、临时用户目录和离线预览桥。先通过界面确认合法的未来事项，再调整合成测试事项的到期时间，等待真实调度器工作。网络只允许本地预览，装饰头像资源被明确阻断并另记；没有联系 Core、TTS 或远端提醒服务。结果与截图保存在忽略目录 tmp/p2-companion-smoke/，可通过下述脚本重现。

验收发现并修复了一个实际接入缺陷：Pinia 开发工具为每次动作创建新的 store Proxy，导致用 this 作为 WeakMap 键的通道、监听器、调度器和 flush 状态无法互相读取。Luna 统一使用 toRaw(this) 并新增动作代理回归后，真实双窗口同步通过。另修正了既有提醒测试的陈旧预期；“明天 3 点”继续采用已有时间推断规则，缺时刻的句子才要求补充，没有因此改动提醒解析器。

## 重现与回滚

在仓库根目录复查桌面：

~~~powershell
. 'D:\environment\activate-dev-env.ps1' 21
pnpm --filter @meguri/desktop-meguri run test:unit
pnpm --filter @meguri/desktop-meguri run typecheck
pnpm --filter @meguri/desktop-meguri run build
pnpm --filter @meguri/desktop-meguri exec electron scripts/p2-companion-smoke.mjs
~~~

在 java 目录复查 Core：

~~~powershell
. 'D:\environment\activate-dev-env.ps1' 21
mvn -q '-Dtest=com.meguri.core.runtime.*Test,com.meguri.core.training.*Test,RuntimeWebControllerTest' test
~~~

关闭主动性可停止非请求新闻；已确认事项仍可安静跟进。表现设置可以撤销或重置。代码回滚需恢复本轮涉及的代码并重启桌面/Core；Core 提取没有数据迁移。当前工作区同时包含此前的 P0/P1 与资源接入成果，不能用整仓重置来回退本轮。

## 已知边界

- 真实 GPT-SoVITS 听感、Windows 输出设备、Live2D 动作与语音的感知节拍仍需在实际资源下人工验收；本轮假音频测试和离线窗口检查不替代这一步。
- 播放账本保留最近 512 条标识与状态，不保存语音正文。先认领再播放在崩溃时可能跳过一次效果，用户可手动回放；不承诺无限期历史去重或跨进程分布式恰好一次播放。
- 本地承诺跟进依赖桌面运行，关闭历史保存后的事项不跨退出保留。
- P1 的 Qwen 完整训练、召回、第二候选后台化及连续语音/流式 TTS 保持独立范围。
