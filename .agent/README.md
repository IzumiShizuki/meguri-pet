# Meguri Agent Guide

这是 Meguri 项目的 agent 入口。先读本文件，再按任务类型打开下面的专题文档；实现细节仍以代码、契约、模块 README 和 OpenSpec 为准。

## 首次进入项目

1. 查看工作区和分支：

   ```powershell
   git status --short --branch
   git branch --all --verbose --no-abbrev
   ```

2. 判断任务是否属于已有 OpenSpec 变更：

   ```powershell
   openspec list --json
   openspec status --change <change-name> --json
   ```

3. 根据任务打开：

   - [architecture.md](architecture.md)：模块边界、运行时归属、权威数据和请求流。
   - [module-index.md](module-index.md)：按功能定位入口文件、API、包职责和对应测试。
   - [workflows.md](workflows.md)：本机启动、测试、构建、基准和运维命令。
   - [guardrails.md](guardrails.md)：离线默认、秘密、外部状态、Git 和 OpenSpec 安全边界。

4. 先执行与任务最接近的只读检查，再编辑文件；完成后运行对应测试和 `git diff --check`。

## 项目地图

| 路径 | 作用 | 默认边界 |
| --- | --- | --- |
| `services/meguri_core/` | Python/FastAPI 本地核心和现有权威 memory bridge | 默认 fake/mock、离线、端口 `8000` |
| `java/meguri-core/` | Java 21 + Spring WebFlux/LangChain4j 运行时 | 默认 `local-mock`、端口 `18080` |
| `packages/protocol/` | TypeScript 协议、事件、SSE 和 reducer | 跨 adapter 的共享契约 |
| `packages/client-sdk/` | 浏览器/客户端共享 API、turn 和 SSE 客户端 | 只允许配置的 loopback Core URL |
| `adapters/airi/` | AIRI provider/renderer 协议适配 | 不改外部 AIRI checkout |
| `adapters/astrbot/` | AstrBot gateway plugin 和每日报告适配 | 默认 loopback、禁止 TTS/屏幕上下文 |
| `adapters/website/` + `apps/website-client/` | 网站客户端适配器和演示页 | 浏览器端 loopback 默认 |
| `apps/desktop-airi/` | Electron overlay、桌面 HTTP 服务和网页 stage | 本地桌面边界 |
| `local-services/` | 本地 TTS 等可替换服务 | 不依赖云端默认配置 |
| `contracts/` | 跨语言 JSON Schema 和 fixtures | 先更新契约，再更新各语言绑定 |
| `ops/` | 启停、部署、备份、验收和 release gate | 可能改变外部状态，必须显式执行 |
| `training/llm/` | 数据、训练、评估和模型注册脚本 | 大文件/模型产物不进入普通 Git 历史 |
| `docs/` | ADR、契约说明、运行和审计文档 | 解释决策，不取代代码 |
| `openspec/` | 变更提案、设计、规格和任务 | material change 的计划来源 |

## 关键入口

- 根说明：[README.md](../README.md)
- Java 运行时：[java/meguri-core/README.md](../java/meguri-core/README.md)
- 运维和发布：[ops/README.md](../ops/README.md)
- 性能契约：[docs/contracts/performance-contract-v1.md](../docs/contracts/performance-contract-v1.md)
- 性能变更：[openspec/changes/optimize-ttft-fast-path-limited-react/](../openspec/changes/optimize-ttft-fast-path-limited-react/)
- 协议 Schema：[contracts/adapter-protocol/v1/adapter-protocol.schema.json](../contracts/adapter-protocol/v1/adapter-protocol.schema.json)

## 最小验证集

任务未明确指定时，优先执行：

```powershell
D:\environment\anaconda3\envs\py314\python.exe -m unittest discover -v
pnpm test:ts
pnpm test:adapter-contract
git diff --check
```

如果改动触及 Java，再执行 `D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd -B test`（见 [workflows.md](workflows.md)）。测试失败时记录实际失败原因，不要把未运行或被环境阻塞的检查描述为通过。

## 快速原则

- 默认使用 mock/offline 路径；访问真实模型、PostgreSQL、任务调度器或部署脚本都属于显式 opt-in。
- 不把 API key、token、cookie、私钥或本机凭据写入仓库；使用 `.example` 和仓库外 secret file。
- 不把缓存、read model、模型输出或 adapter 作为权威状态；遵守各运行时的 authority boundary。
- 持久化事件后再广播；保持 idempotency、deadline、取消、顺序和 replay 语义。
- 不覆盖用户已有的 dirty worktree；合并前必须确认待合并文件、测试状态和目标分支。
