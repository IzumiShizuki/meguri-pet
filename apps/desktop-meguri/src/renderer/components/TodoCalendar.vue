<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import type { ShizukiTodo } from '../../shared/desktop-contract'
import {
  calendarMonthDays, shanghaiCalendarTime, shiftCalendarMonth, todoCalendarEntries,
  todoCalendarStatus, todoCalendarTimeLabel, todosForCalendarDate,
} from '../utils/todo-calendar'

const props = defineProps<{ todos: ShizukiTodo[] }>()
const now = ref(Date.now())
const today = computed(() => shanghaiCalendarTime(now.value).slice(0, 10))
let timer: ReturnType<typeof setInterval>
onMounted(() => { timer = setInterval(() => { now.value = Date.now() }, 60000) })
onBeforeUnmount(() => clearInterval(timer))
const month = ref(today.value.slice(0, 7))
const selectedDate = ref(today.value)
const entries = computed(() => todoCalendarEntries(props.todos))
const days = computed(() => calendarMonthDays(month.value).map(day => ({
  ...day, count: todosForCalendarDate(entries.value, day.date).length,
})))
const selectedItems = computed(() => todosForCalendarDate(entries.value, selectedDate.value))
function goToday() {
  selectedDate.value = shanghaiCalendarTime(Date.now()).slice(0, 10)
  month.value = selectedDate.value.slice(0, 7)
}
</script>

<template>
  <section class="todo-calendar" aria-label="Todo 日历">
    <header class="month-heading">
      <strong>待办日历</strong>
      <div>
        <button type="button" aria-label="上个月" @click="month = shiftCalendarMonth(month, -1)">‹</button>
        <span>{{ month }}</span>
        <button type="button" aria-label="下个月" @click="month = shiftCalendarMonth(month, 1)">›</button>
        <button type="button" @click="goToday">今天</button>
      </div>
    </header>
    <div class="calendar-weekdays" aria-hidden="true"><span v-for="name in ['一', '二', '三', '四', '五', '六', '日']" :key="name">{{ name }}</span></div>
    <div class="calendar-days">
      <button v-for="day in days" :key="day.date" type="button" :class="{ muted: !day.inMonth, selected: day.date === selectedDate, today: day.date === today }"
        :aria-current="day.date === today ? 'date' : undefined" :aria-pressed="day.date === selectedDate" :aria-label="`${day.date}，${day.count} 项待办，查看截止时间`" @click="selectedDate = day.date">
        {{ day.label }}<small v-if="day.count">{{ day.count }}</small>
      </button>
    </div>
    <section class="date-details" aria-label="待办日期详情" aria-live="polite">
      <header><strong>{{ selectedDate }}</strong><small>Asia/Shanghai · {{ selectedItems.length }} 项</small></header>
      <p v-if="!selectedItems.length">这一天没有日历待办。</p>
      <ol v-else>
        <li v-for="item in selectedItems" :key="item.todo.todoId" :class="{ done: item.todo.done }">
          <div class="item-heading"><strong>{{ item.todo.title }}</strong><span :class="{ overdue: todoCalendarStatus(item, now) === '已逾期' }">{{ todoCalendarStatus(item, now) }}</span></div>
          <dl>
            <template v-if="item.todo.timingMode === 'RANGE'"><dt>开始</dt><dd>{{ todoCalendarTimeLabel(item.start, item.dayPrecision) }}</dd></template>
            <dt>截止</dt><dd>{{ todoCalendarTimeLabel(item.end, item.dayPrecision) }}{{ item.dayPrecision ? ' · 按天' : '' }}</dd>
          </dl>
          <p v-if="item.todo.detail">{{ item.todo.detail }}</p>
        </li>
      </ol>
    </section>
  </section>
</template>

<style scoped>
.todo-calendar { flex-shrink: 0; display: grid; gap: 10px; border-top: 1px solid var(--conversation-border); padding-top: 14px; }
.month-heading, .month-heading > div, .date-details header, .item-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
.month-heading > div { font-variant-numeric: tabular-nums; }
button { min-width: 28px; min-height: 28px; padding: 3px 7px; border: 1px solid var(--conversation-border); border-radius: 8px; color: inherit; background: var(--conversation-input); cursor: pointer; }
button:focus-visible { outline: 2px solid var(--conversation-accent); outline-offset: 2px; }
.calendar-weekdays, .calendar-days { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 4px; text-align: center; }
.calendar-weekdays { font-size: 11px; opacity: .65; }
.calendar-days button { position: relative; min-height: 34px; }
.calendar-days small { position: absolute; right: 3px; bottom: 1px; color: var(--conversation-accent); font-size: 9px; }
.calendar-days .muted { opacity: .45; }
.calendar-days .today { border-color: var(--conversation-accent); }
.calendar-days .selected { background: var(--conversation-user-bubble); border-color: var(--conversation-accent); }
.date-details { display: grid; gap: 10px; font-size: 12px; }
.date-details header small { opacity: .65; }
.date-details ol { display: grid; gap: 8px; list-style: none; margin: 0; padding: 0; }
.date-details li { padding: 10px; border: 1px solid var(--conversation-border); border-radius: 10px; overflow-wrap: anywhere; }
.item-heading span { font-size: 11px; opacity: .8; }
.item-heading .overdue { color: var(--conversation-danger); }
.done .item-heading strong { text-decoration: line-through; opacity: .6; }
dl { display: grid; grid-template-columns: auto 1fr; gap: 4px 10px; margin: 8px 0 0; font-variant-numeric: tabular-nums; }
dt { opacity: .65; } dd { margin: 0; }
p { margin: 5px 0 0; white-space: pre-wrap; opacity: .75; }
</style>
