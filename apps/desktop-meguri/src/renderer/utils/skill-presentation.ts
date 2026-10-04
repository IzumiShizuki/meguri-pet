import type { SkillRevisionView } from '../services/skills-api'

/** Keeps the enable/rollback guard identical for every revision action surface. */
export function canActivateRevision(
  revision: SkillRevisionView,
  licenseConfirmed: boolean,
  fallbackLicense?: string,
): boolean {
  const license = revision.manifest?.license ?? fallbackLicense
  return revision.report.state === 'VALID'
    && revision.report.compatible
    && !revision.active
    && Boolean(license || licenseConfirmed)
}
