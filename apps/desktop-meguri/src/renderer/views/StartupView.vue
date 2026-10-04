<script setup lang="ts">
import type { ReadinessSnapshot } from '../../shared/desktop-contract'

import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const snapshot = ref<ReadinessSnapshot>({ runId: 0, status: 'checking', items: [] })
const retrying = ref(false)
let dispose: (() => void) | undefined

const readyCount = computed(() => snapshot.value.items.filter(item => item.status === 'ready').length)
const progress = computed(() => snapshot.value.items.length
  ? Math.round(readyCount.value / snapshot.value.items.length * 100)
  : 0)

onMounted(async () => {
  if (!window.meguriDesktop)
    return
  snapshot.value = await window.meguriDesktop.readiness.get()
  dispose = window.meguriDesktop.readiness.onChanged(next => { snapshot.value = next })
})

onBeforeUnmount(() => dispose?.())

async function retry() {
  if (!window.meguriDesktop)
    return
  retrying.value = true
  try {
    snapshot.value = await window.meguriDesktop.readiness.retry()
  }
  finally {
    retrying.value = false
  }
}
</script>

<template>
  <main class="startup-shell">
    <div class="startup-drag drag-region" />
    <div class="startup-orbit" aria-hidden="true">
      <div class="orbit orbit-a" />
      <div class="orbit orbit-b" />
      <div class="startup-mark">巡</div>
    </div>

    <section class="startup-copy">
      <p class="startup-eyebrow">MEGURI DESKTOP</p>
      <h1>正在醒来</h1>
      <p>把记忆、声音和今天的状态一件件接回来。</p>
    </section>

    <section class="readiness-panel" aria-label="启动状态">
      <div v-for="item in snapshot.items" :key="item.capability" class="readiness-row" :class="item.status">
        <span class="readiness-dot" />
        <div>
          <strong>{{ item.label }}</strong>
          <small>{{ item.detail }}</small>
        </div>
        <span class="readiness-state">{{ item.status === 'ready' ? '就绪' : item.status === 'failed' ? '失败' : '检查中' }}</span>
      </div>
    </section>

    <footer class="startup-footer">
      <div class="progress-track"><span :style="{ width: `${progress}%` }" /></div>
      <div class="startup-footer-copy">
        <span>{{ snapshot.status === 'failed' ? '有一项需要处理' : snapshot.status === 'ready' ? '马上就好' : '正在连接本地能力' }}</span>
        <button v-if="snapshot.status === 'failed'" class="retry-button no-drag" type="button" :disabled="retrying" @click="retry">
          {{ retrying ? '重试中…' : '重新检查' }}
        </button>
        <span v-else>{{ progress }}%</span>
      </div>
    </footer>
  </main>
</template>
