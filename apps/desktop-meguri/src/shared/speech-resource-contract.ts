export interface SpeechResourceProfile {
  id: string
  name: string
  engine: 'gpt-sovits'
  modelVersion: string
  language: string
  readOnly: boolean
  active: boolean
  available: boolean
  importedAt?: string
}

export interface SpeechResourceCatalog {
  engine: 'GPT-SoVITS'
  ready: boolean
  activeId: string
  profiles: SpeechResourceProfile[]
  error?: string
}

export interface SpeechResourceResult {
  ok: boolean
  error?: string
  catalog?: SpeechResourceCatalog
}

export interface SpeechResourcesBridge {
  list(): Promise<SpeechResourceCatalog>
  importPackage(): Promise<SpeechResourceResult>
  download(manifestUrl: string): Promise<SpeechResourceResult>
  activate(id: string): Promise<SpeechResourceResult>
  remove(id: string): Promise<SpeechResourceResult>
}
