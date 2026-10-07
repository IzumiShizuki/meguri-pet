import { describe, expect, it } from 'vitest'
import type { ShizukiTodo } from '../../shared/desktop-contract'
import { calendarMonthDays, shanghaiCalendarTime, shiftCalendarMonth, todoCalendarEntries, todoCalendarStatus, todoCalendarTimeLabel, todosForCalendarDate } from './todo-calendar'

function todo(fields: Partial<ShizukiTodo>) {
  return { todoId: 1, title: '提交报告', dueAt: '2026-10-08T18:30:00', showOnCalendar: true, timePrecision: 'MINUTE', timingMode: 'DEADLINE', ...fields } as ShizukiTodo
}
describe('Todo calendar', () => {
  it('groups naive and zoned times in Shanghai independently of the host timezone', () => {
    expect(shanghaiCalendarTime('2026-10-08T17:30:00Z')).toBe('2026-10-09T01:30')
    expect(shanghaiCalendarTime('2026-10-08T18:30:00')).toBe('2026-10-08T18:30')
    expect(shanghaiCalendarTime('2026-10-08')).toBe('2026-10-08T00:00')
    expect(shanghaiCalendarTime('2026-02-30')).toBe('')
    expect(shanghaiCalendarTime('invalid')).toBe('')
  })
  it('omits hidden, undated, and invalid ranges while including the final range day', () => {
    const entries = todoCalendarEntries([
      todo({}), todo({ todoId: 2, showOnCalendar: false }), todo({ todoId: 3, dueAt: '' }),
      todo({ todoId: 4, timingMode: 'RANGE', rangeStartAt: '2026-10-07T09:00:00', dueAt: '2026-10-10T23:59:00' }),
      todo({ todoId: 5, timingMode: 'RANGE', rangeStartAt: '2026-10-11', dueAt: '2026-10-08' }),
    ])
    expect(entries).toHaveLength(2)
    expect(todosForCalendarDate(entries, '2026-10-08')).toHaveLength(2)
    expect(todosForCalendarDate(entries, '2026-10-10')).toHaveLength(1)
    expect(todosForCalendarDate(entries, '2026-10-11')).toHaveLength(0)
  })
  it('returns all items and reflects confirmed changes from the supplied Todo snapshot', () => {
    const todos = Array.from({ length: 7 }, (_, index) => todo({ todoId: index + 1 }))
    expect(todosForCalendarDate(todoCalendarEntries(todos), '2026-10-08')).toHaveLength(7)
    todos[0].done = true
    todos[1].dueAt = '2026-10-09T09:00:00'
    const entries = todoCalendarEntries(todos)
    expect(todosForCalendarDate(entries, '2026-10-08')).toHaveLength(6)
    expect(todoCalendarStatus(entries.find(item => item.todo.todoId === 1)!)).toBe('已完成')
  })
  it('formats full precision and keeps a day deadline pending until the next Shanghai date', () => {
    const [day] = todoCalendarEntries([todo({ timePrecision: 'DAY', dueAt: '2026-10-08' })])
    expect(todoCalendarTimeLabel(day.end, true)).toBe('2026-10-08')
    expect(todoCalendarTimeLabel('2026-10-08T18:30', false)).toBe('2026-10-08 18:30')
    expect(todoCalendarStatus(day, Date.parse('2026-10-08T23:59:00+08:00'))).toBe('待完成')
    expect(todoCalendarStatus(day, Date.parse('2026-10-09T00:00:00+08:00'))).toBe('已逾期')
  })
  it('keeps the six-week grid and month navigation stable across year boundaries', () => {
    expect(calendarMonthDays('2026-10')).toHaveLength(42)
    expect(calendarMonthDays('2026-10')[0].date).toBe('2026-09-28')
    expect(shiftCalendarMonth('2026-12', 1)).toBe('2027-01')
    expect(shiftCalendarMonth('2026-01', -1)).toBe('2025-12')
  })
})
