<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { remoteChatConsented } from '../../shared/experience-contract'
import { useConversationStore } from '../stores/conversation'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import { useTrainingFeedbackStore } from '../stores/training-feedback'
import TrainingFeedbackPanel from './TrainingFeedbackPanel.vue'

const preferences = useDesktopPreferencesStore()
const conversation = useConversationStore()
const feedback = useTrainingFeedbackStore()
const pending = computed(() => feedback.entries.filter(entry => entry.session_id === conversation.sessionId))
onMounted(() => { void feedback.refresh(conversation.sessionId) })
</script>

<template>
  <section class="training-settings" aria-labelledby="training-title">
    <h2 id="training-title">监督与训练</h2>
    <p>让 Meguri 学习你明确指出的表达和行为。反馈先保存为候选，经过离线脱敏、审核、训练和评测后才能用于模型。</p>
    <label class="setting-row"><span><strong>训练模式</strong><small>给后续回复提供可选的行为纠正入口，偶尔比较两种回复。每次保存仍需单独同意；可随时关闭。</small></span><input type="checkbox" :checked="preferences.value.training.enabled" :disabled="!remoteChatConsented(preferences.value.privacy)" @change="preferences.update({ training: { enabled: ($event.target as HTMLInputElement).checked } })"></label>
    <p v-if="!remoteChatConsented(preferences.value.privacy)" class="training-hint">开启训练模式前，请在隐私与数据中允许网络对话。撤回已保存反馈不受此开关影响。</p>
    <p class="training-hint">生日、近况、待办等会变化的事实应保存到正式记忆。关闭训练模式会停止后续训练模式采样；已有候选和回执仍可管理。</p>
    <div class="settings-actions"><button type="button" :disabled="feedback.refreshing" @click="feedback.refresh(conversation.sessionId)">{{ feedback.refreshing ? '正在刷新…' : '刷新当前会话' }}</button></div>
    <p v-if="feedback.refreshError" class="training-error" role="alert">{{ feedback.refreshError }}</p>
    <h3>当前会话的待反馈回复</h3>
    <p v-if="!pending.length" class="training-hint">暂无待反馈回复。开启训练模式后，可以继续对话；反馈完全可选。</p>
    <article v-for="entry in pending" :key="entry.turn_id" class="training-record"><small>{{ entry.turn_id }}</small><TrainingFeedbackPanel :session-id="entry.session_id" :turn-id="entry.turn_id" /></article>
    <h3>已保存回执</h3>
    <p class="training-hint">本机保留回执编号，开始新对话后仍可撤回。撤回会排除后续训练导出，无法自动逆转已经完成的训练。</p>
    <p v-if="!feedback.receipts.length" class="training-hint">尚未保存反馈。</p>
    <article v-for="receipt in feedback.receipts.slice().reverse()" :key="receipt.feedback_id" class="training-record"><small>{{ receipt.session_id }}</small><TrainingFeedbackPanel :session-id="receipt.session_id" :turn-id="receipt.turn_id" /></article>
  </section>
</template>

<style scoped>
.training-settings > p { line-height: 1.7; font-size: 12px; }
h2 { margin-top: 0; font-size: 18px; }
h3 { margin-top: 25px; font-size: 13px; }
.training-hint { color: #78716c; }
.training-error { color: #b91c1c; }
.training-record { padding: 12px; margin-top: 9px; border: 1px solid #e7e5e4; border-radius: 12px; }
.training-record > small { display: block; font-size: 10px; color: #78716c; overflow-wrap: anywhere; }
:global(.dark) .training-hint, :global(.dark) .training-record > small { color: var(--dark-text-secondary); }
:global(.dark) .training-record { border-color: var(--dark-border); }
:global(.dark) .training-error { color: #fca5a5; }
</style>
