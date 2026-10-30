## Context

基线 Meguri `1d5b00dc`；只读站点工作树 `ec29b37c`，无未提交变更。现有星期多选/Mon–Fri、六段 Cron、提前量、规则时区、创建 journal 和日期实例完成可直接复用，不重做账号体系。

已证实：桌面 `index.mjs:1074` 的列表 IPC 调用 `useSnapshot`，后者 `shizuki-reminders.mjs:179` 立即扫描；提醒计算未排除 `recurringRule.enabled=false`。`saveRecurringRule` 只保留已有起止时间，忽略创建输入的起止字段。站点 `LightAppServiceImpl:907–910` 的 GET 会发布列表事件并生成实例，故“HTTP GET”不等于数据库纯读。AstrBot `timeprism_todo_creation.py:81–84` 明确只接受一次性上海时区提醒；Core create DTO 只有 requestId/title/dueAt，writer 提前量固定为 0。这是既有支持范围，不是被测试证实的故障。

## Goals / Non-Goals

**Goals:** 用失败测试固定本地查询/派发边界，暂停规则不再触发，起止时间可明确设置且回读核对；既有规则的非目标字段、完成实例、owner/expectedAccount 与不确定写入防重均保留。

**Non-Goals:** 不写真实 Todo、不读凭据、不启动/重启进程；不承诺网站后台推送或跨设备 exactly-once；本站点源码保持只读。AstrBot 周期创建和服务端纯查询先设计后另行实现，不把它们标为已完成。

## Decisions

1. `useSnapshot` 只接收缓存且淘汰旧在途同步，不调用 scan、不写 dedupe、不派发。定时 scan、明确 sync 和已确认 mutation 继续走原调度，避免破坏原用户启用的提醒。列表 IPC 保留旧返回结构。测试使用假 client/时钟/内存回调和独立临时 dedupe 文件。
2. 周期实例附带规则明确暂停时跳过；缺失规则仍沿用现有 fail-closed。提前量沿用实例的权威值，不自行把旧实例改成新计划。规则读取失败不猜时区。
3. 周期输入新增可选 startAt/endAt，格式为规则时区中的 LocalDateTime（分钟或秒），规范化到秒。省略保留已有值；新规则开始值仍默认为规则时区今日零点；空结束值清除限制。非法日期、时区缺口、倒置边界在写请求前拒绝。UI 展示明确边界，既有秒精度不因保存被截断。签名包含显式边界，自动默认值仍保持跨午夜不确定恢复稳定。
4. 不更改已有不确定创建 journal。回归要求同 ID 同内容只回读、同 ID 改内容冲突、新 ID 不能绕过相同未决创建、完成只影响指定实例、关闭提醒按站点清空字段。编辑 PUT 当前没有持久未决 journal，后续增加 expected revision/内容指纹与原请求回读，避免超时后覆盖他端更新；此轮不声称已具备跨端并发 CAS。
5. 站点后续设计：保留旧 GET 的生成语义供旧客户端兼容；新增明确只读快照入口（不发布 TodoListRequestedEvent、不 materialize），再提供显式、幂等的实例同步入口给调度器。必须有真实服务端测试证明 mapper/event 写入为 0，不能只加一个未被服务端识别的 query 参数。新客户端在老站点上显示“同步读取”，不伪称纯读。
6. 跨端契约用同一虚构 fixture：Sun=0、Mon=1…Sat=6，Cron 规范化去重；提前 MINUTE=0..N/DAY>0；wall-clock+IANA zone；start/end 包含时间且含边界；逻辑实例 ruleId+recurringInstanceAt，done 独立。站点预览 `withinRuleDates` 只比较日期，与服务端和桌面完整 LocalDateTime 不同，列为站点跟进项；一次性站点提醒跟随浏览器时区、桌面固定上海，也单独记录迁移需求。DST 用双方 fixture 并与 Spring Cron 权威输出比对，不仅比较两个同源实现。
7. AstrBot 后续扩展沿 owner 私聊草稿→预览→明确 confirm→Core writer→站点回执。新增单独 recurring DTO/endpoint，不改变一次性 DTO 默认；草稿包含星期、时间、IANA zone、提前量、起止时间，编辑重新生成请求身份，取消失效。无需新凭据或让外部 Skill 绕过 owner/confirm。

## Risks / Trade-offs

- 本地列表无即时派发 → 已启用调度的下一次 30 秒扫描仍可触发，测试必须区分查询调用和独立调度时钟。
- 站点 GET 有实例生成副作用 → 本轮不调用真实接口；纯数据库查询须站点后续合同落地。
- 暂停与列表读取可能有跨端竞态 → 抑制附带暂停规则的旧实例，保留失败时清空快照机制，真实 UI/网络竞态另验。
- 各端各自 dedupe → 明确是每设备防重，不声称两端共享投递锁；全局只提醒一次需另行产品决策与服务端协调。

## Migration Plan

先离线红灯→修复→专项/桌面全量/typecheck/build；保留用户桌面改动。本轮只提交任务范围代码和证据。生产激活、新版 UI 和真实双端临时规则另验，UAC 不重发；回滚可单独撤销本次代码提交，不迁移或删用户数据。
