<script setup lang="ts">
import { computed } from 'vue'
import { useConversationStore, type ConversationMessage } from '../stores/conversation'
import { speechPlaybackState } from '../services/speech-playback'

const props = defineProps<{ message: ConversationMessage, disabled?: boolean }>()
const conversation = useConversationStore()
const available = computed(() => props.message.role === 'meguri' && Boolean(props.message.text.trim())
  && (!props.message.status || props.message.status === 'complete'))
const active = computed(() => conversation.speechReplayMessageId === props.message.id)
const failure = computed(() => conversation.speechReplayErrorMessageId === props.message.id ? conversation.speechReplayError : '')
</script>

<template>
  <span v-if="available" class="message-replay-control">
    <button
      type="button"
      class="message-replay"
      :aria-label="active ? '停止这句语音' : '重放这句语音'"
      :title="disabled ? '请等语音通话结束' : active ? '停止这句语音' : '重放这句语音'"
      :aria-pressed="active"
      :disabled="disabled || (conversation.sending && !active)"
      @click.stop="conversation.replaySpeech(message.id)"
    >{{ active ? (speechPlaybackState.speaking ? '■ 停止' : '■ 生成中…') : '▷ 重放语音' }}</button>
    <span v-if="failure" class="message-replay-error" role="status" aria-live="polite">{{ failure }}</span>
  </span>
</template>
