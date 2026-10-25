<script setup lang="ts">
import { REMINDER_ZONE_LABEL } from '../../shared/reminder-intent.mjs'
import type { ReminderFlow } from '../services/reminder-flow'

const props = defineProps<{ flow: ReminderFlow }>()
const emit = defineEmits<{ created: [source: string] }>()
async function confirm() {
  if (await props.flow.confirm()) emit('created', props.flow.source)
}
async function cancelReview() {
  await props.flow.cancelReview()
}
</script>

<template>
  <section v-if="flow.phase !== 'idle' || flow.pending.length" class="reminder-review no-drag" aria-label="提醒核对" aria-live="polite">
    <strong>{{ flow.phase === 'success' ? '服务器已确认' : '提醒核对' }}</strong>
    <p v-if="flow.question">{{ flow.question }}</p>
    <template v-if="flow.phase === 'clarify'">
      <label>提醒内容<input v-model="flow.title" maxlength="200" /></label>
      <label>{{ REMINDER_ZONE_LABEL }}<input v-model="flow.dueAt" type="datetime-local" step="60" /></label>
      <button type="button" @click="flow.clarify()">重新核对</button>
    </template>
    <template v-if="flow.phase === 'review'">
      <p>行为：{{ flow.dueAt ? '提醒我' : '无提醒待办' }} · {{ flow.title }}</p>
      <p>时间：{{ flow.dueAt.replace('T', ' ') }} · {{ REMINDER_ZONE_LABEL }}</p>
      <small>由 Meguri 桌面轮询触发。请保持电脑与 Meguri 运行并联网；退出后不保证准时提醒。</small>
      <small>若已绑定主账号，会按既有设置尝试语音和 QQ 投递；Core 接收不等于 QQ 已送达。</small>
      <button type="button" :disabled="flow.busy || !flow.account" @click="confirm">{{ flow.busy ? '正在核对服务器…' : flow.dispatched ? '重试 / 核对写入结果' : '确认创建提醒' }}</button>
      <button v-if="!flow.dispatched" type="button" @click="flow.phase = 'clarify'">修改内容或时间</button>
    </template>
    <p v-if="flow.feedback" class="shizuki-todo-feedback" role="status">{{ flow.feedback }}</p>
    <p v-if="flow.error" class="shizuki-todo-error" role="alert">{{ flow.error }}</p>
    <button type="button" :disabled="flow.cancelRequested" @click="cancelReview">{{ flow.cancelRequested ? '正在停止核对…' : flow.phase === 'success' ? '关闭' : '取消核对' }}</button>
    <button v-if="flow.dispatched" type="button" :disabled="flow.busy" @click="flow.defer()">稍后核对（不取消服务器记录）</button>
    <div v-if="flow.pending.length && !flow.dispatched">
      <p>未确认的创建请求（可从重启前恢复）：</p>
      <button v-for="request in flow.pending" :key="request.requestId" type="button" @click="flow.restore(request)">{{ request.title }} · 核对原请求</button>
    </div>
  </section>
</template>
