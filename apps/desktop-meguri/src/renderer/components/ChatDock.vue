<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'

import {
  attachmentAccessLabel,
  isReadableAttachment,
  maxReadableAttachmentBytes,
  maxStagedAttachments,
  normalizeReadableAttachment,
  stagedReadableBytes,
  type LocalResourceAttachment,
  type LocalResourceCandidate,
  type LocalResourceResolution,
  resourceMarkerPresent,
  resolveLocalResourceInput,
  searchLocalResources,
  toLocalResourceAttachment,
} from '../services/local-resources'
import type { PixPinCaptureEvent, PixPinCaptureMode } from '../../shared/desktop-contract'
import type { DesktopVoiceState } from '../../shared/desktop-contract'
import { useConversationStore, type ConversationMessage } from '../stores/conversation'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import TodoPanel from './TodoPanel.vue'

const conversation = useConversationStore()
const preferences = useDesktopPreferencesStore()
const { messages, sending, error } = storeToRefs(conversation)
const { value } = storeToRefs(preferences)
const draft = ref('')
const history = ref<HTMLElement>()
const composer = ref<HTMLTextAreaElement>()
const resourceCandidates = ref<LocalResourceCandidate[]>([])
const selectedAttachments = ref<LocalResourceAttachment[]>([])
const activeResourceResolution = ref<LocalResourceResolution>()
const resourceHint = ref('')
const resourceState = ref<'idle' | 'searching' | 'ready' | 'error'>('idle')
const highlightedResource = ref(0)
const dismissedResourceDraft = ref('')
const dropActive = ref(false)
const attachmentBusy = ref(false)
const captureInProgress = ref(false)
const captureId = ref('')
const captureMode = ref<PixPinCaptureMode>()
const captureSessionId = ref('')
const captureHint = ref('')
const todoOpen = ref(false)
const voiceState = ref<'idle' | DesktopVoiceState['state']>('idle')
const voiceHint = ref('')
let resourceSearchTimer: ReturnType<typeof setTimeout> | undefined
let resourceSearchController: AbortController | undefined
let resourceSearchSequence = 0
let dragDepth = 0

const resourcePanelVisible = computed(() => resourceState.value !== 'idle' || selectedAttachments.value.length > 0)
const resourceListOpen = computed(() => resourceCandidates.value.length > 0)
const hasReadableAttachments = computed(() => selectedAttachments.value.some(isReadableAttachment))
const resourceScopeLabel = computed(() => hasReadableAttachments.value ? '发送后读取所选内容' : '只引用元数据，不读取正文')
const screenshotQuestion = '请看看这张截图，告诉我你注意到什么。'
const canSend = computed(() => (draft.value.trim().length > 0 || selectedAttachments.value.length > 0)
  && !sending.value && !attachmentBusy.value)
const captureActive = computed(() => captureInProgress.value)
const screenshotActionsDisabled = computed(() => captureActive.value || attachmentBusy.value)

watch(() => messages.value.map(message => message.text).join('|'), async () => {
  await nextTick()
  if (history.value)
    history.value.scrollTop = history.value.scrollHeight
})

watch(draft, value => scheduleResourceResolution(value))

function invalidateResourceSearch() {
  clearTimeout(resourceSearchTimer)
  resourceSearchTimer = undefined
  resourceSearchController?.abort()
  resourceSearchController = undefined
  resourceSearchSequence += 1
}

function clearResourceDiscovery() {
  invalidateResourceSearch()
  activeResourceResolution.value = undefined
  resourceCandidates.value = []
  highlightedResource.value = 0
  resourceState.value = 'idle'
  resourceHint.value = selectedAttachmentHint()
}

function selectedAttachmentHint(): string {
  const readableCount = selectedAttachments.value.filter(isReadableAttachment).length
  const metadataCount = selectedAttachments.value.length - readableCount
  if (readableCount && metadataCount)
    return `${readableCount} 个附件将在发送后读取，${metadataCount} 个仅引用元数据`
  if (readableCount)
    return `${readableCount} 个附件将在发送后读取；可在发送前移除`
  return metadataCount ? '已引用元数据，未读取文件正文' : ''
}

function showAttachmentError(message: string) {
  resourceState.value = 'error'
  resourceHint.value = message
}

async function stageClipboardImage() {
  const bridge = window.meguriDesktop?.attachments
  if (!bridge) {
    showAttachmentError('截图读取桥接未就绪，请重启 Meguri 后再试')
    return
  }
  attachmentBusy.value = true
  resourceState.value = 'searching'
  resourceHint.value = '正在读取剪贴板截图…'
  try {
    const result = await bridge.clipboardImage()
    const attachment = result.ok ? normalizeReadableAttachment(result.attachment) : null
    if (!attachment) {
      showAttachmentError(result.error || '剪贴板图片未通过安全检查')
      return
    }
    stageAttachment(attachment)
    await nextTick()
    composer.value?.focus()
  }
  catch (reason) {
    showAttachmentError(reason instanceof Error ? reason.message : '无法读取剪贴板图片')
  }
  finally {
    attachmentBusy.value = false
  }
}

async function onPixPinCaptureChanged(event: PixPinCaptureEvent) {
  if (!event.captureId)
    return
  if (!captureId.value && event.state === 'starting') {
    captureId.value = event.captureId
    captureMode.value = event.mode
    captureSessionId.value = conversation.sessionId
    captureInProgress.value = true
  }
  if (event.captureId !== captureId.value)
    return

  if (event.state === 'starting') {
    captureHint.value = '正在准备 PixPin 截图…'
    return
  }
  if (event.state === 'selecting') {
    captureHint.value = event.hint || 'PixPin 正在框选；完成后 Meguri 会接收截图。按 Esc 可取消 PixPin。'
    return
  }

  const mode = captureMode.value ?? event.mode
  const sessionId = captureSessionId.value
  captureId.value = ''
  captureInProgress.value = false
  captureMode.value = undefined
  captureSessionId.value = ''
  if (event.state !== 'received') {
    captureHint.value = event.error || (event.state === 'cancelled' ? '截图已取消。' : 'PixPin 截图未完成。')
    return
  }
  if (sessionId && sessionId !== conversation.sessionId) {
    captureHint.value = '对话已切换，这张截图没有附加到新对话。请重新截图。'
    return
  }
  const attachment = normalizeReadableAttachment(event.attachment)
  if (!attachment) {
    showAttachmentError('PixPin 截图未通过图片附件检查。')
    return
  }
  if (mode !== 'direct_send') {
    if (stageAttachment(attachment)) {
      captureHint.value = '截图已添加到草稿；原有文字和附件均已保留。'
      await nextTick()
      composer.value?.focus()
    }
    else {
      captureHint.value = resourceHint.value || '截图未能添加到当前草稿。'
    }
    return
  }

  if (sending.value) {
    if (stageAttachment(attachment)) {
      if (!draft.value.trim())
        draft.value = screenshotQuestion
      captureHint.value = '当前对话正在回复；截图已放入草稿，回复结束后可发送。'
      await nextTick()
      composer.value?.focus()
    }
    else {
      captureHint.value = resourceHint.value || '截图未能添加到当前草稿。'
    }
    return
  }

  captureHint.value = '正在把截图交给 Meguri…'
  const completed = await conversation.send(screenshotQuestion, [attachment])
  captureHint.value = completed
    ? '截图问题已发送。'
    : (error.value || '发送未完成；可在对话中的失败消息上重试。截图和默认问题已保留。')
}

async function startPixPinCapture(mode: PixPinCaptureMode) {
  if (captureActive.value || (mode === 'direct_send' && sending.value)) {
    captureHint.value = mode === 'direct_send' && sending.value
      ? '当前有回复正在进行，请等回复结束后再直接发送截图。'
      : '已有一次 PixPin 截图正在进行。'
    return
  }
  const bridge = window.meguriDesktop?.screenshots
  if (!bridge) {
    captureHint.value = 'PixPin 截图仅在 Meguri 桌面版可用。'
    return
  }
  captureMode.value = mode
  captureSessionId.value = conversation.sessionId
  captureInProgress.value = true
  captureHint.value = '正在准备 PixPin…'
  let result: Awaited<ReturnType<typeof bridge.start>>
  try {
    result = await bridge.start(mode)
  }
  catch (reason) {
    captureId.value = ''
    captureInProgress.value = false
    captureMode.value = undefined
    captureSessionId.value = ''
    captureHint.value = reason instanceof Error ? reason.message : 'PixPin 截图启动失败。'
    return
  }
  if (!result.ok) {
    captureId.value = ''
    captureInProgress.value = false
    captureMode.value = undefined
    captureSessionId.value = ''
    captureHint.value = result.error || 'PixPin 截图启动失败。'
    return
  }
  if (result.captureId && !captureId.value && (result.state === 'starting' || result.state === 'selecting'))
    captureId.value = result.captureId
}

async function cancelPixPinCapture() {
  const id = captureId.value
  if (!id)
    return
  const result = await window.meguriDesktop?.screenshots.cancel(id)
  if (!result?.ok)
    captureHint.value = result?.error || '无法取消 PixPin 截图。'
}

function stageAttachment(attachment: LocalResourceAttachment): boolean {
  if (selectedAttachments.value.some(item => item.reference_id === attachment.reference_id)) {
    resourceState.value = 'ready'
    resourceHint.value = `${attachment.name} 已经在附件列表中`
    return false
  }
  if (selectedAttachments.value.length >= maxStagedAttachments) {
    showAttachmentError(`一次最多选择 ${maxStagedAttachments} 个附件`)
    return false
  }
  if (isReadableAttachment(attachment)
    && stagedReadableBytes(selectedAttachments.value) + attachment.size_bytes > maxReadableAttachmentBytes) {
    showAttachmentError('可读取附件合计不能超过 10 MB，请移除部分附件后重试')
    return false
  }
  selectedAttachments.value.push(attachment)
  resourceState.value = 'ready'
  resourceHint.value = selectedAttachmentHint()
  return true
}

function scheduleResourceResolution(value: string) {
  invalidateResourceSearch()
  if (value === dismissedResourceDraft.value)
    return
  dismissedResourceDraft.value = ''
  if (!resourceMarkerPresent(value)) {
    clearResourceDiscovery()
    return
  }
  resourceSearchTimer = setTimeout(() => void resolveResourceDraft(value), 180)
}

async function resolveResourceDraft(value: string) {
  invalidateResourceSearch()
  const sequence = resourceSearchSequence
  const controller = new AbortController()
  resourceSearchController = controller
  resourceState.value = 'searching'
  resourceHint.value = '正在识别 @ 本地资源引用…'
  resourceCandidates.value = []
  highlightedResource.value = 0
  try {
    const resolved = await resolveLocalResourceInput(conversation.sessionId, value, controller.signal)
    if (sequence !== resourceSearchSequence || controller.signal.aborted)
      return
    if (resolved.kind !== 'resource') {
      clearResourceDiscovery()
      return
    }
    activeResourceResolution.value = resolved
    const query = resolved.arguments.trim()
    if (!query) {
      resourceState.value = 'ready'
      resourceHint.value = '请在 @ 后输入文件名关键词'
      return
    }
    resourceHint.value = `正在通过 Everything 查找“${query}”…`
    const result = await searchLocalResources(conversation.sessionId, query, controller.signal)
    if (sequence !== resourceSearchSequence || controller.signal.aborted)
      return
    resourceCandidates.value = result.candidates
    highlightedResource.value = 0
    resourceState.value = 'ready'
    resourceHint.value = result.message
      || (result.candidates.length ? '选择一个文件；不会读取正文' : '没有找到匹配资源')
  }
  catch (reason) {
    if (sequence !== resourceSearchSequence || controller.signal.aborted)
      return
    resourceState.value = 'error'
    resourceCandidates.value = []
    resourceHint.value = reason instanceof Error ? reason.message : 'Everything 搜索失败，请稍后重试'
  }
  finally {
    if (sequence === resourceSearchSequence)
      resourceSearchController = undefined
  }
}

function selectResourceCandidate(candidate: LocalResourceCandidate) {
  const attachment = toLocalResourceAttachment(candidate)
  if (!attachment)
    return
  stageAttachment(attachment)
  const baseMessage = activeResourceResolution.value?.message.trim() ?? ''
  dismissedResourceDraft.value = baseMessage || '请参考所选本地资源'
  draft.value = dismissedResourceDraft.value
  clearResourceDiscovery()
  resourceHint.value = '已引用元数据，未读取文件正文；确认后再发送'
  void nextTick(() => composer.value?.focus())
}

function removeResource(referenceId: string) {
  selectedAttachments.value = selectedAttachments.value.filter(item => item.reference_id !== referenceId)
  resourceState.value = selectedAttachments.value.length ? 'ready' : 'idle'
  resourceHint.value = selectedAttachmentHint()
}

function attachmentTitle(attachment: LocalResourceAttachment): string {
  return attachment.type === 'local_file_reference' ? attachment.path : attachment.name
}

function hasDraggedFiles(event: DragEvent): boolean {
  return Array.from(event.dataTransfer?.types ?? []).includes('Files')
}

function onDragEnter(event: DragEvent) {
  if (!hasDraggedFiles(event))
    return
  dragDepth += 1
  dropActive.value = true
}

function onDragOver(event: DragEvent) {
  if (hasDraggedFiles(event) && event.dataTransfer)
    event.dataTransfer.dropEffect = 'copy'
}

function onDragLeave(event: DragEvent) {
  if (!hasDraggedFiles(event))
    return
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0)
    dropActive.value = false
}

async function onDrop(event: DragEvent) {
  dragDepth = 0
  dropActive.value = false
  const files = Array.from(event.dataTransfer?.files ?? [])
  if (!files.length)
    return
  const bridge = window.meguriDesktop?.attachments
  if (!bridge) {
    showAttachmentError('文件读取桥接未就绪，请重启 Meguri 后再试')
    return
  }

  attachmentBusy.value = true
  resourceState.value = 'searching'
  resourceHint.value = `正在安全检查 ${files.length} 个拖入项目…`
  let accepted = 0
  const errors: string[] = []
  try {
    for (const file of files) {
      if (selectedAttachments.value.length >= maxStagedAttachments) {
        errors.push(`一次最多选择 ${maxStagedAttachments} 个附件`)
        break
      }
      const path = bridge.pathForFile(file)
      if (!path) {
        errors.push(`${file.name || '该项目'} 不是可读取的本地文件`)
        continue
      }
      const result = await bridge.inspect(path)
      const attachment = result.ok ? normalizeReadableAttachment(result.attachment) : null
      if (!attachment) {
        errors.push(result.error || `${file.name} 未通过安全检查`)
        continue
      }
      if (stageAttachment(attachment))
        accepted += 1
    }
  }
  catch (reason) {
    errors.push(reason instanceof Error ? reason.message : '读取拖入文件时发生错误')
  }
  finally {
    attachmentBusy.value = false
  }

  if (errors.length) {
    resourceState.value = 'error'
    resourceHint.value = accepted ? `已添加 ${accepted} 个；${errors[0]}` : errors[0]
  }
  else {
    resourceState.value = 'ready'
    resourceHint.value = selectedAttachmentHint()
  }
  void nextTick(() => composer.value?.focus())
}

function pasteContainsImage(event: ClipboardEvent): boolean {
  return Array.from(event.clipboardData?.items ?? [])
    .some(item => item.kind === 'file' && item.type.startsWith('image/'))
}

async function onComposerPaste(event: ClipboardEvent) {
  if (!pasteContainsImage(event))
    return
  event.preventDefault()
  await stageClipboardImage()
}

function dismissResourcePicker() {
  dismissedResourceDraft.value = draft.value
  clearResourceDiscovery()
}

async function send() {
  if (!canSend.value)
    return
  if (activeResourceResolution.value || (resourceMarkerPresent(draft.value) && !draft.value.trimStart().startsWith('@@'))) {
    await resolveResourceDraft(draft.value)
    return
  }

  let message = draft.value.trim() || (hasReadableAttachments.value ? '请查看所选附件' : '请参考所选本地资源')
  if (draft.value.trimStart().startsWith('@@')) {
    try {
      const resolved = await resolveLocalResourceInput(conversation.sessionId, draft.value)
      if (resolved.kind === 'normal')
        message = resolved.message.trim()
    }
    catch {
      // Escaping is a convenience; ordinary text remains sendable if resolution is temporarily unavailable.
    }
  }
  const attachments = selectedAttachments.value.map(attachment => ({ ...attachment }))
  draft.value = ''
  selectedAttachments.value = []
  clearResourceDiscovery()
  const completed = await conversation.send(message, attachments)
  if (!completed) {
    if (!draft.value.trim())
      draft.value = message
    const selectedIds = new Set(selectedAttachments.value.map(attachment => attachment.reference_id))
    selectedAttachments.value.push(...attachments.filter(attachment => !selectedIds.has(attachment.reference_id)))
    resourceState.value = attachments.length ? 'error' : 'idle'
    resourceHint.value = attachments.length ? '发送未完成，附件已保留，可重试' : ''
  }
}

function toggleTodoPanel() {
  todoOpen.value = !todoOpen.value
  if (todoOpen.value && !value.value.chat.expanded)
    void preferences.update({ chat: { expanded: true } })
}

function onVoiceChanged(state: DesktopVoiceState) {
  voiceState.value = state.state
  if (state.state === 'completed' && state.text) {
    draft.value = draft.value.trim() ? `${draft.value.trimEnd()} ${state.text}` : state.text
    voiceHint.value = '已识别到草稿，可修改后发送或打开待办。'
    voiceState.value = 'idle'
    void nextTick(() => composer.value?.focus())
  }
  else if (state.state === 'error') {
    voiceHint.value = state.error || '语音识别失败。'
    voiceState.value = 'idle'
  }
  else if (state.state === 'cancelled') {
    voiceHint.value = '语音输入已取消。'
    voiceState.value = 'idle'
  }
  else if (state.state === 'listening') {
    voiceHint.value = '正在听，请说话…'
  }
}

async function toggleVoice() {
  const bridge = window.meguriDesktop?.voice
  if (!bridge) {
    voiceHint.value = '本机语音识别尚未就绪，请重启 Meguri。'
    return
  }
  if (voiceState.value === 'listening' || voiceState.value === 'recognizing') {
    await bridge.cancel()
    return
  }
  voiceHint.value = '正在启动麦克风…'
  voiceState.value = 'listening'
  const result = await bridge.start()
  if (!result.ok && result.code !== 'cancelled') {
    voiceHint.value = result.error || '语音识别失败。'
    voiceState.value = 'idle'
  }
}

function onComposerKeydown(event: KeyboardEvent) {
  if (event.isComposing)
    return
  if (resourceListOpen.value && event.key === 'ArrowDown') {
    event.preventDefault()
    highlightedResource.value = (highlightedResource.value + 1) % resourceCandidates.value.length
    return
  }
  if (resourceListOpen.value && event.key === 'ArrowUp') {
    event.preventDefault()
    highlightedResource.value = (highlightedResource.value - 1 + resourceCandidates.value.length) % resourceCandidates.value.length
    return
  }
  if (resourceListOpen.value && event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    selectResourceCandidate(resourceCandidates.value[highlightedResource.value])
    return
  }
  if (resourcePanelVisible.value && event.key === 'Escape') {
    event.preventDefault()
    dismissResourcePicker()
    return
  }
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    void send()
  }
}

function roleLabel(message: ConversationMessage) {
  if (message.role === 'user') return '你'
  if (message.role === 'meguri') return 'Meguri'
  if (message.role === 'artifact') return '文件'
  return '系统'
}

async function openArtifact(message: ConversationMessage) {
  if (!message.localPath)
    return
  const result = await window.meguriDesktop?.artifacts.open(message.localPath)
  if (!result?.ok && message.href)
    window.open(message.href, '_blank', 'noopener')
}

onBeforeUnmount(() => {
  invalidateResourceSearch()
  dragDepth = 0
  disposePixPinCapture()
  disposeClipboardImport()
  disposeVoiceChanged()
  if (voiceState.value === 'listening' || voiceState.value === 'recognizing')
    void window.meguriDesktop?.voice?.cancel()
  if (captureId.value)
    void window.meguriDesktop?.screenshots.cancel(captureId.value)
})

const disposePixPinCapture = window.meguriDesktop?.screenshots.onChanged(onPixPinCaptureChanged) ?? (() => {})
const disposeClipboardImport = window.meguriDesktop?.attachments.onClipboardImportRequested(() => {
  void stageClipboardImage()
}) ?? (() => {})
const disposeVoiceChanged = window.meguriDesktop?.voice?.onChanged(onVoiceChanged) ?? (() => {})
void window.meguriDesktop?.screenshots.status().then(status => {
  if (status.state === 'starting' || status.state === 'selecting') {
    captureId.value = status.captureId || ''
    captureInProgress.value = true
    captureMode.value = status.mode
    captureSessionId.value = conversation.sessionId
    captureHint.value = '正在接收 PixPin 截图；按 Esc 可取消 PixPin。'
  }
  else if (status.clipboardImportShortcutAvailable === false) {
    captureHint.value = '剪贴板导入快捷键与其他程序冲突；截图后请用 Ctrl+V 粘贴。'
  }
})
watch(() => conversation.sessionId, (next, previous) => {
  if (captureId.value && next !== previous)
    void cancelPixPinCapture()
})
</script>

<template>
  <section
    class="chat-dock"
    :class="{ expanded: value.chat.expanded, 'is-drop-target': dropActive }"
    aria-label="与 Meguri 对话"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent="onDragOver"
    @dragleave.prevent="onDragLeave"
    @drop.prevent.stop="onDrop"
  >
    <div v-if="dropActive" class="attachment-drop-overlay no-drag" role="status" aria-live="polite">
      <strong>松开后让 Meguri 读取</strong>
      <span>图片、PDF、文本文档与 DOCX</span>
    </div>
    <header class="chat-header drag-region">
      <div class="chat-title no-drag">
        <span class="chat-presence" />
        <div>
          <strong>Meguri</strong>
          <small>{{ sending ? '正在回应' : '陪在你身边' }}</small>
        </div>
      </div>
      <div class="chat-actions no-drag">
        <button type="button" title="Shizuki 待办" aria-label="打开 Shizuki 待办" @click="toggleTodoPanel">☑</button>
        <button type="button" title="开始新对话" aria-label="开始新对话" @click="conversation.newConversation()">＋</button>
        <button
          type="button"
          :title="value.chat.expanded ? '收起记录' : '展开记录'"
          :aria-label="value.chat.expanded ? '收起聊天记录' : '展开聊天记录'"
          @click="preferences.update({ chat: { expanded: !value.chat.expanded } })"
        >{{ value.chat.expanded ? '⌄' : '⌃' }}</button>
      </div>
    </header>

    <TodoPanel v-if="todoOpen" :suggested-title="draft" @close="todoOpen = false" />

    <div ref="history" class="chat-history no-drag" :class="{ empty: messages.length === 0 }">
      <div v-if="messages.length === 0" class="empty-conversation">
        <span>傍晚好。</span>
        <p>想聊点什么，或者让我帮你处理一件事？</p>
      </div>
      <article v-for="message in messages" v-else :key="message.id" class="message" :class="[message.role, message.status]">
        <span class="message-role">{{ roleLabel(message) }}</span>
        <button v-if="message.role === 'artifact'" type="button" class="artifact-history-link" @click="openArtifact(message)">
          <span>↗</span>{{ message.text }}
        </button>
        <p v-else>{{ message.text || (message.status === 'streaming' ? '…' : '') }}</p>
      </article>
    </div>

    <div v-if="error" class="conversation-error no-drag" role="alert">
      <span>{{ error }}</span>
      <button type="button" :disabled="sending" @click="conversation.retry()">重试</button>
    </div>

    <section
      v-if="resourcePanelVisible"
      class="resource-panel no-drag"
      :class="`is-${resourceState}`"
      aria-label="Everything 本地资源选择"
    >
      <header class="resource-heading">
        <strong>本地附件</strong>
        <span class="metadata-only">{{ resourceScopeLabel }}</span>
      </header>
      <p class="resource-hint" :aria-busy="resourceState === 'searching'" aria-live="polite">{{ resourceHint }}</p>
      <div v-if="resourceCandidates.length" id="everything-resource-list" class="resource-list" role="listbox" aria-label="Everything 搜索结果">
        <button
          v-for="(candidate, index) in resourceCandidates"
          :id="`everything-resource-${index}`"
          :key="candidate.id"
          type="button"
          class="resource-candidate"
          :class="{ active: highlightedResource === index }"
          role="option"
          :aria-selected="highlightedResource === index"
          :title="candidate.path"
          @mouseenter="highlightedResource = index"
          @click="selectResourceCandidate(candidate)"
        >
          <span class="resource-candidate-name">{{ candidate.name }}</span>
          <span class="resource-candidate-path">{{ candidate.path }}</span>
        </button>
      </div>
      <div v-if="selectedAttachments.length" class="resource-selected" aria-label="已选择的本地资源">
        <span
          v-for="attachment in selectedAttachments"
          :key="attachment.reference_id"
          class="resource-chip"
          :class="{ readable: isReadableAttachment(attachment) }"
        >
          <span class="resource-chip-copy">
            <span :title="attachmentTitle(attachment)">{{ attachment.name }}</span>
            <small>{{ attachmentAccessLabel(attachment) }}</small>
          </span>
          <button type="button" :aria-label="`移除 ${attachment.name}`" title="移除附件" @click="removeResource(attachment.reference_id)">×</button>
        </span>
      </div>
    </section>

    <div class="screenshot-actions no-drag" aria-label="PixPin 截图">
      <button type="button" class="screenshot-action" :disabled="screenshotActionsDisabled" @click="startPixPinCapture('draft')">
        截图问 Meguri
      </button>
      <button type="button" class="screenshot-action direct" :disabled="screenshotActionsDisabled || sending" @click="startPixPinCapture('direct_send')">
        截图直接发送
      </button>
      <button v-if="captureActive" type="button" class="screenshot-cancel" :disabled="!captureId" @click="cancelPixPinCapture">取消</button>
      <span class="screenshot-hint" role="status" aria-live="polite">
        {{ voiceHint || captureHint || 'PixPin 截图后也可按 Ctrl+Alt+Shift+P 导入剪贴板' }}
      </span>
    </div>

    <form class="composer no-drag" :aria-busy="attachmentBusy" @submit.prevent="send">
      <textarea
        ref="composer"
        v-model="draft"
        rows="1"
        maxlength="8000"
        placeholder="输入消息；@文件名 仅引用，或拖入文件 / 粘贴截图…"
        aria-label="消息"
        aria-autocomplete="list"
        :aria-expanded="resourceListOpen"
        :aria-controls="resourceListOpen ? 'everything-resource-list' : undefined"
        :aria-activedescendant="resourceListOpen ? `everything-resource-${highlightedResource}` : undefined"
        @keydown="onComposerKeydown"
        @paste="onComposerPaste"
      />
      <button type="button" class="voice-button" :class="{ listening: voiceState === 'listening' }" :aria-label="voiceState === 'listening' ? '取消语音输入' : '开始语音输入'" :title="voiceState === 'listening' ? '取消语音输入' : '语音输入'" @click="toggleVoice">{{ voiceState === 'listening' ? '■' : '🎙' }}</button>
      <button v-if="sending" type="button" class="send-button cancel" aria-label="取消回复" @click="conversation.cancel()">■</button>
      <button v-else type="submit" class="send-button" :disabled="!canSend" aria-label="发送消息">↑</button>
    </form>
  </section>
</template>
