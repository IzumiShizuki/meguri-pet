<script setup lang="ts">
import type {
  LocalAgentProbeResult,
  LocalAgentSettings,
  LocalAgentSettingsPatch,
  MultimodalRelayConfig,
  MultimodalRelayDraft,
  ReadinessSnapshot,
  DesktopStartupPreferencesPatch,
  DesktopStartupStatus,
  ShizukiConnectionStatus,
} from '../../shared/desktop-contract'

import { storeToRefs } from 'pinia'
import { computed, onMounted, ref } from 'vue'

import { useDesktopPreferencesStore } from '../stores/desktop-preferences'

type SettingsSection = 'general' | 'chat' | 'character' | 'window' | 'startup' | 'speech' | 'shizuki' | 'shortcuts' | 'multimodal' | 'agents' | 'services'
type RelayFeedback = { kind: 'neutral' | 'success' | 'error', message: string }

const preferences = useDesktopPreferencesStore()
const { value } = storeToRefs(preferences)
const active = ref<SettingsSection>()
const readiness = ref<ReadinessSnapshot>({ runId: 0, status: 'checking', items: [] })
const relay = ref<MultimodalRelayConfig>({
  version: 1,
  enabled: false,
  baseUrl: '',
  model: '',
  apiKeyConfigured: false,
})
const relayDraft = ref<MultimodalRelayDraft>({ enabled: false, baseUrl: '', model: '', apiKey: '' })
const relayLoading = ref(true)
const relaySaving = ref(false)
const relayTesting = ref(false)
const relayDirty = ref(false)
const relayFeedback = ref<RelayFeedback>()

const agentSettings = ref<LocalAgentSettings>({
  version: 1,
  enabled: false,
  harnessAutoStart: false,
  dshHome: '',
  install: {
    codex: { installed: false, home: '', signedIn: false },
    harness: { cli: 'missing', credentials: 'missing', preset: 'unavailable', logReady: false },
  },
})
const agentDraft = ref<LocalAgentSettingsPatch>({ enabled: false, harnessAutoStart: false, dshHome: '' })
const agentLoading = ref(true)
const agentSaving = ref(false)
const agentProbing = ref(false)
const agentDirty = ref(false)
const agentProbe = ref<LocalAgentProbeResult>()

const startupDraft = ref<DesktopStartupPreferencesPatch>({ openAtLogin: true, runAsAdministrator: true })
const startupLoading = ref(true)
const startupSaving = ref(false)
const startupDirty = ref(false)
const startupStatus = ref<DesktopStartupStatus>({ ok: true, message: '正在读取 Windows 启动配置。' })
const startupFeedback = ref<RelayFeedback>()

function startupBridgeErrorMessage(error: unknown) {
  const message = error instanceof Error ? error.message.trim() : String(error).trim()
  if (/no handler registered/iu.test(message))
    return `桌面主进程没有加载启动设置接口，请完全退出 Meguri 后重新打开。${message}`
  return message || '没有收到桌面主进程的错误详情。'
}
const shizukiStatus = ref<ShizukiConnectionStatus>({ connected: false, origin: 'https://site.shizuki.online', email: '', userId: 0, error: '' })
const shizukiDraft = ref({ origin: 'https://site.shizuki.online', email: '', password: '' })
const shizukiLoading = ref(true)
const shizukiSaving = ref(false)
const shizukiFeedback = ref<RelayFeedback>()

const sections: Array<{ id: SettingsSection, label: string, description: string, icon: string }> = [
  { id: 'general', label: '外观', description: '颜色模式和界面动效', icon: '◐' },
  { id: 'chat', label: '对话', description: '嵌入式聊天坞和历史记录', icon: '▤' },
  { id: 'character', label: '角色', description: 'Meguri 立绘与表现方式', icon: '◇' },
  { id: 'window', label: '窗口', description: '置顶、位置和舞台行为', icon: '▣' },
  { id: 'startup', label: '启动与权限', description: '登录启动和管理员权限', icon: '↥' },
  { id: 'speech', label: '语音', description: '本地语音输入、回复语音和音量', icon: '♪' },
  { id: 'shizuki', label: 'Shizuki 待办', description: '连接 TimePrism 账号和到点提醒', icon: '☑' },
  { id: 'shortcuts', label: '快捷键', description: '全局显示与隐藏快捷键', icon: '⌘' },
  { id: 'multimodal', label: '多模态', description: '图片与 PDF 的视觉中转站', icon: '◎' },
  { id: 'agents', label: '工作 Agent', description: '把任务转交给本机 Codex / Harness', icon: '⇥' },
  { id: 'services', label: '服务状态', description: 'Core、网关、语音与资源', icon: '●' },
]

const currentSection = computed(() => sections.find(section => section.id === active.value))
const relayConfigured = computed(() => Boolean(
  relay.value.baseUrl && relay.value.model && relay.value.apiKeyConfigured,
))
const relayBusy = computed(() => relayLoading.value || relaySaving.value || relayTesting.value)
const agentBusy = computed(() => agentLoading.value || agentSaving.value || agentProbing.value)

function agentInstallLabel(state: string): string {
  const labels: Record<string, string> = {
    installed: '已安装',
    missing: '未找到',
    present: '已存在',
    user_preset: '已有同名预设',
    unavailable: '不可用',
    configured: '已配置',
  }
  return labels[state] ?? state
}

function applyAgentSettings(settings: LocalAgentSettings) {
  agentSettings.value = structuredClone(settings)
  agentDraft.value = {
    enabled: settings.enabled,
    harnessAutoStart: settings.harnessAutoStart,
    dshHome: settings.dshHome ?? '',
  }
  agentDirty.value = false
}

function markAgentDirty() {
  agentDirty.value = true
  agentProbe.value = undefined
}

async function saveAgentSettings() {
  if (agentBusy.value)
    return
  const bridge = window.meguriDesktop?.localAgents
  if (!bridge) {
    agentProbe.value = { codex: { ok: false, message: '桌面配置桥接不可用，请重启 Meguri。' }, harness: { ok: false, message: '桌面配置桥接不可用，请重启 Meguri。' } }
    return
  }
  agentSaving.value = true
  try {
    const result = await bridge.save(agentDraft.value)
    if (!result.ok || !result.settings) {
      agentProbe.value = { codex: { ok: false, message: result.error ?? '配置没有保存。' }, harness: { ok: false, message: result.error ?? '配置没有保存。' } }
      return
    }
    applyAgentSettings(result.settings)
  }
  catch {
    agentProbe.value = { codex: { ok: false, message: '保存失败，请检查本地配置目录。' }, harness: { ok: false, message: '保存失败，请检查本地配置目录。' } }
  }
  finally {
    agentSaving.value = false
  }
}

async function testAgentConnections() {
  if (agentBusy.value)
    return
  const bridge = window.meguriDesktop?.localAgents
  if (!bridge) {
    agentProbe.value = { codex: { ok: false, message: '桌面配置桥接不可用。' }, harness: { ok: false, message: '桌面配置桥接不可用。' } }
    return
  }
  agentProbing.value = true
  agentProbe.value = undefined
  try {
    agentProbe.value = await bridge.test()
  }
  catch {
    agentProbe.value = { codex: { ok: false, message: '连接测试失败。' }, harness: { ok: false, message: '连接测试失败。' } }
  }
  finally {
    agentProbing.value = false
  }
}

function applyRelayConfig(config: MultimodalRelayConfig) {
  relay.value = structuredClone(config)
  relayDraft.value = {
    enabled: config.enabled,
    baseUrl: config.baseUrl,
    model: config.model,
    apiKey: '',
  }
  relayDirty.value = false
}

function sectionDescription(section: (typeof sections)[number]) {
  if (section.id === 'shizuki')
    return shizukiLoading.value ? '正在读取连接状态' : shizukiStatus.value.connected ? `已连接 · ${shizukiStatus.value.email}` : '尚未连接 TimePrism'
  if (section.id === 'startup') {
    if (startupLoading.value)
      return '正在读取 Windows 启动配置'
    const enabled = [
      startupDraft.value.openAtLogin ? '登录启动' : '',
      startupDraft.value.runAsAdministrator ? '管理员权限' : '',
    ].filter(Boolean)
    return startupStatus.value.ok
      ? (enabled.length ? enabled.join(' · ') : '未启用登录启动或管理员权限')
      : 'Windows 启动配置需要处理'
  }
  if (section.id === 'agents') {
    if (agentLoading.value)
      return '正在读取本机安装状态'
    if (!agentSettings.value.enabled)
      return '未启用 · 任务只在本地执行'
    const installed = [agentSettings.value.install.codex.installed ? 'Codex' : '', agentSettings.value.install.harness.cli === 'installed' ? 'Harness' : ''].filter(Boolean)
    return installed.length ? `已启用 · ${installed.join(' / ')}` : '已启用 · 未找到本机执行器'
  }
  if (section.id !== 'multimodal')
    return section.description
  if (relayLoading.value)
    return '正在读取本地配置'
  if (!relayConfigured.value)
    return '未配置 · 图片暂不可识别'
  return relay.value.enabled ? `已启用 · ${relay.value.model}` : `已保存 · ${relay.value.model}`
}

onMounted(async () => {
  await preferences.initialize()
  const [snapshot, savedRelay, savedAgents] = await Promise.all([
    window.meguriDesktop?.readiness.get(),
    window.meguriDesktop?.multimodalRelay.get(),
    window.meguriDesktop?.localAgents.get(),
  ])
  if (snapshot)
    readiness.value = snapshot
  if (savedRelay)
    applyRelayConfig(savedRelay)
  else
    relayFeedback.value = { kind: 'error', message: '无法读取多模态配置，请重新打开设置。' }
  relayLoading.value = false
  if (savedAgents)
    applyAgentSettings(savedAgents)
  agentLoading.value = false
  startupDraft.value = { ...value.value.startup }
  const startupBridge = window.meguriDesktop?.startupSettings
  if (!startupBridge) {
    startupStatus.value = { ok: false, message: '无法读取 Windows 启动状态，请重新打开设置。' }
  }
  else {
    try {
      startupStatus.value = await startupBridge.getStatus()
    }
    catch (error) {
      startupStatus.value = { ok: false, message: startupBridgeErrorMessage(error) }
    }
  }
  startupLoading.value = false
  try {
    const connected = await window.meguriDesktop?.shizuki?.status()
    if (connected) {
      shizukiStatus.value = connected
      shizukiDraft.value.origin = connected.origin || 'https://site.shizuki.online'
      shizukiDraft.value.email = connected.email || ''
    }
  }
  catch {
    shizukiFeedback.value = { kind: 'error', message: '无法读取 Shizuki 连接状态。' }
  }
  shizukiLoading.value = false
})

async function connectShizuki() {
  if (shizukiSaving.value) return
  const bridge = window.meguriDesktop?.shizuki
  if (!bridge) {
    shizukiFeedback.value = { kind: 'error', message: '桌面连接桥接不可用，请重启 Meguri。' }
    return
  }
  shizukiSaving.value = true
  shizukiFeedback.value = undefined
  try {
    const result = await bridge.connect({ ...shizukiDraft.value })
    if (!result.ok || !result.status) {
      shizukiFeedback.value = { kind: 'error', message: result.error || 'Shizuki 连接失败。' }
      return
    }
    shizukiStatus.value = result.status
    shizukiFeedback.value = { kind: 'success', message: '已连接 Shizuki TimePrism，可在聊天坞打开待办。' }
  }
  catch {
    shizukiFeedback.value = { kind: 'error', message: 'Shizuki 连接失败，请检查网络。' }
  }
  finally {
    shizukiDraft.value.password = ''
    shizukiSaving.value = false
  }
}

async function disconnectShizuki() {
  if (shizukiSaving.value) return
  const bridge = window.meguriDesktop?.shizuki
  if (!bridge) return
  shizukiSaving.value = true
  try {
    const result = await bridge.disconnect()
    if (!result.ok || !result.status) {
      shizukiFeedback.value = { kind: 'error', message: result.error || '断开连接失败。' }
      return
    }
    shizukiStatus.value = result.status
    shizukiFeedback.value = { kind: 'success', message: '已断开 Shizuki 账号连接。' }
  }
  catch {
    shizukiFeedback.value = { kind: 'error', message: '断开连接失败。' }
  }
  finally {
    shizukiSaving.value = false
  }
}

async function setShizukiMainAccount(enabled: boolean) {
  if (shizukiSaving.value) return
  const bridge = window.meguriDesktop?.shizuki
  if (!bridge) return
  shizukiSaving.value = true
  try {
    const result = enabled ? await bridge.setMainAccount() : await bridge.clearMainAccount()
    if (!result.ok || !result.status) {
      shizukiFeedback.value = { kind: 'error', message: result.error || '主账号设置失败。' }
      return
    }
    shizukiStatus.value = result.status
    shizukiFeedback.value = { kind: 'success', message: enabled ? '已绑定当前 Shizuki 主账号。' : '已取消主账号绑定和待发 QQ 提醒。' }
  }
  catch {
    shizukiFeedback.value = { kind: 'error', message: '主账号设置失败。' }
  }
  finally {
    shizukiSaving.value = false
  }
}

async function refreshShizukiDeliveryStatus() {
  try {
    const status = await window.meguriDesktop?.shizuki?.status()
    if (status) shizukiStatus.value = status
  }
  catch {
    shizukiFeedback.value = { kind: 'error', message: '无法读取最新提醒投递状态。' }
  }
}

function setAppearance(appearance: 'system' | 'light' | 'dark') {
  void preferences.update({ appearance })
}

async function retryReadiness() {
  const snapshot = await window.meguriDesktop?.readiness.retry()
  if (snapshot)
    readiness.value = snapshot
}

function runWindowAction(action: 'minimize' | 'hide' | 'centerStage' | 'reloadStage') {
  void window.meguriDesktop?.window[action]()
}

function setAlwaysOnTop(value: boolean) {
  void window.meguriDesktop?.window.setAlwaysOnTop(value)
}

function markStartupDirty() {
  startupDirty.value = true
  startupFeedback.value = { kind: 'neutral', message: '更改尚未保存。' }
}

async function saveStartupSettings() {
  if (startupLoading.value || startupSaving.value)
    return
  const bridge = window.meguriDesktop?.startupSettings
  if (!bridge) {
    startupFeedback.value = { kind: 'error', message: '桌面配置桥接不可用，请重启 Meguri。' }
    return
  }
  startupSaving.value = true
  startupFeedback.value = { kind: 'neutral', message: '正在应用 Windows 启动任务；管理员权限变更可能会显示 UAC 提示…' }
  try {
    const result = await bridge.save(startupDraft.value)
    if (!result.ok || !result.preferences) {
      if (result.preferences) {
        preferences.apply(result.preferences)
        startupDraft.value = { ...result.preferences.startup }
        startupDirty.value = false
      }
      startupStatus.value = result.status ?? { ok: false, message: result.error ?? 'Windows 启动设置没有应用。' }
      startupFeedback.value = { kind: 'error', message: result.error ?? startupStatus.value.message }
      return
    }
    preferences.apply(result.preferences)
    startupDraft.value = { ...result.preferences.startup }
    startupDirty.value = false
    startupStatus.value = result.status ?? { ok: true, message: 'Windows 启动设置已应用。' }
    startupFeedback.value = { kind: 'success', message: startupStatus.value.message }
  }
  catch (error) {
    const detail = startupBridgeErrorMessage(error)
    startupStatus.value = { ok: false, message: `Windows 启动设置应用失败：${detail}` }
    startupFeedback.value = { kind: 'error', message: startupStatus.value.message }
  }
  finally {
    startupSaving.value = false
  }
}

function markRelayDirty() {
  relayDirty.value = true
  relayFeedback.value = { kind: 'neutral', message: '更改尚未保存。' }
}

async function saveRelay() {
  if (relayBusy.value)
    return
  const bridge = window.meguriDesktop?.multimodalRelay
  if (!bridge) {
    relayFeedback.value = { kind: 'error', message: '桌面配置桥接不可用，请重启 Meguri。' }
    return
  }
  relaySaving.value = true
  relayFeedback.value = { kind: 'neutral', message: '正在保存本地配置…' }
  try {
    const result = await bridge.save(relayDraft.value)
    if (!result.ok || !result.config) {
      relayFeedback.value = { kind: 'error', message: result.error ?? '配置没有保存，请检查输入后重试。' }
      return
    }
    applyRelayConfig(result.config)
    relayFeedback.value = { kind: 'success', message: '配置已保存。完整重启 Meguri 后生效。' }
  }
  catch {
    relayFeedback.value = { kind: 'error', message: '保存失败，请检查本地配置目录后重试。' }
  }
  finally {
    relaySaving.value = false
  }
}

async function testRelay() {
  if (relayBusy.value)
    return
  const bridge = window.meguriDesktop?.multimodalRelay
  if (!bridge) {
    relayFeedback.value = { kind: 'error', message: '桌面配置桥接不可用，请重启 Meguri。' }
    return
  }
  relayTesting.value = true
  relayFeedback.value = { kind: 'neutral', message: '正在发送一张极小测试图…' }
  try {
    const result = await bridge.test(relayDraft.value)
    relayFeedback.value = result.ok
      ? { kind: 'success', message: result.message ?? '视觉输入测试通过。' }
      : { kind: 'error', message: result.error ?? '视觉输入测试失败，请检查配置。' }
  }
  catch {
    relayFeedback.value = { kind: 'error', message: '连接测试失败，请检查中转站网络。' }
  }
  finally {
    relayTesting.value = false
  }
}
</script>

<template>
  <main class="settings-shell">
    <header class="settings-titlebar drag-region">
      <div><span class="settings-mark">巡</span><strong>Meguri</strong></div>
      <div class="settings-window-actions no-drag">
        <button type="button" aria-label="最小化设置" title="最小化" @click="runWindowAction('minimize')">—</button>
        <button type="button" aria-label="关闭设置" title="关闭" @click="runWindowAction('hide')">×</button>
      </div>
    </header>

    <section class="settings-content no-drag">
      <header class="settings-page-heading">
        <button v-if="active" class="settings-back" type="button" aria-label="返回设置首页" @click="active = undefined">‹</button>
        <div><small>{{ active ? '设置' : 'MEGURI DESKTOP' }}</small><h1>{{ currentSection?.label ?? '设置' }}</h1><p>{{ currentSection?.description ?? '管理 Meguri 已经支持的桌面能力。' }}</p></div>
      </header>

      <div v-if="!active" class="settings-home">
        <button v-for="section in sections" :key="section.id" type="button" class="settings-entry" @click="active = section.id">
          <div><strong>{{ section.label }}</strong><small>{{ sectionDescription(section) }}</small></div>
          <span aria-hidden="true">{{ section.icon }}</span>
        </button>
        <p class="settings-catalog-note">AIRI 的供应商、插件、更新器和高级模型渲染器会在 Meguri 具备对应运行能力后再加入。</p>
      </div>

      <div v-else class="settings-detail">
        <template v-if="active === 'general'">
          <div class="setting-group">
            <div><strong>颜色模式</strong><small>同步应用到聊天坞、控制岛和设置窗口。</small></div>
            <div class="segmented-control"><button v-for="mode in (['system', 'light', 'dark'] as const)" :key="mode" type="button" :class="{ active: value.appearance === mode }" @click="setAppearance(mode)">{{ mode === 'system' ? '跟随系统' : mode === 'light' ? '浅色' : '深色' }}</button></div>
          </div>
        </template>

        <template v-else-if="active === 'chat'">
          <label class="setting-row"><span><strong>显示嵌入式聊天</strong><small>在角色舞台底部保留 AIRI 风格聊天坞。</small></span><input type="checkbox" :checked="value.chat.visible" @change="preferences.update({ chat: { visible: ($event.target as HTMLInputElement).checked } })"></label>
          <label class="setting-row"><span><strong>默认展开历史</strong><small>使用 55vh 的对话历史区域。</small></span><input type="checkbox" :checked="value.chat.expanded" @change="preferences.update({ chat: { expanded: ($event.target as HTMLInputElement).checked } })"></label>
        </template>

        <template v-else-if="active === 'character'">
          <div class="setting-group"><div><strong>默认立绘</strong><small>选择 Meguri 01–06 套立绘。</small></div><div class="outfit-settings"><button v-for="code in ['01','02','03','04','05','06']" :key="code" type="button" :class="{ active: value.character.outfitCode === code }" @click="preferences.update({ character: { outfitCode: code } })">{{ code }}</button></div></div>
          <label class="setting-row"><span><strong>减少动态效果</strong><small>关闭呼吸、摆动和界面过渡。</small></span><input type="checkbox" :checked="value.character.reducedMotion" @change="preferences.update({ character: { reducedMotion: ($event.target as HTMLInputElement).checked } })"></label>
        </template>

        <template v-else-if="active === 'window'">
          <label class="setting-row"><span><strong>保持窗口置顶</strong><small>让 Meguri 保持在其他应用上方。</small></span><input type="checkbox" :checked="value.window.alwaysOnTop" @change="setAlwaysOnTop(($event.target as HTMLInputElement).checked)"></label>
          <label class="setting-row"><span><strong>悬停隐藏并穿透</strong><small>置顶时，鼠标移到立绘会自动淡出；透明背景可直接点击后方内容，聊天和按钮仍可操作。</small></span><input type="checkbox" :checked="value.window.fadeOnHover" @change="preferences.update({ window: { fadeOnHover: ($event.target as HTMLInputElement).checked } })"></label>
          <div class="settings-actions"><button type="button" @click="runWindowAction('centerStage')">将舞台居中</button><button type="button" @click="runWindowAction('reloadStage')">刷新舞台</button></div>
        </template>

        <template v-else-if="active === 'startup'">
          <form class="agent-settings" @submit.prevent="saveStartupSettings">
            <label class="setting-row">
              <span><strong>登录 Windows 时启动 Meguri</strong><small>登录当前 Windows 账户后自动打开桌宠。</small></span>
              <input v-model="startupDraft.openAtLogin" type="checkbox" :disabled="startupLoading || startupSaving" @change="markStartupDirty">
            </label>

            <label class="setting-row">
              <span><strong>以管理员身份启动 Meguri</strong><small>使用 Windows 任务计划程序的最高权限启动。首次应用时，Windows 会询问一次 UAC 授权；以后登录启动不会每次询问。</small></span>
              <input v-model="startupDraft.runAsAdministrator" type="checkbox" :disabled="startupLoading || startupSaving" @change="markStartupDirty">
            </label>

            <div class="relay-actions startup-actions">
              <button class="primary-action" type="submit" :disabled="startupLoading || startupSaving || (!startupDirty && startupStatus.ok)">
                {{ startupSaving ? '正在应用…' : startupDirty || startupStatus.ok ? '保存启动设置' : '重试应用启动设置' }}
              </button>
            </div>

            <p class="relay-feedback" :class="startupStatus.ok ? 'success' : 'error'" role="status" aria-live="polite">
              {{ startupStatus.message }}
            </p>
            <p v-if="startupFeedback" class="relay-feedback" :class="startupFeedback.kind" role="status" aria-live="polite">
              {{ startupFeedback.message }}
            </p>
          </form>
        </template>

        <template v-else-if="active === 'speech'">
          <div class="setting-group"><div><strong>语音输入</strong><small>聊天坞麦克风使用本机 Windows 中文语音识别。识别结果先放进草稿，供你修改后发送。</small></div></div>
          <label class="setting-row"><span><strong>播放回复与主账号提醒语音</strong><small>使用本地 GPT-SoVITS 朗读回复和到点提醒。</small></span><input type="checkbox" :checked="value.speech.ttsEnabled" @change="preferences.update({ speech: { ttsEnabled: ($event.target as HTMLInputElement).checked } })"></label>
          <label class="setting-slider"><span><strong>播放音量</strong><small>{{ Math.round(value.speech.volume * 100) }}%</small></span><input type="range" min="0" max="1" step="0.05" :value="value.speech.volume" @input="preferences.update({ speech: { volume: Number(($event.target as HTMLInputElement).value) } })"></label>
        </template>

        <template v-else-if="active === 'shizuki'">
          <p v-if="shizukiLoading" class="settings-catalog-note">正在读取 Shizuki 连接状态…</p>
          <template v-else>
            <div class="relay-route" :class="{ enabled: shizukiStatus.connected }">
              <div class="relay-route-line"><span>Meguri 待办</span><i aria-hidden="true">→</i><strong>Shizuki TimePrism</strong></div>
              <small>{{ shizukiStatus.connected ? `已连接 ${shizukiStatus.email}` : '连接后可在聊天坞创建、查看和完成待办' }}</small>
            </div>
            <form v-if="!shizukiStatus.connected" class="relay-settings-form" @submit.prevent="connectShizuki">
              <label class="setting-group relay-field"><span><strong>站点地址</strong><small>默认使用 Shizuki 生产 HTTPS 入口。</small></span><input v-model.trim="shizukiDraft.origin" type="url" maxlength="2048" autocomplete="url" spellcheck="false" :disabled="shizukiSaving"></label>
              <label class="setting-group relay-field"><span><strong>登录邮箱</strong><small>使用 Shizuki 账号；浏览器登录状态不会自动导入。</small></span><input v-model.trim="shizukiDraft.email" type="email" maxlength="320" autocomplete="username" :disabled="shizukiSaving"></label>
              <label class="setting-group relay-field"><span><strong>密码</strong><small>仅用于本次登录，Meguri 只加密保存续期令牌。</small></span><input v-model="shizukiDraft.password" type="password" maxlength="1024" autocomplete="off" spellcheck="false" :disabled="shizukiSaving"></label>
              <div class="relay-actions"><button class="primary-action" type="submit" :disabled="shizukiSaving || !shizukiDraft.email || !shizukiDraft.password">{{ shizukiSaving ? '正在连接…' : '连接 Shizuki' }}</button></div>
            </form>
            <div v-else class="relay-settings-form">
              <p class="settings-catalog-note">账号编号 {{ shizukiStatus.userId }}。到点提醒会在 Meguri 运行时显示；关闭桌宠后不能准时送达。仅支持邮箱密码连接。</p>
              <p class="settings-catalog-note">{{ shizukiStatus.mainUserId === shizukiStatus.userId ? '当前账号是主账号；到点会尝试语音播报并上传 QQ 提醒。' : shizukiStatus.mainUserId ? `主账号已绑定编号 ${shizukiStatus.mainUserId}；当前账号不会向主账号 QQ 推送。` : '尚未选择主账号。' }}</p>
              <div class="relay-actions"><button v-if="!shizukiStatus.mainUserId" type="button" :disabled="shizukiSaving" @click="setShizukiMainAccount(true)">设为主账号</button><button v-else type="button" :disabled="shizukiSaving" @click="setShizukiMainAccount(false)">取消主账号绑定</button></div>
              <p v-if="shizukiStatus.mainUserId" class="settings-catalog-note">QQ Core 上传凭据：{{ shizukiStatus.qqUploadConfigured ? '本机已配置' : '本机未配置' }}；待上传 {{ shizukiStatus.qqPending ?? 0 }} 条。Core 接收后仍需 AstrBot 配置并成功投递，才会出现在 QQ。</p>
              <p v-if="shizukiStatus.qqError" class="relay-feedback error" role="status">{{ shizukiStatus.qqError }}</p>
              <p v-if="shizukiStatus.reminderError" class="relay-feedback error" role="alert">桌面提醒：{{ shizukiStatus.reminderError }}</p>
              <div class="relay-actions"><button type="button" @click="refreshShizukiDeliveryStatus">刷新提醒投递状态</button></div>
              <div class="relay-actions"><button type="button" :disabled="shizukiSaving" @click="disconnectShizuki">{{ shizukiSaving ? '正在断开…' : '断开连接' }}</button></div>
            </div>
            <div v-if="!shizukiStatus.connected && shizukiStatus.mainUserId" class="relay-settings-form"><p class="settings-catalog-note">已绑定主账号编号 {{ shizukiStatus.mainUserId }}。重新连接该账号后才会触发语音和 QQ 提醒。</p><button type="button" :disabled="shizukiSaving" @click="setShizukiMainAccount(false)">取消主账号绑定</button></div>
            <p v-if="shizukiFeedback" class="relay-feedback" :class="shizukiFeedback.kind" role="status" aria-live="polite">{{ shizukiFeedback.message }}</p>
            <p v-else-if="shizukiStatus.error" class="relay-feedback error" role="alert">{{ shizukiStatus.error }}</p>
          </template>
        </template>

        <template v-else-if="active === 'shortcuts'">
          <label class="setting-group"><div><strong>显示或隐藏 Meguri</strong><small>选择一个全局快捷键。</small></div><select :value="value.shortcuts.toggleVisibility" @change="preferences.update({ shortcuts: { toggleVisibility: ($event.target as HTMLSelectElement).value } })"><option>CommandOrControl+Shift+M</option><option>CommandOrControl+Alt+M</option><option>CommandOrControl+Shift+Space</option></select></label>
          <div class="setting-group shortcut-reference"><div><strong>AIRI 全局快捷键</strong><small>这些组合在其他应用获得焦点时也可使用。</small></div><ul><li><kbd>Ctrl+Alt+H</kbd><span>显示/隐藏聊天</span></li><li><kbd>Ctrl+Alt+C</kbd><span>窗口居中</span></li><li><kbd>Ctrl+Alt+Shift+M</kbd><span>窗口居中（fork 兼容）</span></li><li><kbd>Ctrl+Alt+[</kbd><span>上一套立绘</span></li><li><kbd>Ctrl+Alt+]</kbd><span>下一套立绘</span></li></ul></div>
        </template>

        <template v-else-if="active === 'multimodal'">
          <div class="relay-route" :class="{ enabled: relay.enabled && relayConfigured }" aria-label="视觉附件路由">
            <div class="relay-route-line">
              <span>图片 / PDF</span>
              <i aria-hidden="true">→</i>
              <strong>{{ relayDraft.model || '视觉中转站' }}</strong>
            </div>
            <small>普通文字继续使用当前 DeepSeek 模型</small>
          </div>

          <form class="relay-settings-form" @submit.prevent="saveRelay">
            <label class="setting-row">
              <span><strong>启用视觉中转站</strong><small>仅图片和 PDF 使用此路由；保存后需完整重启。</small></span>
              <input v-model="relayDraft.enabled" type="checkbox" :disabled="relayBusy" @change="markRelayDirty">
            </label>

            <label class="setting-group relay-field">
              <span><strong>Base URL</strong><small>填写 OpenAI-compatible 地址，并包含版本路径，例如 /v1。</small></span>
              <input
                v-model.trim="relayDraft.baseUrl"
                type="url"
                maxlength="2048"
                inputmode="url"
                autocomplete="url"
                spellcheck="false"
                placeholder="https://relay.example.com/v1"
                :disabled="relayBusy"
                @input="markRelayDirty"
              >
            </label>

            <label class="setting-group relay-field">
              <span><strong>视觉模型 ID</strong><small>例如 qwen3.7-flash；必须是中转站实际支持图片输入的模型。</small></span>
              <input
                v-model.trim="relayDraft.model"
                type="text"
                maxlength="128"
                autocomplete="off"
                spellcheck="false"
                placeholder="qwen3.7-flash"
                :disabled="relayBusy"
                @input="markRelayDirty"
              >
            </label>

            <label class="setting-group relay-field">
              <span>
                <strong>API Key</strong>
                <small>{{ relay.apiKeyConfigured ? '已保存密钥；留空不会覆盖。输入新值可替换。' : '密钥只保存到受信任的本地 secret 文件。' }}</small>
              </span>
              <input
                v-model="relayDraft.apiKey"
                type="password"
                maxlength="8192"
                autocomplete="new-password"
                spellcheck="false"
                :placeholder="relay.apiKeyConfigured ? '••••••••（已保存）' : '输入 API Key'"
                :disabled="relayBusy"
                @input="markRelayDirty"
              >
            </label>

            <div class="relay-actions">
              <button class="primary-action" type="submit" :disabled="relayBusy || !relayDirty">
                {{ relaySaving ? '正在保存…' : '保存配置' }}
              </button>
              <button type="button" :disabled="relayBusy" @click="testRelay">
                {{ relayTesting ? '正在测试…' : '测试视觉连接' }}
              </button>
            </div>

            <p v-if="relayFeedback" class="relay-feedback" :class="relayFeedback.kind" role="status" aria-live="polite">
              {{ relayFeedback.message }}
            </p>
          </form>
        </template>

        <template v-else-if="active === 'agents'">
          <div class="agent-facts">
            <div><span>Codex 可执行文件</span><b :class="{ warn: !agentSettings.install.codex.installed }">{{ agentSettings.install.codex.installed ? '已找到' : '未找到' }}</b></div>
            <div><span>Codex 登录状态</span><b :class="{ warn: !agentSettings.install.codex.signedIn }">{{ agentSettings.install.codex.signedIn ? '已登录' : '未确认' }}</b></div>
            <div><span>DeepSeek Harness CLI</span><b :class="{ warn: agentSettings.install.harness.cli !== 'installed' }">{{ agentInstallLabel(agentSettings.install.harness.cli) }}</b></div>
            <div><span>Harness 模型凭据</span><b :class="{ warn: agentSettings.install.harness.credentials !== 'configured' }">{{ agentInstallLabel(agentSettings.install.harness.credentials) }}</b></div>
            <div><span>Meguri 工作预设</span><b :class="{ warn: agentSettings.install.harness.preset === 'unavailable' }">{{ agentInstallLabel(agentSettings.install.harness.preset) }}</b></div>
          </div>

          <form class="agent-settings" @submit.prevent="saveAgentSettings">
            <label class="setting-row">
              <span><strong>启用任务转交</strong><small>Meguri 只整理任务卡；正文由你确认后才发送，且每张卡只发送一次。</small></span>
              <input v-model="agentDraft.enabled" type="checkbox" :disabled="agentBusy" @change="markAgentDirty">
            </label>

            <label class="setting-row">
              <span><strong>由 Meguri 启动 Harness 网页宿主</strong><small>额外启动本机 Harness 服务；不改变 Harness 自己的沙箱、批准与模型设置。</small></span>
              <input v-model="agentDraft.harnessAutoStart" type="checkbox" :disabled="agentBusy || !agentDraft.enabled" @change="markAgentDirty">
            </label>

            <label class="agent-field">
              <span>
                <strong>Harness 数据目录（可选）</strong>
                <small>留空使用默认目录；只用于安装工作预设和读取启动地址，不读取任何密钥。</small>
              </span>
              <input
                v-model.trim="agentDraft.dshHome"
                type="text"
                maxlength="4096"
                spellcheck="false"
                placeholder="C:\\Users\\you\\.dsh"
                :disabled="agentBusy"
                @input="markAgentDirty"
              >
            </label>

            <div class="relay-actions">
              <button class="primary-action" type="submit" :disabled="agentBusy || !agentDirty">
                {{ agentSaving ? '正在保存…' : '保存配置' }}
              </button>
              <button type="button" :disabled="agentBusy || !agentSettings.enabled" @click="testAgentConnections">
                {{ agentProbing ? '正在测试…' : '测试连接' }}
              </button>
            </div>

            <div v-if="agentProbe" class="agent-facts" role="status" aria-live="polite">
              <div><span>Codex</span><b :class="{ warn: !agentProbe.codex.ok }">{{ agentProbe.codex.message }}</b></div>
              <div><span>DeepSeek Harness</span><b :class="{ warn: !agentProbe.harness.ok }">{{ agentProbe.harness.message }}</b></div>
            </div>
          </form>

          <p class="settings-catalog-note">
            桌宠不安装、不升级、不代登录 Codex 或 DeepSeek Harness，也从不读取它们的模型凭据。
            需要命令或文件审批的任务请在本地执行器中处理，桌宠没有审批界面。
          </p>
        </template>

        <template v-else>
          <div class="service-list"><div v-for="item in readiness.items" :key="item.capability" :class="item.status"><span/><div><strong>{{ item.label }}</strong><small>{{ item.detail }}</small></div><b>{{ item.status === 'ready' ? '就绪' : item.status === 'failed' ? '失败' : '检查中' }}</b></div></div>
          <button class="secondary-action" type="button" @click="retryReadiness">重新检查</button>
        </template>
      </div>
    </section>
  </main>
</template>
