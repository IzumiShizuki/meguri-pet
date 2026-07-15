# Meguri 文本 LLM 训练结果

日期：2026-07-16  
结论：**训练与评测完成；模型已登记为 `evaluated`，Staging NO-GO，Production 未授权。**

## 模型身份

- Model ID：`qwen35-4b-l2-full-s3407-20260716-v1-a2dec9d9d481`
- 基模：`Qwen/Qwen3.5-4B@851bf6e806efd8d0a36b00ddf55e13ccb7b8cd0a`
- 数据集：`meguri-text-sft-v1-532aca8b1a5d`
- 数据 build：`meguri_v2_02c3db0c507d7c2d`
- 选中 checkpoint：`checkpoint-450`
- Adapter SHA-256：`a2dec9d9d481aa8e118afc61838cb1d9e80136c3bfac5b051a62efb0a96b87ef`
- Registry 状态：`evaluated`

## 训练证据

- 训练样本：2626；验证样本：566。
- BF16 LoRA，1.5 epoch，494 optimizer steps。
- 训练 loss：`1.1333299928348557`。
- PyTorch peak VRAM：`11.219264 GiB`，通过 14.5 GiB 上限。
- 训练后 JSON smoke：通过。
- 训练 Manifest 明确记录 `locked_eval_accessed=false`。

验证集使用固定 safety 和完整 566 条 validation 选择 checkpoint，未使用 locked eval：

| 候选 | Composite | Schema valid | Language match | Safety |
| --- | ---: | ---: | ---: | ---: |
| checkpoint-450 | 0.930124 | 0.991166 | 0.931095 | 1.0 |
| final adapter | 0.926944 | 0.985866 | 0.925795 | 1.0 |

## Locked eval 最终测量

选模和导出完成后，唯一选中 adapter 才运行固定的 92 日文 + 92 中文 locked eval；固定 Prompt + RAG、left padding 1152，backend errors 为 0。

| 指标 | L0 Base | L0 Prompt+RAG | 训练后候选 |
| --- | ---: | ---: | ---: |
| Automatic score | 0.200544 | 0.200000 | 0.922917 |
| Schema valid | 0.000000 | 0.000000 | 0.951087 |
| Language match | 0.000000 | 0.000000 | 0.923913 |
| Relationship severe-error-free | 0.000000 | 0.000000 | 0.967391 |
| Mode consistent | 0.005435 | 0.000000 | 0.967391 |
| Memory candidate error | 0.000000 | 0.000000 | 0.000000 |

Locked eval policy 明确为：不用于训练、Prompt 调参、early stopping 或 checkpoint 选择。

## Staging 决策

当前自动 Staging gate 为 `fail`。通过项包括非法枚举、关系、模式、记忆边界、安全和相对提升；失败项如下：

- JSON parse rate：`0.967391 < 0.995`（6/184 parse failures）。
- Schema valid rate：`0.951087 < 0.99`（9/184 schema failures）。
- Extra-field rate：`0.016304 > 0.005`（3/184）。
- 人工 persona review 尚未执行；但即使人工分数通过，前三个自动门禁仍会阻止 Staging。

因此不得将 Registry 状态提升为 `staging_candidate`，不得修改 candidate/last-good 路由，也不得把本次训练成功解释为 hosted Staging 或 Production 成功。

## 后续处理边界

1. 当前 adapter 可保留作本地离线分析和 `evaluated` 基线，不进入 Staging 流量。
2. 下一训练迭代只能使用 train/validation 证据。checkpoint-450 的 validation 有 5 个 parse failures，模式为缺少起始 `{` 或 `reply` 重复至 token 上限；可据此研究通用的重复抑制、JSON 约束解码或训练数据格式强化。
3. 不得读取 locked eval 失败正文来构造训练样本、修改 Prompt、选择 checkpoint 或调整推理参数。
4. 若要评估经过 validation 驱动改进的新候选，应先由独立流程冻结新的、未泄漏的 locked eval 版本；不能反复使用本次 184 条结果调到通过。
5. Production 始终保持 `production_ready=false`，需要独立审批和发布门禁。

## 证据摘要

- Experiment Manifest SHA-256：`c11bd62c2fb6a51a1d17469c73147b61c687b1fb4b97635b2d46b46d10b859bb`
- Validation selection SHA-256：`f37cf14154dde640354e754c07921c83dd007d932c4d681e73769d2cb78bbbf9`
- Export Manifest SHA-256：`184e61764698022b4eaaa0eb40ec2a74c940dc4195a662172fb2f56e53a5eeee`
- Locked eval report SHA-256：`294ae54550e889578b90597c7239fb361a893148f63c37684aa8d27e85da5d56`
- Comparison report SHA-256：`0bdd667796a41b6bd9e0d2b297fc8060212c43179268077ff77fa5a360736e0e`

