<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import SkillsWorkspace from '../components/SkillsWorkspace.vue'
import WorkPanel from '../components/WorkPanel.vue'
import { useArtifactStore } from '../stores/artifacts'
import { useConversationStore } from '../stores/conversation'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import { usePresentationStore } from '../stores/presentation'
import { usePresentationSurfaceStore } from '../stores/presentation-surface'
import { useWorkStore } from '../stores/work'
import { resolveSurfacePointerBehavior } from '../utils/stage-surface'
import type { ShizukiReminderEvent } from '../../shared/desktop-contract'
import AmbientStage from './AmbientStage.vue'
import GalgameStage from './GalgameStage.vue'

const presentation = usePresentationStore()
const conversation = useConversationStore()
const artifacts = useArtifactStore()
const preferences = useDesktopPreferencesStore()
const surface = usePresentationSurfaceStore()
const work = useWorkStore()
const ambient = ref<InstanceType<typeof AmbientStage>>()
const skillsOpen = ref(false)
const workOpen = ref(false)
const portraitFaded = ref(false)
const reminderCards = ref<ShizukiReminderEvent[]>([])
const { character } = storeToRefs(presentation)
const { value } = storeToRefs(preferences)

function onKeydown(event: KeyboardEvent) {
  if (event.ctrlKey && event.shiftKey && event.key.toLowerCase() === 'k' && !surface.isGalgame) {
    event.preventDefault()
    skillsOpen.value = !skillsOpen.value
    return
  }
  if (event.ctrlKey && event.shiftKey && event.key.toLowerCase() === 'j' && !surface.isGalgame) {
    event.preventDefault()
    workOpen.value = !workOpen.value
  }
}

const interactiveSelector = [
  '.chat-dock',
  '.controls-island',
  '.skills-workspace',
  '.work-panel',
  '.artifact-stack',
  '.shizuki-reminder-stack',
  'button',
  'a',
  'input',
  'textarea',
  'select',
  '[data-stage-interactive]',
].join(', ')

let lastClickThrough: boolean | undefined
let lastPointer: { x: number, y: number } | undefined
let pointerPollTimer: ReturnType<typeof setInterval> | undefined
let pointerPollPending = false

function setClickThrough(ignore: boolean) {
  if (lastClickThrough === ignore)
    return
  lastClickThrough = ignore
  const request = window.meguriDesktop?.window.setClickThrough(ignore)
  if (request) {
    void request.catch(() => {
      lastClickThrough = undefined
    })
  }
}

function updatePointerBehavior(x: number, y: number) {
  lastPointer = { x, y }
  const target = document.elementFromPoint(x, y)
  const behavior = resolveSurfacePointerBehavior(surface.surface, {
    enabled: value.value.window.alwaysOnTop && value.value.window.fadeOnHover,
    x,
    y,
    viewportWidth: window.innerWidth,
    viewportHeight: window.innerHeight,
    portraitBounds: ambient.value?.portraitBounds(),
    interactive: Boolean(target?.closest(interactiveSelector)),
  })
  portraitFaded.value = behavior.fadePortrait
  setClickThrough(behavior.clickThrough)
}

function onMouseMove(event: MouseEvent) {
  if (!surface.isGalgame)
    updatePointerBehavior(event.clientX, event.clientY)
}

function onMouseLeave() {
  if (surface.isGalgame)
    return
  portraitFaded.value = false
  if (value.value.window.alwaysOnTop && value.value.window.fadeOnHover)
    setClickThrough(true)
}

async function pollPointerPosition() {
  if (surface.isGalgame || pointerPollPending)
    return
  pointerPollPending = true
  try {
    const point = await window.meguriDesktop?.window.pointerPosition()
    if (point
      && point.x >= 0
      && point.y >= 0
      && point.x <= window.innerWidth
      && point.y <= window.innerHeight)
      updatePointerBehavior(point.x, point.y)
  }
  finally {
    pointerPollPending = false
  }
}

function startPointerPolling() {
  if (!pointerPollTimer)
    pointerPollTimer = setInterval(() => void pollPointerPosition(), 80)
}

function stopPointerPolling() {
  clearInterval(pointerPollTimer)
  pointerPollTimer = undefined
}

function restoreInteraction() {
  portraitFaded.value = false
  setClickThrough(false)
}

async function syncSurfacePointerBehavior() {
  if (surface.isGalgame) {
    stopPointerPolling()
    restoreInteraction()
    return
  }
  startPointerPolling()
  await nextTick()
  if (lastPointer)
    updatePointerBehavior(lastPointer.x, lastPointer.y)
  else
    restoreInteraction()
}

async function refreshPresentationSnapshot() {
  try {
    const snapshot = await window.meguriDesktop?.characterPresentation?.getActive()
    if (!snapshot)
      return
    surface.setResourceSnapshot({
      revision: snapshot.revision,
      packageId: snapshot.packageId,
      spriteDataUrl: snapshot.sprite?.dataUrl,
      backgroundDataUrl: snapshot.background?.dataUrl,
    })
  }
  catch {
    // The built-in presentation remains available when a local pack cannot load.
  }
}

async function enterGalgame() {
  skillsOpen.value = false
  workOpen.value = false
  setClickThrough(false)
  if (!conversation.sending)
    await refreshPresentationSnapshot()
  surface.enterGalgame()
}

function exitGalgame() {
  surface.exitGalgame()
}

function openAmbientControls() {
  if (!surface.isGalgame)
    ambient.value?.openControls()
}

function onReminder(reminder: ShizukiReminderEvent) {
  if (!reminder?.todoId || !reminder.title) return
  const alreadyShown = reminderCards.value.some(card => card.todoId === reminder.todoId && card.triggerAt === reminder.triggerAt)
  reminderCards.value = [
    reminder,
    ...reminderCards.value.filter(card => !(card.todoId === reminder.todoId && card.triggerAt === reminder.triggerAt)),
  ].slice(0, 3)
  if (reminder.mainAccount && !alreadyShown)
    void conversation.playSpeech(`提醒你，${reminder.title.slice(0, 100)}。`, 'neutral', 'medium')
}

function dismissReminder(reminder: ShizukiReminderEvent) {
  reminderCards.value = reminderCards.value.filter(card => card !== reminder)
}

const disposeShizukiReminder = window.meguriDesktop?.shizuki?.onReminder(onReminder) ?? (() => {})

watch(() => value.value.character.outfitCode, outfitCode => presentation.selectOutfit(outfitCode), { immediate: true })
watch(() => character.value.spriteUrl, (spriteUrl) => {
  if (!surface.resourceSnapshot.packageId)
    surface.setResourceSnapshot({ revision: `builtin:${spriteUrl}` })
}, { immediate: true })
watch(() => surface.surface, () => void syncSurfacePointerBehavior())
watch(
  () => [value.value.window.alwaysOnTop, value.value.window.fadeOnHover] as const,
  ([alwaysOnTop, fadeOnHover]) => {
    if (surface.isGalgame || !alwaysOnTop || !fadeOnHover)
      restoreInteraction()
    else if (lastPointer)
      updatePointerBehavior(lastPointer.x, lastPointer.y)
  },
)

onMounted(async () => {
  await preferences.initialize()
  presentation.selectOutfit(value.value.character.outfitCode)
  await refreshPresentationSnapshot()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('mousemove', onMouseMove)
  document.documentElement.addEventListener('mouseleave', onMouseLeave)
  startPointerPolling()
  restoreInteraction()
  artifacts.start(conversation.sessionId)
  void work.start()
  await window.meguriDesktop?.readiness.rendererReady()
})

onBeforeUnmount(() => {
  disposeShizukiReminder()
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('mousemove', onMouseMove)
  document.documentElement.removeEventListener('mouseleave', onMouseLeave)
  stopPointerPolling()
  restoreInteraction()
  preferences.dispose()
  artifacts.stop()
  work.stop()
})
</script>

<template>
  <main
    class="stage-shell"
    :class="{
      'reduced-motion': value.character.reducedMotion,
      'chat-visible': value.chat.visible,
      'chat-expanded': value.chat.expanded,
      'galgame-visible': surface.isGalgame,
    }"
    @contextmenu.prevent="openAmbientControls"
  >
    <AmbientStage
      v-if="!surface.isGalgame"
      ref="ambient"
      :portrait-faded="portraitFaded"
      @galgame="enterGalgame"
      @skills="skillsOpen = true"
      @work="workOpen = true"
    />
    <GalgameStage v-else @exit="exitGalgame" />
    <div v-if="reminderCards.length" class="shizuki-reminder-stack no-drag" aria-live="assertive">
      <article v-for="reminder in reminderCards" :key="`${reminder.todoId}:${reminder.triggerAt}`" class="shizuki-reminder-card">
        <div><strong>{{ reminder.late ? '补发提醒' : '到点提醒' }}</strong><button type="button" :aria-label="`关闭 ${reminder.title} 提醒`" @click="dismissReminder(reminder)">×</button></div>
        <p>{{ reminder.title }}</p>
        <small>{{ new Date(reminder.triggerAt).toLocaleString('zh-CN') }}</small>
      </article>
    </div>
    <Transition name="skills-sheet">
      <SkillsWorkspace
        v-if="skillsOpen && !surface.isGalgame"
        :session-id="conversation.sessionId"
        @close="skillsOpen = false"
      />
    </Transition>
    <Transition name="skills-sheet">
      <WorkPanel
        v-if="workOpen && !surface.isGalgame"
        @close="workOpen = false"
      />
    </Transition>
  </main>
</template>
