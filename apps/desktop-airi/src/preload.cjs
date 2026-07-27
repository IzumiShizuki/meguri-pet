const { contextBridge, ipcRenderer, webUtils } = require('electron')

const trustedHosts = new Set(['127.0.0.1', 'localhost', '[::1]'])

if (trustedHosts.has(globalThis.location.hostname)) {
  // Keep the bridge deliberately narrow: a dropped native File can be resolved
  // to its path, but the renderer receives no Node, shell, or arbitrary IPC API.
  contextBridge.exposeInMainWorld('meguriDesktop', {
    getPathForFile(file) {
      try {
        return webUtils.getPathForFile(file)
      } catch {
        return ''
      }
    },
    openGeneratedArtifact(path) {
      return ipcRenderer.invoke('meguri-open-artifact', path)
    },
    getArtifactIcon(path) {
      return ipcRenderer.invoke('meguri-artifact-icon', path)
    },
  })
}
