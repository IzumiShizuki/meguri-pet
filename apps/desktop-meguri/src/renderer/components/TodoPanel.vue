<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

import type { ShizukiConnectionStatus, ShizukiTodo } from '../../shared/desktop-contract'

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
  if (!bridge) {
    error.value = '桌面待办桥接尚未就绪，请重启 Meguri。'
    return
  }
  loading.value = true
  error.value = ''
  try {
    status.value = await bridge.status()
    if (!status.value.connected) {
      todos.value = []
      return
    }
    const result = await bridge.listTodos()
    if (!result.ok) {
      error.value = result.error || '无法读取 Shizuki 待办。'
      return
    }
    todos.value = result.todos || []
  }
  catch {
    error.value = '无法读取 Shizuki 待办，请检查网络。'
  }
  finally {
    loading.value = false
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
  error.value = ''
  feedback.value = ''
  try {
    const result = await bridge.createTodo({ title: finalTitle, dueAt: dueAt.value || undefined })
    if (!result.ok || !result.todo) {
      error.value = result.error || 'Shizuki 尚未确认待办已创建。'
      return
    }
    todos.value = [result.todo, ...todos.value.filter(todo => todo.todoId !== result.todo?.todoId)]
    feedback.value = dueAt.value
      ? `已存入 Shizuki，提醒时间：${showTime(result.todo.dueAt)}`
      : '待办已存入 Shizuki。'
    title.value = ''
    dueAt.value = ''
  }
  catch {
    error.value = '写入 Shizuki 失败，请检查网络后重试。'
  }
  finally {
    busy.value = false
  }
}

async function complete(todo: ShizukiTodo) {
  if (!bridge || busy.value) return
  busy.value = true
  error.value = ''
  feedback.value = ''
  try {
    const result = await bridge.completeTodo(todo.todoId)
    if (!result.ok || !result.todo) {
      error.value = result.error || 'Shizuki 尚未确认待办已完成。'
      return
    }
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
onMounted(() => {
  window.addEventListener('focus', refreshOnFocus)
  void refresh()
})
onBeforeUnmount(() => window.removeEventListener('focus', refreshOnFocus))
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
      <form class="shizuki-todo-form" @submit.prevent="create">
        <label>待办内容<input v-model="title" maxlength="200" placeholder="例如：睡觉" :disabled="busy" /></label>
        <label>到点提醒 <small>可选，本机时间</small><input v-model="dueAt" type="datetime-local" step="60" :disabled="busy" /></label>
        <p v-if="dueAt" class="shizuki-todo-preview">将于 {{ showTime(dueAt) }} 提醒：{{ title.trim() || '待办内容' }}</p>
        <button type="submit" :disabled="busy || !title.trim()">{{ busy ? '正在保存…' : '存入 Shizuki' }}</button>
      </form>
      <p v-if="feedback" class="shizuki-todo-feedback" role="status">{{ feedback }}</p>
      <div class="shizuki-todo-list" :aria-busy="loading">
        <p v-if="loading">正在读取待办…</p>
        <p v-else-if="pending.length === 0">没有未完成的待办。</p>
        <div v-for="todo in pending" :key="todo.todoId" class="shizuki-todo-item">
          <button type="button" :disabled="busy" :aria-label="`完成 ${todo.title}`" @click="complete(todo)">○</button>
          <span><strong>{{ todo.title }}</strong><small v-if="todo.dueAt">{{ showTime(todo.dueAt) }}{{ todo.reminderEnabled ? ' · 已设提醒' : '' }}</small></span>
        </div>
      </div>
      <small v-if="completedCount" class="shizuki-todo-completed">已完成 {{ completedCount }} 项</small>
      <small class="shizuki-todo-note">Meguri 运行时会到点通知；关闭桌宠后不能准时提醒。</small>
    </template>
    <p v-if="error" class="shizuki-todo-error" role="alert">{{ error }}</p>
  </section>
</template>
