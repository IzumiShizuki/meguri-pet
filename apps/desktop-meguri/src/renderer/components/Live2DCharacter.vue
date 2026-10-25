<script setup lang="ts">
import type { Component } from 'vue'
import type { Live2DActive } from '../../shared/live2d-contract'
import type { CharacterPresentation } from '../utils/character-presentation'
import { markRaw, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { playSpeech, speechPlaybackState } from '../services/speech-playback'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import { usePresentationStore } from '../stores/presentation'

const props = withDefaults(defineProps<{ character: CharacterPresentation, speaking: boolean, surface?: 'ambient' | 'galgame' }>(), { surface: 'ambient' })
const emit = defineEmits<{ fallback: [error: string] }>()
const preferences = useDesktopPreferencesStore()
const presentation = usePresentationStore()
const Scene = shallowRef<Component>()
const model = ref<Live2DActive | null>(null)
const pointer = ref<{ x: number, y: number, space: 'client' }>()
const error = ref('')
const scene = ref<{ setMotion(name: string, index?: number): Promise<unknown>, setExpression(name: string): Promise<unknown>, hitTest(x: number, y: number): string[] }>()
let pointerTimer: ReturnType<typeof setInterval> | undefined
let generation = 0
let lastTouch = 0
function fail(reason: unknown) {
  const detail = reason instanceof Error ? reason.message : String(reason)
  error.value = `Live2D 暂时不可用，已显示 PNG：${detail.slice(0, 180)}`
  emit('fallback', error.value)
}
async function loadCore() {
  if ('Live2DCubismCore' in window) return
  await new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = new URL('meguri-cubism-core.js', document.baseURI).href
    script.onload = () => resolve()
    script.onerror = () => reject(new Error('Cubism Core 未加载；检查 AIRI 安装与构建配置。'))
    document.head.appendChild(script)
  })
}
async function refresh() {
  const current = ++generation
  error.value = ''
  model.value = null
  if (preferences.value.character.renderer !== 'live2d') return
  try {
    const active = await window.meguriDesktop?.live2d?.active()
    if (current !== generation) return
    if (!active) throw new Error('请先导入并启用 Live2D 模型。')
    await loadCore()
    const module = await import('virtual:meguri-live2d')
    if (current !== generation) return
    if (!module.default) throw new Error('本地 AIRI Live2D 运行时不可用。')
    Scene.value = markRaw(module.default)
    model.value = active
  }
  catch (reason) { if (current === generation) fail(reason) }
}
function onPointer(event: PointerEvent) { pointer.value = { x: event.clientX, y: event.clientY, space: 'client' } }
async function touch(event: PointerEvent) {
  if (preferences.value.character.reducedMotion || Date.now() - lastTouch < 1500) return
  const areas = scene.value?.hitTest(event.clientX, event.clientY) ?? []
  if (!areas.length || !model.value) return
  lastTouch = Date.now()
  const area = areas[0]
  const reaction = model.value.reactions[area]
  const defaultMotion = model.value.motions.find(name => name.toLowerCase().replaceAll('_', '').includes(`tap${area.toLowerCase()}`))
  if (reaction?.motion || defaultMotion) await scene.value?.setMotion(reaction?.motion ?? defaultMotion!)
  if (reaction?.expression) await scene.value?.setExpression(reaction.expression)
  if (reaction?.speech) {
    await playSpeech({ text: reaction.speech, voiceStyle: 'soft', intensity: 'low', preferences: preferences.value.speech, surface: props.surface, onSpeaking: value => presentation.setSpeaking(value) })
  }
}
watch([
  () => preferences.value.character.renderer,
  () => preferences.value.character.live2dModelId,
], refresh)
watch([() => props.character.motionTag, () => presentation.motionRevision], async ([motion]) => {
  if (!motion || preferences.value.character.reducedMotion) return
  const normalize = (value: string) => value.toLowerCase().replace(/[^a-z0-9]/g, '')
  const name = model.value?.motions.find(name => normalize(name) === normalize(motion))
  if (name) await scene.value?.setMotion(name)
})
watch(() => props.character.expressionTag, async (expression) => {
  const name = model.value?.expressions.find(name => name.toLowerCase() === expression.toLowerCase())
  if (name) await scene.value?.setExpression(name)
})
onMounted(() => {
  void refresh()
  window.addEventListener('pointermove', onPointer)
  pointerTimer = setInterval(async () => {
    if (preferences.value.character.reducedMotion || props.surface === 'galgame') return
    const point = await window.meguriDesktop?.window.pointerPosition()
    if (point) pointer.value = { ...point, space: 'client' }
  }, 100)
})
onBeforeUnmount(() => { generation++; clearInterval(pointerTimer); window.removeEventListener('pointermove', onPointer) })
</script>

<template>
  <div class="live2d-character" :data-stage-interactive="model && !error ? '' : undefined" @pointerup="touch">
    <component :is="Scene" v-if="Scene && model && !error" ref="scene" :model-src="model.modelUrl" :model-id="model.id" :cursor-position="preferences.value.character.reducedMotion ? undefined : pointer" :paused="preferences.value.character.reducedMotion" :mouth-open-size="speechPlaybackState.mouthOpen" :now-speaking="speaking || speechPlaybackState.speaking" @error="fail" />
    <img v-else :src="character.spriteUrl" alt="Meguri" draggable="false">
    <p v-if="error" class="live2d-fallback-status" role="status">{{ error }}</p>
  </div>
</template>

<style scoped>
.live2d-character { position: relative; width: 100%; height: 100%; pointer-events: auto; }
.live2d-character > img { width: 100%; height: 100%; object-fit: contain; }
.live2d-fallback-status { position: absolute; bottom: 0; left: 10%; width: 80%; padding: 8px; border-radius: 8px; background: var(--panel-bg, #fff); color: var(--text-primary, #293044); font-size: 12px; pointer-events: none; }
</style>
