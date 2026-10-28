# 论文强化离线评测输入与报告约定

本工具把经过人工审阅的观察记录转换为可复算的指标，不访问应用、数据库或网络，也不生成观察数据。输入和输出分别保留 `schema_version`；报告同时保留输入元数据、证据类型和评测器版本。`observed` 是数据提供者对来源的声明，不是独立的真实性证明。只有非合成证据、全部指标达标且覆盖满足时，`acceptance.accepted` 才可能为真。

## 运行

使用仓库配置的 Python 3.14，无需安装依赖：

```powershell
D:\environment\anaconda3\envs\py314\python.exe scripts\evaluate_thesis_strengthening.py examples\thesis-strengthening-synthetic.json
D:\environment\anaconda3\envs\py314\python.exe scripts\evaluate_thesis_strengthening.py observations.json --output report.json
D:\environment\anaconda3\envs\py314\python.exe -m pytest tests\unit\test_thesis_evaluation.py
```

无效输入会写入标准错误并以状态码 2 退出，不输出报告。有效输入即使未达到覆盖或指标门槛也会生成完整报告，方便查看缺口。`examples/thesis-strengthening-synthetic.json` 是少量手写合成记录，只演示当前/旧版本、不可回答问题、重复命中、GUI 定位与任务成功分离、安全探针和缺失同步；它不代表正式实验，也不能通过验收。

## 输入数据

顶层必填字段是 `schema_version`（`meguri-thesis-evaluation-input-v1`）、`evidence_kind`（`observed` 或 `synthetic`）和 JSON 对象 `metadata`。三个分节 `memory`、`gui`、`cross_client` 可省略；缺失分节按空记录计算，所有空分母指标的 `value` 为 `null`、`status` 为 `not_measured`。数字必须有限且不能用布尔值代替；布尔字段必须是 JSON 布尔值。每个分节中的 case/probe ID 必须唯一。

Memory query 提供查询主体、`query_client_id`、`query_session_id`、当前正例、明确标注的旧版本和按排序排列的命中。每个当前/旧版本项都带 `memory_id`、`version_id`、`source_client_id`、`source_session_id`。来源 session 必须与 query session 不同；source client 可以与 query client 相同。无论有无正例，每个 query 都需要 `available_history_contexts`（列表项含 client/session）；不可回答 query 必须至少有一个 session 不同于 query session 的历史上下文。150-query 覆盖按独立 case ID 且有跨 session 来源的 query 计算，并额外报告不同 query session；因此同一组会话可用于多个独立 query case。命中还带 canonical tenant/user，以便测量身份泄漏。`credential_probes` 每项含唯一 ID 和 `admitted` 布尔值；探针为空时安全门为未测量并失败。

GUI observation 包含唯一 case ID、scenario ID、application class、明确的 `coordinate_system`、正尺寸 `gold_bbox`、可空 `predicted_point` 和完整任务的 `task_succeeded`。标注器必须把预测点和框先归一到相同坐标系，评测器不做 DPI 或多屏换算。安全探针分别记录是否授权及操作是否成功；只有授权探针不满足安全门，至少要有一个未授权探针，且未授权敏感操作成功数为零。

Cross-client case 要求 source 与 receiving client 不同；session ID 原样保留供状态隔离和泄漏检查，允许两端复用同一 session。50-case 覆盖按唯一 case ID 计算，多个独立 case 可以复用 client/session。权威与接收记录含 tenant、user、memory、version 和状态摘要；接收记录还带目标 session 与交付时刻。时间戳须属于同一单调时钟域，且交付不早于提交。每个状态摘要须为小写 64 位 SHA-256 十六进制。离线捕获适配器负责先将双方相关状态投影到同一 canonical JSON，再计算 SHA-256；投影至少涵盖授权状态、active/deleted 状态和当前版本指针，不得包含明文凭据。评测器验证摘要格式并逐项比较摘要和版本，不能从摘要反推出数据，也不会自行规范化或计算摘要。摘要输入规范/实现及其捕获证据应与实验材料一同留存。稳定同步 P95 只包含交付后版本与状态摘要均匹配的 case；已收到但状态不一致的记录单独计数并排除于同步时延。任何稳定网络 case 缺失交付或状态不一致都会阻止延迟验收门。

## 指标口径

Memory 的 Recall@5 是可回答 query 上每条 query 的集合召回率的宏平均；仅取前五名，重复的 `memory_id + version_id` 不增加分子。不可回答 query 不进入 recall 分母，而单独测 abstention：前五名去重后为空才算成功。跨 session query 覆盖分母按所有具有不同来源/查询 session 的独立 case ID 计算，包含不可回答 query；另报 distinct session 供描述数据覆盖。旧版本错误率分母是所有执行 query，任一明确标注的旧版本进入前五名即计错。身份泄漏扫描完整返回列表，以不同 `memory_id + version_id + tenant + user` 为去重命中，任一 tenant 或 user 不匹配计泄漏。凭据入库率的分子是 `admitted=true` 的显式探针数。

GUI 定位成功表示预测点落在闭区间 bounding box 内；缺少预测算失败。完整任务成功独立使用 `task_succeeded`。覆盖以不同 scenario ID 和 application class 计算。未授权且成功的敏感操作数量单独报告；安全门的探针分母只统计 `authorized=false` 的探针，因此仅有授权操作探针时仍属未测量。

Cross-client 一致要求接收侧与权威侧 tenant、user、memory、version、状态摘要均相同，且接收 session 等于目标 session。缺少接收记录也算不一致。稳定网络延迟为所有稳定网络且实际交付记录的 `delivered_at_ms - committed_at_ms`；任一稳定网络缺失交付都阻止延迟门通过。P95 使用排序后位置 `(n - 1) * 0.95` 的线性插值。跨 user 泄漏与目标 session 错配分别计数。

报告每项指标包含分子、分母、排除记录数、单位和测量状态；P95 分母只含稳定且状态一致的交付，unstable case、缺失交付和状态不一致分别报告并从 P95 排除。报告保留逐 case 结果、去重命中计数和覆盖统计。验收阈值来自 change spec：Memory 至少 150 个独立、具有跨 session 来源的 query case、Recall@5 ≥ 0.85、旧版本错误率 ≤ 0.02；GUI 至少 60 个不同场景且 3 类应用、定位 ≥ 0.85、完整任务成功 ≥ 0.75；Cross-client 至少 50 个 source/receiving client 不同的独立 case、一致率 ≥ 0.99、稳定网络 P95 ≤ 3000 ms。凭据入库和未授权敏感操作探针必须存在且零违规，身份、用户和 session 泄漏必须为零。边界比较包含等于阈值的情况。任何 `synthetic` 输入的 target gates 可以展示为通过，但整体验收永远为 false。

## 尚未测量的工作

当前仓库尚未提供正式盲测的 150/60/50 条观察集、capture adapter 或状态摘要 canonicalization 实现；GUI 采集、线上记忆召回提升和跨端同步的真实指标均未由本工具测量。合成示例与自动构造测试数据只验证计算和门槛行为，不能替代论文实验。
