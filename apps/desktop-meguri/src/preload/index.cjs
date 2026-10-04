const { contextBridge, ipcRenderer, webUtils } = require('electron')

// Sandboxed Electron preloads can only require a limited set of built-ins.
// Keep this channel table in the single preload bundle instead of importing a
// sibling file, otherwise the entire desktop bridge fails to initialize.
const channels = Object.freeze({
  readinessGet: 'meguri:readiness:get',
  readinessRetry: 'meguri:readiness:retry',
  readinessChanged: 'meguri:readiness:changed',
  rendererReady: 'meguri:renderer:ready',
  preferencesGet: 'meguri:preferences:get',
  preferencesUpdate: 'meguri:preferences:update',
  preferencesMigrateLegacy: 'meguri:preferences:migrate-legacy',
  preferencesChanged: 'meguri:preferences:changed',
  startupSettingsStatusGet: 'meguri:startup-settings:status:get',
  startupSettingsSave: 'meguri:startup-settings:save',
  multimodalRelayGet: 'meguri:multimodal-relay:get',
  multimodalRelaySave: 'meguri:multimodal-relay:save',
  multimodalRelayTest: 'meguri:multimodal-relay:test',
  windowHide: 'meguri:window:hide',
  windowMinimize: 'meguri:window:minimize',
  windowToggle: 'meguri:window:toggle',
  windowSetClickThrough: 'meguri:window:set-click-through',
  windowPointerPosition: 'meguri:window:pointer-position',
  windowOpenSettings: 'meguri:window:open-settings',
  windowOpenStudio: 'meguri:window:open-studio',
  windowCenterStage: 'meguri:window:center-stage',
  windowReloadStage: 'meguri:window:reload-stage',
  windowSetAlwaysOnTop: 'meguri:window:set-always-on-top',
  windowQuit: 'meguri:window:quit',
  artifactOpen: 'meguri:artifact:open',
  artifactIcon: 'meguri:artifact:icon',
  attachmentInspect: 'meguri:attachment:inspect',
  attachmentClipboardImage: 'meguri:attachment:clipboard-image',
  attachmentClipboardImportRequested: 'meguri:attachment:clipboard-import-requested',
  pixpinCaptureStart: 'meguri:pixpin-capture:start',
  pixpinCaptureCancel: 'meguri:pixpin-capture:cancel',
  pixpinCaptureStatus: 'meguri:pixpin-capture:status',
  pixpinCaptureChanged: 'meguri:pixpin-capture:changed',
  characterPacksList: 'meguri:character-packs:list',
  characterPacksImportDirectory: 'meguri:character-packs:import-directory',
  characterPacksAddResource: 'meguri:character-packs:add-resource',
  characterPacksPublish: 'meguri:character-packs:publish',
  characterPacksActivate: 'meguri:character-packs:activate',
  characterPacksDisable: 'meguri:character-packs:disable',
  characterPacksRestore: 'meguri:character-packs:restore',
  characterPacksPreview: 'meguri:character-packs:preview',
  characterPresentationGet: 'meguri:character-presentation:get',
  localAgentsGet: 'meguri:local-agents:get',
  localAgentsSave: 'meguri:local-agents:save',
  localAgentsTest: 'meguri:local-agents:test',
  workSnapshot: 'meguri:work:snapshot',
  workTargets: 'meguri:work:targets',
  workProjects: 'meguri:work:projects',
  workPrepare: 'meguri:work:prepare',
  workConfirm: 'meguri:work:confirm',
  workRefresh: 'meguri:work:refresh',
  workDismiss: 'meguri:work:dismiss',
  workOpenNative: 'meguri:work:open-native',
  workChanged: 'meguri:work:changed',
  shizukiStatus: 'meguri:shizuki:status',
  shizukiSetMainAccount: 'meguri:shizuki:main-account:set',
  shizukiClearMainAccount: 'meguri:shizuki:main-account:clear',
  shizukiConnect: 'meguri:shizuki:connect',
  shizukiDisconnect: 'meguri:shizuki:disconnect',
  shizukiListTodos: 'meguri:shizuki:todos:list',
  shizukiCreateTodo: 'meguri:shizuki:todos:create',
  shizukiPendingCreates: 'meguri:shizuki:todos:pending-creates',
  shizukiUpdateTodo: 'meguri:shizuki:todos:update',
  shizukiCompleteTodo: 'meguri:shizuki:todos:complete',
  shizukiReminder: 'meguri:shizuki:reminder',
  voiceStart: 'meguri:voice:start',
  voiceCancel: 'meguri:voice:cancel',
  voiceChanged: 'meguri:voice:changed',
})

const viewArgument = process.argv.find(argument => argument.startsWith('--meguri-view='))
const requestedView = viewArgument?.slice('--meguri-view='.length)
const view = requestedView === 'startup' || requestedView === 'settings' || requestedView === 'studio' ? requestedView : 'stage'

const studioCharacterPacks = {
  list: () => ipcRenderer.invoke(channels.characterPacksList),
  importDirectory: () => ipcRenderer.invoke(channels.characterPacksImportDirectory),
  addResource: draftId => ipcRenderer.invoke(channels.characterPacksAddResource, draftId),
  publish: draftId => ipcRenderer.invoke(channels.characterPacksPublish, draftId),
  activate: packageId => ipcRenderer.invoke(channels.characterPacksActivate, packageId),
  disable: packageId => ipcRenderer.invoke(channels.characterPacksDisable, packageId),
  restore: packageId => ipcRenderer.invoke(channels.characterPacksRestore, packageId),
  preview: (packageId, resourceId) => ipcRenderer.invoke(channels.characterPacksPreview, packageId, resourceId),
}

const characterPresentation = view === 'stage'
  ? { getActive: () => ipcRenderer.invoke(channels.characterPresentationGet) }
  : undefined

const studioBridge = Object.freeze({
  view,
  window: {
    hide: () => ipcRenderer.invoke(channels.windowHide),
    minimize: () => ipcRenderer.invoke(channels.windowMinimize),
  },
  characterPacks: studioCharacterPacks,
})

const desktopBridge = {
  view,
  readiness: {
    get: () => ipcRenderer.invoke(channels.readinessGet),
    retry: () => ipcRenderer.invoke(channels.readinessRetry),
    onChanged(listener) {
      const handler = (_event, snapshot) => listener(snapshot)
      ipcRenderer.on(channels.readinessChanged, handler)
      return () => ipcRenderer.removeListener(channels.readinessChanged, handler)
    },
    rendererReady: () => ipcRenderer.invoke(channels.rendererReady),
  },
  preferences: {
    get: () => ipcRenderer.invoke(channels.preferencesGet),
    update: patch => ipcRenderer.invoke(channels.preferencesUpdate, patch),
    migrateLegacy: patch => ipcRenderer.invoke(channels.preferencesMigrateLegacy, patch),
    onChanged(listener) {
      const handler = (_event, preferences) => listener(preferences)
      ipcRenderer.on(channels.preferencesChanged, handler)
      return () => ipcRenderer.removeListener(channels.preferencesChanged, handler)
    },
  },
  startupSettings: {
    getStatus: () => ipcRenderer.invoke(channels.startupSettingsStatusGet),
    save: patch => ipcRenderer.invoke(channels.startupSettingsSave, patch),
  },
  multimodalRelay: {
    get: () => ipcRenderer.invoke(channels.multimodalRelayGet),
    save: draft => ipcRenderer.invoke(channels.multimodalRelaySave, draft),
    test: draft => ipcRenderer.invoke(channels.multimodalRelayTest, draft),
  },
  localAgents: {
    get: () => ipcRenderer.invoke(channels.localAgentsGet),
    save: patch => ipcRenderer.invoke(channels.localAgentsSave, patch),
    test: () => ipcRenderer.invoke(channels.localAgentsTest),
  },
  // Task forwarding is confirm-gated: the renderer can only freeze a card and
  // confirm the exact version it was shown. It never supplies a raw send.
  work: {
    snapshot: () => ipcRenderer.invoke(channels.workSnapshot),
    targets: query => ipcRenderer.invoke(channels.workTargets, query),
    projects: () => ipcRenderer.invoke(channels.workProjects),
    prepare: input => ipcRenderer.invoke(channels.workPrepare, input),
    confirm: (id, version) => ipcRenderer.invoke(channels.workConfirm, id, version),
    refresh: id => ipcRenderer.invoke(channels.workRefresh, id),
    dismiss: (id, version) => ipcRenderer.invoke(channels.workDismiss, id, version),
    openNative: id => ipcRenderer.invoke(channels.workOpenNative, id),
    onChanged(listener) {
      const handler = (_event, snapshot) => listener(snapshot)
      ipcRenderer.on(channels.workChanged, handler)
      return () => ipcRenderer.removeListener(channels.workChanged, handler)
    },
  },
  ...(view === 'stage' || view === 'settings' ? {
    shizuki: {
      status: () => ipcRenderer.invoke(channels.shizukiStatus),
      setMainAccount: () => ipcRenderer.invoke(channels.shizukiSetMainAccount),
      clearMainAccount: () => ipcRenderer.invoke(channels.shizukiClearMainAccount),
      ...(view === 'settings' ? {
        connect: input => ipcRenderer.invoke(channels.shizukiConnect, input),
        disconnect: () => ipcRenderer.invoke(channels.shizukiDisconnect),
      } : {
        listTodos: () => ipcRenderer.invoke(channels.shizukiListTodos),
        createTodo: input => ipcRenderer.invoke(channels.shizukiCreateTodo, input),
        pendingCreates: () => ipcRenderer.invoke(channels.shizukiPendingCreates),
        updateTodo: (id, input) => ipcRenderer.invoke(channels.shizukiUpdateTodo, id, input),
        completeTodo: (id, expectedAccount) => ipcRenderer.invoke(channels.shizukiCompleteTodo, id, expectedAccount),
        onReminder(listener) {
          const handler = (_event, reminder) => listener(reminder)
          ipcRenderer.on(channels.shizukiReminder, handler)
          return () => ipcRenderer.removeListener(channels.shizukiReminder, handler)
        },
      }),
    },
  } : {}),
  ...(view === 'stage' ? {
    voice: {
      start: () => ipcRenderer.invoke(channels.voiceStart),
      cancel: () => ipcRenderer.invoke(channels.voiceCancel),
      onChanged(listener) {
        const handler = (_event, state) => listener(state)
        ipcRenderer.on(channels.voiceChanged, handler)
        return () => ipcRenderer.removeListener(channels.voiceChanged, handler)
      },
    },
  } : {}),
  window: {
    hide: () => ipcRenderer.invoke(channels.windowHide),
    minimize: () => ipcRenderer.invoke(channels.windowMinimize),
    toggleVisibility: () => ipcRenderer.invoke(channels.windowToggle),
    setClickThrough: ignore => ipcRenderer.invoke(channels.windowSetClickThrough, ignore === true),
    pointerPosition: () => ipcRenderer.invoke(channels.windowPointerPosition),
    openSettings: () => ipcRenderer.invoke(channels.windowOpenSettings),
    openStudio: () => ipcRenderer.invoke(channels.windowOpenStudio),
    centerStage: () => ipcRenderer.invoke(channels.windowCenterStage),
    reloadStage: () => ipcRenderer.invoke(channels.windowReloadStage),
    setAlwaysOnTop: value => ipcRenderer.invoke(channels.windowSetAlwaysOnTop, value === true),
    quit: () => ipcRenderer.invoke(channels.windowQuit),
  },
  artifacts: {
    open: path => ipcRenderer.invoke(channels.artifactOpen, path),
    icon: path => ipcRenderer.invoke(channels.artifactIcon, path),
  },
  attachments: {
    pathForFile: file => {
      try {
        return webUtils.getPathForFile(file)
      }
      catch {
        return ''
      }
    },
    inspect: path => ipcRenderer.invoke(channels.attachmentInspect, path),
    clipboardImage: () => ipcRenderer.invoke(channels.attachmentClipboardImage),
    onClipboardImportRequested(listener) {
      const handler = () => listener()
      ipcRenderer.on(channels.attachmentClipboardImportRequested, handler)
      return () => ipcRenderer.removeListener(channels.attachmentClipboardImportRequested, handler)
    },
  },
  screenshots: {
    start: mode => ipcRenderer.invoke(channels.pixpinCaptureStart, mode),
    cancel: captureId => ipcRenderer.invoke(channels.pixpinCaptureCancel, captureId),
    status: () => ipcRenderer.invoke(channels.pixpinCaptureStatus),
    onChanged(listener) {
      const handler = (_event, snapshot) => listener(snapshot)
      ipcRenderer.on(channels.pixpinCaptureChanged, handler)
      return () => ipcRenderer.removeListener(channels.pixpinCaptureChanged, handler)
    },
  },
  ...(characterPresentation ? { characterPresentation } : {}),
}

// Studio does not need chat, attachments, preferences, or arbitrary window
// commands. Keep its renderer boundary limited to its own window controls and
// the named character-pack operations above.
contextBridge.exposeInMainWorld('meguriDesktop', view === 'studio' ? studioBridge : desktopBridge)
