# GUI 与跨端代理论文来源核对（2026-10-12）

## 范围与结论

本报告恢复原附件[6]-[10]五篇论文，并另列11篇 GUI、移动端与跨设备补充论文（[S1]-[S11]），共核对**16篇不同论文**。每篇均链接至少一个作者、出版方或会议论文集的一手页面；补充论文编号与附件编号分开。附件[7]-[10]不以 OSWorld 替代。下表分别标出全文是否可读，以及本次实际核读范围；“可读全文”不表示逐字通读。

**附件[6]可节译并发布。** *Large Language Model-Brained GUI Agents: A Survey*，Chaoyun Zhang、Shilin He、Jiaxu Qian 等，TMLR 2025年6月。TMLR 作者指南明确其所有投稿和正式论文均为 CC BY 4.0；正式 OpenReview PDF 与作者/预印本页可读。按统一口径（含译文小标题、不含许可说明），GUI 译文为 **1,257 个汉字**；加上主任务记忆译文的1,055个汉字，合计 **2,312 个汉字**。节译选自摘要、V-F “Memory”及 XI-A “Privacy Concerns”。发布时附原文引用、CC BY 4.0 链接及译文修改说明；第三方图表、截图和标识仍需单独核权。

## 原附件论文核对

| 编号 | 书目与 DOI | 一手全文、可读性与本次核读范围 | 方法、贡献与局限 | 论文许可与翻译判断 |
|---|---|---|---|---|
| [6] | **Large Language Model-Brained GUI Agents: A Survey** — Chaoyun Zhang, Shilin He, Jiaxu Qian et al., *Transactions on Machine Learning Research* (TMLR), June 2025；arXiv:2411.18279，DOI [10.48550/arXiv.2411.18279](https://doi.org/10.48550/arXiv.2411.18279)。OpenReview forum: `xChuYjvXTp`。 | [TMLR/OpenReview 正式 PDF](https://openreview.net/pdf/0e2a59aacb16c1cf9894b8baf1b419b2e69ad522.pdf)、[arXiv v12](https://arxiv.org/abs/2411.18279)、[Microsoft Research 作者页](https://www.microsoft.com/en-us/research/publication/large-language-model-brained-gui-agents-a-survey/)可读。本次核读并节译摘要、V-F Memory、XI-A Privacy Concerns。 | 综述 GUI 代理架构、记忆、训练、评测及 web/mobile/desktop/cross-platform 范畴；作为综述，其结论受纳入文献与分类框架约束，并非新的受控系统实验。 | **正式 TMLR 版 CC BY 4.0**，依据 [TMLR 作者指南](https://www.jmlr.org/tmlr/author-guide.html)，可翻译和改编；应署名、链接许可并标明改动。arXiv 镜像的分发条款不同，翻译依据应标明正式版许可。 |
| [7] | Tao Xie, De-Zhi Ran, Yuan Cao, Meng-Zhou Wu, Yu-Zhe Guo, Wei Yang, “**From User Operations to Agentic Automation: Toward Intent-Oriented Software in the LLM Era**,” *Journal of Computer Science and Technology* 41(1):245–257 (2026), DOI [10.1007/s11390-026-6182-0](https://doi.org/10.1007/s11390-026-6182-0)。 | [JCST 英文全文及 PDF](https://jcst.ict.ac.cn/article/doi/10.1007/s11390-026-6182-0)；[出版方 PDF](https://jcst.ict.ac.cn/en/article/pdf/preview/10.1007/s11390-026-6182-0.pdf)。全文公开可读；本次核读摘要、两种架构及信任/错误恢复章节。 | Perspective，提出插件式（把意图转换为既有 UI 操作）与原生式（直接生成 API/函数调用）两种意图导向软件架构，并归纳意图理解、规划、错误恢复、信任等挑战。概念/架构论证，没有受控用户研究或代理性能基准；落地效果仍待实证。 | 页面未标示 CC 改编许可；Crossmark 的 text/data-mining 权限不等于翻译许可。可读不代表可发布译文，完整翻译应先确认出版方/作者许可。 |
| [8] | Yang Liu, “**A new human-computer interaction paradigm: Agent interaction model based on large models and its prospects**,” *Virtual Reality & Intelligent Hardware* 7(3):237–266 (June 2025), DOI [10.1016/j.vrih.2025.04.001](https://doi.org/10.1016/j.vrih.2025.04.001)。 | [Elsevier ScienceDirect 正式页](https://www.sciencedirect.com/science/article/pii/S209657962500021X)标为 Open Access / Creative Commons license。页面显示全文可在线阅读；本次仅核读正式页的摘要和元数据，未核读全文。 | 概念性综述/展望，提出四类交互模式、八种模态、21类场景（含智能家居、健康和养老），并讨论大模型代理的结构与交互机制。页面摘要未给出可复现实验；框架覆盖和场景判断主要属概念综合。 | 出版方检索摘要只写 “under a Creative Commons license”，未在可读取的一手页确认具体 CC 版本；二手元数据称 BY-NC-ND，但本报告不把它当授权证据。全文可读，**公开翻译/改编许可未从一手页确认**；获取出版方版权页后再决定。 |
| [9] | Harith Al-Safi, Harith Ibrahim, Paul Steenson, “**Vega: LLM-Driven Intelligent Chatbot Platform for Internet of Things Control and Development**,” *Sensors* 25(12):3809 (2025), DOI [10.3390/s25123809](https://doi.org/10.3390/s25123809)。 | [MDPI 正式全文与 PDF](https://www.mdpi.com/1424-8220/25/12/3809)可读；出版记录为2025-06-18。本次核读摘要、引言、实验与验证、错误分析、局限和结论。 | 以 Raspberry Pi、物理电路/传感器、控制服务器和聊天 web app 组成原型；论文报告20名参与者的基线比较及约622条自动生成测试消息。样本规模小，测试场景和硬件集中，主模型使用 GPT‑3.5；作者指出云依赖、隐私、错误率、网络和扩展性局限。 | **CC BY 4.0**（出版方仓储的正式版本记录明确），可翻译、改编和分享；署名、链接许可并声明修改。 |
| [10] | Sadia Sultana Chowa, Riasad Alvi, Subhey Sadi Rahman, Md Abdur Rahman, Mohaimenul Azam Khan Raiaan, Md Rafiqul Islam, Mukhtar Hussain, Sami Azam, “**From language to action: a review of large language models as autonomous agents and tool users**,” *Artificial Intelligence Review* 59:71 (2026), DOI [10.1007/s10462-025-11471-9](https://doi.org/10.1007/s10462-025-11471-9)。 | [Springer 正式 HTML/PDF](https://link.springer.com/article/10.1007/s10462-025-11471-9)为 Open Access，全文可读。本次核读摘要、方法/筛选、讨论和权利许可段。 | 结构化综述：按七个研究问题分析单/多代理、工具、推理/规划/记忆、提示/微调、基准与68个数据集；筛入2023–2025年108篇。纳入标准偏重 A*/A 类会议及 Q1 期刊，可能漏掉其他会议、预印本和灰色文献；文献截至2025年，快速变化领域的覆盖会滞后。 | **CC BY 4.0**，Springer 权利页明确允许改编；署名、链接许可并说明修改。论文第三方材料按图注另核。 |

## 补充 GUI / 跨端论文

以下11篇是为 GUI 和跨设备研究补充的独立来源，不占用附件[7]-[10]编号。

| 编号 | 论文与出处 | 一手来源 / 全文 | 方法局限与本项目关联 | 许可判断 |
|---|---|---|---|---|
| [S1] | *OSWorld: Benchmarking Multimodal Agents for Open-Ended Tasks in Real Computer Environments*, arXiv:2404.07972。 | [arXiv](https://arxiv.org/abs/2404.07972) PDF/HTML；[官方仓库](https://github.com/xlang-ai/OSWorld)。全文公开可读；本次核读 arXiv 摘要及元数据，未核读正文。 | 369项真实桌面任务，跨 Ubuntu、Windows、macOS；基准环境覆盖有限且执行结果易受应用版本/环境状态影响。 | 论文 arXiv 标 CC BY 4.0；代码仓库另为 Apache-2.0。 |
| [S2] | *Windows Agent Arena: Evaluating Multi-Modal OS Agents at Scale*, arXiv:2409.08264。 | [arXiv](https://arxiv.org/abs/2409.08264)；[Microsoft 项目页](https://microsoft.github.io/WindowsAgentArena/)；[repo license](https://github.com/microsoft/WindowsAgentArena/blob/main/LICENSE)。预印本公开可读；本次核读摘要、元数据及项目页，未核读论文正文。 | 150+ Windows 桌面任务与并行评测；结论依赖 Windows 环境和任务集，不能直接外推到其他 OS。 | arXiv 标示 non-exclusive distribution（不足以授权论文翻译）；代码仓库 MIT。 |
| [S3] | *SeeClick: Harnessing GUI Grounding for Advanced Visual GUI Agents*, ACL 2024，DOI [10.18653/v1/2024.acl-long.505](https://doi.org/10.18653/v1/2024.acl-long.505)。 | [ACL Anthology 全文/PDF](https://aclanthology.org/2024.acl-long.505/)，同文提出 ScreenSpot。论文/PDF公开可读；本次核读论文摘要与 ACL 记录，未核读正文。 | 以截图定位控件并建立 ScreenSpot 基准；grounding 指标不能代表完整多步任务成功率。 | ACL Anthology 页面声明 CC BY 4.0；代码/数据许可证另查[作者仓库](https://github.com/njucckevin/SeeClick)。 |
| [S4] | *UI-TARS: Pioneering Automated GUI Interaction with Native Agents*, arXiv:2501.12326。 | [arXiv](https://arxiv.org/abs/2501.12326)；[作者代码库](https://github.com/bytedance/UI-TARS)。预印本公开可读；本次核读摘要和元数据，未核读正文。 | 视觉输入到键鼠动作的端到端代理；不同模型、提示和平台评测条件影响横向可比性。 | arXiv 标 non-exclusive distribution，不足以许可全文翻译；仓库 Apache-2.0 不代表论文/权重同许可。 |
| [S5] | *Agent S: An Open Agentic Framework that Uses Computers Like a Human*, arXiv:2410.08164。 | [arXiv](https://arxiv.org/abs/2410.08164)；[官方 repo](https://github.com/simular-ai/Agent-S)。预印本公开可读；本次核读摘要和元数据，未核读正文。 | 分层规划、经验检索和 ACI，在 OSWorld / Windows Agent Arena 评测；性能受底层模型、任务分布和环境限制。 | arXiv 标 CC BY 4.0；代码仓库 Apache-2.0。 |
| [S6] | *AndroidWorld: A Dynamic Benchmarking Environment for Autonomous Agents*, arXiv:2405.14573。 | [arXiv](https://arxiv.org/abs/2405.14573)；[Google Research 项目页](https://google-research.github.io/android_world/)。预印本公开可读；本次核读摘要、元数据和项目页，未核读正文。 | 116个程序化任务、20个 Android 应用；模拟器任务对真实设备和应用变化的代表性有限。 | arXiv 标 CC BY 4.0；环境代码 Apache-2.0。 |
| [S7] | *Scaling Computer-Use Grounding via User Interface Decomposition and Synthesis*（OSWorld-G）, arXiv:2505.13227。 | [arXiv](https://arxiv.org/abs/2505.13227)；[项目页](https://osworld-grounding.github.io/)。预印本公开可读；本次核读摘要及项目页，未核读正文。 | 564个 grounding 样本，用于定位/布局理解；样本数及合成构造限制覆盖范围，不能替代整任务测试。 | arXiv 标 CC BY 4.0；数据集各自许可仍需另查。 |
| [S8] | *ScreenSpot-Pro: GUI Grounding for Professional High-Resolution Computer Use*, arXiv:2504.07981。 | [arXiv](https://arxiv.org/abs/2504.07981)；[grounding leaderboard](https://gui-agent.github.io/grounding-leaderboard/)。预印本公开可读；本次核读摘要、元数据和榜单，未核读正文。 | 23个专业应用、5个行业、3种 OS 的高分辨率定位；专业样本与真实工作流广度仍有限。 | arXiv 标 CC BY 4.0；第三方界面截图/数据须按其自身权利核验。 |
| [S9] | *OmniParser for Pure Vision Based GUI Agent*, arXiv:2408.00203。 | [arXiv](https://arxiv.org/abs/2408.00203)；[Microsoft repo 与模型许可说明](https://github.com/microsoft/OmniParser)。预印本公开可读；本次核读摘要、元数据和 repo 许可，未核读正文。 | 将屏幕解析为可交互区域以支持纯视觉代理；解析误差、动态页面与细粒度控件会影响后续行动。 | arXiv 标 CC BY 4.0；代码、模型权重的许可并不相同，须按 repo 文件逐项确认。 |
| [S10] | *Mobile-Agent-v2: Mobile Device Operation Assistant with Effective Navigation via Multi-Agent Collaboration*, arXiv:2406.01014。 | [arXiv](https://arxiv.org/abs/2406.01014)。预印本公开可读；本次核读摘要和元数据，未核读正文。 | 面向移动 GUI 的多代理规划、反思与进度记忆；论文预印本主要描述方法/基准实验，跨设备泛化仍需实测。 | arXiv 标 non-exclusive distribution，不足以授权全文翻译；代码/模型/数据须分别核验。 |
| [S11] | *JarvisGUI: Towards Cross-Device GUI Agents with Dynamic Task Composition*, arXiv:2609.10451。 | [arXiv 作者记录](https://arxiv.org/abs/2609.10451)可确认题名、作者和2026-09-09提交日期；当前记录将 DOI 标为 pending registration。预印本全文可读；本次核读摘要、元数据和作者备注，未核读正文。 | 预印本提出 Android、Windows、Ubuntu 跨设备动态任务组合；截至本次核对仅按 arXiv 预印本引用，接受会议的作者备注不作为正式出版元数据。 | arXiv 显示 non-exclusive distribution；全文可读不等于允许改编/公开翻译。代码、数据许可未据此推定。 |

## [6] 中文节译（含小标题1,257汉字）

译文及其三个小标题共1,257个汉字（按 Unicode 汉字计，不含许可说明和译文前的说明段），取自论文摘要、V-F“记忆”中短期/长期记忆的定义段，以及 XI-A“隐私问题”的问题描述和建议段。与主任务记忆译文（含小标题）1,055个汉字相加，合计2,312个汉字。为方便阅读，个别长句做了中文语序调整；未翻译全文，也没有翻译论文图表。原文：[TMLR/OpenReview 正式 PDF](https://openreview.net/pdf/0e2a59aacb16c1cf9894b8baf1b419b2e69ad522.pdf)、[arXiv HTML v12](https://arxiv.org/html/2411.18279v12)。

### 摘要（译）

图形用户界面（GUI）长期以来都是人机交互的核心，为用户访问和操作数字系统提供了直观、以视觉为主的方式。传统 GUI 自动化依赖脚本或规则；这类方法适用于固定流程，却难以适应动态、真实的应用环境。大型语言模型，特别是多模态模型的发展，为 GUI 自动化带来了新的可能。它们在自然语言理解、代码生成、任务泛化和视觉处理方面展现出强大能力，催生出新一代由大语言模型驱动的 GUI 代理。这些代理能够理解复杂界面元素，并根据自然语言指令自主执行操作。用户因此可以用简单的对话指令完成复杂的多步骤任务；其应用横跨网页导航、移动应用操作和桌面自动化，正在改变人们与软件交互的方式，相关研究和产业应用也快速发展。

本文系统梳理这类 GUI 代理的演进历程、核心构成与先进技术，并回答若干关键问题：现有 GUI 代理框架有哪些？如何收集和使用数据来训练专用模型？如何开发面向 GUI 任务的大型动作模型？应采用哪些指标和基准来评估代理能力？本文还考察新兴应用，并通过分析指出研究空白、提出后续路线图。作者希望这份综述能为研究人员和实践者提供领域基础、方法指南及有价值的参考。

### V-F 记忆（节译）

要让 GUI 代理可靠地完成复杂的多步骤任务，它必须保留记忆，以便在本身不保存状态的环境中跟踪进度。记忆使代理能够记录此前执行过的操作及其结果，并掌握任务整体状态，从而为下一步决策提供依据。记忆建立了行动连续性，使代理从被动响应系统转变为有状态、能依据累积知识调整行为的系统。GUI 代理的记忆通常分为短期记忆和长期记忆。

短期记忆（STM）是模型运行期间主要使用的临时上下文，保存当前任务相关的信息，例如近期计划、操作、执行结果和环境状态，并随任务推进持续更新。多步骤任务中的每项决策都建立在之前的步骤之上，因此短期记忆帮助代理理解任务轨迹、追踪进展并作出更有依据的决策。不过，短期记忆受模型上下文窗口限制，能够延续的信息量有限。代理可以选择性丢弃或概括关联较弱的细节，把最有影响的信息保留下来。尽管容量有限，短期记忆仍是维持连贯、理解上下文并利用即时反馈执行复杂流程的关键。

长期记忆（LTM）是运行时上下文之外的外部信息库。与临时的短期记忆不同，它保留已完成任务、成功的操作序列、情境提示和经验总结，并可存储在磁盘或数据库中，容纳远多于模型即时上下文的信息。长期记忆保存成功轨迹、操作指南和常见交互模式；代理处理新任务时，可通过检索增强生成（RAG）取回历史资料，利用过往经验调整策略。这样的机制与终身学习相近，使代理能跨会话积累经验，逐步提升准确度和效率。

### XI-A 隐私问题（节译）

隐私是大语言模型 GUI 代理面临的一项突出问题。为了识别界面并与之交互，代理通常需要接触敏感用户数据，例如屏幕截图、交互历史、个人凭据和机密文件。尤其使用云端模型时，这些数据往往需要发送到远程服务器进行推理。由此产生的数据泄露、未授权访问和个人信息滥用风险，会在输入经过第三方 API 或离开设备处理时进一步增加，形成合规与安全隐患，阻碍现实部署。

与传统 LLM 应用相比，GUI 代理对用户活动的观察粒度更细，所需系统访问范围往往也更广，因此隐私保护部署是这一领域特有且关键的挑战。后续研究应推动设备端推理，避免上传个人数据；同时发展模型压缩、设备端优化和高效推理方法，以适应终端算力限制。框架还应提供数据脱敏、安全通信，以及对代理上下文中数据使用范围的明确约束；并集成系统级隐私控制和用户同意机制，例如运行时权限对话框或沙箱执行。

**许可与译文说明：**本节依据 TMLR 正式发表版本，按 TMLR 对所有稿件适用的 CC BY 4.0 条款制作中文节译。若对外发布，应附原文书目信息和 [CC BY 4.0 许可链接](https://creativecommons.org/licenses/by/4.0/)，声明“中文节译，非官方译本”及所作编辑；论文图表和可能包含的第三方内容不在本节译范围内。

## 对“10篇”的建议读法

上表包含至少 10 篇互相独立的 GUI/移动/跨设备论文或基准。SeeClick 与 ScreenSpot 是同一论文中提出的模型和基准，避免重复计数；OSWorld-G 是独立后续论文；JarvisGUI 作为跨设备主题的近作单独列出。若研究重点是仓库现有 Windows observation runtime，建议优先读 OSWorld、Windows Agent Arena、SeeClick/ScreenSpot、Agent S、OSWorld-G、ScreenSpot-Pro 和 JarvisGUI；AndroidWorld 与 Mobile-Agent-v2 用于移动端跨域参照，UI-TARS 与 OmniParser 用于视觉 grounding/端到端能力参照。

## 许可判断边界

1. arXiv 页面的公开可读性只说明可取得 PDF/HTML，不自动代表允许全文翻译、发布或商用。
2. CC BY 4.0 明确允许分享与改编，需提供适当署名、许可链接并说明修改；完整翻译应标成“译文/非官方翻译”，不要暗示作者认可。
3. arXiv 的 non-exclusive distribution license 只表明作者授予 arXiv 分发论文的非独占许可；仅凭这一项无法确认译文公开发布所需的改编权。此类来源可用于阅读、内部分析和摘要，若要发布完整译文需查作者/出版方授权条款或直接取得许可。
4. 论文、代码、数据、模型权重、截图及第三方标识可能有不同许可。不要把仓库的 MIT/Apache/CC-BY 标签外推到论文附件或模型文件。

## 一手参考

- [附件[6] TMLR 正式 PDF](https://openreview.net/pdf/0e2a59aacb16c1cf9894b8baf1b419b2e69ad522.pdf)；[Microsoft Research 作者页](https://www.microsoft.com/en-us/research/publication/large-language-model-brained-gui-agents-a-survey/)；[arXiv v12](https://arxiv.org/abs/2411.18279)；[TMLR CC BY 4.0 作者指南](https://www.jmlr.org/tmlr/author-guide.html)
- [附件[7] JCST 期刊页/全文](https://jcst.ict.ac.cn/article/doi/10.1007/s11390-026-6182-0)；[DOI](https://doi.org/10.1007/s11390-026-6182-0)；[Crossmark 权利状态](https://crossmark.crossref.org/dialog/?doi=10.1007%2Fs11390-026-6182-0)
- [附件[8] Elsevier ScienceDirect 正式页](https://www.sciencedirect.com/science/article/pii/S209657962500021X)；[DOI](https://doi.org/10.1016/j.vrih.2025.04.001)
- [附件[9] MDPI Sensors 正式全文](https://www.mdpi.com/1424-8220/25/12/3809)；[DOI](https://doi.org/10.3390/s25123809)；[University of Leeds 作者仓储记录及 CC BY 4.0 正式版](https://eprints.whiterose.ac.uk/id/eprint/228713/)
- [附件[10] Springer 正式全文与许可](https://link.springer.com/article/10.1007/s10462-025-11471-9)；[DOI](https://doi.org/10.1007/s10462-025-11471-9)
- [OSWorld arXiv](https://arxiv.org/abs/2404.07972)；[OSWorld 官方代码与 Apache-2.0 标识](https://github.com/xlang-ai/OSWorld)
- [Windows Agent Arena arXiv](https://arxiv.org/abs/2409.08264)；[Microsoft 项目页](https://microsoft.github.io/WindowsAgentArena/)；[官方 MIT LICENSE](https://github.com/microsoft/WindowsAgentArena/blob/main/LICENSE)
- [SeeClick ACL Anthology 记录、DOI 与许可说明](https://aclanthology.org/2024.acl-long.505/)；[论文 PDF](https://aclanthology.org/2024.acl-long.505.pdf)
- [UI-TARS arXiv](https://arxiv.org/abs/2501.12326)；[官方 repo](https://github.com/bytedance/UI-TARS)
- [Agent S arXiv](https://arxiv.org/abs/2410.08164)；[官方 repo](https://github.com/simular-ai/Agent-S)
- [AndroidWorld arXiv](https://arxiv.org/abs/2405.14573)；[Google Research 项目页](https://google-research.github.io/android_world/)
- [OSWorld-G arXiv](https://arxiv.org/abs/2505.13227)；[项目页](https://osworld-grounding.github.io/)
- [ScreenSpot-Pro arXiv](https://arxiv.org/abs/2504.07981)；[榜单](https://gui-agent.github.io/grounding-leaderboard/)
- [OmniParser arXiv](https://arxiv.org/abs/2408.00203)；[Microsoft 官方 repo 与模型许可说明](https://github.com/microsoft/OmniParser)
- [Mobile-Agent-v2 arXiv](https://arxiv.org/abs/2406.01014)
- [JarvisGUI arXiv 作者记录](https://arxiv.org/abs/2609.10451)（仅按预印本引用，DOI pending registration）
- [arXiv non-exclusive distribution license 原文](https://arxiv.org/licenses/nonexclusive-distrib/1.0/license.html)
- [Creative Commons CC BY 4.0 deed](https://creativecommons.org/licenses/by/4.0/)
