# 原生桌面角色与语音资源

本次实现的设置入口为「角色 → Live2D」和「语音 → 本地语音模型」。平台扩展与主对话模型配置不在本次范围内。

## Live2D

从本地目录或 ZIP 导入完整的 Cubism 3/4/5 导出资源：一个 `.model3.json`、对应 `.moc3`、纹理，以及声明的动作、表情、物理和其他资源。包内路径必须相对且名称大小写一致。当前上限为 2048 个文件、512 MB 总量、100 MB 单文件；外部 URL、越界路径、符号链接、可执行资源和缺失引用都会被拒绝。导入会复制到 Electron 的用户数据目录，移除只作用于受管副本。

激活后，桌宠和 Galgame 共用 AIRI Live2D 引擎，支持鼠标注视、模型 HitAreas 触摸、可用的动作与表情、语音口型。降低动态效果会暂停动画与注视、禁止触摸动作。加载失败时显示原因并回退 PNG。随时可选「使用 PNG 立绘」。

默认复用 `D:/program/airi-meguri` 中已安装的 `@proj-airi/stage-ui-live2d` 和 Cubism Core。使用其他 AIRI 安装时，在构建及运行环境指定 `MEGURI_AIRI_ROOT`。本仓库的构建插件只在构建输出中适配接口，不修改 AIRI checkout。缺少 AIRI/Cubism 时，PNG 仍可使用。

可以在 `.model3.json` 同目录放置 `meguri-interactions.json`：

```json
{
  "name": "我的角色",
  "license": "角色作者及使用许可说明",
  "reactions": {
    "Body": { "motion": "TapBody", "expression": "happy", "speech": "我在这里。" }
  }
}
```

区域、动作组和表情名称必须存在于模型；触摸语音最多 200 字。不提供映射时，会使用已有的 `TapBody` 等匹配动作组。对话动作与表情只触发模型确实支持的名称。

角色模型需由用户提供，本仓库不附带新的 Meguri Live2D 模型。本次实模检查使用 [Live2D 官方 Haru 样例](https://github.com/Live2D/CubismWebSamples/tree/5-r.3/Samples/Resources/Haru)，仅保存在忽略的隔离检查目录，不自动选作桌宠。模型及 Cubism Core 的分发须遵守各自许可；本次没有发布或分发安装包。

## GPT-SoVITS 语音包

语音复用本机现有 GPT-SoVITS 环境，不安装第二套 ONNX/VITS 引擎。支持的是完整 GPT-SoVITS profile，移动端的 VITS/ONNX 包不能直接导入。

目录内提供 `voice-profile.json`：

```json
{
  "schemaVersion": 1,
  "engine": "gpt-sovits",
  "name": "我的语音",
  "modelVersion": "v2Pro",
  "language": "ja",
  "referenceText": "与参考音频一致的文本",
  "files": {
    "gpt": { "path": "voice.ckpt", "sha256": "替换为64位小写SHA-256" },
    "sovits": { "path": "voice.pth", "sha256": "替换为64位小写SHA-256" },
    "referenceAudio": { "path": "reference.wav", "sha256": "替换为64位小写SHA-256" }
  }
}
```

参考音频接受 WAV、OGG、MP3、FLAC。每个文件最多 2 GB，整包最多 4 GB。每次导入、激活都检查资源与哈希；激活第三方权重前使用 PyTorch `weights_only=True` 验证，拒绝可执行 pickle 对象，不进行不安全加载降级。清单声明版本不保证本机引擎拥有该版本所需的所有基础模型，实际激活失败会保留或尝试恢复原语音。

HTTPS 下载使用同一清单，为每个 `files` 条目增加公开 HTTPS `url`。不接受带凭据地址、私有网络地址或跳转；下载后校验 SHA-256，完成后由用户选择激活。网络失败、缺少资源或校验失败不会替换原语音。

预装语音保持只读。导入包放在本仓库忽略的 `.codex-runtime/voice-profiles`，只有未激活的受管语音可移除，源目录和现有环境不会被删除。

## 运行与授权

语音管理接口只监听本机 `127.0.0.1:9880`，须带宿主凭据。默认凭据文件是仓库外 `D:/environment/secrets/meguri/tts-management-token.txt`，首次启动新桥接服务时生成，已有文件不会被覆盖；可用 `MEGURI_TTS_MANAGEMENT_TOKEN_FILE` 指向其他仓库外绝对路径。令牌不返回界面、不进入资源清单或诊断文件。

旧版已运行的 TTS 服务没有新管理接口，需退出并用 `ops/scripts/start_local_tts.ps1` 重启。先确认 9880/9881 的现有服务归属，避免重复启动 GPU 模型。此改动没有自动重启正在运行的服务、安装工具链或热切换用户模型。

语速 0.5–2.0×、音量、全局快速静音、桌宠/Galgame 独立开关应用到后续回复、触摸反馈及本地提醒。关闭当前播放开关会停止正在生成或播放的语音；切换界面不会重放已消费的语音事件。TTS 失败不影响文字回复。

## 隔离检查

以下检查不会修改正常用户配置或写正式记忆：

```powershell
Remove-Item Env:\ELECTRON_RUN_AS_NODE -ErrorAction SilentlyContinue
pnpm --filter @meguri/desktop-meguri exec electron scripts/companion-experience-visual-smoke.mjs
# 仅下载官方样例到忽略目录；也可以自行提供获授权模型。
& 'D:\environment\anaconda3\envs\py314\python.exe' apps/desktop-meguri/scripts/download-live2d-smoke-fixture.py
$env:MEGURI_LIVE2D_SMOKE_MODEL = 'D:\program\meguri-pet\.codex-runtime\live2d-smoke\Haru'
# Live2D smoke 要求已有 dev:renderer（默认 4174）。
pnpm --filter @meguri/desktop-meguri exec electron scripts/live2d-runtime-smoke.mjs
& 'D:\environment\miniconda3\envs\GPTSoVits\python.exe' -m unittest local-services.tts-runtime.test_safe_weights -v
```
