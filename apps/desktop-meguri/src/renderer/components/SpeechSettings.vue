<script setup lang="ts">
import type { SpeechResourceCatalog, SpeechResourceResult } from '../../shared/speech-resource-contract'
import { onMounted, ref } from 'vue'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'

const preferences = useDesktopPreferencesStore()
const catalog = ref<SpeechResourceCatalog>({ engine: 'GPT-SoVITS', ready: false, activeId: 'default', profiles: [] })
const url = ref('')
const busy = ref(false)
const message = ref('')
function bridge() {
  const value = window.meguriDesktop?.speechResources
  if (!value) throw new Error('本地语音桥接尚未就绪。')
  return value
}
async function refresh() {
  try { catalog.value = await bridge().list() }
  catch { message.value = '无法连接本地语音服务。' }
}
async function run(operation: () => Promise<SpeechResourceResult>) {
  busy.value = true
  message.value = ''
  try {
    const result = await operation()
    if (result.catalog) catalog.value = result.catalog
    message.value = result.ok ? '语音资源已更新。' : result.error ?? '语音资源操作失败。'
  }
  catch { message.value = '语音资源操作失败，请重试。' }
  finally { busy.value = false }
}
onMounted(refresh)
</script>

<template>
  <section aria-label="本地语音资源">
    <h3>本地语音模型</h3>
    <p class="section-intro">{{ catalog.engine }} · {{ catalog.ready ? '服务已就绪' : '服务未就绪' }}。支持带 GPT 权重、SoVITS 权重和参考音频的完整 profile；不兼容移动端 VITS/ONNX 包。</p>
    <p v-if="catalog.error" role="status">{{ catalog.error }}</p>
    <label class="setting-slider"><span><strong>语速</strong><small>{{ preferences.value.speech.speed.toFixed(1) }}×</small></span><input type="range" min="0.5" max="2" step="0.1" :value="preferences.value.speech.speed" @input="preferences.update({ speech: { speed: Number(($event.target as HTMLInputElement).value) } })"></label>
    <label class="setting-row"><span><strong>桌宠模式语音</strong><small>桌宠回复、触摸反馈和桌宠模式提醒。</small></span><input type="checkbox" :checked="preferences.value.speech.ambientEnabled" @change="preferences.update({ speech: { ambientEnabled: ($event.target as HTMLInputElement).checked } })"></label>
    <label class="setting-row"><span><strong>Galgame 模式语音</strong><small>独立控制 Galgame 回复和提醒。</small></span><input type="checkbox" :checked="preferences.value.speech.galgameEnabled" @change="preferences.update({ speech: { galgameEnabled: ($event.target as HTMLInputElement).checked } })"></label>
    <div class="settings-actions"><button type="button" :disabled="busy" @click="run(() => bridge().importPackage())">导入语音包目录</button><button type="button" :disabled="busy" @click="refresh">刷新状态</button></div>
    <label class="setting-row"><span><strong>HTTPS 下载清单</strong><small>从你选择的 HTTPS 清单下载并核验 SHA-256；下载完成后选择激活。</small></span><input v-model="url" type="url" placeholder="https://…/voice-profile.json" :disabled="busy"></label>
    <button type="button" :disabled="busy || !url.startsWith('https://')" @click="run(() => bridge().download(url))">下载语音包</button>
    <article v-for="profile in catalog.profiles" :key="profile.id" class="setting-row">
      <span><strong>{{ profile.name }}{{ profile.active ? ' · 当前语音' : '' }}</strong><small>{{ profile.modelVersion }} · {{ profile.language }} · {{ profile.available ? '资源可用' : '资源缺失' }}{{ profile.readOnly ? ' · 预装只读' : '' }}</small></span>
      <button type="button" :disabled="busy || !profile.available || profile.active" @click="run(() => bridge().activate(profile.id))">激活</button>
      <button v-if="!profile.readOnly" type="button" :disabled="busy || profile.active" @click="run(() => bridge().remove(profile.id))">移除</button>
    </article>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>
