import { createPinia, setActivePinia } from 'pinia'
import type { TurnEventEnvelope } from '@meguri/protocol'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const { createTurnMock, helloMock, subscribeMock } = vi.hoisted(() => ({
  createTurnMock: vi.fn(),
  helloMock: vi.fn(),
  subscribeMock: vi.fn(),
}))

vi.mock('@meguri/client-sdk', () => ({
  MeguriApiClient: class {
    hello = helloMock
    createTurn = createTurnMock
    subscribe = subscribeMock
    cancel = vi.fn()
  },
}))

import { friendlyConversationError, restoreConversationMessages, type ConversationMessage, useConversationStore } from './conversation'
import type { LocalResourceAttachment } from '../services/local-resources'

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
  beforeEach(() => {
    storage.clear()
    setActivePinia(createPinia())
    helloMock.mockReset().mockResolvedValue({ selected_protocol_version: '1.1' })
    createTurnMock.mockReset().mockResolvedValue({ turn_id: 'turn-1' })
    subscribeMock.mockReset().mockResolvedValue(undefined)
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

  it('turns the missing vision-provider failure into an actionable desktop message', () => {
    expect(friendlyConversationError(
      'The selected model is not configured for image or PDF attachments and MEGURI_LLM_MULTIMODAL_FALLBACK_MODEL is not configured.',
    )).toBe('截图或 PDF 已收到，但当前没有配置视觉模型；请配置独立的视觉模型后再试。')
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
    const playSpeech = vi.spyOn(conversation, 'playSpeech').mockResolvedValue()
    const assistant: ConversationMessage = {
      id: 'reply-1',
      role: 'meguri',
      text: '',
      createdAt: 1,
      status: 'streaming',
    }
    const event = {
      event_id: 'speech-once-1',
      replay_policy: 'ONCE',
      type: 'tts.requested',
      data: { text: '这一段只播放一次。', voice_style: 'neutral' },
    } as unknown as TurnEventEnvelope

    await conversation.acceptEvent(event, assistant)
    await conversation.acceptEvent(event, assistant)

    expect(playSpeech).toHaveBeenCalledTimes(1)
  })
})
