# 日语完整训练报告

`full_ja_001` 已完成 GPT 20 epoch 与 SoVITS 20 epoch。中文语音开发已停止，中文历史样本不参与本模型的训练或评测。

- 训练集：1306 条；GPT 有效 1275 条；测试集参与训练 0 条。
- 最终 GPT SHA-256：`5b2448a964de5c3eb3119752038a41e1e490d3360f5f42c838af5c26aa63ef35`
- 最终 SoVITS SHA-256：`55911e156a9b67e7af649acc737fe9cb991dae6811cefa14683e7049832ad501`
- 固定评测：10 条日语，全部成功生成并通过 32 kHz 单声道校验。
- GPT 最终 loss：`339.59`；top-3 accuracy：`0.916`。
- 完整模型热启动 RTF：`0.281849`；4 epoch 基线：`0.293984`。
- 发布状态：`PENDING_FINAL_JA_LISTENING`；权重尚未部署到任何服务。
