<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { correctMemory, memoryDetail } from '../services/memory-api'
import { createMemoryCorrectionEditor } from '../services/memory-correction-editor'

const props = defineProps<{ memoryId: string, sessionId: string, recordUpdatedAt: string }>()
const emit = defineEmits<{ submitted: [], review: [], close: [], editing: [value: boolean] }>()
const editor = createMemoryCorrectionEditor({
  load: signal => memoryDetail(props.sessionId, props.memoryId, signal),
  submit: (body, requestId, signal) => correctMemory(props.sessionId, props.memoryId, body, requestId, signal),
})
const { state, canSubmit } = editor
const textArea = ref<HTMLTextAreaElement>()
const beginButton = ref<HTMLButtonElement>()
const candidateButton = ref<HTMLButtonElement>()
const sourceNames: Record<string, string> = { desktop_pet: '桌宠', website: '网页', astrbot: '聊天平台', direct_user: '本人提供', llm_candidate: '对话整理', admin: '管理员录入' }
function label(value: string | null | undefined) { return value ? (sourceNames[value] ?? value) : '未记录' }
function date(value: string) {
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '时间未记录' : parsed.toLocaleString('zh-CN')
}
async function begin() {
  editor.begin()
  emit('editing', state.editing)
  await nextTick()
  textArea.value?.focus()
}
async function cancel() {
  editor.cancel()
  emit('editing', state.editing)
  await nextTick()
  beginButton.value?.focus()
}
async function save() {
  const candidate = await editor.save()
  if (candidate) {
    emit('editing', false)
    emit('submitted')
    await nextTick()
    candidateButton.value?.focus()
  }
}
onMounted(editor.reload)
watch(() => props.recordUpdatedAt, () => {
  // List refreshes can update a stale display, but never erase an edit or its receipt.
  if (!state.editing && !state.submitting && !state.submitted) void editor.reload()
})
onUnmounted(editor.dispose)
</script>

<template>
  <section :id="`memory-detail-${memoryId}`" class="memory-detail" aria-label="记忆来源与改正" :aria-busy="state.loading || state.submitting">
    <div class="detail-heading">
      <strong>来源与版本</strong>
      <button type="button" :disabled="state.submitting" @click="emit('close')">收起</button>
    </div>
    <p v-if="state.loading" role="status">正在读取当前记忆…</p>
    <template v-if="state.detail">
      <dl>
        <div><dt>当前版本</dt><dd>{{ state.detail.version_no ? `第 ${state.detail.version_no} 版` : '未记录' }}</dd></div>
        <div><dt>更新时间</dt><dd>{{ date(state.detail.updated_at) }}</dd></div>
        <div><dt>来源</dt><dd>{{ label(state.detail.source.client_id) }} · {{ label(state.detail.source.kind) }}</dd></div>
        <div><dt>来源会话</dt><dd>{{ label(state.detail.source.session_id) }}</dd></div>
      </dl>
      <p class="current-text"><strong>当前内容</strong>{{ state.detail.content_text || '当前版本正文不可用。' }}</p>
      <p v-if="state.detail.change_reason" class="detail-hint">变更说明：{{ state.detail.change_reason }}</p>
      <button v-if="!state.editing && !state.submitted && state.detail.can_correct" ref="beginButton" type="button" :disabled="state.loading" @click="begin">改正这条记忆</button>
      <p v-if="!state.detail.can_correct" class="detail-hint">这条记忆当前不能改正。已删除记忆可先恢复，再读取当前版本。</p>
      <form v-if="state.editing" @submit.prevent="save">
        <p class="detail-hint">改正内容会先进入候选。只有在候选记录中确认通过，正式记忆才会更新。</p>
        <label :for="`correction-text-${memoryId}`">改正后的内容</label>
        <textarea :id="`correction-text-${memoryId}`" ref="textArea" v-model="state.contentText" rows="4" maxlength="8000" required :disabled="state.submitting" :aria-describedby="`correction-text-count-${memoryId}`" :aria-invalid="[...state.contentText.trim()].length > 4000" />
        <small :id="`correction-text-count-${memoryId}`" class="detail-hint">{{ [...state.contentText.trim()].length }} / 4000 字</small>
        <label :for="`correction-reason-${memoryId}`">改正原因</label>
        <textarea :id="`correction-reason-${memoryId}`" v-model="state.changeReason" rows="2" maxlength="2000" required :disabled="state.submitting" :aria-describedby="`correction-reason-count-${memoryId}`" :aria-invalid="[...state.changeReason.trim()].length > 1000" placeholder="例如：之前记错了，或我的偏好已经改变。" />
        <small :id="`correction-reason-count-${memoryId}`" class="detail-hint">{{ [...state.changeReason.trim()].length }} / 1000 字</small>
        <p class="detail-hint">请不要填写密码或密钥。</p>
        <div class="detail-actions">
          <button type="submit" :disabled="!canSubmit">{{ state.submitting ? '正在提交…' : '提交为待审核候选' }}</button>
          <button type="button" :disabled="state.submitting" @click="cancel">取消改正</button>
        </div>
      </form>
    </template>
    <p v-if="state.submitted" role="status">{{ state.submitted.status === 'rejected' ? '改正内容未通过记忆规则，请在候选记录查看原因。' : '改正候选已保存，请到候选记录确认；正式记忆尚未更新。' }}</p>
    <button v-if="state.submitted" ref="candidateButton" type="button" @click="emit('review')">查看候选记录</button>
    <p v-if="state.error" class="detail-error" role="alert">{{ state.error }}</p>
    <p v-if="state.error && state.editing" class="detail-hint">已输入的改正内容仍保留。重新读取当前版本后，请核对再提交。</p>
    <button v-if="state.error || state.editing" type="button" :disabled="state.loading || state.submitting" @click="editor.reload">重新读取当前版本</button>
  </section>
</template>

<style scoped>
.memory-detail { --memory-hint: var(--dark-text-secondary, #626e84); --memory-panel: var(--dark-input, rgba(128, 144, 182, .09)); --memory-border: var(--dark-border, #cdd6e6); --memory-error: #b4474e; --memory-focus: var(--dark-amber, #677cd3); display: grid; gap: 12px; margin-top: 4px; padding-top: 14px; border-top: 1px solid var(--memory-border); min-width: 0; }
:global(.dark) .memory-detail { --memory-error: var(--danger, #ff9ca9); }
.detail-heading, .detail-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.detail-heading { justify-content: space-between; }
p, dl, dd { margin: 0; }
dl { display: grid; gap: 8px; font-size: 13px; line-height: 1.6; }
dl > div { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 10px; }
dt, .detail-hint { color: var(--memory-hint); }
dd, p { overflow-wrap: anywhere; white-space: pre-wrap; }
.current-text { display: grid; gap: 6px; line-height: 1.6; }
.detail-hint, .detail-error { font-size: 13px; line-height: 1.6; }
.detail-error { color: var(--memory-error); }
form { display: grid; gap: 8px; }
label { font-size: 14px; font-weight: 600; margin-top: 4px; }
textarea { width: 100%; min-width: 0; box-sizing: border-box; resize: vertical; font: inherit; line-height: 1.6; color: inherit; background: var(--memory-panel); border: 1px solid var(--dark-placeholder, #7d899d); border-radius: 10px; padding: 10px; }
textarea::placeholder { color: var(--memory-hint); }
button { border: 1px solid var(--memory-border); padding: 8px 12px; border-radius: 10px; color: inherit; background: var(--memory-panel); cursor: pointer; font: inherit; }
button:hover:enabled { border-color: var(--memory-focus); }
button:disabled, textarea:disabled { opacity: .55; cursor: default; }
button:focus-visible, textarea:focus-visible { outline: 3px solid var(--memory-focus); outline-offset: 2px; }
</style>
