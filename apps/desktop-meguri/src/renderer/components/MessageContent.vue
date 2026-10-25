<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { renderMessageMarkdown } from '../utils/message-format'
import 'katex/dist/katex.min.css'
import type { ConversationMessage } from '../stores/conversation'
import MessageReplayButton from './MessageReplayButton.vue'

const props = defineProps<{ text: string, rich?: boolean, streaming?: boolean, message?: ConversationMessage, replayDisabled?: boolean }>()
const feedback = ref('')
const copying = ref(false)
let resetTimer: ReturnType<typeof setTimeout> | undefined
const formatted = computed(() => props.rich ? renderMessageMarkdown(props.text || (props.streaming ? '…' : '')) : '')

async function copy() {
  if (copying.value) return
  copying.value = true
  clearTimeout(resetTimer)
  try {
    if (!navigator.clipboard?.writeText) throw new Error('Clipboard unavailable')
    await navigator.clipboard.writeText(props.text)
    feedback.value = '已复制原文'
    resetTimer = setTimeout(() => { feedback.value = '' }, 2500)
  }
  catch { feedback.value = '复制失败，请选中文字后按 Ctrl+C' }
  finally { copying.value = false }
}
onBeforeUnmount(() => clearTimeout(resetTimer))
</script>

<template>
  <div class="message-content">
    <div v-if="rich" class="message-body rich-message" v-html="formatted" />
    <div v-else class="message-body plain-message">{{ text || (streaming ? '…' : '') }}</div>
    <div class="message-copy-row">
      <button v-if="text" type="button" class="message-copy" aria-label="复制消息原文" :disabled="copying" @click="copy">复制</button>
      <MessageReplayButton v-if="message" :message="message" :disabled="replayDisabled" />
      <span v-if="feedback" role="status" aria-live="polite">{{ feedback }}</span>
    </div>
  </div>
</template>
