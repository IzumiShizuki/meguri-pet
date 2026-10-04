import type { MeguriDesktopBridge, PendingTodoCreate, ShizukiTodo } from '../../shared/desktop-contract'
import { parseReminderIntent, parseShanghaiTime, REMINDER_TIMEZONE } from '../../shared/reminder-intent.mjs'

type Intent = { status: 'none' | 'ready' | 'clarify' | 'unsupported', title?: string, dueAt?: string, message?: string }
type Bridge = NonNullable<MeguriDesktopBridge['shizuki']>

export class ReminderFlow {
  phase: 'idle' | 'review' | 'clarify' | 'unsupported' | 'success' = 'idle'
  source = ''
  title = ''
  dueAt = ''
  question = ''
  error = ''
  feedback = ''
  busy = false
  requestId = ''
  account = ''
  dispatched = false
  saved?: ShizukiTodo
  pending: PendingTodoCreate[] = []

  constructor(public bridge?: Bridge, public clock = () => Date.now(), public newId: () => string = () => crypto.randomUUID()) {}

  async prepare(text: string, overrides: { title?: string, dueAt?: string } = {}): Promise<boolean> {
    const result = parseReminderIntent(text, this.clock(), overrides) as Intent
    if (result.status === 'none') return false
    if (this.busy || this.dispatched) {
      this.error = '请先核对当前写入结果；不能用新请求重复创建。'
      return true
    }
    this.source = text
    this.error = ''
    this.feedback = ''
    this.title = result.title || ''
    this.dueAt = result.dueAt || ''
    this.question = result.message || ''
    this.phase = result.status === 'ready' ? 'review' : result.status
    this.requestId = ''
    this.account = ''
    if (this.phase === 'review') {
      this.requestId = this.newId()
      try {
        const status = await this.bridge?.status()
        if (status?.connected) {
          this.account = `${status.origin}:${status.userId}`
          const pending = await this.bridge!.pendingCreates()
          if (!pending.ok) { this.error = pending.error || '无法核对原创建请求。'; this.account = ''; return true }
          this.pending = pending.requests || []
          const previous = this.pending.find(item => item.title === this.title && item.dueAt === this.dueAt)
          if (previous) {
            this.requestId = previous.requestId
            this.dispatched = previous.state === 'uncertain'
            this.error = '找到相同内容的原请求，将核对原写入结果。'
          }
        }
        else this.error = '请先在设置中连接 Shizuki，再重新核对提醒。'
      }
      catch { this.error = '无法核对 Shizuki 账号，请连接后重新核对。' }
    }
    return true
  }

  async clarify() { await this.prepare(this.source, { title: this.title, dueAt: this.dueAt }) }

  async confirm(): Promise<boolean> {
    if (!this.bridge || this.busy || this.phase !== 'review' || !this.requestId) return false
    if (!this.dispatched && (!Number.isFinite(parseShanghaiTime(this.dueAt)) || parseShanghaiTime(this.dueAt) <= this.clock())) {
      this.error = '核对的提醒时间已过，请重新修改时间。'
      return false
    }
    this.busy = true
    this.error = ''
    try {
      const status = await this.bridge.status()
      if (!status.connected || `${status.origin}:${status.userId}` !== this.account) {
        this.error = '连接或账号已改变，请回到原账号核对；没有创建新的待办。'
        return false
      }
      this.dispatched = true
      const result = await this.bridge.createTodo({
        title: this.title, dueAt: this.dueAt || undefined, requestId: this.requestId, expectedAccount: this.account,
        behavior: 'reminder', timezone: REMINDER_TIMEZONE,
      })
      if (!result.ok || !result.todo?.todoId) {
        if (['request_rejected', 'invalid_due_at', 'invalid_title', 'not_connected', 'account_changed', 'unsupported_action', 'unsupported_timezone'].includes(result.code || ''))
          this.dispatched = false
        this.error = result.error || '服务器尚未确认创建成功。草稿已保留，可重试核对。'
        return false
      }
      this.saved = result.todo
      this.phase = 'success'
      this.dispatched = false
      this.feedback = result.todo.dueAt
        ? `已创建提醒 #${result.todo.todoId}：${result.todo.title}；${result.todo.dueAt.replace('T', ' ').slice(0, 16)}，Asia/Shanghai（UTC+08:00）。由运行中的 Meguri 桌面触发；未确认 QQ 送达。`
        : `已创建无提醒待办 #${result.todo.todoId}：${result.todo.title}。`
      this.pending = this.pending.filter(item => item.requestId !== this.requestId)
      return true
    }
    catch { this.error = '连接中断，写入结果未确认。草稿与请求编号已保留，重试会先核对记录。'; return false }
    finally { this.busy = false }
  }

  async restorePending() {
    if (!this.bridge || this.busy) return
    try {
      const result = await this.bridge.pendingCreates()
      if (result.ok) this.pending = result.requests || []
      else { this.error = result.error || '创建记录无法读取。'; this.phase = 'unsupported' }
    }
    catch { this.error = '无法读取未确认的创建记录。'; this.phase = 'unsupported' }
  }

  async restore(request: PendingTodoCreate) {
    if (this.busy || this.dispatched) return
    let status
    try { status = await this.bridge?.status() }
    catch { this.error = '无法连接原 Shizuki 账号，请稍后核对。'; return }
    if (!status?.connected) { this.error = '请连接原 Shizuki 账号后核对。'; return }
    this.source = ''
    this.title = request.title
    this.dueAt = request.dueAt
    this.requestId = request.requestId
    this.account = `${status.origin}:${status.userId}`
    this.dispatched = true
    this.phase = 'review'
    this.error = '已恢复未确认请求。重试将使用原请求编号，不会盲目重复创建。'
  }

  close() {
    if (this.busy) return
    if (this.dispatched) { this.error = '写入结果尚未确认，不能将其当作已取消。请重试核对或在 TimePrism 管理记录。'; return }
    this.phase = 'idle'
  }

  async defer() {
    if (this.busy) return
    await this.restorePending()
    this.dispatched = false
    this.phase = 'idle'
  }
}
