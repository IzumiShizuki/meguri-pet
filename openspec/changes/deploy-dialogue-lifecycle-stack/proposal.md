## Why

用户已明确授权部署前轮通过验证的修复，并要求部署后继续修改问题代码。当前本地与远程 Core 均为旧版本，远程显式关闭联网，输入检查尚未启用，源码测试结果没有进入实际对话链路。

## What Changes

- 部署经过 SHA256 标识的 Java Core、桌面构建及 AstrBot 网关更新。
- 在实际 Core 配置中启用联网与独立输入检查，保留既有密钥、身份和存储边界。
- 增加可回滚的候选验收和配置核验，避免健康但缺少能力的旧部署继续被复用。
- 对部署后复现的问题补回归、修复、重新构建并验收。

## Capabilities

### New Capabilities

- `dialogue-deployment-activation`: 实际部署的版本、能力配置、回滚与对话验收。

### Modified Capabilities

无。

## Impact

本机 Java/Electron/网关进程、远程现有 meguri-staging Core 容器和 AstrBot 网关插件，以及对应启动/部署脚本。测试使用模拟身份和本地 collector，不向 QQ 用户或群发送验收消息，不开启未配置的真实 Todo 写入，不迁移正式用户数据库。
