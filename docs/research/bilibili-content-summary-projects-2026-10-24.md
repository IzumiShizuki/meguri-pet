# Bilibili 观看总结：内容理解与开源项目对照

调研日期：2026-10-24（Asia/Shanghai）。本轮只读公开 GitHub README、源码、LICENSE、Release 与 GitHub REST API；没有读取、上传或写入个人观看记录、Cookie、模型密钥。此文是调研结果，方案段落为工程建议，不代表已经集成或通过真实视频端到端验证。

## 判断

要回答“具体看了什么、最近在往哪个方向看”，需要两层信息：每条视频的内容证据与摘要，以及跨视频的具体主题聚合。BiliNote 的字幕→转写→结构化笔记链路最值得参考；BilibiliHistoryFetcher 适合保留为历史和元数据来源，其当前版本不能被当作现成的视频理解服务。后者在 v1.7.0 明确移除了 AI 摘要与语音转文字模块。[B1][B2][H1][H2]

建议最小接入：沿用现有历史采集和模型配置，增加独立的 Python 内容增强步骤，优先读取视频简介、可访问字幕或用户本地导出的 transcript，再让模型为每条视频生成具体摘要、主题和证据引用；最后汇总成“近期关注方向＋代表视频”。无内容证据时保留“仅依据标题/简介”的明确状态。第一阶段不必部署完整 BiliNote，也不必引入 ASR 模型。这是基于下述项目结构和依赖作出的实现建议。

## 项目比较

| 项目 | 已核实的能力与边界 | 接入判断（工程推断） | 许可证与维护证据 |
|---|---|---|---|
| [JefferyHcool/BiliNote](https://github.com/JefferyHcool/BiliNote) | 平台字幕优先，无字幕时下载音频再转写，之后用 LLM 生成 Markdown；支持时间链接、截图和可选视频画面理解。是逐视频内容总结，不自动等于观看方向聚合。[B1][B2][B3] | 最适合参考字幕适配器、缓存和摘要结构。整个服务还包含下载器、转写器和任务管理，直接部署的运维代价高于在现有插件加薄适配器。[B2][B3] | MIT，已核对 LICENSE。[B4] 最新 release 为 v2.4.5（2026-08-25）；默认分支最近提交 2026-09-01，仍未归档。[B5][M1] |
| [LifeArchiveProject/BilibiliHistoryFetcher](https://github.com/LifeArchiveProject/BilibiliHistoryFetcher) | 历史留存、统计、年度总结、标题分析及只读 MCP；v1.7.0 移除 AI 摘要/语音转写，不能按旧介绍当作当前字幕总结引擎。[H1][H2] | 已在使用其数据时继续复用；不建议为本次内容理解再引入一套重复历史服务。 | MIT。[H3] 当前 latest release 为 v1.7.2（2026-06-21）；默认分支最近提交 2026-06-26，未归档。[H4][M2] |
| [Decentralised-AI/BibiGPT](https://github.com/Decentralised-AI/BibiGPT) | 公开仓库标为 v1；B 站代码读取视频标题/简介及字幕，缺字幕返回 `subtitlesArray: null`，这条公开 B 站路径没有 ASR 回退。不能把商业站的全部能力归给这个公开 v1。[G1][G2][G3] | 可参考提示词与字幕时间戳处理；公开代码较旧，不作为新插件的首选底座。 | GPL-3.0，已核对 LICENSE.txt。[G4] 默认分支最近提交为 2023-08-11，未归档不代表近期维护。[M3] |
| [hyhAsma/bili-history-analyzer-userscript](https://github.com/hyhAsma/bili-history-analyzer-userscript) | 直接分析历史，支持按时间/时长/设备筛选、可视化和自配 LLM；代码按块分析历史字段再聚合观看行为，无完整字幕获取链路。[U1][U2] | 对“历史→分块摘要→总报告”最轻巧，但仅迁移其行为报告仍不足以补真实内容证据。 | MIT。[U3] 源码标记 3.2.0，README 仍写 3.1.0；默认分支最近提交 2025-10-24。[U1][U2][M4] |
| [AliceDel66/BiliNote](https://github.com/AliceDel66/BiliNote)（不同于 JefferyHcool 项目） | 浏览器扩展，本地保存课程/笔记，字幕优先；无字幕时由用户主动选择 STT，把临时音轨发往自配 STT。README 标明 pre-alpha，单文件 25MB，暂不支持分段转写。[A1] | 若以后要在浏览器观看时采集已登录可见字幕，比只靠后台匿名抓取更贴合；本次无需迁移成独立学习扩展。 | MIT。[A2] 默认分支最近提交 2026-07-21，未归档。[M5] |
| [Nemo2011/bilibili-api](https://github.com/Nemo2011/bilibili-api)（原 bilibili-api-python 上游） | 当前仓库只剩关停声明，2026-07-06 已归档并宣布永久停维；不能继续推荐为“活跃 SDK”。[N1] | 不增加为新依赖；已有安装也应与具体版本及替代适配器分开评估。 | 当前 HEAD 没有可核验的历史代码许可证，故此文不宣称当前仓库可按某历史许可复用。关停和归档状态以当前仓库为准。[N1] |

维护时间取默认分支最新 commit；latest release 与 commit 分开记录。未使用星标数推断质量，也没有声称这些项目的字幕 API 在本机一定可用。

## BiliNote 字幕实现：适合参考的具体部分

已逐行核对 [`backend/app/downloaders/bilibili_subtitle.py`](https://github.com/JefferyHcool/BiliNote/blob/90940fde4d224d0f69ad99674f4af0a1ea52d3c4/backend/app/downloaders/bilibili_subtitle.py)：[B6]

1. 从链接提取 BV 与分 P，访问 `GET https://api.bilibili.com/x/web-interface/view?bvid=...`，从 `pages` 取相应 `cid`。
2. 请求 `GET https://api.bilibili.com/x/player/wbi/v2?bvid=...&cid=...`，读取 `data.subtitle.subtitles`。
3. 在可见字幕轨道中优先人工中文，再选中文 AI 字幕，之后回退其他语言；读取轨道 `subtitle_url` 的 JSON，将 `body[].from/to/content` 规范成带时间段的 transcript。
4. `CookieConfigManager` 注入已配置 Cookie；代码注释明确 AI 字幕需要登录态 SESSDATA。字幕轨道为空、URL 为空或网络错误均返回空结果。该文件的请求没有自行计算 `w_rid` / `wts`；这一实现细节不能推出接口永远不需要签名。

这些是开源项目对 B 站 Web 接口的现有调用实现，不是经核实的稳定官方开放平台契约。**已实现获取流程，不等于公开匿名一定能拿到字幕。** 因此插件应将 `no_subtitles`、`login_required`、`fetch_failed` 与 `metadata_only` 分开保存，支持用户提供本地 transcript；遇到不可访问数据正常降级。不能把无字幕改写成“已读完整视频”。[B6]

BiliNote 的 [`bilibili_downloader.py`](https://github.com/JefferyHcool/BiliNote/blob/90940fde4d224d0f69ad99674f4af0a1ea52d3c4/backend/app/downloaders/bilibili_downloader.py) 还会在直接字幕失败时调用 yt-dlp 字幕逻辑；完整生成器则继续走音频转写。这个回退会增加下载、FFmpeg、转写依赖与运行成本，建议作为后续可选能力。[B2][B3]

若已经部署 BiliNote，可以参考其 `/generate_note` 和 `/task_status/{task_id}` 接口；请求可带 `prefetched_transcript`，跳过后端获取字幕/ASR，字幕结构为 `language/full_text/segments[{start,end,text}]`。接口同时需要模型、provider、视频链接等配置，仍需要插件侧适配，不能只替换一个摘要 URL。[B7]

## 建议的最小数据流与输出

以下是设计建议，不是某个上游项目已经提供的 API。

`现有观看历史 → 视频身份（bvid + cid）→ 元数据/字幕/本地 transcript → 逐视频内容卡片缓存 → 时间窗口内的主题聚合 → 观看总结`

每条内容卡片建议记录：

- 视频标题、作者、来源链接、分 P、观看日期；不把“看过视频”推定为“已掌握知识”。
- 一两句具体内容摘要、2–4 个知识点或讨论问题、具体主题（例如“Spring 事务传播/回滚边界”，不能只写“科技”）。
- `evidence_source`（字幕、用户导出 transcript、简介或仅标题）、时间戳引用、生成模型和版本、原内容摘要哈希。
- `status` 与失败原因；字幕不可得时降低结论强度，模型返回缺证据时不凭空补全。

窗口报告建议呈现：主要方向、各方向代表视频与具体内容、新出现/持续关注的子主题，以及字幕覆盖率。计数、观看时长等继续由确定性统计计算，模型只解释有证据的主题；只有已知观看区间时才裁剪 transcript。历史接口只有最后进度时，不能把它解释成用户完整看过 `[0, progress]` 区间。

第一阶段验收可用脱敏 fixture 覆盖：有字幕、仅简介、仅标题、分 P 区分、字幕不可访问、重复视频缓存、长 transcript 分块、模型失败。报告至少展示数条“具体讲了什么＋来源视频”，并对缺证据说明降级；不能以标签雷达图或观看习惯锐评替代此验收。

## 一手来源

- [B1] [BiliNote README](https://github.com/JefferyHcool/BiliNote/blob/90940fde4d224d0f69ad99674f4af0a1ea52d3c4/README.md)。
- [B2] [BiliNote NoteGenerator 源码](https://github.com/JefferyHcool/BiliNote/blob/90940fde4d224d0f69ad99674f4af0a1ea52d3c4/backend/app/services/note.py)。
- [B3] [BiliNote BilibiliDownloader 源码](https://github.com/JefferyHcool/BiliNote/blob/90940fde4d224d0f69ad99674f4af0a1ea52d3c4/backend/app/downloaders/bilibili_downloader.py)。
- [B4] [BiliNote LICENSE](https://github.com/JefferyHcool/BiliNote/blob/90940fde4d224d0f69ad99674f4af0a1ea52d3c4/LICENSE)。
- [B5] [BiliNote v2.4.5 release](https://github.com/JefferyHcool/BiliNote/releases/tag/v2.4.5)。
- [B6] [BiliNote BilibiliSubtitleFetcher 源码](https://github.com/JefferyHcool/BiliNote/blob/90940fde4d224d0f69ad99674f4af0a1ea52d3c4/backend/app/downloaders/bilibili_subtitle.py)。
- [B7] [BiliNote note 路由与 prefetched_transcript](https://github.com/JefferyHcool/BiliNote/blob/90940fde4d224d0f69ad99674f4af0a1ea52d3c4/backend/app/routers/note.py)。
- [H1] [BilibiliHistoryFetcher README](https://github.com/LifeArchiveProject/BilibiliHistoryFetcher/blob/096dabb17981b7ec279dc01ace6df1c026706c71/README.md)。
- [H2] [BilibiliHistoryFetcher v1.7.0 release：移除 AI 摘要与语音转文字](https://github.com/LifeArchiveProject/BilibiliHistoryFetcher/releases/tag/v1.7.0)。
- [H3] [BilibiliHistoryFetcher LICENSE.md](https://github.com/LifeArchiveProject/BilibiliHistoryFetcher/blob/096dabb17981b7ec279dc01ace6df1c026706c71/LICENSE.md)。
- [H4] [BilibiliHistoryFetcher v1.7.2 release](https://github.com/LifeArchiveProject/BilibiliHistoryFetcher/releases/tag/v1.7.2)。
- [G1] [BibiGPT 公开 v1 README](https://github.com/Decentralised-AI/BibiGPT/blob/29ea03d917bd78f257b06f95f0f5ec9e695e579e/README.md)。
- [G2] [BibiGPT fetchBilibiliSubtitle](https://github.com/Decentralised-AI/BibiGPT/blob/29ea03d917bd78f257b06f95f0f5ec9e695e579e/lib/bilibili/fetchBilibiliSubtitle.ts)。
- [G3] [BibiGPT fetchBilibiliSubtitleUrls](https://github.com/Decentralised-AI/BibiGPT/blob/29ea03d917bd78f257b06f95f0f5ec9e695e579e/lib/bilibili/fetchBilibiliSubtitleUrls.ts)。
- [G4] [BibiGPT LICENSE.txt](https://github.com/Decentralised-AI/BibiGPT/blob/29ea03d917bd78f257b06f95f0f5ec9e695e579e/LICENSE.txt)。
- [U1] [bili-history-analyzer-userscript README](https://github.com/hyhAsma/bili-history-analyzer-userscript/blob/f2604b798d7f68e00ebd9cda80a3097c423b8afd/README.md)。
- [U2] [用户脚本源码：历史分块与聚合提示词](https://github.com/hyhAsma/bili-history-analyzer-userscript/blob/f2604b798d7f68e00ebd9cda80a3097c423b8afd/bili-history-analyzer-userscript.js)。
- [U3] [用户脚本 LICENSE](https://github.com/hyhAsma/bili-history-analyzer-userscript/blob/f2604b798d7f68e00ebd9cda80a3097c423b8afd/LICENSE)。
- [A1] [AliceDel66/BiliNote README](https://github.com/AliceDel66/BiliNote/blob/36aba9803658b270bd8596864e4c8246510631cf/README.md)。
- [A2] [AliceDel66/BiliNote LICENSE](https://github.com/AliceDel66/BiliNote/blob/36aba9803658b270bd8596864e4c8246510631cf/LICENSE)。
- [N1] [Nemo2011/bilibili-api 当前关停声明和归档标识](https://github.com/Nemo2011/bilibili-api)。
- [M1] [BiliNote GitHub repository API](https://api.github.com/repos/JefferyHcool/BiliNote)；[最新提交 API](https://api.github.com/repos/JefferyHcool/BiliNote/commits?per_page=1)。
- [M2] [BilibiliHistoryFetcher repository API](https://api.github.com/repos/LifeArchiveProject/BilibiliHistoryFetcher)；[最新提交 API](https://api.github.com/repos/LifeArchiveProject/BilibiliHistoryFetcher/commits?per_page=1)。
- [M3] [BibiGPT repository API](https://api.github.com/repos/Decentralised-AI/BibiGPT)；[最新提交 API](https://api.github.com/repos/Decentralised-AI/BibiGPT/commits?per_page=1)。
- [M4] [用户脚本 repository API](https://api.github.com/repos/hyhAsma/bili-history-analyzer-userscript)；[最新提交 API](https://api.github.com/repos/hyhAsma/bili-history-analyzer-userscript/commits?per_page=1)。
- [M5] [AliceDel66/BiliNote repository API](https://api.github.com/repos/AliceDel66/BiliNote)；[最新提交 API](https://api.github.com/repos/AliceDel66/BiliNote/commits?per_page=1)。

GitHub REST 元数据在本轮通过直接只读 HTTP GET 核验，链接为动态结果；上述默认分支 commit SHA 是调研时的固定快照。源码许可证只说明代码复用许可，不能证明 B 站接口、视频内容或云端模型服务的额外授权。
