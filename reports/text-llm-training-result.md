# Meguri 文本 LLM 训练结果

日期：2026-07-16  
结论：**训练与 v1 locked eval 已完成；v2 解码配置已通过 validation-only 选择，但尚未做新的独立 locked eval。模型仍登记为 `evaluated`，Staging NO-GO，Production 未授权。**

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

## Validation-only v2 解码配置

既有 locked eval 完成后，只根据 checkpoint-450 的 validation 失败模式测试了通用解码约束；没有读取或使用 locked eval 失败正文。已冻结的配置位于 `training/llm/configs/qwen35_4b_lora_decode_v2.yaml`：

- `repetition_penalty=1.05`
- `no_repeat_ngram_size=4`
- 强制 tokenizer 编码的 `{"` 完整 JSON 对象起始 token 序列
- 首个完整 JSON 对象闭合后停止生成，不做输出后修复

相同 adapter 的完整 566 条 validation 与固定 safety 对比如下：

| 解码配置 | Composite | JSON parse | Schema valid | Extra field | Language | Expression | Intensity | Voice | Safety |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 默认 v1 | 0.930124 | 0.991166 | 0.991166 | 0.000000 | 0.931095 | 0.800353 | 0.699647 | 0.397527 | 1.0 |
| validation-selected v2 | 0.937279 | 1.000000 | 1.000000 | 0.000000 | 0.939929 | 0.805654 | 0.703180 | 0.416961 | 1.0 |

v2 共 566/566 可解析且 Schema-valid，固定 safety 为 8/8；报告记录 `locked_eval_accessed=false`。这只证明它是更好的 validation 候选，不能覆盖 v1 locked eval 结论，也不能据此提升 Registry 状态。

后续门禁工具链现已补齐：generation profile 经过 Schema、base/tokenizer revision、adapter digest 和 SHA-256 校验；新 locked suite 必须使用已提交 manifest，且 Base、Prompt+RAG、候选三条报告必须共享输入身份；人工 review 必须覆盖 184/184、分别满足日中自然度门禁并保留独立 reviewer/non-tuning 声明；Registry、Gateway 和 Release Manifest 会共同绑定 profile 与 locked-suite 身份。这些能力只使后续测量可审计，不代表测量已经执行或 Staging 已获准。

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
2. validation 驱动的 v2 解码配置已经冻结；不得再根据旧 locked eval 的失败内容修改它。
3. 由独立流程先提交带新 `suite_id` 和输入哈希的 locked-eval manifest；同一新 suite 必须重新运行 Base、Prompt+RAG 和 v2 候选三条路径，不能把新候选与旧 L0 结果直接比较，也不能重跑旧 184 条并据其结果继续调参。
4. 新 locked eval 自动门禁通过后，再按冻结 rubric 完成人工 persona review；两者都通过前不得绑定 Staging 路由。
5. 若 v2 失败，只能回到 train/validation 或构建下一训练候选；若通过，则需把 adapter、解码 profile、评测证据和 rollback identity 一起绑定为新的可部署身份，不能悄悄改写当前 v1 `evaluated` 记录。
6. Production 始终保持 `production_ready=false`，需要独立审批和发布门禁。

## 证据摘要

- Experiment Manifest SHA-256：`c11bd62c2fb6a51a1d17469c73147b61c687b1fb4b97635b2d46b46d10b859bb`
- Validation selection SHA-256：`f37cf14154dde640354e754c07921c83dd007d932c4d681e73769d2cb78bbbf9`
- Export Manifest SHA-256：`184e61764698022b4eaaa0eb40ec2a74c940dc4195a662172fb2f56e53a5eeee`
- Locked eval report SHA-256：`294ae54550e889578b90597c7239fb361a893148f63c37684aa8d27e85da5d56`
- Comparison report SHA-256：`0bdd667796a41b6bd9e0d2b297fc8060212c43179268077ff77fa5a360736e0e`
- v2 validation report SHA-256：`d9fca15d068d036a40c5ecfbeadedc27438bf798c9483fbe141defcf1c97e48c`
- v2 safety report SHA-256：`87aae409cd51e4d7d6ed17d96ea4253217982207cabc4067094d173a49688ce7`
- v2 validation diagnostic SHA-256：`6cf6057878f132f423d78c15ac981373801b0d01a5fcfffecab42fb988cdcf5c`
- v2 generation profile SHA-256：`3b36c58ddcb6a09499c7a5528054741d2f43f25c2b87c2a2b576cc5a1c170738`
