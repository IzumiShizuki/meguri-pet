import { defineStore } from 'pinia'

import {
  defaultCharacterState,
  presentationFromCue,
  type CharacterPresentation,
} from '../utils/character-presentation'

export const usePresentationStore = defineStore('presentation', {
  state: () => ({
    character: { ...defaultCharacterState } as CharacterPresentation,
    speaking: false,
    statusText: 'Meguri 已就绪',
    statusTone: 'ready' as 'ready' | 'busy' | 'error',
  }),
  actions: {
    applyCue(data: Record<string, unknown>) {
      this.character = presentationFromCue(this.character, data)
    },
    cycleOutfit(direction: 1 | -1) {
      const current = Number(this.character.outfitCode)
      const next = ((current - 1 + direction + 6) % 6) + 1
      this.selectOutfit(String(next).padStart(2, '0'))
    },
    selectOutfit(outfitCode: string) {
      if (!/^0[1-6]$/.test(outfitCode))
        return
      this.character = presentationFromCue(this.character, { outfit_code: outfitCode, sprite_file: undefined })
    },
    setSpeaking(value: boolean) {
      this.speaking = value
    },
    setStatus(text: string, tone: 'ready' | 'busy' | 'error' = 'ready') {
      this.statusText = text
      this.statusTone = tone
    },
  },
})
