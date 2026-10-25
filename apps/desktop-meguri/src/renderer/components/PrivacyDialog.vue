<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { nextTick, onMounted, ref } from 'vue'
import { desktopPrivacyPolicy } from '../../shared/privacy-policy'
import { PRIVACY_POLICY_VERSION } from '../../shared/experience-contract'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'

const preferences = useDesktopPreferencesStore()
const { value } = storeToRefs(preferences)
const dialog = ref<HTMLDialogElement>()
const allowChat = ref(false)
const allowMemory = ref(false)
const saveHistory = ref(value.value.privacy.saveHistory)
const checkUpdates = ref(value.value.privacy.checkUpdatesOnStartup)
const saving = ref(false)
const error = ref('')
onMounted(() => void nextTick(() => dialog.value?.showModal()))

async function accept(localOnly = false) {
  if (saving.value) return
  saving.value = true
  error.value = ''
  try {
    await preferences.update({
      privacy: { acceptedPolicyVersion: PRIVACY_POLICY_VERSION, remoteChatAllowed: !localOnly && allowChat.value, saveHistory: saveHistory.value, checkUpdatesOnStartup: !localOnly && checkUpdates.value },
      memory: { enabled: !localOnly && allowChat.value && allowMemory.value },
    })
  }
  catch { error.value = '无法保存你的选择，请重试。' }
  finally { saving.value = false }
}
</script>

<template>
  <dialog ref="dialog" class="privacy-dialog no-drag" data-stage-interactive aria-labelledby="privacy-title" @cancel.prevent>
    <h2 id="privacy-title">先决定 Meguri 如何使用数据</h2>
    <p class="privacy-intro">网络对话会使用配置的 AI 服务。正式记忆由你选择开启，可随时撤回。</p>
    <details class="privacy-policy"><summary>阅读隐私与数据政策 · {{ PRIVACY_POLICY_VERSION }}</summary><section v-for="item in desktopPrivacyPolicy" :key="item.title"><h3>{{ item.title }}</h3><p>{{ item.text }}</p></section></details>
    <form @submit.prevent="accept()">
      <label><input v-model="allowChat" type="checkbox"><span>允许网络对话<small>消息及主动附加的内容会发送给配置的 AI 服务。</small></span></label>
      <label><input v-model="allowMemory" type="checkbox" :disabled="!allowChat"><span>启用正式记忆<small>经身份验证后读取已有记忆，并提交候选和摘要。</small></span></label>
      <label><input v-model="saveHistory" type="checkbox"><span>保存本地对话记录</span></label>
      <label><input v-model="checkUpdates" type="checkbox"><span>允许启动时向 GitHub 检查更新</span></label>
      <p v-if="error" class="settings-feedback" role="alert">{{ error }}</p>
      <div class="settings-actions"><button type="button" :disabled="saving" @click="accept(true)">仅使用本地舞台</button><button type="submit" :disabled="saving">{{ saving ? '正在保存…' : '保存选择' }}</button></div>
    </form>
  </dialog>
</template>
