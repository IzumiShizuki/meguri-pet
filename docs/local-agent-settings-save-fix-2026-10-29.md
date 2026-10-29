# 工作 Agent 配置保存失败修复

## 根因与修复

现场显示“保存失败，请检查本地配置目录”。这来自 `SettingsView.vue` 的通用异常捕获，并不是文件写入错误的证据。

保存时直接把 `agentDraft.value`（Vue 响应式 Proxy）传入 Electron context bridge。隔离 Electron 43 实测：同字段普通对象到达 IPC，Vue Proxy 报 `An object could not be cloned`，IPC 保存处理器调用次数保持 0。因此这次失败发生在写盘之前。

修复内容：

- 设置页只发送明确的普通布尔值/字符串对象；保存反馈独立于连接测试，并区分成功、输入校验、文件写入和未确认传输结果。
- 原有三个设置 IPC 处理器抽成窄模块以便测试，继续使用原主进程 `trustedSettingsRenderer` 的窗口身份和预期 URL 校验，没有新 CLI、HTTP 或绕过入口。
- 新输入校验拒绝非布尔开关、非本地绝对 Harness 目录；既有旧版配置仍可读。
- 保存使用独立临时文件和原子替换。替换失败保留原文件，不应用未提交配置；清理失败不误报已提交写入失败。
- 运行态异常应用失败时明确提示“配置已保存，但运行状态未能更新”，不把它伪装成文件写入失败。

没有更改 OS 权限、UAC、ACL、凭据、模型选择或自动启动。没有重启 Core 或 Harness。

## 验证

| 检查 | 结果 |
| --- | --- |
| 新增保存/IPC/运行态单元测试 | 17/17 通过 |
| 全量桌面单元测试 | 67 个文件，500/500 通过 |
| 原 preload、单实例、托盘 smoke | 全部 `ok:true`，测试命令退出码 0 |
| 新增真实 Electron 设置 smoke | 实际 SettingsView、真实 preload 和保存处理器的 7 个场景通过 |
| 类型检查、Vite 构建 | 通过；存在既有的大 chunk 提示 |
| `git diff --check` | 通过 |

新增 smoke 通过真实 Vue 表单验证保存、错误反馈、关闭重开读回、主动连接探测，以及另一个窗口/意外来源拒绝。它使用临时 profile、假执行器，并阻断网络；不是用户现场保存成功的证明。

首轮沙箱单元测试为 498/500，本地回环 HTTP 和无麦克风语音加载受运行环境限制；在正常本机环境复核后 500/500 通过。原 preload smoke 遗留一个本次创建的 Vite 子进程，核对 PID、父进程、启动时间和项目路径后只清理了该测试子进程，最终命令退出 0；用户原有 Vite、Core 和 Harness 未停止。

重跑新增设置回归：

```powershell
Remove-Item Env:\ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
pnpm --filter @meguri/desktop-meguri test:agent-settings
```

输出中的临时目录保留 `result.json` 及测试配置供检查，不使用用户 profile。

## 现场与交付边界

当前构建已生成，但原桌面主进程仍在运行，尚未确认加载本轮新代码。设置窗口此前点击/Tab 没有可验证效果，最新目标窗口画面又被其他应用遮挡，因此没有继续盲点、调试注入、直接写生产配置或强制终止桌面进程。

最小下一步是用户把 **Meguri 设置窗口置于前台，按 Ctrl+R 刷新一次**，再进入“工作 Agent”。关闭设置窗口只会隐藏它，不能当作重新加载。刷新可加载前端序列化修复；本轮后台校验加强需下次正常重启桌面应用才加载。出现 UAC 时应由用户决定，不把此前修复请求当作新的 UAC 确认。

现场保存、读回、真实连接测试和可见任务卡仍待验收。此前 Codex/Harness 两条独立任务链已真实通过，Harness 使用已安装的 `0.1.5-rc.1`；不代表 `0.2.0-rc.2` 兼容问题已修复。

证据保存在忽略目录 `reports/agent-connection-check-20261029/`：`save-clone-reproduction.json`、`settings-smoke.stdout.log`、`save-fix-unit.log`、`save-fix-desktop-test.log`、`recheck-live-status.json` 和 `final-report.md`。没有提交登录令牌、cookie 或实际凭据。
