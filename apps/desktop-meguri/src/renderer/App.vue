<script setup lang="ts">
import { computed } from 'vue'

import StageView from './views/StageView.vue'
import SettingsView from './views/SettingsView.vue'
import StartupView from './views/StartupView.vue'
import CharacterStudioView from './views/CharacterStudioView.vue'

const params = new URLSearchParams(window.location.search)
const activeView = computed(() => window.meguriDesktop?.view ?? (params.get('view') === 'startup' ? 'startup' : 'stage'))
</script>

<template>
  <StartupView v-if="activeView === 'startup'" />
  <SettingsView v-else-if="activeView === 'settings'" />
  <CharacterStudioView v-else-if="activeView === 'studio'" />
  <StageView v-else />
</template>
