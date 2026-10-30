# 检索与回答格式组合回归（2026-10-30）

用户明确批准将两笔检索修复和三笔回答格式实现整合到本地 `fix/dialogue-reliability-20261028`，先做组合回归。本轮不 push、不合并主分支、不部署或重启 Core，不改凭据及生产 Todo。

## 整合记录

起点为 `c06fef53f2b5333efbb994a3366a6a1d74f93fee`，其查询保真修复保留。

| 已批准候选 | 本地整合提交 | 内容 |
| --- | --- | --- |
| c3849df97725f18245d91d27f595b973868ad69c | 734b1eecd91f15a76d0bb777a731595314db3f82 | RFC 数字边界、站点约束与响应诊断 |
| 9ebf8ab0d8c847411f6b686a3b18927bca07cb0e | 4c3bc43b45dedf2bd3e6261cc4b0fb0b0d2a455e | 长问题原作召回的有界主题回退 |
| 610ef4ed48cfed7d8ff4871ff24a7e849edb7cee | 3a73df9f05f1503d6902e1cb1bc1ba8664a663fd | 本轮直接回答约束与一次修复 |
| 8cac2e0a40b12d23569e9e3854db9bb1b9bf4d39 | 4de884652126c82d0c05b3e9628554cf8cb97103 | 撤销、引文、指标、人格与取消／超时边界 |
| c48a2ee80674abbc44dd90fdaa0628b7776d7ad4 | 3f75ea1d012ee79b4fecf1ec2965562fc3cbecc3 | 引文内撇号和中文 ASCII 句点 |

两份 bundle 校验通过后导入，未引入旧交付文档提交。唯一 cherry-pick 冲突是跳过交付记录造成的 OpenSpec 完成标记差异；按已核实证据解决，没有源码冲突。此前两次整合拒绝均未执行；本次具体批准后的实际整合成功，不再是审批阻塞。

## 组合发现与修正

首轮 184 项为 179 通过、5 失败、0 错误。一个既有站点约束用例与四个新组合用例暴露了相同问题：`site:` 跟在“提供来源”之后，或后续带“只回复／只用一句话”时，呈现要求残留在搜索词里，污染相关性锚点。

修正仅涉及 `WebRetrievalProvider` 和 `WebSearchPolicy`：先从原始输入提取并保存域名约束，再规范化剩余主题；移除引文之外、明确分句的限定回答要求，容忍移除域名后留下的空白与标点；最后补回域名约束。重试和网页最终地址仍执行同一域名限制。没有降低相关性阈值，也没有伪造结果。

新增 7 项跨模块测试与 3 项查询对照：RFC 的三种写法经错误主机／错误编号过滤、一次重试及一次回答修复；`currentColor` 保真与精确回复；长问题召回／明确禁止召回；检索内容不能激活或取消当前回答约束；引文内类似要求保留为主题文本。全部使用虚构语料、脚本模型和本机 fixture，不写用户记忆。

## 验证结果

| 验证 | 结果 |
| --- | --- |
| Java 针对性组合回归 | 187 通过，0 失败／错误／跳过 |
| Java 全量 | 887 项：882 通过，0 失败，0 错误，5 跳过 |
| 与最终回答格式候选的 XML 比较 | 821 个共有测试状态不变，新增 66 项通过，无删除 |
| 根 TypeScript 协议／客户端／renderer／adapter | 52 通过 |
| Python canonical fixture + AstrBot gateway | unittest：38 通过 |
| AIRI 三份合同／协议测试 | 34 通过 |
| Adapter DTO/schema 一致性 | 通过 |
| 两个相关 OpenSpec 变更校验、git diff --check | 通过 |

187 项是全量的子集，根 TS 的 21 项合同测试也包含在 52 项中，不能重复相加。5 个跳过项为 Everything 实机索引、两项独立 PostgreSQL 恢复、Shizuki 实机窗口和需显式启用的 ModelScope 下载／导入验收；本轮没有为消除跳过而启用这些能力。

Java 在 `reports/integrated-regression-20261030/isolated` 编译和测试，使用已验证的 JDK 21 测试进程 TCP 回退目录、独立 user.home、离线 Maven 缓存及清空真实 provider 环境的脚本。该参数是当前 JDK 的测试运行方案，不是操作系统 AF_UNIX 修复。

原 `run_adapter_contract_fixtures.py` 沙箱执行到 pytest 无输出，已仅终止本次测试子进程。替代 unittest 在沙箱中同样停在首个异步 fixture，受 90 秒上限终止；最小 `asyncio.sleep(0)` 也在沙箱内 5 秒超时。获准在正常本机上下文执行后，最小案例和相同的 38 项用例全部通过。AIRI 余下 34 项另行完成。不能把原入口的受阻执行称为成功，也没有修改 OS 权限或网络设置。

## 复现与证据

```powershell
.\reports\integrated-regression-20261030\run-isolated-tests.ps1 -Tests 'IntegratedRetrievalReplyTest,WebSearchQueryFidelityTest,WebSearchGuardrailsTest,BingRssDiagnosticsTest,LoreQueryFocusTest,ReplyConstraintsFollowupReviewTest,ReplyConstraintsReviewTest,ReplyConstraintProviderRegressionTest,ReplyRepairLifecycleTest,ReplyConstraintsTest,LangChain4jLlmProviderTest,UserVisibleReplyStreamTest,DurableNativeStreamingTest' -LogName targeted-after-fix.log
.\reports\integrated-regression-20261030\run-isolated-tests.ps1 -LogName full.log
```

使用 `.agent/workflows.md` 中的 Node 24、Python 3.14 和 Maven 3.9.16。跨模块检查命令为根 `test:ts` 的七个 `node --test` 文件、`python -m unittest tests.test_adapter_protocol_canonical_fixture tests.test_astrbot_gateway -v`、`python scripts/check_adapter_contract_consistency.py`，以及 AIRI 包内 `pnpm exec vitest run src/contract-fixture.test.ts src/index.test.ts src/protocol.test.ts`。只使用已有依赖。

本地证据目录包含修复前后及全量 Surefire ZIP、对应日志、`source-manifest.json`、`verification-summary.json`、用户改动及运行制品哈希快照。测试源码与主树 Java／契约配置源码一致；404 个既有角色资源原样复用。报告只上传整理结论，不上传原始日志、语料或私人配置。

## 实际运行与未测边界

本轮复核 Core `/health` 为 `ok`；PID 67344 及启动时间延续原部署，运行源码记录仍为 `b4b03a40b12379c8447510c84083491a3ecdfa08`。健康返回的 `meguri_v2_02c3db0c507d7c2d` 是数据 build_id，不能当作新代码版本。桌面 PID 12568、Harness PID 5172 均未重启。

15 个原有用户文件和 1,063 个生产 target/classes 文件逐一哈希相同。桌面时间戳相关改动及原来排除的五个文档保留；本轮未操作生产 Todo、凭据、真实记忆或模型设置。

真实模型遵从率、延迟、真实 Bing 相关性和新版 UI 尚未复验，因为新版未部署。公开搜索上游跑题仍是已知限制；离线过滤成功不代表上游已返回可靠材料。回答约束仍是有限语法，受约束回复等待完整校验，每候选正文最多两次调用，A/B 最多四次，不代表整轮总调用数。先前 MCP 管理凭据／目标服务、Todo 桌面双端与 Harness 上游模型冷却问题仍分别保留，不由本次整合自动解决。

下一阶段在另行确认重启时机后加载已验代码，先复核运行制品，再做约 12 轮隔离真实模型验收：原格式失败样例、撤销／引文／技术标点／普通聊天对照、取消及下一轮恢复；逐轮比较 SSE 与最终快照、调用数和唯一终态。现阶段无需回退运行服务，因为从未替换其制品。
