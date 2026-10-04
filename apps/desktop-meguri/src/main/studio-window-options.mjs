export function resolveStudioBounds(workArea) {
  const area = workArea && typeof workArea === 'object'
    ? workArea
    : { x: 0, y: 0, width: 1920, height: 1080 }
  const width = Math.min(1180, Math.max(760, Number(area.width) || 1180))
  const height = Math.min(800, Math.max(580, Number(area.height) || 760))
  return {
    width,
    height,
    x: Math.round((Number(area.x) || 0) + ((Number(area.width) || width) - width) / 2),
    y: Math.round((Number(area.y) || 0) + ((Number(area.height) || height) - height) / 2),
  }
}

export function createStudioWindowOptions({ bounds, preloadPath }) {
  return {
    title: 'Meguri Character Studio',
    ...bounds,
    minWidth: 760,
    minHeight: 580,
    frame: false,
    transparent: false,
    resizable: true,
    show: false,
    backgroundColor: '#f5f5f4',
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      backgroundThrottling: false,
      preload: preloadPath,
      additionalArguments: ['--meguri-view=studio'],
    },
  }
}
