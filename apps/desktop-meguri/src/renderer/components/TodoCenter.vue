<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { ShizukiCenterSnapshot, ShizukiConnectionStatus, ShizukiTodo, ShizukiNote, ShizukiCenterMutation, ShizukiCenterResult } from '../../shared/desktop-contract'
import { sortCenterTodos, compareCenterNotes, centerTodoStatus } from '../../shared/todo-center-order.mjs'
import TodoCalendar from './TodoCalendar.vue'
import RecurringTodoEditor from './RecurringTodoEditor.vue'

const props = defineProps<{ suggestedTitle?: string }>()
const emit = defineEmits<{ close: [] }>()
const bridge = window.meguriDesktop?.shizuki
const status = ref<ShizukiConnectionStatus>()
const snapshot = ref<ShizukiCenterSnapshot>()
const view = ref<'tasks' | 'notes' | 'calendar'>('tasks')
const busy = ref(false), loading = ref(false), error = ref(''), feedback = ref('')
const editor = ref<'task' | 'note'>()
const draft = ref<Record<string, any>>({})
const draftAccount = ref('')
const removeSelected = ref(false)
const pendingInput = ref<ShizukiCenterMutation>()
let disposed = false, sequence = 0, timer: ReturnType<typeof setInterval>
const accountKey = (s?: ShizukiConnectionStatus) => s?.connected ? `${s.origin}:${s.userId}` : ''
const account = computed(() => accountKey(status.value))
const canWrite = computed(() => snapshot.value?.ready === true && Boolean(account.value))
const tasks = computed(() => sortCenterTodos(snapshot.value?.todos || []))
const notes = computed(() => [...(snapshot.value?.notes || [])].sort(compareCenterNotes))
const labels: Record<string, string> = { HIGH: '紧急', MEDIUM: '普通', LOW: '低' }
const plain = <T,>(value: T): T => JSON.parse(JSON.stringify(value))

async function refresh() {
  if (busy.value || loading.value || disposed || !bridge?.center) return
  loading.value = true
  const current = ++sequence
  try {
    const nextStatus = await bridge.status()
    if (disposed || current !== sequence) return
    if (accountKey(nextStatus) !== account.value) snapshot.value = undefined
    status.value = nextStatus
    if (!nextStatus.connected) return
    const result = await bridge.center.snapshot()
    const confirmed = await bridge.status()
    if (disposed || current !== sequence) return
    if (accountKey(confirmed) !== accountKey(nextStatus)) { snapshot.value = undefined; status.value = confirmed; return }
    if (!result.ok || !result.snapshot) { error.value = result.error || '无法读取统一中心。'; return }
    snapshot.value = result.snapshot
    error.value = result.snapshot.error || ''
  }
  catch { if (!disposed) error.value = '连接失败，原编辑内容已保留。' }
  finally { if (current === sequence) loading.value = false }
}
function editTask(task?: ShizukiTodo) {
  if (busy.value || !canWrite.value) return
  editor.value = 'task'; draftAccount.value = account.value
  draft.value = task ? plain(task) : { title: props.suggestedTitle || '', detail: '', priority: 'MEDIUM', dueAt: '', reminderEnabled: false, done: false }
  pendingInput.value = undefined; removeSelected.value = false; error.value = ''; feedback.value = ''
}
function editNote(note?: ShizukiNote) {
  if (busy.value || !canWrite.value) return
  editor.value = 'note'; draftAccount.value = account.value
  draft.value = note ? plain(note) : { title: '', content: '', pinned: false, sortNum: 0 }
  pendingInput.value = undefined; removeSelected.value = false; error.value = ''; feedback.value = ''
}
async function execute(operation: () => Promise<ShizukiCenterResult>, closeEditor = false) {
  if (busy.value || !canWrite.value) return
  busy.value = true; ++sequence; loading.value = false; error.value = ''; feedback.value = ''
  try {
    const result = await operation()
    if (disposed) return
    if (!result.ok) { error.value = result.error || '服务器拒绝了本次请求；原草稿已保留。'; return }
    feedback.value = result.state === 'confirmed' ? '服务器已确认同步。' : '待同步：已保存原请求，联网后请核对或重试。'
    // Pending content is durable in the main-process journal and visible below.
    if (closeEditor) { pendingInput.value = undefined; editor.value = undefined; removeSelected.value = false }
  }
  catch { error.value = '结果未确认；原草稿已保留，请核对待同步记录。' }
  finally { busy.value = false; await refresh() }
}
async function save() {
  if (!bridge?.center || draftAccount.value !== account.value || !editor.value) return
  const item = plain(draft.value)
  if (editor.value === 'task') {
    if (!item.dueAt) item.reminderEnabled = false
    if (item.reminderEnabled && item.deadlineRemindValue == null) {
      item.deadlineRemindValue = 0; item.deadlineRemindUnit = 'MINUTE'
    }
  }
  pendingInput.value ||= { requestId: crypto.randomUUID(), expectedAccount: draftAccount.value, item }
  const input = plain(pendingInput.value)
  const kind = editor.value
  await execute(() => kind === 'task' ? bridge.center!.saveTask(input)
    : removeSelected.value ? bridge.center!.deleteNote(input) : bridge.center!.saveNote(input), true)
}
const completionRequests = new Map<string, ShizukiCenterMutation>()
async function complete(task: ShizukiTodo) {
  if (!bridge?.center) return
  const key = `${account.value}:${task.todoId}:${task.version}:${task.done}`
  if (!completionRequests.has(key)) completionRequests.set(key, { requestId: crypto.randomUUID(), expectedAccount: account.value, item: plain({ ...task, done: !task.done }) })
  await execute(() => bridge.center!.saveTask(completionRequests.get(key)!))
}
async function retry(requestId: string) {
  if (bridge?.center) await execute(() => bridge.center!.retry(requestId, account.value))
}
function openSettings() { void window.meguriDesktop?.window.openSettings() }
function visibleRefresh() { if (document.visibilityState === 'visible') void refresh() }
onMounted(() => {
  void refresh(); timer = setInterval(visibleRefresh, 60000)
  window.addEventListener('online', visibleRefresh); window.addEventListener('focus', visibleRefresh)
})
onBeforeUnmount(() => { disposed = true; ++sequence; clearInterval(timer); window.removeEventListener('online', visibleRefresh); window.removeEventListener('focus', visibleRefresh) })
</script>

<template>
  <section class="shizuki-todo-panel todo-center no-drag" aria-label="统一 Todo 中心">
    <header><div><strong>Todo 与便签</strong><small>Shizuki · 同一账号同步</small></div><div><button :disabled="busy || loading" @click="refresh">刷新</button><button aria-label="关闭待办" @click="emit('close')">×</button></div></header>
    <template v-if="!status?.connected"><p>连接已有 Shizuki 账号后查看同一份任务和便签。</p><button @click="openSettings">打开连接设置</button></template>
    <template v-else>
      <p v-if="snapshot?.stale" class="notice">离线缓存 · {{ snapshot.savedAt ? new Date(snapshot.savedAt).toLocaleString() : '' }}。草稿尚未同步时不会标记成功。</p>
      <nav aria-label="统一中心视图"><button :aria-pressed="view === 'tasks'" @click="view = 'tasks'">待办与约定</button><button :aria-pressed="view === 'notes'" @click="view = 'notes'">便签</button><button :aria-pressed="view === 'calendar'" @click="view = 'calendar'">日历</button></nav>
      <p v-if="!canWrite" class="notice">站点保护接口未就绪，当前只读；不会回退到无版本保护的写入。</p>
      <template v-if="view === 'tasks'">
        <button :disabled="busy || !canWrite" @click="editTask()">新增待办 / 无截止约定</button>
        <p class="hint">按紧急程度、截止时间排序；未设截止的约定不会逾期。</p>
        <ul class="records"><li v-for="task in tasks" :key="task.todoId">
          <button :disabled="busy || !canWrite" :aria-label="`${task.done ? '重新打开' : '完成'} ${task.title}`" @click="complete(task)">{{ task.done ? '✓' : '○' }}</button>
          <div><strong>{{ task.title }}</strong><small>{{ labels[task.priority] || '普通' }} · {{ centerTodoStatus(task) }}<template v-if="task.dueAt"> · {{ task.dueAt.replace('T', ' ') }} · {{ task.timeZoneId || (task.recurringRuleId ? '时区待核对' : 'Asia/Shanghai') }}</template></small><p v-if="task.detail">{{ task.detail }}</p><small v-if="task.recurringRuleId">周期日期实例 {{ task.recurringInstanceAt }}；完成仅影响这一天。</small></div>
          <button v-if="!task.recurringRuleId" :disabled="busy || !canWrite" @click="editTask(task)">编辑</button>
        </li></ul>
        <RecurringTodoEditor v-if="canWrite && !snapshot?.stale" :key="account" :account="account" protected-write @changed="refresh" />
      </template>
      <template v-else-if="view === 'notes'">
        <button :disabled="busy || !canWrite" @click="editNote()">新建便签</button>
        <ul class="records"><li v-for="note in notes" :key="note.noteId"><div><strong>{{ note.pinned ? '置顶 · ' : '' }}{{ note.title }}</strong><p>{{ note.content }}</p></div><button :disabled="busy || !canWrite" @click="editNote(note)">编辑</button></li></ul>
      </template>
      <TodoCalendar v-else :todos="snapshot?.todos || []" />
      <form v-if="editor && draftAccount === account" @submit.prevent="save">
        <fieldset :disabled="busy || !!pendingInput"><legend>{{ editor === 'task' ? '待办 / 约定' : '便签' }} · 保存前核对内容</legend>
          <label>标题<input v-model="draft.title" maxlength="200" required /></label>
          <label>{{ editor === 'task' ? '正文 / 约定内容' : '便签内容' }}<textarea v-if="editor === 'task'" v-model="draft.detail" maxlength="2000" rows="4" /><textarea v-else v-model="draft.content" maxlength="20000" rows="6" /></label>
          <template v-if="editor === 'task'"><label>紧急程度<select v-model="draft.priority"><option value="HIGH">紧急</option><option value="MEDIUM">普通</option><option value="LOW">低</option></select></label><label>截止时间（可留空，Asia/Shanghai）<input v-model="draft.dueAt" type="datetime-local" step="1" /></label><label><input v-model="draft.reminderEnabled" type="checkbox" :disabled="!draft.dueAt" />到期提醒</label></template>
          <template v-else><label><input v-model="draft.pinned" type="checkbox" />置顶便签</label><label v-if="draft.noteId"><input v-model="removeSelected" type="checkbox" />确认删除这条便签（保存时执行）</label></template>
        </fieldset>
        <button :disabled="busy || !canWrite || !draft.title?.trim()" type="submit">{{ removeSelected ? '确认删除这条便签' : '确认保存' }}</button><button type="button" :disabled="busy" @click="editor = undefined; pendingInput = undefined">取消编辑</button>
      </form>
      <p v-else-if="editor" class="notice">编辑中的账号已切换。原草稿仍保留，返回原账号后可继续。</p>
      <details v-if="snapshot?.pending.length" open><summary>待同步 / 冲突记录（{{ snapshot.pending.length }}）</summary><article v-for="item in snapshot.pending" :key="item.requestId"><strong>{{ item.draft.title || item.targetId }} · {{ item.state === 'conflict' ? '版本冲突' : item.state === 'rejected' ? '已拒绝' : '待同步' }}</strong><p>{{ item.error }}</p><pre>{{ item.draft.content || item.draft.detail || '' }}</pre><button v-if="item.state === 'pending'" :disabled="busy || !canWrite" @click="retry(item.requestId)">使用原请求重试</button><small v-else>请刷新，比较原草稿与当前内容，再从列表重新编辑；不会自动覆盖。</small></article></details>
    </template>
    <p v-if="loading" role="status">正在读取…</p><p v-if="feedback" role="status">{{ feedback }}</p><p v-if="error" role="alert">{{ error }}</p>
    <small class="hint">提醒需要相应客户端保持运行；各设备本地去重，不保证跨设备只提醒一次。</small>
  </section>
</template>

<style scoped>
.todo-center { gap: 12px; } .todo-center > * { flex-shrink: 0; } nav, header, header > div:last-child { display: flex; gap: 8px; } header { justify-content: space-between; } header small, .records small { display: block; opacity: .75; }
button, input, select, textarea { color: inherit; background: var(--conversation-input); border: 1px solid var(--conversation-border); border-radius: 7px; padding: 7px; font: inherit; } button { cursor: pointer; } button:disabled { opacity: .5; cursor: default; } button[aria-pressed=true] { border-color: var(--conversation-accent); color: var(--conversation-accent); }
.records { display: grid; gap: 10px; list-style: none; padding: 0; } .records li { display: flex; align-items: flex-start; gap: 8px; border-bottom: 1px solid var(--conversation-border); padding-bottom: 10px; } .records li > div { flex: 1; min-width: 0; } p, pre { white-space: pre-wrap; overflow-wrap: anywhere; margin: 5px 0; } pre { font: inherit; }
form, fieldset { display: grid; gap: 9px; min-width: 0; } label { display: grid; gap: 4px; } label:has(input[type=checkbox]) { display: flex; align-items: center; } input, textarea { min-width: 0; } .notice { border-left: 3px solid var(--conversation-accent); padding: 8px; } .hint { opacity: .7; font-size: 12px; } [role=alert] { color: var(--conversation-danger); } article { padding: 10px 0; border-bottom: 1px solid var(--conversation-border); }
</style>
