# 远端 DeepSeek + Meguri 角色层（本地运行）

## 运行边界

本地链路固定为：

```text
AIRI -> http://127.0.0.1:18080 -> Meguri Java Core -> https://api.deepseek.com
```

- AIRI 只连接本机 Java Core，不直接请求 DeepSeek，也不保存 API Key。
- Java Core 负责注入 `configs/meguri_system_prompt.txt`、关系/模式状态、经审核的 Lore RAG，并校验结构化回复。
- DeepSeek API Key 只由 Core 从仓库外的绝对路径文件读取；不要把 Key 写入环境变量、AIRI 配置或本仓库。
- 这条官方托管模型链路没有挂载 Meguri LoRA。当前的“Meguri 感”来自稳定角色提示词与知识库检索；若以后需要 LoRA，必须部署自有 adapter 推理网关，再把 Core 指向该网关，不能把本地 LoRA 直接附加到官方 DeepSeek API。

## 1. 准备仓库外密钥文件

建议在 `D:\environment\secrets\meguri\deepseek-api-key.txt` 中只保存一行真实 API Key。该文件不能位于 `D:\program\meguri-pet` 内，也不能提交到 Git。

Core 会拒绝相对路径、空文件以及内联的 `MEGURI_LLM_API_KEY`。可先创建仓库外目录，再用本地编辑器写入密钥：

```powershell
New-Item -ItemType Directory -Force -Path 'D:\environment\secrets\meguri'
notepad 'D:\environment\secrets\meguri\deepseek-api-key.txt'
```

## 2. 设置本地进程环境

以下配置适合桌宠低延迟对话。`deepseek-v4-flash` 是当前推荐的交互模型；显式关闭 thinking，避免桌面聊天默认进入较慢的推理模式。`json_object` 与 `max_tokens=1200` 配合 Core 注入的 JSON 示例和本地 schema 二次校验。

```powershell
$env:MEGURI_CORE_BIND_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '18080'
$env:MEGURI_LLM_PROVIDER = 'openai-compatible'
$env:MEGURI_LLM_BASE_URL = 'https://api.deepseek.com'
$env:MEGURI_LLM_MODEL = 'deepseek-v4-flash'
$env:MEGURI_LLM_API_KEY_FILE = 'D:\environment\secrets\meguri\deepseek-api-key.txt'
$env:MEGURI_LLM_RESPONSE_FORMAT = 'json_object'
$env:MEGURI_LLM_THINKING = 'disabled'
$env:MEGURI_LLM_MAX_TOKENS = '1200'
$env:MEGURI_LLM_MAX_CONCURRENCY = '4'
```

对应的无密钥示例在 `ops/env/deepseek.local.env.example`。该文件只是配置清单，不会被 Spring Boot 自动加载。

## 3. 启动 Java Core

在已设置上述环境变量的 PowerShell 中运行：

```powershell
cmd /d /c "call D:\environment\activate-zhuowang-system.cmd >nul && cd /d D:\program\meguri-pet\java\meguri-core && D:\environment\maven\runtime\apache-maven-3.9.16\bin\mvn.cmd -B spring-boot:run"
```

确认本地 Core 健康：

```powershell
Invoke-RestMethod 'http://127.0.0.1:18080/actuator/health'
```

随后把 AIRI 的 Meguri Core 地址设为 `http://127.0.0.1:18080`。AIRI 中展示的模型名只是 UI 信息；实际远端模型由 Core 的 `MEGURI_LLM_MODEL` 管理。

## 4. 验证边界

建议先用 AIRI 连续验证三类输入：普通任务、疲惫/安慰等角色语境、与当前关系阶段不匹配的设定问题。检查回复是否先解决任务、再体现克制的 Meguri 关怀，同时确认不相关 RAG 不会被强行注入。

截至本文档建立时，仓库没有用户的真实 DeepSeek API Key，因此没有执行会计费的认证 smoke。配置真实 Key 后的首次对话会调用远端 API，可能产生费用；应由用户确认后再执行，并以真实返回、Core 日志和用量指标作为验证依据。

模型名、thinking 参数和 JSON Output 的当前行为以 DeepSeek 官方文档为准：

- [Models & Pricing](https://api-docs.deepseek.com/quick_start/pricing)
- [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)
- [JSON Output](https://api-docs.deepseek.com/guides/json_mode/)
