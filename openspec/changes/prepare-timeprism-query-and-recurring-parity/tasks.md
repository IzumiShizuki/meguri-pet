## 1. Evidence and regression

- [x] 1.1 核对主树、站点只读合同和用户未提交工作，记录既有实现与未测项。
- [x] 1.2 新增并先运行查询不派发、旧快照隔离、暂停规则、显式日期边界失败回归。
- [x] 1.3 关闭 web_fetch 摘要额度审查问题，核对归档结果而不导入候选。

## 2. Desktop implementation

- [x] 2.1 分离快照接收和扫描，保留显式调度及已确认 mutation。
- [x] 2.2 抑制暂停规则的旧实例提醒，保留缺失规则 fail-closed。
- [x] 2.3 接入起止时间输入、规范化、验证、回读和 UI，保持创建 journal 兼容。

## 3. Verification and handoff

- [x] 3.1 跑针对性和桌面完整单元测试、typecheck、build、OpenSpec strict 与 diff 检查。
- [x] 3.2 交付站点纯查询/AstrBot 周期草稿/跨端时区与更新幂等的后续方案及验收矩阵。
- [x] 3.3 核对用户改动保留并提交本次安全代码；不 push、不部署、不操作生产 Todo。
- [ ] 3.4 在另行允许的加载窗口完成真实 UI 与站点临时规则验收（本轮 UAC 待确认）。
