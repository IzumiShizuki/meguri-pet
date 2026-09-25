# Meguri structured context rollout

本变更以 Java Message DAG 为权威源。`ConversationContextReadModel`、结构化摘要和恢复决策都是可重建投影，不写回 Persona、正式 Memory 或消息 DAG 权威字段。Python 链继续使用 bounded deque 和 legacy session summary，不与 Java 压缩质量直接横向比较。

默认配置保持关闭：

- `MEGURI_CONTEXT_STRUCTURED_COMPRESSION_ENABLED=false`
- `MEGURI_CONTEXT_SELECTIVE_REHYDRATION_ENABLED=false`
- `MEGURI_CONTEXT_TOPIC_DETECTION_ENABLED=false`
- `MEGURI_CONTEXT_SEMANTIC_COMPRESSION_ENABLED=false`

建议按 shadow mode → 小范围 THINK 恢复 → 可选语义 worker 的顺序启用。语义输出超时、非法、source 不可验证时立即回退确定性策略；任何 read-model 缺失、过期、digest/revision 不匹配或 QUOTE/RESUME_FROM/TOPIC_LINK 都走完整 Harness 并在成功后重建投影。

回滚只需关闭对应 flag。原始 DAG、旧版摘要 JSON 和已有 trace 不删除；旧摘要没有结构化字段时只作为普通 `SUMMARY`，不会触发自动恢复。

评估必须同时记录 token compression ratio、constraint/state/temporal-causal accuracy、exact recall、rehydration hit/unnecessary rate、总 prompt tokens、成本和 fallback failures。没有同 release、同 workload、同 client metric 的有效 before/after 时，TTFT 必须标记 `NOT_MEASURED`，不得推导收益数字。
