<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { Live2DCatalog, Live2DResult } from '../../shared/live2d-contract'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'

const preferences = useDesktopPreferencesStore()
const catalog = ref<Live2DCatalog>({ version: 1, models: [] })
const busy = ref(false)
const message = ref('')
function bridge() {
  const value = window.meguriDesktop?.live2d
  if (!value) throw new Error('Live2D 桥接尚未就绪。')
  return value
}
async function refresh() {
  try { catalog.value = await bridge().catalog() }
  catch { message.value = '无法读取 Live2D 模型目录。' }
}
async function run(operation: () => Promise<Live2DResult>, renderer?: 'png' | 'live2d') {
  busy.value = true
  message.value = ''
  try {
    const result = await operation()
    if (!result.ok) { message.value = result.error ?? 'Live2D 操作失败。'; return }
    if (result.catalog) catalog.value = result.catalog
    if (renderer) await preferences.update({ character: { renderer, live2dModelId: catalog.value.activeId ?? '' } })
    message.value = 'Live2D 模型已更新。'
  }
  catch { message.value = 'Live2D 操作失败，请重试。' }
  finally { busy.value = false }
}
onMounted(refresh)
</script>

<template>
  <section aria-label="Live2D 模型管理">
    <h3>Live2D 角色</h3>
    <p class="section-intro">导入你有权使用的 Cubism .model3.json 模型目录或 ZIP。角色模型由你提供；运行时复用本地 AIRI 和 Cubism Core。</p>
    <div class="settings-actions">
      <button type="button" :disabled="busy" @click="run(() => bridge().importDirectory())">导入模型目录</button>
      <button type="button" :disabled="busy" @click="run(() => bridge().importArchive())">导入 ZIP</button>
      <button type="button" :disabled="busy" @click="preferences.update({ character: { renderer: 'png' } })">使用 PNG 立绘</button>
      <button type="button" :disabled="busy" @click="refresh">刷新</button>
    </div>
    <p v-if="!catalog.models.length">还没有导入模型。PNG 立绘随时可用。</p>
    <article v-for="model in catalog.models" :key="model.id" class="setting-row">
      <span><strong>{{ model.name }}{{ catalog.activeId === model.id ? ' · 已激活' : '' }}</strong><small>{{ model.motions.length }} 组动作 · {{ model.expressions.length }} 个表情 · {{ model.hitAreas.length }} 个触摸区域</small><small v-if="model.license">资源许可：{{ model.license }}</small></span>
      <button type="button" :disabled="busy" @click="run(() => bridge().activate(model.id), 'live2d')">使用</button>
      <button type="button" :disabled="busy" @click="run(() => bridge().remove(model.id), catalog.activeId === model.id ? 'png' : undefined)">移除</button>
    </article>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>
