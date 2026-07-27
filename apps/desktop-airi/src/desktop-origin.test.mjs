import assert from 'node:assert/strict'
import test from 'node:test'

import { desktopOrigin } from './desktop-origin.mjs'

test('allows the production Electron file origin', () => {
  assert.equal(desktopOrigin({ headers: { origin: 'file://' } }), 'file://')
})

test('allows the captured originless AIRI provider request', () => {
  assert.equal(desktopOrigin({
    headers: {
      'user-agent': 'Mozilla/5.0 Electron/43.1.1',
      'x-meguri-desktop-client': 'airi',
    },
  }), null)
})

test('keeps the existing opaque and local development origins', () => {
  assert.equal(desktopOrigin({ headers: { origin: 'null' } }), 'null')
  assert.equal(desktopOrigin({ headers: { origin: 'http://127.0.0.1:5173' } }), 'http://127.0.0.1:5173')
  assert.equal(desktopOrigin({ headers: { origin: 'http://localhost:4173' } }), 'http://localhost:4173')
})

test('keeps arbitrary web origins outside the desktop gateway', () => {
  assert.equal(desktopOrigin({ headers: { origin: 'https://example.com' } }), undefined)
  assert.equal(desktopOrigin({ headers: {} }), undefined)
  assert.equal(desktopOrigin({ headers: { 'user-agent': 'Mozilla/5.0 Electron/43.1.1' } }), undefined)
  assert.equal(desktopOrigin({ headers: { 'x-meguri-desktop-client': 'airi' } }), undefined)
})
