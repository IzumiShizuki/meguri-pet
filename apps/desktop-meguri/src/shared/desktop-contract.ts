export type ReadinessStatus = 'pending' | 'checking' | 'ready' | 'failed'

import type { DesktopColorPalette, DesktopMemoryPreferences, DesktopPrivacyPreferences, DesktopWallpaperResult, DesktopDiagnosticResult } from './experience-contract'
import type { DesktopUpdateState } from './update-contract'
import type { Live2DBridge } from './live2d-contract'
import type { SpeechResourcesBridge } from './speech-resource-contract'
import type { CompanionPreferences, CompanionPreferencesPatch } from './companion-contract'

export type ReadinessCapability = 'gateway' | 'core' | 'tts' | 'assets' | 'renderer'

export interface ReadinessItem {
  capability: ReadinessCapability
  label: string
  status: ReadinessStatus
  detail: string
  checkedAt?: number
}

export interface ReadinessSnapshot {
  runId: number
  status: 'checking' | 'ready' | 'failed'
  items: ReadinessItem[]
}

export interface DesktopResult {
  ok: boolean
  error?: string
  code?: string
}

export interface ArtifactIconResult extends DesktopResult {
  dataUrl?: string
}

export interface DesktopLocalReadableAttachment {
  type: 'local_file_reference'
  source: 'everything'
  reference_id: string
  name: string
  path: string
  kind: 'image' | 'pdf' | 'document'
  size_bytes: number
  modified_at: string
  content_access: 'multimodal_read' | 'document_read'
}

export interface DesktopInlineImageAttachment {
  type: 'inline_attachment'
  source: 'airi_inline'
  reference_id: string
  name: string
  kind: 'image'
  size_bytes: number
  modified_at: string
  content_access: 'multimodal_read'
  mime_type: 'image/png'
  data_url: string
}

export type DesktopReadableAttachment = DesktopLocalReadableAttachment | DesktopInlineImageAttachment

export interface DesktopAttachmentResult extends DesktopResult {
  attachment?: DesktopReadableAttachment
}

export type PixPinCaptureMode = 'draft' | 'direct_send'

export type PixPinCaptureState = 'idle' | 'starting' | 'selecting' | 'received' | 'cancelled' | 'timed_out' | 'failed'

export interface PixPinCaptureSnapshot {
  state: PixPinCaptureState
  clipboardImportShortcutAvailable?: boolean
  captureId?: string
  mode?: PixPinCaptureMode
  startedAt?: number
  deadline?: number
}

export interface PixPinCaptureEvent extends PixPinCaptureSnapshot {
  error?: string
  hint?: string
  attachment?: DesktopReadableAttachment
}

export interface PixPinCaptureStartResult extends DesktopResult {
  captureId?: string
  state?: PixPinCaptureState
}

export type AppearanceMode = 'system' | 'light' | 'dark'

export interface DesktopPreferences {
  version: 1
  appearance: AppearanceMode
  systemAccentColor?: string
  chat: {
    visible: boolean
    expanded: boolean
    wallpaperDataUrl: string
    wallpaperBlur: number
    bubbleOpacity: number
    colorPalette: DesktopColorPalette
  }
  character: {
    outfitCode: string
    reducedMotion: boolean
    renderer: 'png' | 'live2d'
    live2dModelId: string
  }
  window: {
    alwaysOnTop: boolean
    fadeOnHover: boolean
  }
  speech: {
    ttsEnabled: boolean
    volume: number
    speed: number
    ambientEnabled: boolean
    galgameEnabled: boolean
  }
  memory: DesktopMemoryPreferences
  companion: CompanionPreferences
  training: { enabled: boolean }
  privacy: DesktopPrivacyPreferences
  shortcuts: {
    toggleVisibility: string
  }
  startup: {
    openAtLogin: boolean
    runAsAdministrator: boolean
  }
}

export interface DesktopPreferencesPatch {
  appearance?: AppearanceMode
  chat?: Partial<DesktopPreferences['chat']>
  character?: Partial<DesktopPreferences['character']>
  window?: Partial<DesktopPreferences['window']>
  speech?: Partial<DesktopPreferences['speech']>
  memory?: Partial<DesktopPreferences['memory']>
  companion?: CompanionPreferencesPatch
  training?: Partial<DesktopPreferences['training']>
  privacy?: Partial<DesktopPreferences['privacy']>
  shortcuts?: Partial<DesktopPreferences['shortcuts']>
  startup?: Partial<DesktopPreferences['startup']>
}

export interface DesktopStartupStatus {
  ok: boolean
  message: string
}

export interface DesktopStartupSaveResult {
  ok: boolean
  preferences?: DesktopPreferences
  status?: DesktopStartupStatus
  error?: string
}

export interface DesktopStartupPreferencesPatch {
  openAtLogin?: boolean
  runAsAdministrator?: boolean
}

export interface MultimodalRelayConfig {
  version: 1
  enabled: boolean
  baseUrl: string
  model: string
  apiKeyConfigured: boolean
  updatedAt?: string
}

export interface MultimodalRelayDraft {
  enabled?: boolean
  baseUrl?: string
  model?: string
  apiKey?: string
}

export interface MultimodalRelaySaveResult extends DesktopResult {
  config?: MultimodalRelayConfig
}

export interface MultimodalRelayTestResult extends DesktopResult {
  message?: string
}

export type CharacterPackResourceKind = 'sprite' | 'background' | 'ui' | 'extra'

export type CharacterPackResourceStatus = 'ready' | 'unavailable'

export interface CharacterPackResource {
  id: string
  name: string
  kind: CharacterPackResourceKind
  mediaType: 'image/png' | 'image/jpeg'
  status: CharacterPackResourceStatus
  validationMessage?: string
}

export type CharacterPackStatus = 'draft' | 'published' | 'disabled'

export interface CharacterPackSummary {
  id: string
  displayName: string
  version: string
  status: CharacterPackStatus
  active: boolean
  importedAt: string
  publishedAt?: string
  resources: CharacterPackResource[]
  validationMessages: string[]
}

export interface CharacterPackCatalog {
  version: 1
  activePackageId?: string
  drafts: CharacterPackSummary[]
  packages: CharacterPackSummary[]
}

export interface CharacterPackResult extends DesktopResult {
  catalog?: CharacterPackCatalog
  pack?: CharacterPackSummary
}

export interface CharacterPackPreviewResult extends DesktopResult {
  dataUrl?: string
  mediaType?: CharacterPackResource['mediaType']
}

export interface CharacterPresentationAsset {
  resourceId: string
  name: string
  mediaType: CharacterPackResource['mediaType']
  dataUrl: string
}

export interface CharacterPresentationSnapshot {
  revision: string
  packageId?: string
  sprite?: CharacterPresentationAsset
  background?: CharacterPresentationAsset
}

export type LocalAgentInstallState = 'installed' | 'missing' | 'present' | 'user_preset' | 'unavailable' | 'configured'

export interface LocalAgentSettings {
  version: 1
  enabled: boolean
  harnessAutoStart: boolean
  dshHome: string
  install: {
    codex: { installed: boolean, home: string, signedIn: boolean }
    harness: { cli: 'installed' | 'missing', credentials: LocalAgentInstallState, preset: LocalAgentInstallState, logReady: boolean }
  }
}

export interface LocalAgentSettingsPatch {
  enabled?: boolean
  harnessAutoStart?: boolean
  dshHome?: string
}

export interface LocalAgentSaveResult extends DesktopResult {
  settings?: LocalAgentSettings
}

export interface LocalAgentProbeResult {
  codex: { ok: boolean, message: string }
  harness: { ok: boolean, message: string }
}

export type WorkExecutor = 'codex' | 'harness'

export type ForwardPhase =
  | 'awaiting_confirmation'
  | 'forwarding'
  | 'accepted'
  | 'completed'
  | 'unknown'
  | 'unavailable'

export type NativeStatus = 'working' | 'approval' | 'completed' | 'failed' | 'unknown'

export interface WorkTarget {
  targetId: string
  title: string
  projectPath: string
  state: 'idle' | 'active' | 'error'
}

export interface WorkProject {
  id: string
  name: string
  rootPath: string
  source: 'index' | 'task_directory'
}

export interface ForwardRequest {
  id: string
  version: number
  phase: ForwardPhase
  text: string
  executor: WorkExecutor
  target?: { targetId: string, title?: string, projectPath?: string }
  project?: { rootPath: string }
  plan?: { title: string, reason: string }
  createdAt: string
  confirmedAt?: string
  detail?: string
  result?: string
  appTurnId?: string
  harnessSessionId?: string
  nativeStatus?: NativeStatus
  nativeApprovalTools?: string[]
  nativeSessionUrl?: string
  reminderCleared?: boolean
}

export interface WorkDraft {
  id: string
  version: number
  status: 'open' | 'prepared' | 'confirming' | 'confirmed' | 'dismissed' | 'failed'
  originalText: string
  text: string
  executor: WorkExecutor
  targetId: string
  projectId: string
  projectVersion: number
  plan?: { title: string, reason: string }
}

export interface WorkSnapshot {
  requests: ForwardRequest[]
  draft: WorkDraft | null
  connection: { codex: string, harness: string }
  executionAvailable: boolean
}

export interface WorkPrepareInput {
  text: string
  executor?: WorkExecutor
  targetId?: string
  projectPath?: string
  plan?: { title: string, reason: string }
}

export interface WorkOpenResult extends DesktopResult {
  url?: string
}

export interface ShizukiConnectionStatus {
  connected: boolean
  origin: string
  email: string
  userId: number
  error: string
  mainUserId?: number
  qqUploadConfigured?: boolean
  qqPending?: number
  qqLastUploadedAt?: number
  qqError?: string
  qqNextAttemptAt?: number
  reminderError?: string
}

export interface ShizukiTodo {
  todoId: number
  projectId: number | null
  title: string
  detail: string
  priority: string
  done: boolean
  dueAt: string
  showOnCalendar: boolean
  timePrecision: string
  timingMode: string
  rangeStartAt: string
  reminderEnabled: boolean
  startRemindValue: number | null
  startRemindUnit: string
  deadlineRemindValue: number | null
  deadlineRemindUnit: string
  sortNum: number
  updatedAt: string
}

export interface ShizukiReminderEvent {
  todoId: number
  title: string
  dueAt: string
  triggerAt: number
  late: boolean
  mainAccount?: boolean
}

export interface PendingTodoCreate {
  requestId: string
  title: string
  dueAt: string
  state: 'uncertain' | 'rejected'
}

export interface DesktopVoiceState {
  state: 'listening' | 'recognizing' | 'completed' | 'cancelled' | 'error'
  text?: string
  error?: string
  stage?: 'engine' | 'audio_input' | 'recognition' | 'helper_process'
}

export interface MeguriDesktopBridge {
  view: 'startup' | 'stage' | 'settings' | 'studio'
  readiness: {
    get(): Promise<ReadinessSnapshot>
    retry(): Promise<ReadinessSnapshot>
    onChanged(listener: (snapshot: ReadinessSnapshot) => void): () => void
    rendererReady(): Promise<void>
  }
  preferences: {
    get(): Promise<DesktopPreferences>
    update(patch: DesktopPreferencesPatch): Promise<DesktopPreferences>
    migrateLegacy(patch: DesktopPreferencesPatch): Promise<DesktopPreferences>
    onChanged(listener: (preferences: DesktopPreferences) => void): () => void
  }
  wallpaper?: {
    select(): Promise<DesktopWallpaperResult>
  }
  diagnostics?: {
    export(): Promise<DesktopDiagnosticResult>
  }
  live2d?: Live2DBridge
  speechResources?: SpeechResourcesBridge
  updates?: {
    get(): Promise<DesktopUpdateState>
    check(): Promise<DesktopUpdateState>
    openRelease(): Promise<DesktopResult>
    onChanged(listener: (state: DesktopUpdateState) => void): () => void
  }
  startupSettings: {
    getStatus(): Promise<DesktopStartupStatus>
    save(patch: DesktopStartupPreferencesPatch): Promise<DesktopStartupSaveResult>
  }
  multimodalRelay: {
    get(): Promise<MultimodalRelayConfig>
    save(draft: MultimodalRelayDraft): Promise<MultimodalRelaySaveResult>
    test(draft: MultimodalRelayDraft): Promise<MultimodalRelayTestResult>
  }
  localAgents: {
    get(): Promise<LocalAgentSettings>
    save(patch: LocalAgentSettingsPatch): Promise<LocalAgentSaveResult>
    test(): Promise<LocalAgentProbeResult>
  }
  work: {
    snapshot(): Promise<WorkSnapshot>
    targets(query: string): Promise<{ limit: number, items: WorkTarget[] }>
    projects(): Promise<WorkProject[]>
    prepare(input: WorkPrepareInput): Promise<DesktopResult & { request?: ForwardRequest }>
    confirm(id: string, version: number): Promise<DesktopResult & { request?: ForwardRequest }>
    refresh(id: string): Promise<DesktopResult & { request?: ForwardRequest }>
    dismiss(id: string, version: number): Promise<DesktopResult & { request?: ForwardRequest }>
    openNative(id: string): Promise<WorkOpenResult>
    onChanged(listener: (snapshot: WorkSnapshot) => void): () => void
  }
  shizuki?: {
    status(): Promise<ShizukiConnectionStatus>
    setMainAccount(): Promise<DesktopResult & { status?: ShizukiConnectionStatus }>
    clearMainAccount(): Promise<DesktopResult & { status?: ShizukiConnectionStatus }>
    connect(input: { origin: string, email: string, password: string }): Promise<DesktopResult & { status?: ShizukiConnectionStatus }>
    disconnect(): Promise<DesktopResult & { status?: ShizukiConnectionStatus }>
    listTodos(): Promise<DesktopResult & { todos?: ShizukiTodo[] }>
    createTodo(input: { title: string, dueAt?: string, requestId: string, expectedAccount: string, behavior?: 'reminder', timezone?: 'Asia/Shanghai' }): Promise<DesktopResult & { todo?: ShizukiTodo }>
    pendingCreates(): Promise<DesktopResult & { requests?: PendingTodoCreate[] }>
    updateTodo(id: number, input: { title?: string, dueAt?: string, cancel?: boolean, expectedAccount: string }): Promise<DesktopResult & { todo?: ShizukiTodo }>
    completeTodo(id: number, expectedAccount: string): Promise<DesktopResult & { todo?: ShizukiTodo }>
    onReminder(listener: (reminder: ShizukiReminderEvent) => void): () => void
  }
  voice?: {
    start(): Promise<DesktopResult & {
      text?: string
      code?: string
      stage?: DesktopVoiceState['stage']
      failureSource?: 'launcher' | 'helper'
      failureDiagnostic?: 'script_missing' | 'script_parse_failure' | 'script_parameter_failure' | 'script_access_denied' | 'script_runtime_failure' | 'helper_argument_failure' | 'helper_method_failure' | 'helper_io_failure' | 'speech_component_failure' | 'helper_bootstrap_failure'
      failureLine?: number
    }>
    cancel(): Promise<DesktopResult>
    onChanged(listener: (state: DesktopVoiceState) => void): () => void
  }
  window: {
    hide(): Promise<void>
    minimize(): Promise<void>
    toggleVisibility(): Promise<boolean>
    setClickThrough(ignore: boolean): Promise<void>
    pointerPosition(): Promise<{ x: number, y: number } | undefined>
    setPresentationSurface?(surface: 'ambient' | 'ambient-panel' | 'galgame'): Promise<DesktopResult>
    openSettings(): Promise<void>
    openStudio(): Promise<void>
    centerStage(): Promise<void>
    reloadStage(): Promise<void>
    setAlwaysOnTop(value: boolean): Promise<DesktopPreferences>
    quit(): Promise<void>
  }
  artifacts: {
    open(path: string): Promise<DesktopResult>
    icon(path: string): Promise<ArtifactIconResult>
  }
  attachments: {
    pathForFile(file: File): string
    inspect(path: string): Promise<DesktopAttachmentResult>
    clipboardImage(): Promise<DesktopAttachmentResult>
    onClipboardImportRequested(listener: () => void): () => void
  }
  screenshots: {
    start(mode: PixPinCaptureMode): Promise<PixPinCaptureStartResult>
    cancel(captureId: string): Promise<DesktopResult>
    status(): Promise<PixPinCaptureSnapshot>
    onChanged(listener: (event: PixPinCaptureEvent) => void): () => void
  }
  characterPacks?: {
    list(): Promise<CharacterPackCatalog>
    importDirectory(): Promise<CharacterPackResult>
    addResource(draftId: string): Promise<CharacterPackResult>
    publish(draftId: string): Promise<CharacterPackResult>
    activate(packageId: string): Promise<CharacterPackResult>
    disable(packageId: string): Promise<CharacterPackResult>
    restore(packageId: string): Promise<CharacterPackResult>
    preview(packageId: string, resourceId: string): Promise<CharacterPackPreviewResult>
  }
  characterPresentation?: {
    getActive(): Promise<CharacterPresentationSnapshot>
  }
}
