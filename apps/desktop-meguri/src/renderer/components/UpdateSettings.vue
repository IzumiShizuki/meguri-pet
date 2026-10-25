<script setup lang="ts">
import type { DesktopUpdateState } from '../../shared/update-contract'
import { onBeforeUnmount, onMounted, ref } from 'vue'
const state = ref<DesktopUpdateState>({ status: 'idle', currentVersion: '', message: '正在读取版本状态。' })
const busy = ref(false)
const feedback = ref('')
let dispose: (() => void) | undefined
onMounted(async () => {
  dispose = window.meguriDesktop?.updates?.onChanged(next => { state.value = next })
  try { state.value = await window.meguriDesktop?.updates?.get() ?? { ...state.value, status: 'unavailable', message: '更新检查接口不可用，请重启桌面应用。' } }
  catch { state.value = { ...state.value, status: 'unavailable', message: '无法读取更新状态，请重试。' } }
})
onBeforeUnmount(() => dispose?.())
async function check() {
  if (busy.value) return
  busy.value = true
  feedback.value = ''
  try { const result = await window.meguriDesktop?.updates?.check(); if (result) state.value = result }
  catch { state.value = { ...state.value, status: 'unavailable', message: '检查更新失败，请检查网络后重试。' } }
  finally { busy.value = false }
}
async function openRelease() {
  try { const result = await window.meguriDesktop?.updates?.openRelease(); if (!result?.ok) feedback.value = result?.error || '当前没有可打开的版本页面。' }
  catch { feedback.value = '无法打开版本页面，请重试。' }
}
</script>

<template>
  <div class="update-settings">
    <div class="update-version"><strong>Meguri Desktop</strong><span>当前版本 {{ state.currentVersion || '未知' }}</span></div>
    <p class="settings-feedback" role="status" aria-live="polite">{{ busy ? '正在向 GitHub 查询最新正式版本…' : state.message }}</p>
    <p v-if="state.latestVersion" class="settings-catalog-note">{{ ['unavailable', 'rate_limited'].includes(state.status) ? '上次查询到的' : '最新' }}正式版本 {{ state.latestVersion }}</p>
    <p v-if="state.checkedAt" class="settings-catalog-note">上次检查 {{ new Date(state.checkedAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }}</p>
    <div class="settings-actions"><button type="button" :disabled="busy" @click="check">{{ busy ? '检查中…' : '检查更新' }}</button><button v-if="state.releaseUrl" type="button" :disabled="busy" @click="openRelease">打开正式版本页面</button></div>
    <p class="settings-catalog-note">版本信息来自 GitHub 的 IzumiShizuki/meguri-pet 仓库。下载和安装由你决定；启动检查开关位于隐私与数据。</p>
    <p v-if="feedback" class="settings-feedback" role="alert">{{ feedback }}</p>
  </div>
</template>
