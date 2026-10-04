/**
 * Wires the local work agents into one narrow surface for the desktop main
 * process.
 *
 * Everything here is optional and fails soft: when no work agent is installed,
 * Meguri behaves exactly as before. Nothing in this module reads or stores a
 * model credential; Codex and the DeepSeek Harness own their own auth.
 */

import { closeSync, existsSync, mkdirSync, openSync } from 'node:fs'
import { spawn } from 'node:child_process'

import { createCodexBridge } from './local-agents/codex-bridge.mjs'
import { harnessCredentialState, codexHomeState, findDshCli } from './local-agents/dsh-discovery.mjs'
import { createHarnessBridge, harnessLauncherUrl } from './local-agents/dsh-bridge.mjs'
import { installWorkPreset, workPresetFiles, workPresetReady } from './local-agents/dsh-preset.mjs'
import { createReceipts } from './local-agents/receipts.mjs'
import { createWorkForwarding } from './local-agents/work-forwarding.mjs'
import { defaultPaths, isAbsoluteLocalPath, textValue } from './local-agents/paths.mjs'

export class LocalAgentError extends Error {
  constructor(code, message) {
    super(message)
    this.name = 'LocalAgentError'
    this.code = code
  }
}

export function createLocalAgentRuntime(options = {}) {
  const environment = options.environment ?? process.env
  const paths = { ...defaultPaths(environment), ...(options.paths ?? {}) }
  const exists = options.exists ?? existsSync
  const logger = options.logger ?? (() => {})

  // A disabled runtime must not touch the user's work agents at all.
  let config = options.config ?? { version: 1, enabled: false, harnessAutoStart: false }
  let receipts
  let forwarding
  let codex
  let harness
  let hostProcess
  let closing = false

  function dshHome() {
    return config.dshHome || paths.dshHome
  }

  function harnessServiceHome() {
    return paths.harnessServiceHome
  }

  function dshCli() {
    return findDshCli({ environment })
  }

  function harnessLogPath() {
    return `${harnessServiceHome()}\\web.log`
  }

  function ensureReceipts() {
    if (!receipts)
      receipts = createReceipts({ filename: paths.receiptDatabasePath })
    return receipts
  }

  function ensureCodex() {
    if (!codex) {
      codex = createCodexBridge({
        environment,
        codexHome: paths.codexHome,
        executable: paths.codexExecutable,
        installRoot: paths.codexInstallRoot,
      })
    }
    return codex
  }

  function ensureHarness() {
    if (!harness) {
      harness = createHarnessBridge({
        launchUrl: () => harnessLauncherUrl(harnessLogPath()),
      })
    }
    return harness
  }

  function ensureForwarding() {
    if (!forwarding) {
      forwarding = createWorkForwarding({
        receipts: ensureReceipts(),
        codex: ensureCodex(),
        harness: ensureHarness(),
        workspaceRoot: harnessServiceHome(),
        discoverProjects: options.discoverProjects,
      })
    }
    return forwarding
  }

  /**
   * Reports the work-preset state without writing anything: reading settings is
   * not allowed to modify the user's Harness home.
   */
  function presetState() {
    const cli = dshCli()
    if (!cli)
      return { cli: 'missing', preset: 'unavailable' }
    if (workPresetReady(dshHome()))
      return { cli: 'installed', preset: 'present' }
    return { cli: 'installed', preset: exists(workPresetFiles(dshHome()).composition) ? 'user_preset' : 'missing' }
  }

  /** Local install facts only. No credential value is read or returned. */
  function installSnapshot() {
    const state = presetState()
    return {
      codex: {
        installed: ensureCodex().installed(),
        home: paths.codexHome,
        ...codexHomeState({ environment }),
      },
      harness: {
        cli: state.cli,
        credentials: state.cli === 'installed' ? harnessCredentialState({ environment }) : 'missing',
        preset: state.preset,
        logReady: exists(harnessLogPath()),
      },
    }
  }

  function requireEnabled() {
    if (!config.enabled)
      throw new LocalAgentError('disabled', '任务转交尚未启用，请先在设置中开启。')
  }

  /** Install the work preset once, on an explicit user action only. */
  function installPreset() {
    if (!dshCli())
      return { installed: false, reason: 'missing_cli' }
    const result = installWorkPreset(dshHome(), {})
    return {
      installed: result.installed,
      reason: result.installed ? 'installed' : result.reason,
    }
  }

  return {
    paths,
    installSnapshot,
    settings() {
      return { ...config, install: installSnapshot() }
    },
    updateSettings(patch) {
      config = { ...config, ...(patch ?? {}) }
      if (config.enabled === false)
        config.harnessAutoStart = false
      // Enabling forwarding installs the work preset once; reading never does.
      const presetInstall = config.enabled === true ? installPreset() : undefined
      return { ...config, install: installSnapshot(), ...(presetInstall ? { presetInstall } : {}) }
    },

    /** Cheap readiness probe used by the settings page's "test connection" action. */
    async connectTest() {
      requireEnabled()
      const [codexResult, harnessResult] = await Promise.all([
        ensureCodex().compatible().catch(() => ({ compatible: false, reason: 'unavailable' })),
        ensureHarness().probe().catch(() => ({ state: 'unavailable' })),
      ])
      return {
        codex: codexResult.compatible
          ? { ok: true, message: `Codex 已连接（${codexResult.account === 'unverified' ? '账号状态未核实' : codexResult.account === 'api_key' ? 'API Key' : '已登录账号'}）。` }
          : { ok: false, message: codexResult.reason === 'unavailable' ? '没有找到本机 Codex，或 Codex 未登录。' : 'Codex 暂时不可用。' },
        harness: harnessResult.state === 'ready'
          ? { ok: true, message: 'DeepSeek Harness 已连接。' }
          : { ok: false, message: harnessResult.state === 'authentication_required' ? 'DeepSeek Harness 需要重新登录。' : 'DeepSeek Harness 尚未启动。' },
      }
    },

    snapshot() {
      if (!config.enabled)
        return { requests: [], draft: null, connection: { codex: 'disabled', harness: 'disabled' }, executionAvailable: false }
      return ensureForwarding().snapshot()
    },
    targets(query) {
      requireEnabled()
      return ensureForwarding().targets(query)
    },
    projects() {
      requireEnabled()
      return ensureForwarding().projects()
    },
    prepare(input) {
      requireEnabled()
      return ensureForwarding().prepare(input)
    },
    confirm(id, version) {
      requireEnabled()
      return ensureForwarding().confirm(id, version)
    },
    refresh(id) {
      if (!config.enabled)
        throw new LocalAgentError('disabled', '任务转交尚未启用。')
      return ensureForwarding().refresh(id)
    },
    dismiss(id, version) {
      if (!config.enabled)
        throw new LocalAgentError('disabled', '任务转交尚未启用。')
      return ensureForwarding().dismiss(id, version)
    },

    /** Opens the native executor UI; Meguri never drives it beyond this. */
    async nativeUrl(id) {
      const record = ensureReceipts().get(id)
      if (!record)
        throw new LocalAgentError('not_found', '找不到这张任务卡。')
      if (record.executor === 'harness') {
        try {
          return { url: await ensureHarness().hostUrl() }
        }
        catch {
          return { url: '' }
        }
      }
      const targetId = textValue(record.target?.targetId, 200)
      return { url: targetId ? `codex://threads/${targetId}` : '' }
    },

    /**
     * Optional: keep a Harness `web` host running for the user.
     *
     * The host's launcher line (which carries its session token) is written only
     * into the private log file; it never reaches a log line, the renderer or a
     * receipt. Meguri needs the user's explicit opt-in before starting a
     * background process that outlives a single turn.
     */
    startHarnessHost() {
      if (hostProcess)
        return { started: false, reason: 'running' }
      const cli = dshCli()
      if (!cli)
        return { started: false, reason: 'missing_cli' }
      const nodeExecutable = options.nodeExecutable ?? process.execPath
      const spawnImpl = options.spawnImpl ?? spawn
      const serviceHome = harnessServiceHome()
      const logPath = harnessLogPath()
      let descriptor
      try {
        mkdirSync(serviceHome, { recursive: true })
        // The launcher line is read from the head of this file, so start clean.
        descriptor = openSync(logPath, 'w', 0o600)
      }
      catch {
        return { started: false, reason: 'unavailable' }
      }
      installPreset()
      try {
        hostProcess = spawnImpl(nodeExecutable, [cli, 'web', '--no-open', '--port', '0'], {
          windowsHide: true,
          stdio: ['ignore', descriptor, descriptor],
          detached: false,
          env: { ...environment, DSH_HOME: dshHome() },
        })
      }
      catch {
        closeSync(descriptor)
        return { started: false, reason: 'unavailable' }
      }
      closeSync(descriptor)
      hostProcess?.once?.('exit', () => { hostProcess = undefined })
      logger('DeepSeek Harness web host started for the desktop pet.')
      return { started: true }
    },

    stopHarnessHost() {
      if (!hostProcess)
        return { stopped: false }
      try {
        hostProcess.kill()
      }
      catch {
        // A host that already exited needs no cleanup.
      }
      hostProcess = undefined
      return { stopped: true }
    },

    presetReady() {
      return workPresetReady(dshHome())
    },

    async shutdown() {
      if (closing)
        return
      closing = true
      this.stopHarnessHost()
      if (forwarding)
        await forwarding.close()
      else
        receipts?.close()
    },
  }
}
