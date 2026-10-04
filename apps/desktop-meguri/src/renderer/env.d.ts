/// <reference types="vite/client" />

import type { MeguriDesktopBridge } from '../shared/desktop-contract'

declare global {
  interface Window {
    meguriDesktop?: MeguriDesktopBridge
  }
}

export {}
