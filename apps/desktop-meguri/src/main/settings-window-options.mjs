export function resolveSettingsBounds(workArea) {
  const area = workArea && typeof workArea === 'object'
    ? workArea
    : { x: 0, y: 0, width: 1920, height: 1080 }
  const width = Math.min(600, Math.max(420, Number(area.width) || 600))
  const height = Math.min(800, Math.max(600, Number(area.height) || 800))
  return {
    width,
    height,
    x: Math.round((Number(area.x) || 0) + ((Number(area.width) || width) - width) / 2),
    y: Math.round((Number(area.y) || 0) + ((Number(area.height) || height) - height) / 2),
  }
}

export function createSettingsWindowOptions({ bounds, preloadPath }) {
  return {
    title: 'Meguri 设置',
    ...bounds,
    minWidth: 420,
    minHeight: 600,
    frame: false,
    transparent: false,
    resizable: true,
    show: false,
    backgroundColor: '#faf8f3',
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      backgroundThrottling: false,
      preload: preloadPath,
      additionalArguments: ['--meguri-view=settings'],
    },
  }
}
