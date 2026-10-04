<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

import type { SkillCatalogView, SkillRevisionView, SkillValidationIssue } from '../services/skills-api'
import { useSkillsStore } from '../stores/skills'
import { canActivateRevision } from '../utils/skill-presentation'

const props = defineProps<{ sessionId: string }>()
const emit = defineEmits<{ close: [] }>()

const skills = useSkillsStore()
const {
  tab,
  query,
  results,
  searchPage,
  remoteDetail,
  selectedInstalled,
  selectedRemoteId,
  selectedInstalledId,
  installed,
  updates,
  searching,
  loadingDetail,
  loadingInstalled,
  operation,
  operationTarget,
  error,
  notice,
} = storeToRefs(skills)
const searchInput = ref<HTMLInputElement>()
const licenseConfirmations = ref<Record<string, boolean>>({})

const remoteAlreadyInstalled = computed(() => installed.value.some(skill =>
  skill.manifest.source_id === 'modelscope'
  && skill.manifest.external_id === remoteDetail.value?.summary.external_id))

const selectedUpdate = computed(() => selectedInstalled.value
  ? updates.value[selectedInstalled.value.manifest.id]
  : undefined)

function switchTab(next: 'discover' | 'installed') {
  skills.tab = next
  skills.clearFeedback()
  if (next === 'installed' && !skills.selectedInstalledId && skills.installed[0])
    skills.selectedInstalledId = skills.installed[0].manifest.id
  if (next === 'discover')
    void nextTick(() => searchInput.value?.focus())
}

function selectInstalled(skill: SkillCatalogView) {
  skills.selectedInstalledId = skill.manifest.id
  skills.clearFeedback()
}

function statusLabel(revision: SkillRevisionView): string {
  if (revision.active) return '已启用'
  if (revision.report.state === 'QUARANTINED') return '隔离中'
  if (revision.report.state === 'INVALID') return '校验失败'
  if (!revision.report.compatible) return '缺少能力'
  return '可启用'
}

function statusIcon(revision: SkillRevisionView): string {
  if (revision.active) return '✓'
  if (revision.report.state === 'INVALID') return '×'
  if (revision.report.state === 'QUARANTINED') return '…'
  if (!revision.report.compatible) return '!'
  return '○'
}

function issueLabel(issue: SkillValidationIssue): string {
  const labels: Record<string, string> = {
    SKILL_LICENSE_MISSING: '未声明许可证；只允许确认后本地使用，不能随 Meguri 分发。',
    SKILL_ALWAYS_IGNORED: 'always 已忽略；Meguri 仍按当前问题相关性选择。',
    SKILL_REQUIREMENT_TOOL_UNSUPPORTED: '需要 V1 不支持的终端或代码执行能力。',
    SKILL_REQUIREMENT_TOOL_MISSING: '当前 Capability 快照中缺少所需工具。',
    SKILL_REQUIREMENT_ENV_MISSING: '缺少所需环境配置；密钥值不会展示给 Skill。',
    SKILL_DANGEROUS_BINARY: '包内包含不允许的二进制文件。',
    SKILL_SYMLINK_REJECTED: '包内包含不允许的软链接。',
    SKILL_MANIFEST_DUPLICATE: '包内必须且只能有一个根目录 SKILL.md。',
  }
  return labels[issue.code] ?? issue.message ?? issue.code
}

function shortDigest(digest: string): string {
  return `${digest.slice(0, 8)}…${digest.slice(-6)}`
}

function dateLabel(value?: string): string {
  if (!value) return '时间未知'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(date)
}

function revisionLicense(revision: SkillRevisionView): string | undefined {
  return revision.manifest?.license ?? selectedInstalled.value?.manifest.license
}

function canActivate(revision: SkillRevisionView): boolean {
  return canActivateRevision(
    revision,
    Boolean(licenseConfirmations.value[revision.digest]),
    selectedInstalled.value?.manifest.license,
  )
}

function activateLabel(revision: SkillRevisionView, index: number): string {
  if (revision.active) return '当前版本'
  return index === 0 ? '启用此版本' : '回滚到此版本'
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    emit('close')
  }
  if (event.key === '/' && !event.ctrlKey && !event.metaKey && !event.altKey) {
    const target = event.target as HTMLElement | null
    if (!target?.matches('input, textarea')) {
      event.preventDefault()
      switchTab('discover')
    }
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  await skills.loadInstalled(props.sessionId)
  if (!skills.selectedInstalledId && skills.installed[0])
    skills.selectedInstalledId = skills.installed[0].manifest.id
  await nextTick()
  searchInput.value?.focus()
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <section class="skills-workspace no-drag" aria-labelledby="skills-title">
    <header class="skills-header">
      <div>
        <p class="skills-kicker">EXTERNAL SKILL CATALOG</p>
        <h1 id="skills-title">技能卡册</h1>
        <p>浏览魔搭公开 Skill；所有正文都按不可信外部数据处理。</p>
      </div>
      <button class="skills-close" type="button" aria-label="关闭技能卡册" title="关闭（Esc）" @click="emit('close')">×</button>
    </header>

    <nav class="skills-tabs" aria-label="Skill 工作区">
      <button type="button" :class="{ active: tab === 'discover' }" :aria-current="tab === 'discover' ? 'page' : undefined" @click="switchTab('discover')">
        魔搭发现
      </button>
      <button type="button" :class="{ active: tab === 'installed' }" :aria-current="tab === 'installed' ? 'page' : undefined" @click="switchTab('installed')">
        本地卡册 <span>{{ installed.length }}</span>
      </button>
    </nav>

    <div class="skills-feedback" aria-live="polite" aria-atomic="true">
      <p v-if="operation" class="skills-progress"><span aria-hidden="true" />{{ operation === 'import' ? '正在下载、隔离并校验…' : '正在更新 Skill 状态…' }}</p>
      <p v-else-if="error" class="skills-error"><b>!</b>{{ error }}</p>
      <p v-else-if="notice" class="skills-notice"><b>✓</b>{{ notice }}</p>
    </div>

    <div v-if="tab === 'discover'" class="skills-layout">
      <section class="skills-index" aria-label="魔搭搜索结果">
        <form class="skills-search" @submit.prevent="skills.search(sessionId)">
          <label for="skill-search">搜索公开 Skill</label>
          <div>
            <input id="skill-search" ref="searchInput" v-model="query" type="search" autocomplete="off" placeholder="例如 PDF、表格、研究…" />
            <button type="submit" :disabled="searching">{{ searching ? '搜索中' : '搜索' }}</button>
          </div>
          <small>按 / 聚焦搜索。V1 只浏览公开目录，不需要魔搭 Token。</small>
        </form>

        <div v-if="!searchPage && !searching" class="skills-empty">
          <span aria-hidden="true">⌕</span>
          <strong>从一个具体任务开始</strong>
          <p>搜索只返回摘要；选中后才读取详情，导入后仍默认禁用。</p>
        </div>
        <div v-else-if="searchPage && !results.length" class="skills-empty">
          <span aria-hidden="true">○</span><strong>没有匹配结果</strong><p>换一个更短的任务词再试。</p>
        </div>
        <ol v-else class="skill-card-list" aria-label="搜索结果">
          <li v-for="result in results" :key="result.external_id">
            <button type="button" :class="{ selected: selectedRemoteId === result.external_id }" @click="skills.loadRemoteDetail(sessionId, result.external_id)">
              <span class="skill-card-source">MODELSCOPE</span>
              <strong>{{ result.name }}</strong>
              <p>{{ result.description || '没有公开简介。' }}</p>
              <span class="skill-card-meta">{{ result.license || '许可证未声明' }} · {{ result.tags.slice(0, 2).join(' / ') || '未分类' }}</span>
            </button>
          </li>
        </ol>
        <footer v-if="searchPage && searchPage.total > searchPage.page_size" class="skills-pagination">
          <button type="button" :disabled="searchPage.page <= 1 || searching" @click="skills.search(sessionId, searchPage.page - 1)">上一页</button>
          <span>第 {{ searchPage.page }} 页 · 共 {{ searchPage.total }} 项</span>
          <button type="button" :disabled="searchPage.page * searchPage.page_size >= searchPage.total || searching" @click="skills.search(sessionId, searchPage.page + 1)">下一页</button>
        </footer>
      </section>

      <aside class="skills-detail" aria-label="魔搭 Skill 详情">
        <div v-if="loadingDetail" class="skills-empty"><span aria-hidden="true">…</span><strong>读取详情</strong></div>
        <template v-else-if="remoteDetail">
          <div class="skill-detail-heading">
            <span>PUBLIC SOURCE</span>
            <h2>{{ remoteDetail.summary.name }}</h2>
            <p>{{ remoteDetail.summary.description || '没有公开简介。' }}</p>
          </div>
          <dl class="skill-facts">
            <div><dt>许可证</dt><dd>{{ remoteDetail.summary.license || '未声明 · 启用时需确认' }}</dd></div>
            <div><dt>版本标识</dt><dd>{{ remoteDetail.summary.revision || '来源未提供' }}</dd></div>
            <div><dt>开发者</dt><dd>{{ remoteDetail.metadata.developer || '未提供' }}</dd></div>
            <div><dt>更新时间</dt><dd>{{ dateLabel(remoteDetail.summary.updated_at) }}</dd></div>
          </dl>
          <section class="skill-requirements">
            <h3>运行要求</h3>
            <p v-if="!remoteDetail.requirements.tools.length && !remoteDetail.requirements.env.length">未声明工具或环境要求。</p>
            <div v-if="remoteDetail.requirements.tools.length"><span>工具</span><code v-for="tool in remoteDetail.requirements.tools" :key="tool">{{ tool }}</code></div>
            <div v-if="remoteDetail.requirements.env.length"><span>环境名</span><code v-for="name in remoteDetail.requirements.env" :key="name">{{ name }}</code></div>
          </section>
          <div class="skill-trust-note"><b>UNTRUSTED_EXTERNAL</b><p>脚本不会由 Skill 子系统执行；所有建议动作仍需命中本轮冻结 Capability。</p></div>
          <button class="skills-primary" type="button" :disabled="Boolean(operation) || remoteAlreadyInstalled" @click="skills.importRemote(sessionId, remoteDetail.summary.external_id)">
            {{ remoteAlreadyInstalled ? '已在本地卡册' : operation === 'import' && operationTarget === remoteDetail.summary.external_id ? '正在导入…' : '导入并校验' }}
          </button>
        </template>
        <div v-else class="skills-empty"><span aria-hidden="true">↗</span><strong>选择一个搜索结果</strong><p>这里会显示许可证、依赖与来源信息。</p></div>
      </aside>
    </div>

    <div v-else class="skills-layout installed-layout">
      <section class="skills-index" aria-label="已安装 Skill">
        <div class="skills-section-title"><div><span>LOCAL REVISIONS</span><h2>已安装</h2></div><button type="button" :disabled="loadingInstalled" @click="skills.loadInstalled(sessionId)">刷新</button></div>
        <div v-if="loadingInstalled" class="skills-empty"><span aria-hidden="true">…</span><strong>读取本地卡册</strong></div>
        <div v-else-if="!installed.length" class="skills-empty"><span aria-hidden="true">□</span><strong>还没有导入 Skill</strong><p>前往“魔搭发现”搜索公开目录。</p><button type="button" @click="switchTab('discover')">开始搜索</button></div>
        <ol v-else class="skill-card-list installed-list">
          <li v-for="skill in installed" :key="skill.manifest.id">
            <button type="button" :class="{ selected: selectedInstalledId === skill.manifest.id }" @click="selectInstalled(skill)">
              <span class="skill-state"><i :class="skill.binding.state.toLowerCase()" />{{ skill.binding.state === 'ENABLED' ? '已启用' : '已停用' }}</span>
              <strong>{{ skill.manifest.name }}</strong>
              <p>{{ skill.manifest.description || '没有简介。' }}</p>
              <span class="skill-card-meta">{{ skill.revisions.length }} 个版本 · {{ skill.manifest.source_id }}</span>
            </button>
          </li>
        </ol>
      </section>

      <aside class="skills-detail installed-detail" aria-label="已安装 Skill 详情">
        <template v-if="selectedInstalled">
          <div class="skill-detail-heading">
            <span>{{ selectedInstalled.manifest.source_id.toUpperCase() }} / LOCAL</span>
            <h2>{{ selectedInstalled.manifest.name }}</h2>
            <p>{{ selectedInstalled.manifest.description || '没有简介。' }}</p>
          </div>
          <div class="skill-trust-note"><b>{{ selectedInstalled.manifest.trust }}</b><p>启用只允许进入相关 AGENT Turn 的受限披露，不会提升执行权限。</p></div>
          <div class="skill-lifecycle-actions">
            <button v-if="selectedInstalled.binding.state === 'ENABLED'" type="button" :disabled="Boolean(operation)" @click="skills.disable(sessionId, selectedInstalled.manifest.id)">停用</button>
            <button type="button" :disabled="Boolean(operation)" @click="skills.checkUpdate(sessionId, selectedInstalled.manifest.id)">检查更新</button>
            <button v-if="selectedUpdate?.update_available" class="accent" type="button" :disabled="Boolean(operation)" @click="skills.importUpdate(sessionId, selectedInstalled.manifest.id)">导入新版本</button>
          </div>
          <p v-if="selectedUpdate" class="update-status"><b>{{ selectedUpdate.update_available ? '有新版本' : '已是最新' }}</b><span>{{ selectedUpdate.current_revision || '本地未知' }} → {{ selectedUpdate.latest_revision || '来源未知' }}</span></p>

          <section class="revision-ledger">
            <header><div><span>IMMUTABLE HISTORY</span><h3>版本与校验</h3></div><small>摘要是版本书脊；回滚不会删除历史。</small></header>
            <article v-for="(revision, index) in [...selectedInstalled.revisions].reverse()" :key="revision.digest" class="revision-card" :class="{ active: revision.active, invalid: revision.report.state === 'INVALID' }">
              <div class="revision-spine" :title="revision.digest"><b>SHA-256</b><code>{{ shortDigest(revision.digest) }}</code></div>
              <div class="revision-content">
                <header>
                  <div><span class="revision-status" :class="revision.report.state.toLowerCase()"><b>{{ statusIcon(revision) }}</b>{{ statusLabel(revision) }}</span><small>{{ dateLabel(revision.imported_at) }}</small></div>
                  <code>{{ revision.source_revision || '无来源版本' }}</code>
                </header>
                <ul v-if="revision.report.issues.length" class="validation-list" aria-label="校验问题">
                  <li v-for="issue in revision.report.issues" :key="`${revision.digest}-${issue.code}-${issue.path}`" :class="issue.severity.toLowerCase()">
                    <b>{{ issue.severity === 'ERROR' ? '×' : '!' }}</b><span>{{ issueLabel(issue) }}<small v-if="issue.path">{{ issue.path }}</small></span>
                  </li>
                </ul>
                <p v-else class="validation-pass"><b>✓</b>结构、摘要与兼容性检查均通过。</p>
                <label v-if="!revisionLicense(revision) && revision.report.state === 'VALID' && revision.report.compatible" class="license-confirm">
                  <input v-model="licenseConfirmations[revision.digest]" type="checkbox" />
                  <span>我确认只在本机使用此无许可证版本，不随 Meguri 分发。</span>
                </label>
                <button class="revision-activate" type="button" :disabled="Boolean(operation) || !canActivate(revision)" @click="skills.activate(sessionId, selectedInstalled.manifest.id, revision.digest, Boolean(licenseConfirmations[revision.digest]))">
                  {{ activateLabel(revision, index) }}
                </button>
              </div>
            </article>
          </section>
        </template>
        <div v-else class="skills-empty"><span aria-hidden="true">□</span><strong>选择一个本地 Skill</strong><p>这里会显示启停、更新、校验和历史回滚。</p></div>
      </aside>
    </div>
  </section>
</template>
