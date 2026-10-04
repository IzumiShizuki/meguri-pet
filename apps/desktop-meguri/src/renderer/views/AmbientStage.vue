<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, ref } from 'vue'

import ArtifactBubbles from '../components/ArtifactBubbles.vue'
import ChatDock from '../components/ChatDock.vue'
import ControlsIsland from '../components/ControlsIsland.vue'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import { usePresentationStore } from '../stores/presentation'

defineProps<{
  portraitFaded: boolean
}>()

const emit = defineEmits<{
  galgame: []
  skills: []
  work: []
}>()

const presentation = usePresentationStore()
const preferences = useDesktopPreferencesStore()
const controls = ref<InstanceType<typeof ControlsIsland>>()
const portrait = ref<HTMLImageElement>()
const { character, speaking } = storeToRefs(presentation)
const { value } = storeToRefs(preferences)

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
      <img
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
    <Transition name="dock">
      <ChatDock v-if="value.chat.visible" />
    </Transition>
    <ControlsIsland ref="controls" @galgame="emit('galgame')" @skills="emit('skills')" @work="emit('work')" />
  </section>
</template>
