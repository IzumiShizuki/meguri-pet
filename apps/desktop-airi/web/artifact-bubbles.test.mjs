import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

import { createArtifactBubble } from './artifact-bubbles.mjs'

class FakeElement extends EventTarget {
  constructor(tagName) {
    super()
    this.tagName = tagName.toUpperCase()
    this.children = []
    this.parentNode = null
    this.attributes = new Map()
    this.className = ''
    this.textContent = ''
    this.isConnected = false
    this.disabled = false
  }

  append(...children) {
    for (const child of children) {
      child.parentNode = this
      child.isConnected = this.isConnected
      this.children.push(child)
    }
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value))
  }

  remove() {
    if (!this.parentNode) return
    this.parentNode.children = this.parentNode.children.filter(child => child !== this)
    this.parentNode = null
    this.isConnected = false
  }

  replaceWith(next) {
    if (!this.parentNode) return
    const siblings = this.parentNode.children
    const index = siblings.indexOf(this)
    next.parentNode = this.parentNode
    next.isConnected = this.isConnected
    siblings.splice(index, 1, next)
    this.parentNode = null
    this.isConnected = false
  }

  click() {
    this.dispatchEvent(new Event('click', { bubbles: true }))
  }
}

class FakeDocument {
  createElement(tagName) {
    return new FakeElement(tagName)
  }
}

function makeContainer(document) {
  const container = document.createElement('div')
  container.isConnected = true
  return container
}

function keydown(target, key) {
  const event = new Event('keydown', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'key', { value: key })
  target.dispatchEvent(event)
  return event
}

async function settle() {
  await new Promise(resolve => setImmediate(resolve))
}

test('rendered bubble exposes separate native open and dismiss actions', () => {
  const document = new FakeDocument()
  const container = makeContainer(document)
  const bubble = createArtifactBubble({ document, item: { name: 'report.md' }, onOpen: async () => true })
  container.append(bubble.element)

  assert.equal(bubble.openButton?.tagName, 'BUTTON')
  assert.equal(bubble.closeButton.tagName, 'BUTTON')
  assert.equal(bubble.openButton?.parentNode, bubble.element)
  assert.equal(bubble.closeButton.parentNode, bubble.element)
})

test('pointer activation opens the artifact and removes the bubble after success', async () => {
  const document = new FakeDocument()
  const container = makeContainer(document)
  let calls = 0
  const bubble = createArtifactBubble({
    document,
    item: { name: 'report.md' },
    onOpen: async () => { calls += 1; return true },
  })
  container.append(bubble.element)

  bubble.openButton.click()
  await settle()

  assert.equal(calls, 1)
  assert.equal(container.children.length, 0)
})

test('Enter and Space activate the same open action and prevent default behavior', async () => {
  for (const key of ['Enter', ' ']) {
    const document = new FakeDocument()
    const container = makeContainer(document)
    let calls = 0
    const bubble = createArtifactBubble({
      document,
      item: { name: 'report.md' },
      onOpen: async () => { calls += 1; return true },
    })
    container.append(bubble.element)

    const event = keydown(bubble.openButton, key)
    await settle()

    assert.equal(event.defaultPrevented, true, key)
    assert.equal(calls, 1, key)
    assert.equal(container.children.length, 0, key)
  }
})

test('failed opening keeps the bubble and leaves dismissal available', async () => {
  const document = new FakeDocument()
  const container = makeContainer(document)
  let calls = 0
  const bubble = createArtifactBubble({
    document,
    item: { name: 'report.md' },
    onOpen: async () => { calls += 1; return false },
  })
  container.append(bubble.element)

  bubble.openButton.click()
  await settle()
  assert.equal(calls, 1)
  assert.equal(container.children.length, 1)

  bubble.closeButton.click()
  assert.equal(container.children.length, 0)
})

test('dismissal remains usable while opening is pending', async () => {
  const document = new FakeDocument()
  const container = makeContainer(document)
  let resolveOpen
  const bubble = createArtifactBubble({
    document,
    item: { name: 'report.md' },
    onOpen: () => new Promise(resolve => { resolveOpen = resolve }),
  })
  container.append(bubble.element)

  bubble.openButton.click()
  await settle()
  assert.equal(bubble.openButton.disabled, true)

  bubble.closeButton.click()
  assert.equal(container.children.length, 0)
  resolveOpen(true)
  await settle()
  assert.equal(container.children.length, 0)
})

test('the page loads the bubble interaction seam', () => {
  const currentFile = fileURLToPath(import.meta.url)
  const html = readFileSync(join(dirname(currentFile), 'index.html'), 'utf8')
  assert.match(html, /import\s+\{\s*createArtifactBubble/)
})
