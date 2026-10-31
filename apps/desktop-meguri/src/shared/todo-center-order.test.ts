import { expect, it } from 'vitest'
import { compareCenterTodos, compareCenterNotes, centerTodoStatus, sortCenterTodos } from './todo-center-order.mjs'
it('uses explicit urgency before deadline and a stable ID tie break', () => {
  const items = [
    {todoId:4,priority:'LOW',dueAt:'2030-01-01T08:00',done:false},
    {todoId:3,priority:'HIGH',dueAt:'',done:false},
    {todoId:2,priority:'HIGH',dueAt:'2030-01-01T10:00',done:false},
    {todoId:1,priority:'HIGH',dueAt:'2030-01-01T10:00',done:false},
    {todoId:5,priority:'HIGH',dueAt:'2020-01-01T10:00',done:true},
  ]
  expect(items.sort(compareCenterTodos).map(t=>t.todoId)).toEqual([1,2,3,4,5])
  expect(centerTodoStatus(items.find(t=>t.todoId===3),Date.parse('2040-01-01'))).toBe('无截止约定')
})
it('compares recurring deadlines as instants in their actual zones', () => {
  const a={todoId:1,priority:'MEDIUM',done:false,dueAt:'2030-01-01T10:00',timeZoneId:'Asia/Shanghai'}
  const b={...a,todoId:2,dueAt:'2030-01-01T09:00',timeZoneId:'America/New_York'}
  expect(compareCenterTodos(a,b)).toBeLessThan(0)
})
it('pinned note ordering is independent of task dates', () => {
  const notes=[{noteId:2,pinned:false,sortNum:0,updatedAt:'2030-01-02'},{noteId:1,pinned:true,sortNum:99,updatedAt:'2030-01-01'}]
  expect(notes.sort(compareCenterNotes)[0].noteId).toBe(1)
})

it('preserves authoritative ordering when a historic recurring timezone is unknown', () => {
  const unknown={todoId:1,priority:'HIGH',done:false,dueAt:'2030-01-01T09:00',recurringRuleId:71}
  const known={todoId:2,priority:'HIGH',done:false,dueAt:'2030-01-01T08:00',timeZoneId:'Asia/Shanghai'}
  expect(sortCenterTodos([unknown,known]).map(t=>t.todoId)).toEqual([1,2])
  expect(centerTodoStatus(unknown,Date.parse('2040-01-01'))).toBe('时间待核对')
})
