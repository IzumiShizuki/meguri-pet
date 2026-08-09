# Safety and Change Guardrails

## Offline and secret boundary

- 默认运行 mock/offline provider；不要因为测试失败就自动切换到真实 LLM、远程 Core、PostgreSQL、Redis、Kafka、MemoryOS 或 Bilibili 账户数据。
- API key、token、cookie、SESSDATA、私钥和数据库密码只能来自仓库外的 secret file 或显式本地环境；不得写入源码、日志、OpenSpec、`.agent` 或提交信息。
- `.env.*`、`ops/secrets/`、模型 checkpoint、报告输出和本机缓存按 `.gitignore` 处理；提交前检查 `git status` 和新增文件内容。
- 遇到私有配置时只验证变量名、路径和 schema，不回显凭据值。

## Authority and data safety

- 保持 `tenant/user/client/session` 绑定、ACL、trust/provenance、formal-memory consent 和 request identity；不接受 adapter 自带的“可信”预算、权限或执行信号。
- read model、cache、检索 bundle、模型回复、工具 observation 和桌面 artifact 都是可重建或不可信输入，不能静默升级为正式 persona、relationship 或 memory 写入。
- Everything 和桌面文件能力默认只返回元数据；路径必须经过 allow-list、secret/path filter 和二次检查；不要为了“方便调试”读取用户文件内容。
- Bilibili 每日报告只允许既定的只读 MCP/浏览器历史边界；不读取 raw account database，不下载视频/字幕/页面内容，不把凭据传给 Meguri。

## Runtime invariants

- 事件持久化成功后才广播；保持单调序列、SSE replay、idempotency key、deadline、取消传播和 terminal state 一致。
- 首个有意义的 delta、`semantic.completed`、artifact metadata 和 provider usage 的定义必须遵循现有契约；不要把 client render 时间、平台投递时间或猜测值冒充 server TTFT。
- `FAST/THINK/AGENT` 与 `NONE/FAST/SLOW` retrieval mode 保持正交；AGENT 只能由服务器权威决策进入，并受能力、审批、预算、重复动作和 deadline 限制。
- 默认关闭性能 rollout flag；没有匹配的 baseline 和 workload 时使用 `NOT_MEASURED`。

## Git and OpenSpec workflow

1. 修改前记录 `git status --short --branch`，保留用户已有的 dirty changes。
2. material code/API/behavior/architecture/test change 先创建或继续 `openspec/changes/`；纯文档/工具变更可以设置 `skip_specs: true`，但仍保留 proposal/design/tasks 的可追踪记录。
3. 不使用 `git reset --hard`、`git checkout --` 或不受限的递归删除来“清理”工作区。
4. 提交前查看 `git diff --stat`、`git diff --check`、新增文件和敏感字段；只 stage 任务范围内的文件。
5. 合并前确认：当前分支提交已完成、工作区可解释、目标分支正确、测试结果已记录、OpenSpec verify 的结论已知。

## External-state operations

以下操作都不是普通构建步骤，必须单独确认目标和影响：

- deploy/rollback、远程 SSH/API、生产配置、数据库迁移、备份恢复；
- 安装或修改 Windows scheduled task；
- 推送 Git、创建 PR、覆盖共享分支；
- 启用真实 provider、写入正式 memory、发送平台消息或上传报告。

先读 `ops/runbooks/` 和相关脚本的 `-Help`/preview 选项；能做 dry-run 或 read-only 检查时优先使用。

## When something fails

- 先保留完整错误、命令和环境边界，判断是代码失败、依赖缺失、端口占用、外部服务不可用还是测试未覆盖。
- 不用扩大权限、关闭安全检查、提交本地生成物或改远程配置来掩盖失败。
- 对未运行、被环境阻塞或仍有 OpenSpec 未完成任务的检查，交付时明确标记为未验证/阻塞，而不是“通过”。
