import type {
  CharacterPackCatalog,
  CharacterPackSummary,
  DesktopPreferences,
  DesktopPreferencesPatch,
  DesktopStartupPreferencesPatch,
  DesktopStartupStatus,
  LocalAgentSettings,
  MeguriDesktopBridge,
  ReadinessSnapshot,
  WorkSnapshot,
} from '../shared/desktop-contract'

const previewPng = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9J7KsAAAAASUVORK5CYII='

const previewLocalAgents: LocalAgentSettings = {
  version: 1,
  enabled: false,
  harnessAutoStart: false,
  dshHome: '',
  install: {
    codex: { installed: false, home: '', signedIn: false },
    harness: { cli: 'missing', credentials: 'missing', preset: 'unavailable', logReady: false },
  },
}

const previewWorkSnapshot: WorkSnapshot = {
  requests: [],
  draft: null,
  connection: { codex: 'disabled', harness: 'disabled' },
  executionAvailable: false,
}

function previewCatalog(): CharacterPackCatalog {
  return {
    version: 1,
    activePackageId: 'pack-meguri-gal-v3',
    drafts: [{
      id: 'draft-spring',
      displayName: '春日试作包',
      version: 'draft-1',
      status: 'draft',
      active: false,
      importedAt: '2026-09-18T00:00:00.000Z',
      resources: [{ id: 'sprite:extras/spring.png', name: 'spring.png', kind: 'extra', mediaType: 'image/png', status: 'ready' }],
      validationMessages: [],
    }],
    packages: [{
      id: 'pack-meguri-gal-v3',
      displayName: 'Meguri Gal Assets',
      version: 'meguri_v2_02c3db0c507d7c2d',
      status: 'published',
      active: true,
      importedAt: '2026-09-18T00:00:00.000Z',
      publishedAt: '2026-09-18T00:05:00.000Z',
      resources: [
        { id: 'sprite:sprites/ce04001m.png', name: 'ce04001m.png', kind: 'sprite', mediaType: 'image/png', status: 'ready' },
        { id: 'background:backgrounds/bg11a.jpg', name: 'bg11a.jpg', kind: 'background', mediaType: 'image/jpeg', status: 'ready' },
        { id: 'ui:ui/FRM_0101.png', name: 'FRM_0101.png', kind: 'ui', mediaType: 'image/png', status: 'ready' },
      ],
      validationMessages: [],
    }],
  }
}

function updatePreviewPack(catalog: CharacterPackCatalog, pack: CharacterPackSummary): CharacterPackCatalog {
  const drafts = catalog.drafts.map(item => item.id === pack.id ? pack : item)
  const packages = catalog.packages.map(item => item.id === pack.id ? pack : item)
  return { ...catalog, drafts, packages }
}

function activePresentation(catalog: CharacterPackCatalog) {
  const pack = catalog.packages.find(item => item.id === catalog.activePackageId && item.status === 'published')
  if (!pack)
    return { revision: 'builtin:default' }
  const asset = (kind: 'sprite' | 'background') => {
    const resource = pack.resources.find(item => item.kind === kind && item.status === 'ready')
    return resource
      ? { resourceId: resource.id, name: resource.name, mediaType: resource.mediaType, dataUrl: previewPng }
      : undefined
  }
  return {
    revision: `${pack.id}:${pack.publishedAt ?? pack.importedAt}`,
    packageId: pack.id,
    sprite: asset('sprite'),
    background: asset('background'),
  }
}

const previewSnapshot: ReadinessSnapshot = {
  runId: 1,
  status: 'checking',
  items: [
    { capability: 'gateway', label: '桌面网关', status: 'ready', detail: '已就绪' },
    { capability: 'core', label: 'Meguri Core', status: 'ready', detail: '已就绪' },
    { capability: 'tts', label: '本地语音', status: 'checking', detail: '正在加载声音模型' },
    { capability: 'assets', label: '角色资源', status: 'ready', detail: '已就绪' },
    { capability: 'renderer', label: '界面渲染', status: 'pending', detail: '等待舞台完成挂载' },
  ],
}

const previewPreferences: DesktopPreferences = {
  version: 1,
  appearance: 'light',
  chat: { visible: true, expanded: false, wallpaperDataUrl: '', wallpaperBlur: 0, bubbleOpacity: 1, colorPalette: 'mint' },
  character: { outfitCode: '04', reducedMotion: false, renderer: 'png', live2dModelId: '' },
  window: { alwaysOnTop: true, fadeOnHover: true },
  speech: { ttsEnabled: true, volume: 0.9, speed: 1, ambientEnabled: true, galgameEnabled: true },
  memory: { enabled: false, autoSummarize: true, summaryEveryTurns: 10 },
  companion: { initiative: 'normal', focus: 'available', expressionsEnabled: true, motionEnabled: true, commitments: [] },
  training: { enabled: false },
  privacy: { acceptedPolicyVersion: '', remoteChatAllowed: false, saveHistory: true, checkUpdatesOnStartup: true },
  shortcuts: { toggleVisibility: 'CommandOrControl+Shift+M' },
  startup: { openAtLogin: true, runAsAdministrator: true },
}

function mergePreviewPreferences(
  current: DesktopPreferences,
  patch: DesktopPreferencesPatch,
): DesktopPreferences {
  const privacy = { ...current.privacy, ...patch.privacy }
  const companion = { ...current.companion, ...patch.companion }
  return {
    ...current,
    ...patch,
    version: 1,
    chat: { ...current.chat, ...patch.chat },
    character: { ...current.character, ...patch.character },
    window: { ...current.window, ...patch.window },
    speech: { ...current.speech, ...patch.speech },
    memory: { ...current.memory, ...patch.memory },
    companion: { ...companion, commitments: privacy.saveHistory ? companion.commitments : [] },
    training: { ...current.training, ...patch.training },
    privacy,
    shortcuts: { ...current.shortcuts, ...patch.shortcuts },
    startup: { ...current.startup, ...patch.startup },
  }
}

/**
 * Installs a deterministic development-only host bridge for visual review in a
 * regular browser. Production builds remove this branch and only trust preload.
 */
export function installPreviewBridge() {
  const preview = new URLSearchParams(window.location.search).get('preview')
  if (!preview || window.meguriDesktop)
    return

  let snapshot = structuredClone(previewSnapshot)
  let preferences = structuredClone(previewPreferences)
  let startupStatus: DesktopStartupStatus = { ok: true, message: '预览模式不会修改 Windows 启动配置。' }
  let multimodalRelay = {
    version: 1 as const,
    enabled: false,
    baseUrl: '',
    model: '',
    apiKeyConfigured: false,
  }
  let characterPacks = previewCatalog()
  let localAgentPreview = structuredClone(previewLocalAgents)
  if (preview === 'failed') {
    snapshot.status = 'failed'
    snapshot.items[1] = {
      ...snapshot.items[1],
      status: 'failed',
      detail: 'HTTP 503',
    }
  }

  const requestedView = new URLSearchParams(window.location.search).get('view')
  const view = requestedView === 'startup' || requestedView === 'settings' || requestedView === 'studio'
    ? requestedView
    : 'stage'
  const bridge: MeguriDesktopBridge = {
    view,
    readiness: {
      get: async () => structuredClone(snapshot),
      retry: async () => {
        snapshot = {
          ...snapshot,
          status: 'ready',
          items: snapshot.items.map(item => ({ ...item, status: 'ready', detail: '已就绪' })),
        }
        return structuredClone(snapshot)
      },
      onChanged: () => () => {},
      rendererReady: async () => {},
    },
    preferences: {
      get: async () => structuredClone(preferences),
      update: async (patch) => {
        preferences = mergePreviewPreferences(preferences, patch)
        return structuredClone(preferences)
      },
      migrateLegacy: async (patch) => {
        preferences = mergePreviewPreferences(preferences, patch)
        return structuredClone(preferences)
      },
      onChanged: () => () => {},
    },
    wallpaper: { select: async () => ({ ok: true, dataUrl: previewPng }) },
    diagnostics: { export: async () => ({ ok: false, error: '浏览器预览不导出本机诊断。' }) },
    live2d: {
      catalog: async () => ({ version: 1, models: [] }),
      active: async () => null,
      importDirectory: async () => ({ ok: false, error: '请在桌面设置中导入 Live2D 模型。' }),
      importArchive: async () => ({ ok: false, error: '请在桌面设置中导入 Live2D 模型。' }),
      activate: async () => ({ ok: false, error: '预览没有 Live2D 模型。' }),
      remove: async () => ({ ok: false, error: '预览没有 Live2D 模型。' }),
    },
    speechResources: {
      list: async () => ({ engine: 'GPT-SoVITS', ready: false, activeId: 'default', profiles: [], error: '浏览器预览不连接本地语音模型管理。' }),
      importPackage: async () => ({ ok: false, error: '请在桌面设置中导入语音资源。' }),
      download: async () => ({ ok: false, error: '预览不下载语音资源。' }),
      activate: async () => ({ ok: false, error: '预览不启用语音资源。' }),
      remove: async () => ({ ok: false, error: '预览不删除语音资源。' }),
    },
    updates: {
      get: async () => ({ status: 'idle', currentVersion: '0.1.0', message: '尚未检查更新。' }),
      check: async () => ({ status: 'no_release', currentVersion: '0.1.0', message: '浏览器预览不会查询 GitHub。' }),
      openRelease: async () => ({ ok: false, error: '浏览器预览不会打开外部发布页。' }),
      onChanged: () => () => {},
    },
    startupSettings: {
      getStatus: async () => structuredClone(startupStatus),
      save: async (patch: DesktopStartupPreferencesPatch) => {
        preferences = mergePreviewPreferences(preferences, { startup: patch })
        startupStatus = { ok: true, message: '仅更新浏览器预览状态；没有修改 Windows 任务计划程序。' }
        return { ok: true, preferences: structuredClone(preferences), status: structuredClone(startupStatus) }
      },
    },
    multimodalRelay: {
      get: async () => structuredClone(multimodalRelay),
      save: async (draft) => {
        multimodalRelay = {
          ...multimodalRelay,
          enabled: draft.enabled === true,
          baseUrl: draft.baseUrl?.trim() ?? '',
          model: draft.model?.trim() ?? '',
          apiKeyConfigured: multimodalRelay.apiKeyConfigured || Boolean(draft.apiKey?.trim()),
        }
        return { ok: true, config: structuredClone(multimodalRelay) }
      },
      test: async () => ({ ok: false, error: '浏览器预览不会连接外部中转站。' }),
    },
    // The preview never contacts a work agent: it only shows the disabled state.
    localAgents: {
      get: async () => structuredClone(localAgentPreview),
      save: async (patch) => {
        localAgentPreview = {
          ...localAgentPreview,
          enabled: patch.enabled === true,
          harnessAutoStart: patch.enabled === true && patch.harnessAutoStart === true,
          dshHome: patch.dshHome?.trim() ?? '',
        }
        return { ok: true, settings: structuredClone(localAgentPreview) }
      },
      test: async () => ({
        codex: { ok: false, message: '浏览器预览不会启动本机 Codex。' },
        harness: { ok: false, message: '浏览器预览不会连接本机 Harness。' },
      }),
    },
    work: {
      snapshot: async () => structuredClone(previewWorkSnapshot),
      targets: async () => ({ limit: 1000, items: [] }),
      projects: async () => [],
      prepare: async () => ({ ok: false, error: '浏览器预览不会转交任务。' }),
      confirm: async () => ({ ok: false, error: '浏览器预览不会转交任务。' }),
      refresh: async () => ({ ok: false, error: '浏览器预览不会读取本机回执。' }),
      dismiss: async () => ({ ok: false, error: '浏览器预览不会修改任务卡。' }),
      openNative: async () => ({ ok: false, error: '浏览器预览不会打开本地执行器。' }),
      onChanged: () => () => {},
    },
    window: {
      hide: async () => {},
      minimize: async () => {},
      toggleVisibility: async () => true,
      setClickThrough: async (ignore) => {
        document.documentElement.dataset.clickThrough = String(ignore)
      },
      pointerPosition: async () => undefined,
      setPresentationSurface: async () => ({ ok: true }),
      openSettings: async () => {},
      openStudio: async () => {},
      centerStage: async () => {},
      reloadStage: async () => {},
      setAlwaysOnTop: async (value) => {
        preferences = mergePreviewPreferences(preferences, { window: { alwaysOnTop: value } })
        return structuredClone(preferences)
      },
      quit: async () => {},
    },
    artifacts: {
      open: async () => ({ ok: true }),
      icon: async () => ({ ok: false }),
    },
    attachments: {
      pathForFile: () => '',
      inspect: async () => ({ ok: false, error: '浏览器预览不读取本地文件。' }),
      clipboardImage: async () => ({ ok: false, error: '浏览器预览不读取系统剪贴板。' }),
      onClipboardImportRequested: () => () => {},
    },
    screenshots: {
      start: async () => ({ ok: false, error: 'PixPin 截图仅在 Meguri 桌面版可用。' }),
      cancel: async () => ({ ok: false, error: 'PixPin 截图仅在 Meguri 桌面版可用。' }),
      status: async () => ({ state: 'idle' as const }),
      onChanged: () => () => {},
    },
    ...(view === 'stage' ? { characterPresentation: {
      getActive: async () => structuredClone(activePresentation(characterPacks)),
    } } : {}),
    ...(view === 'studio' ? { characterPacks: {
      list: async () => structuredClone(characterPacks),
      importDirectory: async () => ({ ok: false, error: '浏览器预览不会读取本地角色包。' }),
      addResource: async (draftId) => {
        const draft = characterPacks.drafts.find(item => item.id === draftId)
        if (!draft)
          return { ok: false, error: '未找到角色包草稿。' }
        const next = structuredClone(draft)
        next.resources.push({
          id: `extra:preview-${next.resources.length + 1}`,
          name: 'preview-extra.png',
          kind: 'extra',
          mediaType: 'image/png',
          status: 'ready',
        })
        characterPacks = updatePreviewPack(characterPacks, next)
        return { ok: true, catalog: structuredClone(characterPacks), pack: next }
      },
      publish: async (draftId) => {
        const draft = characterPacks.drafts.find(item => item.id === draftId)
        if (!draft)
          return { ok: false, error: '未找到角色包草稿。' }
        const pack = { ...draft, id: `pack-${draft.id.slice('draft-'.length)}`, status: 'published' as const, active: true, publishedAt: '2026-09-18T00:10:00.000Z' }
        characterPacks = {
          ...characterPacks,
          activePackageId: pack.id,
          drafts: characterPacks.drafts.filter(item => item.id !== draftId),
          packages: [...characterPacks.packages.map(item => ({ ...item, active: false })), pack],
        }
        return { ok: true, catalog: structuredClone(characterPacks), pack }
      },
      activate: async (packageId) => {
        const existing = characterPacks.packages.find(item => item.id === packageId)
        if (!existing || existing.status !== 'published')
          return { ok: false, error: '角色包暂时不能启用。' }
        const pack = { ...existing, active: true }
        characterPacks = {
          ...characterPacks,
          activePackageId: packageId,
          packages: characterPacks.packages.map(item => item.id === packageId ? pack : { ...item, active: false }),
        }
        return { ok: true, catalog: structuredClone(characterPacks), pack }
      },
      disable: async (packageId) => {
        const existing = characterPacks.packages.find(item => item.id === packageId)
        if (!existing)
          return { ok: false, error: '未找到角色包。' }
        const pack = { ...existing, status: 'disabled' as const, active: false }
        characterPacks = {
          ...characterPacks,
          activePackageId: characterPacks.activePackageId === packageId ? undefined : characterPacks.activePackageId,
          packages: characterPacks.packages.map(item => item.id === packageId ? pack : item),
        }
        return { ok: true, catalog: structuredClone(characterPacks), pack }
      },
      restore: async (packageId) => {
        const existing = characterPacks.packages.find(item => item.id === packageId)
        if (!existing)
          return { ok: false, error: '未找到角色包。' }
        const pack = { ...existing, status: 'published' as const, active: true }
        characterPacks = {
          ...characterPacks,
          activePackageId: packageId,
          packages: characterPacks.packages.map(item => item.id === packageId ? pack : { ...item, active: false }),
        }
        return { ok: true, catalog: structuredClone(characterPacks), pack }
      },
      preview: async () => ({ ok: true, dataUrl: previewPng, mediaType: 'image/png' }),
    } } : {}),
  }
  window.meguriDesktop = bridge
}
