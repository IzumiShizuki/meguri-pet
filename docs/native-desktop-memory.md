# 原生桌面正式记忆

## 当前状态：2026-10-06

已按所有者授权接入服务器 `111.228.35.186:5432`，使用独立 `meguri_memory` 数据库，pgvector `0.8.5`，迁移版本 `20260729_0007`。桌面网关、Java Core、Python 记忆桥的受保护状态及列表读取均已连通。部署记录见 `native-memory-server-feedback-2026-10-06.md`。

现有桌面设置已开启正式记忆和每 10 轮自动整理。候选事实仍通过设置页审核后成为长期记忆。当前采用文本检索，embedding 与 reranking 均关闭。

## 为什么以前没有使用

原生桌面第一阶段提交 `f068f85` 的 `add-native-meguri-desktop-frontend/design.md` 将 Core 记忆变更列为非目标。原生 `conversation.ts` 因而在握手中主动发送 `formal_memory: false` 与 `formal_memory_write: false`。这不是权威记忆系统尚未实现：Python `native_pgvector` 已提供候选审核、长期回忆、版本、软删除、恢复和审计。

2026-10-05 的只读检查还发现：本机 Java 18080 的 `/health` 正常，`memory_provider` 值仅表示已装配 `PythonMemoryGateway` 类；8000 的 Python 服务不可达，本地启动器没有配置数据库与内部桥令牌。`D:/environment/secrets/meguri` 中只有桌面 Core 令牌；仓库 `ops/secrets` 中只有说明与模型 API key 文件，没有可复用正式记忆数据库凭据。不能仅更改握手布尔值，也不能用浏览器历史或 fake provider 冒充正式记忆。

## 已实现链路

原生设置 → `desktopFetch` → 既有 Node `/core` 网关 → Java `/v1/memory/desktop/*` → Python `/internal/memory/desktop/*` → `native_pgvector`。

受信网关注入宿主令牌与固定当前用户身份，渲染器不会取得令牌。Java 在本地 mock 模式也要求桌面身份令牌、正确 tenant/user/client/session，远程模式继续受服务端用户与客户端允许名单约束。Python 仅接受既有内部桥令牌和允许的内部来源，并验证权威 provider、租户以及候选/记忆归属。请求体不能指定 actor、user 或管理员身份。

设置可查看正式/候选/已删除记忆，确认或拒绝候选、软删除、恢复、导出。批准仍经过现有安全与冲突规则。使用开关共同控制正式记忆回忆与候选写入；关闭后保留数据并停止后续使用。

当前会话整理从 Java 当前用户会话快照取最近最多 20 条消息，每条最多 2000 字符，过滤疑似凭据，最多产生 3 条候选。稳定 session/checkpoint 定义幂等操作；Python 将第一次结果保存在已有幂等存储中，重试不会产生第二组候选。自动间隔为 3–30 轮成功对话。

## 启动已有权威环境

需要准备隔离的 PostgreSQL/pgvector 应用数据库及与现有版本相符的迁移。2026-10-06 已获授权使用现有服务器管理员配置完成独立数据库和角色的初始化；运行时只使用 Meguri 应用角色。数据库 URL 和内部桥令牌位于仓库外文件，同一令牌文件交给 Java 和 Python。

启动器默认读取 `D:/environment/secrets/meguri/memory-database-url.txt` 和 `internal-bridge-token.txt`。两份文件存在时自动启用记忆桥；明确设置 `MEGURI_MEMORY_BRIDGE_ENABLED=false` 可以关闭。显式环境变量和启动参数优先，普通桌面启动无需重复填写数据库配置。迁移账号连接文件 `memory-migration-database-url.txt` 只供维护，Core 和 Python 服务不读取。

在启动 Core 的同一 PowerShell 会话设置：

```powershell
$env:MEGURI_DATABASE_URL_FILE = 'D:\environment\secrets\meguri\memory-database-url.txt'
$env:MEGURI_INTERNAL_BRIDGE_TOKEN_FILE = 'D:\environment\secrets\meguri\internal-bridge-token.txt'
$env:MEGURI_TENANT_ID = 'meguri-local'
$env:MEGURI_DESKTOP_TENANT_ID = 'meguri-local'
$env:MEGURI_MEMORY_BRIDGE_ENABLED = 'true'
# 只检查文件路径；不启动、不访问数据库：
& .\ops\scripts\start-meguri-memory-bridge.ps1 -CheckConfiguration
# 配置具备后显式启动；无需安装工具链：
& .\ops\scripts\start-meguri-core.ps1
```

独立桥入口 `start-meguri-memory-bridge.ps1 -Foreground` 也会要求这些文件，不降级 fake。实际启动会检查数据库 revision 和 pgvector，并在后台桥的受保护状态就绪后返回。`-CheckConfiguration` 仅检查路径。

本机配置默认使用 `MEGURI_DATABASE_REVISION=20260729_0007`、`MEGURI_EMBEDDING_MODEL_REVISION=dashscope-text-embedding-v4-2048-r20260725`、`MEGURI_EMBEDDING_BACKEND=disabled` 和 `MEGURI_RERANK_BACKEND=disabled`。旧 handoff 和 release 常量中的 `20260728_0006` 不适用于这次部署；启动器显式指定实际迁移 head。

Java 宿主认证配置为 `MEGURI_DESKTOP_AUTH_TOKEN_FILE`，启动器默认复用现有 `D:/environment/secrets/meguri/desktop-core-token.txt`；网关使用 `MEGURI_CORE_AUTH_TOKEN_FILE`。自定义这两个路径时必须指向同一凭据。Hosted Core 还须配置 `MEGURI_FORMAL_MEMORY_ALLOWED_USERS=local-airi-user` 和 `MEGURI_FORMAL_MEMORY_ALLOWED_CLIENTS=desktop_pet`，以及一致的 tenant。配置变更需要重启相关 Core/网关进程。

缺少桥、数据库、认证或服务端授权时，设置显示具体状态，对话按无正式记忆继续。初次政策同意与 AI 对话许可另由隐私设置控制；撤回后停止新对话，关闭本地保存不会擅自删除已有记录，清理使用明确的“清空本地记录”操作。

## API

Java GET：`status`、`records`、`candidates`。POST：`candidates/{id}/review`、`memories/{id}/delete`、`memories/{id}/restore`、`export`、`summary`。变更须带 `X-Request-ID`；整理另须已授权 Hello binding 的 `client_instance_id` 和正整数 `checkpoint`。所有路径均位于 `/v1/memory/desktop`，仅由受信桌面网关调用。
