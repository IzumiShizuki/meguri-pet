<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { ref } from 'vue'
import { desktopPrivacyPolicy } from '../../shared/privacy-policy'
import { PRIVACY_POLICY_VERSION } from '../../shared/experience-contract'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import { useConversationStore } from '../stores/conversation'

const preferences = useDesktopPreferencesStore()
const conversation = useConversationStore()
const { value } = storeToRefs(preferences)
const feedback = ref('')
const exporting = ref(false)
const confirmClear = ref(false)
async function setRemoteChat(enabled: boolean) {
  await preferences.update({ privacy: { remoteChatAllowed: enabled }, ...(!enabled ? { memory: { enabled: false }, training: { enabled: false } } : {}) })
  feedback.value = enabled ? '已允许后续网络对话。' : '已停止后续网络对话和正式记忆请求。'
}
async function exportDiagnostics() {
  if (exporting.value) return
  exporting.value = true
  feedback.value = ''
  try {
    const result = await window.meguriDesktop?.diagnostics?.export()
    feedback.value = result?.ok ? '诊断文件已保存到你选择的位置。' : result?.error || (result ? '已取消导出。' : '诊断导出接口不可用，请重启桌面应用。')
  }
  catch { feedback.value = '诊断导出失败，请检查目标目录是否可写。' }
  finally { exporting.value = false }
}
function clearHistory() {
  conversation.clearHistory()
  confirmClear.value = false
  feedback.value = '已清除本机对话记录；正式记忆可在记忆设置里管理。'
}
</script>

<template>
  <div class="privacy-settings">
    <label class="setting-row"><span><strong>允许网络对话</strong><small>经本机 Core 将消息发往配置的 AI 服务，关闭后停止后续请求。</small></span><input type="checkbox" :checked="value.privacy.remoteChatAllowed" @change="setRemoteChat(($event.target as HTMLInputElement).checked)"></label>
    <label class="setting-row"><span><strong>允许正式记忆</strong><small>授权当前身份读取记忆、提交候选与摘要；关闭不会删除已有记忆。</small></span><input type="checkbox" :checked="value.memory.enabled" :disabled="!value.privacy.remoteChatAllowed" @change="preferences.update({ memory: { enabled: ($event.target as HTMLInputElement).checked } })"></label>
    <label class="setting-row"><span><strong>保存本地对话记录</strong><small>关闭后停止新增持久记录，旧记录保留至主动清除。</small></span><input type="checkbox" :checked="value.privacy.saveHistory" @change="preferences.update({ privacy: { saveHistory: ($event.target as HTMLInputElement).checked } })"></label>
    <label class="setting-row"><span><strong>启动时检查更新</strong><small>允许每次启动静默向 GitHub 查询一次正式版本。</small></span><input type="checkbox" :checked="value.privacy.checkUpdatesOnStartup" @change="preferences.update({ privacy: { checkUpdatesOnStartup: ($event.target as HTMLInputElement).checked } })"></label>
    <details class="privacy-policy" open>
      <summary>隐私与数据政策 · {{ PRIVACY_POLICY_VERSION }}</summary>
      <section v-for="item in desktopPrivacyPolicy" :key="item.title"><h3>{{ item.title }}</h3><p>{{ item.text }}</p></section>
    </details>
    <div class="settings-actions"><button type="button" :disabled="exporting" @click="exportDiagnostics">{{ exporting ? '正在导出…' : '导出脱敏诊断' }}</button><button type="button" :disabled="conversation.sending" @click="confirmClear = !confirmClear">清除本地对话记录</button></div>
    <div v-if="confirmClear" class="privacy-clear-confirm" role="alert"><p>清除本机保存的对话和当前历史？此操作无法撤销。</p><div class="settings-actions"><button type="button" @click="confirmClear = false">保留记录</button><button type="button" :disabled="conversation.sending" @click="clearHistory">确认清除</button></div></div>
    <p v-if="feedback" class="settings-feedback" role="status" aria-live="polite">{{ feedback }}</p>
  </div>
</template>
