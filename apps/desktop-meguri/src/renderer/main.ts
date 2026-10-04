import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import './styles.css'

if (import.meta.env.DEV)
  await import('./preview-bridge').then(module => module.installPreviewBridge())

createApp(App)
  .use(createPinia())
  .mount('#app')
