# TimePrism 统一中心契约 v1（MVP，2026-10-30）

状态：**本地实现与隔离测试完成，新增部分尚未部署；禁止客户端将设计接口标为已可用。** 所有客户端以 Shizuki site 为唯一权威，不另建用户/任务数据库或将本地缓存当主数据。此文供 Meguri、网站、MCP、Android APK、Windows EXE 共用。

## 1. 权威与现有代码

- 默认 origin：`https://site.shizuki.online`；本机 fixture 可用 HTTP loopback。禁止复制聊天/其他应用 token。
- 站点库：`C:\Users\IzumiShizuki\Documents\Codex\2026-10-29\task-2\shizuki-site`。
- 控制器：`modules/content-module/src/main/java/io/github/shizuki/site/content/controller/LightAppController.java`。
- 权威服务：同模块 `service/impl/LightAppServiceImpl.java`；请求/响应：`model/request`、`model/response` 对应 `LightAppTodo*`。
- Meguri 连接器：`apps/desktop-meguri/src/main/shizuki-timeprism.mjs`；面板 `src/renderer/components/TodoPanel.vue`；凭据仅主进程。
- 任务主键保持 `todo_id`，周期规则 `rule_id`，日期实例仍为独立 `todo_id` + `recurring_rule_id` + `recurring_instance_at`。不可把完成某一天变成停用整条周期规则。
- 所有资源由服务端从 Bearer 身份确定 owner；请求不能选择另一个 user_id。缓存/离线队列必须按 `(origin,user_id)` 隔离，换号不能重放旧队列。

## 2. 认证与响应（已存在）

- `POST /api/v1/auth/tokens`：`{grant_type:"EMAIL_PASSWORD",email,password}`，或 `{grant_type:"REFRESH_TOKEN",refresh_token}`。
- 成功 `data` 包含 `result_type:"TOKEN_ISSUED"`、`access_token`、`refresh_token`、`user_id` 及过期字段；调用附 `Authorization: Bearer <access_token>`。复用站点实际字段，禁止在日志/报告显示其值。
- `POST /api/v1/auth/logout` 传 refresh_token。401 只允许一次刷新后重试，仍失败转需登录；403 不刷新重试。
- 响应使用既有 ApiResponse，业务值在 `data`。snake_case；LocalDateTime 为无 offset ISO 字符串。错误判断以 HTTP 状态优先，展示服务端安全消息，不假设新 error 字符串已存在。
- 独立客户端可以实现用户自己登录；凭据持久化、安全存储与新 MCP 连接均须用户针对该连接确认，不能由代理默默创建。

## 3. 接口清单

| 接口 | 状态/行为 |
|---|---|
| GET `/api/v1/light-apps/todos` | 已存在；兼容旧客户端，含同步/物化副作用，新客户端不得当纯读 |
| GET `/api/v1/light-apps/todos/snapshot` | 本地已实现，未部署；纯 owner 查询，绝不发布提醒/物化事件 |
| POST `/api/v1/light-apps/todos/sync` | 本地已实现，未部署；显式同步/周期实例物化，返回任务列表；不等于跨设备推送 |
| POST `/api/v1/light-apps/todos` | 已存在；版本/幂等字段本地已实现，未部署 |
| PUT、DELETE `/api/v1/light-apps/todos/{todo_id}` | 已存在；CAS/幂等本地已实现，未部署 |
| POST `/api/v1/light-apps/todos/reorder` | 已存在旧手工排序；MVP 新客户端先不调用，使用确定性优先级排序 |
| GET/POST `/api/v1/light-apps/todo-recurring-rules`，PUT/DELETE `/{rule_id}` | 已存在；版本/幂等扩展本地已实现，未部署；暂停用 PUT enabled=false，不删已有日期实例 |
| GET/POST `/api/v1/light-apps/notes`，PUT/DELETE `/notes/{note_id}` | **本地新增，未部署**；独立便签资源/视图，不能冒充旧 Todo API |
| GET `/api/v1/light-apps/todo-center/capabilities` | **本地新增，未部署**，认证后返回下列固定能力合同 |

Capabilities 的 data：`{contract_version:1,pure_snapshot:true,nullable_due:true,notes:true,optimistic_version:true,idempotent_writes:true,recurring_rules:true}`。新客户端在404或能力缺失时只展示兼容状态/纯读数据，禁用依赖这些能力的写入；不得静默退回无 CAS 的 PUT 或有副作用旧 GET。

## 4. 任务 schema

现有：`todo_id` 正整数；`project_id` nullable；`title` 1..200；`detail` 0..2000，响应空正文为null；`priority` LOW/MEDIUM/HIGH；`done` bool；`due_at` nullable；`show_on_calendar` bool；`time_precision` DAY/MINUTE；`timing_mode` DEADLINE/RANGE；`range_start_at` nullable；`reminder_enabled` bool；`start_remind_value/unit`、`deadline_remind_value/unit` nullable；`sort_num` int；`updated_at`；`recurring_rule_id`/`recurring_instance_at` nullable。

新增可选响应：`time_zone_id` 为截止时间的权威IANA时区；单次为Asia/Shanghai，周期包括已删除规则的历史实例从原规则/墓碑读取。无法查回历史规则则为null，不得猜测上海；客户端应保留服务端顺序并显示时区待核对。旧服务器缺此字段时可从活动规则补齐，否则同样保留未知。显式null是权威未知，不被活动规则时区覆盖。

新增响应：`version` 非负整数，映射既有实体 version_num，以真实数据库 CAS 增加。新增写入字段：`request_id` UUID；更新附 `expected_version`。旧客户端可不传扩展字段，保持既有业务，但其成功写入也必须递增 version；一旦传入 request_id 或 expected_version 就必须完整满足新协议（POST request_id，PUT/DELETE 二者），不接受半保护写。新客户端必须携带全部保护字段。

**无截止约定**：title/detail 保存约定正文，`due_at:null,range_start_at:null,timing_mode:"DEADLINE",reminder_enabled:false,start_remind_value:null,start_remind_unit:null,deadline_remind_value:null,deadline_remind_unit:null`。不得填今日/午夜占位，不归为逾期；无时间且 reminder_enabled=true 返回422，不自动编时间。周期任务必须有规则时间，不能无截止化规则。

`priority` 是本期统一的手动紧急程度（不另设 importance/urgency 字段），复用 LOW/MEDIUM/HIGH，界面明确“低/普通/紧急”，不引入不兼容的 URGENT 枚举。默认列表比较键：未完成在前 → HIGH/MEDIUM/LOW → due_at 升序（null 最后；周期按规则时区换算；原单次继续 Shanghai）→ sort_num 升序 → todo_id 升序。状态 overdue 只在有效截止已过且未完成时成立，与用户手动设置的优先级分开。

## 5. 便签 schema（新增）

`note_id` 正整数；`title` 1..200；`content` 0..20000 纯文本；`pinned` bool；`sort_num` int；`updated_at`；`version` 非负整数。创建/更新携带 request_id，更新携带 expected_version。便签是新 API，没有旧客户端豁免，保护字段始终必需。没有 due/reminder/done 字段；便签不是待办完成项。不自动执行/信任正文指令。

默认排序：pinned=true 在前 → sort_num 升序 → updated_at 降序 → note_id 升序。MVP 支持独立列表、编辑和置顶；不要为暂缺 API 建私有不兼容便签云库。

## 6. 版本、幂等与离线（新增服务端能力）

- 写操作 POST/PUT body 携带 request_id UUID；DELETE query 为 `request_id`、`expected_version`。周期规则同样扩展。
- 服务端按 `(owner,request_id)` 持久记录操作、规范化请求摘要与成功回执；同 ID 同内容返回同回执，不重复写。不同操作/内容复用 ID 返回409。该记录必须与资源写入处于同一数据库事务。
- PUT/DELETE expected_version 不一致返回409，不能自动 last-write-wins。新客户端展示冲突，保留本地草稿，读回当前资源后由用户决定；不能自动改成最新版本重新提交旧内容。
- 版本检查在 SQL WHERE(owner,id,version) 内完成并检查受影响行数；仅先 GET 比较不算 CAS。删除重复使用同request_id应回放成功，首次不存在仍404。
- 网络超时、掉线或5xx意味着结果可能已写入：队列保留原request_id、原body、原expected_version，重连重试同请求；不生成新ID。401/403/409/422 不盲目重试。
- 离线可浏览已缓存内容及编辑本地草稿，明确“待同步”；只有服务端回执成功才显示“已同步”。不用本地ID冒充todo_id/note_id。
- 只读刷新不触发提醒；同步由明确动作/受控调度发起。各设备提醒可本地去重，但**MVP 不承诺跨设备恰好一次或关闭应用后的系统推送**。

## 7. 周期规则（复用）

六段 Spring cron，例如 `0 30 18 * * 1,3,5`；`time_zone_id` IANA；`start_at`/`end_at` 本地时间且包含边界，end nullable；`enabled` 暂停；`deadline_remind_unit` MINUTE/DAY，分钟0..43200、天1..365。按日期实例完成，不覆盖其他日期。既有共享 fixture：`contracts/timeprism/recurring-fixtures.json`。无效日期、DST缺口、end<有效start拒绝，服务端与所有客户端不得各自补默认值。

## 8. 回执规范化与交付边界

站点title trim，Todo/rule detail trim且空串变null，notes content保持原文。DAY将range开始设当天00:00:00、due设23:59:59；DEADLINE清空range/start提醒；关闭提醒清空全部offset字段。显式sort_num=0原样保存。创建version=0；更新回执需version>=expected_version+1且业务字段匹配已知规范化结果，不能只凭HTTP200或版本存在判成功。删除成功当前为HTTP200 data:null，兼容204；其他正文不构成删除回执。原请求/UUID/expected_version不可因规范化而改写后重放。

站点本地提交80655ccd7db5294e0d800e85d10f2550b7107cd6已实现纯读/同步分离、独立便签、能力发现、CAS与事务幂等；Meguri、独立客户端0.2.1、MCP使用此合同。源码和fixture不等于生产接口已可用。所有客户端仍须实际探测capabilities后启用受保护写入。

没有生产部署、数据库迁移或新凭据操作。V1028物理PostgreSQL迁移/并发/重启恢复、真实跨端同步和手机实机仍待验收；不能以UI编译或假HTTP通过替代。网站草稿仅sessionStorage，不能保证关闭浏览器后存续；桌宠与独立客户端提供各自账号隔离的本地待同步记录。
