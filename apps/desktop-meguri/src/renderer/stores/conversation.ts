import { MeguriApiClient } from '@meguri/client-sdk'
import { SessionTurnReducer, type SessionEventCheckpoint, type TurnEventEnvelope } from '@meguri/protocol'
import { defineStore } from 'pinia'
import { reactive, watch } from 'vue'

import { coreUrl, desktopFetch } from '../services/desktop-fetch'
import { isReadableAttachment, type LocalResourceAttachment } from '../services/local-resources'
import { usePresentationStore } from './presentation'
import { useDesktopPreferencesStore } from './desktop-preferences'
import { useTrainingFeedbackStore } from './training-feedback'
import { usePresentationSurfaceStore } from './presentation-surface'
import { remoteChatConsented } from '../../shared/experience-contract'
import type { DesktopMemoryStatus } from '../../shared/memory-contract'
import { memoryStatus, summarizeMemory } from '../services/memory-api'
import { playSpeech as synthesizeSpeech, cancelSpeech, speechPlaybackState, updateSpeechPreferences } from '../services/speech-playback'
import { claimPlaybackEffect, updatePlaybackReceipt } from '../services/playback-ledger'
import { useCompanionDirectorStore } from './companion-director'
import { finishTurnProgress, progressForEvent, type TurnProgressItem } from '../utils/turn-progress'

export type ConversationRole = 'user' | 'meguri' | 'system' | 'artifact'

export interface ConversationMessage {
  id: string
  role: ConversationRole
  text: string
  createdAt: number
  status?: 'streaming' | 'complete' | 'failed'
  turnId?: string
  href?: string
  localPath?: string
  speech?: { text: string, voiceStyle: string, intensity: string }
  progress?: TurnProgressItem[]
}

interface PersistedConversation {
  userId: string
  installId: string
  sessionId: string
  checkpoint?: SessionEventCheckpoint
  messages: ConversationMessage[]
  successfulTurns?: number
  lastSummaryCheckpoint?: number
}

const storageKey = 'meguri.desktop.conversation.v1'
const metadataStorageKey = 'meguri.desktop.conversation.metadata.v1'
const historyClearKey = 'meguri.desktop.conversation.clear.v1'

function identifier(prefix: string): string {
  return `${prefix}-${crypto.randomUUID()}`
}

export function restoreConversationMessages(messages: ConversationMessage[]): ConversationMessage[] {
  return messages.slice(-100).map(message => message.status === 'streaming'
    ? {
        ...message,
        text: message.text || '上次回复在应用关闭时中断。',
        status: 'failed' as const,
      }
    : message)
}

export function friendlyConversationError(reason: string): string {
  if (reason.includes('not configured for image or PDF attachments')
    || reason.includes('MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL')) {
    return '截图或 PDF 已收到，但当前没有配置视觉模型；请配置独立的视觉模型后再试。'
  }
  if (/required\s+retrieval\s+context\s+exceeds\s+its\s+source\s+maximum/i.test(reason)
    || /(?:context|prompt).{0,48}budget|budget.{0,48}(?:context|prompt)|上下文.{0,8}预算|预算.{0,8}上下文/i.test(reason))
    return '检索资料超过当前上下文预算，请重试或缩小问题范围。'
  if (/required\s+context\s+exceeds\s+the\s+model\s+hard\s+token\s+threshold/i.test(reason))
    return '消息或资料超过当前上下文预算，请重试或缩小问题范围。'
  return reason
}

function readPersisted(): PersistedConversation {
  try {
    const history = JSON.parse(localStorage.getItem(storageKey) ?? '{}') as Partial<PersistedConversation>
    const metadata = JSON.parse(localStorage.getItem(metadataStorageKey) ?? '{}') as Partial<PersistedConversation>
    const value = { ...history, ...metadata }
    return {
      userId: typeof value.userId === 'string' ? value.userId : 'local-airi-user',
      installId: typeof value.installId === 'string' ? value.installId : identifier('meguri-install'),
      sessionId: typeof value.sessionId === 'string' ? value.sessionId : identifier('meguri-desktop'),
      checkpoint: value.checkpoint,
      messages: Array.isArray(history.messages) && (!metadata.sessionId || metadata.sessionId === history.sessionId) ? restoreConversationMessages(history.messages) : [],
      successfulTurns: Number.isSafeInteger(value.successfulTurns) ? value.successfulTurns : 0,
      lastSummaryCheckpoint: Number.isSafeInteger(value.lastSummaryCheckpoint) ? value.lastSummaryCheckpoint : 0,
    }
  }
  catch {
    return {
      userId: 'local-airi-user',
      installId: identifier('meguri-install'),
      sessionId: identifier('meguri-desktop'),
      messages: [],
    }
  }
}

function clientFor(sessionId: string): MeguriApiClient {
  return new MeguriApiClient(coreUrl, (input, init) => desktopFetch(sessionId, input, init) as never)
}

class RequestTimeoutError extends Error { }

function waitForRequest<T>(request: Promise<T>, signal: AbortSignal, timeoutMs: number, onTimeout?: () => void): Promise<T> {
  return new Promise((resolve, reject) => {
    if (signal.aborted) {
      reject(new DOMException('Conversation was interrupted', 'AbortError'))
      return
    }
    let settled = false
    const finish = (callback: () => void) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      signal.removeEventListener('abort', onAbort)
      callback()
    }
    const onAbort = () => finish(() => reject(new DOMException('Conversation was interrupted', 'AbortError')))
    const timer = setTimeout(() => finish(() => {
      onTimeout?.()
      reject(new RequestTimeoutError('连接超时，请检查 Meguri Core 后重试。'))
    }), timeoutMs)
    signal.addEventListener('abort', onAbort, { once: true })
    request.then(value => finish(() => resolve(value)), error => finish(() => reject(error)))
  })
}

const conversationDefinition = defineStore('conversation', {
  state: () => ({
    ...readPersisted(),
    activeTurnId: undefined as string | undefined,
    sending: false,
    error: '',
    lastUserText: '',
    lastAttachments: [] as LocalResourceAttachment[],
    abortController: undefined as AbortController | undefined,
    memoryStatus: undefined as DesktopMemoryStatus | undefined,
    memoryNotice: '',
    summaryBusy: false,
    privacySyncInstalled: false,
    summaryAbortController: undefined as AbortController | undefined,
    speechReplayMessageId: '',
    speechReplayErrorMessageId: '',
    speechReplayError: '',
    speechReplaySequence: 0,
    speechRequestSequence: 0,
    automaticSpeechEventIds: [] as string[],
    pendingMotionCues: [] as Array<{ identity: { sessionId: string, turnId?: string, effectId: string, kind: 'motion' }, motionTag: string, interruptionGeneration: number }>,
    speechRequestedTurnIds: [] as string[],
    completedTurnIds: [] as string[],
  }),
  getters: {
    hasMessages: state => state.messages.length > 0,
  },
  actions: {
    initializePrivacySync() {
      if (this.privacySyncInstalled)
        return
      this.privacySyncInstalled = true
      const preferences = useDesktopPreferencesStore()
      const surface = usePresentationSurfaceStore()
      watch(() => [preferences.value.speech, surface.surface] as const,
        ([speech, activeSurface]) => updateSpeechPreferences(speech, activeSurface), { deep: true, flush: 'sync' })
      watch(() => [remoteChatConsented(preferences.value.privacy), preferences.value.memory.enabled] as const,
        ([chatAllowed, memoryEnabled], previous) => {
          if (!chatAllowed || (previous?.[1] && !memoryEnabled)) {
            this.summaryAbortController?.abort()
            if (this.sending)
              void this.cancel()
          }
          if (!memoryEnabled)
            this.memoryStatus = undefined
        }, { flush: 'sync' })
      if (typeof window !== 'undefined') {
        window.addEventListener('storage', event => {
          if (event.key !== historyClearKey || !event.newValue)
            return
          try {
            const marker = JSON.parse(event.newValue) as { sessionId?: unknown }
            if (typeof marker.sessionId !== 'string' || !/^meguri-desktop-[a-f0-9-]+$/.test(marker.sessionId))
              return
            void this.cancel()
            this.sessionId = marker.sessionId
            this.checkpoint = undefined
            this.messages = []
            this.lastUserText = ''
            this.lastAttachments = []
            this.successfulTurns = 0
            this.lastSummaryCheckpoint = 0
            localStorage.removeItem(storageKey)
            this.persist()
            localStorage.removeItem(storageKey)
          }
          catch { /* Ignore malformed clear markers. */ }
        })
      }
    },
    persist() {
      const metadata = {
        userId: this.userId,
        installId: this.installId,
        sessionId: this.sessionId,
        checkpoint: this.checkpoint,
        successfulTurns: this.successfulTurns ?? 0,
        lastSummaryCheckpoint: this.lastSummaryCheckpoint ?? 0,
      }
      localStorage.setItem(metadataStorageKey, JSON.stringify(metadata))
      if (useDesktopPreferencesStore().value.privacy.saveHistory)
        localStorage.setItem(storageKey, JSON.stringify({ ...metadata, messages: this.messages.slice(-100) }))
    },
    async send(text: string, attachments: LocalResourceAttachment[] = []): Promise<boolean> {
      const message = text.trim()
      if (!message || this.sending)
        return false
      const preferences = useDesktopPreferencesStore()
      if (!remoteChatConsented(preferences.value.privacy)) {
        this.error = '请先在设置中的隐私与数据页面阅读并接受当前数据政策，允许 AI 对话后再发送。'
        return false
      }
      this.stopSpeechReplay()
      const selectedAttachments = attachments.map(attachment => ({ ...attachment }))
      const sendingSessionId = this.sessionId
      const metadataAttachments = selectedAttachments.filter(attachment => !isReadableAttachment(attachment))
      const readableAttachments = selectedAttachments.filter(isReadableAttachment)
      const attachmentContext = [
        metadataAttachments.length
          ? `[本地资源引用（仅文件名等元数据，内容未读取）：${metadataAttachments.map(attachment => `${attachment.name}${attachment.kind === 'directory' ? '（文件夹）' : ''}`).join('、')}]`
          : '',
        readableAttachments.length
          ? `[用户已明确授权读取以下附件内容：${readableAttachments.map(attachment => attachment.name).join('、')}]`
          : '',
      ].filter(Boolean)
      const outgoingMessage = attachmentContext.length ? `${message}\n\n${attachmentContext.join('\n')}` : message
      this.lastUserText = message
      this.lastAttachments = selectedAttachments
      this.error = ''
      this.sending = true
      void useCompanionDirectorStore().setRuntimeContext({ activeTurn: true })
      const presentation = usePresentationStore()
      presentation.setStatus('Meguri 正在思考…', 'busy')
      const userMessage: ConversationMessage = {
        id: identifier('message'),
        role: 'user',
        text: selectedAttachments.length
          ? `${message}\n📎 ${selectedAttachments.map(attachment => `${attachment.name}${isReadableAttachment(attachment) ? '（将读取）' : '（仅引用）'}`).join('、')}`
          : message,
        createdAt: Date.now(),
        status: 'complete',
      }
      const assistantMessage = reactive<ConversationMessage>({
        id: identifier('message'),
        role: 'meguri',
        text: '',
        createdAt: Date.now(),
        status: 'streaming',
        progress: [{ label: '正在连接 Meguri…', state: 'active' }],
      })
      this.messages.push(userMessage, assistantMessage)
      this.persist()

      const client = clientFor(this.sessionId)
      const reducer = new SessionTurnReducer(this.checkpoint ?? 0)
      const controller = new AbortController()
      this.abortController = controller
      const sendController = controller
      let completed = false
      try {
        let useMemory = false
        if (preferences.value.memory.enabled) {
          try {
            this.memoryStatus = await waitForRequest(memoryStatus(sendingSessionId), controller.signal, 1_800)
          }
          catch (error) {
            if (controller.signal.aborted) throw error
            this.memoryStatus = { available: false, authorized: false, provider: 'unavailable', reason: 'readiness_timeout' }
          }
          useMemory = this.memoryStatus.available && this.memoryStatus.authorized
          this.memoryNotice = useMemory ? '' : '正式记忆当前不可用，本次对话不会读取或保存正式记忆。'
        }
        else {
          this.memoryNotice = ''
        }
        // A consent change while optional readiness was being queried takes
        // effect before any conversation or memory request is submitted.
        if (!remoteChatConsented(preferences.value.privacy))
          throw new Error('AI 对话许可已撤回，请在隐私与数据设置中重新允许后发送。')
        if (controller.signal.aborted || this.sessionId !== sendingSessionId)
          throw new DOMException('Conversation was interrupted', 'AbortError')
        useMemory = useMemory && preferences.value.memory.enabled
        const helloRequest = client.hello({
          protocol_versions: ['1.1', '1.0'],
          identity: {
            meguri_user: { id: this.userId },
            platform_actor: { platform: 'desktop', actor_id: 'local-user' },
            client_instance: { id: this.installId, profile: 'desktop_pet' },
            session: { id: this.sessionId },
          },
          capabilities: {
            text: true,
            voice: true,
            sprite: true,
            screen_context: false,
            formal_memory: useMemory,
            sse: true,
          },
          permissions: {
            screen_read: false,
            microphone: false,
            audio_playback: true,
            notifications: false,
            formal_memory_write: useMemory,
          },
        })
        const hello = await waitForRequest(helloRequest, controller.signal, 20_000, () => controller.abort())
        if (controller.signal.aborted || this.sessionId !== sendingSessionId || !remoteChatConsented(preferences.value.privacy))
          throw new DOMException('Conversation permission was withdrawn', 'AbortError')
        const createRequest = client.createTurn({
          protocol_version: hello.selected_protocol_version,
          identity: {
            meguri_user: { id: this.userId },
            platform_actor: { platform: 'desktop', actor_id: 'local-user' },
            client_instance: { id: this.installId, profile: 'desktop_pet' },
            session: { id: this.sessionId },
          },
          message: outgoingMessage,
          training_mode: preferences.value.training.enabled,
          ...(selectedAttachments.length ? { attachments: selectedAttachments } : {}),
        }, identifier('desktop-turn'))
        void createRequest.then(created => {
          if (controller.signal.aborted || this.sessionId !== sendingSessionId)
            void client.cancel(created.turn_id).catch(() => {})
        }).catch(() => {})
        const created = await waitForRequest(createRequest, controller.signal, 20_000, () => controller.abort())
        if (controller.signal.aborted || this.sessionId !== sendingSessionId || !remoteChatConsented(preferences.value.privacy)) {
          void client.cancel(created.turn_id).catch(() => {})
          throw new DOMException('Conversation permission was withdrawn', 'AbortError')
        }
        this.activeTurnId = created.turn_id
        assistantMessage.turnId = created.turn_id
        const isCurrentTurn = () => !controller.signal.aborted
          && this.abortController === controller
          && this.sessionId === sendingSessionId
          && this.activeTurnId === created.turn_id
        const subscribeRequest = client.subscribe(sendingSessionId, reducer, {
          signal: controller.signal,
          untilTurnId: created.turn_id,
          onEvent: async event => {
            if (!isCurrentTurn() || (event.turn_id && event.turn_id !== created.turn_id)
              || (event.session_id && event.session_id !== sendingSessionId)) return
            // The reducer has accepted this sequence before the callback. Persist
            // it before any ONCE audio side effect so reconnect cannot replay TTS.
            this.checkpoint = reducer.checkpoint()
            this.persist()
            await this.acceptEvent(event, assistantMessage)
            if (event.type === 'turn.cancelled') { this.stopSpeechReplay(); controller.abort() }
          },
          onSnapshot: snapshot => {
            if (!isCurrentTurn()) return
            const turn = snapshot.turns.find(candidate => candidate.turn_id === created.turn_id)
            if (turn) {
              assistantMessage.text = turn.text
              assistantMessage.status = turn.status === 'failed' ? 'failed' : 'complete'
              if (turn.status === 'failed' || turn.status === 'cancelled') {
                usePresentationSurfaceStore().interruptPresentation()
                this.stopSpeechReplay()
                if (turn.status === 'cancelled') controller.abort()
              }
              if (turn.expression && useDesktopPreferencesStore().value.companion.expressionsEnabled)
                presentation.applyStaticCue(turn.expression)
            }
          },
        })
        await waitForRequest(subscribeRequest, controller.signal, 120_000, () => controller.abort())
        this.checkpoint = reducer.checkpoint()
        if (!this.completedTurnIds.includes(created.turn_id)) this.completedTurnIds.push(created.turn_id)
        if (this.completedTurnIds.length > 512) this.completedTurnIds.splice(0, this.completedTurnIds.length - 512)
        if (!this.speechRequestedTurnIds.includes(created.turn_id))
          this.applyPendingMotionCues(created.turn_id, true)
        if (controller.signal.aborted)
          throw new DOMException('Conversation was cancelled', 'AbortError')
        if (assistantMessage.status === 'failed')
          throw new Error(this.error || '这次回复失败，请重试。')
        assistantMessage.status = 'complete'
        assistantMessage.progress = finishTurnProgress(assistantMessage.progress ?? [], 'complete')
        presentation.setStatus('Meguri 已回应', 'ready')
        completed = true
      }
      catch (error) {
        if (controller.signal.aborted && !(error instanceof RequestTimeoutError)) {
          assistantMessage.status = 'complete'
          assistantMessage.progress = finishTurnProgress(assistantMessage.progress ?? [], 'cancelled')
          if (!assistantMessage.text)
            assistantMessage.text = '这次回复已取消。'
          if (this.sessionId === sendingSessionId && this.abortController === sendController)
            presentation.setStatus('已取消回复', 'ready')
        }
        else {
          const reason = friendlyConversationError(error instanceof Error ? error.message : String(error))
          if (this.sessionId === sendingSessionId && this.abortController === sendController)
            this.error = reason
          assistantMessage.status = 'failed'
          assistantMessage.progress = finishTurnProgress(assistantMessage.progress ?? [], 'failed')
          if (!assistantMessage.text)
            assistantMessage.text = '这次回复未完成，请查看下方原因或重试。'
          if (this.sessionId === sendingSessionId && this.abortController === sendController)
            presentation.setStatus('对话连接失败', 'error')
        }
      }
      finally {
        if (this.abortController === sendController) {
          this.activeTurnId = undefined
          this.abortController = undefined
          this.sending = false
          void useCompanionDirectorStore().setRuntimeContext({ activeTurn: false,
            speaking: presentation.speaking || speechPlaybackState.speaking || speechPlaybackState.busy })
          this.persist()
        }
      }
      if (completed) {
        if (this.sessionId !== sendingSessionId)
          return false
        this.successfulTurns = (this.successfulTurns ?? 0) + 1
        this.persist()
        if (preferences.value.training.enabled)
          void useTrainingFeedbackStore().refresh(sendingSessionId)
        const currentPreferences = useDesktopPreferencesStore().value
        if (currentPreferences.memory.enabled && currentPreferences.memory.autoSummarize
          && this.successfulTurns % currentPreferences.memory.summaryEveryTurns === 0)
          void this.summarizeCurrentSession()
      }
      return completed
    },
    async summarizeCurrentSession(): Promise<boolean> {
      const preferences = useDesktopPreferencesStore().value
      if (!preferences.memory.enabled || !remoteChatConsented(preferences.privacy)) {
        this.memoryNotice = '请先接受数据政策、允许 AI 对话并开启正式记忆。'
        return false
      }
      const checkpoint = this.checkpoint?.last_sequence ?? 0
      const summarySessionId = this.sessionId
      if (this.sending || this.summaryBusy || !checkpoint) {
        this.memoryNotice = '请等待当前回复完成，或先进行一轮对话。'
        return false
      }
      if (this.lastSummaryCheckpoint === checkpoint) {
        this.memoryNotice = '这个会话进度已经整理过；继续对话后可以再次整理。'
        return true
      }
      this.summaryBusy = true
      const summaryController = new AbortController()
      this.summaryAbortController = summaryController
      const isCurrentSummary = () => this.summaryAbortController === summaryController
        && this.sessionId === summarySessionId && !summaryController.signal.aborted
      try {
        const status = await waitForRequest(memoryStatus(summarySessionId), summaryController.signal, 8_000)
        if (!isCurrentSummary()) return false
        this.memoryStatus = status
        if (!status.available || !status.authorized)
          throw new Error('正式记忆服务尚未就绪，请在记忆设置中检查状态。')
        if (!useDesktopPreferencesStore().value.memory.enabled || !remoteChatConsented(useDesktopPreferencesStore().value.privacy))
          throw new Error('正式记忆许可已撤回，整理已停止。')
        if (!isCurrentSummary())
          throw new Error('当前会话已切换，整理已停止。')
        const result = await summarizeMemory(summarySessionId, this.installId, checkpoint, summaryController.signal)
        if (!isCurrentSummary())
          return false
        this.lastSummaryCheckpoint = checkpoint
        this.memoryNotice = `已整理最近 ${result.message_count} 条消息，${result.candidate_ids.length} 条候选等待审核。`
        this.persist()
        return true
      }
      catch (error) {
        if (isCurrentSummary())
          this.memoryNotice = error instanceof Error ? error.message : '会话整理失败，请稍后重试。'
        return false
      }
      finally {
        if (this.summaryAbortController === summaryController) {
          this.summaryAbortController = undefined
          this.summaryBusy = false
        }
      }
    },
    clearHistory() {
      void this.cancel()
      useTrainingFeedbackStore().clearLocalDrafts()
      this.newConversation()
      localStorage.removeItem(storageKey)
      localStorage.setItem(historyClearKey, JSON.stringify({ sessionId: this.sessionId, marker: crypto.randomUUID() }))
    },
    async acceptEvent(event: TurnEventEnvelope, assistantMessage: ConversationMessage) {
      if (event.session_id && event.session_id !== this.sessionId) return
      if (event.turn_id && this.activeTurnId && event.turn_id !== this.activeTurnId) return
      if (event.turn_id && assistantMessage.turnId && event.turn_id !== assistantMessage.turnId) return
      const data = event.data
      const presentation = usePresentationStore()
      const surface = usePresentationSurfaceStore()
      assistantMessage.progress = progressForEvent(assistantMessage.progress ?? [], event)
      if (event.type === 'text.delta' && typeof data.delta === 'string')
        assistantMessage.text += data.delta
      else if (event.type === 'text.completed' && typeof data.text === 'string') {
        assistantMessage.text = data.text
        if (event.turn_id && !this.completedTurnIds.includes(event.turn_id))
          this.completedTurnIds.push(event.turn_id)
        if (this.completedTurnIds.length > 512)
          this.completedTurnIds.splice(0, this.completedTurnIds.length - 512)
        const speech = useDesktopPreferencesStore().value.speech
        const activeSurface = usePresentationSurfaceStore().surface
        if (!speech.ttsEnabled || !(activeSurface === 'galgame' ? speech.galgameEnabled : speech.ambientEnabled))
          this.applyPendingMotionCues(event.turn_id, true)
      }
      else if (['semantic.completed', 'expression.cue', 'sprite.resolved'].includes(event.type)) {
        if (useDesktopPreferencesStore().value.companion.expressionsEnabled)
          presentation.applyStaticCue(data)
        if (typeof data.motion_tag === 'string' && data.motion_tag.trim())
          void this.playAutomaticMotion(event, data.motion_tag)
      }
      else if (event.type === 'tool.started')
        presentation.setStatus('正在调用能力…', 'busy')
      else if (event.type === 'tts.requested' && typeof data.text === 'string' && data.text.trim()) {
        if (event.turn_id && !this.speechRequestedTurnIds.includes(event.turn_id)) {
          this.speechRequestedTurnIds.push(event.turn_id)
          if (this.speechRequestedTurnIds.length > 512)
            this.speechRequestedTurnIds.splice(0, this.speechRequestedTurnIds.length - 512)
        }
        assistantMessage.speech = {
          text: data.text,
          voiceStyle: typeof data.voice_style === 'string' ? data.voice_style : 'neutral',
          intensity: presentation.character.intensity,
        }
        void this.playAutomaticSpeech(event, assistantMessage.speech.text, assistantMessage.speech.voiceStyle, assistantMessage.speech.intensity, assistantMessage)
      }
      else if (event.type === 'turn.failed') {
        assistantMessage.status = 'failed'
        assistantMessage.progress = finishTurnProgress(assistantMessage.progress ?? [], 'failed')
        this.error = friendlyConversationError(typeof data.error === 'string' ? data.error : 'Turn failed')
        surface.interruptPresentation()
        this.interruptPendingMotionCues(event.turn_id)
        this.stopSpeechReplay()
      }
      else if (event.type === 'turn.cancelled') {
        assistantMessage.progress = finishTurnProgress(assistantMessage.progress ?? [], 'cancelled')
        surface.interruptPresentation()
        this.interruptPendingMotionCues(event.turn_id)
        this.stopSpeechReplay()
      }
      else if (event.type === 'training.candidates.ready') {
        try { useTrainingFeedbackStore().capture(event.session_id, event.turn_id, data) }
        catch { /* An optional invalid feedback event cannot interrupt conversation. */ }
      }
      this.persist()
    },
    async playSpeech(text: string, voiceStyle: string, intensity: string) {
      this.stopSpeechReplay()
      const sequence = this.speechRequestSequence
      const presentation = usePresentationStore()
      const preferences = useDesktopPreferencesStore()
      const surface = usePresentationSurfaceStore()
      await synthesizeSpeech({ text, voiceStyle, intensity, preferences: preferences.value.speech,
        surface: surface.surface, requestId: identifier('desktop-notice'),
        onSpeaking: speaking => { if (sequence === this.speechRequestSequence) presentation.setSpeaking(speaking) } })
      if (sequence === this.speechRequestSequence)
        presentation.setSpeaking(false)
    },
    async playNoticeSpeech(text: string, voiceStyle: string, intensity: string, noticeId: string): Promise<boolean> {
      const sessionId = this.sessionId
      const identity = { sessionId, effectId: noticeId, kind: 'speech' as const }
      if (!await claimPlaybackEffect(identity)) return false
      const director = useCompanionDirectorStore()
      const surface = usePresentationSurfaceStore()
      const preferences = useDesktopPreferencesStore()
      if (sessionId !== this.sessionId || director.context.focus === 'focused' || director.context.activeTurn
        || director.context.speaking || speechPlaybackState.speaking || speechPlaybackState.busy
        || !preferences.value.speech.ttsEnabled || preferences.value.speech.volume <= 0
        || !(surface.surface === 'ambient' ? preferences.value.speech.ambientEnabled : preferences.value.speech.galgameEnabled)) {
        await updatePlaybackReceipt(identity, 'skipped')
        return false
      }
      this.stopSpeechReplay()
      const sequence = this.speechRequestSequence
      const presentation = usePresentationStore()
      void (async () => {
        try {
          await synthesizeSpeech({ text, voiceStyle, intensity, preferences: preferences.value.speech,
            surface: surface.surface, requestId: `desktop-${sessionId}-${noticeId}`,
            onSpeaking: speaking => { if (sequence === this.speechRequestSequence) presentation.setSpeaking(speaking) } })
          await updatePlaybackReceipt(identity, sequence === this.speechRequestSequence ? 'completed' : 'interrupted')
        }
        catch { await updatePlaybackReceipt(identity, 'interrupted') }
        finally {
          if (sequence === this.speechRequestSequence) presentation.setSpeaking(false)
        }
      })()
      return true
    },
    async playAutomaticSpeech(event: TurnEventEnvelope, text: string, voiceStyle: string, intensity: string, assistantMessage: ConversationMessage) {
      if (this.automaticSpeechEventIds.includes(event.event_id)) return
      const expectedSequence = this.speechRequestSequence
      const sessionId = this.sessionId
      const surfaceStore = usePresentationSurfaceStore()
      const interrupted = surfaceStore.interruptionGeneration
      const allowed = () => expectedSequence === this.speechRequestSequence
        && sessionId === this.sessionId
        && (!event.session_id || event.session_id === this.sessionId)
        && (!event.turn_id || !this.activeTurnId || event.turn_id === this.activeTurnId)
        && interrupted === surfaceStore.interruptionGeneration
        && assistantMessage.status !== 'failed'
      if (!allowed()) return
      this.automaticSpeechEventIds.push(event.event_id)
      if (this.automaticSpeechEventIds.length > 512)
        this.automaticSpeechEventIds.splice(0, this.automaticSpeechEventIds.length - 512)
      const identity = { sessionId, turnId: event.turn_id, effectId: event.event_id, kind: 'speech' as const }
        if (!await claimPlaybackEffect(identity, Date.now(), allowed)) return
      if (!allowed()) {
        await updatePlaybackReceipt(identity, 'interrupted')
        return
      }
      this.stopSpeechReplay()
      const sequence = this.speechRequestSequence
      const presentation = usePresentationStore()
      const preferences = useDesktopPreferencesStore()
      const surface = usePresentationSurfaceStore()
      let playbackStarted = false
      try {
        await synthesizeSpeech({ text, voiceStyle, intensity, preferences: preferences.value.speech,
          surface: surface.surface, requestId: `desktop-${event.event_id}`,
          onSpeaking: speaking => {
            if (sequence !== this.speechRequestSequence) return
            presentation.setSpeaking(speaking)
            if (speaking) {
              playbackStarted = true
              this.applyPendingMotionCues(event.turn_id, true)
            }
          } })
        await updatePlaybackReceipt(identity, sequence === this.speechRequestSequence ? 'completed' : 'interrupted')
      }
      catch {
        await updatePlaybackReceipt(identity, 'interrupted')
      }
      finally {
        if (sequence === this.speechRequestSequence) presentation.setSpeaking(false)
        if (!playbackStarted && interrupted === usePresentationSurfaceStore().interruptionGeneration)
          this.applyPendingMotionCues(event.turn_id, true)
      }
    },
    async playAutomaticMotion(event: TurnEventEnvelope, motionTag: string) {
      const surface = usePresentationSurfaceStore()
      const interrupted = surface.interruptionGeneration
      const sessionId = this.sessionId
      const preferences = useDesktopPreferencesStore().value
      if (!preferences.companion.motionEnabled || preferences.character.reducedMotion) return
      const normalizedMotion = motionTag.toLowerCase().replace(/[^a-z0-9]/gu, '').slice(0, 80)
      if (!normalizedMotion) return
      const turnId = event.turn_id
      const identity = {
        sessionId,
        turnId,
        effectId: `motion:${normalizedMotion}`,
        kind: 'motion' as const,
      }
      const allowed = () => sessionId === this.sessionId
        && (!event.session_id || event.session_id === this.sessionId)
        && (!turnId || !this.activeTurnId || turnId === this.activeTurnId)
        && interrupted === surface.interruptionGeneration
        && useDesktopPreferencesStore().value.companion.motionEnabled
        && !useDesktopPreferencesStore().value.character.reducedMotion
      if (!await claimPlaybackEffect(identity, Date.now(), allowed)) return
      if (!allowed()) {
        await updatePlaybackReceipt(identity, 'interrupted')
        return
      }
      this.pendingMotionCues.push({ identity, motionTag, interruptionGeneration: interrupted })
      if (turnId && this.completedTurnIds.includes(turnId) && !this.speechRequestedTurnIds.includes(turnId)) {
        this.applyPendingMotionCues(turnId, true)
        return
      }
      if (this.speechRequestedTurnIds.includes(turnId ?? '')) {
        if (usePresentationStore().speaking || speechPlaybackState.speaking)
          this.applyPendingMotionCues(turnId, true)
      }
    },
    applyPendingMotionCues(turnId?: string, force = false) {
      if (!force && turnId && this.speechRequestedTurnIds.includes(turnId)) return
      const surface = usePresentationSurfaceStore()
      const sessionId = this.sessionId
      const preferences = useDesktopPreferencesStore().value
      for (const cue of [...this.pendingMotionCues]) {
        if ((turnId && cue.identity.turnId !== turnId) || cue.identity.sessionId !== sessionId)
          continue
        this.pendingMotionCues = this.pendingMotionCues.filter(item => item !== cue)
        if (cue.interruptionGeneration !== surface.interruptionGeneration
          || !preferences.companion.motionEnabled || preferences.character.reducedMotion) {
          void updatePlaybackReceipt(cue.identity, 'interrupted')
          continue
        }
        usePresentationStore().applyMotionCue(cue.motionTag)
        void updatePlaybackReceipt(cue.identity, 'completed')
      }
    },
    interruptPendingMotionCues(turnId?: string) {
      const interrupted = this.pendingMotionCues.filter(cue => !turnId || cue.identity.turnId === turnId)
      this.pendingMotionCues = this.pendingMotionCues.filter(cue => !interrupted.includes(cue))
      for (const cue of interrupted)
        void updatePlaybackReceipt(cue.identity, 'interrupted')
    },
    stopSpeechReplay() {
      this.speechReplaySequence++
      this.speechRequestSequence++
      this.speechReplayMessageId = ''
      this.speechReplayErrorMessageId = ''
      this.speechReplayError = ''
      cancelSpeech()
    },
    async replaySpeech(messageId: string) {
      if (this.speechReplayMessageId === messageId) {
        this.stopSpeechReplay()
        return
      }
      const message = this.messages.find(candidate => candidate.id === messageId)
      if (this.sending || !message || message.role !== 'meguri' || !message.text.trim()
        || (message.status && message.status !== 'complete')) return
      this.stopSpeechReplay()
      const sequence = this.speechReplaySequence
      this.speechRequestSequence++
      const requestSequence = this.speechRequestSequence
      this.speechReplayMessageId = message.id
      const presentation = usePresentationStore()
      const speech = message.speech?.text?.trim()
        ? message.speech : { text: message.text, voiceStyle: 'neutral', intensity: 'calm' }
      try {
        await synthesizeSpeech({ ...speech, preferences: useDesktopPreferencesStore().value.speech,
          surface: usePresentationSurfaceStore().surface, manual: true,
          requestId: identifier('manual-replay'),
          onSpeaking: speaking => { if (requestSequence === this.speechRequestSequence) presentation.setSpeaking(speaking) } })
        if (sequence === this.speechReplaySequence && speechPlaybackState.error) {
          this.speechReplayErrorMessageId = message.id
          this.speechReplayError = speechPlaybackState.error
        }
      }
      finally {
        if (sequence === this.speechReplaySequence) this.speechReplayMessageId = ''
        if (requestSequence === this.speechRequestSequence) presentation.setSpeaking(false)
      }
    },
    async cancel() {
      usePresentationSurfaceStore().interruptPresentation()
      this.interruptPendingMotionCues(this.activeTurnId)
      this.stopSpeechReplay()
      this.abortController?.abort()
      this.summaryAbortController?.abort()
      if (this.activeTurnId)
        void clientFor(this.sessionId).cancel(this.activeTurnId).catch(() => {})
    },
    retry() {
      if (this.lastUserText)
        return this.send(this.lastUserText, this.lastAttachments)
    },
    newConversation() {
      const previousSessionId = this.sessionId
      const previousTurnId = this.activeTurnId
      // Release the visible busy state before returning so the composer can
      // accept a new turn immediately. Identity checks in old finally blocks
      // prevent them from clearing state owned by the new turn.
      this.summaryAbortController?.abort()
      this.summaryAbortController = undefined
      this.summaryBusy = false
      usePresentationSurfaceStore().interruptPresentation()
      this.interruptPendingMotionCues()
      this.stopSpeechReplay()
      this.abortController?.abort()
      this.abortController = undefined
      this.activeTurnId = undefined
      this.sending = false
      void useCompanionDirectorStore().setRuntimeContext({ activeTurn: false,
        speaking: speechPlaybackState.speaking || speechPlaybackState.busy })
      usePresentationStore().setStatus('准备好开始新对话', 'ready')
      if (previousTurnId)
        void clientFor(previousSessionId).cancel(previousTurnId).catch(() => {})
      this.sessionId = identifier('meguri-desktop')
      this.checkpoint = undefined
      this.messages = []
      this.error = ''
      this.lastUserText = ''
      this.lastAttachments = []
      this.automaticSpeechEventIds = []
      this.speechRequestedTurnIds = []
      this.completedTurnIds = []
      this.successfulTurns = 0
      this.lastSummaryCheckpoint = 0
      this.memoryStatus = undefined
      this.memoryNotice = ''
      this.persist()
    },
    addAiNewsMessage(reportId: string, text: string) {
      return this.addCompanionNoticeMessage(reportId, text, true)
    },
    addCompanionNoticeMessage(id: string, text: string, aiNews = false) {
      if (aiNews && localStorage.getItem('meguri.desktop.ai-news-last.v1') === id)
        return true
      if (this.messages.some(message => message.id === id))
        return true
      this.messages.push({
        id,
        role: 'meguri',
        text,
        createdAt: Date.now(),
        status: 'complete',
      })
      this.persist()
      if (aiNews)
        localStorage.setItem('meguri.desktop.ai-news-last.v1', id)
      return true
    },
    addArtifactMessage(artifact: { name: string, href: string, local_path: string }) {
      if (this.messages.some(message => message.role === 'artifact' && message.localPath === artifact.local_path))
        return
      this.messages.push({
        id: identifier('artifact'),
        role: 'artifact',
        text: artifact.name,
        href: artifact.href,
        localPath: artifact.local_path,
        createdAt: Date.now(),
        status: 'complete',
      })
      this.persist()
    },
  },
})

export function useConversationStore(...args: Parameters<typeof conversationDefinition>) {
  const store = conversationDefinition(...args)
  store.initializePrivacySync()
  return store
}
