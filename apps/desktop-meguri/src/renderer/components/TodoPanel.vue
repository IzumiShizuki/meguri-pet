<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import type { ShizukiConnectionStatus, ShizukiTodo } from '../../shared/desktop-contract'
import { REMINDER_ZONE_LABEL } from '../../shared/reminder-intent.mjs'
import TodoCalendar from './TodoCalendar.vue'

const emit = defineEmits<{ close: [] }>()
const props = defineProps<{ suggestedTitle?: string }>()
const bridge = window.meguriDesktop?.shizuki
const status = ref<ShizukiConnectionStatus>()
const todos = ref<ShizukiTodo[]>([])
const title = ref((props.suggestedTitle || '').trim().slice(0, 200))
const dueAt = ref('')
const busy = ref(false)
const loading = ref(false)
const error = ref('')
const feedback = ref('')
const creationInput = ref<{ title: string, dueAt?: string, requestId: string, expectedAccount: string }>()
const selectedTodo = ref<ShizukiTodo>()
const mutation = ref<'edit' | 'cancel'>()
const selectedAccount = ref('')
const view = ref<'list' | 'calendar'>('list')
let refreshSequence = 0
let refreshTimer: ReturnType<typeof setInterval>
let disposed = false

function accountKey(value?: ShizukiConnectionStatus) {
  return value?.connected ? `${value.origin}:${value.userId}` : ''
}

const pending = computed(() => todos.value.filter(todo => !todo.done)
  .sort((left, right) => {
    if (!left.dueAt) return 1
    if (!right.dueAt) return -1
    return left.dueAt.localeCompare(right.dueAt)
  }))
const completedCount = computed(() => todos.value.filter(todo => todo.done).length)

function showTime(value: string) {
  return value ? value.replace('T', ' ').slice(0, 16) : ''
}

function openSettings() {
  void window.meguriDesktop?.window.openSettings()
}

async function refresh() {
  if (busy.value || disposed) return
  if (!bridge) {
    error.value = '桌面待办桥接尚未就绪，请重启 Meguri。'
    return
  }
  loading.value = true
  const sequence = ++refreshSequence
  error.value = ''
  try {
    const nextStatus = await bridge.status()
    if (disposed || sequence !== refreshSequence) return
    if (accountKey(status.value) !== accountKey(nextStatus)) {
      todos.value = []
      cancelReview()
    }
    status.value = nextStatus
    if (!nextStatus.connected) {
      todos.value = []
      return
    }
    const result = await bridge.listTodos()
    const confirmedStatus = await bridge.status()
    if (disposed || sequence !== refreshSequence) return
    if (accountKey(nextStatus) !== accountKey(confirmedStatus)) {
      todos.value = []
      status.value = confirmedStatus
      cancelReview()
      return
    }
    if (!result.ok) {
      error.value = result.error || '无法读取 Shizuki 待办。'
      return
    }
    todos.value = result.todos || []
  }
  catch {
    if (disposed || sequence !== refreshSequence) return
    error.value = '无法读取 Shizuki 待办，请检查网络。'
  }
  finally {
    if (sequence === refreshSequence) loading.value = false
  }
}

async function create() {
  if (!bridge || busy.value) return
  const finalTitle = title.value.trim()
  if (!finalTitle) {
    error.value = '先写下待办标题。'
    return
  }
  busy.value = true
  refreshSequence += 1
  loading.value = false
  error.value = ''
  feedback.value = ''
  try {
    if (!status.value?.connected) { error.value = '请先连接 Shizuki。'; return }
    const expectedAccount = selectedTodo.value ? selectedAccount.value : `${status.value.origin}:${status.value.userId}`
    if (!selectedTodo.value && !creationInput.value) {
      const requests = await bridge.pendingCreates()
      if (!requests.ok) { error.value = requests.error || '无法核对创建记录。'; return }
      const original = requests.requests?.find(item => item.title === finalTitle && item.dueAt === dueAt.value)
      creationInput.value = { title: finalTitle, dueAt: dueAt.value || undefined, requestId: original?.requestId || crypto.randomUUID(), expectedAccount }
    }
    const result = selectedTodo.value
      ? await bridge.updateTodo(selectedTodo.value.todoId, { title: finalTitle, dueAt: dueAt.value, cancel: mutation.value === 'cancel', expectedAccount })
      : await bridge.createTodo(creationInput.value!)
    const currentStatus = await bridge.status()
    if (disposed) return
    if (accountKey(currentStatus) !== expectedAccount) {
      todos.value = []
      status.value = currentStatus
      error.value = 'Shizuki 账号已改变，请回到原账号核对写入结果。'
      return
    }
    if (!result.ok || !result.todo) {
      if (['request_rejected', 'invalid_due_at', 'invalid_title', 'not_connected', 'account_changed'].includes(result.code || '')) creationInput.value = undefined
      error.value = result.error || 'Shizuki 尚未确认待办已创建。'
      return
    }
    refreshSequence += 1
    loading.value = false
    todos.value = [result.todo, ...todos.value.filter(todo => todo.todoId !== result.todo?.todoId)]
    feedback.value = selectedTodo.value
      ? (mutation.value === 'cancel' ? `已取消提醒：${result.todo.title}` : `服务器已确认修改：${result.todo.title}，${showTime(result.todo.dueAt)}`)
      : dueAt.value
      ? `已存入 Shizuki，提醒时间：${showTime(result.todo.dueAt)}`
      : '待办已存入 Shizuki。'
    title.value = ''
    dueAt.value = ''
    creationInput.value = undefined
    selectedTodo.value = undefined
    mutation.value = undefined
    selectedAccount.value = ''
  }
  catch {
    error.value = '写入 Shizuki 失败，请检查网络后重试。'
  }
  finally {
    busy.value = false
  }
}

function selectMutation(todo: ShizukiTodo, kind: 'edit' | 'cancel') {
  if (busy.value || loading.value || creationInput.value || !status.value?.connected) return
  selectedTodo.value = todo
  mutation.value = kind
  selectedAccount.value = `${status.value.origin}:${status.value.userId}`
  title.value = todo.title
  dueAt.value = todo.dueAt.slice(0, 16)
  feedback.value = ''
  error.value = ''
}

function cancelReview() {
  if (busy.value) return
  selectedTodo.value = undefined
  mutation.value = undefined
  selectedAccount.value = ''
  title.value = ''
  dueAt.value = ''
}

async function complete(todo: ShizukiTodo) {
  if (!bridge || busy.value || loading.value || !status.value?.connected) return
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const expectedAccount = accountKey(status.value)
    const result = await bridge.completeTodo(todo.todoId, expectedAccount)
    const currentStatus = await bridge.status()
    if (disposed) return
    if (accountKey(currentStatus) !== expectedAccount) {
      todos.value = []
      status.value = currentStatus
      error.value = 'Shizuki 账号已改变，请刷新后查看当前账号待办。'
      return
    }
    if (!result.ok || !result.todo) {
      error.value = result.error || 'Shizuki 尚未确认待办已完成。'
      return
    }
    refreshSequence += 1
    loading.value = false
    todos.value = todos.value.map(item => item.todoId === todo.todoId ? result.todo! : item)
    feedback.value = `已完成：${result.todo.title}`
  }
  catch {
    error.value = '更新 Shizuki 待办失败，请稍后重试。'
  }
  finally {
    busy.value = false
  }
}

function refreshOnFocus() { void refresh() }
function refreshWhenVisible() { if (document.visibilityState === 'visible' && !loading.value) void refresh() }
onMounted(() => {
  window.addEventListener('focus', refreshOnFocus)
  document.addEventListener('visibilitychange', refreshWhenVisible)
  refreshTimer = setInterval(refreshWhenVisible, 60000)
  void refresh()
})
onBeforeUnmount(() => {
  disposed = true
  refreshSequence += 1
  clearInterval(refreshTimer)
  window.removeEventListener('focus', refreshOnFocus)
  document.removeEventListener('visibilitychange', refreshWhenVisible)
})
</script>

<template>
  <section class="shizuki-todo-panel no-drag" aria-label="Shizuki 待办">
    <header>
      <div><strong>Shizuki 待办</strong><small>与 TimePrism 同步</small></div>
      <div class="shizuki-todo-head-actions">
        <button type="button" :disabled="loading || busy" @click="refresh">刷新</button>
        <button type="button" aria-label="关闭待办" @click="emit('close')">×</button>
      </div>
    </header>

    <div v-if="!status?.connected" class="shizuki-todo-connect">
      <p>先在设置中连接你的 Shizuki 账号，待办会写入同一个 TimePrism 账户。</p>
      <button type="button" @click="openSettings">打开设置</button>
    </div>
    <template v-else>
      <div class="todo-view-switch" aria-label="待办视图">
        <button type="button" :aria-pressed="view === 'list'" @click="view = 'list'">待办列表</button>
        <button type="button" :aria-pressed="view === 'calendar'" @click="view = 'calendar'">日历</button>
      </div>
      <TodoCalendar v-show="view === 'calendar'" :key="accountKey(status)" :todos="todos" />
      <div v-show="view === 'list'" class="todo-list-view">
      <form class="shizuki-todo-form" @submit.prevent="create">
        <label>待办内容<input v-model="title" maxlength="200" placeholder="例如：睡觉" :disabled="busy || !!creationInput || mutation === 'cancel'" /></label>
        <label>到点提醒 <small>{{ REMINDER_ZONE_LABEL }}</small><input v-model="dueAt" type="datetime-local" step="60" :disabled="busy || !!creationInput || mutation === 'cancel'" /></label>
        <p class="shizuki-todo-preview">{{ selectedTodo ? `待办 #${selectedTodo.todoId} · ` : '' }}{{ mutation === 'cancel' ? '取消提醒（保留待办）：' : dueAt ? `提醒 · ${showTime(dueAt)} · ${REMINDER_ZONE_LABEL}：` : '创建无提醒待办：' }}{{ title.trim() || '待办内容' }}</p>
        <p v-if="creationInput" class="shizuki-todo-preview">保留原请求，重试使用相同编号；结果未确认时不要另建同一待办。</p>
        <button type="submit" :disabled="busy || !title.trim()">{{ busy ? '正在核对服务器…' : mutation === 'cancel' ? '确认取消提醒' : mutation === 'edit' ? '确认修改提醒' : creationInput ? '重试 / 核对原请求' : '确认存入 Shizuki' }}</button>
        <button v-if="selectedTodo" type="button" :disabled="busy" @click="cancelReview">放弃本次修改</button>
      </form>
      <p v-if="feedback" class="shizuki-todo-feedback" role="status">{{ feedback }}</p>
      <div class="shizuki-todo-list" :aria-busy="loading">
        <p v-if="loading">正在读取待办…</p>
        <p v-else-if="pending.length === 0">没有未完成的待办。</p>
        <div v-for="todo in pending" :key="todo.todoId" class="shizuki-todo-item">
          <button type="button" :disabled="busy || loading" :aria-label="`完成 ${todo.title}`" @click="complete(todo)">○</button>
          <span><strong>{{ todo.title }}</strong><small v-if="todo.dueAt">{{ showTime(todo.dueAt) }}{{ todo.reminderEnabled ? ' · 已设提醒' : '' }}</small></span>
          <button type="button" :disabled="busy || loading || !!creationInput" @click="selectMutation(todo, 'edit')">修改</button>
          <button v-if="todo.reminderEnabled" type="button" :disabled="busy || loading || !!creationInput" @click="selectMutation(todo, 'cancel')">取消提醒</button>
        </div>
      </div>
      <small v-if="completedCount" class="shizuki-todo-completed">已完成 {{ completedCount }} 项</small>
      <small class="shizuki-todo-note">Meguri 运行时会到点通知；关闭桌宠后不能准时提醒。</small>
      </div>
    </template>
    <p v-if="error" class="shizuki-todo-error" role="alert">{{ error }}</p>
  </section>
</template>

<style scoped>
.shizuki-todo-panel > * { flex-shrink: 0; }
.todo-view-switch { display: flex; gap: 6px; }
.todo-view-switch button { border: 1px solid var(--conversation-border); border-radius: 8px; padding: 6px 12px; color: var(--conversation-text); background: var(--conversation-input); cursor: pointer; font: inherit; font-size: 12px; }
.todo-view-switch button[aria-pressed='true'] { color: var(--conversation-accent); background: var(--conversation-user-bubble); border-color: var(--conversation-accent); }
.todo-view-switch button:focus-visible { outline: 2px solid var(--conversation-accent); outline-offset: 2px; }
.todo-list-view { display: grid; gap: 8px; }
</style>
