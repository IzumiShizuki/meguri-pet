# 桌宠跨会话记忆论文阅读笔记

日期：2026-10-12  
研究对象：毕业设计“基于多层记忆与桌面感知控制的跨端智能桌宠系统设计与实现”  
范围：记忆层次、候选抽取与风险审核、版本演化、混合召回、定时整合和评测。本文只新增研究笔记，不修改实现。

## 面向本项目的研究问题

本项目已有 `native_pgvector` 权威存储、Java/Python bridge、候选审核和版本链；embedding 与 rerank 目前在运行配置中关闭。以下研究可指导后续设计，但论文中的实验指标不是本项目的验证结果。项目验收目标仍应独立测量：至少 150 个跨会话样本 Recall@5 ≥ 85%，旧版本误召回 ≤ 2%，敏感凭据违规入库为 0。记忆流程应区分会话短期上下文与长期记忆；抽取结果先作为带出处和风险标签的候选，经规则/权限检查和人工审核后方可成为权威记忆；更新写为新版本并保留时间、来源、状态和当前版本指针；关键词/结构化检索与向量召回可组合，但降级路径应在 embedding 关闭时仍可用；定时整理只生成候选或摘要，不应越过安全审核。

## 文献记录

### 1. 递归摘要用于长期对话记忆

Q. Wang 等， “Recursively summarizing enables long-term dialogue memory in large language models,” *Neurocomputing*, 639 (2025), 130193. [DOI / ScienceDirect](https://doi.org/10.1016/j.neucom.2025.130193)。核实题名、作者首字母、卷期、文章号及摘要。访问范围：出版社摘要和页面可见的引言/方法性内容；页面提供 `Get rights and content`，本次未核实到允许改编的开放许可证，未读取或声称读取付费 PDF 全文。

方法上，先让生成模型概括短对话片段，再将新对话和旧摘要递归合并为更新摘要，并在回答时把最新摘要作为记忆上下文。论文称其方法可与长上下文及检索增强方法互补；其摘要报告在多个模型和长期对话设置中的一致性改进。局限是压缩可能遗漏细节；摘要中旧信息被带入新摘要的错误会逐轮传播；摘要并不天然保留来源证据、冲突或不可逆版本关系；页面摘要所述实验不可直接等同于本仓库目标指标。适配启示：每轮或每固定轮次整理可生成 session summary 候选，标明证据消息及覆盖时间，不自动覆盖已审核的长期事实；保留未压缩的会话/轮次记录供具体事实回查。

**指定论文译文状态：**未找到出版社页上可确认的 CC BY 等改编许可，因此不对该论文作长篇翻译。下文以具有可核实 CC BY 4.0 许可的 LongMemEval 正文替代；本论文的摘要与方法观察仍保留在本条。

### 2. 心理重要度和遗忘机制：LUFY

Ryuichi Sumida, Koji Inoue, and Tatsuya Kawahara, “Enhancing Long-term RAG Chatbots with Psychological Models of Memory Importance and Forgetting,” *Dialogue & Discourse*, 16(2) (2025), 74–110. [DOI / 期刊页](https://doi.org/10.5210/dad.2025.203)；[开放全文 PDF](https://journals.uic.edu/ojs/index.php/dad/article/download/14138/12125/93254)。访问范围：全文 37 页，核读摘要、§1–4.1 及实验/讨论相关页。文章标示 Creative Commons Attribution License 3.0。

LUFY 为对话记忆打分，融合情绪唤起、语言惊奇度、LLM 重要性判断、最相关召回次数与次相关召回次数等指标，再将加权强度和距上次访问时间用于保留/遗忘。存储同时保留结构化 profile、会话摘要和用户—机器人话语对；profile 会随会话更新，而摘要与话语对可参与遗忘。研究报告在人类参与者至少两小时、跨多轮会话的实验中，保留情绪显著内容并忘掉多数内容提升了满意度，检索 precision 相较无遗忘 RAG 提升超过 17%。局限：参与规模与场景仍有限；“情绪显著”不能替代用户明确授权或敏感度政策；论文指出语言困惑度并不等价于人类感知的惊奇，且把发言对当作事件是近似。适配启示：重要度可辅助排序、过期复核或低风险候选筛选，不可让低分触发无审计硬删除；用户明确纠错/更新、事实时效和风险级别应比热度分数优先。

### 3. MDR 个性化对话记忆蒸馏

Pengli Wu, Xuebing Yang, Yanlong Wen, and Wensheng Zhang, “MDR: Memory distillation and reproduction for personalized dialogue generation,” *Knowledge-Based Systems*, 336 (2026), 115252. [DOI / ScienceDirect](https://doi.org/10.1016/j.knosys.2025.115252)。核实官方页面作者、卷期、文章号及摘要。访问范围：出版社 article preview、摘要和引言部分；PDF 页面显示需机构访问，本次不声称读过全文。

MDR 以教师/学生编码器的知识蒸馏对齐查询、persona、历史对话与目标回复特征，构造更新的用户画像，并在解码阶段按回复 token 贡献调节生成，以平衡连贯性与多样性。摘要报告其在 ConvAI2 与 Baidu PersonaChat 上的实验结果。局限是论文主要处理训练期的个性化回复生成，不能直接证明生产记忆库的候选安全、来源审计、并发更新或旧版本误召回率；所用数据集与桌面宠物跨端任务存在域差异。适配启示：可借鉴“历史信息要影响回复”的目标，但权威事实演化应继续由显式候选审核及版本链负责，不能把隐式 profile 向量当作可审计事实。

### 4. LLM 与知识方法集成综述

Wenli Yang, Lilian Some, Michael Bain, and Byeong Kang, “A comprehensive survey on integrating large language models with knowledge-based methods,” *Knowledge-Based Systems*, 318 (2025), 113503. [DOI / ScienceDirect](https://doi.org/10.1016/j.knosys.2025.113503)。官方页核实为开放获取并标示 Creative Commons license；作者顺序以出版社记录为准。访问范围：出版社摘要、引言与分类/挑战相关可见内容；未据此宣称逐页读完全文。

该综述梳理 LLM 与知识库/知识图谱结合的表示、检索、生成与应用路线，讨论知识准确性、可解释性、运营和伦理问题。其价值是帮助区分“把文本交给生成模型”与“把结构化、可追踪事实交给系统查询”。局限在于覆盖面广而非针对个人长期记忆；综述的分类本身不是新的对照实验，具体效果取决于所引用方案。适配启示：把偏好、时间、来源、敏感度、状态和版本等放入结构化元数据；向量相似度只负责候选发现，不能决定事实权威性。

### 5. LLM 自主智能体综述

Lei Wang, Chen Ma, Xueyang Feng, Zeyu Zhang, Hao Yang, Jingsen Zhang, Zhiyuan Chen, Jiakai Tang, Xu Chen, Yankai Lin, Wayne Xin Zhao, Zhewei Wei, and Jirong Wen, “A survey on large language model based autonomous agents,” *Frontiers of Computer Science*, 18, 186345 (2024). [DOI / Springer Nature 全文](https://doi.org/10.1007/s11704-024-40231-1)。官方页核实开放获取，CC BY 4.0；访问范围：全文网页中的统一 agent 框架、记忆模块和评测章节及摘要。

文章以统一框架组织 agent 的 profile、记忆、规划、行动和评估，并综述社科、自然科学及工程应用。局限是系统综述覆盖面大，记忆机制不是单独实验对象，且相关 agent 文献迭代很快。适配启示：记忆服务应作为桌宠编排中的独立模块，为桌面感知和控制动作提供经授权的上下文；感知结果需要标记是用户陈述、屏幕观察还是模型推断，防止将观察猜测写成用户事实。

### 6. MemoryBank

Wanjun Zhong, Lianghong Guo, Qiqi Gao, He Ye, and Yanlin Wang, “MemoryBank: Enhancing Large Language Models with Long-Term Memory,” *Proceedings of the AAAI Conference on Artificial Intelligence*, 38(17) (2024), 19724–19731. [DOI / AAAI 官方论文页](https://doi.org/10.1609/aaai.v38i17.29946)。访问范围：官方摘要、元数据；PDF 链接公开，但此笔记未做全篇核读，也未找到该刊页面的改编许可说明。

MemoryBank 将对话记忆和用户人格特征用于长期陪伴，通过持续更新及受艾宾浩斯遗忘曲线启发的记忆强度，按时间衰减并在回忆时强化。论文以 SiliconFriend 为例，结合真实用户对话定性分析与模拟对话定量评估。局限：长期陪伴实验规模和评估设置不能直接代表真实跨端部署；初始模型较简化，只用提及/访问与时间建模重要度，缺少对错误事实、敏感数据和版本冲突的强治理。适配启示：把记忆访问次数/时间作为可配置召回特征即可，不能用“被频繁召回”来证明事实真实；候选风险审核应独立于重要度排序。

### 7. Generative Agents

Joon Sung Park, Joseph C. O’Brien, Carrie Jun Cai, Meredith Ringel Morris, Percy Liang, and Michael S. Bernstein, “Generative Agents: Interactive Simulacra of Human Behavior,” *Proceedings of the 36th Annual ACM Symposium on User Interface Software and Technology (UIST ’23)* (2023), Article 2, 1–22. [DOI / ACM](https://doi.org/10.1145/3586183.3606763)；[作者公开全文](https://arxiv.org/abs/2304.03442)。访问范围：作者全文 §4.1–4.2、反思/计划及用户评估方法；ACM 页面标为 Open Access，论文正文许可另需以出版页标注为准。

架构将观察写入带时间戳与访问时间的自然语言 memory stream，以 recency、importance、relevance 组合排序召回；反思对多条观察递归综合，并连回所依据记忆；计划也作为记忆供后续检索。局限：Smallville 是受控模拟环境，不证明真实用户信息抽取安全或长期可靠；作者也指出召回相关度、重要性和近期度仍有优化空间。适配启示：整合结果保存其证据记忆 ID/版本引用，使总结可回溯；定时任务可以产生反思候选，审核通过才转为长期事实。

### 8. MemGPT

Charles Packer, Sarah Wooders, Kevin Lin, Vivian Fang, Shishir G. Patil, Ion Stoica, and Joseph E. Gonzalez, “MemGPT: Towards LLMs as Operating Systems,” arXiv:2310.08560 (2023). [arXiv DOI](https://doi.org/10.48550/arXiv.2310.08560)；[arXiv 正文](https://arxiv.org/abs/2310.08560)。访问范围：全文 HTML §2–3、上下文队列/函数工具和多会话评测段；目前按预印本 DOI 标引，未列期刊 DOI。

MemGPT 以虚拟内存类比管理有限主上下文和外部 recall/archive 存储：FIFO 队列发生上下文压力时产生警告，模型可以调用函数保存/检索信息；驱逐消息时更新递归摘要，被驱逐内容仍保留在 recall store。局限：系统依赖模型自行调用写入/检索函数，自动操作可能遗漏或错误；存储生命周期、隐私授权与纠错机制需要应用额外实现；论文实验聚焦受控文档和对话任务。适配启示：把当前会话工作集与长期候选/权威存储分层；上下文压力提示可触发整理，但写入仍经候选审核和幂等桥接。

### 9. LongMemEval

Di Wu, Hongwei Wang, Wenhao Yu, Yuwei Zhang, Kai-Wei Chang, and Dong Yu, “LongMemEval: Benchmarking Chat Assistants on Long-Term Interactive Memory,” ICLR 2025 / arXiv:2410.10813 (2024). [arXiv 全文及版本记录](https://arxiv.org/abs/2410.10813)；[全文 HTML](https://arxiv.org/html/2410.10813)。arXiv HTML 明示 CC BY 4.0。访问范围：全文 §1–5、评测设计与§4.2 四项设计选择。

LongMemEval 以信息提取、多会话推理、时间推理、知识更新和未知问题拒答五类能力评估长期交互记忆；包括 500 道问题，并给出约 115K token 和约 1.5M token 两种标准历史长度。统一视角将记忆流程拆为索引、召回和阅读；论文研究轮次粒度、键扩展、时间感知查询扩展及结构化阅读。局限：离线问题集仍不能覆盖真实用户变化和全部多模态桌面场景；其设计试验中的相对提升不是任何实现都能复现的保证。适配启示：在本项目至少 150 个跨会话样本中显式纳入时间冲突、更新后旧值查询、缺失事实拒答、用户/助手来源区分和多跳问题；Recall@5 与生成答案正确率分开记录。

### 10. LoCoMo 长期对话基准

Adyasha Maharana, Dong-Ho Lee, Sergey Tulyakov, Mohit Bansal, Francesco Barbieri, and Yuwei Fang, “Evaluating Very Long-Term Conversational Memory of LLM Agents,” *Proceedings of ACL 2024 (Volume 1: Long Papers)*, 13851–13870. [DOI / ACL Anthology](https://doi.org/10.18653/v1/2024.acl-long.747)。访问范围：ACL 官方论文页及全文 PDF 摘要、数据构造、任务和错误/比较讨论部分。

论文提出以 LLM agent 对话生成、时间事件图和人工核验构造 LoCoMo；数据覆盖最长 32 个 session、平均约 600 轮和 16K tokens，并测问答、事件摘要和多模态对话生成。局限：合成对话加人工修订与真实用户长期交互不同；对话数量有限，且论文报告长上下文及 RAG 虽提升表现，仍落后于人类。适配启示：可借鉴时间事件、多跳和图像相关提问形式，但自有 150+ 样本要由本地真实会话/经同意的测试数据构成，不能把 LoCoMo 得分移植为项目效果。

### 11. Mem0

Prateek Chhikara, Dev Khant, Saket Aryan, Taranjeet Singh, and Deshraj Yadav, “Mem0: Building Production-Ready AI Agents with Scalable Long-Term Memory,” arXiv:2504.19413 (2025). [arXiv DOI](https://doi.org/10.48550/arXiv.2504.19413)；[arXiv 全文](https://arxiv.org/abs/2504.19413)。访问范围：全文 HTML 的 memory update algorithm、LoCoMo 评测和消融分析；目前按预印本 DOI 标引，未列期刊 DOI。

Mem0 从对话中抽取事实，检索已有记忆后分类为 ADD、UPDATE、DELETE 或 NOOP；其增强变体把图关系纳入记忆。论文在 LoCoMo 对比多种基线，报告答题分数、延迟和 token 成本方面结果。局限：更新算法依靠语义相似和模型冲突判断，论文展示的替换/删除流程本身不等于不可变历史审计；基准及模型/评审配置可能影响结果；作者和实现与 Mem0 产品生态关联，需区分论文测量与产品宣传。适配启示：四类操作可作为审核 UI 的变更意图标签；UPDATE/DELETE 应映射为带理由的新版本/软删除事件，不做物理覆盖；NOOP 也记录或返回可解释结果。

### 12. MemoryOS

Jiazheng Kang, Mingming Ji, Zhe Zhao, and Ting Bai, “Memory OS of AI Agent,” *Proceedings of EMNLP 2025*. [DOI](https://doi.org/10.18653/v1/2025.emnlp-main.1318)；[ACL Anthology 正文](https://aclanthology.org/2025.emnlp-main.1318/)；[arXiv 版本](https://arxiv.org/abs/2506.06326)。访问范围：全文 HTML §3–4 架构、存储、更新策略及 LoCoMo 实验/消融。

MemoryOS 将存储分为短期 dialogue pages、中期按主题聚合的 segments 和长期 persona；新页按 FIFO 从短期迁至中期，主题段以语义/关键词相关度分页，并在召回时结合近因会话与 persona。论文报告在 LoCoMo 上相对基线的 F1、BLEU-1 提升，并做模块消融。局限：论文性能建立在启用语义 embedding 的特定实验配置；分层阈值、热度替换和自动总结会引入错误合并或过时画像，且没有验证本项目的敏感凭据零入库目标。适配启示：可以借鉴 STM/中期整理/长期画像的逻辑层次，但数据库仍由 `native_pgvector` 作权威；当 embedding/rerank 关闭时，采用关键词和结构化回退，并独立评估每个召回通道。

## 可复用的设计结论与项目评测

跨论文更稳健的共识不是“记得越多越好”，而是让记忆保留来源、可分层、可更新、可检索并可拒答。递归摘要和中期整理适合压缩主题与上下文；它们会损失细节，故需保留底层会话证据。心理重要度和召回反馈可用于排序或复核优先级，不能替代真实性、安全敏感度与用户授权。事实更新需要时间戳和明确新旧关系；仅按向量相似度取 top-k 容易把已被更新的旧事实召回，因此应把当前版本过滤、有效时间、版本关系和来源纳入查询/答案上下文。混合召回应测试结构字段、关键词及向量通道独立和组合时的增益，同时确保向量服务不可用时仍能回答可由精确/全文检索命中的问题。

建议将本项目验收分成可复核的四组：①跨会话事实记忆集（至少 150 样本，Recall@5 ≥ 85%）；②更新/时间冲突集（旧版本误召回 ≤ 2%，明确“截至何时”的问法）；③安全拒写集（凭据、令牌、密码、支付/身份敏感值违规入库 0，并验证检测前后候选、日志和导出均无泄露）；④召回降级/整合集（embedding/rerank 关闭、关键词/结构检索、重复候选、每 10 轮自动整理、幂等重试）。检索命中应以含金标准证据的 top-5 衡量，不能以 LLM 自评代替；旧值被检出后回答应优先当前版本或明确不确定。

## 替代译文：LongMemEval §4.2 设计选择

**替代原因与许可。** 毕业材料指定的 Wang 等论文 [1] 页面未提供可确认的改编许可，因此没有长篇翻译该论文。下文选择与长期记忆直接相关的 LongMemEval 论文。arXiv 全文 HTML 的许可链接明确标注 CC BY 4.0，许可地址为 [Creative Commons Attribution 4.0 International](https://creativecommons.org/licenses/by/4.0/)；所据为 [arXiv:2410.10813v2 全文 §4.2，CP 1–4；§5.2–5.5 实验观察](https://arxiv.org/html/2410.10813)，2025-03-04 版本，正文约第 8–11 页/HTML 行 235–250、269–318。译文为下述段落的中文翻译，未将摘要或意译冒充译文；依许可署名并说明翻译改编。

**中文译文（CC BY 4.0 翻译改编；研究笔记译者：Codex）：**

我们归纳出聊天助手长期记忆的四个关键控制点，如图 4 所示。我们分析已有研究的设计选择及其局限，并提出相应优化。受篇幅限制，这里只概述这些设计，详细方案见第 5 节和附录 D。

**控制点一：记忆值。** 记忆值指存入记忆的会话内容采用什么形式和粒度。用户与 AI 的会话通常较长且涉及多个主题；把整场会话存为一个条目，可能妨碍有效检索与阅读。反过来，像既有工作那样把会话压缩成摘要或用户事实，也可能造成信息损失，使系统难以回答细节问题。第 5.2 节比较三种表示方式：存储完整会话、把会话拆成单轮，以及进一步抽取摘要或事实。

**控制点二：检索键。** 即使把会话拆分并压缩，每条记忆仍可能包含大量信息，而用户问题通常只与其中一部分相关。因此，把记忆内容本身直接当作检索键——这是既有工作中常见的做法——可能不是最佳方案。论文提出扩展检索键：从记忆值中抽取摘要、关键词、用户事实和带时间戳的事件，加入索引。这样能突出关键信息，并为检索提供多条路径。

**控制点三：查询。** 对于直接明确的问题，前述记忆值与检索键优化可能足以获得较高的召回准确率。但当查询带有时间指涉，例如“你上周末推荐的是哪家餐厅？”时，忽略时间的相似度搜索并不足够。论文采用时间感知的索引与查询扩展：将记忆关联到带时间戳的事件，并把检索限制在相关时间范围内。

**控制点四：阅读策略。** 回答复杂问题可能需要综合多条记忆。前述设计即便提高了召回准确率，也不代表语言模型一定能有效推理较长的检索上下文。第 5.5 节研究了阅读策略，并展示先抽取关键信息再回答（Chain-of-Note）以及使用结构化格式提示等优化，对提高模型正确利用检索结果很重要。

**相关实验观察（同一论文）。** 在值的粒度实验中，把整场会话拆成轮次后，GPT-4o 阅读表现明显提升；但把会话或轮次进一步换成摘要/事实，虽然节省 token，却常因信息损失而降低问答表现。多会话综合题是例外：统一抽取的事实有助于比较多个会话。不同阅读模型能处理的检索上下文长度也不同：Llama 3.1 8B 在检索超过 3,000 tokens 后表现显著下降，而 GPT-4o 在超过 20,000 tokens 时仍有提升。

论文还发现，只用压缩后的摘要、关键词或用户事实作索引键，并不会自动改善记忆召回。将抽取的用户事实与原始记忆内容拼接为复合键，能同时保留重点线索和原文信息；在论文设置中，这种扩展平均提高 Recall@k 9.4%，最终问答准确率 5.4%。对于时间问题，研究把记忆中事件的日期加入索引，再让模型从问题中推断时间范围以过滤无关条目；在轮次粒度下召回平均提高 11.3%，会话粒度下提高 6.8%。作者同时指出，这一方法依赖足够强的模型准确提取时间范围，较小模型可能遗漏或臆造时间线索。

最后，论文在只给出证据会话的“理想召回”条件下比较阅读方式，发现即使召回完美，不合适的阅读策略仍可让 GPT-4o 比最佳设置低最多 10 个百分点。将结果表示为 JSON 本身并不总优于自然语言；与 Chain-of-Note 配合后，结构化格式才对不同能力的阅读模型持续有益。Chain-of-Note 先逐条摘取证据，再基于简短笔记推理，减少模型在长上下文中漏掉关键细节的风险。

**翻译长度：**1,055 个汉字（译文正文及小标题，按汉字 Unicode 范围精确计数；不含译文前的许可说明和译文后计数字段）。

## 阅读与核验规模说明

本次共核实并记录 12 个互不重复来源。已能打开全文并核读正文段落的来源包括 [2] LUFY（官方 PDF 37 页）、[5] autonomous-agent survey（Springer 开放全文）、[7] Generative Agents（作者公开全文）、[8] MemGPT（arXiv 全文）、[9] LongMemEval（arXiv 全文 28 页）、[10] LoCoMo（ACL Anthology 全文）、[11] Mem0（arXiv 全文）、[12] MemoryOS（ACL/arXiv 全文）。为便于复查，保存了 [LongMemEval §4–5 的 CC BY 4.0 英文正文节选](longmemeval-ccby-excerpt-2026-10-12.txt)：基于官方 arXiv HTML 提取 H2–H4 标题及段落，节内空白合并为一个空格，段落间以两个换行分隔；排除标题/摘要、表格单元格、参考文献和非段落图形文字。按文件中的 `BEGIN/END EXCERPT` 边界计数，共 **10,284 个英文字符**（包含空格和段落换行）。PowerShell 可复核命令：

```powershell
@'
from pathlib import Path
t = Path('docs/research/longmemeval-ccby-excerpt-2026-10-12.txt').read_text(encoding='utf-8').split('--- BEGIN EXCERPT ---\n', 1)[1].split('\n--- END EXCERPT ---', 1)[0]
print(len(t))
'@ | & 'D:\environment\anaconda3\envs\py314\python.exe' -X utf8 -
```

这 10,284 字符是已访问、可定位正文范围内的可复核下限，不把付费预览/摘要页当作全文计数，也不声称全文每个字符都已精读。[1]、[3] 和 [6] 仅按摘要/预览范围使用；[4] 使用官方摘要和可见的综述内容，不加入字符总数。记忆译文按正文及小标题汉字口径实测为 1,055 字；与 GUI 报告按同一口径计得的 1,257 字许可翻译合计 **2,312 字**。

与 [GUI 与跨端论文笔记](by-gui-cross-client-papers-2026-10-12.md) 合并统计为 **28 篇互异来源**：本文 12 篇记忆论文，GUI 笔记 16 篇；英文节选来自本文已计入的 LongMemEval，不另计为新论文。

## 来源核验要点

- [1] ScienceDirect DOI 页面确认题名、Neurocomputing 639、文章号 130193 和出版社摘要；未见改编许可证。
- [2] 期刊正文 PDF 首页确认卷页、DOI、作者及 CC BY 3.0；期刊页确认摘要与发布时间。
- [3] ScienceDirect 页面确认 KBS 336、文章号 115252、DOI、作者和摘要；PDF 需要机构访问。
- [4] ScienceDirect 页面确认作者顺序为 Yang、Some、Bain、Kang，卷期文章号、DOI、Open Access 与 Creative Commons 标示。
- [5] Springer 官方全文页确认作者、DOI、2024 年出版及 CC BY 4.0。
- [6] AAAI 官方 proceedings 页确认作者、DOI、页码与年份。
- [7] ACM UIST proceedings 记录确认 DOI 和出版元数据；作者公开的 arXiv 全文用于正文核读。
- [8] arXiv 官方记录确认题名、作者、编号；全文 HTML 用于架构和实验核读。
- [9] arXiv 全文列出 v2 日期和 CC BY 4.0，官方 ICLR 论文记录见 [OpenReview](https://openreview.net/forum?id=pZiyCaVuti)（本次页面访问受挑战页限制，正文与许可依据 arXiv）。
- [10] ACL Anthology 官方页确认 ACL 2024 论文、作者、DOI、摘要和全文 PDF。
- [11] arXiv 官方页面确认论文题名、作者和编号；全文算法附录及评测章节用于核读。
- [12] ACL Anthology EMNLP 2025 页确认会议出版版本；arXiv 全文用于架构及实验核读。
