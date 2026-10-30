## Why

AstrBot 的 owner 私聊草稿目前只能创建一次性提醒，不能表达站点及桌面已有的多星期、时区和提前提醒规则。站点列表 GET 还会生成实例，不能作为无副作用查询使用。

## What Changes

- 新增明确的周期草稿语法、预览/修改/取消/确认，保留一次性接口和草稿兼容。
- Core 新增 owner-scoped 周期写入接口，复用认证、请求日志、结果核对与不确定状态恢复。
- 使用共同fixture对齐星期、六字段Cron、IANA时区、起止时间、提前量；添加兼容的站点纯查询与显式同步入口，并接入桌面查询/调度边界。
- 所有验证为本地隔离测试，不修改生产待办、凭据、权限或运行服务。

## Capabilities

### New Capabilities
- `timeprism-recurring-drafts`: owner私聊周期草稿确认及幂等写入。
- `timeprism-pure-snapshot`: 保留旧客户端的纯列表快照和显式实例同步边界。

### Modified Capabilities
无；原一次性接口语义保留。

## Impact

Meguri AstrBot parser/store/client、Java reminder controller/writer、新合同fixture与桌面TimePrism客户端；站点已有LightApp controller/service及隔离测试。站点拥有独立OpenSpec记录。不引入依赖、数据库迁移、新Todo系统或真实平台调用。
