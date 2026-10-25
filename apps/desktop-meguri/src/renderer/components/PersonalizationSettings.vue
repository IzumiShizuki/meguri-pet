<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { ref } from 'vue'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'

const preferences = useDesktopPreferencesStore()
const { value } = storeToRefs(preferences)
const busy = ref(false)
const feedback = ref('')
const palettes = [
  { id: 'system', label: '系统强调色', color: '#0078d4' },
  { id: 'amber', label: '琥珀', color: '#b45309' },
  { id: 'rose', label: '蔷薇', color: '#be185d' },
  { id: 'mint', label: '薄荷', color: '#047857' },
  { id: 'blue', label: '晴空', color: '#0369a1' },
  { id: 'violet', label: '紫藤', color: '#7c3aed' },
] as const

async function selectWallpaper() {
  if (busy.value) return
  busy.value = true
  feedback.value = ''
  try {
    const result = await window.meguriDesktop?.wallpaper?.select()
    if (result?.ok && result.dataUrl) {
      await preferences.update({ chat: { wallpaperDataUrl: result.dataUrl } })
      feedback.value = '壁纸已应用到聊天坞和 Galgame 对话。'
    }
    else if (result?.error) feedback.value = result.error
    else if (!result) feedback.value = '壁纸选择接口不可用，请重启桌面应用。'
  }
  catch { feedback.value = '无法读取壁纸，请选择有效的 PNG、JPEG 或 WebP 图片后重试。' }
  finally { busy.value = false }
}
</script>

<template>
  <div class="personalization-settings">
    <fieldset class="palette-settings">
      <legend>对话配色</legend>
      <div class="palette-options">
        <button v-for="palette in palettes" :key="palette.id" type="button" :aria-pressed="value.chat.colorPalette === palette.id" :class="{ active: value.chat.colorPalette === palette.id }" @click="preferences.update({ chat: { colorPalette: palette.id } })">
          <span :style="{ background: palette.id === 'system' ? value.systemAccentColor || palette.color : palette.color }" aria-hidden="true" />{{ palette.label }}
        </button>
      </div>
      <p class="settings-catalog-note">系统强调色不可用时使用晴空配色；跟随系统、浅色与深色模式可在外观里选择。</p>
    </fieldset>
    <div class="wallpaper-preview" :style="value.chat.wallpaperDataUrl ? { backgroundImage: `url('${value.chat.wallpaperDataUrl}')` } : undefined" aria-label="聊天外观预览">
      <span class="wallpaper-preview-blur" :style="{ backdropFilter: `blur(${value.chat.wallpaperBlur * .24}px)` }" aria-hidden="true" />
      <p>Meguri 在这里。<br>预览聊天壁纸与气泡。</p>
    </div>
    <div class="settings-actions">
      <button type="button" :disabled="busy" @click="selectWallpaper">{{ busy ? '正在读取…' : '选择本地壁纸' }}</button>
      <button type="button" :disabled="!value.chat.wallpaperDataUrl || busy" @click="preferences.update({ chat: { wallpaperDataUrl: '', wallpaperBlur: 0 } })">恢复默认背景</button>
    </div>
    <label class="setting-row"><span><strong>壁纸模糊 · {{ value.chat.wallpaperBlur }}%</strong><small>实时应用到聊天背景，不改变桌宠周边透明区域。</small></span><input type="range" min="0" max="100" step="1" aria-label="壁纸模糊百分比" :value="value.chat.wallpaperBlur" @input="preferences.update({ chat: { wallpaperBlur: Number(($event.target as HTMLInputElement).value) } })"></label>
    <label class="setting-row"><span><strong>气泡不透明度 · {{ Math.round(value.chat.bubbleOpacity * 100) }}%</strong><small>20%–100%，文字始终保持清晰。</small></span><input type="range" min="20" max="100" step="1" aria-label="气泡不透明度百分比" :value="value.chat.bubbleOpacity * 100" @input="preferences.update({ chat: { bubbleOpacity: Number(($event.target as HTMLInputElement).value) / 100 } })"></label>
    <p v-if="feedback" class="settings-feedback" role="status" aria-live="polite">{{ feedback }}</p>
  </div>
</template>
