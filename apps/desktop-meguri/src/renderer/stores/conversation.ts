import { MeguriApiClient } from '@meguri/client-sdk'
import { SessionTurnReducer, type SessionEventCheckpoint, type TurnEventEnvelope } from '@meguri/protocol'
import { defineStore } from 'pinia'

import { coreUrl, desktopFetch } from '../services/desktop-fetch'
import { isReadableAttachment, type LocalResourceAttachment } from '../services/local-resources'
import { usePresentationStore } from './presentation'
import { useDesktopPreferencesStore } from './desktop-preferences'
import { usePresentationSurfaceStore } from './presentation-surface'

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
}

interface PersistedConversation {
  userId: string
  installId: string
  sessionId: string
  checkpoint?: SessionEventCheckpoint
  messages: ConversationMessage[]
}

const storageKey = 'meguri.desktop.conversation.v1'

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
  return reason
}

function readPersisted(): PersistedConversation {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) ?? '{}') as Partial<PersistedConversation>
    return {
      userId: typeof value.userId === 'string' ? value.userId : 'local-airi-user',
      installId: typeof value.installId === 'string' ? value.installId : identifier('meguri-install'),
      sessionId: typeof value.sessionId === 'string' ? value.sessionId : identifier('meguri-desktop'),
      checkpoint: value.checkpoint,
      messages: Array.isArray(value.messages) ? restoreConversationMessages(value.messages) : [],
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

export const useConversationStore = defineStore('conversation', {
  state: () => ({
    ...readPersisted(),
    activeTurnId: undefined as string | undefined,
    sending: false,
    error: '',
    lastUserText: '',
    lastAttachments: [] as LocalResourceAttachment[],
    abortController: undefined as AbortController | undefined,
    activeAudio: undefined as HTMLAudioElement | undefined,
  }),
  getters: {
    hasMessages: state => state.messages.length > 0,
  },
  actions: {
    persist() {
      localStorage.setItem(storageKey, JSON.stringify({
        userId: this.userId,
        installId: this.installId,
        sessionId: this.sessionId,
        checkpoint: this.checkpoint,
        messages: this.messages.slice(-100),
      }))
    },
    async send(text: string, attachments: LocalResourceAttachment[] = []): Promise<boolean> {
      const message = text.trim()
      if (!message || this.sending)
        return false
      const selectedAttachments = attachments.map(attachment => ({ ...attachment }))
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
      const assistantMessage: ConversationMessage = {
        id: identifier('message'),
        role: 'meguri',
        text: '',
        createdAt: Date.now(),
        status: 'streaming',
      }
      this.messages.push(userMessage, assistantMessage)
      this.persist()

      const client = clientFor(this.sessionId)
      const reducer = new SessionTurnReducer(this.checkpoint ?? 0)
      const controller = new AbortController()
      this.abortController = controller
      let completed = false
      try {
        const hello = await client.hello({
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
            formal_memory: false,
            sse: true,
          },
          permissions: {
            screen_read: false,
            microphone: false,
            audio_playback: true,
            notifications: false,
            formal_memory_write: false,
          },
        })
        const created = await client.createTurn({
          protocol_version: hello.selected_protocol_version,
          identity: {
            meguri_user: { id: this.userId },
            platform_actor: { platform: 'desktop', actor_id: 'local-user' },
            client_instance: { id: this.installId, profile: 'desktop_pet' },
            session: { id: this.sessionId },
          },
          message: outgoingMessage,
          ...(selectedAttachments.length ? { attachments: selectedAttachments } : {}),
        }, identifier('desktop-turn'))
        this.activeTurnId = created.turn_id
        assistantMessage.turnId = created.turn_id
        await client.subscribe(this.sessionId, reducer, {
          signal: controller.signal,
          untilTurnId: created.turn_id,
          onEvent: async event => {
            // The reducer has accepted this sequence before the callback. Persist
            // it before any ONCE audio side effect so reconnect cannot replay TTS.
            this.checkpoint = reducer.checkpoint()
            this.persist()
            await this.acceptEvent(event, assistantMessage)
          },
          onSnapshot: snapshot => {
            const turn = snapshot.turns.find(candidate => candidate.turn_id === created.turn_id)
            if (turn) {
              assistantMessage.text = turn.text
              assistantMessage.status = turn.status === 'failed' ? 'failed' : 'complete'
              if (turn.expression)
                presentation.applyCue(turn.expression)
            }
          },
        })
        this.checkpoint = reducer.checkpoint()
        assistantMessage.status = 'complete'
        presentation.setStatus('Meguri 已回应', 'ready')
        completed = true
      }
      catch (error) {
        if (controller.signal.aborted) {
          assistantMessage.status = 'complete'
          if (!assistantMessage.text)
            assistantMessage.text = '这次回复已取消。'
          presentation.setStatus('已取消回复', 'ready')
        }
        else {
          const reason = friendlyConversationError(error instanceof Error ? error.message : String(error))
          this.error = reason
          assistantMessage.status = 'failed'
          if (!assistantMessage.text)
            assistantMessage.text = '这次没有连接上 Meguri。'
          presentation.setStatus('对话连接失败', 'error')
        }
      }
      finally {
        this.activeTurnId = undefined
        this.abortController = undefined
        this.sending = false
        presentation.setSpeaking(false)
        this.persist()
      }
      return completed
    },
    async acceptEvent(event: TurnEventEnvelope, assistantMessage: ConversationMessage) {
      const data = event.data
      const presentation = usePresentationStore()
      const surface = usePresentationSurfaceStore()
      if (event.replay_policy === 'ONCE' && !surface.consumeOneShot(event.event_id)) {
        this.persist()
        return
      }
      if (event.type === 'text.delta' && typeof data.delta === 'string')
        assistantMessage.text += data.delta
      else if (event.type === 'text.completed' && typeof data.text === 'string')
        assistantMessage.text = data.text
      else if (['semantic.completed', 'expression.cue', 'sprite.resolved'].includes(event.type))
        presentation.applyCue(data)
      else if (event.type === 'tool.started')
        presentation.setStatus('正在调用能力…', 'busy')
      else if (event.type === 'tts.requested' && typeof data.text === 'string' && data.text.trim())
        await this.playSpeech(data.text, typeof data.voice_style === 'string' ? data.voice_style : 'neutral', presentation.character.intensity)
      else if (event.type === 'turn.failed') {
        assistantMessage.status = 'failed'
        this.error = friendlyConversationError(typeof data.error === 'string' ? data.error : 'Turn failed')
      }
      this.persist()
    },
    async playSpeech(text: string, voiceStyle: string, intensity: string) {
      const presentation = usePresentationStore()
      const preferences = useDesktopPreferencesStore()
      const surface = usePresentationSurfaceStore()
      if (!preferences.value.speech.ttsEnabled)
        return
      const generation = surface.interruptionGeneration
      let audio: HTMLAudioElement | undefined
      try {
        presentation.setSpeaking(true)
        const response = await fetch('http://127.0.0.1:9880/tts/synthesize', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            text,
            voice_style: voiceStyle,
            expression_intensity: intensity,
          }),
        })
        if (!response.ok || generation !== surface.interruptionGeneration)
          return
        const nextAudio = new Audio(URL.createObjectURL(await response.blob()))
        audio = nextAudio
        nextAudio.volume = preferences.value.speech.volume
        if (generation !== surface.interruptionGeneration)
          return
        this.activeAudio?.pause()
        this.activeAudio = nextAudio
        await nextAudio.play()
        await new Promise<void>(resolve => {
          nextAudio.addEventListener('ended', () => resolve(), { once: true })
          nextAudio.addEventListener('error', () => resolve(), { once: true })
          nextAudio.addEventListener('pause', () => resolve(), { once: true })
        })
      }
      catch {
        // Speech is optional; text completion remains authoritative.
      }
      finally {
        if (audio) {
          if (this.activeAudio === audio)
            this.activeAudio = undefined
          URL.revokeObjectURL(audio.src)
        }
        presentation.setSpeaking(false)
      }
    },
    async cancel() {
      usePresentationSurfaceStore().interruptPresentation()
      if (this.activeAudio) {
        const audio = this.activeAudio
        this.activeAudio = undefined
        audio.pause()
        URL.revokeObjectURL(audio.src)
      }
      if (this.activeTurnId) {
        try {
          await clientFor(this.sessionId).cancel(this.activeTurnId)
        }
        catch {
          // Aborting the local stream still restores the UI even if Core already completed.
        }
      }
      this.abortController?.abort()
    },
    retry() {
      if (this.lastUserText)
        return this.send(this.lastUserText, this.lastAttachments)
    },
    newConversation() {
      usePresentationSurfaceStore().interruptPresentation()
      if (this.activeAudio) {
        const audio = this.activeAudio
        this.activeAudio = undefined
        audio.pause()
        URL.revokeObjectURL(audio.src)
      }
      this.abortController?.abort()
      this.sessionId = identifier('meguri-desktop')
      this.checkpoint = undefined
      this.messages = []
      this.error = ''
      this.lastUserText = ''
      this.lastAttachments = []
      this.persist()
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
