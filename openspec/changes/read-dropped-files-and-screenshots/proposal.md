## Why

Meguri 的原生前端目前只能通过 `@`/Everything 发送文件路径等元数据，拖入文件和从 Snipaste 粘贴截图都不会把实际内容交给模型。用户已经明确选择文件或粘贴图片时，应当把这个动作视为一次有边界的读取授权，并复用 Core 已有的文档和多模态安全读取能力。

## What Changes

- 在聊天区接受用户拖入的本地文件，并通过 Electron 主进程验证真实路径、文件类型和大小后生成可读取附件。
- 根据受支持类型将图片/PDF 标记为 `multimodal_read`，将文本、Markdown、CSV、JSON、YAML 和 DOCX 标记为 `document_read`；不支持或超限的文件显示明确错误。
- 接受 Windows 剪贴板中的图片，使 Snipaste 等截图工具复制的截图能够粘贴到 Meguri，并作为有大小限制的内联图片提交。
- 给附件显示读取状态和来源，保留 `@`/Everything 搜索默认仅引用元数据的隐私语义。
- 添加 Electron 桥接、前端交互、Core 兼容性和安全边界的自动化验证。

## Capabilities

### New Capabilities

- `desktop-file-and-screenshot-ingestion`: 用户可通过拖放或剪贴板明确授权 Meguri 读取受支持的本地文件和截图，同时获得可见状态、错误反馈与受限内容传输。

### Modified Capabilities

- None.

## Impact

- `apps/desktop-meguri` 的 Electron IPC、preload 合约、聊天编辑器和本地资源附件模型。
- 现有 turn attachment 负载与 Java Core 的 `multimodal_read` / `document_read` 解析链路。
- 桌面前端单元测试、preload smoke test，以及本地端到端验证；不新增截图软件私有插件，不自动监视剪贴板或任意读取文件系统。
