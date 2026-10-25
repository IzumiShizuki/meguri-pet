<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import type { CompanionActivityStatus } from '../../shared/companion-contract'
import { remoteChatConsented } from '../../shared/experience-contract'

import { useCompanionDirectorStore } from '../stores/companion-director'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'

const director = useCompanionDirectorStore()
const preferences = useDesktopPreferencesStore()
const text = ref('')
const dueAt = ref('')
const feedback = ref('')
const saving = ref(false)
const activityStatus = ref<CompanionActivityStatus>()
let disposeActivity: (() => void) | undefined
const presenceText = computed(() => {
  const presence = activityStatus.value?.presence
  if (!presence) return '活动检测尚未就绪：主动搭话与屏幕观察暂停。'
  if (presence.active) return '最近 10 分钟有鼠标或键盘操作，允许按冷却规则尝试主动搭话。'
  const reasons: Record<string, string> = { locked: '电脑已锁定', suspended: '电脑已休眠', idle: '超过 10 分钟没有鼠标或键盘操作', awaiting_input: '等待解锁或恢复后的新操作', unknown: '无法读取系统活动状态' }
  return `${reasons[presence.reason] ?? '活动检测不可用'}：主动搭话与屏幕观察暂停。`
})
const observationText = computed(() => {
  if (!preferences.value.companion.screenObservationEnabled) return '屏幕观察已关闭。'
  const states: Record<string, string> = { idle: '等待符合条件的搭话时机', observing: '正在本机识别前台窗口文字', observed: '已完成一次本机文字识别', excluded: '当前窗口或文字已跳过', unavailable: '本机文字识别暂不可用', paused: '观察已暂停' }
  return states[activityStatus.value?.observation.state ?? 'idle'] ?? '等待符合条件的搭话时机'
})
const commitments = computed(() => director.commitments)

function localDateTimeValue(date: Date) {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function formatDue(value: number) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(value)
}

function commitmentState(status: string, followUpDeliveredAt?: number) {
  if (status === 'completed') return '已完成'
  if (status === 'cancelled') return '已取消'
  if (followUpDeliveredAt) return '已提醒'
  return '待跟进'
}

async function addCommitment() {
  const parsedDueAt = new Date(dueAt.value).getTime()
  saving.value = true
  feedback.value = ''
  try {
    const created = await director.addCommitment(text.value, parsedDueAt)
    if (!created) {
      feedback.value = '请填写不超过 240 字的事项和一个未来时间；最多保留 100 项。'
      return
    }
    text.value = ''
    dueAt.value = ''
    feedback.value = preferences.value.privacy.saveHistory
      ? '已保存本机跟进。'
      : '已添加到当前运行会话；关闭桌面后不会保留。'
  }
  catch (error) {
    feedback.value = error instanceof Error ? `保存失败：${error.message}` : '保存失败，请稍后重试。'
  }
  finally {
    saving.value = false
  }
}

onMounted(() => {
  dueAt.value = localDateTimeValue(new Date(Date.now() + 60 * 60 * 1000))
  void window.meguriDesktop?.companionActivity?.status().then(status => { activityStatus.value = status }).catch(() => {})
  disposeActivity = window.meguriDesktop?.companionActivity?.onChanged(status => { activityStatus.value = status })
})
onUnmounted(() => disposeActivity?.())
</script>

<template>
  <div class="setting-group">
    <label for="companion-initiative"><strong>主动性</strong><small>控制主动搭话和新闻频率；关闭后仍保留你确认的提醒与本机跟进。</small></label>
    <select id="companion-initiative" aria-label="主动性档位" :value="preferences.value.companion.initiative" @change="preferences.updateCompanion({ initiative: ($event.target as HTMLSelectElement).value as 'off' | 'low' | 'normal' | 'high' })">
      <option value="off">关闭</option>
      <option value="low">低</option>
      <option value="normal">普通</option>
      <option value="high">高</option>
    </select>
  </div>

  <p class="settings-catalog-note">最近聊过天后，低、普通、高分别至少空闲 120、45、15 分钟才会尝试搭话；未回复时不会连续催聊，每天最多 3 次。22:00–08:00、专注或语音通话期间保持安静。需允许 AI 对话并保持桌面运行。</p>

  <p class="settings-catalog-note">仅在最近 10 分钟有系统鼠标或键盘操作时触发。你在其他软件里操作不会重置聊天冷却；电脑未休眠但人已离开，也会停止主动搭话、主动新闻播报和屏幕观察。你明确设置的提醒照常保留。</p>
  <p class="settings-catalog-note" role="status">{{ presenceText }}</p>
  <label class="setting-row">
    <span><strong>允许主动观察屏幕文字</strong><small>仅在搭话条件满足时，本机识别当前前台窗口的可见文字，最多 1000 字发给已配置的 AI。截图不保存、不上传；可随时关闭。</small></span>
    <input type="checkbox" :disabled="!remoteChatConsented(preferences.value.privacy)" :checked="preferences.value.companion.screenObservationEnabled" @change="preferences.updateCompanion({ screenObservationEnabled: ($event.target as HTMLInputElement).checked })">
  </label>
  <p class="settings-catalog-note" role="status">{{ observationText }}</p>

  <label class="setting-row">
    <span><strong>专注模式</strong><small>打开后暂缓新闻、提醒和跟进；对话回复照常显示。</small></span>
    <input type="checkbox" :checked="preferences.value.companion.focus === 'focused'" @change="director.setFocus(($event.target as HTMLInputElement).checked ? 'focused' : 'available')">
  </label>
  <label class="setting-row">
    <span><strong>允许表情变化</strong><small>关闭后仍显示消息文字和语音，不触发表情反馈。</small></span>
    <input type="checkbox" :checked="preferences.value.companion.expressionsEnabled" @change="preferences.updateCompanion({ expressionsEnabled: ($event.target as HTMLInputElement).checked })">
  </label>
  <label class="setting-row">
    <span><strong>允许动作</strong><small>关闭后不触发提醒相关动作；系统减少动态效果时也会遵循系统设置。</small></span>
    <input type="checkbox" :checked="preferences.value.companion.motionEnabled" @change="preferences.updateCompanion({ motionEnabled: ($event.target as HTMLInputElement).checked })">
  </label>

  <div class="settings-actions">
    <button type="button" @click="preferences.undoCompanionPreferences()" :disabled="!preferences.previousCompanion">撤销上次表现设置</button>
    <button type="button" @click="preferences.resetCompanionPreferences()">恢复默认表现设置</button>
  </div>

  <section class="companion-commitments" aria-labelledby="companion-commitment-heading">
    <div class="setting-group"><div><strong id="companion-commitment-heading">本机跟进</strong><small>只有你在这里确认的内容才会创建跟进；到期时需要 Meguri 桌面正在运行。</small></div></div>
    <form class="agent-settings" @submit.prevent="addCommitment">
      <label class="agent-field">
        <span><strong>跟进内容</strong><small>不超过 240 字，不会从模型回复中自动提取承诺。</small></span>
        <input v-model.trim="text" type="text" maxlength="240" autocomplete="off" placeholder="例如：周五前整理申请材料">
      </label>
      <label class="agent-field">
        <span><strong>提醒时间</strong><small>使用本机时区。</small></span>
        <input v-model="dueAt" type="datetime-local">
      </label>
      <div class="relay-actions"><button class="primary-action" type="submit" :disabled="saving || !text || !dueAt">{{ saving ? '正在添加…' : '确认本机跟进' }}</button></div>
      <p v-if="feedback" class="relay-feedback" role="status" aria-live="polite">{{ feedback }}</p>
    </form>
    <p v-if="!preferences.value.privacy.saveHistory" class="settings-catalog-note">历史保存已关闭：新增跟进只保存在当前运行会话，关闭桌面后清除。</p>
    <ul v-if="commitments.length" class="companion-commitment-list">
      <li v-for="item in commitments" :key="item.id">
        <div><strong>{{ item.text }}</strong><small>{{ formatDue(item.dueAt) }} · {{ commitmentState(item.status, item.followUpDeliveredAt) }}</small></div>
        <div class="settings-actions">
          <button v-if="item.status === 'pending'" type="button" @click="director.completeCommitment(item.id)">完成</button>
          <button v-if="item.status === 'pending'" type="button" @click="director.cancelCommitment(item.id)">取消</button>
        </div>
      </li>
    </ul>
    <p v-else class="settings-catalog-note">还没有本机跟进。</p>
  </section>
</template>

<style scoped>
.settings-catalog-note { color: var(--dark-text-secondary, #5f6269); font-size: 13px; line-height: 1.65; }
.companion-commitments { display: grid; gap: 12px; margin-top: 18px; }
.companion-commitment-list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.companion-commitment-list li { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px; border: 1px solid var(--border-subtle, rgba(120, 130, 145, .2)); border-radius: 12px; }
.companion-commitment-list li > div:first-child { display: grid; gap: 4px; min-width: 0; }
.companion-commitment-list strong { overflow-wrap: anywhere; }
.companion-commitment-list small { opacity: .72; }
.companion-commitment-list .settings-actions { margin: 0; }
</style>
