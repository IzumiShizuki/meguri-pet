<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, nextTick, onMounted, ref } from 'vue'

import type { ForwardRequest, WorkExecutor } from '../../shared/desktop-contract'
import { useWorkStore } from '../stores/work'

const emit = defineEmits<{ close: [] }>()

const work = useWorkStore()
const { requests, targets, projects, busy, searching, error, notice, connection } = storeToRefs(work)

const draftText = ref('')
const executor = ref<WorkExecutor>('codex')
const targetId = ref('')
const projectPath = ref('')
const targetQuery = ref('')
let searchTimer: ReturnType<typeof setTimeout> | undefined

const canPrepare = computed(() => draftText.value.trim().length > 0 && !busy.value)
const pendingCards = computed(() => requests.value.filter(record => record.phase === 'awaiting_confirmation'))
const activeCards = computed(() => requests.value.filter(record =>
  record.confirmedAt && ['forwarding', 'accepted', 'unknown'].includes(record.phase)))
const settledCards = computed(() => requests.value.filter(record =>
  ['completed', 'unavailable'].includes(record.phase)))

function phaseLabel(record: ForwardRequest): string {
  if (record.phase === 'awaiting_confirmation') return '待你确认'
  if (record.phase === 'forwarding') return '正在转交'
  if (record.phase === 'accepted') return record.nativeStatus === 'working' ? '执行中' : '已接收'
  if (record.phase === 'completed') return '已完成'
  if (record.phase === 'unknown') return '结果未知'
  return '未能完成'
}

function phaseTone(record: ForwardRequest): string {
  if (record.phase === 'completed') return 'is-done'
  if (['unknown', 'unavailable'].includes(record.phase)) return 'is-warn'
  if (record.phase === 'awaiting_confirmation') return 'is-pending'
  return 'is-running'
}

function executorLabel(value: WorkExecutor): string {
  return value === 'harness' ? 'DeepSeek Harness' : 'Codex'
}

function connectionLabel(state: string): string {
  if (state === 'compatible' || state === 'ready') return '已就绪'
  if (state === 'disabled') return '未启用'
  if (state === 'authentication_required') return '需要登录'
  if (state === 'incompatible') return '版本不兼容'
  return '未连接'
}

function onTargetQueryChange() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => void work.refreshTargets(targetQuery.value), 200)
}

function selectTarget(id: string) {
  targetId.value = targetId.value === id ? '' : id
}

async function createCard() {
  if (!canPrepare.value)
    return
  const created = await work.prepare({
    text: draftText.value.trim(),
    executor: executor.value,
    ...(executor.value === 'codex'
      ? (targetId.value ? { targetId: targetId.value } : {})
      : (projectPath.value ? { projectPath: projectPath.value } : {})),
  })
  if (created) {
    draftText.value = ''
    targetId.value = ''
    await nextTick()
  }
}

async function confirmCard(record: ForwardRequest) {
  await work.confirm(record.id, record.version)
}

async function openNative(record: ForwardRequest) {
  await work.openNative(record.id)
}

function clearDraft() {
  draftText.value = ''
  targetId.value = ''
  projectPath.value = ''
  work.clearFeedback()
}

onMounted(async () => {
  await work.start()
  await Promise.all([work.refreshTargets(''), work.refreshProjects()])
})
</script>

<template>
  <section class="work-panel no-drag" aria-label="转交工作任务">
    <header class="work-heading">
      <div>
        <strong>交给本机工作 Agent</strong>
        <small>Meguri 只整理任务卡；正文由你确认后才转交，且只发送一次。</small>
      </div>
      <button type="button" class="work-close" aria-label="关闭" @click="emit('close')">×</button>
    </header>

    <div class="work-connections">
      <span :class="connectionLabel(connection.codex) === '已就绪' ? 'ok' : 'off'">Codex · {{ connectionLabel(connection.codex) }}</span>
      <span :class="connectionLabel(connection.harness) === '已就绪' ? 'ok' : 'off'">Harness · {{ connectionLabel(connection.harness) }}</span>
    </div>

    <form class="work-composer" @submit.prevent="createCard">
      <textarea
        v-model="draftText"
        rows="3"
        maxlength="20000"
        placeholder="描述你想完成的事情，例如：检查相机模块并修掉帧率抖动。"
        aria-label="任务内容"
      />

      <div class="work-executor">
        <button type="button" :class="{ active: executor === 'codex' }" @click="executor = 'codex'">Codex</button>
        <button type="button" :class="{ active: executor === 'harness' }" @click="executor = 'harness'">DeepSeek Harness</button>
      </div>

      <template v-if="executor === 'codex'">
        <input
          v-model.trim="targetQuery"
          type="search"
          maxlength="200"
          placeholder="搜索已有 Codex 任务…"
          aria-label="搜索 Codex 任务"
          @input="onTargetQueryChange"
        >
        <p v-if="searching" class="work-hint">正在读取 Codex 任务列表…</p>
        <div v-else-if="targets.length" class="work-options" role="listbox" aria-label="Codex 任务">
          <button
            v-for="target in targets.slice(0, 12)"
            :key="target.targetId"
            type="button"
            role="option"
            :aria-selected="targetId === target.targetId"
            :class="{ active: targetId === target.targetId }"
            @click="selectTarget(target.targetId)"
          >
            <span>{{ target.title }}</span>
            <small>{{ target.projectPath }}</small>
          </button>
        </div>
        <p v-else class="work-hint">没有可用的 Codex 任务；请先在 Codex 中创建或打开一个任务。</p>
        <p v-if="targetId" class="work-hint">已选择任务；项目目录会跟随 Codex 任务自身，不需要另外指定。</p>
      </template>

      <template v-else>
        <label class="work-field">
          <span>项目目录</span>
          <input v-model.trim="projectPath" type="text" maxlength="4096" placeholder="D:\\program\\your-project" spellcheck="false">
        </label>
        <div v-if="projects.length" class="work-options" role="listbox" aria-label="本地项目">
          <button
            v-for="project in projects.slice(0, 8)"
            :key="project.id"
            type="button"
            role="option"
            :aria-selected="projectPath === project.rootPath"
            :class="{ active: projectPath === project.rootPath }"
            @click="projectPath = project.rootPath"
          >
            <span>{{ project.name }}</span>
            <small>{{ project.rootPath }}</small>
          </button>
        </div>
        <p class="work-hint">Harness 会在该目录下执行，并沿用 Harness 自己的沙箱与批准设置。</p>
      </template>

      <div class="work-actions">
        <button class="primary-action" type="submit" :disabled="!canPrepare">
          {{ busy ? '正在整理…' : '整理任务卡' }}
        </button>
        <button type="button" @click="clearDraft">清空</button>
      </div>
    </form>

    <p v-if="notice" class="work-feedback success" role="status">{{ notice }}</p>
    <p v-if="error" class="work-feedback error" role="alert">{{ error }}</p>

    <section v-if="pendingCards.length" class="work-list" aria-label="待确认任务卡">
      <h3>待你确认</h3>
      <article v-for="record in pendingCards" :key="record.id" class="work-card is-pending">
        <header>
          <strong>{{ record.plan?.title || '未命名任务' }}</strong>
          <span>{{ executorLabel(record.executor) }}</span>
        </header>
        <p class="work-text">{{ record.text }}</p>
        <p v-if="record.target?.title" class="work-meta">Codex 任务：{{ record.target.title }}</p>
        <p v-if="record.project?.rootPath" class="work-meta">目录：{{ record.project.rootPath }}</p>
        <p v-if="record.plan?.reason" class="work-meta">{{ record.plan.reason }}</p>
        <div class="work-actions">
          <button class="primary-action" type="button" :disabled="busy" @click="confirmCard(record)">确认并转交</button>
          <button type="button" @click="work.dismiss(record.id, record.version)">丢弃</button>
        </div>
      </article>
    </section>

    <section v-if="activeCards.length" class="work-list" aria-label="进行中的任务">
      <h3>进行中</h3>
      <article v-for="record in activeCards" :key="record.id" class="work-card" :class="phaseTone(record)">
        <header>
          <strong>{{ phaseLabel(record) }}</strong>
          <span>{{ executorLabel(record.executor) }}</span>
        </header>
        <p class="work-text">{{ record.text }}</p>
        <p v-if="record.detail" class="work-meta">{{ record.detail }}</p>
        <p v-if="record.nativeApprovalTools?.length" class="work-meta">
          等待原生批准：{{ record.nativeApprovalTools.join('、') }}（请在本地执行器中处理）
        </p>
        <div class="work-actions">
          <button type="button" @click="work.refresh(record.id)">刷新回执</button>
          <button type="button" @click="openNative(record)">打开本地执行器</button>
        </div>
      </article>
    </section>

    <section v-if="settledCards.length" class="work-list" aria-label="已结束的任务">
      <h3>已结束</h3>
      <article v-for="record in settledCards" :key="record.id" class="work-card" :class="phaseTone(record)">
        <header>
          <strong>{{ phaseLabel(record) }}</strong>
          <span>{{ executorLabel(record.executor) }}</span>
        </header>
        <p v-if="record.result" class="work-result">{{ record.result }}</p>
        <p v-else-if="record.detail" class="work-meta">{{ record.detail }}</p>
        <div class="work-actions">
          <button type="button" @click="openNative(record)">打开本地执行器</button>
          <button v-if="!record.reminderCleared" type="button" @click="work.dismiss(record.id, record.version)">隐藏提醒</button>
        </div>
      </article>
    </section>

    <p v-if="!requests.length" class="work-empty">
      还没有任务卡。先在本地安装并登录 Codex 或 DeepSeek Harness，然后在上面描述一件事。
    </p>
  </section>
</template>
