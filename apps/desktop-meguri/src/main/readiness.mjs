const gatewayOrigin = process.env.MEGURI_GATEWAY_ORIGIN ?? 'http://127.0.0.1:15173'

const definitions = [
  { capability: 'gateway', label: '桌面网关', url: `${gatewayOrigin}/artifacts/recent`, desktopHeaders: true },
  { capability: 'core', label: 'Meguri Core', url: `${gatewayOrigin}/core/health`, desktopHeaders: true, validate: data => data?.status === 'ok' },
  { capability: 'tts', label: '本地语音', url: 'http://127.0.0.1:9880/health', optional: true, validate: data => data?.ready === true },
  {
    capability: 'assets',
    label: '角色资源',
    url: `${gatewayOrigin}/assets/sprites/meguri/ce04001l.png`,
    validateResponse: response => response.headers.get('content-type')?.startsWith('image/png') === true,
  },
]

export function createReadinessCoordinator({ fetchImpl = fetch, desktopSession = 'meguri-desktop', timeoutMs = 4_000, definitions: capabilityDefinitions = definitions } = {}) {
  let runId = 0
  let snapshot = createSnapshot(0, 'checking', capabilityDefinitions)
  const listeners = new Set()

  function emit(next) {
    snapshot = next
    for (const listener of listeners)
      listener(snapshot)
  }

  async function check() {
    const currentRun = ++runId
    emit(createSnapshot(currentRun, 'checking', capabilityDefinitions))
    const checked = await Promise.all(capabilityDefinitions.map(definition => checkCapability({
      definition,
      fetchImpl,
      desktopSession,
      timeoutMs,
    })))
    const requiredFailure = checked.some(item => item.status === 'failed' && !item.optional)
    const next = {
      runId: currentRun,
      status: requiredFailure ? 'failed' : 'ready',
      items: [
        ...checked.map(({ optional: _optional, ...item }) => item),
        {
          capability: 'renderer',
          label: '界面渲染',
          status: 'pending',
          detail: '等待舞台完成挂载',
        },
      ],
    }
    if (runId === currentRun)
      emit(next)
    return next
  }

  function markRendererReady() {
    const items = snapshot.items.map(item => item.capability === 'renderer'
      ? { ...item, status: 'ready', detail: '界面已就绪', checkedAt: Date.now() }
      : item)
    emit({ ...snapshot, items })
  }

  return {
    check,
    current: () => snapshot,
    markRendererReady,
    subscribe(listener) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}

function createSnapshot(runId, status, capabilityDefinitions = definitions) {
  return {
    runId,
    status,
    items: [
      ...capabilityDefinitions.map(definition => ({
        capability: definition.capability,
        label: definition.label,
        status: 'pending',
        detail: '等待检查',
      })),
      { capability: 'renderer', label: '界面渲染', status: 'pending', detail: '等待舞台完成挂载' },
    ],
  }
}

async function checkCapability({ definition, fetchImpl, desktopSession, timeoutMs }) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const response = await fetchImpl(definition.url, {
      cache: 'no-store',
      headers: definition.desktopHeaders
        ? {
            'X-Meguri-Desktop-Client': 'meguri',
            'X-Meguri-Desktop-Session': desktopSession,
            'User-Agent': 'Meguri Desktop Electron/43.1.1',
          }
        : undefined,
      signal: controller.signal,
    })
    if (!response.ok)
      throw new Error(`HTTP ${response.status}`)
    if (definition.validateResponse && !definition.validateResponse(response))
      throw new Error('响应不是有效的角色资源')
    const data = definition.validate ? await response.json() : undefined
    const ready = definition.validate ? definition.validate(data) : true
    if (!ready)
      throw new Error('服务尚未就绪')
    return readyItem(definition, definition.optional ? '可选能力已就绪' : '已就绪')
  }
  catch (error) {
    return {
      capability: definition.capability,
      label: definition.label,
      status: definition.optional ? 'ready' : 'failed',
      detail: definition.optional ? '暂不可用，可在启动后继续加载' : errorMessage(error),
      checkedAt: Date.now(),
      optional: definition.optional,
    }
  }
  finally {
    clearTimeout(timer)
  }
}

function readyItem(definition, detail) {
  return {
    capability: definition.capability,
    label: definition.label,
    status: 'ready',
    detail,
    checkedAt: Date.now(),
    optional: definition.optional,
  }
}

function errorMessage(error) {
  if (error instanceof Error && error.name === 'AbortError')
    return '检查超时'
  return error instanceof Error ? error.message : String(error)
}
