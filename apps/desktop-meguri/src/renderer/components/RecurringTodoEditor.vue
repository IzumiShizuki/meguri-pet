<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import type { ShizukiRecurringRule, ShizukiRecurringRuleInput } from '../../shared/desktop-contract'
import { recurringDescription, weeklyCron, weeklyPreset, recurringStartOfDay } from '../../shared/recurring-todos.mjs'
const props = defineProps<{ account: string, protectedWrite?: boolean }>()
const emit = defineEmits<{ changed: [], rules: [rules: ShizukiRecurringRule[]] }>()
const bridge = window.meguriDesktop?.shizuki
const rules = ref<ShizukiRecurringRule[]>([])
const selected = ref<number>()
const selectedVersion = ref<number>()
const title = ref(''), time = ref('20:00'), zone = ref('Asia/Shanghai'), cron = ref('')
const startAt = ref(''), endAt = ref('')
const weekdays = ref<number[]>([1, 2, 3, 4, 5])
const custom = ref(false), enabled = ref(true), reminder = ref(true)
const amount = ref(10), unit = ref('MINUTE'), busy = ref(false), error = ref(''), feedback = ref('')
const pending = ref<ShizukiRecurringRuleInput>()
let disposed = false, timer: ReturnType<typeof setInterval>
async function load() {
  if (!bridge || busy.value || disposed) return
  try {
    const result = await bridge.listRecurringRules()
    if (disposed) return
    if (!result.ok) { error.value = result.error || '无法读取周期规则。'; return }
    rules.value = result.rules || []; emit('rules', rules.value)
  } catch { if (!disposed) error.value = '无法读取周期规则，请检查连接。' }
}
function edit(rule?: ShizukiRecurringRule) {
  if (busy.value || pending.value) return
  selectedVersion.value = rule?.version
  selected.value = rule?.ruleId; title.value = rule?.title || ''; zone.value = rule?.timeZoneId || 'Asia/Shanghai'
  startAt.value = rule?.startAt || ''; endAt.value = rule?.endAt || ''
  const preset = rule ? weeklyPreset(rule.cronExpr) : { weekdays: [1, 2, 3, 4, 5], time: '20:00' }
  custom.value = !preset; cron.value = rule?.cronExpr || ''; weekdays.value = preset?.weekdays || []; time.value = preset?.time || '20:00'
  enabled.value = rule?.enabled ?? true; reminder.value = rule?.reminderEnabled ?? true
  amount.value = rule?.deadlineRemindValue ?? 10; unit.value = rule?.deadlineRemindUnit || 'MINUTE'
  error.value = ''; feedback.value = ''
}
async function save() {
  if (!bridge || busy.value) return
  try {
    if (!pending.value) {
      if (!custom.value) weeklyCron(weekdays.value, time.value)
      pending.value = { ruleId: selected.value, title: title.value, timeZoneId: zone.value, enabled: enabled.value,
        startAt: startAt.value || (!selected.value && props.protectedWrite ? recurringStartOfDay(Date.now(), zone.value) : ''), endAt: endAt.value,
        ...(props.protectedWrite ? { protectedWrite: true, expectedVersion: selectedVersion.value } : {}),
        reminderEnabled: reminder.value, deadlineRemindValue: Number(amount.value), deadlineRemindUnit: unit.value,
        ...(custom.value ? { cronExpr: cron.value } : { weekdays: [...weekdays.value], time: time.value }),
        requestId: crypto.randomUUID(), expectedAccount: props.account }
    }
    busy.value = true; error.value = ''
    // Vue wraps refs (including nested weekday arrays) in proxies that Electron
    // cannot structured-clone. Keep the pending identity, but send a plain DTO.
    const request = { ...pending.value, ...(pending.value.weekdays ? { weekdays: [...pending.value.weekdays] } : {}) }
    const result = await bridge.saveRecurringRule(request)
    if (disposed) return
    if (!result.ok || !result.rule) {
      error.value = result.error || '服务器尚未确认保存。'
      if (['request_rejected', 'invalid_title', 'invalid_schedule', 'invalid_timezone', 'invalid_reminder', 'invalid_id', 'account_changed', 'not_found'].includes(result.code || '')) pending.value = undefined
      return
    }
    pending.value = undefined; selected.value = result.rule.ruleId; selectedVersion.value = result.rule.version
    feedback.value = `服务器已保存并回读：${recurringDescription(result.rule)}`
    emit('changed')
  } catch (e) { error.value = e instanceof Error ? e.message : '保存失败，请核对原请求。' }
  finally { busy.value = false; await load() }
}
onMounted(() => { void load(); timer = setInterval(() => { if (document.visibilityState === 'visible') void load() }, 60000) })
onBeforeUnmount(() => { disposed = true; clearInterval(timer) })
</script>
<template>
  <details class="recurring-editor">
    <summary>周期待办 · 星期与提前提醒</summary>
    <p>完成某一天的待办只更新该日期实例；修改周期规则影响后续安排。</p>
    <ul><li v-for="rule in rules" :key="rule.ruleId"><button type="button" :disabled="busy || !!pending" @click="edit(rule)">{{ rule.title }} · {{ recurringDescription(rule) }}</button></li></ul>
    <button type="button" :disabled="busy || !!pending" @click="edit()">新建周期规则</button>
    <form @submit.prevent="save">
      <fieldset :disabled="busy || !!pending">
        <legend>{{ selected ? `编辑规则 #${selected}` : '新建规则' }}</legend>
        <label>标题<input v-model="title" maxlength="200" required /></label>
        <template v-if="!custom">
          <div class="weekdays"><label v-for="day in [1, 2, 3, 4, 5, 6, 0]" :key="day"><input v-model="weekdays" type="checkbox" :value="day" />周{{ '日一二三四五六'[day] }}</label></div>
          <button type="button" @click="weekdays = [1, 2, 3, 4, 5]">周一至周五</button>
          <label>截止时间<input v-model="time" type="time" required /></label>
        </template>
        <label v-else>原 Cron 表达式<input v-model="cron" maxlength="64" required /><small>高级规则保留原样，不转换成星期快捷项。</small></label>
        <label>时区<input v-model="zone" required placeholder="Asia/Shanghai" /></label>
        <label>开始时间<input v-model="startAt" type="datetime-local" step="1" /><small>按上方时区；留空保留原值，新建时从当天零点生效。</small></label>
        <label>结束时间<input v-model="endAt" type="datetime-local" step="1" /><small>按上方时区；留空表示不设结束限制。</small></label>
        <label><input v-model="enabled" type="checkbox" />启用规则</label>
        <label><input v-model="reminder" type="checkbox" />提前提醒</label>
        <label v-if="reminder">提前<input v-model.number="amount" type="number" min="0" step="1" /><select v-model="unit"><option value="MINUTE">分钟（0 为到点）</option><option value="DAY">天</option></select></label>
      </fieldset>
      <p v-if="pending">正在保留原请求；结果不确定时仅核对，避免重复创建。</p>
      <button v-if="pending && error" type="button" :disabled="busy" @click="pending = undefined">保留当前文本并重新核对</button>
      <button type="submit" :disabled="busy || !title.trim()">{{ busy ? '正在核对…' : pending ? '重试 / 核对原请求' : '确认保存周期规则' }}</button>
    </form>
    <p v-if="error" role="alert">{{ error }}</p><p v-if="feedback" role="status">{{ feedback }}</p>
    <small>网页提醒需页面保持打开；桌面提醒需 Meguri 运行。两端各自去重，不保证跨设备只提醒一次。</small>
  </details>
</template>
<style scoped>
.recurring-editor { border-top: 1px solid var(--conversation-border); padding: 10px 0; font-size: 12px; }
fieldset, form { display: grid; gap: 8px; min-width: 0; } label { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.weekdays { display: flex; flex-wrap: wrap; gap: 8px; } input:not([type=checkbox]) { min-width: 0; flex: 1; } ul { padding-left: 16px; }
button, input, select { font: inherit; color: inherit; background: var(--conversation-input); border: 1px solid var(--conversation-border); border-radius: 5px; padding: 5px; }
small { opacity: .75; } [role=alert] { color: var(--conversation-danger); }
</style>
