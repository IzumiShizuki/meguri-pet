import { coreUrl, desktopFetch } from './desktop-fetch'

export type ValidationState = 'QUARANTINED' | 'VALID' | 'INVALID'
export type ActivationState = 'DISABLED' | 'ENABLED'

export interface SkillRequirement {
  tools: string[]
  env: string[]
}

export interface SkillSummary {
  external_id: string
  name: string
  description: string
  license?: string
  tags: string[]
  revision: string
  updated_at?: string
}

export interface SkillSearchPage {
  items: SkillSummary[]
  page: number
  page_size: number
  total: number
}

export interface SkillSourceDetail {
  summary: SkillSummary
  requirements: SkillRequirement
  metadata: Record<string, unknown>
}

export interface SkillValidationIssue {
  code: string
  severity: 'WARNING' | 'ERROR'
  message: string
  path?: string
}

export interface SkillValidationReport {
  state: ValidationState
  compatible: boolean
  redistributable: boolean
  issues: SkillValidationIssue[]
  checked_at: string
}

export interface SkillManifestView {
  id: string
  source_id: string
  external_id: string
  trust: 'UNTRUSTED_EXTERNAL'
  name: string
  description: string
  license?: string
  tags: string[]
  requires: SkillRequirement
  always: boolean
  source_revision: string
  source_updated_at?: string
}

export interface SkillRevisionView {
  skill_id: string
  digest: string
  source_revision: string
  manifest?: SkillManifestView
  report: SkillValidationReport
  imported_at: string
  active: boolean
}

export interface SkillCatalogView {
  manifest: SkillManifestView
  revisions: SkillRevisionView[]
  binding: {
    skill_id: string
    state: ActivationState
    active_digest?: string
    updated_at: string
  }
}

export interface SkillUpdateStatus {
  update_available: boolean
  current_revision: string
  latest_revision: string
}

export class SkillApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly retryable = false,
    public readonly status = 0,
  ) {
    super(message)
    this.name = 'SkillApiError'
  }
}

async function requestJson<T>(sessionId: string, path: string, init: RequestInit = {}): Promise<T> {
  const response = await desktopFetch(sessionId, `${coreUrl}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...init.headers,
    },
  })
  const payload = await response.json().catch(() => ({})) as Record<string, unknown>
  if (!response.ok) {
    throw new SkillApiError(
      typeof payload.code === 'string' ? payload.code : `HTTP_${response.status}`,
      typeof payload.message === 'string' ? payload.message : 'Skill 服务暂时不可用。',
      payload.retryable === true,
      response.status,
    )
  }
  return payload as T
}

export const skillsApi = {
  search(sessionId: string, query: string, page = 1, pageSize = 20) {
    const params = new URLSearchParams({ q: query, page: String(page), page_size: String(pageSize) })
    return requestJson<SkillSearchPage>(sessionId, `/internal/v1/skill-sources/modelscope/skills?${params}`)
  },
  detail(sessionId: string, externalId: string) {
    const params = new URLSearchParams({ skill_id: externalId })
    return requestJson<SkillSourceDetail>(sessionId, `/internal/v1/skill-sources/modelscope/skills/detail?${params}`)
  },
  list(sessionId: string) {
    return requestJson<SkillCatalogView[]>(sessionId, '/internal/v1/skills')
  },
  get(sessionId: string, skillId: string) {
    return requestJson<SkillCatalogView>(sessionId, `/internal/v1/skills/${encodeURIComponent(skillId)}`)
  },
  importSkill(sessionId: string, externalId: string) {
    return requestJson<SkillCatalogView>(sessionId, '/internal/v1/skills/imports', {
      method: 'POST',
      body: JSON.stringify({ source_id: 'modelscope', external_id: externalId }),
    })
  },
  activate(sessionId: string, skillId: string, digest: string, licenseConfirmed: boolean) {
    return requestJson<SkillCatalogView>(sessionId,
      `/internal/v1/skills/${encodeURIComponent(skillId)}/revisions/${encodeURIComponent(digest)}:activate`, {
        method: 'POST',
        body: JSON.stringify({ license_confirmed: licenseConfirmed }),
      })
  },
  disable(sessionId: string, skillId: string) {
    return requestJson<SkillCatalogView>(sessionId,
      `/internal/v1/skills/${encodeURIComponent(skillId)}:disable`, { method: 'POST' })
  },
  checkUpdate(sessionId: string, skillId: string) {
    return requestJson<SkillUpdateStatus>(sessionId,
      `/internal/v1/skills/${encodeURIComponent(skillId)}:check-update`, { method: 'POST' })
  },
  importUpdate(sessionId: string, skillId: string) {
    return requestJson<SkillCatalogView>(sessionId,
      `/internal/v1/skills/${encodeURIComponent(skillId)}:import-update`, { method: 'POST' })
  },
}
