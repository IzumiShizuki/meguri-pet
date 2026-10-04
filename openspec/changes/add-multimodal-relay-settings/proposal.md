## Why

Meguri 已具备受限图片/PDF附件和多模态 fallback 路由，但当前只能通过环境变量配置视觉供应商，用户无法在桌面应用中安全地填写中转站地址、模型和密钥。需要一个可信、可见且可验证的设置入口，让后续接入任意 OpenAI-compatible 视觉中转站不再依赖手工修改脚本。

## What Changes

- 在现有 Meguri 设置首页增加“多模态”入口，展示是否启用、是否已保存密钥以及当前模型。
- 提供启用开关、OpenAI-compatible Base URL、模型 ID 和 API Key 表单；密钥输入只在当前表单中短暂存在，保存后不回传明文。
- 在 Electron 主进程集中校验并保存非敏感配置与仓库外密钥文件，拒绝非 HTTPS 的远程地址、无效模型名和不完整启用配置。
- 提供显式的视觉连接测试，用一张极小测试图验证端点确实接受标准 `image_url`，并给出可恢复的错误反馈。
- 让本地 Core 启动脚本读取已保存配置并设置现有多模态 fallback 环境变量；普通文本模型保持不变，图片/PDF 才进入中转模型。
- 保存配置后明确提示需要重启 Meguri 才能应用，不静默终止或替换正在进行的会话。

## Capabilities

### New Capabilities

- `desktop-multimodal-relay-settings`: 用户可从 Meguri 设置窗口安全配置、检查并启用一个 OpenAI-compatible 多模态中转站，本地启动器可据此激活已有视觉附件路由。

### Modified Capabilities

- None.

## Impact

- `apps/desktop-meguri` 的设置页面、样式、Electron IPC/preload 合约和主进程配置存储。
- `ops/scripts/start-meguri-core.ps1` 的本地多模态 fallback 环境加载。
- 桌面端单元、preload、类型与构建验证，以及本地 Core/Gateway/Electron 重启验证。
- 不把 API Key 写入仓库、桌面偏好 JSON、renderer localStorage 或日志；不改变现有 DeepSeek 文本模型配置。
