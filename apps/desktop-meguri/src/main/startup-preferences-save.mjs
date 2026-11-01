import { mergeDesktopPreferences } from './desktop-preferences.mjs'

function errorText(error) {
  return error instanceof Error ? error.message : String(error)
}

/**
 * Save the user's requested startup preference before touching Task Scheduler.
 * `ok` means both operations completed; `saved` reports the local preference
 * write independently so callers can retain a choice with an unapplied OS task.
 */
export async function saveStartupPreferences({ currentPreferences, patch, persist, apply }) {
  const next = mergeDesktopPreferences(currentPreferences, {
    startup: { ...currentPreferences.startup, ...patch },
  })

  try {
    await persist(next)
  }
  catch (error) {
    const message = `本机启动偏好保存失败；Windows 启动任务未更改。${errorText(error).slice(0, 1200)}`
    return {
      ok: false,
      saved: false,
      preferences: currentPreferences,
      status: { ok: false, pending: false, code: 'preference_save_failed', message },
      error: message,
    }
  }

  let applied
  try {
    applied = await apply(next.startup)
  }
  catch (error) {
    applied = { ok: false, message: errorText(error) }
  }

  if (applied?.ok) {
    return {
      ok: true,
      saved: true,
      preferences: next,
      status: { ...applied, ok: true, pending: false },
    }
  }

  const message = `启动偏好已保存在本机，但 Windows 启动任务尚未更新；当前任务仍保持此前状态。${applied?.message || 'Windows 未确认应用启动任务。'}`
  return {
    ok: false,
    saved: true,
    preferences: next,
    status: {
      ...applied,
      ok: false,
      pending: true,
      code: applied?.code || 'windows_startup_pending',
      message,
    },
    error: message,
  }
}
