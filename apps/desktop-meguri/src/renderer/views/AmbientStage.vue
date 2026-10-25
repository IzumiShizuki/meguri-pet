<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, ref } from 'vue'

import ArtifactBubbles from '../components/ArtifactBubbles.vue'
import ChatDock from '../components/ChatDock.vue'
import ControlsIsland from '../components/ControlsIsland.vue'
import Live2DCharacter from '../components/Live2DCharacter.vue'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import { usePresentationStore } from '../stores/presentation'

defineProps<{
  portraitFaded: boolean
}>()

const emit = defineEmits<{
  galgame: []
  skills: []
  work: []
  panel: [open: boolean]
}>()

const presentation = usePresentationStore()
const preferences = useDesktopPreferencesStore()
const controls = ref<InstanceType<typeof ControlsIsland>>()
const chatPanelOpen = ref(false)
const portrait = ref<HTMLElement>()
const { character, speaking } = storeToRefs(presentation)
const { value } = storeToRefs(preferences)

function onChatPanel(open: boolean) {
  chatPanelOpen.value = open
  emit('panel', open)
}

function deactivateLeavingChat(element: Element) {
  element.setAttribute('inert', '')
  element.setAttribute('aria-hidden', 'true')
}

function activateEnteringChat(element: Element) {
  element.removeAttribute('inert')
  element.removeAttribute('aria-hidden')
}

const motionClass = computed(() => {
  if (value.value.character.reducedMotion)
    return ''
  const motion = character.value.motionTag?.toLowerCase() ?? ''
  if (/bounce|jump|excited/.test(motion)) return 'motion-bounce'
  if (/shake|angry/.test(motion)) return 'motion-shake'
  if (/sway|idle|soft/.test(motion)) return 'motion-sway'
  return 'motion-breathe'
})

defineExpose({
  openControls: () => controls.value?.open(),
  portraitBounds: () => portrait.value?.getBoundingClientRect(),
})
</script>

<template>
  <section class="ambient-stage" aria-label="Meguri 桌宠舞台">
    <section class="character-stage" :class="{ speaking, 'hover-faded': portraitFaded }" aria-label="Meguri 角色舞台">
      <div v-if="value.character.renderer === 'live2d'" ref="portrait" class="character-sprite live2d-portrait">
        <Live2DCharacter :character="character" :speaking="speaking" />
      </div>
      <img
        v-else
        ref="portrait"
        :key="character.spriteUrl"
        class="character-sprite"
        :class="motionClass"
        :src="character.spriteUrl"
        alt="Meguri"
        draggable="false"
      >
      <div class="character-shadow" aria-hidden="true" />
    </section>

    <ArtifactBubbles />
    <Transition name="dock" @before-leave="deactivateLeavingChat" @before-enter="activateEnteringChat" @leave-cancelled="activateEnteringChat">
      <ChatDock v-if="value.chat.visible" @panel="onChatPanel">
        <template #controls>
          <ControlsIsland v-if="chatPanelOpen" ref="controls" inline @galgame="emit('galgame')" @skills="emit('skills')" @work="emit('work')" />
        </template>
      </ChatDock>
    </Transition>
    <ControlsIsland v-if="!chatPanelOpen" ref="controls" @galgame="emit('galgame')" @skills="emit('skills')" @work="emit('work')" />
  </section>
</template>
