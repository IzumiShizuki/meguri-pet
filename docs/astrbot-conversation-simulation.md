# AstrBot 模拟会话测试

通过合成 AstrBot 私聊/群聊事件测试真实插件入口、网关、身份映射和 HTTP/SSE 客户端。模拟的消息链交给本地 HTTP 接收器，不调用 QQ API，也不需要安装完整 AstrBot 或 QQ SDK。

## 重复运行

使用现有 Python 环境：

```powershell
& 'D:\environment\anaconda3\envs\py314\python.exe' 'D:\program\meguri-pet\tools\simulate_astrbot_conversations.py'
```

默认采用固定协议响应，操作系统通过 127.0.0.1:0 分配并保留空闲端口。生成随机机器人/用户会话，草稿和检查点位于临时目录；退出时关闭接收器、HTTP 客户端并清理临时状态。JSON 默认写入 .codex-runtime/astrbot-simulation/latest.json，不扫描/修改真实会话。

默认运行 15 个事件场景，核对 11 个条件，包括私聊、群聊、多轮当前信息请求、重复消息、提醒草稿/确认/取消、群内写入拒绝、接收器 503、Core 失败与同消息重试。预设 Core 503 会留下 core_degraded_reply 观察记录；这是负向用例，是否正确处理由对应 check 判断。

仅运行新增回归：

```powershell
Set-Location 'D:\program\meguri-pet'
& 'D:\environment\anaconda3\envs\py314\python.exe' -m pytest tests/test_astrbot_conversation_simulation.py
```

## 使用已有 Core

```powershell
& 'D:\environment\anaconda3\envs\py314\python.exe' `
  'D:\program\meguri-pet\tools\simulate_astrbot_conversations.py' `
  --core-url 'http://127.0.0.1:18080' `
  --core-token-file '已有的外部凭据文件路径' `
  --output 'D:\program\meguri-pet\.codex-runtime\astrbot-simulation\configured-core.json'
```

必须使用回环地址；服务器可通过现有 SSH 连接临时转发到操作系统分配的本地端口。工具不包含 SSH 凭据或自动部署操作。令牌文件需要匹配该 Core 的 AstrBot 权限，内容不会进入报告。

此模式运行私聊、群聊、同一私聊的第二次查询和未启用 Todo 指令，共 4 个场景/5 项检查；使用真实 Core/模型响应，只有平台边界为模拟。合成身份不做跨平台用户绑定，hello 请求正式记忆及写入权限均为 false。禁止在此模式启用模拟 Todo 写入；不会向真实 Shizuki 账号创建测试任务。

## 读懂结果

- mode=protocol-fixture：受控协议响应，验证代码与传输，不证明外部模型、联网或 QQ 平台可用。
- mode=configured-core：现有 Core 的实际响应，仍由本地接收器接收，未经过线上 AstrBot 进程或 QQ。
- checks：明确的传输/控制行为断言。进程在 check 失败时返回非零退出码。
- findings：独立配置/语义观察。即使传输 check 通过，联网关闭或无回执的动作承诺仍会保留。
- accepted_by_local_collector：仅表示本地 HTTP 接收器接受。生成成功、任务写入回执、平台接受与终端到达分别记录，不能互相替代。
- verified_task_receipt：受控写入返回并被真实客户端验证的回执，或此前已验证的同草稿回执。回复文字不构成回执。
- real_platform_sends 恒为 0；resources_closed 检查临时监听线程和状态目录已经释放。

SDK shell 只补齐插件需要的平台接口。该测试不经过传话筒图片渲染、真实 QQ 发送限制、主动消息窗口或设备到达/已读，相关验收需单独进行。

## 本次修复

1. 中文单字“十”在分钟/钟点解析中被提前拒绝，现在可解析“十分钟后提醒我喝水”。
2. session events 使用会话级递增序号。以前客户端遇到同会话上一轮事件就报跨回合错误；现在先验证会话、序号和重放策略，推进游标后只应用当前回合的数据与终态。跨会话事件仍拒绝。

修复在源码中，是否应用到线上取决于插件部署版本。本工具不会替换线上插件、启用联网或改变提醒配置。
