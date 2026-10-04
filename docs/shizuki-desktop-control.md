# 通过 Meguri 桌宠控制本机 Shizuki

Meguri Core 可以连接 `shizuki-site` 的 `codex/integrate-meguri-pet-frontend` 分支所提供的桌面控制协议。控制只发生在当前 Windows 用户的本机回环地址上；它不会控制远程网站，也不会启动、安装或结束 Shizuki 进程。

## 启动与使用

先启动 Shizuki 桌面版：

```powershell
cd D:\program\shizuki-site\fronted\vue3-merged
pnpm desktop:dev
```

已安装桌面版时，直接从开始菜单启动 **Shizuki** 即可。Shizuki 启动后会创建 `%APPDATA%\Shizuki\control-api.json`；关闭应用时会删除它。

然后启动 Meguri 桌宠：

```powershell
cd D:\program\meguri-pet
.\start-meguri-desktop.bat
```

本地启动器会开启受限 ReAct 执行路径、启用 Shizuki 适配器，并由服务端授予 `shizuki:control` scope。普通服务器配置仍然默认关闭此能力。

可以对桌宠直接说：

- “打开 Shizuki 的音乐页”
- “让式月窗口显示出来”
- “最小化 Shizuki”
- “让 Shizuki 暂停音乐”
- “Shizuki 下一首”
- “看看 Shizuki 现在的状态”

请求中需要同时出现 `Shizuki` 或 `式月`，以及明确的操作或状态词。这样普通聊天不会无意进入工具执行路径。

## 支持的操作

| 类型 | 桌宠能力 | Shizuki 协议命令 |
| --- | --- | --- |
| 状态 | `shizuki.state.read` | `GET /v1/state` |
| 页面 | `shizuki.open.home`、`blog`、`music`、`apps`、`ai_hub`、`profile`、`admin`、`author` | `app.navigate` + 固定 destination |
| 窗口 | `shizuki.window.show`、`hide`、`focus`、`minimize`、`toggle_visibility` | 对应 `window.*` 命令 |
| 音乐 | `shizuki.music.play`、`pause`、`toggle`、`next`、`previous` | 对应 `music.*` 命令 |

模型不会获得自由格式的 `command` 或 `payload` 参数。上述 19 项能力均映射到编译时固定的命令和参数；不支持任意 URL、Shell、JavaScript、DOM selector 或文件访问。

## 配置

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `MEGURI_SHIZUKI_ENABLED` | `false` | 是否注册 Shizuki 能力 |
| `MEGURI_SHIZUKI_MANIFEST_PATH` | `%APPDATA%\Shizuki\control-api.json` | 覆盖发现清单路径，主要用于测试 |
| `MEGURI_SHIZUKI_CONNECT_TIMEOUT_MS` | `750` | 回环连接超时，范围 1–30000 ms |
| `MEGURI_SHIZUKI_REQUEST_TIMEOUT_MS` | `2000` | 单次请求超时，范围 1–30000 ms |
| `MEGURI_CAPABILITY_SCOPES` | 空 | 必须包含 `shizuki:control` 才会向当前 turn 暴露能力 |

默认 manifest 包含进程生命周期内有效的 bearer token。它只由 Meguri Core 读取，不会进入桌宠 renderer、模型上下文、日志或工具结果。客户端拒绝重定向，只接受没有路径、查询参数、fragment 或 user-info 的字面量 `http://127.0.0.1:<port>`，并验证协议 major、活跃 PID、健康状态及服务端实际声明的命令。

## 离线与故障处理

- 提示“Shizuki 桌面应用当前未运行”：先启动 Shizuki，并确认 `%APPDATA%\Shizuki\control-api.json` 存在。
- 提示“界面尚未就绪”：Electron 已启动但 renderer 还未完成加载，稍等后重试。
- 提示“控制凭据已失效”：重启 Shizuki。Meguri 遇到 `401/403` 会重新读取 manifest 并仅重试一次。
- 提示“没有及时确认操作”：结果可能不确定，Meguri 不会自动重复可能已执行的窗口或播放操作；请先查询状态再决定是否重试。
- 提示“协议不兼容”：确认 Shizuki 分支提供的是控制协议 `1.x`，并运行该仓库的 `pnpm test:desktop` 与 `pnpm desktop:smoke`。

Shizuki 桌面前端的数据网关仍按该项目配置连接 `http://111.228.35.186:8080`。本能力只控制本机桌面外壳，不会把该项目切换到本地 Docker 中间件。
