<script setup lang="ts">
import { storeToRefs } from 'pinia'

import { useArtifactStore, type ArtifactItem } from '../stores/artifacts'

const store = useArtifactStore()
const { bubbles } = storeToRefs(store)

function extension(item: ArtifactItem) {
  const value = item.name.split('.').pop() ?? 'FILE'
  return value.length <= 5 ? value.toUpperCase() : 'FILE'
}
</script>

<template>
  <TransitionGroup name="artifact" tag="div" class="artifact-stack" aria-live="polite" aria-label="新生成的文件">
    <div v-for="item in bubbles" :key="item.key" class="artifact-bubble">
      <button class="artifact-open" type="button" :title="`打开 ${item.name}`" @click="store.open(item)">
        <img v-if="item.iconDataUrl" :src="item.iconDataUrl" alt="" />
        <span v-else>{{ extension(item) }}</span>
      </button>
      <button class="artifact-close" type="button" :aria-label="`关闭 ${item.name} 泡泡`" @click="store.dismiss(item.key)">×</button>
    </div>
  </TransitionGroup>
</template>
