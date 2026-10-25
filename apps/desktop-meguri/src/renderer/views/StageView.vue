<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import SkillsWorkspace from '../components/SkillsWorkspace.vue'
import WorkPanel from '../components/WorkPanel.vue'
import PrivacyDialog from '../components/PrivacyDialog.vue'
import { PRIVACY_POLICY_VERSION } from '../../shared/experience-contract'
import type { DesktopUpdateState } from '../../shared/update-contract'
import type { CompanionActivityStatus } from '../../shared/companion-contract'
import { useArtifactStore } from '../stores/artifacts'
import { useConversationStore } from '../stores/conversation'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import { usePresentationStore } from '../stores/presentation'
import { usePresentationSurfaceStore } from '../stores/presentation-surface'
import { useCompanionDirectorStore } from '../stores/companion-director'
import { deliverCompanionNotice, registerReminderPresenter } from '../services/companion-notice-delivery'
import { speechPlaybackState } from '../services/speech-playback'
import { remoteChatConsented } from '../../shared/experience-contract'
import { acknowledgeReachOut, createCompanionReachOutScheduler, requestReachOut } from '../services/companion-reach-outs'
import { reachOutDeliveryCount } from '../services/playback-ledger'
import { useWorkStore } from '../stores/work'
import { resolveSurfacePointerBehavior } from '../utils/stage-surface'
import type { ShizukiReminderEvent } from '../../shared/desktop-contract'
import { ambientPortrait, ambientPortraitCenter, ambientSideBubbleMinWidth, compactStageSize } from '../../shared/stage-geometry.mjs'
import AmbientStage from './AmbientStage.vue'
import GalgameStage from './GalgameStage.vue'

const presentation = usePresentationStore()
const conversation = useConversationStore()
const artifacts = useArtifactStore()
const preferences = useDesktopPreferencesStore()
const surface = usePresentationSurfaceStore()
const director = useCompanionDirectorStore()
const work = useWorkStore()
const ambient = ref<InstanceType<typeof AmbientStage>>()
const skillsOpen = ref(false)
const workOpen = ref(false)
const chatPanelOpen = ref(false)
const viewportWidth = ref(window.innerWidth)
const viewportObserver = new ResizeObserver(entries => {
  viewportWidth.value = entries[0]?.contentRect.width ?? window.innerWidth
})
const portraitFaded = ref(false)
const reminderCards = ref<ShizukiReminderEvent[]>([])
const updateState = ref<DesktopUpdateState>()
const activityStatus = ref<CompanionActivityStatus>()
let disposeActivity: (() => void) | undefined
async function refreshActivity() {
  try { activityStatus.value = await window.meguriDesktop?.companionActivity?.status() }
  catch { activityStatus.value = undefined }
}
const updateDismissed = ref(false)
let disposeUpdates: (() => void) | undefined
const { character } = storeToRefs(presentation)
const { value } = storeToRefs(preferences)
const reachOuts = createCompanionReachOutScheduler({
  getState: () => ({
    userId: conversation.userId, sessionId: conversation.sessionId,
    initiative: value.value.companion.initiative,
    allowed: remoteChatConsented(value.value.privacy),
    available: value.value.companion.focus === 'available' && !conversation.sending
      && !director.context.voiceActive && !presentation.speaking && !speechPlaybackState.speaking && !speechPlaybackState.busy,
    visible: document.visibilityState === 'visible' && value.value.chat.visible && !skillsOpen.value && !workOpen.value,
    editing: Boolean(document.activeElement?.matches('input, textarea, [contenteditable="true"]'))
      || Boolean(document.querySelector<HTMLTextAreaElement>('.composer textarea')?.value.trim()),
    lastDeliveredAt: director.lastDeliveredAt,
    dailyReachOutCount: reachOutDeliveryCount(),
    messages: conversation.messages,
    userActiveUntil: activityStatus.value?.presence.active ? activityStatus.value.presence.activeUntil : 0,
    screenObservationEnabled: value.value.companion.screenObservationEnabled,
  }),
  refreshPresence: refreshActivity,
  generate: requestReachOut,
  deliver: (candidate, isCurrent) => {
    const notice = { ...candidate, kind: 'reach_out' as const, surface: surface.surface }
    return deliverCompanionNotice(notice, director.evaluateNotice(notice), isCurrent)
  },
  acknowledge: acknowledgeReachOut,
})
watch(() => [conversation.sessionId, conversation.sending, value.value.privacy.remoteChatAllowed,
  value.value.privacy.acceptedPolicyVersion, value.value.companion.initiative, value.value.companion.focus,
  director.context.voiceActive, presentation.speaking, speechPlaybackState.speaking, speechPlaybackState.busy],
() => reachOuts.contextChanged(), { flush: 'sync' })
watch(() => [activityStatus.value?.presence.active, activityStatus.value?.presence.activeUntil], () => {
  void director.setRuntimeContext({ userActiveUntil: activityStatus.value?.presence.active ? activityStatus.value.presence.activeUntil : 0 })
  reachOuts.contextChanged()
}, { flush: 'sync' })
watch(() => value.value.companion.screenObservationEnabled, () => reachOuts.activity(), { flush: 'sync' })
const disposeNoticeDelivery = director.onDelivery(({ notice, decision }) => deliverCompanionNotice(notice, decision))
const disposeReminderPresenter = registerReminderPresenter((notice) => {
  const reminder = notice.reminder
  if (!reminder?.todoId || !reminder.title) return false
  reminderCards.value = [
    { ...reminder, dueAt: new Date(reminder.triggerAt).toISOString(), late: Boolean(reminder.late) },
    ...reminderCards.value.filter(card => !(card.todoId === reminder.todoId && card.triggerAt === reminder.triggerAt)),
  ].slice(0, 3)
  return true
})
// Only full workspaces change the native window geometry. Chat stays in the
// compact pet window so it can behave like an overlay bubble.
const largeBubbleOpen = computed(() => skillsOpen.value || workOpen.value
  || (chatPanelOpen.value && !value.value.chat.expanded))
const nativeSurface = computed(() => surface.isGalgame ? 'galgame' : largeBubbleOpen.value ? 'ambient-panel' : 'ambient')

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && (skillsOpen.value || workOpen.value)) {
    skillsOpen.value = false
    workOpen.value = false
    return
  }
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
    if (point && Number.isFinite(point.x) && Number.isFinite(point.y))
      updatePointerBehavior(point.x, point.y)
    else
      restoreInteraction()
  }
  catch {
    // Native hit-test/leave delivery is unreliable while the window is transparent.
    restoreInteraction()
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
  lastPointer = undefined
  portraitFaded.value = false
  setClickThrough(false)
}

function onWindowResize() {
  viewportWidth.value = window.innerWidth
  // A resized/moved host invalidates cached window-local pointer coordinates.
  lastPointer = undefined
  lastClickThrough = undefined
  restoreInteraction()
  void nextTick().then(() => pollPointerPosition())
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
  if (!await fitPresentationSurface('galgame')) return
  surface.enterGalgame()
}

async function exitGalgame() {
  if (!await fitPresentationSurface('ambient')) return
  surface.exitGalgame()
}

async function fitPresentationSurface(next: 'ambient' | 'ambient-panel' | 'galgame') {
  try {
    const result = await window.meguriDesktop?.window.setPresentationSurface?.(next)
    if (result && !result.ok) {
      presentation.setStatus(result.error ?? '舞台切换未完成，请重试。', 'error')
      return false
    }
    return true
  }
  catch {
    presentation.setStatus('请重启桌面应用以加载窗口布局更新。', 'error')
    return false
  }
}

function openUpdateSettings() {
  void window.meguriDesktop?.window.openSettings()
}

function openAmbientControls() {
  if (!surface.isGalgame)
    ambient.value?.openControls()
}

function onReminder(reminder: ShizukiReminderEvent) {
  if (!reminder?.todoId || !reminder.title) return
  const createdAt = Number(reminder.triggerAt) || Date.now()
  const notice = {
    id: `reminder:${reminder.todoId}:${createdAt}`,
    kind: 'reminder' as const,
    text: `提醒你，${reminder.title.slice(0, 100)}。`,
    createdAt,
    expiresAt: Math.max(Date.now() + 60 * 60 * 1000, createdAt + 24 * 60 * 60 * 1000),
    surface: surface.surface,
    reminder: {
      todoId: Number(reminder.todoId),
      title: reminder.title.slice(0, 100),
      triggerAt: createdAt,
      mainAccount: Boolean(reminder.mainAccount),
      late: Boolean(reminder.late),
    },
  }
  const decision = director.enqueueNotice(notice)
  if (decision.action === 'deliver')
    void deliverCompanionNotice(notice, decision)
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
  () => [conversation.sending, presentation.speaking, speechPlaybackState.speaking, speechPlaybackState.busy,
    surface.surface, value.value.companion.focus] as const,
  ([activeTurn, speaking, audioSpeaking, audioBusy, activeSurface, focus]) => {
    void director.setRuntimeContext({ activeTurn, speaking: speaking || audioSpeaking || audioBusy,
      surface: activeSurface, focus })
  },
  { immediate: true },
)
// Child panel events settle after preference updates; fit only the final surface.
// Otherwise collapsing history can briefly open a native panel before closing it.
watch([nativeSurface, () => value.value.chat.expanded], ([next]) => {
  void fitPresentationSurface(next)
}, { flush: 'post' })
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
  await refreshActivity()
  disposeActivity = window.meguriDesktop?.companionActivity?.onChanged(status => {
    activityStatus.value = status
    reachOuts.contextChanged()
  })
  await preferences.initialize()
  await director.setRuntimeContext({ focus: value.value.companion.focus, activeTurn: conversation.sending,
    speaking: presentation.speaking || speechPlaybackState.speaking || speechPlaybackState.busy, surface: surface.surface })
  director.startScheduler()
  reachOuts.start()
  window.addEventListener('pointerdown', reachOuts.activity, true)
  window.addEventListener('keydown', reachOuts.activity, true)
  window.addEventListener('input', reachOuts.activity, true)
  document.addEventListener('visibilitychange', reachOuts.activity)
  await fitPresentationSurface(nativeSurface.value)
  try { updateState.value = await window.meguriDesktop?.updates?.get() }
  catch { /* Optional update state must not delay the character stage. */ }
  disposeUpdates = window.meguriDesktop?.updates?.onChanged(state => { updateState.value = state })
  presentation.selectOutfit(value.value.character.outfitCode)
  await refreshPresentationSnapshot()
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('resize', onWindowResize)
  viewportObserver.observe(document.documentElement)
  document.documentElement.addEventListener('mouseleave', onMouseLeave)
  startPointerPolling()
  restoreInteraction()
  artifacts.start(conversation.sessionId)
  void work.start()
  await window.meguriDesktop?.readiness.rendererReady()
})

onBeforeUnmount(() => {
  director.stopScheduler()
  disposeNoticeDelivery()
  disposeReminderPresenter()
  director.dispose()
  reachOuts.dispose()
  disposeActivity?.()
  window.removeEventListener('pointerdown', reachOuts.activity, true)
  window.removeEventListener('keydown', reachOuts.activity, true)
  window.removeEventListener('input', reachOuts.activity, true)
  document.removeEventListener('visibilitychange', reachOuts.activity)
  disposeUpdates?.()
  disposeShizukiReminder()
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('resize', onWindowResize)
  viewportObserver.disconnect()
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
    :style="{
      '--character-width': `${ambientPortrait.width}px`,
      '--character-height': `${ambientPortrait.height}px`,
      '--character-center': `${ambientPortraitCenter(viewportWidth, largeBubbleOpen)}px`,
      '--portrait-lane-width': `${compactStageSize.width}px`,
    }"
    :class="{
      'reduced-motion': value.character.reducedMotion,
      'chat-visible': value.chat.visible,
      'chat-expanded': value.chat.expanded,
      'panel-open': largeBubbleOpen,
      'side-bubble': largeBubbleOpen && viewportWidth >= ambientSideBubbleMinWidth,
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
      @panel="chatPanelOpen = $event"
    />
    <GalgameStage v-else @exit="exitGalgame" />
    <PrivacyDialog v-if="preferences.loaded && value.privacy.acceptedPolicyVersion !== PRIVACY_POLICY_VERSION" />
    <div v-if="updateState?.status === 'available' && !updateDismissed" class="stage-update-notice no-drag" data-stage-interactive role="status">
      <span>发现新版本 {{ updateState.latestVersion }}</span>
      <button type="button" @click="openUpdateSettings">打开设置查看</button>
      <button type="button" aria-label="关闭更新提示" @click="updateDismissed = true">×</button>
    </div>
    <div v-if="reminderCards.length" class="shizuki-reminder-stack no-drag" aria-live="assertive">
      <article v-for="reminder in reminderCards" :key="`${reminder.todoId}:${reminder.triggerAt}`" class="shizuki-reminder-card">
        <div><strong>{{ reminder.late ? '补发提醒' : '到点提醒' }}</strong><button type="button" :aria-label="`关闭 ${reminder.title} 提醒`" @click="dismissReminder(reminder)">×</button></div>
        <p>{{ reminder.title }}</p>
        <small>{{ new Date(reminder.triggerAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' }) }} · Asia/Shanghai</small>
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
