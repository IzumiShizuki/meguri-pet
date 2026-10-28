# 本机 Turn Runtime 持久化启用与验收

适用于 Windows 原生桌面 → 本机 Java Core。反馈界面、离线训练数据桥和隔离恢复验收已实现；正式启用需要单独的 Runtime 数据库连接。正式记忆库和它的应用/迁移账号继续由现有记忆服务管理。

2026-10-08 已完成本机启用：复用服务器 `111.228.35.186:5432`，独立库 `meguri_turn_runtime` 与受限角色 `meguri_turn_owner` 已建立。管理员凭据只从既有同步配置 `D:\program\shizuki-site\resouces\yaml\common-config.yaml` 读取用于建库；实际服务使用 `D:\environment\secrets\meguri\turn-runtime-database-url.txt`。ACL 限本机所有者、SYSTEM 和 Administrators。此状态针对本机桌面 Core；以下建库模板供新环境使用，现有库不要重复创建或覆盖。

实际 Core、网关和隔离用户配置的原生 Electron 客户端重启验收已通过：同一 Turn、13 个事件、会话快照、待反馈、保存回执保持；合成反馈已撤回，未产生正式记忆写入。专用库中两套隔离恢复测试也通过，临时 schema 已清理。详细证据见 [学习与实施记录](../../docs/p0-learning-and-runtime-recovery-2026-10-08.md)。Windows PowerShell 5.1 和 PowerShell 7 均已验证配置脚本。

## 1. 数据库准备

建议由数据库管理员建立独立库 `meguri_turn_runtime` 和独立登录角色 `meguri_turn_owner`。当前 Java Runtime 会在启动时初始化、升级自己的表，因此角色需要在该库内创建/修改自己的表；它不需要超级用户、建库、建角色或记忆库权限。现有记忆迁移角色没有 CREATEDB / CREATEROLE，不能完成这一步。

管理员先核对目标 PostgreSQL 实例及命名是否空闲，再在管理员客户端执行下面的模板。不要将密码填入此文档或提交 SQL 文件；通过客户端的密码管理功能设置角色密码。创建数据库需要在事务外执行。

```sql
-- 管理员预检：期望均为 0；已有同名对象时先核对所有权，勿覆盖。
SELECT count(*) FROM pg_roles WHERE rolname = 'meguri_turn_owner';
SELECT count(*) FROM pg_database WHERE datname = 'meguri_turn_runtime';

CREATE ROLE meguri_turn_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
-- 此处通过管理员客户端单独设置密码；psql 可使用 \password meguri_turn_owner。
CREATE DATABASE meguri_turn_runtime OWNER meguri_turn_owner;
REVOKE ALL ON DATABASE meguri_turn_runtime FROM PUBLIC;
GRANT CONNECT ON DATABASE meguri_turn_runtime TO meguri_turn_owner;
```

切换到新建的 `meguri_turn_runtime` 库后执行：

```sql
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO meguri_turn_owner;
```

这里只准备对象与权限，不导入记忆、反馈或评测数据。不在正式记忆库的 public 中初始化 Runtime 表。测试连接可以单独拥有在测试库内创建临时 schema 的权限；测试脚本的连接不能自动变成正式启动连接。

## 2. 本机连接文件

将专用 PostgreSQL URI 保存在仓库外的：

`D:\environment\secrets\meguri\turn-runtime-database-url.txt`

也可通过 `MEGURI_RUNTIME_DATABASE_URL_FILE` 指向另一个绝对路径。URI 中的用户名/密码按 URL 编码，数据库名使用字母、数字、下划线或连字符；可选查询参数只允许 `sslmode`。文件由本机用户可读，避免共享给其他账户。无需把密码或完整 URI 发到聊天或 Notion。

启动前运行配置检查：

```powershell
./ops/scripts/test-runtime-postgres-config.ps1
```

启动器检测专用文件后选择 `postgres` profile、Turn journal、事件、会话图和任务队列的 PostgreSQL 实现。离线/真实模型配置仍按当前 provider 设置；持久化不要求切换到真实模型。显式 `MEGURI_TURN_JOURNAL_MODE=postgres` 缺失或非法配置时拒绝启动，数据库不可用也拒绝启动。

## 3. 可重复的隔离验收

```powershell
./ops/scripts/check-runtime-postgres-recovery.ps1 -DatabaseSecretFile <绝对路径的测试连接文件>
```

脚本执行两套 opt-in 测试：

1. 存储重建与独立 writer/reader JVM：事件 ID/序列、完成话轮幂等身份、上下文、待反馈、outbox 去重、任务租约恢复。
2. 三次完整 Spring Core 进程启动：带鉴权的 HTTP 创建/查询、SSE 回放与 Last-Event-ID、恢复后的会话上下文、待反馈与保存回执、撤回、生成中断终态、过期任务租约。

测试使用随机 `meguri_p0_recovery_*` / `meguri_p0_core_*` schema，search_path 只指向该 schema；反馈和用户目录为临时夹具，模型为离线 mock，正式记忆写入为关闭。finally 结束所有测试 Core，删除本次精确 schema 并检查清理。测试失败保留临时 Core 日志用于诊断。不会连接正在使用的 Core、使用真实聊天数据或重启真实桌面。

## 4. 正式桌面验收

在专用连接准备好、明确实际本机 Core 的进程与端口及重启窗口后，使用现有 `start-meguri-core.ps1` / 桌面启动器：

1. 确认日志选择 PostgreSQL，Core health 与 build 正确，原生客户端指向本机 Core。
2. 创建合成测试会话，开启「监督与训练」，产生待反馈；记录 Turn ID、完成回复与最后事件序列。
3. 在窗口内重启 Core。重新打开同一会话，确认完成回复、上下文、待反馈恢复；相同提交键不创建第二个 Turn。
4. 明确同意并保存反馈，核对回执；再次重启后确认回执仍在，撤回后状态与排除标记正确。
5. 检查 outbox/job 的原任务身份与租约恢复，不新增正式记忆测试写入，不启动 GPU 训练。

上述检查成功后才能将 OpenSpec 任务 4.5 改为完成，并更新 Notion 的「正式启用」状态。隔离测试通过不能替代实际客户端配置验收。

## 5. 显式回退

```powershell
$env:MEGURI_TURN_JOURNAL_MODE = 'in-memory'
./ops/scripts/start-meguri-core.ps1
```

回退清除本进程 Runtime 连接配置并选择内存实现；这个模式无法在 Core 重启后恢复后续新 Turn。它不会删除已有 Runtime 库或磁盘反馈。恢复持久化需取消内存覆盖并使用原专用连接；不要删除数据库作为回退步骤。
