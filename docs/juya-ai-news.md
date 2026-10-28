# 橘鸦 AI 早报

Meguri Core 直接订阅 `https://daily.juya.uk/rss.xml`，使用已配置的聊天模型提炼
3–5 条重点、每条的影响，以及 1–2 个聊天话题。正文附报道日期和原文链接。
AstrBot 和桌面客户端通过既有 Core 日报通道读取结果。

## 运行与配置

- 每天北京时间 08:00 起，每 10 分钟检查当天文章。启动后也会补查。
- 同一天的摘要持久化保存，重启不再调用模型；RSS 或模型失败会继续重试。
- 默认离线配置不抓取网络。`ops/scripts/start-meguri-core.ps1` 已启用此功能。
- Windows 日报保存在用户目录 `.meguri/daily-reports`；可读副本写到项目
  `reports/daily/ai-news-YYYY-MM-DD.md`。原生桌面启动时补收当天摘要，并按日报编号去重。
- AstrBot 网关的 `daily_report_kinds` 加入 `ai_news`，保持既有
  `daily_report_targets` 和 `daily_reports_enabled=true`。发送失败会继续重试。
- 服务器可以在现有 `MEGURI_DAILY_REPORT_DIR` 目录下放置 `ai-news.properties`，内容为
  `meguri.ai-news.enabled=true`，然后重启既有 Core。无需新建容器或安装第二个 AstrBot。

环境配置：`MEGURI_AI_NEWS_ENABLED`、`MEGURI_AI_NEWS_START_HOUR`、
`MEGURI_AI_NEWS_POLL_DELAY_MS`、`MEGURI_AI_NEWS_DESKTOP_DIRECTORY`。
模型凭据继续由既有外部密钥文件提供。

## 手动查看与聊天

AstrBot 发送 `/ainews`、`/ai早报` 或 `/meguri news` 可直接取最近一期。
桌宠可以直接问“今天 AI 早报有什么重点”或“哪条新闻值得聊”。

Core 给普通与结构化聊天请求都提供最近一期摘要，并标注日期、来源和外部资料边界。
超过三天的摘要不再自动进入聊天上下文。新闻不会写成用户或角色的个人经历。

接口（继续使用现有身份认证）：

- `GET /v1/daily/ai-news/status`：启用状态、等待/就绪/重试状态。
- `GET /v1/daily/reports/latest?kind=ai_news`：最近一期摘要和投递文本。
- `GET /v1/daily/reports/ai_news/YYYY-MM-DD/markdown`：存档 Markdown。

## 停用与恢复

本地启动器设 `MEGURI_AI_NEWS_ENABLED=false`，或移除启动器中强制启用的设置。
服务器配置文件设 `meguri.ai-news.enabled=false`；两者均需重启 Core。
在 AstrBot 的 `daily_report_kinds` 中移除 `ai_news` 可单独关闭 QQ 推送。
已有 Bilibili 日报和其他插件配置保持原样。

部署前会备份远程 JAR、网关源码和配置。恢复备份并重启对应服务即可回滚。
RSS 更新可能晚于公众号，轮询不是即时回调；摘要是对早报的提炼，关键事实仍应查看原始出处。

## 2026-10-07 启用记录

- 本地 Core 与远程 Core 均已生成 `ai_news:2026-10-07`，真实聊天请求可引用其中新闻与日期。
- AstrBot 已在原有私聊目标成功投递今天的早报；原有 Bilibili 订阅保持启用。
- 远程镜像：`meguri-core-java:ai-news-20261007`；既有 `manual-latest` 标签已更新，重建容器时仍包含早报模块。
- 回滚镜像：`meguri-core-java:pre-ai-news-20261007`；JAR 备份位于
  `/app/data/daily-reports/meguri-core.pre-ai-news-20261007.jar`。
- 网关源码与配置各保留 `.bak-ai-news-20261007` 副本。
- 当前原生桌宠进程拒绝了自动重启（Windows 拒绝访问）。从托盘退出后重新打开，
  会加载已构建的每日摘要消息界面，并补收今天的内容；后端抓取与聊天新闻资料已经生效。
