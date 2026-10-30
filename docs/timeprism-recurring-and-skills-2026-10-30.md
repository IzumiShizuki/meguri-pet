# 周期 Todo 与 Skills 组合回归（2026-10-30）

本阶段代码已完成本地隔离验证，未部署、未推送，不代表真实站点/UI/模型验收。下一阶段统一 Todo 中心见 `contracts/timeprism-unified-center-v1.md`，新增便签与CAS仍在实现。

## 本阶段实现

- AstrBot 支持有界每天/多星期/工作日、提前分钟或天、IANA时区、显式起止范围。owner私聊先预览再明确确认；编辑新建请求ID，不确定重试保留原ID。未支持的相对范围/秒精度澄清，不改写成无限周期。
- Core 新增受原身份门禁保护的周期规则写入；复用凭据边界、持久journal和唯一marker回读。完整核验规则字段；跨重启、范围过期后仍可核对原不确定写入，不第二次POST。
- 桌面纯读仅使用新snapshot；显式调度使用sync。缺纯读接口不回退旧副作用GET。
- 站点 `b7f247e213365197fcb6475c0dc26ab0545aabe8` 增加纯snapshot/显式sync，旧GET兼容。有效start/end校验；预览使用完整日期时间并排除DST缺口。站点独立本地提交，未部署。
- Skills候选026f809等效主树93e90eb；依赖、禁用、候选排序、错误原因、只读MCP审批接线和缺省outputSchema已整合。
- 审查补修：畸形JSON/SSE JSON按MCP_RESPONSE_INVALID不可重试，真实IO故障仍按原有有界策略恢复。没有放宽地址、scope、审批或写权限。

## 可复现证据

主证据 `reports/recurring-drafts-20261030/`：
- Java整合全量1016项：1011通过、0失败/错误、5原跳过。与此前988项逐一比较状态不变，新增28全过；`java-comparison.json`、`java-full-surefire.zip`。
- Java针对性39/39；全量命令 `reports/capability-integration-20261030/run-isolated-tests.ps1 -LogName recurring-skills-full.log`。使用D盘既有隔离副本、JDK21和已验证测试进程TCP回退目录，不修改运行Core/JDK全局配置。
- 桌面针对性63/63，全量540/540；typecheck和build通过（保留原大chunk提示）。
- Python默认discover391/391；复审新增相对范围和IANA完整token用例后相关9/9（全量计数为新增前快照）。协议TS52/52。
- 站点Java reactor584项：540通过、44个数据库门控跳过；前端全量2047/2047；相关Java49/49及前端31/31。共享JSON fixture哈希一致，真实Controller→Service使用假mapper回读完整秒/时区/星期/提前量，未连接真实DB。
- 失败先行：周期新Java5项404失败；桌面新6项中3失败；站点新4项Java及3项前端失败。MCP新增JSON/SSE两项先失败、IO对照通过，修后6/6。周期相对限定5个反例先失败，修后通过。日期非法值补测识别422误500并修正。

## 限制与兼容

先部署支持snapshot的站点，再加载新桌面/Core。旧服务无snapshot时明确报兼容状态。当前Core仍是旧运行制品；本轮未重启、无新UAC、未动真实Todo/记忆/凭据。站点DB门控测试、真实模型、生产双端和窗口UI未测，不能以fakeHTTP通过替代。

原单次Core writer的过期不确定请求仍受未来时间校验限制，是既有问题，本周期路径已独立覆盖过期恢复。统一中心后端幂等上线后应统一原单次恢复路径，不能在未迁移前声称全链路Exactly Once。
