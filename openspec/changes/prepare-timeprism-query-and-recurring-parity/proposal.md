## Why

Todo 查询当前会同步触发本地提醒派发；附有暂停周期规则的旧实例仍可能被桌面扫描选中。桌面已有多星期、提前提醒、规则时区和创建防重，优先补齐这些可复现边界，并明确站点 GET 生成实例的兼容限制。

## What Changes

- 将桌面快照接收与提醒派发分开，由明确调度入口扫描；保留原定时同步和已确认变更流程。
- 暂停或缺失周期规则时抑制实例提醒，保留完成状态和实例身份。
- 让周期规则创建/编辑显式接收起止日期时间，并维持未提供字段的原值及既有跨日恢复语义。
- 为跨端同一规则的星期、提前量、时区、日期实例、重复请求建立隔离契约测试。
- 准备站点纯查询接口、AstrBot 周期草稿和双端验收后续设计；本轮不写站点、不操作生产待办、不部署。

## Capabilities

### New Capabilities
- `timeprism-query-delivery-boundary`: 查询/快照与派发分离，以及暂停规则的提醒边界。
- `timeprism-recurring-contract-parity`: 复用现有周期规则合同，补齐日期边界输入和跨端验收。

### Modified Capabilities
无已归档主规格；相关既有变更作为兼容背景保留。

## Impact

主树影响 `apps/desktop-meguri/src/main/shizuki-reminders.mjs`、`index.mjs`、`shizuki-timeprism.mjs`、共享桌面合同及周期编辑器和相应测试。网站、Core/AstrBot 周期写入扩展仅提供后续设计，不在本次更改权限、凭据或数据库。现有用户改动、旧 Core 和已取消 UAC 状态保留。
