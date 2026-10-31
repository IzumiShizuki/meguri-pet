import { recurringTime } from './recurring-todos.mjs'

export function todoDeadline(todo) {
  if (!todo.dueAt) return Infinity
  const zone = Object.hasOwn(todo, 'timeZoneId') ? todo.timeZoneId : todo.recurringRule?.timeZoneId || (todo.recurringRuleId ? '' : 'Asia/Shanghai')
  if (!zone) return Infinity
  const result = recurringTime(todo.dueAt, zone)
  return Number.isFinite(result) ? result : Infinity
}

export function compareCenterTodos(a, b) {
  const priority = { HIGH: 0, MEDIUM: 1, LOW: 2 }
  return Number(a.done) - Number(b.done) || (priority[a.priority] ?? 1) - (priority[b.priority] ?? 1)
    || todoDeadline(a) - todoDeadline(b) || (a.sortNum || 0) - (b.sortNum || 0) || a.todoId - b.todoId
}

export function centerTodoStatus(todo, now = Date.now()) {
  if (todo.done) return '已完成'
  if (!todo.dueAt) return '无截止约定'
  const deadline = todoDeadline(todo)
  return !Number.isFinite(deadline) ? '时间待核对' : deadline < now ? '已逾期' : '待完成'
}

export function compareCenterNotes(a, b) {
  return Number(b.pinned) - Number(a.pinned) || (a.sortNum || 0) - (b.sortNum || 0)
    || b.updatedAt.localeCompare(a.updatedAt) || a.noteId - b.noteId
}

// A missing historic rule zone cannot be reconstructed locally. Preserve the
// authority's within-priority order instead of sorting a guessed instant.
export function sortCenterTodos(todos) {
  const copy = [...todos]
  const group = todo => `${Boolean(todo.done)}:${todo.priority}`
  const unknownGroups = new Set(copy.filter(todo => todo.dueAt && todo.recurringRuleId
    && !(Object.hasOwn(todo, 'timeZoneId') ? todo.timeZoneId : todo.recurringRule?.timeZoneId)).map(group))
  const priority = { HIGH: 0, MEDIUM: 1, LOW: 2 }
  return copy.sort((a, b) => Number(a.done) - Number(b.done) || (priority[a.priority] ?? 1) - (priority[b.priority] ?? 1)
    || (unknownGroups.has(group(a)) ? 0 : compareCenterTodos(a, b)))
}
