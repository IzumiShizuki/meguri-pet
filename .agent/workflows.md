# Development and Verification Workflows

以下命令面向当前 Windows 工作站。优先复用 `D:\environment` 中的工具链，不要修改或重装该目录。

## Toolchain

```powershell
$meguriPython = 'D:\environment\anaconda3\envs\py314\python.exe'
$meguriNode = 'D:\environment\nodejs\runtime\node-v24.17.0-win-x64\node.exe'
$meguriMaven = 'D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd'
$meguriJavaHome = 'D:\environment\jdk\temurin-21\jdk-21.0.11+10'
```

仓库级开发环境也提供：

```powershell
. 'D:\environment\activate-dev-env.ps1'
```

Java 模块要求 JDK 21；如果环境未激活，使用 `$meguriJavaHome` 和 `$meguriMaven` 的显式路径。Node workspace 要求 Node 24，包管理器版本见根 `package.json` 的 `packageManager` 字段。

## Python Core

默认路径是本地 mock/offline Core，不需要 API key、PostgreSQL、Redis、Kafka 或 MemoryOS：

```powershell
& $meguriPython -m uvicorn services.meguri_core.app:app --host 127.0.0.1 --port 8000
```

Python 测试：

```powershell
& $meguriPython -m unittest discover -v
& $meguriPython -m pytest
```

常用契约和一致性检查：

```powershell
& $meguriPython scripts/run_adapter_contract_fixtures.py
& $meguriPython scripts/check_adapter_contract_consistency.py
```

## Java Core

Java 模块在 `java/meguri-core/`，默认 `local-mock`，端口 `18080`：

```powershell
$env:JAVA_HOME = $meguriJavaHome
$env:Path = "$env:JAVA_HOME\bin;D:\environment\maven\runtime\apache-maven-3.9.16\bin;$env:Path"
Push-Location java/meguri-core
& $meguriMaven -B test
& $meguriMaven -B spring-boot:run
Pop-Location
```

真实 OpenAI-compatible provider、PostgreSQL profile、web search、Everything 和 weather 都是显式配置的 opt-in；配置方式和边界以 [java/meguri-core/README.md](../java/meguri-core/README.md) 为准。不要在命令行、日志或 `.agent` 中写入 secret 内容。

## TypeScript, adapters, and desktop

安装依赖前先确认工作区状态；已有 `node_modules/` 时不要无理由重装：

```powershell
pnpm install
pnpm test:ts
pnpm test:adapter-contract
```

根脚本会运行协议、client SDK、renderer、AIRI、TTS、website 和 adapter contract 测试。单模块调试可使用：

```powershell
Push-Location apps/desktop-airi
pnpm web
# 另一个终端中，确认 Core 已启动后再按需运行：pnpm overlay
Pop-Location
```

桌面/本地服务的其他入口见 `apps/desktop-airi/package.json`、`local-services/*/package.json` 和 `ops/scripts/start-*.ps1`。普通浏览器和 Electron 的文件打开、资源选择、origin/IPC 约束不能合并成一个无条件的路径。

## Performance and evidence

性能变更先检查契约、fixtures 和 OpenSpec 任务：

```powershell
openspec status --change optimize-ttft-fast-path-limited-react --json
Get-Help .\scripts\benchmark_ttft.ps1 -Full
```

性能报告必须带 release/commit、workload、model/provider、execution/retrieval mode、client、sample size 和 P50/P95/P99。没有可比 baseline 时写 `NOT_MEASURED`，不填写推测的毫秒或提升百分比。

## Operational scripts

`ops/scripts/` 同时包含本地启停、定时任务、部署、备份、回滚和 acceptance gate。先区分：

- 本地开发：`start-meguri-core.ps1`、`start-meguri-airi.ps1`、`start-meguri-overlay.ps1`、`start_local_tts.ps1`。
- 只读检查：`check_*`、`generate_release_manifest.py`、`check_release_manifest.py` 等。
- 外部状态操作：`deploy_staging.py`、`rollback_staging.py`、`install-*-task.ps1`、数据库/备份脚本。

后两类可能触碰远程服务、Windows Task Scheduler、数据库或生产 gate；只有用户明确要求并读完对应 runbook 后才能执行。

## Completion checklist

```powershell
git diff --check
git status --short --branch
openspec validate <change-name> --type change --no-interactive
```

然后运行受影响语言/模块的测试，并在交付说明中列出实际执行的命令、失败或跳过的检查及原因。
