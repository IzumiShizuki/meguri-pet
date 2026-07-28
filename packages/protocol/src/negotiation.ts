import type {
  ClientCapabilities,
  ClientHello,
  ClientPermissions,
  HelloResponse,
  ProtocolVersion,
} from './dto.ts'

export const ADAPTER_PROTOCOL_MAJOR = 1
export const DEFAULT_PROTOCOL_VERSION: ProtocolVersion = '1.0'

export class ProtocolCompatibilityError extends Error {
  readonly code: 'UNSUPPORTED_PROTOCOL_MAJOR' | 'UNSUPPORTED_REQUIRED_EXTENSION'

  constructor(
    code: 'UNSUPPORTED_PROTOCOL_MAJOR' | 'UNSUPPORTED_REQUIRED_EXTENSION',
    message: string,
  ) {
    super(message)
    this.code = code
  }
}

export function assertCompatibleProtocolVersion(version: string): asserts version is ProtocolVersion {
  const match = /^(\d+)\.(\d+)$/.exec(version)
  if (!match)
    throw new ProtocolCompatibilityError('UNSUPPORTED_PROTOCOL_MAJOR', `invalid protocol version: ${version}`)
  if (Number(match[1]) !== ADAPTER_PROTOCOL_MAJOR) {
    throw new ProtocolCompatibilityError(
      'UNSUPPORTED_PROTOCOL_MAJOR',
      `protocol major ${match[1]} is incompatible with URL major v${ADAPTER_PROTOCOL_MAJOR}`,
    )
  }
}

export function selectProtocolVersion(
  offered: readonly string[],
  supported: readonly ProtocolVersion[] = [DEFAULT_PROTOCOL_VERSION],
): ProtocolVersion {
  const compatibleOffered = offered.filter((version) => {
    try {
      assertCompatibleProtocolVersion(version)
      return true
    }
    catch {
      return false
    }
  })
  const selected = [...supported]
    .filter(version => compatibleOffered.includes(version))
    .sort(compareVersions)
    .at(-1)
  if (!selected) {
    throw new ProtocolCompatibilityError(
      'UNSUPPORTED_PROTOCOL_MAJOR',
      `no compatible protocol version; offered=${offered.join(',')}`,
    )
  }
  return selected
}

export function assertRequiredExtensions(
  required: readonly string[] | undefined,
  supported: readonly string[],
): void {
  const missing = (required ?? []).filter(extension => !supported.includes(extension)).sort()
  if (missing.length > 0) {
    throw new ProtocolCompatibilityError(
      'UNSUPPORTED_REQUIRED_EXTENSION',
      `unsupported required extension(s): ${missing.join(', ')}`,
    )
  }
}

export function negotiateHello(
  hello: ClientHello,
  server: {
    versions: ProtocolVersion[]
    capabilities: ClientCapabilities
    capabilitiesRevision: string
    grantedPermissions: ClientPermissions
    extensions?: string[]
  },
): HelloResponse {
  const supportedExtensions = [...(server.extensions ?? [])].sort()
  assertRequiredExtensions(hello.required_extensions, supportedExtensions)
  return {
    selected_protocol_version: selectProtocolVersion(hello.protocol_versions, server.versions),
    server_capabilities_revision: server.capabilitiesRevision,
    server_capabilities: { ...server.capabilities },
    effective_capabilities: intersectCapabilities(hello.capabilities, server.capabilities),
    // Client permission claims are requests only; server grants are authoritative.
    granted_permissions: intersectPermissions(hello.permissions, server.grantedPermissions),
    supported_extensions: supportedExtensions,
  }
}

export function intersectCapabilities(
  client: ClientCapabilities,
  server: ClientCapabilities,
): ClientCapabilities {
  const keys = [...new Set([...Object.keys(client), ...Object.keys(server)])].sort()
  return Object.fromEntries(keys.map(key => [key, client[key] === true && server[key] === true])) as ClientCapabilities
}

export function intersectPermissions(
  requested: ClientPermissions,
  serverGranted: ClientPermissions,
): ClientPermissions {
  return {
    screen_read: requested.screen_read && serverGranted.screen_read,
    microphone: requested.microphone && serverGranted.microphone,
    audio_playback: requested.audio_playback && serverGranted.audio_playback,
    notifications: requested.notifications && serverGranted.notifications,
    formal_memory_write: requested.formal_memory_write && serverGranted.formal_memory_write,
  }
}

function compareVersions(left: string, right: string): number {
  const [, leftMinor] = left.split('.').map(Number)
  const [, rightMinor] = right.split('.').map(Number)
  return leftMinor - rightMinor
}
