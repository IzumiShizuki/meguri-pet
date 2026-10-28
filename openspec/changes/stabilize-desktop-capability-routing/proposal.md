## Why

普通的“帮我查看一下今天 Codex 是否更新了”被提醒解析器误判为定时动作，导致消息没有提交给 Core。联网失败缺少可见诊断，思考时缺少阶段进度，多个小功能组合后的行为没有被现有单元测试覆盖。

## What Changes

- 收紧提醒与定时动作入口，普通查询、联网、文件内容和 Todo 查询保持可发送。
- 检查真实联网链路，保留搜索成功、空结果、关闭、超时等诊断，保留资料引用，并让界面显示本轮检索状态与失败原因。
- 展示公开的执行阶段、工具进度和结果摘要，支持折叠；不暴露模型内部推理或敏感上下文。
- 检查取消、重试、断连、待办核对、附件和语音组合，增加针对真实症状的回归测试。
- 记录全量测试结果和本机运行环境限制，并由 Luna 实施已确认修复。

## Capabilities

### New Capabilities
- `desktop-capability-routing`: 提醒、Todo、联网与普通聊天的无冲突路由、发送恢复和可见执行进度。

### Modified Capabilities

无。当前主规格目录为空，本次通过新增跨能力规格约束现有行为。

## Impact

Desktop reminder parser/flow, ChatDock, conversation store, Core web retrieval adapter and orchestration diagnostics, local launcher and focused regression tests. Reuse existing runtime dependencies; preserve existing unrelated working-tree changes. No remote Todo or message writes during automated live probes.
