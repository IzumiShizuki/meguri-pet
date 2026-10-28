# Bilibili 观看内容日报

日报在统计之外增加“视频具体讲了什么”和“当天关注的具体方向”。默认优先分析观看权重最高的 12 个视频，完整列表、方向对应的视频链接与证据等级写在 Markdown；QQ 卡片显示三条代表内容和最多六个方向。方向由模型按实际材料归纳，支持编程、语言、音乐、游戏、生活等类型。

相关项目和固定版本源码对照见 [调研记录](research/bilibili-content-summary-projects-2026-10-24.md)。本次采用独立的轻量适配器，没有引入整个 BiliNote 服务。

## 运行

现有 `ops/scripts/run-bilibili-daily.ps1` 的每日链路现在是：

1. BHF 同步 → 本机只读 MCP → 原来的元数据日报。
2. `tools/enrich_bilibili_report.py` 获取公开视频简介和可访问字幕，复用配置的文本模型生成逐视频内容与具体主题。
3. 聚合方向、更新 Markdown/图片数据 → 原有发布链路。

计划任务仍调用同一个脚本，不需要重新注册。`-MetadataOnly` 可恢复原元数据模式。`-ContentMaxVideos 6` 可缩小样本；范围 1–24。内容增强默认最多用 100 秒，超时或失败继续发布元数据日报，运行状态保存在原有任务状态文件的 `content_status`。这是代码层的接入；服务器 AstrBot 需要加载更新后的 `bilibili_daily_card.py` 才会使用新的卡片排版。

模型优先复用 `MEGURI_LLM_BASE_URL`、`MEGURI_LLM_MODEL`、`MEGURI_LLM_API_KEY_FILE`。无环境配置时，日任务使用当前本地 Core 启动器已采用的 DeepSeek 地址和 `deepseek-v4-flash`，查找现有外部密钥文件 `D:\environment\secrets\meguri-deepseek-api-key.txt` 或 `D:\environment\secrets\meguri\deepseek-api-key.txt`。切换其他服务可传 `-ContentLlmBaseUrl`、`-ContentLlmModel`、`-ContentLlmKeyFile`；只对原 DeepSeek 地址自动查找默认密钥，密钥正文不会进入参数或报告。

无模型或调用失败时显示简介/字幕摘录、标题推测，不会冒充已生成语义方向。已成功的内容与摘要默认缓存在仓库外 `%USERPROFILE%\.meguri\bilibili\content-cache`；证据缓存 24 小时，模型摘要 30 天，键包含材料哈希、模型地址/名称、提示词版本。失败不会写入成功摘要缓存。

## 单独处理已有日报

```powershell
python tools/enrich_bilibili_report.py `
  --project-root D:\program\meguri-pet `
  --report-json C:\private\bilibili-metadata.json `
  --output-json C:\private\bilibili-content.json `
  --max-videos 12
```

独立 CLI 不自动寻找密钥，使用上述环境变量或 `--llm-base-url`、`--llm-model`、`--llm-key-file`。原来的 `generate_bilibili_browser_report.py` 单独运行仍只读历史元数据，不请求视频内容或模型。Java 的原元数据接口和选定视频总结占位接口也保持原行为。

可把 BiliNote 等工具的转写整理为以下 JSON，再传 `--transcripts-json C:\private\transcripts.json`：

```json
{
  "BV1xx411c7mD": {
    "cid": 123456,
    "text": "这里放该视频对应的字幕或转写正文。"
  }
}
```

只有日报中存在的 BV 会被处理，历史与导出资料都提供 CID 时必须匹配。多 P 视频缺少对应 CID 或一天观看多个 P 时，匿名提取保守回退到简介，不猜测第一 P。手工导出合并转写时由提供者确认 BV/分 P 对应关系。

`--no-public-fetch` 只禁止 Bilibili 网络请求，仍可调用已配置的模型。要完全本地处理，需要使用本地模型地址，或显式传空模型参数以仅生成摘录。

## 证据与范围

- **字幕**：实际获取的公开字幕或指定转写片段。
- **简介**：只概括 UP 主提供的简介，不能视为视频正文结论。
- **仅标题推测**：缺少更强证据；不能编造视频观点。
- **未分析**：超过样本上限或运行预算的视频，仍保留在元数据表中。

正文输入每视频最多 5000 字，超出部分属于本次未提供材料。报告明确显示字幕、简介、仅标题的数量及分析样本占全天视频数。模型只接收 BV 标识、标题、内容依据和文本片段，不接收账号身份、观看日期/时间或播放进度。统计仍由原确定性计算完成，最后播放进度无法证明用户看过每一段、学会了知识或认同某观点。

公开视频接口属于社区项目使用的 Web 接口，可能要求登录或变更。实测简介可取得；无法保证每个视频的匿名字幕可见。当前版本不读取 Cookie，不下载音视频、不自动 ASR；有内容提取失败时如实降级。

## 验证与预览

运行 `python -m unittest tools.tests.test_enrich_bilibili_report tools.tests.test_generate_bilibili_browser_report tests.test_bilibili_daily_card tests.test_daily_report_publisher tests.test_astrbot_daily_reports tests.test_astrbot_plugin_package`。

演示图片在 `reports/previews/bilibili-content-digest-demo/daily.png`，演示日报在同目录的 `reports/daily/bilibili-2026-10-23.md`，全部为合成资料，不是个人观看记录。另已用三条合成转写实际验证当前配置模型的 JSON 输出与 BV 对应关系；没有向模型发送个人观看历史。
