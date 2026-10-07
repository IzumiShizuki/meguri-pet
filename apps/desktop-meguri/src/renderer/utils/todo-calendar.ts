import type { ShizukiTodo } from '../../shared/desktop-contract'

const zone = 'Asia/Shanghai'
const formatter = new Intl.DateTimeFormat('sv-SE', {
  timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})
export function shanghaiCalendarTime(value: string | number): string {
  const raw = typeof value === 'string' ? value.trim() : value
  const naive = typeof raw === 'string' && /^\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?)?$/.test(raw)
  const timestamp = typeof raw === 'number' ? raw : Date.parse(naive ? `${raw.length === 10 ? `${raw}T00:00:00` : raw}+08:00` : raw)
  if (!Number.isFinite(timestamp)) return ''
  const formatted = formatter.format(new Date(timestamp)).replace(' ', 'T')
  if (naive && formatted.slice(0, 10) !== String(raw).slice(0, 10)) return ''
  return formatted
}

export interface TodoCalendarEntry {
  todo: ShizukiTodo
  start: string
  end: string
  dayPrecision: boolean
}

export function todoCalendarEntries(todos: ShizukiTodo[]): TodoCalendarEntry[] {
  return todos.flatMap((todo) => {
    const end = shanghaiCalendarTime(todo.dueAt)
    if (todo.showOnCalendar === false || !todo.title.trim() || !end) return []
    const start = todo.timingMode === 'RANGE' ? shanghaiCalendarTime(todo.rangeStartAt) || end : end
    if (start > end) return []
    return [{ todo, start, end, dayPrecision: todo.timePrecision === 'DAY' }]
  }).sort((a, b) => a.end.localeCompare(b.end) || a.todo.title.localeCompare(b.todo.title, 'zh-CN'))
}

export function todosForCalendarDate(entries: TodoCalendarEntry[], date: string) {
  return entries.filter(item => item.start.slice(0, 10) <= date && date <= item.end.slice(0, 10))
}

export function todoCalendarTimeLabel(value: string, dayPrecision: boolean) {
  return dayPrecision ? value.slice(0, 10) : value.replace('T', ' ')
}

export function todoCalendarStatus(item: TodoCalendarEntry, now = Date.now()) {
  if (item.todo.done) return '已完成'
  const current = shanghaiCalendarTime(now)
  const overdue = item.dayPrecision ? item.end.slice(0, 10) < current.slice(0, 10) : Date.parse(`${item.end}:00+08:00`) < now
  return overdue ? '已逾期' : '待完成'
}

export function calendarMonthDays(month: string) {
  const [year, monthIndex] = month.split('-').map(Number)
  const first = new Date(Date.UTC(year, monthIndex - 1, 1))
  const offset = (first.getUTCDay() + 6) % 7
  return Array.from({ length: 42 }, (_, index) => {
    const date = new Date(Date.UTC(year, monthIndex - 1, 1 - offset + index)).toISOString().slice(0, 10)
    return { date, label: Number(date.slice(8)), inMonth: date.startsWith(month) }
  })
}

export function shiftCalendarMonth(month: string, offset: number) {
  const [year, monthIndex] = month.split('-').map(Number)
  return new Date(Date.UTC(year, monthIndex - 1 + offset, 1)).toISOString().slice(0, 7)
}
