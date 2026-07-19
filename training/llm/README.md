# Meguri 文本 LLM 流水线

本目录实现 Notion 计划 17 中的纯文本模型工作，不包含也不会调用任何
TTS 数据、训练或推理代码。

执行顺序固定如下：

1. `L-001`：环境与精确模型版本兼容性探测。
2. `L-002` / `L-003`：对只读源数据进行确定性转换并执行质量门禁。
3. `L-004`：在 locked eval 集上执行冻结的 L0 评测。训练和 validation
   代码无法访问 locked cases。
4. `L-005` / `L-006`：可复现的 LoRA/QLoRA 训练入口，以及包含 100–200
   个样本的 smoke 训练。
5. `L-007` 及以后：完整实验、固定评测、模型 Registry 与 Staging。

权威源数据 build 为 `meguri_v2_02c3db0c507d7c2d`。规范数据和
`datasets/meguri/exports` 始终按只读方式处理。派生数据写入
`training/llm/artifacts`；每个派生数据集都有独立 Manifest，以及根据内容
计算出的 dataset ID。

已批准的 exports 包含服装代码 `07` 和 `08`，但两者在运行时均处于禁用
状态。为了保持固定的 GO 数量，流水线会保留这些数据并确定性标记为
`private`；这不会启用对应服装，因为服装可用性仍由外部运行时决定。
转换后的记录会写入
`interaction_mode_source=deterministic_outfit_map_v1`。

GO exports 还包含旧值 `voice_style=embarrassed`，该值不属于已固定的
运行时 Schema。转换器会记录这一情况，并确定性归一化为 `restrained`；
源记录保持不变，Manifest 与质量报告会公开归一化策略及数量。

主模型是 `configs/qwen35_4b_bf16_lora.yaml` 中固定版本的官方
`Qwen/Qwen3.5-4B`。Qwen3.5 是多模态模型，但本流水线会冻结全部视觉层，
只训练语言 attention/MLP 模块。对比模型为使用 NF4 QLoRA 的
`Qwen/Qwen3-4B-Instruct-2507`。在明确门禁通过前，8B 配置保持禁用。

使用项目 Python 环境执行不下载模型的预检：

```powershell
python -m training.llm.scripts.probe_environment --mode static
```

完整探测必须使用专用 LLM 环境，并显式传入 `--allow-download`。完整探测
通过后才允许执行 smoke 或完整训练。每份成功的 L-001 报告还必须在探测
证据中记录精确的 `python -m pip freeze` 环境锁；缺少该快照的探测不视为
完成。带版本的探测、评测与训练任务均采用 fail-closed：Git 工作树必须
干净，且整个操作期间记录的 commit 不得变化。

## 可复现命令

Windows/Blackwell 模型环境与应用环境相互隔离。先安装固定的 CUDA wheel，
再安装其余依赖锁：

```powershell
D:\environment\anaconda3\envs\meguri-llm\python.exe -m pip install `
  torch==2.8.0 torchvision==0.23.0 `
  --index-url https://download.pytorch.org/whl/cu128
D:\environment\anaconda3\envs\meguri-llm\python.exe -m pip install `
  -r training\llm\environment\requirements-windows-blackwell.txt
```

在 Windows 上，流水线会自动将 TorchInductor 和 Triton 缓存放置在
`D:\environment\cache\meguri-llm` 下，以规避原生路径长度限制。只有在
确实需要其他较短且可写的缓存根目录时，才应在启动前设置
`MEGURI_LLM_COMPILE_CACHE_ROOT`。最终解析出的缓存路径会写入 L-001 报告。

执行精确模型探测，并从只读源数据构建派生数据集：

```powershell
python -m training.llm.scripts.probe_environment --mode full --allow-download `
  --report training\llm\artifacts\reports\qwen35-full-probe.json
python -m training.llm.scripts.build_sft_dataset `
  --data-root D:\program\meguri-pet\datasets\meguri `
  --split-root D:\program\meguri-pet\data\meguri\aligned_v1\splits
```

L-006 命令会强制满足以下条件：100–200 条训练记录、50–100 个 optimizer
steps、完整探测通过、仅 assistant 标签、EOS/JSON 边界，以及训练后能生成
通过 Schema 的响应：

```powershell
python -m training.llm.scripts.run_smoke `
  --experiment-id qwen35-4b-smoke-s3407 `
  --dataset-dir <派生数据集目录> `
  --probe-report <通过的完整探测报告> `
  --input-pad-length 768 --allow-download
```

当前确定性的 160/40 L-006 子集长度范围为 652..755 tokens。Smoke 训练
必须固定 padding 到 768，使 Windows/Triton 只编译一种训练 shape；观测到
的最大长度和请求的 pad length 都会写入 smoke 数据集与实验 Manifest。
如果样本会被截断，命令将直接失败，不会静默退回可变 shape。

训练使用 Transformers causal-LM loss，并由 `SFTTrainer` 提供累计的
assistant token 数量。即使 Qwen3.5 的 forward 签名不能直接接收
`num_items_in_batch`，该方式仍能保证八个 microbatches 之间的 loss
归一化正确。评测阶段没有累计 item count，因此分母改为当前 batch 中
未被忽略的 assistant labels 数量。

完整训练使用同一入口，但不传 `--smoke`。恢复训练必须显式指定，而且只
接受同一实验目录下的 checkpoint。Checkpoint 排名只使用冻结的 validation
综合分和固定 synthetic safety suite；locked eval 在结构上被排除在选模
流程之外。

本地 validation 与 safety 任务必须显式指定固定的 `--input-pad-length`。
两者都会拒绝脏工作树或运行期间发生变化的 Git 工作树，并在报告中记录
精确的评测 commit 与框架版本。仅用于 validation 的 v2 解码实验还可以
设置有界的 `--repetition-penalty` 和 `--no-repeat-ngram-size`；两者都会
写入 backend metadata。`--force-json-object-start` 可以强制首批生成 tokens
为 tokenizer 编码后的 `{"` 前缀，但不会在生成后修复输出。不得根据
locked-eval 失败内容调整这些参数。

validation 选出的 profile 固定在
`configs/qwen35_4b_lora_decode_v2.yaml`。它的状态刻意保持为
`validation_selected`，不具备 Staging 资格：该 profile 必须在一套新的、
独立冻结的 locked set 上只测量一次，然后通过冻结 rubric 的人工 persona
复核。此前 184 条 locked eval 结果仅作为默认 v1 解码路径的证据，不得用于
调整或批准 v2。

该文件会按照固定的 generation-profile 合约进行校验，并绑定 base/tokenizer
revision、adapter digest、生成参数、validation 证据、safety 证据和旧 locked
suite 排除项。Profile 冻结后，评测和推理必须使用 `--generation-profile`，
不得再次通过零散命令行参数重复或覆盖这些配置。

```powershell
python -m training.llm.scripts.train --experiment-id <实验ID> `
  --dataset-dir <数据集目录> --probe-report <探测报告> `
  --smoke-report <通过的L-006实验Manifest> `
  --input-pad-length 768 --allow-download
python -m training.llm.scripts.resume --experiment-id <实验ID> `
  --dataset-dir <数据集目录> --probe-report <探测报告> `
  --resume-from-checkpoint <实验checkpoint>
```

只能在模型和配置冻结后运行 locked eval。必须显式确认其 evaluation-only
用途；已提交的 fixture Manifest 会固定全部 184 个 case 的哈希：

```powershell
python -m training.llm.eval.run_locked_eval `
  --run-id <冻结运行ID> --run-kind post_train `
  --eval-root D:\program\meguri-pet\datasets\meguri\exports\eval `
  --rag-jsonl D:\program\meguri-pet\datasets\meguri\exports\rag\chunks_train.jsonl `
  --train-jsonl <派生train.jsonl> --backend local --config <训练配置> `
  --adapter <已选adapter> --allow-download --input-pad-length 1152 `
  --acknowledge-locked-eval-is-evaluation-only
```

## 独立 v2 locked eval 与人工复核

下一次 v2 测量需要独立创建并提交一个带有新 `suite_id` 的 Manifest。Profile
明确排除了 `meguri-locked-eval-v1` 及其冻结输入哈希身份，因此仅重命名旧
suite 会被拒绝。位于 checkout 外部或未被 Git 跟踪的 Manifest 同样会被
拒绝。新 suite 与旧 L0 报告不可直接比较，因此三条路径都必须在同一个新
Manifest 和同一组输入上重新运行。

Suite 必须由训练/调参角色之外的人员准备和批准。冻结工具只读取候选
held-out 文件来计算 digest 与零重叠数量，不会导出正文。它要求新的 source
build identity、不同的 preparer/approver，并要求新 suite 与 train、
validation、旧 locked set 在 sample、input、full-case、scene 和规范化
near-input 维度均为零重叠。Near-input 使用写入 Manifest 的固定 `0.95`
相似度阈值：

```powershell
python -m training.llm.eval.locked_suite `
  --suite-id <新suite ID> --source-build-id <新评测source build ID> `
  --eval-root <独立新评测目录> `
  --dataset-dir <派生release数据集> `
  --previous-locked-manifest training\llm\eval\fixtures\locked_eval_manifest.json `
  --previous-locked-eval-root <旧locked eval目录> `
  --rag-jsonl <冻结RAG JSONL> `
  --prepared-by <独立准备者ID> --approved-by <独立批准者ID> `
  --source-authority <heldout数据权威来源> `
  --output training\llm\eval\fixtures\<新suite Manifest>.json `
  --acknowledge-independent-freeze-and-non-tuning
```

独立方必须审核并提交生成的 v2 Manifest，之后训练/评测操作方才能启动
评测。Manifest 只包含文件/内容集合 digest、重叠数量和声明，不包含 case
正文。

需要在同一新 suite 上运行以下三条路径：

1. 不使用 RAG、也不加载 adapter 的 Base L0。
2. 不加载 adapter 的 Prompt+RAG L0。
3. 使用冻结 v2 generation profile 的导出 adapter。

每条命令必须使用相同的 `--locked-manifest`、`--eval-root`、固定 Prompt、
Response Schema 和 `--input-pad-length 1152`。候选命令还需要以下参数：

```powershell
python -m training.llm.eval.run_locked_eval `
  --run-id <新suite候选运行ID> --run-kind post_train `
  --locked-manifest <已提交的新suite Manifest> `
  --eval-root <独立新评测目录> --rag-jsonl <冻结RAG JSONL> `
  --suite-rag-jsonl <冻结RAG JSONL> --dataset-dir <派生release数据集> `
  --previous-locked-manifest training\llm\eval\fixtures\locked_eval_manifest.json `
  --previous-locked-eval-root <旧locked eval目录> `
  --train-jsonl <派生train.jsonl> --backend local `
  --config training\llm\configs\qwen35_4b_bf16_lora.yaml `
  --adapter <导出的adapter> `
  --generation-profile training\llm\configs\qwen35_4b_lora_decode_v2.yaml `
  --input-pad-length 1152 --acknowledge-locked-eval-is-evaluation-only
```

固定 safety suite 必须使用同一个 profile。Comparison 采用 fail-closed：
候选与 safety 报告必须使用同一个 profile；全部 L0 与候选报告必须使用同一
locked-suite Manifest、相同输入哈希，以及相同且通过的独立性验证 digest。

自动门禁通过后，创建冻结 rubric 的 review packet。Packet 包含模型输出和
粗粒度 relationship/mode 上下文，但不包含源 sample ID；其内容仍然只能
用于测量：

```powershell
python -m training.llm.eval.human_review prepare `
  --locked-eval-dir <新suite候选输出目录> `
  --packet <复核packet.json> --review-template <复核表单.json>
python -m training.llm.eval.human_review finalize `
  --packet <复核packet.json> --completed-review <已完成复核表单.json> `
  --output <人工复核结果.json>
```

最终汇总要求完成全部 184 项评分，记录 reviewer 身份和时间戳，并声明
reviewer 独立、locked 内容未用于调参。批准条件为 persona score 不低于
`0.90`，JP 与 ZH 自然度分别不低于 `0.90`，且人工 safety 拒绝数为零。

只有 comparison gate 通过后，才能为同一个 adapter 注册独立的、绑定
profile 的部署身份。注册时会同时校验新 suite、Manifest、profile、adapter、
包含人工复核的 comparison 和 rollback target：

```powershell
python -m training.llm.scripts.register_model `
  --export-dir <导出的adapter> --experiment-manifest <实验Manifest> `
  --validation-selection <validation选模报告> `
  --locked-eval-report <新suite候选报告> `
  --comparison-report <新suite comparison报告> `
  --generation-profile training\llm\configs\qwen35_4b_lora_decode_v2.yaml `
  --model-id <独立的profile绑定模型ID> `
  --status staging_candidate --parent-model-id <evaluated v1模型ID> `
  --rollback-model-id <明确的last-good模型ID>
```

当前冻结的 184-case suite 在组装 Prompt+RAG 后，输入长度范围为 896..1143
tokens。本地对比使用 left padding 到 1152 tokens，使 TorchInductor 只处理
一种输入 shape；报告仍保留每条输入未 padding 前的长度。Base、Prompt+RAG
与 adapter 路径必须使用相同的 pad length。

## Staging 边界

Gateway 需要认证，兼容 OpenAI 接口，并在发送 JSON 或 SSE 前校验完整的
Meguri 响应。它会强制校验固定 Registry digest、Prompt hash、timeout、
concurrency、生成取消，以及 candidate/last-good 路由。仓库内的 routing
state 刻意保持未配置并采用 fail-closed；在 evaluated 模型产物与 last-good
Registry 条目存在前，Gateway 不会进入 ready 状态。切回 last-good 使用
`training.llm.scripts.switch_staging_model`，不需要重新构建模型。

绑定 profile 的候选必须使用独立的 deployment model ID。Registry 与
Gateway 会将 profile ID 和 SHA-256 与 adapter digest、base/tokenizer
revision 一起校验；Gateway 会实际执行已固定的生成参数，并返回
`X-Meguri-Generation-Profile-Id` 和
`X-Meguri-Generation-Profile-SHA256`。Profile 字段为 null 的旧 v1 条目
继续保留原始默认解码行为。

Environment Agent 提供的机器可读交接契约位于
`ops/contracts/llm-agent.environment-contract.json`，人类可读的 Staging
交接说明位于 `docs/contracts/llm-staging-handoff.md`。这些文件只能授权
candidate Staging 路由；本分支中的任何代码都不会据此把模型标记为
Production-ready。
