<script setup lang="ts">
import type { CharacterPackResource, CharacterPackResourceKind, CharacterPackSummary } from '../../shared/desktop-contract'

import { storeToRefs } from 'pinia'
import { computed, onBeforeUnmount, onMounted } from 'vue'

import { useCharacterPacksStore } from '../stores/character-packs'

const characterPacks = useCharacterPacksStore()
const hostWindow = window.meguriDesktop?.window
const {
  catalog,
  selectedPack,
  selectedPackId,
  selectedResource,
  selectedResourceId,
  previewDataUrl,
  previewError,
  loading,
  operation,
  error,
  notice,
} = storeToRefs(characterPacks)

const resourceKinds: Array<{ kind: CharacterPackResourceKind, label: string }> = [
  { kind: 'sprite', label: '立绘' },
  { kind: 'background', label: '背景' },
  { kind: 'ui', label: '界面' },
  { kind: 'extra', label: '草稿附加资源' },
]

const groupedResources = computed(() => resourceKinds.map(group => ({
  ...group,
  resources: selectedPack.value?.resources.filter(resource => resource.kind === group.kind) ?? [],
})).filter(group => group.resources.length))

const canAddResource = computed(() => selectedPack.value?.status === 'draft')
const canPublish = computed(() => selectedPack.value?.status === 'draft')
const canActivate = computed(() => selectedPack.value?.status === 'published' && !selectedPack.value.active)
const canDisable = computed(() => selectedPack.value?.status === 'published')
const canRestore = computed(() => selectedPack.value?.status === 'disabled')

function statusLabel(pack: CharacterPackSummary): string {
  if (pack.active) return '当前启用'
  return pack.status === 'draft' ? '草稿' : pack.status === 'disabled' ? '已停用' : '已发布'
}

function resourceStatus(resource: CharacterPackResource): string {
  return resource.status === 'ready' ? '已验证' : resource.validationMessage || '不可用'
}

function dateLabel(value?: string): string {
  if (!value) return '未发布'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' }).format(date)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    void window.meguriDesktop?.window.hide()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  void characterPacks.load()
})

onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <main class="studio-shell">
    <header class="studio-titlebar drag-region">
      <div class="studio-brand"><span>巡</span><div><strong>Character Studio</strong><small>本地角色表现资源</small></div></div>
      <div class="studio-window-actions no-drag">
        <button type="button" title="最小化" aria-label="最小化角色工作室" @click="hostWindow?.minimize()">−</button>
        <button type="button" title="关闭" aria-label="关闭角色工作室" @click="hostWindow?.hide()">×</button>
      </div>
    </header>

    <section class="studio-toolbar no-drag" aria-label="角色包操作">
      <div><strong>角色包目录</strong><small>{{ catalog.packages.length }} 个已发布 · {{ catalog.drafts.length }} 个草稿</small></div>
      <button type="button" class="studio-command primary" :disabled="Boolean(operation)" @click="characterPacks.importDirectory()">
        {{ operation === 'import' ? '正在导入…' : '导入目录' }}
      </button>
    </section>

    <p v-if="error || notice || loading" class="studio-feedback no-drag" :class="{ error, notice }" aria-live="polite">
      {{ loading ? '正在读取角色包目录…' : error || notice }}
    </p>

    <section class="studio-workspace no-drag">
      <aside class="studio-library" aria-label="角色包库">
        <header><strong>草稿</strong><span>{{ catalog.drafts.length }}</span></header>
        <div v-if="!catalog.drafts.length" class="studio-empty">尚无草稿</div>
        <button
          v-for="pack in catalog.drafts"
          :key="pack.id"
          type="button"
          class="studio-pack-row"
          :class="{ selected: selectedPackId === pack.id }"
          :aria-pressed="selectedPackId === pack.id"
          @click="characterPacks.selectPack(pack.id)"
        >
          <strong>{{ pack.displayName }}</strong><small>{{ pack.version }} · {{ pack.resources.length }} 项</small><span>{{ statusLabel(pack) }}</span>
        </button>

        <header><strong>已发布</strong><span>{{ catalog.packages.length }}</span></header>
        <div v-if="!catalog.packages.length" class="studio-empty">导入并发布后会显示在这里</div>
        <button
          v-for="pack in catalog.packages"
          :key="pack.id"
          type="button"
          class="studio-pack-row"
          :class="{ selected: selectedPackId === pack.id, active: pack.active, disabled: pack.status === 'disabled' }"
          :aria-pressed="selectedPackId === pack.id"
          @click="characterPacks.selectPack(pack.id)"
        >
          <strong>{{ pack.displayName }}</strong><small>{{ pack.version }} · {{ dateLabel(pack.publishedAt) }}</small><span>{{ statusLabel(pack) }}</span>
        </button>
      </aside>

      <section class="studio-resources" aria-label="角色包资源">
        <header><div><small>资源</small><h1>{{ selectedPack?.displayName || '选择一个角色包' }}</h1></div><span v-if="selectedPack" :class="['studio-status', selectedPack.status]">{{ statusLabel(selectedPack) }}</span></header>
        <p v-if="selectedPack?.validationMessages.length" class="studio-validation">{{ selectedPack.validationMessages.join(' ') }}</p>
        <div v-if="!selectedPack" class="studio-empty large">从左侧选择角色包，或先导入一个带 `manifest.json` 的目录。</div>
        <template v-else>
          <section v-for="group in groupedResources" :key="group.kind" class="studio-resource-group">
            <header><strong>{{ group.label }}</strong><span>{{ group.resources.length }}</span></header>
            <div class="studio-resource-list">
              <button
                v-for="resource in group.resources"
                :key="resource.id"
                type="button"
                :class="{ selected: selectedResourceId === resource.id, unavailable: resource.status !== 'ready' }"
                :aria-pressed="selectedResourceId === resource.id"
                @click="characterPacks.selectResource(resource.id)"
              >
                <strong>{{ resource.name }}</strong><small>{{ resourceStatus(resource) }}</small>
              </button>
            </div>
          </section>
        </template>
      </section>

      <aside class="studio-inspector" aria-label="资源预览和发布操作">
        <section class="studio-preview">
          <header><small>预览</small><strong>{{ selectedResource?.name || '未选择资源' }}</strong></header>
          <div v-if="previewDataUrl" class="studio-preview-image"><img :src="previewDataUrl" :alt="selectedResource?.name || '角色资源预览'" @error="characterPacks.markPreviewUnavailable()"></div>
          <div v-else class="studio-preview-fallback"><strong>{{ previewError || '选择已经验证的 PNG 或 JPG 资源' }}</strong><small>资源预览失败不会影响对话文本。</small></div>
          <dl v-if="selectedResource" class="studio-resource-facts"><div><dt>类型</dt><dd>{{ selectedResource.kind }}</dd></div><div><dt>格式</dt><dd>{{ selectedResource.mediaType }}</dd></div><div><dt>状态</dt><dd>{{ resourceStatus(selectedResource) }}</dd></div></dl>
        </section>

        <section v-if="selectedPack" class="studio-actions">
          <header><strong>版本操作</strong><small>进行中的表现会保留当前解析快照。</small></header>
          <button v-if="canAddResource" type="button" :disabled="Boolean(operation)" @click="characterPacks.addResource(selectedPack.id)">{{ operation === 'add-resource' ? '正在添加…' : '添加 PNG / JPG' }}</button>
          <button v-if="canPublish" type="button" class="primary" :disabled="Boolean(operation)" @click="characterPacks.publish(selectedPack.id)">{{ operation === 'publish' ? '正在发布…' : '发布草稿' }}</button>
          <button v-if="canActivate" type="button" class="primary" :disabled="Boolean(operation)" @click="characterPacks.activate(selectedPack.id)">{{ operation === 'activate' ? '正在启用…' : '启用版本' }}</button>
          <button v-if="canDisable" type="button" class="danger" :disabled="Boolean(operation)" @click="characterPacks.disable(selectedPack.id)">{{ operation === 'disable' ? '正在停用…' : '停用版本' }}</button>
          <button v-if="canRestore" type="button" class="primary" :disabled="Boolean(operation)" @click="characterPacks.restore(selectedPack.id)">{{ operation === 'restore' ? '正在恢复…' : '恢复并启用' }}</button>
        </section>
      </aside>
    </section>
  </main>
</template>
