# Meguri Agent 双环境运维手册

> 适用范围：`D:\program\meguri-pet` 的本机 Windows/AIRI 出口，以及服务器/AstrBot 出口。
> 目标读者：接手巡检、诊断、修复和发布的 Agent。
> 原则：先定位故障层，再做最小修复；不得用重装、清库、关闭鉴权或整体重启代替诊断。

## 0. Agent 使用规则

Agent 每次处理运维问题时，必须按以下顺序工作：

1. 确认用户说的是“本机/AIRI”还是“服务器/AstrBot”，不要同时修改两个出口。
2. 只读收集时间、环境、URL、HTTP 状态、耗时、端口、进程、build/release ID 和最近错误。
3. 沿 `客户端 -> 入口 -> Core -> 下游能力/模型 -> 事件流 -> 渲染/投递` 逐层定位。
4. 先恢复单个失败组件；只有证据表明整条栈不可恢复时才扩大重启范围。
5. 使用同一入口完成健康检查和一条最小 smoke；仅“端口已监听”不算恢复。
6. 记录修复前后证据。没有运行的检查不得写成“已通过”。

严禁在运维记录、聊天、命令输出或提交中回显 API key、Bearer token、Cookie、身份盐、数据库密码或私有证书。只记录 secret file 的路径是否存在、是否可读及权限是否符合预期。

### 0.1 两种“环境”不能混淆

本文的两个运行环境是：

| 运行环境 | 用户出口 | 主要入口 | Core | 典型用途 |
| --- | --- | --- | --- | --- |
| 本机 Windows | AIRI / Meguri 桌面 | `127.0.0.1:5173` | 本机 Java `127.0.0.1:18080`，或由桌面网关代理到远端 Core | 桌宠、TTS、Everything、本机文件、多模态、本机应用控制 |
| 服务器 | AstrBot | AstrBot 平台消息 | 服务器宿主机回环 `127.0.0.1:18080` | 平台聊天、双语图文渲染、日报、可选 Relay 远程任务 |

仓库中的 `dev`、`staging`、`production` 是 Compose 发布隔离域，不是上表的两个用户出口。处理服务器故障前还要再确认目标是 `meguri-dev`、`meguri-staging` 还是 `meguri-production`；不得扫描、串用或回退到另一个发布环境。

## 1. 请求链路和出口边界

### 1.1 本机 AIRI 链路

```text
AIRI / Meguri Desktop
  -> http://127.0.0.1:5173/core
     -> 普通文本：由 MEGURI_CORE_URL 决定
     -> Everything / 文档编辑 / Skill 管理：强制本机 Core
     -> 图片、PDF、可读文档：强制本机 Core，并将会话粘滞在本机 30 分钟
  -> Java Core http://127.0.0.1:18080
     -> LLM / Web / Weather / Python memory-RAG bridge / 本机能力
  -> Local TTS http://127.0.0.1:9880
     -> GPT-SoVITS internal http://127.0.0.1:9881
```

注意两种启动模式：

- `start-meguri-airi.bat` 会把 `MEGURI_CORE_URL` 和 `MEGURI_LOCAL_CORE_URL` 都设为本机 `http://127.0.0.1:18080`，用于全本地调试。
- 直接启动 `apps/desktop-airi/src/web-server.mjs` 时，普通文本默认指向 `https://bot.shizuki.online/meguri-core`，本机资源和多模态仍指向 `http://127.0.0.1:18080`。不要仅凭“桌面在本机”就假定文本请求一定没有出网。

桌面网关负责注入远端 Core token；AIRI renderer 不应接触 token 或模型密钥。远端 token 绝不能被转发给本机 Core。

### 1.2 服务器 AstrBot 链路

```text
平台消息
  -> AstrBot
  -> astrbot_plugin_meguri_gateway
  -> http://127.0.0.1:18080
  -> Java Core -> LLM / memory-RAG / web / daily services
  -> SSE/checkpoint -> AstrBot renderer -> 平台回复

可选远程任务：
  AstrBot -> Meguri Relay http://127.0.0.1:18100
          -> preview -> 人工确认码 -> queued task
```

AstrBot 插件默认只接受回环 Core URL，HTTP 客户端设置 `trust_env=False`，避免服务器全局 HTTP 代理把 `127.0.0.1` 请求送出并产生假 `502`。远程命令默认关闭，且必须同时满足 Relay token、精确操作者绑定和二阶段确认。

`https://bot.shizuki.online/meguri-core/` 是受 Bearer token 保护的公共反向代理路径，主要供可信桌面网关等客户端使用。无 token 收到 `401` 表示 DNS/TLS/OpenResty 已可达，不代表 Core 已完成业务健康检查。

## 2. 功能列表和支持矩阵

状态含义：`支持` 表示有实现链路；`条件支持` 表示需要显式配置或授权；`不支持` 表示当前出口有意不提供，Agent 不得通过关闭安全边界强行开启。

| 功能 | 本机 AIRI/桌面 | 服务器 AstrBot | 依赖与运维要点 |
| --- | --- | --- | --- |
| 同步文本聊天 | 支持 | 支持 | `POST /v1/chat/respond`；真实模型为显式 opt-in，默认 Core 可使用 mock |
| 异步 Turn、SSE、取消 | 支持 | 支持 | `/v1/turns`、session events、snapshot、cancel；默认最多 3 次 SSE 重连，checkpoint 后才执行一次性副作用 |
| 断线重放和游标恢复 | 支持 | 支持 | `after_sequence` / `Last-Event-ID`；HTTP `410` 应恢复 snapshot，不应清空会话 |
| 表情、立绘、动作语义 | 支持 | 支持 | `semantic.completed` 和 expression/sprite 数据由 Core 权威生成；AstrBot 交给 Gal renderer |
| 本机 GPT-SoVITS TTS | 支持 | 不支持 | 只绑定 `127.0.0.1:9880`；AstrBot 的 voice capability 有意关闭 |
| 图片拖放/截图粘贴 | 条件支持 | 不支持 | 仅用户明确提交后读取；图片需 vision-capable 主模型或显式 fallback；AstrBot 当前发送 `attachments: []` |
| PDF 多模态读取 | 条件支持 | 不支持 | 本机安全路径/大小复验；模型路由必须明确支持 PDF |
| TXT/Markdown/CSV/JSON/YAML/DOCX 文档读取 | 条件支持 | 不支持 | 原生桌面使用 `document_read`；单文档读取限制与桌面策略保持一致，不等于任意文件系统权限 |
| `@` Everything 文件搜索 | 支持 | 不支持 | 仅返回允许根目录内的元数据；Core 必须运行在 Windows，WSL/Docker 内无法使用 Windows `es.exe` |
| 生成文件链接和桌宠气泡 | 支持 | 条件支持 | 本机仅暴露 `reports/`、`output/` 下的被动文档；AstrBot 日报可渲染图片或退化成文本 |
| Memory / RAG | 条件支持 | 条件支持 | Java 可桥接 Python `127.0.0.1:8000`；bridge 关闭/不可达时按能力降级，正式 memory 仍由 Python 边界权威管理 |
| Web 搜索 | 条件支持 | 条件支持 | `MEGURI_WEB_SEARCH_ENABLED=true`；默认 Bing RSS URL，8 秒；普通聊天不应无条件触发搜索 |
| 天气简报/临雨提醒 | 支持，可关闭 | 支持，可关闭 | Open-Meteo；默认每 15 分钟轮询，地点持久化在用户目录 |
| Bilibili 只读日报 | 条件支持 | 条件支持 | 本机只读 MCP `8899` 优先，浏览器历史为受限 fallback；AstrBot proactive delivery 默认关闭 |
| 账单摘要/日报存储 | 条件支持 | 条件支持 | 依赖既定 digest/report 文件；失败不应阻塞普通聊天 |
| ModelScope Skill 搜索/导入 | 条件支持 | Core 条件支持 | 默认关闭；仅公开 Skill，15 秒超时；导入后仍是不可信能力，不自动扩大权限 |
| Agent / limited ReAct | 条件支持 | 条件支持 | 由服务器权威决定 `FAST/THINK/AGENT`；能力 scope、预算、审批、deadline 仍必须满足 |
| Shizuki 本机应用控制 | 条件支持 | 不支持 | 本机 manifest、loopback bearer、`shizuki:control` scope；不改变 Shizuki 远端业务网关 |
| AstrBot 中日双语回复 | 不适用 | 支持 | `bilingual_zh_ja=true`；中文在前、日文在后，仍使用五字段 LLM 响应契约 |
| AstrBot Relay 远程任务 | 不适用 | 条件支持，默认关闭 | `/meguri run` 只生成预览，必须 `/meguri confirm` 后入队；禁止绕过操作者 allowlist |
| 身份绑定、幂等和去重 | 支持 | 支持 | client/user/session 分离；AstrBot 平台消息 ID 十分钟去重，重试复用 idempotency key |

### 2.1 多模态特别边界

当前本机启动器使用的 `deepseek-v4-flash` 文本路由不能被误标为视觉模型。图片或 PDF 只有在以下任一条件成立时才能成功：

1. 当前模型 ID 出现在 `MEGURI_LLM_MULTIMODAL_PRIMARY_MODELS`；或
2. 已配置 `MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL` 及可用的兼容 endpoint/secret file。

限制值：单个多模态附件默认 `5 MiB`，总计默认 `10 MiB`，桌面网关仅对带正确多模态标记的请求放宽到 `12 MiB`。标记、附件 `content_access`、MIME、路径、扩展名或模型能力不匹配时必须显式失败，不能把内容静默降级成文件名后继续回答。

## 3. 工作列表

### 3.1 每次接手任务前

- [ ] 记录时间和时区，确认故障是持续、间歇还是仅一次请求。
- [ ] 执行 `git status --short --branch`，保留工作区已有改动。
- [ ] 明确出口：本机 AIRI、原生 Meguri Desktop、服务器 AstrBot，或公共 `/meguri-core`。
- [ ] 明确发布域：本机源码、`meguri-dev`、`meguri-staging` 或 `meguri-production`。
- [ ] 记录用户看到的原始错误、HTTP 状态、请求类型和是否包含图片/文件。
- [ ] 检查端口/进程，再检查入口健康，再检查下游链接；不要反过来猜测。
- [ ] 在任何重启前保存最近日志、build/release ID、容器状态和失败时间窗口。

### 3.2 日常巡检

| 周期 | 工作 | 通过标准 | 异常后动作 |
| --- | --- | --- | --- |
| 每次启动 | 本机 `18080/5173/9880` 健康 | Core `status=ok`、gateway 能代理 Core、TTS `ready=true` | 按第 6 节分层修复 |
| 每日 | 公共 Core DNS/TLS/HTTP | 无 token 为 `401`，带授权的正式 probe 为健康响应 | `DNS/TLS` 与 `502/upstream` 分开处理 |
| 每日 | AstrBot -> loopback Core | 插件无连续 timeout/502，checkpoint 可写 | 检查 Core、token file、plugin data 权限和 `trust_env=False` 版本 |
| 每日 | 外部 provider 耗时 | 没有连续 timeout/429/5xx | 先确认 provider，再调整并发/降级；不先放大所有 timeout |
| 每日 | 磁盘和日志 | 日志可写、无异常增长、artifact 目录不过量 | 依据保留策略归档；不得删除事故证据或数据库卷 |
| 每周 | 链接清单 | DNS、TCP、TLS、HTTP 四层都有记录 | 更新故障单中的失败层和 p50/单次耗时 |
| 每周 | 备份/恢复证据 | 最近备份有 checksum，且存在隔离恢复验证 | 未恢复验证的备份不得作为发布 gate |
| 发布前 | manifest、隔离、验收、回滚 | 所有目标环境 gate 通过，last-good 明确 | gate 失败即停止，不切生产流量 |
| 发布后 | health + 最小 turn + SSE | build/release 匹配、turn terminal、事件无 gap | 先停止新流量，再按既定 rollback |

### 3.3 故障处理记录模板

```text
故障时间/时区：
出口：local-airi | remote-astrbot | public-core
发布域：local | dev | staging | production
用户症状：
请求类型：text | image | pdf | document | tts | resource | agent | daily
第一失败层：client | gateway | core | provider | bridge | sse | renderer
URL/端口（无 secret）：
HTTP 状态/错误码/耗时：
build_id / release_id：
修复动作：
修复后 health：
修复后 smoke：
未恢复功能和下一步：
```

## 4. 链接列表

链接状态会变化，因此本节记录“权威地址、用途、预期响应和超时预算”，不记录某一天的在线快照。

### 4.1 本机链接

| 地址 | 用途 | 正常判断 | 相关超时/轮询 |
| --- | --- | --- | --- |
| `http://127.0.0.1:18080/health` | Java Core 应用健康 | HTTP 200，`status=ok`、`runtime=java` | 入口 probe 建议 5 秒 |
| `http://127.0.0.1:18080/health/live` | Java Core liveness | HTTP 200 | 只说明进程活着，不代表 provider 可用 |
| `http://127.0.0.1:18080/health/ready` | Java Core readiness/release | HTTP 200 且 build/release 符合目标 | 发布验收使用 |
| `http://127.0.0.1:18080/actuator/health` | Spring 管理健康 | HTTP 200 | 启动脚本可用 `/health` 作为业务检查 |
| `http://127.0.0.1:5173/` | AIRI support gateway 静态入口 | HTTP 200 | 只证明 Node 网关在监听 |
| `http://127.0.0.1:5173/core/health` | 从桌面网关到实际 Core | 带可信桌面请求头后 HTTP 200 | 启动脚本为 10 秒 |
| `http://127.0.0.1:5173/artifacts/recent` | 最近生成文件元数据 | HTTP 200 JSON | 只暴露 metadata，不以读文件内容验证 |
| `http://127.0.0.1:9880/health` | 本机 TTS bridge | HTTP 200 且 `ready=true` | 单次 health 3 秒；启动等待上限 3 分钟 |
| `http://127.0.0.1:9880/tts/synthesize` | TTS 合成 | POST 成功返回音频 | 上游合成 30 秒 |
| `http://127.0.0.1:9881` | GPT-SoVITS 内部端口 | 由 bridge 管理 | 不给 AIRI、AstrBot 或公网直接调用 |
| `http://127.0.0.1:8000/health` | Python Core / memory-RAG bridge | 启用时 HTTP 200 | Java memory 1.5 秒，RAG 2.5 秒 |
| `http://127.0.0.1:8899/health` | Bilibili 只读 MCP | 任务启用时 HTTP 200 | health 2 秒，Core MCP 5 秒 |
| `http://127.0.0.1:8899/mcp/` | Bilibili 只读 MCP RPC | 需正确 MCP 调用 | 日报总生成上限默认 20 秒 |
| `http://127.0.0.1:18100` | 可选 Meguri Relay | 仅启用远程任务时存在 | AstrBot/Relay 默认 8 秒 |

### 4.2 服务器和公共入口

| 地址/路径 | 用途 | 无凭据预期 | 故障归属 |
| --- | --- | --- | --- |
| `https://bot.shizuki.online/meguri-core/health` | 受保护的公共 Core 健康入口 | `401` 可证明 DNS/TLS/OpenResty 可达 | DNS/TLS -> 域名/证书；`502/504` -> OpenResty 到 Core；带 token `401/403` -> token/鉴权 |
| 服务器 `http://127.0.0.1:18080/health` | AstrBot 和 OpenResty 的上游 Core | 可能因 Core 鉴权返回 `401` | 无 TCP -> Core 容器/端口；HTTP 5xx -> Core 日志/依赖 |
| `/opt/meguri/staging/{config,secrets,logs,backups,state}` | staging 权威运维路径 | 不适用 | 只允许 staging 工作流管理，不能借用 production 目录 |
| `/opt/astrbot/data` | 现有 AstrBot 生产数据 | 不适用 | 只读诊断；Meguri 发布/回滚不得挂载、覆盖或删除 |
| TLS Docker control plane `111.228.35.186:2376` | 受证书保护的远端 Docker API | 无证书应失败 | 仅经批准的只读检查/发布脚本使用；不是聊天健康 URL |

公共 `/meguri-core/` 的 OpenResty 配置关闭 proxy buffering，read timeout 为 `180s`。AstrBot 自身不应绕公共域名访问 Core，而应使用服务器回环地址。

### 4.3 外部依赖链接

| 地址 | 能力 | 默认预算 | 失败时的正确降级 |
| --- | --- | --- | --- |
| `https://api.deepseek.com` | OpenAI-compatible 文本模型 | LLM 默认 30 秒；staging 示例 60 秒 | 明确返回模型不可用；不得伪造 mock 角色回复冒充成功 |
| `https://www.bing.com/search` | 显式 Web 搜索 | 8 秒、最多 5 条 | 只让搜索能力失败；普通聊天和本地能力保持可用 |
| `https://api.open-meteo.com/v1/forecast` | 天气 | 后台默认 15 分钟再轮询 | 保留最后成功天气/返回不可用，不阻塞聊天 |
| `https://modelscope.cn` | 公共 Skill 搜索和下载 | 15 秒 | Skill 查询/导入失败；已启用本地能力不应被清除 |
| `https://dashscope.aliyuncs.com/compatible-mode/v1` | embedding | 20 秒 | memory candidate 可待处理；不丢原始事务/审计 |
| `https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank` | rerank | 12 秒 | 按既定检索策略降级，不跨 ACL 或扩大结果 |
| `https://api.notion.com` | 可选 Notion knowledge sync | 由 Notion sync 配置决定 | 同步失败不删除现有知识版本 |
| `https://api.shizuki.online/api/v1/internal/meguri/prompt-cache` | 可选 prompt-cache 指标上报 | 5 秒，默认每 60 秒 | 只丢/延后遥测，不能影响用户回复 |

### 4.4 仓库内权威链接

| 主题 | 先读文件 |
| --- | --- |
| Agent 总入口 | [`.agent/README.md`](../../.agent/README.md) |
| 架构和 authority | [`.agent/architecture.md`](../../.agent/architecture.md) |
| 功能到源码/测试定位 | [`.agent/module-index.md`](../../.agent/module-index.md) |
| 本机启动和验证 | [`.agent/workflows.md`](../../.agent/workflows.md) |
| 安全边界 | [`.agent/guardrails.md`](../../.agent/guardrails.md) |
| Java Core 配置与能力 | [`java/meguri-core/README.md`](../../java/meguri-core/README.md) |
| AIRI 桌面出口 | [`apps/desktop-airi/README.md`](../../apps/desktop-airi/README.md) |
| AstrBot 插件、命令和 Relay | [`adapters/astrbot/README.md`](../../adapters/astrbot/README.md) |
| TTS | [`local-services/tts-runtime/README.md`](../../local-services/tts-runtime/README.md) |
| 环境隔离契约 | [`docs/contracts/environment-contract.md`](../../docs/contracts/environment-contract.md) |
| staging 发布/回滚 | [`staging-deploy-and-rollback.md`](staging-deploy-and-rollback.md) |
| staging 备份/恢复 | [`staging-backup-and-restore.md`](staging-backup-and-restore.md) |
| production 审批 | [`production-approval.md`](production-approval.md) |

## 5. 链接超时排查

### 5.1 先确定失败层

| 现象 | 说明 | 下一步 |
| --- | --- | --- |
| `Resolve-DnsName` 失败 | DNS 层失败 | 检查域名、DNS 服务器和解析记录；不要重启 Core |
| TCP refused | 主机可达但端口未监听/被拒绝 | 检查目标进程、容器、绑定地址和防火墙 |
| TCP timeout | 路由、安全组、防火墙或远端网络无响应 | 检查网络路径；不要先修改应用 timeout |
| TLS/证书错误 | SNI、证书链、过期或时间错误 | 检查证书和系统时间；禁止用永久跳过校验作为修复 |
| HTTP `401/403` | 服务可达，鉴权/身份失败 | 比对 token file 路径、权限、tenant/client identity；不回显 token |
| HTTP `404` | 路径、前缀或版本错误 | 核对 `/meguri-core/` 前缀、Core 路由和 build |
| HTTP `408` 或客户端 timeout | 单跳预算耗尽 | 查该跳耗时、SSE heartbeat、下游 provider；不要统一增加所有 timeout |
| HTTP `429` | provider/服务限流或并发饱和 | 降低并发、排队、退避；不要立即重复相同非幂等请求 |
| HTTP `502` | 网关收到无效/不可达上游响应 | 从网关检查 Core loopback；AstrBot 场景确认未继承代理 |
| HTTP `503` | 组件未 ready 或配置缺失 | 查 health body 和依赖；本机网关也会在远端 token 缺失时返回 `503` |
| HTTP `504` | 反向代理等待上游超时 | 查 Core/provider 时间线以及 OpenResty 180 秒边界 |
| SSE 中断/无 terminal | 事件流或代理层故障 | 从最后 sequence 重连；3 次后查 snapshot，不清会话/不重发副作用 |

### 5.2 PowerShell 只读检查

本机端口和拥有者：

```powershell
$ports = 18080, 5173, 9880, 8000, 8899, 18100
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object LocalPort -In $ports |
  Sort-Object LocalPort |
  Select-Object LocalAddress, LocalPort, OwningProcess,
    @{Name='Process';Expression={(Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName}}
```

本机健康检查：

```powershell
Invoke-RestMethod 'http://127.0.0.1:18080/health' -TimeoutSec 5
Invoke-RestMethod 'http://127.0.0.1:9880/health' -TimeoutSec 3

$desktopHeaders = @{
  Origin = 'null'
  'User-Agent' = 'Mozilla/5.0 Electron/43.1.1'
  'X-Meguri-Desktop-Client' = 'airi'
  'X-Meguri-Desktop-Session' = 'agent-health-check'
}
Invoke-RestMethod 'http://127.0.0.1:5173/core/health' `
  -Headers $desktopHeaders -TimeoutSec 10
```

远端公共入口分层检查：

```powershell
Resolve-DnsName bot.shizuki.online
Test-NetConnection bot.shizuki.online -Port 443

# 不带 token 的 401 是“入口可达”，不是业务健康通过。
try {
  Invoke-WebRequest -UseBasicParsing `
    'https://bot.shizuki.online/meguri-core/health' -TimeoutSec 8
}
catch {
  [pscustomobject]@{
    Status = [int]$_.Exception.Response.StatusCode
    Error = $_.Exception.Message
  }
}
```

不要把携带 token 的命令粘贴到工单或 Agent 回复中。需要正式授权 probe 时，使用已有 secret file 和既有脚本/受控 shell，并只汇报状态码、耗时和 build/release ID。

### 5.3 预算关系

| 跳点 | 默认预算 |
| --- | --- |
| AstrBot -> Core HTTP/SSE 单次 I/O | `8s` |
| LLM provider | `30s`（部署 env 可覆盖） |
| Agent planner queue/provider/deadline | `250ms / 4s / 5s` |
| OpenResty public Core read | `180s` |
| Web 搜索 | `8s` |
| Everything | `1.8s` |
| Java -> Python memory/RAG | `1.5s / 2.5s` |
| ModelScope Skill | `15s` |
| Bilibili MCP/整份报告 | `5s / 20s` |
| Shizuki connect/request | `750ms / 2s` |
| Remote Agent connect/request | `3s / 10s` |
| TTS health/synthesis/start ready | `3s / 30s / 3min` |

如果内层调用可能超过外层预算，修复优先级是：让 SSE heartbeat/异步状态保持可观察 -> 缩短或降级内层工作 -> 调整该跳的单一预算。禁止没有测量就把所有 timeout 一起放大，否则只会把快速失败变成长时间卡死。

## 6. 故障矩阵：什么时候检查、如何修复

| 用户症状 | 首查 | 常见原因 | 最小修复 | 修复后验证 |
| --- | --- | --- | --- | --- |
| AIRI 完全打不开 | `5173` 端口、gateway error log、AIRI build | Node 网关未启动；端口被非 Node 进程占用；AIRI 未 build | 确认端口拥有者；启动 gateway；缺 build 时按 AIRI README 构建 | `5173/` 200，再启动 Electron |
| AIRI 页面开但一直 connecting | `5173/core/health` 与 `18080/health` 分别检查 | Core 未启动；网关 target 错；token file 缺失；请求头不匹配 | 恢复 Core；核对 `MEGURI_CORE_URL`/`MEGURI_LOCAL_CORE_URL` 和 token file 路径 | gateway 代理 health 200，完成一条 text turn |
| 本机聊天 timeout | Core 日志、LLM endpoint、请求是否走远端 | provider 慢/429；桌面默认文本路由到了远端；SSE 中断 | 明确当前 target；恢复 provider 或临时使用已批准的本机/mock 路径；保留 idempotency | 同一会话出现 terminal event，无 sequence gap |
| 本机文件搜索失败 | Core 是否 Windows 原生、`es.exe`、1.8s timeout | Core 跑在 WSL/Docker；Everything 未启动/路径错误；查询超时 | 用 `ops/scripts/start-meguri-core.ps1` 在 Windows 启动；修正既定 `es.exe` 路径 | `/v1/resources/search` 返回安全候选；不读取文件内容 |
| 图片/PDF提示模型不支持 | multimodal env、Core route、附件大小/类型 | 文本模型未声明 vision；fallback 为空；文件超限；请求没有多模态标记 | 配置明确的 vision-capable 主模型或 fallback；减小附件；不得把文本模型伪装为视觉模型 | 新会话提交小型允许附件，Core 明确完成或返回具体配置错误 |
| 上传大文件出现 `413/400/502` | 1/12 MiB gateway body、5/10 MiB Core 限制 | 标记不匹配；单文件/总量超限；代理 body 被截断 | 使用受支持类型和限制内文件；检查 `X-Meguri-Multimodal` 与 payload 一致 | attachment 被 Core 接受且没有静默降级 |
| TTS 无声 | `9880/health`、bridge/upstream error log | 模型启动中；9881 上游失败；引用音频/config 缺失 | 等待 ready；仅重启 TTS bridge；核对文件存在，不改聊天 Core | health `ready=true`，一次短句合成成功 |
| 文字正常但 SSE 卡住 | session events、heartbeat、last sequence、snapshot | 代理 buffering；AstrBot 8 秒 read timeout；连接断开；游标过期 | 确认 OpenResty buffering off；允许客户端按 checkpoint 重连；`410` 使用 snapshot | terminal 到达，ONCE 事件不重复 |
| AstrBot 回复“服务暂时不可用” | AstrBot plugin log、loopback `18080`、HTTP 状态 | Core down；token/tenant 不匹配；HTTP 代理污染；协议/sequence 错误 | 恢复 loopback Core；核对 token file 路径；确认运行版本 `trust_env=False`；协议错则回滚兼容 build | `/meguri status` 与一条 `/meguri chat` 成功 |
| AstrBot 普通消息没有触发 | route 配置和消息类型 | 默认要求 `/meguri`；私聊全路由关闭；群聊禁止；sender allowlist | 使用 `/meguri chat`；仅按产品要求调整 route/allowlist | 目标私聊/群聊按配置触发，非目标消息仍不拦截 |
| AstrBot 重复回复 | message ID、dedupe 状态、idempotency key | 平台重投；checkpoint 文件不可写；实例重复运行 | 恢复 plugin data 写权限；停掉重复插件实例；保留同一消息幂等键 | 同一 platform message 仅投递一次 |
| AstrBot 图片/语音不工作 | 功能矩阵 | 当前 gateway 有意 `attachments: []` 且 voice/screen disabled | 不在事故中强开；创建正式功能变更并更新契约/安全策略 | 保持清晰“不支持”反馈，不泄露平台附件 |
| 公共地址 `401` | 是否带正确授权、是否仅做无凭据 probe | 无 token 的预期行为；或 token file 不一致 | 无凭据巡检记录“入口可达”；授权客户端核对同一 secret file 来源 | 无 token 仍 401，合法客户端 health 200 |
| 公共地址 `502/504` | 服务器 loopback Core、OpenResty error、容器 health | Core 未监听；上游崩溃；provider/请求超过预算 | 先恢复 Core 回环健康；再恢复 proxy；只有 Core 健康后才 reload proxy | loopback health -> 公共授权 health -> smoke 依次通过 |
| Web/天气/Skill 单项 timeout | 对应外部 URL和 feature flag | 外部依赖故障、DNS/TLS、预算过小 | 关闭/降级单项能力或等待外部恢复；不要重启整条聊天栈 | 普通聊天正常，单项能力给明确 unavailable |
| Memory/RAG timeout | Python `8000`、bridge flag/token、1.5/2.5s | Python 未启动；bridge 未启用；内部 token 错；向量服务慢 | 若产品需要则恢复 Python bridge；否则保持明确 unavailable；不得旁路正式 memory | bridge health/受控查询成功，tenant/user 隔离仍成立 |
| Relay 远程命令失败 | `remote_commands_enabled`、Relay `18100`、operator binding | 默认关闭；token 缺失；操作者不在 allowlist；设备离线 | 只在已批准时恢复 Relay；重新走 preview + confirm，不直接入队 | devices 可查、preview 有过期时间、确认后才有 task |
| 日报没推送 | AstrBot daily flag/targets、Core inbox、render 目录、8899 MCP | proactive 默认关；目标为空；报告太旧；渲染或 MCP 失败 | 恢复只读数据源/目录；保留 pending 以便重试；必要时退化文本 | 指定目标仅收到一次，delivery state 已持久化 |

## 7. 修复操作

### 7.1 本机安全启动/恢复

完整本机 AIRI 调试栈：

```powershell
& 'D:\program\meguri-pet\start-meguri-airi.bat'
```

只启动 Java Core：

```powershell
& 'D:\program\meguri-pet\ops\scripts\start-meguri-core.ps1'
```

Core 已健康时，只恢复 gateway + TTS + AIRI：

```powershell
& 'D:\program\meguri-pet\ops\scripts\start-meguri-airi.ps1'
```

只恢复 TTS：

```powershell
& 'D:\program\meguri-pet\ops\scripts\start_local_tts.ps1'
```

不要盲目结束 `18080`、`5173`、`9880` 的拥有进程。先确认分别是预期的 Java、Node、Python，并保存日志。`start-meguri-core.ps1` 会提示旧 Java 进程，但遇到非 Java 占用时应停止并调查，不可强杀未知进程。

本机日志：

```text
D:\environment\logs\meguri\meguri-core.log
D:\environment\logs\meguri\meguri-core.err.log
D:\environment\logs\meguri\airi-support-gateway.log
D:\environment\logs\meguri\airi-support-gateway.err.log
D:\program\meguri-pet\log\meguri-tts-bridge.log
D:\program\meguri-pet\log\meguri-tts-bridge.err.log
D:\program\meguri-pet\log\gpt-sovits-api-v2.log
D:\environment\logs\meguri\airi-tamagotchi-runtime.log
D:\environment\logs\meguri\airi-tamagotchi-runtime.err.log
```

日志文件可能因不同启动入口而不存在。不存在时确认该组件是否在前台终端输出，不能把“没有日志文件”直接判断为“没有错误”。

### 7.2 服务器安全诊断和恢复顺序

服务器侧按以下顺序执行，具体命令应在获准的 SSH 或 TLS Docker control plane 中运行：

1. 只读查看目标发布域容器/进程、health、端口 `18080` 和最近日志。
2. 从服务器本机访问回环 Core；鉴权状态与网络状态分开记录。
3. Core 健康后检查 AstrBot 插件到回环 Core，不要让 AstrBot 改走公共域名。
4. 回环链路健康后检查 OpenResty `/meguri-core/` 公共入口。
5. 最后做一条受控 AstrBot smoke；不要使用真实生产消息数据当测试 fixture。

若需要重新发布 `meguri-staging`，必须按 [`staging-deploy-and-rollback.md`](staging-deploy-and-rollback.md) 使用 immutable manifest、dry-run、readiness 和 last-good。`ops/scripts/redeploy-meguri-airi.ps1` 是轻量开发脚本，会跳过测试、health 和 smoke，且带可选远端替换能力；不得把它当作生产事故的标准恢复命令。

以下动作不是普通修复，需用户明确批准：修改 OpenResty/1Panel、服务器防火墙/DNS/证书、替换 AstrBot 插件、迁移/恢复数据库、切换生产流量、删除镜像/容器/volume、安装计划任务。

### 7.3 回滚触发条件

满足任一条件时停止继续打补丁并进入回滚评估：

- 新 build 的 `/health/ready` 与 manifest/release ID 不匹配；
- canary 出现持续 5xx、terminal turn 率下降、SSE gap/重复副作用；
- 身份、tenant、token 或正式 memory 边界无法证明正确；
- 数据库 revision 改变但没有经验证的跨版本恢复路径；
- 修复需要关闭鉴权、公开内部端口或修改 `/opt/astrbot/data` 才能继续。

回滚时先停止新流量并保留日志/事件/build ID，然后恢复 last-good。不要使用 `docker compose down -v`、Alembic `downgrade`、重装 AstrBot 或清空 checkpoint 作为快捷回滚。

## 8. 修复完成标准

只有同时满足以下条件，Agent 才能声明已恢复：

- [ ] 失败入口 health/readiness 通过，且 build/release ID 属于目标环境。
- [ ] 一条最小文本 turn 到达 `completed` terminal。
- [ ] SSE 重连/sequence/checkpoint 无 gap、无重复 ONCE 副作用。
- [ ] 若故障涉及图片/文件，受限的小型测试附件确实由正确模型读取，没有静默降级。
- [ ] 若故障涉及 TTS、日报、Skill、Web、天气或 memory，只验证受影响能力，普通聊天仍可用。
- [ ] AstrBot smoke 使用目标账号/会话边界，没有扩大群聊或操作者 allowlist。
- [ ] 没有 secret 出现在日志、工单、Git diff 或 Agent 回复。
- [ ] 记录实际执行的检查、结果、失败/跳过项和后续风险。

## 9. 文档维护

以下变化发生时必须同步更新本手册：

- 入口域名、端口、反向代理前缀或 loopback 绑定变化；
- 新增/删除外部 provider、MCP、Relay 或本机 service；
- timeout、retry、SSE replay 或 fallback 政策变化；
- AIRI/AstrBot 的 capability 声明变化，尤其是图片、文件、TTS、screen context；
- secret file 路径契约、Compose 环境名、server path 或发布/回滚流程变化。

更新时以源码、配置和对应测试为准，不复制真实 secret，不把一次性的在线状态写成长期事实。
