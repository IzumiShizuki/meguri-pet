import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const virtualId = 'virtual:meguri-live2d'
const resolvedId = '\0meguri-live2d'

/** Read-only AIRI integration: extend public scene controls at build time without editing AIRI. */
export function live2dIntegration({ airiRoot = process.env.MEGURI_AIRI_ROOT ?? 'D:/program/airi-meguri' } = {}) {
  const sourceRoot = resolve(airiRoot).replaceAll('\\', '/')
  const scene = `${sourceRoot}/packages/stage-ui-live2d/src/components/scenes/Live2D.vue`
  const model = `${sourceRoot}/packages/stage-ui-live2d/src/components/scenes/live2d/Model.vue`
  const core = `${sourceRoot}/apps/stage-tamagotchi/src/renderer/public/assets/js/CubismSdkForWeb-5-r.3/Core/live2dcubismcore.min.js`
  const adapter = fileURLToPath(new URL('../src/renderer/services/live2d-ui-adapter.ts', import.meta.url))
  return {
    name: 'meguri-airi-live2d',
    enforce: 'pre',
    config() {
      return { resolve: { dedupe: ['vue', 'pinia'], alias: [{ find: /^@proj-airi\/ui$/, replacement: adapter }] }, server: { fs: { allow: [sourceRoot, resolve(fileURLToPath(new URL('..', import.meta.url)), '../..')] } } }
    },
    resolveId(id) { if (id === virtualId) return resolvedId },
    load(id) {
      if (id !== resolvedId) return
      if (!existsSync(scene) || !existsSync(core)) return 'throw new Error("AIRI Live2D 或 Cubism Core 不可用；请配置 MEGURI_AIRI_ROOT 后重建。"); export default undefined;'
      return `export { default } from ${JSON.stringify(scene)};`
    },
    transform(code, id) {
      const path = id.split('?')[0].replaceAll('\\', '/')
      if (id.includes('?')) return
      if (path === scene) {
        return code.replace('defineExpose({', `defineExpose({
  setMotion: (name: string, index?: number) => live2dModelRef.value?.setMotion(name, index),
  setExpression: (name: string) => live2dModelRef.value?.setExpression(name),
  hitTest: (x: number, y: number) => live2dModelRef.value?.hitTest(x, y) ?? [],
`).replace(':live2d-expression-enabled="live2dExpressionEnabled"', ':live2d-expression-enabled="false"')
      }
      if (path === model) {
        return code.replace('defineExpose({', `defineExpose({
  setExpression: (name: string) => model.value?.expression(name),
  hitTest: (clientX: number, clientY: number) => {
    const canvas = pixiApp.value?.view
    if (!canvas || !model.value) return []
    const bounds = canvas.getBoundingClientRect()
    // Model hit testing consumes Pixi global pixels; CSS and resolution can differ.
    return model.value.hitTest((clientX - bounds.left) * canvas.width / bounds.width, (clientY - bounds.top) * canvas.height / bounds.height)
  },
`).replace("import.meta.hot?.dispose(() => {", "false && import.meta.hot?.dispose(() => {")
      }
    },
    configureServer(server) {
      server.middlewares.use('/meguri-cubism-core.js', (_request, response) => {
        if (!existsSync(core)) { response.statusCode = 404; response.end('Cubism Core unavailable'); return }
        response.setHeader('Content-Type', 'application/javascript')
        response.end(readFileSync(core))
      })
    },
    generateBundle() {
      if (existsSync(core)) this.emitFile({ type: 'asset', fileName: 'meguri-cubism-core.js', source: readFileSync(core) })
    },
  }
}
