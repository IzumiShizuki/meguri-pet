# Meguri 20.x 剩余事项

更新时间：2026-07-29

## 状态基线

- 20.0-20.7 本地代码与自动化契约已实现，不再保留旧审计中的 P0/P1 代码缺口。
- 唯一最新实施状态以 `docs/notion-20-implementation-plan-2026-07-28.md` 为准。
- `docs/notion-20-audit-2026-07-28.md` 仅保留为实现前的历史差距快照。
- 当前没有发现新增的 TODO、FIXME、空实现或必须在提交前补齐的代码阻断项。

## 上线前环境验收

以下事项已有本地接口、适配器或契约测试，但仍缺真实环境证据，不能标记为生产完成：

1. 在真实 PostgreSQL + pgvector 上验证迁移、GIN/HNSW 查询计划、并发锁、事务失败、重启恢复、lease/CAS、Outbox 与 Effect Ledger。
2. 使用真实 Notion 工作区验证分页、限流、删除、ACL 变化、增量同步、定时任务和凭据轮换。
3. 使用真实 MCP Server 验证认证、`list_changed`、断连重连、恶意输出、版本 drain 和最小权限。
4. 使用真实 Remote Agent 验证跨进程提交、callback/polling、长任务、重复投递、取消、网络故障和 OS/容器级隔离。
5. 使用真实 Provider 验证原生流 TTFT、流中断、部分文本、取消、绝对 deadline 和稳定 failure code。
6. 让 AIRI、AstrBot、Website 连接同一套 Core，验证真实身份、过期 token、跨端正式记忆、session 隔离、断线重连与 ONCE 副作用去重。
7. 完成生产 secret 扫描、日志脱敏、备份恢复、容量、监控告警、灰度、回滚和故障演练。

## 产品与客户端边界

- `apps/desktop-airi` 仍是独立的集成诊断面；正式 AIRI 接入以 `D:\program\airi-meguri\packages\meguri-airi-adapter` 为准。
- AIRI Adapter 的协议、checkpoint、重连和事件映射已完成本地验证；PNG-first 是否继续作为阶段方案、何时切换原生 `@proj-airi/stage-ui-live2d`，仍是产品选择和真实模型资产验收事项。
- AstrBot Gateway 的路由、身份边界、远程任务预览/确认和 durable checkpoint 已完成本地验证；真实平台账号、群聊、Relay 和重复投递仍需部署环境 E2E。

## 最新自动化快照

- Java 21 Maven：404 tests，0 failures，0 errors，1 skipped。
- Python：376 passed，8 skipped；本次变更文件 Ruff 与 compileall 通过。
- Alembic：单一 head `20260729_0007`，离线 `0001 -> 0007 -> base` 全链升降级通过。
- 根 TypeScript：48/48 passed。
- AIRI Adapter：31/31 passed，严格 TypeScript 与指定 ESLint 通过。
- Website、AstrBot、AIRI 使用同一 canonical fixture，三端契约验证通过。
- AstrBot 插件 ZIP 已生成并通过打包检查。

## 已知仓库基线

- AIRI 从仓库根运行 Vitest 会在发现测试前因上游配置引用不存在的 `apps/server` 失败；从 `packages/meguri-airi-adapter` 运行完整测试为 31/31。
- 全仓 Ruff 仍有 41 个历史问题，位于本轮未触碰的旧插件、脚本和桥接代码；本次新增与修改的 Python 文件均已通过 Ruff。
