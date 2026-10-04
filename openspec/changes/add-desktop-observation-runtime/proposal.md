## Why

Meguri 当前已经有桌宠表现、Persona、对话上下文和时间状态的运行时基础，但桌宠还无法可靠知道自己附着在哪个窗口、在什么隐私边界内观察，或把一次观察压缩为可过期的语义事实。现在补上这条本地优先的观察基础链路，可以为 PixPin、Screenpipe、主动表达和 Watch 提供稳定契约，同时避免把原始屏幕内容直接混入普通对话上下文。

## What Changes

- 新增可序列化的 `ObservedWindow` 模型，描述窗口身份、进程、标题、边界、锚点、偏移和可见性。
- 新增窗口附着状态机：绑定、更新窗口几何、最小化/恢复、解除绑定，并禁止观察运行时自主移动桌宠。
- 新增本地观察策略，支持总开关、应用/标题黑名单、隐私模式和敏感窗口默认拒绝。
- 新增 `ScreenContext`、`SemanticDiff` 和 TTL 模型，用结构化语义状态替代连续原始截图。
- 新增确定性的屏幕上下文有效性判断：过期事实不得继续作为当前事实使用。
- 新增 mock observation provider 和单元测试，先验证协议、状态转换、隐私拒绝和差异计算，不接入真实截图、OCR、VLM 或主动发言。
- 为后续本地 Windows Agent、PixPin 和 Screenpipe 保留 provider 接口与事件扩展点。

## Capabilities

### New Capabilities

- `desktop-window-observation`: 管理桌宠与 Windows 窗口的附着关系、窗口状态变化及本地隐私策略。
- `screen-semantic-context`: 管理屏幕语义上下文、语义差异、置信度、来源和 TTL，有效期后自动降级为历史事实。

### Modified Capabilities

无。

## Impact

- Java Core：新增 observation/screen 领域模型、服务和 provider 契约；为后续 ContextBundle 接入保留独立边界。
- Desktop adapter：新增本地观察能力的协议占位和 mock 实现，但本次不开放真实屏幕读取。
- Protocol：增加可回放、可审计的观察状态/语义上下文事件类型，避免把观察数据伪装成普通用户 turn。
- Tests：覆盖窗口附着状态、隐私拒绝、ScreenContext TTL、SemanticDiff 和 provider 行为。
- 不修改现有 AIRI 渲染、TTS、天气通知和用户已有桌面启动脚本；不触碰工作区中既有的 `ops/scripts/start-meguri-airi.ps1` 修改及 `start-meguri-airi.bat` 未跟踪文件。
