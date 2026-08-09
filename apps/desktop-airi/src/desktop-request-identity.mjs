function presentValues(values) {
  return values.filter(value => value !== undefined && value !== null)
}

function allPresentValuesMatch(values, expected) {
  const present = presentValues(values)
  return present.length > 0 && present.every(value => value === expected)
}

/**
 * Accepts either the legacy flat turn identity or the canonical protocol
 * identity envelope. If both shapes are present, every supplied value must
 * agree so a caller cannot smuggle a conflicting Core identity past the gate.
 */
export function matchesDesktopRequestIdentity(payload, desktopSession) {
  if (!payload || typeof payload !== 'object')
    return false

  const identity = payload.identity && typeof payload.identity === 'object'
    ? payload.identity
    : {}

  return allPresentValuesMatch(
    [payload.user_id, identity.meguri_user?.id],
    'local-airi-user',
  ) && allPresentValuesMatch(
    [payload.client_id, identity.client_instance?.profile],
    'airi',
  ) && allPresentValuesMatch(
    [payload.session_id, identity.session?.id],
    desktopSession,
  )
}
