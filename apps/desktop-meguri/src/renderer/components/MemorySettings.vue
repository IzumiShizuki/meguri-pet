<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import type { DesktopMemoryCandidate, DesktopMemoryRecord, DesktopMemoryStatus } from '../../shared/memory-contract'
import { remoteChatConsented } from '../../shared/experience-contract'
import { exportMemory, memoryCandidates, memoryReason, memoryRecords, memoryStatus, mutateMemory, reviewMemoryCandidate } from '../services/memory-api'
import { useConversationStore } from '../stores/conversation'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import MemoryDetail from './MemoryDetail.vue'

const preferences = useDesktopPreferencesStore()
const conversation = useConversationStore()
const status = ref<DesktopMemoryStatus>()
const records = ref<DesktopMemoryRecord[]>([])
const candidates = ref<DesktopMemoryCandidate[]>([])
const tab = ref<'active' | 'candidates' | 'deleted'>('active')
const busy = ref(false)
const notice = ref('')
const error = ref('')
const selectedMemoryId = ref<string | null>(null)
const editorEditing = ref(false)
let refreshGeneration = 0
let disposed = false
const ready = computed(() => status.value?.available && status.value.authorized)
const correctionsAvailable = computed(() => ready.value
  && status.value?.capabilities?.includes('memory_detail') && status.value.capabilities.includes('memory_corrections'))
const statusText = computed(() => {
  if (!status.value)
    return '正在检查正式记忆服务…'
  if (ready.value)
    return '正式记忆服务已就绪 · native_pgvector'
  return /^[a-z_]+$/.test(status.value.reason) ? memoryReason(status.value.reason) : status.value.reason
})
const displayedRecords = computed(() => records.value.filter(item => item.status === (tab.value === 'deleted' ? 'deleted' : 'active')))
const pendingStatuses = ['pending', 'pending_review', 'needs_confirmation']
const statusNames: Record<string, string> = { pending: '待处理', pending_review: '待审核', needs_confirmation: '需要确认', approved: '已批准', rejected: '已拒绝' }

async function refresh() {
  const sessionId = conversation.sessionId
  const generation = ++refreshGeneration
  const current = () => !disposed && generation === refreshGeneration && sessionId === conversation.sessionId
  busy.value = true
  error.value = ''
  try {
    const nextStatus = await memoryStatus(sessionId)
    if (!current()) return
    status.value = nextStatus
    conversation.memoryStatus = status.value
    if (ready.value) {
      const [nextRecords, nextCandidates] = await Promise.all([memoryRecords(sessionId), memoryCandidates(sessionId)])
      if (!current()) return
      records.value = nextRecords
      candidates.value = nextCandidates
    }
    else {
      records.value = []
      candidates.value = []
    }
  }
  catch (reason) {
    if (current()) error.value = reason instanceof Error ? reason.message : '记忆列表读取失败。'
  }
  finally {
    if (current()) busy.value = false
  }
}

async function run(action: () => Promise<unknown>, message: string) {
  const sessionId = conversation.sessionId
  const current = () => !disposed && sessionId === conversation.sessionId
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
    if (!current()) return
    notice.value = message
    await refresh()
  }
  catch (reason) {
    if (!current()) return
    const message = reason instanceof Error ? reason.message : '记忆操作失败。'
    await refresh()
    if (current()) error.value = message
  }
  finally {
    if (current()) busy.value = false
  }
}

async function downloadExport() {
  await run(async () => {
    const blob = await exportMemory(conversation.sessionId)
    const href = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = href
    link.download = 'meguri-memory-export.jsonl'
    link.click()
    setTimeout(() => URL.revokeObjectURL(href), 1000)
  }, '正式记忆导出已交给下载窗口。文件包含记忆内容，请自行妥善保存。')
}

async function summarize() {
  await conversation.summarizeCurrentSession()
  await refresh()
}

function closeDetail() {
  const memoryId = selectedMemoryId.value
  selectedMemoryId.value = null
  editorEditing.value = false
  if (memoryId) void nextTick(() => document.getElementById(`memory-detail-toggle-${memoryId}`)?.focus())
}
function reviewCorrections() {
  tab.value = 'candidates'
  void nextTick(() => document.getElementById('memory-candidates-tab')?.focus())
}
watch(tab, closeDetail)
watch(correctionsAvailable, available => { if (!available) closeDetail() })
watch(() => conversation.sessionId, () => {
  closeDetail()
  records.value = []
  candidates.value = []
  status.value = undefined
  notice.value = ''
  void refresh()
})
onMounted(refresh)
onUnmounted(() => { disposed = true; refreshGeneration++ })
</script>

<template>
  <section class="memory-settings" aria-labelledby="memory-title">
    <div class="memory-heading">
      <div><h2 id="memory-title">正式记忆</h2><p>由权威记忆服务保存；模型提出的事实先进入候选，经过审核后再用于长期回忆。</p></div>
      <button type="button" :disabled="busy || editorEditing" @click="refresh">刷新状态</button>
    </div>
    <p class="memory-status" :class="{ ready }" role="status">{{ statusText }}</p>
    <p v-if="!remoteChatConsented(preferences.value.privacy)" class="memory-hint">开启前请在「隐私与数据」接受当前政策并允许 AI 对话。</p>
    <label class="memory-option">
      <span><strong>使用和整理正式记忆</strong><small>允许当前对话回忆已有记忆，并提取候选。关闭后停止进一步使用，已有数据仍可管理。</small></span>
      <input type="checkbox" :disabled="!remoteChatConsented(preferences.value.privacy)" :checked="preferences.value.memory.enabled" @change="preferences.update({ memory: { enabled: ($event.target as HTMLInputElement).checked } })">
    </label>
    <label class="memory-option">
      <span><strong>自动整理当前会话</strong><small>仅整理最近最多 20 条消息，不自动批准候选。</small></span>
      <input type="checkbox" :checked="preferences.value.memory.autoSummarize" @change="preferences.update({ memory: { autoSummarize: ($event.target as HTMLInputElement).checked } })">
    </label>
    <label class="memory-interval"><span>每 {{ preferences.value.memory.summaryEveryTurns }} 轮成功对话整理一次</span>
      <input type="range" min="3" max="30" step="1" :value="preferences.value.memory.summaryEveryTurns" @input="preferences.update({ memory: { summaryEveryTurns: Number(($event.target as HTMLInputElement).value) } })">
    </label>
    <div class="memory-actions">
      <button type="button" :disabled="busy || editorEditing || !ready || !preferences.value.memory.enabled || conversation.sending || conversation.summaryBusy" @click="summarize">{{ conversation.summaryBusy ? '正在整理…' : '整理当前会话' }}</button>
      <button type="button" :disabled="busy || editorEditing || !ready" @click="downloadExport">导出正式记忆</button>
    </div>
    <p v-if="conversation.memoryNotice" role="status">{{ conversation.memoryNotice }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="error" class="memory-error" role="alert">{{ error }}</p>
    <div class="memory-tabs" role="group" aria-label="记忆分类">
      <button type="button" :disabled="editorEditing" :aria-pressed="tab === 'active'" @click="tab = 'active'">正式记忆 {{ records.filter(item => item.status === 'active').length }}</button>
      <button id="memory-candidates-tab" type="button" :disabled="editorEditing" :aria-pressed="tab === 'candidates'" @click="tab = 'candidates'">候选记录 {{ candidates.length }}</button>
      <button type="button" :disabled="editorEditing" :aria-pressed="tab === 'deleted'" @click="tab = 'deleted'">已删除 {{ records.filter(item => item.status === 'deleted').length }}</button>
    </div>
    <ul v-if="tab === 'candidates'" class="memory-list">
      <li v-for="item in candidates" :key="item.candidate_id">
        <p>{{ item.content_text }}</p><small>{{ statusNames[item.status] ?? item.status }} · {{ item.memory_type }}</small>
        <p v-if="item.review_reason" class="memory-hint">审核说明：{{ item.review_reason }}</p>
        <p v-if="item.merge_policy === 'supersede'" class="memory-hint">记忆改正候选 · 确认后会以新版本替代原内容；版本变化时需重新核对。</p>
        <div v-if="pendingStatuses.includes(item.status)" class="memory-actions">
          <button type="button" :disabled="busy || !ready" @click="run(() => reviewMemoryCandidate(conversation.sessionId, item.candidate_id, 'approve'), '已提交确认，结果由记忆审核与冲突规则决定。')">确认记住</button>
          <button type="button" :disabled="busy || !ready" @click="run(() => reviewMemoryCandidate(conversation.sessionId, item.candidate_id, 'reject'), '已拒绝这条候选。')">拒绝</button>
        </div>
      </li>
      <li v-if="!candidates.length" class="memory-empty">{{ ready ? '还没有候选记忆。' : '服务就绪后可以查看候选。' }}</li>
    </ul>
    <ul v-else class="memory-list">
      <li v-for="item in displayedRecords" :key="item.memory_id">
        <p>{{ item.canonical_text }}</p><small>{{ item.memory_type }}</small>
        <div class="memory-actions">
          <button v-if="correctionsAvailable" :id="`memory-detail-toggle-${item.memory_id}`" type="button" :disabled="busy || editorEditing || !ready" :aria-expanded="selectedMemoryId === item.memory_id" :aria-controls="selectedMemoryId === item.memory_id ? `memory-detail-${item.memory_id}` : undefined" @click="selectedMemoryId === item.memory_id ? closeDetail() : selectedMemoryId = item.memory_id">查看来源与改正</button>
          <button v-if="tab === 'deleted'" type="button" :disabled="busy || editorEditing || !ready" @click="run(() => mutateMemory(conversation.sessionId, item.memory_id, 'restore'), '已恢复记忆。')">恢复</button>
          <button v-else type="button" :disabled="busy || editorEditing || !ready" @click="run(() => mutateMemory(conversation.sessionId, item.memory_id, 'delete'), '记忆已移至已删除，可在其中恢复。')">移到已删除</button>
        </div>
        <MemoryDetail v-if="correctionsAvailable && selectedMemoryId === item.memory_id" :key="`${conversation.sessionId}:${item.memory_id}`" :memory-id="item.memory_id" :session-id="conversation.sessionId" :record-updated-at="item.updated_at" @close="closeDetail" @editing="editorEditing = $event" @submitted="refresh" @review="reviewCorrections" />
      </li>
      <li v-if="!displayedRecords.length" class="memory-empty">{{ ready ? (tab === 'deleted' ? '没有已删除记忆。' : '还没有经过审核的正式记忆。') : '服务就绪后可以管理正式记忆。' }}</li>
    </ul>
  </section>
</template>

<style scoped>
.memory-settings { display: grid; gap: 14px; }
.memory-heading, .memory-option { display: flex; align-items: center; justify-content: space-between; gap: 18px; }
h2, p { margin: 0; }
.memory-heading p, small, .memory-hint { color: var(--text-secondary, #69758b); font-size: 13px; line-height: 1.6; }
.memory-heading p { margin-top: 6px; }
.memory-option small { display: block; margin-top: 4px; }
.memory-option input { accent-color: var(--accent-color, #677cd3); }
.memory-interval { display: grid; gap: 10px; font-size: 14px; }
.memory-interval input { width: 100%; accent-color: var(--accent-color, #677cd3); }
.memory-actions, .memory-tabs { display: flex; flex-wrap: wrap; gap: 8px; }
button { border: 1px solid var(--border-color, #cdd6e6); padding: 8px 12px; border-radius: 10px; color: inherit; background: var(--panel-background, rgba(128, 144, 182, .09)); cursor: pointer; }
button:disabled { opacity: .55; cursor: default; }
button:focus-visible { outline: 3px solid var(--accent-color, #677cd3); outline-offset: 2px; }
button[aria-pressed="true"] { border-color: var(--accent-color, #677cd3); background: rgba(100, 124, 210, .16); }
.memory-status { padding: 12px; background: rgba(134, 145, 169, .1); border-radius: 12px; font-size: 13px; line-height: 1.6; }
.memory-status.ready { background: rgba(41, 157, 116, .12); }
.memory-error { color: var(--error-color, #b4474e); font-size: 13px; line-height: 1.6; }
.memory-list { padding: 0; margin: 0; list-style: none; display: grid; gap: 10px; }
.memory-list li { display: grid; gap: 8px; padding: 14px; border: 1px solid var(--border-color, #cdd6e6); border-radius: 12px; }
.memory-list p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.6; }
.memory-empty { font-size: 13px; color: var(--text-secondary, #69758b); }
</style>
