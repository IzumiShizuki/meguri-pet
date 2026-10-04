<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import { type ConversationMessage, useConversationStore } from '../stores/conversation'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import { usePresentationStore } from '../stores/presentation'
import { usePresentationSurfaceStore } from '../stores/presentation-surface'

const emit = defineEmits<{ exit: [] }>()

const conversation = useConversationStore()
const presentation = usePresentationStore()
const preferences = useDesktopPreferencesStore()
const surface = usePresentationSurfaceStore()
const { messages, sending, error } = storeToRefs(conversation)
const { character, speaking, statusText } = storeToRefs(presentation)
const { value } = storeToRefs(preferences)
const { resourceSnapshot } = storeToRefs(surface)
const draft = ref('')
const history = ref<HTMLElement>()
const composer = ref<HTMLTextAreaElement>()
const portraitFailed = ref(false)

const canSend = computed(() => Boolean(draft.value.trim()) && !sending.value)
const portraitUrl = computed(() => resourceSnapshot.value.spriteDataUrl || character.value.spriteUrl)
const backdropStyle = computed(() => resourceSnapshot.value.backgroundDataUrl
  ? { backgroundImage: `url("${resourceSnapshot.value.backgroundDataUrl}")` }
  : undefined)

watch(() => messages.value.map(message => `${message.id}:${message.text}:${message.status}`).join('|'), async () => {
  await nextTick()
  if (history.value)
    history.value.scrollTop = history.value.scrollHeight
})

watch(portraitUrl, () => { portraitFailed.value = false })

function roleLabel(message: ConversationMessage) {
  if (message.role === 'user') return '你'
  if (message.role === 'meguri') return 'Meguri'
  if (message.role === 'artifact') return '文件'
  return '系统'
}

async function send() {
  const message = draft.value.trim()
  if (!message || sending.value)
    return
  draft.value = ''
  const completed = await conversation.send(message)
  if (!completed && !draft.value)
    draft.value = message
}

function onComposerKeydown(event: KeyboardEvent) {
  if (event.isComposing)
    return
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void send()
  }
}

function toggleSpeech() {
  void preferences.update({ speech: { ttsEnabled: !value.value.speech.ttsEnabled } })
}

onMounted(() => void nextTick(() => composer.value?.focus()))
</script>

<template>
  <section class="galgame-stage" data-stage-interactive aria-label="Meguri Galgame 舞台">
    <div class="galgame-backdrop" aria-hidden="true">
      <span v-if="backdropStyle" class="galgame-backdrop-image" :style="backdropStyle" />
      <span class="galgame-backdrop-window" />
      <span class="galgame-backdrop-floor" />
    </div>

    <header class="galgame-toolbar drag-region">
      <div class="galgame-scene-label no-drag">
        <strong>Meguri</strong>
        <span>{{ statusText }}</span>
      </div>
      <div class="galgame-toolbar-actions no-drag">
        <button type="button" :aria-pressed="value.speech.ttsEnabled" :title="value.speech.ttsEnabled ? '关闭回复语音' : '开启回复语音'" @click="toggleSpeech">
          {{ value.speech.ttsEnabled ? '语音开' : '语音关' }}
        </button>
        <button type="button" title="返回桌宠模式" @click="emit('exit')">返回桌宠</button>
      </div>
    </header>

    <div class="galgame-layout">
      <section class="galgame-character" :class="{ speaking }" aria-label="当前角色表现">
        <img
          v-if="!portraitFailed"
          :key="portraitUrl"
          :src="portraitUrl"
          alt="Meguri"
          draggable="false"
          @error="portraitFailed = true"
        >
        <div v-else class="galgame-character-fallback" role="status">
          <strong>Meguri</strong>
          <span>角色资源暂时不可用</span>
        </div>
      </section>

      <section class="galgame-dialogue" aria-label="对话">
        <div ref="history" class="galgame-history" :class="{ empty: messages.length === 0 }" aria-live="polite">
          <div v-if="messages.length === 0" class="galgame-empty-state">
            <strong>傍晚好。</strong>
            <span>Meguri 在这里。</span>
          </div>
          <article v-for="message in messages" v-else :key="message.id" class="galgame-message" :class="[message.role, message.status]">
            <span class="galgame-message-role">{{ roleLabel(message) }}</span>
            <p>{{ message.text || (message.status === 'streaming' ? '…' : '') }}</p>
          </article>
        </div>

        <div v-if="error" class="galgame-error" role="alert">
          <span>{{ error }}</span>
          <button type="button" :disabled="sending" @click="conversation.retry()">重试</button>
        </div>

        <form class="galgame-composer" @submit.prevent="send">
          <textarea
            ref="composer"
            v-model="draft"
            rows="2"
            maxlength="8000"
            placeholder="和 Meguri 说点什么…"
            aria-label="消息"
            @keydown="onComposerKeydown"
          />
          <button v-if="sending" type="button" class="galgame-send cancel" aria-label="取消回复" @click="conversation.cancel()">取消</button>
          <button v-else type="submit" class="galgame-send" :disabled="!canSend">发送</button>
        </form>
      </section>
    </div>
  </section>
</template>
