export interface Live2DReaction {
  motion?: string
  expression?: string
  speech?: string
}

export interface Live2DModelSummary {
  id: string
  name: string
  importedAt: string
  entryPoint: string
  hitAreas: string[]
  motions: string[]
  expressions: string[]
  reactions: Record<string, Live2DReaction>
  license?: string
}

export interface Live2DCatalog {
  version: 1
  activeId?: string
  models: Live2DModelSummary[]
}

export interface Live2DActive extends Live2DModelSummary {
  modelUrl: string
}

export interface Live2DResult {
  ok: boolean
  error?: string
  catalog?: Live2DCatalog
}

export interface Live2DBridge {
  catalog(): Promise<Live2DCatalog>
  importDirectory(): Promise<Live2DResult>
  importArchive(): Promise<Live2DResult>
  activate(id: string): Promise<Live2DResult>
  remove(id: string): Promise<Live2DResult>
  active(): Promise<Live2DActive | null>
}
