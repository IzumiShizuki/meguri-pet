# Meguri 本地训练执行计划

- 数据版本：`meguri_v2_02c3db0c507d7c2d`
- 正式数据仓：`D:\program\meguri-pet\datasets\meguri`（只读）
- GPT-SoVITS：`D:\environment\projects\GPT-SoVITS`
- 独立环境：`D:\environment\miniconda3\envs\GPTSoVits`
- 模型输出：`D:\AI\models\meguri`

## 执行阶段

1. 独立复核 build ID、115 个数据仓校验和、必需入口、音频路径与场景级切分。
2. 盘点 GPU、驱动、Python、PyTorch、CUDA、FFmpeg、GPT-SoVITS commit、工作树状态和预训练权重哈希。
3. 对正式 TTS manifest 做非破坏性声学审计，生成 100 条分层人工试听队列。人工试听完成前不训练。
4. 从 manifest 在独立工作区生成 GPT-SoVITS 单行 filelist；test 只用于最终盲评，不进入训练、参考音频选择或调参。
5. 建立闭源 LLM + Prompt + RAG + JSON 契约的 A/B/C/D 固定评测 harness。没有 API 配置时只运行 Mock Provider 以验证流程。
6. 声学 Gate 通过后，先运行零样本基线，再运行小规模 GPT-SoVITS 基线；只有小规模基线显著优于零样本时才申请完整训练。
7. 文本 LoRA 仅在真实闭源 LLM 固定评测证明 Prompt + RAG + 状态机不足、且得到再次批准后考虑。

## 磁盘预算

当前 D 盘可用空间约 759 GiB。现有 `data/meguri` 约 1.58 GiB，正式数据仓约 0.06 GiB。

| 项目 | 预计增量 |
| --- | ---: |
| 核验、日志、报告、Mock 文本评测 | < 0.2 GiB |
| 声学审计与试听样本 | 0.2-1 GiB |
| GPT-SoVITS 预处理缓存 | 6-15 GiB |
| 小规模基线 checkpoints / samples | 5-12 GiB |
| 后续完整训练候选 | 15-30 GiB |
| 建议保留安全余量 | 50 GiB |

本轮不会重新下载现有预训练模型，也不会复制正式音频仓。

## 主要风险与控制

- **人工试听不可自动替代**：自动声学指标不能可靠判断台词匹配、BGM/SE、截断和角色音色；未完成 100 条试听时 Gate 不得判为 GO。
- **GPT-SoVITS 仓库已有本地修改**：环境仓为 dirty worktree；训练工程只读取并记录，不覆盖或回退这些修改。
- **正式 filelist 含多行台词**：训练工作区从 TSV 结构化读取并压成单行，不修改正式导出。
- **测试集污染**：test scene、test 音频和 test 文本不参与参考样本选择、训练或参数选择。
- **表情与 voice_style 是启发式标签**：第一版 TTS 优先单一稳定音色，不强行训练八种风格。
- **模型与素材版权**：训练产物仅保留本地，不上传或公开发布。
- **显存压力**：RTX 5060 Ti 16GB 使用 FP16、保守 batch size 和逐阶段 checkpoint；重型任务串行运行。
- **文本评测证据不足**：Mock Provider 只证明 harness 可运行，不能作为文本 LoRA 的效果证据。

## 必须暂停并请求批准的节点

- 下载大型模型或额外 ASR 模型；
- 开始较长的完整 TTS 训练；
- 开始文本 LoRA/SFT；
- 破坏性音频处理；
- 上传或发布数据、素材或权重；
- 修改正式数据仓。
