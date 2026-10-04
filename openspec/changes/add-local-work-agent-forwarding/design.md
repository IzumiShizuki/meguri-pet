## Context

Meguri 的原生桌面端（`apps/desktop-meguri`）已经有清晰的信任边界：renderer 只能通过 preload 暴露的窄 IPC 访问主进程能力，主进程独占文件系统与子进程。同时仓库约定"客户端不拥有权威状态"：Core 拥有 turn、memory 与 Persona 权威，adapter 只做转换与投递。

把任务转交给本机 Codex / DeepSeek Harness 时有两个可选位置：

1. **Java Core 的 `RemoteAgentGateway` + `REMOTE_AGENT` 能力**：符合仓库的 Capability 权威模型，但需要让 Java 进程拉起 `codex app-server` 子进程、读取 Windows 本地安装路径、处理 dsh 的 launcher token，且要新增审批、预算、幂等与 capability scope 一整套语义。
2. **桌面端本地桥**：参考实现 AAAAGENT 的做法，把执行器当作桌面软件而不是 Core 能力。

## Decision

采用**桌面端本地桥**。

理由：

- 被转交的对象是"用户本机已登录的桌面 Agent"，它的凭据、沙箱和审批界面都在桌面语境里。让 Java Core 代持这些状态会把服务端变成用户机器上的进程管理器，与现有 `RemoteAgentGateway` 的 HTTP 远端语义不符。
- 现有 `RemoteAgentGateway` 的契约是 HTTP/远端 agent；`codex app-server --stdio` 是子进程标准输入输出，语义不同，强行塞进同一接口会污染该边界。
- 桌面端已经承担了"本地文件、本地进程、本地凭据"的职责（Everything、TTS、多模态中转），本能力与之同类。
- 桌面端实现可以完全独立于 Core 的 turn 生命周期，即使 Core 未启动也不影响用户把任务交给 Codex。

保留的长期选项：如果将来需要让模型在 turn 中**自主提议**转交，再由服务端重新校验并审批，那一步应当实现为 Java Core 的 `REMOTE_AGENT` 能力，复用现有的 approval、budget 与 idempotency 机制，而不是把本地桥暴露给模型直接调用。

## Consequences

- **正面**：不触碰 Core 权威边界；启用/停用完全由用户显式控制；缺少执行器时桌宠行为与之前完全一致；实现与测试可以独立于 Python/Java 运行。
- **正面**：CCore 未运行、或用户只想要桌宠陪伴时，转交能力仍然可用（当然也仍然需要执行器本身可用）。
- **负面**：桌宠无法在对话里"主动"提议转交，用户必须先打开任务卡面板。这是有意的：模型不能自授执行权。
- **负面**：本地桥与 Java `RemoteAgentGateway` 将来可能同时存在，需要靠命名与文档区分"用户手动转交"与"模型提议 + 服务端审批"两条链路。

## 关键不变式

1. `confirm` 是唯一的写路径；`prepare` / `snapshot` / `targets` / `projects` / `refresh` / `dismiss` 都不向执行器发送任务。
2. claim 必须先于投递落盘，且与 `phase='forwarding'` 在同一事务；`dispatchAttempted` 是持久的重复发送边界。
3. 回执推导是纯函数且悲观：任何缺失、乱序、身份不符或审批未平衡都判 `unknown`。
4. 已完成的回执不会被更慢的 `unknown` 观察降级。
5. 用户自己的文件永不被覆盖：工作预设只在不存在时以 `wx` 写入。

## Alternatives considered

- **让桌宠直接 `codex exec` 一次性命令**：丢弃会话上下文、丢弃执行器自己的沙箱与审批、无法取得可靠回执，且会把一个不受控的 shell 通道引入 renderer 可达的范围。已拒绝。
- **在 Core 里新增"本地 Executor"能力并让模型调用**：需要服务端为每个 turn 管理子进程生命周期与凭据，且在缺少审批界面时无法安全放行写操作。已推迟到需要模型自主提议时再评估。
