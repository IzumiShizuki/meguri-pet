import { readFileSync } from 'node:fs'
import ts from 'typescript'

// Execute the actual production handler, not a parallel implementation.
const view = readFileSync(new URL('../src/renderer/views/SettingsView.vue', import.meta.url), 'utf8')
const script = view.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)?.[1]
if (!script) throw new Error('SettingsView script was not found')
const source = ts.createSourceFile('SettingsView.ts', script, ts.ScriptTarget.Latest, true)
const names = ['startupBridgeErrorMessage', 'saveStartupSettings']
const functions = names.map(name => {
  const node = source.statements.find(node => ts.isFunctionDeclaration(node) && node.name?.text === name)
  if (!node) throw new Error(`SettingsView function missing: ${name}`)
  return node.getText(source)
})
export const startupHandlerCode = ts.transpileModule(functions.join('\n'), {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
}).outputText

const form = view.match(/<form class="agent-settings" @submit.prevent="saveStartupSettings">([\s\S]*?)<\/form>/)?.[1]
const feedback = form?.slice(form.indexOf('<p '))
if (!feedback?.includes('startupFeedback.message')) throw new Error('Startup feedback template was not found')
export const startupFeedbackTemplate = `<div>${feedback}</div>`
