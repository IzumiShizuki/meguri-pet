import { createPinia, setActivePinia } from 'pinia'
import type { TurnEventEnvelope } from '@meguri/protocol'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick, watchEffect } from 'vue'

const { createTurnMock, helloMock, subscribeMock, cancelMock, memoryStatusMock, summarizeMock } = vi.hoisted(() => ({
  createTurnMock: vi.fn(),
  helloMock: vi.fn(),
  subscribeMock: vi.fn(),
  cancelMock: vi.fn(),
  memoryStatusMock: vi.fn(),
  summarizeMock: vi.fn(),
}))

vi.mock('@meguri/client-sdk', () => ({
  MeguriApiClient: class {
    hello = helloMock
    createTurn = createTurnMock
    subscribe = subscribeMock
    cancel = cancelMock
  },
}))

vi.mock('../services/memory-api', () => ({ memoryStatus: memoryStatusMock, summarizeMemory: summarizeMock }))
vi.mock('../services/speech-playback', () => ({ playSpeech: vi.fn().mockResolvedValue(undefined), cancelSpeech: vi.fn(), updateSpeechPreferences: vi.fn(), speechPlaybackState: { error: '' } }))

import { friendlyConversationError, restoreConversationMessages, type ConversationMessage, useConversationStore } from './conversation'
import { createVoiceConversationController } from '../services/voice-conversation'
import type { LocalResourceAttachment } from '../services/local-resources'
import { useDesktopPreferencesStore } from './desktop-preferences'
import { usePresentationStore } from './presentation'
import { PRIVACY_POLICY_VERSION } from '../../shared/experience-contract'
import { playSpeech as synthesizeSpeech, cancelSpeech, speechPlaybackState } from '../services/speech-playback'
import { playbackReceiptsForSession } from '../services/playback-ledger'

const storage = new Map<string, string>()

Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: {
    getItem: (key: string) => storage.get(key) ?? null,
    setItem: (key: string, value: string) => storage.set(key, String(value)),
    removeItem: (key: string) => storage.delete(key),
    clear: () => storage.clear(),
  },
})

describe('conversation persistence', () => {
  it('delivers an AI briefing once across store restarts without sending a user turn', () => {
    storage.clear()
    setActivePinia(createPinia())
    const store = useConversationStore()
    store.messages = []
    store.addAiNewsMessage('ai_news:2026-10-07', '今日重点及来源')
    store.addAiNewsMessage('ai_news:2026-10-07', '今日重点及来源')
    expect(store.messages).toHaveLength(1)
    expect(store.messages[0]).toMatchObject({ role: 'meguri', text: '今日重点及来源', status: 'complete' })
    setActivePinia(createPinia())
    const restarted = useConversationStore()
    const count = restarted.messages.length
    restarted.addAiNewsMessage('ai_news:2026-10-07', '今日重点及来源')
    expect(restarted.messages).toHaveLength(count)
    restarted.addAiNewsMessage('ai_news:2026-10-08', '新一期')
    expect(restarted.messages).toHaveLength(count + 1)
  })

  beforeEach(() => {
    storage.clear()
    Object.defineProperty(navigator, 'locks', { configurable: true, value: undefined })
    setActivePinia(createPinia())
    helloMock.mockReset().mockResolvedValue({ selected_protocol_version: '1.1' })
    createTurnMock.mockReset().mockResolvedValue({ turn_id: 'turn-1' })
    subscribeMock.mockReset().mockResolvedValue(undefined)
    cancelMock.mockReset().mockResolvedValue(undefined)
    memoryStatusMock.mockReset().mockResolvedValue({ available: true, authorized: true, provider: 'native_pgvector', reason: 'ready' })
    summarizeMock.mockReset().mockResolvedValue({ status: 'persisted', message_count: 2, candidate_ids: ['candidate-1'], checkpoint: 20 })
    vi.mocked(synthesizeSpeech).mockReset().mockResolvedValue(undefined)
    vi.mocked(cancelSpeech).mockClear()
    speechPlaybackState.error = ''
    const preferences = useDesktopPreferencesStore()
    preferences.value.privacy.acceptedPolicyVersion = PRIVACY_POLICY_VERSION
    preferences.value.privacy.remoteChatAllowed = true
    preferences.value.memory.enabled = false
  })

  it('does not revive an interrupted reply as active streaming work', () => {
    const stored: ConversationMessage[] = [{
      id: 'reply-1',
      role: 'meguri',
      text: '',
      createdAt: 1,
      status: 'streaming',
    }]

    expect(restoreConversationMessages(stored)).toEqual([{
      ...stored[0],
      text: '上次回复在应用关闭时中断。',
      status: 'failed',
    }])
  })

  it('restores a completed dialogue after reload and allows the next message', async () => {
    const original = useConversationStore()
    original.messages = [{ id: 'completed-reply', role: 'meguri', text: '上一轮已完成', createdAt: 1, status: 'complete' }]
    original.persist()

    setActivePinia(createPinia())
    const preferences = useDesktopPreferencesStore()
    preferences.value.privacy.acceptedPolicyVersion = PRIVACY_POLICY_VERSION
    preferences.value.privacy.remoteChatAllowed = true
    const reopened = useConversationStore()
    expect(reopened.messages).toEqual([{ id: 'completed-reply', role: 'meguri', text: '上一轮已完成', createdAt: 1, status: 'complete' }])
    expect(reopened.sending).toBe(false)
    expect(await reopened.send('继续聊')).toBe(true)
    expect(createTurnMock).toHaveBeenCalledTimes(1)
  })

  it('turns the missing vision-provider failure into an actionable desktop message', () => {
    expect(friendlyConversationError(
      'The selected model is not configured for image or PDF attachments and MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL is not configured.',
    )).toBe('截图或 PDF 已收到，但当前没有配置视觉模型；请配置独立的视觉模型后再试。')
    expect(friendlyConversationError('Retrieval context budget exceeded while preparing the request.'))
      .toBe('检索资料超过当前上下文预算，请重试或缩小问题范围。')
    expect(friendlyConversationError('required RETRIEVAL context exceeds its source maximum'))
      .toBe('检索资料超过当前上下文预算，请重试或缩小问题范围。')
    expect(friendlyConversationError('required context exceeds the model hard token threshold'))
      .toBe('消息或资料超过当前上下文预算，请重试或缩小问题范围。')
  })

  it('sends selected Everything references with filename-only context and retains them for retry', async () => {
    const attachment: LocalResourceAttachment = {
      type: 'local_file_reference',
      source: 'everything',
      reference_id: 'r-1',
      name: '日报.md',
      path: 'D:\\notes\\日报.md',
      kind: 'file',
      size_bytes: 42,
      modified_at: null,
      content_access: 'not_read',
    }
    const conversation = useConversationStore()

    await conversation.send('帮我总结', [attachment])

    expect(createTurnMock).toHaveBeenCalledWith(
      expect.objectContaining({
        message: expect.stringContaining('日报.md'),
        attachments: [attachment],
      }),
      expect.any(String),
    )
    expect(createTurnMock.mock.calls[0][0].message).not.toContain('D:\\notes')
    expect(conversation.messages[0].text).toBe('帮我总结\n📎 日报.md（仅引用）')
    expect(conversation.lastAttachments).toEqual([attachment])

    createTurnMock.mockClear()
    await conversation.retry()

    expect(createTurnMock.mock.calls[0][0].attachments).toEqual([attachment])
  })

  it('sends explicitly readable screenshot content without calling it metadata-only', async () => {
    const attachment: LocalResourceAttachment = {
      type: 'inline_attachment',
      source: 'airi_inline',
      reference_id: 'inline-1',
      name: '截图.png',
      kind: 'image',
      size_bytes: 4,
      modified_at: '2026-08-19T08:00:00.000Z',
      content_access: 'multimodal_read',
      mime_type: 'image/png',
      data_url: 'data:image/png;base64,iVBORw==',
    }
    const conversation = useConversationStore()

    await conversation.send('看看这里', [attachment])

    const payload = createTurnMock.mock.calls[0][0]
    expect(payload.attachments).toEqual([attachment])
    expect(payload.message).toContain('用户已明确授权读取以下附件内容：截图.png')
    expect(payload.message).not.toContain('内容未读取')
    expect(payload.message).not.toContain(attachment.data_url)
    expect(conversation.messages[0].text).toContain('截图.png（将读取）')
    expect(conversation.lastAttachments).toEqual([attachment])
  })

  it('retains a PixPin direct-send prompt and screenshot for retry after a vision-route failure', async () => {
    const attachment: LocalResourceAttachment = {
      type: 'inline_attachment',
      source: 'airi_inline',
      reference_id: 'inline_capture-1',
      name: '截图-20260929T001000Z.png',
      kind: 'image',
      size_bytes: 4,
      modified_at: '2026-09-29T00:10:00.000Z',
      content_access: 'multimodal_read',
      mime_type: 'image/png',
      data_url: 'data:image/png;base64,iVBORw==',
    }
    const prompt = '请看看这张截图，告诉我你注意到什么。'
    subscribeMock.mockRejectedValueOnce(new Error('The selected model is not configured for image or PDF attachments.'))
    const conversation = useConversationStore()

    expect(await conversation.send(prompt, [attachment])).toBe(false)
    expect(conversation.lastUserText).toBe(prompt)
    expect(conversation.lastAttachments).toEqual([attachment])
    expect(conversation.error).toContain('没有配置视觉模型')

    subscribeMock.mockResolvedValueOnce(undefined)
    await conversation.retry()
    expect(createTurnMock).toHaveBeenCalledTimes(2)
    expect(createTurnMock.mock.calls[1][0]).toMatchObject({
      message: expect.stringContaining(prompt),
      attachments: [attachment],
    })
  })

  it('does not replay an already-consumed one-shot speech event after a surface rebuild', async () => {
    const conversation = useConversationStore()
    const assistant: ConversationMessage = {
      id: 'reply-1',
      role: 'meguri',
      text: '',
      createdAt: 1,
      status: 'streaming',
    }
    const event = {
      event_id: 'speech-once-1',
      session_id: conversation.sessionId,
      turn_id: 'turn-1',
      replay_policy: 'ONCE',
      type: 'tts.requested',
      data: { text: '这一段只播放一次。', voice_style: 'neutral' },
    } as unknown as TurnEventEnvelope

    await conversation.acceptEvent(event, assistant)
    await conversation.acceptEvent(event, assistant)

    await vi.waitFor(() => expect(synthesizeSpeech).toHaveBeenCalledTimes(1))
    expect(synthesizeSpeech).toHaveBeenCalledWith(expect.objectContaining({ requestId: 'desktop-speech-once-1' }))
  })

  it('adds a recognized voice transcript to normal conversation history exactly once', async () => {
    const conversation = useConversationStore()
    let resolveNextRecognition!: (result: { ok: boolean, code?: string }) => void
    const nextRecognition = new Promise<{ ok: boolean, code?: string }>((resolve) => { resolveNextRecognition = resolve })
    const recognize = vi.fn()
      .mockResolvedValueOnce({ ok: true, text: '帮我安排明天的计划' })
      .mockImplementationOnce(() => nextRecognition)
    let resolveSent!: () => void
    const transcriptSent = new Promise<void>((resolve) => { resolveSent = resolve })
    const controller = createVoiceConversationController({
      recognize,
      cancelRecognition: vi.fn(),
      send: async (text) => {
        const completed = await conversation.send(text)
        resolveSent()
        return completed
      },
      cancelConversation: () => conversation.cancel(),
      waitForSpeech: async () => {},
    })

    controller.start()
    await transcriptSent

    expect(conversation.messages.filter(message => message.role === 'user').map(message => message.text))
      .toEqual(['帮我安排明天的计划'])
    expect(conversation.lastUserText).toBe('帮我安排明天的计划')
    expect(createTurnMock).toHaveBeenCalledOnce()

    await controller.stop()
    resolveNextRecognition({ ok: false, code: 'cancelled' })
  })

  it('persists the original speech cue and explicitly replays it without sending a new turn', async () => {
    const conversation = useConversationStore()
    const assistant: ConversationMessage = { id: 'reply-voice', role: 'meguri', text: '**显示的回复**', createdAt: 1, status: 'complete' }
    conversation.messages = [assistant]
    await conversation.acceptEvent({ event_id: 'original-speech', replay_policy: 'ONCE', type: 'tts.requested',
      data: { text: '原本播报的这一句话。', voice_style: 'gentle' } } as unknown as TurnEventEnvelope, assistant)
    expect(assistant.speech).toMatchObject({ text: '原本播报的这一句话。', voiceStyle: 'gentle' })
    setActivePinia(createPinia())
    const restarted = useConversationStore()
    expect(restarted.messages[0].speech).toEqual(assistant.speech)
    vi.mocked(synthesizeSpeech).mockClear()
    await restarted.replaySpeech(assistant.id)
    expect(synthesizeSpeech).toHaveBeenCalledWith(expect.objectContaining({ ...assistant.speech, manual: true,
      requestId: expect.stringMatching(/^manual-replay-/u) }))
    expect(restarted.messages[0].text).toBe('**显示的回复**')
    expect(createTurnMock).not.toHaveBeenCalled()
    expect(helloMock).not.toHaveBeenCalled()
  })

  it('allows a distinct automatic effect id to play in a newly selected session', async () => {
    const conversation = useConversationStore()
    const assistant: ConversationMessage = { id: 'reply-1', role: 'meguri', text: '回复', createdAt: 1, status: 'streaming' }
    const event = { event_id: 'event-same', session_id: conversation.sessionId, turn_id: 'turn-1', replay_policy: 'ONCE',
      type: 'tts.requested', data: { text: '第一会话', voice_style: 'neutral' } } as unknown as TurnEventEnvelope
    await conversation.acceptEvent(event, assistant)
    await vi.waitFor(() => expect(synthesizeSpeech).toHaveBeenCalledTimes(1))

    conversation.newConversation()
    const nextAssistant: ConversationMessage = { id: 'reply-2', role: 'meguri', text: '回复', createdAt: 2, status: 'streaming' }
    await conversation.acceptEvent({ ...event, session_id: conversation.sessionId } as TurnEventEnvelope, nextAssistant)
    await vi.waitFor(() => expect(synthesizeSpeech).toHaveBeenCalledTimes(2))
  })

  it('does not cancel a deliberate replay when an old automatic speech event is delivered again', async () => {
    const original = useConversationStore()
    const assistant: ConversationMessage = { id: 'reply-claimed', role: 'meguri', text: '已回复', createdAt: 1, status: 'complete' }
    original.messages = [assistant]
    const event = { event_id: 'already-claimed', session_id: original.sessionId, turn_id: 'old-turn', replay_policy: 'ONCE',
      type: 'tts.requested', data: { text: '自动语音', voice_style: 'neutral' } } as unknown as TurnEventEnvelope
    await original.acceptEvent(event, assistant)
    await vi.waitFor(() => expect(synthesizeSpeech).toHaveBeenCalledTimes(1))

    setActivePinia(createPinia())
    const restarted = useConversationStore()
    let finishReplay!: () => void
    vi.mocked(synthesizeSpeech).mockImplementationOnce(() => new Promise<void>(resolve => { finishReplay = resolve }))
    const replay = restarted.replaySpeech(assistant.id)
    expect(restarted.speechReplayMessageId).toBe(assistant.id)
    vi.mocked(cancelSpeech).mockClear()
    await restarted.acceptEvent(event, restarted.messages[0])
    expect(restarted.speechReplayMessageId).toBe(assistant.id)
    expect(cancelSpeech).not.toHaveBeenCalled()
    finishReplay()
    await replay
  })

  it('does not start a speech effect when its durable claim resolves after cancellation', async () => {
    let release!: () => void
    const lockWait = new Promise<void>(resolve => { release = resolve })
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: { request: async (_name: string, _options: unknown, action: () => Promise<unknown>) => { await lockWait; return action() } },
    })
    const conversation = useConversationStore()
    const assistant: ConversationMessage = { id: 'reply-1', role: 'meguri', text: '', createdAt: 1, status: 'streaming' }
    await conversation.acceptEvent({ event_id: 'late-speech', session_id: conversation.sessionId, turn_id: 'turn-1',
      replay_policy: 'ONCE', type: 'tts.requested', data: { text: '不应播出', voice_style: 'neutral' } } as unknown as TurnEventEnvelope, assistant)
    await conversation.cancel()
    release()
    await Promise.resolve()
    await Promise.resolve()
    expect(playbackReceiptsForSession(conversation.sessionId)).toEqual([])
    expect(synthesizeSpeech).not.toHaveBeenCalled()
  })

  it('deduplicates repeated motion cues across semantic, expression and sprite events', async () => {
    const conversation = useConversationStore()
    const presentation = usePresentationStore()
    const applyMotion = vi.spyOn(presentation, 'applyMotionCue')
    const assistant: ConversationMessage = { id: 'reply-motion', role: 'meguri', text: '', createdAt: 1, status: 'streaming' }
    for (const [index, type] of ['semantic.completed', 'expression.cue', 'sprite.resolved'].entries()) {
      await conversation.acceptEvent({ event_id: `motion-event-${index}`, session_id: conversation.sessionId, turn_id: 'turn-motion',
        replay_policy: 'ONCE', type, data: { expression_tag: 'happy', motion_tag: 'wave' } } as unknown as TurnEventEnvelope, assistant)
    }
    await vi.waitFor(() => expect(conversation.pendingMotionCues).toHaveLength(1))
    conversation.applyPendingMotionCues('turn-motion', true)
    expect(applyMotion).toHaveBeenCalledTimes(1)
    expect(presentation.motionRevision).toBe(1)
  })

  it('waits for actual speech start to apply a cue and ignores callbacks from the replaced turn', async () => {
    const conversation = useConversationStore()
    const presentation = usePresentationStore()
    const callbacks: Array<(speaking: boolean) => void> = []
    const finishes: Array<() => void> = []
    vi.mocked(synthesizeSpeech).mockImplementation((input) => new Promise<void>((resolve) => {
      callbacks.push(input.onSpeaking!)
      finishes.push(resolve)
    }))
    conversation.activeTurnId = 'turn-beat-1'
    const firstReply: ConversationMessage = { id: 'reply-beat-1', role: 'meguri', text: '', createdAt: 1, status: 'streaming' }
    const envelope = (event_id: string, type: string, data: Record<string, unknown>) => ({
      event_id, session_id: conversation.sessionId, turn_id: conversation.activeTurnId,
      replay_policy: 'ONCE', type, data,
    }) as unknown as TurnEventEnvelope

    await conversation.acceptEvent(envelope('beat-cue-1', 'expression.cue', { motion_tag: 'wave' }), firstReply)
    await vi.waitFor(() => expect(conversation.pendingMotionCues).toHaveLength(1))
    await conversation.acceptEvent(envelope('beat-tts-1', 'tts.requested', { text: '慢慢合成的语音', voice_style: 'neutral' }), firstReply)
    await vi.waitFor(() => expect(synthesizeSpeech).toHaveBeenCalledTimes(1))
    await conversation.acceptEvent(envelope('beat-delta-1', 'text.delta', { delta: '文字先显示。' }), firstReply)

    expect(firstReply.text).toBe('文字先显示。')
    expect(presentation.motionRevision).toBe(0)
    callbacks[0](false)
    expect(presentation.motionRevision).toBe(0)
    callbacks[0](true)
    expect(presentation.motionRevision).toBe(1)
    expect(presentation.character.motionTag).toBe('wave')

    conversation.newConversation()
    conversation.activeTurnId = 'turn-beat-2'
    const secondReply: ConversationMessage = { id: 'reply-beat-2', role: 'meguri', text: '', createdAt: 2, status: 'streaming' }
    await conversation.acceptEvent(envelope('beat-tts-2', 'tts.requested', { text: '新的语音', voice_style: 'neutral' }), secondReply)
    await vi.waitFor(() => expect(synthesizeSpeech).toHaveBeenCalledTimes(2))
    callbacks[1](true)
    expect(presentation.speaking).toBe(true)

    callbacks[0](false)
    callbacks[0](true)
    expect(presentation.speaking).toBe(true)
    expect(presentation.motionRevision).toBe(1)

    callbacks[1](false)
    finishes[0]()
    finishes[1]()
    await vi.waitFor(() => expect(presentation.speaking).toBe(false))
  })

  it('does not apply reply expressions or motion when the user disables them', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.value.companion.expressionsEnabled = false
    preferences.value.companion.motionEnabled = false
    const conversation = useConversationStore()
    const presentation = usePresentationStore()
    const before = { ...presentation.character }
    const assistant: ConversationMessage = { id: 'reply-disabled', role: 'meguri', text: '', createdAt: 1, status: 'streaming' }
    await conversation.acceptEvent({ event_id: 'motion-disabled', session_id: conversation.sessionId, turn_id: 'turn-disabled',
      replay_policy: 'ONCE', type: 'expression.cue', data: { expression_tag: 'angry', motion_tag: 'wave' } } as unknown as TurnEventEnvelope, assistant)
    await Promise.resolve()
    expect(presentation.character).toEqual(before)
    expect(presentation.motionRevision).toBe(0)
    expect(conversation.pendingMotionCues).toHaveLength(0)
  })

  it('applies a motion whose durable claim resolves after completed text when no speech is requested', async () => {
    let release!: () => void
    const wait = new Promise<void>(resolve => { release = resolve })
    Object.defineProperty(navigator, 'locks', {
      configurable: true,
      value: { request: async (_name: string, _options: unknown, action: () => Promise<unknown>) => { await wait; return action() } },
    })
    const conversation = useConversationStore()
    const presentation = usePresentationStore()
    const assistant: ConversationMessage = { id: 'reply-late-motion', role: 'meguri', text: '', createdAt: 1, status: 'streaming' }
    await conversation.acceptEvent({ event_id: 'late-motion', session_id: conversation.sessionId, turn_id: 'turn-late-motion',
      replay_policy: 'ONCE', type: 'expression.cue', data: { motion_tag: 'wave' } } as unknown as TurnEventEnvelope, assistant)
    await conversation.acceptEvent({ event_id: 'late-text', session_id: conversation.sessionId, turn_id: 'turn-late-motion',
      replay_policy: 'ALWAYS', type: 'text.completed', data: { text: '完成' } } as unknown as TurnEventEnvelope, assistant)
    release()
    await vi.waitFor(() => expect(presentation.motionRevision).toBe(1))
  })

  it('replays an old reply with automatic speech disabled and preserves speed, volume, and preferences', async () => {
    const preferences = useDesktopPreferencesStore()
    Object.assign(preferences.value.speech, { ttsEnabled: false, ambientEnabled: false, volume: 0.45, speed: 1.3 })
    const savedPreferences = JSON.stringify(preferences.value)
    const conversation = useConversationStore()
    conversation.messages = [{ id: 'legacy', role: 'meguri', text: '这是一条旧回复。', createdAt: 1 }]
    await conversation.replaySpeech('legacy')
    expect(synthesizeSpeech).toHaveBeenCalledWith(expect.objectContaining({ text: '这是一条旧回复。', manual: true,
      preferences: expect.objectContaining({ ttsEnabled: false, volume: 0.45, speed: 1.3 }) }))
    expect(JSON.stringify(preferences.value)).toBe(savedPreferences)
    expect(conversation.speechReplayMessageId).toBe('')
    expect(createTurnMock).not.toHaveBeenCalled()
  })

  it('stops selected playback and keeps replacement playback selected when an older request settles', async () => {
    let finishFirst!: () => void
    let finishSecond!: () => void
    vi.mocked(synthesizeSpeech)
      .mockImplementationOnce(() => new Promise<void>(resolve => { finishFirst = resolve }))
      .mockImplementationOnce(() => new Promise<void>(resolve => { finishSecond = resolve }))
    const conversation = useConversationStore()
    conversation.messages = ['first', 'second'].map(id => ({ id, role: 'meguri', text: id, createdAt: 1, status: 'complete' }))
    const first = conversation.replaySpeech('first')
    expect(conversation.speechReplayMessageId).toBe('first')
    const second = conversation.replaySpeech('second')
    finishFirst()
    await first
    expect(conversation.speechReplayMessageId).toBe('second')
    await conversation.replaySpeech('second')
    expect(conversation.speechReplayMessageId).toBe('')
    expect(cancelSpeech).toHaveBeenCalledTimes(3)
    finishSecond()
    await second
    expect(conversation.speechReplayMessageId).toBe('')
  })

  it('shows a local speech failure at the selected reply and clears it on retry', async () => {
    vi.mocked(synthesizeSpeech).mockImplementationOnce(async () => { speechPlaybackState.error = '本地语音服务未启动' })
    const conversation = useConversationStore()
    conversation.messages = [{ id: 'reply', role: 'meguri', text: '晚安。', createdAt: 1, status: 'complete' }]
    await conversation.replaySpeech('reply')
    expect(conversation.speechReplayErrorMessageId).toBe('reply')
    expect(conversation.speechReplayError).toBe('本地语音服务未启动')
    expect(conversation.speechReplayMessageId).toBe('')
    speechPlaybackState.error = ''
    await conversation.replaySpeech('reply')
    expect(conversation.speechReplayError).toBe('')
    expect(conversation.messages).toHaveLength(1)
  })

  it('does not replay user messages, files, failures, empty replies, or unfinished responses', async () => {
    const conversation = useConversationStore()
    conversation.messages = [
      { id: 'user', role: 'user', text: '你好', createdAt: 1, status: 'complete' },
      { id: 'file', role: 'artifact', text: 'notes.md', createdAt: 1, status: 'complete' },
      { id: 'failed', role: 'meguri', text: '失败', createdAt: 1, status: 'failed' },
      { id: 'empty', role: 'meguri', text: '', createdAt: 1, status: 'complete' },
      { id: 'stream', role: 'meguri', text: '正在回复', createdAt: 1, status: 'streaming' },
      { id: 'reply', role: 'meguri', text: '完成', createdAt: 1, status: 'complete' },
    ]
    for (const id of ['user', 'file', 'failed', 'empty', 'stream', 'missing']) await conversation.replaySpeech(id)
    conversation.sending = true
    await conversation.replaySpeech('reply')
    expect(synthesizeSpeech).not.toHaveBeenCalled()
  })

  it('blocks chat before consent without network or new messages', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.value.privacy.remoteChatAllowed = false
    const conversation = useConversationStore()
    expect(await conversation.send('hello')).toBe(false)
    expect(conversation.error).toContain('数据政策')
    expect(conversation.messages).toHaveLength(0)
    expect(helloMock).not.toHaveBeenCalled()
    expect(createTurnMock).not.toHaveBeenCalled()
    expect(memoryStatusMock).not.toHaveBeenCalled()
  })

  it('requests formal memory only after consent, enablement and actual authorized readiness', async () => {
    const preferences = useDesktopPreferencesStore()
    const conversation = useConversationStore()
    await conversation.send('without memory')
    expect(helloMock.mock.calls[0][0]).toMatchObject({ capabilities: { formal_memory: false }, permissions: { formal_memory_write: false } })
    preferences.value.memory.enabled = true
    await conversation.send('with memory')
    expect(helloMock.mock.calls[1][0]).toMatchObject({ capabilities: { formal_memory: true }, permissions: { formal_memory_write: true } })
    memoryStatusMock.mockResolvedValueOnce({ available: false, authorized: true, reason: 'authoritative_provider_required' })
    await conversation.send('provider missing')
    expect(helloMock.mock.calls[2][0]).toMatchObject({ capabilities: { formal_memory: false }, permissions: { formal_memory_write: false } })
    expect(conversation.memoryNotice).toContain('不会读取或保存')
  })

  it('reactively publishes streamed text and execution progress while the turn is active', async () => {
    const conversation = useConversationStore()
    const observed: Array<{ text?: string, progress?: string }> = []
    const stop = watchEffect(() => {
      const reply = conversation.messages.filter(message => message.role === 'meguri').at(-1)
      observed.push({ text: reply?.text, progress: reply?.progress?.at(-1)?.label })
    })
    let duringRetrieval: string | undefined
    let duringText: string | undefined
    subscribeMock.mockImplementationOnce(async (_session, _reducer, options) => {
      await options.onEvent({ event_id: 'progress-stage', session_id: conversation.sessionId, turn_id: 'turn-1',
        type: 'turn.stage.changed', data: { stage: 'retrieving' } } as unknown as TurnEventEnvelope)
      await nextTick()
      duringRetrieval = observed.at(-1)?.progress
      await options.onEvent({ event_id: 'progress-delta', session_id: conversation.sessionId, turn_id: 'turn-1',
        type: 'text.delta', data: { delta: '流式文本' } } as unknown as TurnEventEnvelope)
      await nextTick()
      duringText = observed.at(-1)?.text
    })

    await conversation.send('stream me')
    stop()
    expect(duringRetrieval).toBe('正在检索信息')
    expect(duringText).toBe('流式文本')
  })

  it('does not create a turn when consent is withdrawn during hello', async () => {
    let finishHello!: (value: object) => void
    helloMock.mockImplementationOnce(() => new Promise(resolve => { finishHello = resolve }))
    const conversation = useConversationStore()
    const sending = conversation.send('pending hello')
    useDesktopPreferencesStore().value.privacy.remoteChatAllowed = false
    finishHello({ selected_protocol_version: '1.1' })
    expect(await sending).toBe(false)
    expect(createTurnMock).not.toHaveBeenCalled()
    expect(conversation.sending).toBe(false)
  })

  it('starts a new conversation synchronously and accepts a send before the old hello settles', async () => {
    let signalReady!: () => void
    const subscribed = new Promise<void>(resolve => { signalReady = resolve })
    let oldOptions!: { onEvent: (event: TurnEventEnvelope) => Promise<void>, onSnapshot: (snapshot: any) => void }
    subscribeMock.mockImplementationOnce((_session, _reducer, options) => {
      oldOptions = options
      signalReady()
      return new Promise(() => {})
    })
    const conversation = useConversationStore()
    const previousSessionId = conversation.sessionId
    const oldSend = conversation.send('old pending request')
    await subscribed
    expect(conversation.activeTurnId).toBe('turn-1')
    expect(conversation.sending).toBe(true)

    conversation.newConversation()
    const nextSessionId = conversation.sessionId
    expect(nextSessionId).not.toBe(previousSessionId)
    expect(conversation.sending).toBe(false)
    expect(conversation.activeTurnId).toBeUndefined()
    const nextSend = conversation.send('new conversation is ready')
    expect(await nextSend).toBe(true)
    expect(await oldSend).toBe(false)
    expect(conversation.sessionId).toBe(nextSessionId)
    expect(conversation.sending).toBe(false)
    const checkpoint = conversation.checkpoint
    await oldOptions.onEvent({ event_id: 'late-turn-event', session_id: previousSessionId, turn_id: 'turn-1', sequence: 99,
      type: 'text.delta', data: { delta: 'old turn result' } } as unknown as TurnEventEnvelope)
    oldOptions.onSnapshot({ turns: [{ turn_id: 'turn-1', text: 'old snapshot', status: 'complete' }] })
    expect(conversation.checkpoint).toEqual(checkpoint)
    expect(conversation.messages.map(message => message.text).join(' ')).not.toContain('old pending request')
    expect(conversation.messages.map(message => message.text).join(' ')).not.toContain('old turn result')
  })

  it.each(['memory readiness', 'hello', 'turn creation'])('releases a pending %s request immediately when cancelled', async phase => {
    const conversation = useConversationStore()
    let release!: (value: any) => void
    const pending = new Promise(resolve => { release = resolve })
    if (phase === 'memory readiness') {
      useDesktopPreferencesStore().value.memory.enabled = true
      memoryStatusMock.mockImplementationOnce(() => pending)
    }
    else if (phase === 'hello') helloMock.mockImplementationOnce(() => pending)
    else {
      createTurnMock.mockImplementationOnce(() => pending)
    }
    const sending = conversation.send(`pending ${phase}`)
    if (phase === 'turn creation')
      await vi.waitFor(() => expect(createTurnMock).toHaveBeenCalled())
    await conversation.cancel()
    await expect(sending).resolves.toBe(false)
    expect(conversation.sending).toBe(false)
    expect(conversation.lastUserText).toBe(`pending ${phase}`)
    expect(conversation.lastAttachments).toEqual([])
    release(phase === 'hello' ? { selected_protocol_version: '1.1' } : phase === 'turn creation' ? { turn_id: 'late-turn' } : { available: true, authorized: true })
    await Promise.resolve()
    await Promise.resolve()
    if (phase === 'turn creation')
      expect(cancelMock).toHaveBeenCalledWith('late-turn')
  })

  it('bounds a stalled event stream and releases the composer', async () => {
    vi.useFakeTimers()
    try {
      let signalReady!: () => void
      const subscribed = new Promise<void>(resolve => { signalReady = resolve })
      subscribeMock.mockImplementationOnce(() => {
        signalReady()
        return new Promise(() => {})
      })
      const conversation = useConversationStore()
      const sending = conversation.send('slow turn')
      await subscribed
      await vi.advanceTimersByTimeAsync(120_000)
      expect(await sending).toBe(false)
      expect(conversation.sending).toBe(false)
      expect(conversation.error).toContain('连接超时')
    }
    finally { vi.useRealTimers() }
  })

  it('ignores late events and snapshots from a turn after switching conversations', async () => {
    let signalReady!: () => void
    const subscribed = new Promise<void>(resolve => { signalReady = resolve })
    let options!: { onEvent: (event: TurnEventEnvelope) => Promise<void>, onSnapshot: (snapshot: any) => void }
    subscribeMock.mockImplementationOnce((_session, _reducer, value) => {
      options = value
      signalReady()
      return new Promise(() => {})
    })
    const conversation = useConversationStore()
    const sending = conversation.send('old session')
    await subscribed
    conversation.newConversation()
    expect(await sending).toBe(false)
    const sessionId = conversation.sessionId
    const originalCheckpoint = conversation.checkpoint
    await options.onEvent({ event_id: 'late-event', session_id: 'old-session', turn_id: 'turn-1', sequence: 99,
      type: 'text.delta', data: { delta: 'stale text' } } as unknown as TurnEventEnvelope)
    options.onSnapshot({ turns: [{ turn_id: 'turn-1', text: 'stale snapshot', status: 'complete' }] })
    expect(conversation.sessionId).toBe(sessionId)
    expect(conversation.checkpoint).toBe(originalCheckpoint)
    expect(conversation.messages).toEqual([])
  })

  it('cancels the active stream when memory consent is withdrawn', async () => {
    let subscribed!: () => void
    const ready = new Promise<void>(resolve => { subscribed = resolve })
    subscribeMock.mockImplementationOnce((_session, _reducer, options) => new Promise((_resolve, reject) => {
      options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')))
      subscribed()
    }))
    useDesktopPreferencesStore().value.memory.enabled = true
    const conversation = useConversationStore()
    const sending = conversation.send('pending stream')
    await ready
    useDesktopPreferencesStore().value.memory.enabled = false
    expect(await sending).toBe(false)
    expect(cancelMock).toHaveBeenCalledWith('turn-1')
    expect(conversation.sending).toBe(false)
  })

  it('submits one summary per stable conversation checkpoint and stops when disabled', async () => {
    useDesktopPreferencesStore().value.memory.enabled = true
    const conversation = useConversationStore()
    conversation.checkpoint = { last_sequence: 20, processed_event_ids: [] }
    expect(await conversation.summarizeCurrentSession()).toBe(true)
    expect(await conversation.summarizeCurrentSession()).toBe(true)
    expect(summarizeMock).toHaveBeenCalledTimes(1)
    expect(summarizeMock).toHaveBeenCalledWith(conversation.sessionId, conversation.installId, 20, expect.any(AbortSignal))
    useDesktopPreferencesStore().value.memory.enabled = false
    conversation.checkpoint.last_sequence = 21
    expect(await conversation.summarizeCurrentSession()).toBe(false)
    expect(summarizeMock).toHaveBeenCalledTimes(1)
  })

  it('releases summary state on reset and ignores a summary response from the prior session', async () => {
    useDesktopPreferencesStore().value.memory.enabled = true
    let finishSummary!: (value: { status: string, message_count: number, candidate_ids: string[], checkpoint: number }) => void
    summarizeMock.mockImplementationOnce(() => new Promise(resolve => { finishSummary = resolve }))
    const conversation = useConversationStore()
    conversation.checkpoint = { last_sequence: 20, processed_event_ids: [] }
    const previousSessionId = conversation.sessionId
    const summarizing = conversation.summarizeCurrentSession()
    await vi.waitFor(() => expect(summarizeMock).toHaveBeenCalled())

    conversation.newConversation()
    expect(conversation.sessionId).not.toBe(previousSessionId)
    expect(conversation.summaryBusy).toBe(false)
    finishSummary({ status: 'persisted', message_count: 2, candidate_ids: ['old-candidate'], checkpoint: 20 })
    expect(await summarizing).toBe(false)
    expect(conversation.lastSummaryCheckpoint).toBe(0)
    expect(conversation.memoryNotice).toBe('')
    expect(conversation.memoryStatus).toBeUndefined()
  })

  it('does not count failed streamed replies toward automatic memory summaries', async () => {
    const preferences = useDesktopPreferencesStore()
    preferences.value.memory.enabled = true
    preferences.value.memory.summaryEveryTurns = 3
    const conversation = useConversationStore()
    conversation.successfulTurns = 2
    subscribeMock.mockImplementationOnce(async (_session, _reducer, options) => {
      await options.onEvent({ event_id: 'failed-1', type: 'turn.failed', replay_policy: 'STATEFUL', data: { error: 'provider unavailable' } })
    })
    expect(await conversation.send('failure')).toBe(false)
    expect(conversation.successfulTurns).toBe(2)
    expect(conversation.messages.at(-1)?.status).toBe('failed')
    expect(conversation.messages.at(-1)?.text).toBe('这次回复未完成，请查看下方原因或重试。')
    expect(summarizeMock).not.toHaveBeenCalled()
  })
  it('does not count server-cancelled replies as successful conversation turns', async () => {
    const conversation = useConversationStore()
    subscribeMock.mockImplementationOnce(async (_session, _reducer, options) => {
      await options.onEvent({ event_id: 'cancelled-1', type: 'turn.cancelled', replay_policy: 'STATEFUL', data: {} })
    })
    expect(await conversation.send('cancelled')).toBe(false)
    expect(conversation.successfulTurns).toBe(0)
    expect(conversation.messages.at(-1)?.text).toContain('取消')
  })

  it('disables new history retention while preserving old saved data until explicit clear', () => {
    const conversation = useConversationStore()
    conversation.messages.push({ id: 'old', role: 'user', text: 'previously saved', createdAt: 1 })
    conversation.persist()
    const oldValue = storage.get('meguri.desktop.conversation.v1')
    useDesktopPreferencesStore().value.privacy.saveHistory = false
    conversation.messages.push({ id: 'new', role: 'user', text: 'not retained', createdAt: 2 })
    conversation.persist()
    expect(storage.get('meguri.desktop.conversation.v1')).toBe(oldValue)
    expect(storage.get('meguri.desktop.conversation.metadata.v1')).not.toContain('not retained')
    conversation.clearHistory()
    expect(storage.has('meguri.desktop.conversation.v1')).toBe(false)
    expect(conversation.messages).toHaveLength(0)
  })
})
