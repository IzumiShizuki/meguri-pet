<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { onBeforeUnmount, ref, watch } from 'vue'

import { useConversationStore } from '../stores/conversation'
import { useDesktopPreferencesStore } from '../stores/desktop-preferences'
import { usePresentationStore } from '../stores/presentation'
import { useWorkStore } from '../stores/work'

const presentation = usePresentationStore()
const conversation = useConversationStore()
const preferences = useDesktopPreferencesStore()
const work = useWorkStore()
const { character, statusText, statusTone } = storeToRefs(presentation)
const { outstanding } = storeToRefs(work)
const { value } = storeToRefs(preferences)
const expanded = ref(false)
const root = ref<HTMLElement>()
let collapseTimer: ReturnType<typeof setTimeout> | undefined
const emit = defineEmits<{
  galgame: []
  skills: []
  work: []
}>()

defineExpose({ open: () => { expanded.value = true } })

watch(expanded, (open) => {
  if (open)
    clearTimeout(collapseTimer)
})

function scheduleCollapse() {
  clearTimeout(collapseTimer)
  collapseTimer = setTimeout(() => { expanded.value = false }, 1500)
}

function cancelCollapse() {
  clearTimeout(collapseTimer)
}

function toggleChat() {
  void preferences.update({ chat: { visible: !value.value.chat.visible } })
}

function selectOutfit(outfitCode: string) {
  presentation.selectOutfit(outfitCode)
  void preferences.update({ character: { outfitCode } })
}

function toggleTheme() {
  void preferences.update({ appearance: preferences.isDark ? 'light' : 'dark' })
}

function toggleSpeech() {
  void preferences.update({ speech: { ttsEnabled: !value.value.speech.ttsEnabled } })
}

function toggleFadeOnHover() {
  void preferences.update({ window: { fadeOnHover: !value.value.window.fadeOnHover } })
}

async function toggleAlwaysOnTop() {
  const next = await window.meguriDesktop?.window.setAlwaysOnTop(!value.value.window.alwaysOnTop)
  if (next)
    preferences.apply(next)
}

function runWindowAction(action: 'openSettings' | 'openStudio' | 'centerStage' | 'reloadStage' | 'hide' | 'quit') {
  void window.meguriDesktop?.window[action]()
}

onBeforeUnmount(() => clearTimeout(collapseTimer))
</script>

<template>
  <aside
    ref="root"
    class="controls-island no-drag"
    :class="{ expanded }"
    aria-label="桌宠控制"
    @mouseenter="cancelCollapse"
    @mouseleave="scheduleCollapse"
  >
    <div class="controls-rail">
      <button class="control-button drag-region" type="button" title="拖动窗口" aria-label="拖动窗口">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2v20M2 12h20M12 2l-3 3m3-3 3 3M12 22l-3-3m3 3 3-3M2 12l3-3m-3 3 3 3m17-3-3-3m3 3-3 3" /></svg>
      </button>
      <button class="control-button" type="button" :class="{ active: value.speech.ttsEnabled }" :aria-pressed="value.speech.ttsEnabled" :title="value.speech.ttsEnabled ? '关闭回复语音' : '开启回复语音'" @click="toggleSpeech">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 9v6a3 3 0 0 0 6 0V9a3 3 0 0 0-6 0Zm-3 5a6 6 0 0 0 12 0M12 20v2" /></svg>
      </button>
      <button class="control-button" type="button" :aria-expanded="expanded" :title="expanded ? '收起控制' : '展开控制'" @click="expanded = !expanded">
        <svg class="chevron" :class="{ rotated: expanded }" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
      </button>
    </div>

    <Transition name="controls-drawer">
      <div v-if="expanded" class="controls-drawer">
        <div class="controls-status" :class="statusTone" role="status">
          <span />
          <p>{{ statusText }}</p>
        </div>

        <section class="outfit-picker" aria-label="立绘换装">
          <header><strong>立绘换装</strong><small>Ctrl + Alt + [ / ]</small></header>
          <div>
            <button
              v-for="code in ['01', '02', '03', '04', '05', '06']"
              :key="code"
              type="button"
              :class="{ active: character.outfitCode === code }"
              :aria-pressed="character.outfitCode === code"
              :title="`切换到立绘 ${code}`"
              @click="selectOutfit(code)"
            >{{ code }}</button>
          </div>
        </section>

        <div class="control-grid">
          <button type="button" title="打开设置" aria-label="打开设置" @click="runWindowAction('openSettings')"><span class="icon">⚙</span><small>设置</small></button>
          <button type="button" title="打开角色工作室" aria-label="打开角色工作室" @click="runWindowAction('openStudio')"><span class="icon">▧</span><small>角色</small></button>
          <button type="button" title="打开技能卡册（Ctrl+Shift+K）" aria-label="打开技能卡册" @click="emit('skills')"><span class="icon">◇</span><small>技能</small></button>
          <button type="button" title="转交工作任务给本机 Codex / Harness（Ctrl+Shift+J）" aria-label="转交工作任务" @click="emit('work')">
            <span class="icon">⇥</span>
            <small>转交<span v-if="outstanding" class="work-badge">{{ outstanding }}</span></small>
          </button>
          <button type="button" title="进入 Galgame 模式" aria-label="进入 Galgame 模式" @click="emit('galgame')"><span class="icon">▣</span><small>舞台</small></button>
          <button type="button" :title="value.chat.visible ? '隐藏聊天' : '显示聊天'" :aria-pressed="value.chat.visible" @click="toggleChat"><span class="icon">▤</span><small>聊天</small></button>
          <button type="button" title="开始新对话" aria-label="开始新对话" @click="conversation.newConversation()"><span class="icon">＋</span><small>新对话</small></button>
          <button type="button" :title="preferences.isDark ? '切换浅色' : '切换深色'" aria-label="切换颜色模式" @click="toggleTheme"><span class="icon">◐</span><small>主题</small></button>
          <button type="button" :title="value.window.alwaysOnTop ? '取消置顶' : '窗口置顶'" :aria-pressed="value.window.alwaysOnTop" @click="toggleAlwaysOnTop"><span class="icon">⌖</span><small>置顶</small></button>
          <button type="button" :title="value.window.fadeOnHover ? '关闭悬停隐藏与鼠标穿透' : '开启悬停隐藏与鼠标穿透'" :aria-pressed="value.window.fadeOnHover" @click="toggleFadeOnHover"><span class="icon">◉</span><small>穿透</small></button>
          <button type="button" title="居中窗口" aria-label="居中窗口" @click="runWindowAction('centerStage')"><span class="icon">◎</span><small>居中</small></button>
          <button type="button" title="刷新舞台" aria-label="刷新舞台" @click="runWindowAction('reloadStage')"><span class="icon">↻</span><small>刷新</small></button>
          <button type="button" title="隐藏 Meguri" aria-label="隐藏 Meguri" @click="runWindowAction('hide')"><span class="icon">◌</span><small>隐藏</small></button>
          <button class="danger" type="button" title="退出 Meguri" aria-label="退出 Meguri" @click="runWindowAction('quit')"><span class="icon">×</span><small>退出</small></button>
        </div>
      </div>
    </Transition>
  </aside>
</template>
