/**
 * Builds the native pet-window options without importing Electron so the
 * Windows composition contract can be covered by a fast unit test.
 *
 * Do not add an explicit fully-transparent `backgroundColor` here. With
 * Electron 43 on Windows that made Chromium render correctly while DWM
 * composited the entire host window at zero alpha.
 */
export function createStageWindowOptions({ bounds, preloadPath, alwaysOnTop = true }) {
  return {
    title: 'Meguri',
    ...bounds,
    minWidth: 360,
    minHeight: 560,
    frame: false,
    transparent: true,
    resizable: true,
    hasShadow: false,
    alwaysOnTop,
    skipTaskbar: true,
    show: false,
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      backgroundThrottling: false,
      preload: preloadPath,
      additionalArguments: ['--meguri-view=stage'],
    },
  }
}
