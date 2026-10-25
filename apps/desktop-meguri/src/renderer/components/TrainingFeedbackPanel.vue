<script setup lang="ts">
import { computed, ref } from 'vue'
import { remoteChatConsented } from '../../shared/experience-contract'
import { useConversationStore } from '../stores/conversation'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import { useTrainingFeedbackStore } from '../stores/training-feedback'
import MessageContent from './MessageContent.vue'

const props = defineProps<{ sessionId: string, turnId: string }>()
const feedback = useTrainingFeedbackStore()
const conversation = useConversationStore()
const preferences = useDesktopPreferencesStore()
const consent = ref(false)
const entry = computed(() => feedback.entry(props.sessionId, props.turnId))
const receipt = computed(() => feedback.receipt(props.sessionId, props.turnId))
const canSubmit = computed(() => entry.value && !entry.value.busy && !entry.value.uncertain
  && consent.value && remoteChatConsented(preferences.value.privacy)
  && (entry.value.selectedCandidateId || entry.value.feedbackText.trim()))

async function submit() {
  const result = await feedback.submit(props.sessionId, props.turnId, consent.value)
  if (!result) return
  consent.value = false
  if (result.status !== 'training_candidate_saved' || !result.response) return
  const message = conversation.messages.find(item => item.turnId === props.turnId)
  if (message && conversation.sessionId === props.sessionId) {
    message.text = result.response.reply
    message.speech = undefined
    conversation.persist()
  }
}
</script>

<template>
  <details v-if="entry || receipt" class="training-feedback">
    <summary>{{ receipt ? (receipt.status === 'revoked' ? '训练许可已撤回' : '反馈已保存') : (entry!.candidates.length > 1 ? '比较回复 · 可选反馈' : '纠正这次回复 · 可选反馈') }}</summary>
    <template v-if="entry && !receipt">
      <p class="feedback-hint">只反馈说话方式、角色表现或行为。需要记住的近况和事实请放入正式记忆。</p>
      <div v-if="entry.candidates.length > 1" class="feedback-candidates" role="group" aria-label="选择更合适的回复">
        <section v-for="(candidate, index) in entry.candidates" :key="candidate.candidate_id" class="feedback-candidate" :class="{ selected: entry.selectedCandidateId === candidate.candidate_id }">
          <label><input type="radio" :name="`candidate-${turnId}`" :checked="entry.selectedCandidateId === candidate.candidate_id" :disabled="entry.busy || entry.uncertain" @change="feedback.edit(sessionId, turnId, { selectedCandidateId: candidate.candidate_id })"><strong>回复 {{ index === 0 ? 'A' : 'B' }}</strong></label>
          <MessageContent :text="candidate.response.reply" rich />
        </section>
      </div>
      <button v-if="entry.selectedCandidateId" class="feedback-secondary" type="button" :disabled="entry.busy || entry.uncertain" @click="feedback.edit(sessionId, turnId, { selectedCandidateId: '' })">清除选择</button>
      <label class="feedback-correction"><span>行为纠正{{ entry.candidates.length > 1 ? '（可选）' : '' }}</span><textarea :value="entry.feedbackText" rows="3" maxlength="2000" :disabled="entry.busy || entry.uncertain" placeholder="例如：先解释关键原因；保持 Meguri 的表达习惯。" @input="feedback.edit(sessionId, turnId, { feedbackText: ($event.target as HTMLTextAreaElement).value })" /><small>{{ entry.feedbackText.length }} / 2000</small></label>
      <label class="feedback-consent"><input v-model="consent" type="checkbox" :disabled="entry.busy"><span>我同意将本轮输入、相关上下文、候选回复和本次反馈保存为离线训练候选；可在设置中撤回。</span></label>
      <p class="feedback-hint">保存后还需脱敏和人工审核，模型不会立即改变。训练许可与正式记忆分别管理。</p>
      <p v-if="!remoteChatConsented(preferences.value.privacy)" class="feedback-hint">网络对话许可已关闭。已保存的反馈仍可撤回。</p>
      <div class="feedback-actions"><button type="button" :disabled="!canSubmit" @click="submit">{{ entry.busy ? '正在保存…' : '保存反馈' }}</button><button type="button" :disabled="feedback.refreshing || entry.busy" @click="feedback.refresh(sessionId)">刷新状态</button></div>
      <p v-if="entry.error" class="feedback-error" role="alert">{{ entry.error }}</p>
    </template>
    <template v-else-if="receipt">
      <p class="feedback-hint">{{ receipt.status === 'revoked' ? '已撤回训练许可，后续导出会排除这条反馈。已经完成的训练不会自动逆转。' : '已保存为训练候选，等待离线脱敏与人工审核。' }}</p>
      <code class="feedback-receipt">回执：{{ receipt.feedback_id }}</code>
      <button v-if="receipt.status !== 'revoked'" type="button" :disabled="receipt.busy" @click="feedback.revoke(sessionId, turnId)">{{ receipt.busy ? '正在撤回…' : '撤回训练许可' }}</button>
      <p v-if="receipt.error" class="feedback-error" role="alert">{{ receipt.error }}</p>
    </template>
    <p v-if="feedback.refreshError" class="feedback-error" role="alert">{{ feedback.refreshError }}</p>
  </details>
</template>

<style scoped>
.training-feedback { margin-top: 9px; padding-top: 7px; border-top: 1px solid var(--conversation-border); font-size: 11px; color: var(--conversation-text); }
summary { color: var(--conversation-accent); cursor: pointer; padding: 3px 0; }
.feedback-hint { margin: 8px 0; color: var(--conversation-muted); font-size: 10px; line-height: 1.6; }
.feedback-candidates { display: grid; gap: 8px; margin: 10px 0; }
.feedback-candidate { min-width: 0; padding: 10px; border: 1px solid var(--conversation-border); border-radius: 10px; background: var(--conversation-input); }
.feedback-candidate.selected { border-color: var(--conversation-accent); box-shadow: inset 3px 0 var(--conversation-accent); }
.feedback-candidate label, .feedback-consent { display: flex; align-items: flex-start; gap: 7px; line-height: 1.6; }
.feedback-candidate label { margin-bottom: 7px; }
.feedback-candidate :deep(.message-body) { font-size: 11px; line-height: 1.65; }
.feedback-correction { display: grid; gap: 5px; margin: 9px 0; }
textarea { width: 100%; resize: vertical; min-height: 60px; padding: 8px; border: 1px solid var(--conversation-border); border-radius: 8px; color: var(--conversation-text); background: var(--conversation-input); font: inherit; }
small { justify-self: end; color: var(--conversation-muted); }
.feedback-actions { display: flex; flex-wrap: wrap; gap: 7px; }
button { padding: 6px 9px; border: 1px solid var(--conversation-border); border-radius: 7px; color: var(--conversation-accent); background: var(--conversation-input); font: inherit; cursor: pointer; }
button:disabled { opacity: .5; cursor: default; }
.feedback-secondary { padding: 3px 7px; }
.feedback-error { color: var(--conversation-danger); line-height: 1.6; }
.feedback-receipt { display: block; overflow-wrap: anywhere; margin-bottom: 10px; font-size: 9px; }
</style>
