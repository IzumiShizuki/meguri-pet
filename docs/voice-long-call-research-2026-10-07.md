# Meguri 实时长通话语音调研

调研日期：2026-10-07  
范围：开源语音会话框架、连续 ASR / VAD、轮次管理、流式 TTS / 打断，以及 Electron 桌面和本地运行接入。  
方法：优先查项目官方 GitHub、官方文档和源代码；仅保存调研结论，不安装依赖、不启动麦克风。

## 结论

调研时（2026-10-07、本次实现之前），Meguri 已有短语音输入和普通聊天所需的基础，但还没有一个持续的语音会话：

- Windows 辅助程序一次打开默认麦克风、识别一段话并返回最终文本，见 [voice-recognition.mjs](../apps/desktop-meguri/src/main/voice-recognition.mjs) 和 [voice-recognition.ps1](../apps/desktop-meguri/src/main/voice-recognition.ps1)。
- 文字进入现有 **conversation.send()** 后由 Meguri API 创建 turn 并通过 SSE 接收结果；打断当前回答时，已有 **conversation.cancel()** 会中止订阅并请求取消 API turn，见 [conversation.ts](../apps/desktop-meguri/src/renderer/stores/conversation.ts)。
- 本地 GPT-SoVITS TTS 目前通过 **POST http://127.0.0.1:9880/tts/synthesize** 返回完整音频 Blob，再由 **HTMLAudioElement** 播放；**cancelSpeech()** 可停止等待或播放中的音频，见 [speech-playback.ts](../apps/desktop-meguri/src/renderer/services/speech-playback.ts)。这不是分块流式 TTS。

调研结论是：**参考 AIRI 的 Electron/Vue 麦克风会话生命周期；参考 Pipecat 的语音轮次、打断和流式管线；用 sherpa-onnx 评估本地中文流式 ASR。** 若以后需要多端接入、电话或更完整的 WebRTC 媒体传输，再评估 LiveKit Agents。

“长通话”建议分清两个级别：

1. **持续会话、逐轮对话**：用户点开始后麦克风保持可用；VAD 把连续音频切成若干话轮，最终文本进入现有 Meguri turn，Meguri 回答后自动继续听。这可以复用现有聊天、SSE、取消和本地 TTS。
2. **可插话的全双工通话**：Meguri 播报时也保留输入监听；确认用户插话后立即停播并取消正在生成的回答，同时只把已经播出的助理文本记入会话历史。这还需要回声处理、流式或短句 TTS、轮次取消和竞态保护。

VAD 只回答“此刻有没有人在说话”；短暂停顿未必代表一句话结束。自然对话应让轮次检测综合 VAD、识别文本和语义信息，或至少先使用可调静音窗口并允许用户插话。Pipecat 官方轮次文档明确区分 VAD 活动和最终轮次决定。[Pipecat：Speech Input & Turn Detection](https://docs.pipecat.ai/pipecat/learn/speech-input)

## 项目比较

| 项目 | 连续 ASR / VAD | 轮次与插话 | TTS / 音频输出 | 桌面与本地接入 | 对 Meguri 的参考价值 |
| --- | --- | --- | --- | --- | --- |
| [AIRI](https://github.com/moeru-ai/airi) | Vue 层持有 MediaStream；语音输入会话使用 VAD 自动分段，并可切到流式转录 provider。代码区分 VAD 分段 ASR 和流式 provider。 | 有启动/停止序列化、失效会话 transcript 丢弃、麦克风更换后重新绑定等生命周期保护。现有 stage 页面在助理播报时暂停输入，这是避免回声的半双工做法。 | AIRI stage 管理 TTS session、播放队列和 stop/cancel；可借鉴展示 speaking 状态、打断播放和口型联动。 | 和 Meguri 一样是 Electron/Vue 桌面应用，最接近界面与设备生命周期。 | **优先参考它的麦克风会话和桌面生命周期代码**，不要把它的 provider/store 架构整体搬入 Meguri。 |
| [Pipecat](https://github.com/pipecat-ai/pipecat) | 音频输入经 transport 持续到 STT。区分持久连接的 streaming STT 与 VAD 分段、HTTP 请求的 segmented STT。自带可在本机 CPU 运行的 Silero VAD。 | 默认可由本地 Smart Turn 检测话轮结束；插话会传播 interruption、取消当前 LLM/TTS 工作、清理未播放音频。可配置最小词数，降低“嗯/好”等短应答造成的误打断。 | LLM 文本可按句聚合后流式合成；音频帧可边生成边播放。带时间戳的已播文本可使被打断后的上下文只包含用户实际听到的内容。 | 可用单客户端 WebSocket 连接单机服务，也可用 SmallWebRTC 做客户端到本地 bot 的对等音频连接；官方文档把前者定位于原型/受控网络，把后者定位于无云基础设施的 P2P。需要增加运行时及 Meguri 与 Pipecat 服务适配。 | **语音会话编排和全双工打断的最佳系统参考**。若采用，应复用 Meguri 的 turn、权限、记忆及事件协议，避免形成第二套对话历史。 |
| [LiveKit Agents](https://docs.livekit.io/agents/) | AgentSession 管理实时音频输入；支持不同 turn detection、endpointing 和音频预处理。 | 有 VAD 或 adaptive interruption、最小时长/词数阈值、误打断恢复；也支持手动 turn 控制。 | session/speech handle 管理播报，用户插话时可取消 assistant speech。 | 支持 Python 和 Node；可自托管 LiveKit Server 并本机开发。但即使单机桌面场景也要处理房间、WebRTC 信令和 Agent worker 生命周期。 | **适合未来跨设备/电话/多人房间**；仅为本机桌面长通话引入整套 RTC 服务可能偏重。 |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | 提供在线流式识别 API、Silero VAD、本地 ONNX 推理。官方模型列表包含中英双语流式 Paraformer 和中文模型；官方仓库还列出 Windows 实时识别示例。 | ASR/VAD 运行库可给出识别和语音段事件；它本身不负责 Meguri 对话状态、语义话轮、取消 LLM 或完整插话策略。 | 带 VITS 等本地 TTS 实现及中文模型，但不等同于 Meguri 已用的 GPT-SoVITS 声线，也不负责全会话编排。 | 可作为本地原生/Python/C API 或 WebSocket 识别服务接入；音频可留在本机。模型首次获取、体积、中文准确度、许可证和 GPU/CPU 表现需单独核实。 | **适合作为可替换的本地连续 ASR 引擎**；不是开箱即用的语音聊天应用。 |

### AIRI：最接近 Meguri 的桌面代码

仓库现有 AIRI 上游固定在 **563a738c88cc42967fd0721a6ca5ad0c9aa403a6**。可以直接阅读以下官方源文件：

- [voice-input-session.ts](https://github.com/moeru-ai/airi/blob/563a738c88cc42967fd0721a6ca5ad0c9aa403a6/packages/stage-ui/src/composables/audio/voice-input-session.ts)：共享输入会话持有麦克风 stream、VAD 自动分段、转录队列、会话 generation 和过期结果过滤；也允许在流式转录启用时跳过 recorder-backed ASR。
- [index.vue](https://github.com/moeru-ai/airi/blob/563a738c88cc42967fd0721a6ca5ad0c9aa403a6/apps/stage-tamagotchi/src/renderer/pages/index.vue)：组合麦克风 stream、流式/分段两种输入模式、字幕、最终文本送入聊天、输入生命周期和停止/重启处理；助理讲话期间暂停监听以规避回声。
- [voice-input-lifecycle.ts](https://github.com/moeru-ai/airi/blob/563a738c88cc42967fd0721a6ca5ad0c9aa403a6/apps/stage-tamagotchi/src/renderer/utils/voice-input-lifecycle.ts)：异步 start/stop 串行化，避免切换设备或按钮连点造成重复占用麦克风。
- [Stage.vue](https://github.com/moeru-ai/airi/blob/563a738c88cc42967fd0721a6ca5ad0c9aa403a6/packages/stage-ui/src/components/scenes/Stage.vue)：TTS session、播放管理器和 stop/cancel 的边界。

AIRI 还有一个较小的全流程参考仓库 [webai-example-realtime-voice-chat](https://github.com/proj-airi/webai-example-realtime-voice-chat)，README 将它定位为 VAD + ASR + LLM Chat + TTS 的 Web AI 实例。它适合看最小端到端数据流，仓库本身是示例项目，不能据此判断其生命周期、恢复、打断和长期维护质量。

**Meguri 可借用的模式：** 一个独立的语音 session 生命周期对象管理麦克风、识别任务和 stop；UI 只订阅 **idle/listening/thinking/speaking/error** 状态；ASR 回调携带 session/turn generation，迟到的识别结果不能再发送。分段 ASR 结果可作为普通最终用户消息；流式 partial 文本只作为暂存字幕，结束后再提交。

### Pipecat：完整管线和打断模型

Pipecat 官方基础管线是：

    transport.input → STT → user context aggregator → LLM → TTS
                   → transport.output → assistant context aggregator

Pipecat 用 frame 把音频、文本、转录和控制信号放到有序处理链中。SystemFrame 中断信号可先于普通排队的文本/音频处理；interrupt 时会取消进行中的 LLM、停止 TTS 并清除尚未播放的音频。[Pipeline](https://docs.pipecat.ai/pipecat/learn/pipeline) · [Interruptions](https://docs.pipecat.ai/pipecat/fundamentals/interruptions)

ASR 模式有明显取舍：streaming STT 持续推送音频，使用持久连接；segmented STT 由 VAD 切片后以 HTTP 请求识别，接入简单但延迟更高。[Pipecat：Speech to Text](https://docs.pipecat.ai/pipecat/learn/speech-to-text)

Smart Turn 在 VAD 检测到停顿后判断用户是否说完，避免把每次短静音都当成 turn 结束；若先做 MVP，可先用 600–900ms 静音窗口，后续再对比本地 turn detector。插话可先在 VAD start 时快速降低延迟，再用最小持续时间/最少词数降低误触；若没有可用转录，需定义恢复或等待行为。[Pipecat：Speech Input & Turn Detection](https://docs.pipecat.ai/pipecat/learn/speech-input) · [Pipecat：User Turn Strategies](https://docs.pipecat.ai/api-reference/server/utilities/turn-management/user-turn-strategies)

TTS 默认可以把 LLM token 聚合为句子再合成，或选择 token 级低延迟输出；句级通常比逐 token 更适合自然中文。Pipecat 文档也说明用播出时间戳维护准确的 interrupted assistant context。[Pipecat：Text to Speech](https://docs.pipecat.ai/pipecat/learn/text-to-speech)

**Meguri 本地接法评估：**

- 低改动方案：renderer 持有麦克风并做连续 VAD/ASR；每个最终识别文本调用现有 **conversation.send()**；继续使用 SSE 和 **tts.requested**。VAD 发生在 Meguri 播报期间时，先 **cancelSpeech()** 并取消当前 turn，再在旧请求完成清理后提交新文本。
- 框架方案：增加一个由 Electron 主进程启动/关闭的本地 Pipecat worker。renderer 和 worker 通过仅绑定 **127.0.0.1** 的 WebSocket 或 SmallWebRTC 传音频和状态。worker 要通过 adapter 调用 Meguri 既有 turn API，或通过明确的内部协议转发最终文本与取消；不能另存一份独立上下文而不和 **conversation.ts** 同步。
- 当前 GPT-SoVITS endpoint 返回整段 Blob，因此插话虽能停止已经开始播放的 audio，但不能利用尚未到达客户端的音频分块，也没有词级播放对齐。要达到自然插话，应将回答拆成句级 TTS 请求并按顺序播放，或新增流式 TTS 接口；每个片段仍要可取消并关联 assistant turn。
- Pipecat 的单客户端 WebSocket transport 默认绑定 localhost，参数 allowed_origins 为空时文档描述为允许所有来源；实施时应指定 renderer 来源、限制本机监听，并增加 session token/来源验证。[Pipecat WebSocket transport](https://docs.pipecat.ai/api-reference/server/services/transport/websocket-server)
- Pipecat 核心仓库采用 BSD-2-Clause；但各 provider extra、模型和 TTS 服务的许可证要逐项确认。特别是其 in-process Piper TTS 使用 GPL-3.0 的 piper-tts；Pipecat 文档建议需隔离许可证时改走独立 Piper HTTP 服务。[Pipecat GitHub](https://github.com/pipecat-ai/pipecat) · [Piper TTS](https://docs.pipecat.ai/api-reference/server/services/tts/piper)

### LiveKit Agents：完善的实时媒体产品化参考

LiveKit AgentSession 处理实时输入、输出、对话上下文和 turn handling。官方文档把“检测用户是否说完”和“用户能否打断 assistant”视为不同阶段；可选自适应中断、VAD 中断、最小时长/词数和误中断恢复。也有手动 turn control：开始说话时打断 agent 并开启输入，结束时提交 turn，取消时清空 turn。[Agent sessions](https://docs.livekit.io/agents/logic/sessions/) · [Turns](https://docs.livekit.io/agents/logic/turns/) · [Turn-taking tuning](https://docs.livekit.io/agents/logic/turns/tuning/)

LiveKit Server 可通过 **livekit-server --dev** 本地运行，Agents SDK 也提供 local console/dev 模式；自托管正式运行则还需媒体服务器和 agent worker。它值得做对照实验，但当前 Meguri 的单机 Electron + 本机 TTS 场景可以先用 loopback WebSocket 或本地进程通信，减少部署负担。[Running LiveKit locally](https://docs.livekit.io/transport/self-hosting/local/) · [LiveKit agent startup modes](https://docs.livekit.io/agents/server/startup-modes/)

### sherpa-onnx：本地流式中文识别候选

sherpa-onnx 文档列出 streaming Paraformer 双语中英模型，以及中文/中英流式模型；其 online recognizer API 以小块 waveform 逐步解码并能在 endpoint 后重置流。官方示例有 online WebSocket server 和 WebSocket 麦克风客户端。[Online ASR API](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/c-api/docs/online-asr.dox) · [Online Paraformer models](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-paraformer/index.html) · [Online WebSocket server](https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/websocket/online-websocket.rst)

官方 Windows 页面提供 streaming ASR 应用和构建源码链接；Silero VAD 的 sherpa-onnx 页面列出支持的模型采样率与配置；本地 TTS 页面列出中文 VITS 模型。[Windows streaming ASR](https://k2-fsa.github.io/sherpa/onnx/flutter/asr-win.html) · [Silero VAD](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html) · [Chinese VITS models](https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/vits.html)

它解决的是可离线部署的音频识别和 VAD 模型，不会自动实现用户轮次、Meguri API turn、聊天记录、TTS 播放取消或 UI。推荐把它作为 ASR engine 选项，先核实 Windows 打包、中文噪声/中英夹杂准确率、CPU 延迟、模型下载和模型许可证；本次调研没有安装或运行它。

## 本次落地决策与后续方向

本次把 composer 麦克风改成显式长通话开关：会话循环复用现有 Windows 本地单句识别；每句最终文本自动调用现有 conversation turn；Meguri 思考和本地 TTS 播报时暂停识别，完成后重新监听；挂断取消识别、进行中的 turn 和播报。这个版本不展示 partial 字幕，也不支持用户在 Meguri 播报时插话。它不添加 Pipecat、LiveKit 或 ASR 模型依赖。

后续若要继续降低识别间隔、显示实时 partial 字幕，可先评估 sherpa-onnx streaming ASR 与 VAD，并测量中文准确率、CPU 延迟和 Windows 打包体积。若目标变成全双工插话，应参考 Pipecat 的 turn/interruption 管线，同时增加可分块、按 turn 取消的 TTS 和已播文本对齐；单独启用 VAD 插话还不足以保护音频回声和聊天上下文。AIRI 仍是 Meguri 最接近的桌面会话生命周期参考；若目标扩展到多设备、电话或多人房间，再评估 LiveKit。

这份调研尚未包含依赖安装、模型许可审查、中文真实环境准确率/延迟测量或真实麦克风测试。当前长通话实现通过模拟识别器与单元测试验证，不代表在用户设备上已做真实语音端到端测试。

