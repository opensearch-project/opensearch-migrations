import { describe, it, expect, beforeAll } from 'vitest'
import { create as createDiffer } from 'jsondiffpatch'
import {
  buildNode, buildChildren, nodeMatchesSearch,
  getTypeLabel, typeBadgeClass, variantTitle, variantDesc,
  isExpert, stripExpert, computeSchemaDiff,
} from './schema-utils.js'

let differ
beforeAll(() => { differ = createDiffer() })

// ── isExpert / stripExpert ────────────────────────────────────────────────────

describe('isExpert', () => {
  it('returns true for [Expert]-prefixed description', () => {
    expect(isExpert('[Expert] Advanced tuning option')).toBe(true)
  })
  it('returns false for a normal description', () => {
    expect(isExpert('The target cluster hostname')).toBe(false)
  })
  it('returns false for an empty string', () => {
    expect(isExpert('')).toBe(false)
  })
  it('returns false for null and undefined', () => {
    expect(isExpert(null)).toBe(false)
    expect(isExpert(undefined)).toBe(false)
  })
})

describe('stripExpert', () => {
  it('removes the [Expert] prefix and leading space', () => {
    expect(stripExpert('[Expert] Advanced tuning option')).toBe('Advanced tuning option')
  })
  it('removes [Expert] with no trailing space', () => {
    expect(stripExpert('[Expert]No space')).toBe('No space')
  })
  it('leaves non-expert descriptions unchanged', () => {
    expect(stripExpert('Normal description')).toBe('Normal description')
  })
  it('returns null/undefined unchanged', () => {
    expect(stripExpert(null)).toBe(null)
    expect(stripExpert(undefined)).toBe(undefined)
  })
})

// ── getTypeLabel ──────────────────────────────────────────────────────────────

describe('getTypeLabel', () => {
  it('returns "union" for oneOf', () => {
    expect(getTypeLabel({ oneOf: [] })).toBe('union')
  })
  it('returns "union" for anyOf', () => {
    expect(getTypeLabel({ anyOf: [] })).toBe('union')
  })
  it('returns "allOf" for allOf', () => {
    expect(getTypeLabel({ allOf: [] })).toBe('allOf')
  })
  it('joins array types with " | "', () => {
    expect(getTypeLabel({ type: ['string', 'null'] })).toBe('string | null')
  })
  it('returns the scalar type string', () => {
    expect(getTypeLabel({ type: 'object' })).toBe('object')
  })
  it('returns "any" when no type is present', () => {
    expect(getTypeLabel({})).toBe('any')
  })
  it('prefers oneOf over a type field', () => {
    expect(getTypeLabel({ type: 'object', oneOf: [] })).toBe('union')
  })
})

// ── typeBadgeClass ────────────────────────────────────────────────────────────

describe('typeBadgeClass', () => {
  it('includes the type-specific modifier class for known types', () => {
    expect(typeBadgeClass('string')).toContain('type-string')
    expect(typeBadgeClass('object')).toContain('type-object')
    expect(typeBadgeClass('boolean')).toContain('type-boolean')
    expect(typeBadgeClass('array')).toContain('type-array')
    expect(typeBadgeClass('integer')).toContain('type-integer')
    expect(typeBadgeClass('number')).toContain('type-number')
    expect(typeBadgeClass('union')).toContain('type-union')
  })
  it('always includes the base badge classes', () => {
    expect(typeBadgeClass('string')).toContain('badge')
    expect(typeBadgeClass('string')).toContain('type-badge')
  })
  it('returns only base classes for unknown types', () => {
    const cls = typeBadgeClass('unknown')
    expect(cls).toContain('badge')
    expect(cls).toContain('type-badge')
    expect(cls.trim()).toBe('badge type-badge')
  })
})

// ── variantTitle ──────────────────────────────────────────────────────────────

describe('variantTitle', () => {
  it('uses branch.title when present', () => {
    expect(variantTitle({ title: 'Basic auth' }, 0, 'oneOf')).toBe('Basic auth')
  })
  it('falls back to properties.type.enum[0]', () => {
    const branch = { properties: { type: { enum: ['basic'] } } }
    expect(variantTitle(branch, 0, 'oneOf')).toBe('basic')
  })
  it('falls back to unionKey[i] when neither title nor enum exists', () => {
    expect(variantTitle({}, 2, 'anyOf')).toBe('anyOf[2]')
  })
  it('prefers title over enum', () => {
    const branch = { title: 'My title', properties: { type: { enum: ['enum-val'] } } }
    expect(variantTitle(branch, 0, 'oneOf')).toBe('My title')
  })
})

// ── variantDesc ───────────────────────────────────────────────────────────────

describe('variantDesc', () => {
  it('lists property names excluding "type"', () => {
    const branch = { properties: { type: {}, host: {}, port: {} } }
    expect(variantDesc(branch)).toBe('Requires host, port.')
  })
  it('returns the no-properties message when only "type" is present', () => {
    const branch = { properties: { type: {} } }
    expect(variantDesc(branch)).toBe('No additional properties required.')
  })
  it('returns the no-properties message when properties is absent', () => {
    expect(variantDesc({})).toBe('No additional properties required.')
  })
})

// ── buildNode ─────────────────────────────────────────────────────────────────

describe('buildNode', () => {
  it('returns null for a null schema', () => {
    expect(buildNode('field', null, ['field'])).toBeNull()
  })
  it('returns null for a non-object schema', () => {
    expect(buildNode('field', 'string', ['field'])).toBeNull()
  })
  it('builds a node with the correct key (joined path)', () => {
    const node = buildNode('host', { type: 'string' }, ['source', 'host'])
    expect(node.key).toBe('source/host')
  })
  it('marks the field as required when name is in requiredSet', () => {
    const node = buildNode('host', { type: 'string' }, ['host'], new Set(['host']))
    expect(node.required).toBe(true)
  })
  it('marks the field as optional when name is not in requiredSet', () => {
    const node = buildNode('host', { type: 'string' }, ['host'], new Set(['port']))
    expect(node.required).toBe(false)
  })
  it('defaults to optional when requiredSet is omitted', () => {
    const node = buildNode('host', { type: 'string' }, ['host'])
    expect(node.required).toBe(false)
  })
  it('populates children from schema.properties', () => {
    const schema = { properties: { a: { type: 'string' }, b: { type: 'integer' } } }
    const node = buildNode('root', schema, ['root'])
    expect(node.children).toHaveLength(2)
    expect(node.children.map(c => c.name)).toEqual(['a', 'b'])
  })
})

// ── buildChildren ─────────────────────────────────────────────────────────────

describe('buildChildren', () => {
  it('returns an empty array for a schema with no children', () => {
    expect(buildChildren({ type: 'string' }, ['field'])).toEqual([])
  })

  it('builds one child per property', () => {
    const schema = { properties: { x: { type: 'string' }, y: { type: 'boolean' } } }
    const children = buildChildren(schema, ['root'])
    expect(children).toHaveLength(2)
    expect(children[0].name).toBe('x')
    expect(children[1].name).toBe('y')
  })

  it('marks required properties correctly', () => {
    const schema = {
      required: ['x'],
      properties: { x: { type: 'string' }, y: { type: 'boolean' } },
    }
    const children = buildChildren(schema, ['root'])
    expect(children.find(c => c.name === 'x').required).toBe(true)
    expect(children.find(c => c.name === 'y').required).toBe(false)
  })

  it('adds a [value] child for additionalProperties', () => {
    const schema = { additionalProperties: { type: 'string' } }
    const children = buildChildren(schema, ['root'])
    expect(children).toHaveLength(1)
    expect(children[0].name).toBe('[value]')
  })

  it('skips additionalProperties: true (not an object)', () => {
    const schema = { additionalProperties: true }
    expect(buildChildren(schema, ['root'])).toEqual([])
  })

  it('builds children for each oneOf branch', () => {
    const schema = {
      oneOf: [
        { title: 'Option A', type: 'object' },
        { title: 'Option B', type: 'object' },
      ],
    }
    const children = buildChildren(schema, ['root'])
    expect(children).toHaveLength(2)
    expect(children[0].name).toBe('Option A')
    expect(children[1].name).toBe('Option B')
  })

  it('builds children for each anyOf branch', () => {
    const schema = {
      anyOf: [{ type: 'string' }, { type: 'integer' }],
    }
    const children = buildChildren(schema, ['root'])
    expect(children).toHaveLength(2)
    expect(children[0].name).toBe('anyOf[0]')
    expect(children[1].name).toBe('anyOf[1]')
  })

  it('uses branch title as the child name for oneOf', () => {
    const schema = { oneOf: [{ title: 'Named branch', type: 'object' }] }
    const children = buildChildren(schema, ['root'])
    expect(children[0].name).toBe('Named branch')
  })

  it('adds an items child for array schemas', () => {
    const schema = { type: 'array', items: { type: 'string' } }
    const children = buildChildren(schema, ['root'])
    expect(children).toHaveLength(1)
    expect(children[0].name).toBe('items')
  })

  it('does not add items child when type is not array', () => {
    const schema = { type: 'object', items: { type: 'string' } }
    expect(buildChildren(schema, ['root'])).toEqual([])
  })

  it('builds correct paths for nested children', () => {
    const schema = { properties: { child: { type: 'string' } } }
    const children = buildChildren(schema, ['parent'])
    expect(children[0].key).toBe('parent/child')
    expect(children[0].pathArr).toEqual(['parent', 'child'])
  })
})

// ── nodeMatchesSearch ─────────────────────────────────────────────────────────

describe('nodeMatchesSearch', () => {
  const leaf = name => ({ name, children: [] })
  const parent = (name, childNames) => ({ name, children: childNames.map(leaf) })

  it('matches when the node name contains the term', () => {
    expect(nodeMatchesSearch(leaf('hostname'), 'host')).toBe(true)
  })
  it('is case-insensitive', () => {
    expect(nodeMatchesSearch(leaf('HostName'), 'hostname')).toBe(true)
  })
  it('returns false when neither node nor children match', () => {
    expect(nodeMatchesSearch(parent('cluster', ['host', 'port']), 'auth')).toBe(false)
  })
  it('matches via a child name even if the parent does not match', () => {
    expect(nodeMatchesSearch(parent('cluster', ['authentication', 'host']), 'auth')).toBe(true)
  })
  it('matches on exact full name', () => {
    expect(nodeMatchesSearch(leaf('type'), 'type')).toBe(true)
  })
})

// ── computeSchemaDiff ─────────────────────────────────────────────────────────

describe('computeSchemaDiff', () => {
  const diff = (oldSchema, newSchema) => computeSchemaDiff(oldSchema, newSchema, differ)

  it('returns empty result when both schemas are identical', () => {
    const schema = { properties: { host: { type: 'string' } } }
    expect(diff(schema, schema)).toEqual({ added: [], removed: [], modified: [] })
  })

  it('returns empty result when both schemas have no properties', () => {
    expect(diff({}, {})).toEqual({ added: [], removed: [], modified: [] })
  })

  it('detects an added top-level field', () => {
    const old = { properties: { host: { type: 'string' } } }
    const next = { properties: { host: { type: 'string' }, port: { type: 'integer' } } }
    const result = diff(old, next)
    expect(result.added).toContain('port')
    expect(result.removed).toHaveLength(0)
    expect(result.modified).toHaveLength(0)
  })

  it('detects a removed top-level field', () => {
    const old  = { properties: { host: { type: 'string' }, legacy: { type: 'string' } } }
    const next = { properties: { host: { type: 'string' } } }
    const result = diff(old, next)
    expect(result.removed).toContain('legacy')
    expect(result.added).toHaveLength(0)
    expect(result.modified).toHaveLength(0)
  })

  it('detects a type change on an existing field', () => {
    const old  = { properties: { timeout: { type: 'string' } } }
    const next = { properties: { timeout: { type: 'integer' } } }
    const result = diff(old, next)
    expect(result.modified).toHaveLength(1)
    expect(result.modified[0].path).toBe('timeout')
    const typeChange = result.modified[0].changes.find(c => c.kind === 'type')
    expect(typeChange).toMatchObject({ from: 'string', to: 'integer' })
  })

  it('detects a default being added', () => {
    const old  = { properties: { enabled: { type: 'boolean' } } }
    const next = { properties: { enabled: { type: 'boolean', default: false } } }
    const result = diff(old, next)
    expect(result.modified).toHaveLength(1)
    const defaultChange = result.modified[0].changes.find(c => c.kind === 'default')
    expect(defaultChange.from).toBeUndefined()
    expect(defaultChange.to).toBe('false')
  })

  it('detects a default being changed', () => {
    const old  = { properties: { replicas: { type: 'integer', default: 1 } } }
    const next = { properties: { replicas: { type: 'integer', default: 3 } } }
    const result = diff(old, next)
    const defaultChange = result.modified[0].changes.find(c => c.kind === 'default')
    expect(defaultChange).toMatchObject({ from: '1', to: '3' })
  })

  it('detects a default being removed', () => {
    const old  = { properties: { mode: { type: 'string', default: 'auto' } } }
    const next = { properties: { mode: { type: 'string' } } }
    const result = diff(old, next)
    const defaultChange = result.modified[0].changes.find(c => c.kind === 'default')
    expect(defaultChange.from).toBe('"auto"')
    expect(defaultChange.to).toBeUndefined()
  })

  it('detects a field becoming required', () => {
    const old  = { properties: { cert: { type: 'string' } } }
    const next = { required: ['cert'], properties: { cert: { type: 'string' } } }
    const result = diff(old, next)
    const reqChange = result.modified[0].changes.find(c => c.kind === 'required')
    expect(reqChange).toMatchObject({ from: false, to: true })
  })

  it('detects a field becoming optional', () => {
    const old  = { required: ['cert'], properties: { cert: { type: 'string' } } }
    const next = { properties: { cert: { type: 'string' } } }
    const result = diff(old, next)
    const reqChange = result.modified[0].changes.find(c => c.kind === 'required')
    expect(reqChange).toMatchObject({ from: true, to: false })
  })

  it('captures multiple scalar changes on the same field in one modified entry', () => {
    const old  = { required: ['x'], properties: { x: { type: 'string', default: 'a' } } }
    const next = { properties: { x: { type: 'integer', default: 0 } } }
    const result = diff(old, next)
    expect(result.modified).toHaveLength(1)
    const { changes } = result.modified[0]
    expect(changes.find(c => c.kind === 'type')).toBeTruthy()
    expect(changes.find(c => c.kind === 'default')).toBeTruthy()
    expect(changes.find(c => c.kind === 'required')).toBeTruthy()
  })

  it('handles simultaneous additions, removals, and modifications', () => {
    const old  = { properties: { a: { type: 'string' }, b: { type: 'integer' } } }
    const next = { properties: { b: { type: 'boolean' }, c: { type: 'object' } } }
    const result = diff(old, next)
    expect(result.added).toContain('c')
    expect(result.removed).toContain('a')
    expect(result.modified[0].path).toBe('b')
  })

  it('does not report a modification when only description changes', () => {
    const old  = { properties: { host: { type: 'string', description: 'old' } } }
    const next = { properties: { host: { type: 'string', description: 'new' } } }
    expect(diff(old, next)).toEqual({ added: [], removed: [], modified: [] })
  })

  it('serialises array defaults to strings to avoid array-diff noise', () => {
    const old  = { properties: { tags: { type: 'array', default: [] } } }
    const next = { properties: { tags: { type: 'array', default: ['a'] } } }
    const result = diff(old, next)
    const defaultChange = result.modified[0].changes.find(c => c.kind === 'default')
    expect(defaultChange).toMatchObject({ from: '[]', to: '["a"]' })
  })

  it('detects an added field nested inside an existing object', () => {
    const old = { properties: { source: { type: 'object', properties: { host: { type: 'string' } } } } }
    const next = { properties: { source: { type: 'object', properties: {
      host: { type: 'string' },
      port: { type: 'integer' },
    } } } }
    const result = diff(old, next)
    expect(result.added).toContain('source/port')
    expect(result.removed).toHaveLength(0)
  })

  it('detects a removed field at an arbitrary depth', () => {
    const old = { properties: { cluster: { type: 'object', properties: {
      auth: { type: 'object', properties: { token: { type: 'string' } } },
    } } } }
    const next = { properties: { cluster: { type: 'object', properties: {
      auth: { type: 'object', properties: {} },
    } } } }
    const result = diff(old, next)
    expect(result.removed).toContain('cluster/auth/token')
  })

  it('reports the full path for a modified nested field', () => {
    const old = { properties: { reindex: { type: 'object', properties: {
      maxRetries: { type: 'integer', default: 3 },
    } } } }
    const next = { properties: { reindex: { type: 'object', properties: {
      maxRetries: { type: 'integer', default: 5 },
    } } } }
    const result = diff(old, next)
    expect(result.modified).toHaveLength(1)
    expect(result.modified[0].path).toBe('reindex/maxRetries')
    expect(result.modified[0].changes[0]).toMatchObject({ kind: 'default', from: '3', to: '5' })
  })

  it('handles cycles in the resolved schema without looping', () => {
    const child = { type: 'object', properties: {} }
    const schema = { properties: { root: child } }
    child.properties.self = child  // circular reference
    expect(() => diff(schema, schema)).not.toThrow()
  })
})
