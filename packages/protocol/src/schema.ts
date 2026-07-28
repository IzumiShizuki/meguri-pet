import adapterProtocolSchema from '../../../contracts/adapter-protocol/v1/adapter-protocol.schema.json' with { type: 'json' }

import type {
  ClientHello,
  ErrorResponse,
  HelloResponse,
  SessionSnapshot,
  Turn,
  TurnCancelResponse,
  TurnCreateRequest,
  TurnCreateResponse,
} from './dto.ts'

type Schema = Record<string, unknown>

export class SchemaValidationError extends TypeError {
  readonly dto: string
  readonly path: string

  constructor(dto: string, path: string, message: string) {
    super(`${dto}${path}: ${message}`)
    this.dto = dto
    this.path = path
  }
}

export function validateDto(name: string, value: unknown): void {
  const definition = definitions()[name]
  if (!definition)
    throw new TypeError(`unknown adapter protocol DTO: ${name}`)
  validateNode(definition, value, name, '')
}

export function parseClientHello(value: unknown): ClientHello {
  validateDto('ClientHello', value)
  return value as ClientHello
}

export function parseHelloResponse(value: unknown): HelloResponse {
  validateDto('HelloResponse', value)
  return value as HelloResponse
}

export function parseTurnCreateRequest(value: unknown): TurnCreateRequest {
  validateDto('TurnCreateRequest', value)
  return value as TurnCreateRequest
}

export function parseTurn(value: unknown): Turn {
  validateDto('Turn', value)
  return value as Turn
}

export function parseTurnCreateResponse(value: unknown): TurnCreateResponse {
  validateDto('TurnCreateResponse', value)
  return value as TurnCreateResponse
}

export function parseTurnCancelResponse(value: unknown): TurnCancelResponse {
  validateDto('TurnCancelResponse', value)
  return value as TurnCancelResponse
}

export function parseSessionSnapshot(value: unknown): SessionSnapshot {
  validateDto('SessionSnapshot', value)
  return value as SessionSnapshot
}

export function parseErrorResponse(value: unknown): ErrorResponse {
  validateDto('ErrorResponse', value)
  return value as ErrorResponse
}

function definitions(): Record<string, Schema> {
  return adapterProtocolSchema.$defs as Record<string, Schema>
}

function validateNode(schema: Schema, value: unknown, dto: string, path: string): void {
  if (typeof schema.$ref === 'string') {
    const name = schema.$ref.split('/').at(-1)
    if (!name || !definitions()[name])
      fail(dto, path, `unresolved schema reference ${schema.$ref}`)
    validateNode(definitions()[name], value, dto, path)
    return
  }
  if (schema.const !== undefined && value !== schema.const)
    fail(dto, path, `must equal ${JSON.stringify(schema.const)}`)
  if (Array.isArray(schema.enum) && !schema.enum.includes(value))
    fail(dto, path, `must be one of ${schema.enum.join(', ')}`)
  if (schema.type === 'object') {
    if (!isRecord(value))
      fail(dto, path, 'must be an object')
    const required = Array.isArray(schema.required) ? schema.required as string[] : []
    for (const key of required) {
      if (!(key in value))
        fail(dto, `${path}/${key}`, 'is required')
    }
    const properties = isRecord(schema.properties) ? schema.properties as Record<string, Schema> : {}
    for (const [key, child] of Object.entries(properties)) {
      if (key in value)
        validateNode(child, value[key], dto, `${path}/${key}`)
    }
    if (schema.additionalProperties === false) {
      for (const key of Object.keys(value)) {
        if (!(key in properties))
          fail(dto, `${path}/${key}`, 'is not allowed')
      }
    }
    else if (isRecord(schema.additionalProperties)) {
      for (const [key, childValue] of Object.entries(value)) {
        if (!(key in properties))
          validateNode(schema.additionalProperties as Schema, childValue, dto, `${path}/${key}`)
      }
    }
  }
  else if (schema.type === 'array') {
    if (!Array.isArray(value))
      fail(dto, path, 'must be an array')
    if (typeof schema.minItems === 'number' && value.length < schema.minItems)
      fail(dto, path, `must contain at least ${schema.minItems} item(s)`)
    if (isRecord(schema.items))
      value.forEach((item, index) => validateNode(schema.items as Schema, item, dto, `${path}/${index}`))
  }
  else if (schema.type === 'string') {
    if (typeof value !== 'string')
      fail(dto, path, 'must be a string')
    if (typeof schema.minLength === 'number' && value.length < schema.minLength)
      fail(dto, path, `must have length >= ${schema.minLength}`)
    if (typeof schema.pattern === 'string' && !new RegExp(schema.pattern).test(value))
      fail(dto, path, `must match ${schema.pattern}`)
  }
  else if (schema.type === 'boolean' && typeof value !== 'boolean') {
    fail(dto, path, 'must be a boolean')
  }
  else if (schema.type === 'integer') {
    if (!Number.isSafeInteger(value))
      fail(dto, path, 'must be an integer')
    if (typeof schema.minimum === 'number' && Number(value) < schema.minimum)
      fail(dto, path, `must be >= ${schema.minimum}`)
  }
}

function fail(dto: string, path: string, message: string): never {
  throw new SchemaValidationError(dto, path || '/', message)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
