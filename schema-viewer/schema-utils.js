// ── Tree builder ──────────────────────────────────────────────────────────────

export function buildNode(name, schema, pathArr, requiredSet = new Set()) {
  if (!schema || typeof schema !== 'object') return null
  return {
    name,
    pathArr,
    key: pathArr.join('/'),
    schema,
    required: requiredSet.has(name),
    children: buildChildren(schema, pathArr),
  }
}

function propertyChildren(schema, parentPath) {
  if (!schema.properties) return []
  const required = new Set(schema.required || [])
  return Object.entries(schema.properties)
    .map(([k, v]) => buildNode(k, v, [...parentPath, k], required))
    .filter(Boolean)
}

function additionalPropertyChildren(schema, parentPath) {
  if (!isObjectSchema(schema.additionalProperties)) return []
  const node = buildNode('[value]', schema.additionalProperties, [...parentPath, '[value]'])
  return node ? [node] : []
}

function unionChildren(schema, parentPath) {
  const unionKey = unionKeyOf(schema)
  if (!unionKey) return []
  return schema[unionKey]
    .map((branch, i) => {
      const label = branch.title || `${unionKey}[${i}]`
      return buildNode(label, branch, [...parentPath, label])
    })
    .filter(Boolean)
}

function itemChildren(schema, parentPath) {
  if (schema.type !== 'array' || !schema.items) return []
  const node = buildNode('items', schema.items, [...parentPath, 'items'])
  return node ? [node] : []
}

export function buildChildren(schema, parentPath) {
  return [
    ...propertyChildren(schema, parentPath),
    ...additionalPropertyChildren(schema, parentPath),
    ...unionChildren(schema, parentPath),
    ...itemChildren(schema, parentPath),
  ]
}

export function nodeMatchesSearch(node, term) {
  if (node.name.toLowerCase().includes(term)) return true
  return node.children.some(c => nodeMatchesSearch(c, term))
}

// ── Type helpers ──────────────────────────────────────────────────────────────

export function getTypeLabel(schema) {
  if (schema.oneOf || schema.anyOf) return 'union'
  if (schema.allOf) return 'allOf'
  if (Array.isArray(schema.type)) return schema.type.join(' | ')
  return schema.type || 'any'
}

// Returns the union keyword used by a schema ('oneOf' / 'anyOf'), or null.
export function unionKeyOf(schema) {
  if (schema.oneOf) return 'oneOf'
  if (schema.anyOf) return 'anyOf'
  return null
}

// True when a value is a schema object (as opposed to a boolean like
// `additionalProperties: true`).
export function isObjectSchema(s) {
  return !!s && typeof s === 'object'
}

export function typeBadgeClass(t) {
  const map = {
    string: 'type-string', object: 'type-object', boolean: 'type-boolean',
    array: 'type-array', integer: 'type-integer', number: 'type-number', union: 'type-union',
  }
  return `badge type-badge ${map[t] || ''}`
}

export function variantTitle(branch, i, unionKey) {
  if (branch.title) return branch.title
  const enumVal = branch.properties?.type?.enum?.[0]
  if (enumVal) return enumVal
  return `${unionKey}[${i}]`
}

export function variantDesc(branch) {
  const props = Object.keys(branch.properties || {}).filter(k => k !== 'type')
  return props.length ? `Requires ${props.join(', ')}.` : 'No additional properties required.'
}

// ── Schema diff ───────────────────────────────────────────────────────────────

// Flattens the resolved schema tree into a map of path → scalar snapshot, then
// diffs the two maps to detect additions, removals, and scalar changes at any
// depth. Paths mirror the tree (e.g. "source/cluster/auth/type").
//
// Expects resolved (dereferenced) schemas so that $ref nodes are fully expanded.
// Changes to a shared definition will therefore appear once per field that uses
// it, which is accurate — all those fields are affected.
//
// `differ` is a jsondiffpatch instance, injected for testability.

// Builds the path → scalar snapshot recorded for a single schema node.
function snapshotOf(schema, required) {
  const snap = { type: getTypeLabel(schema), required }
  if (schema.default !== undefined) snap.default = JSON.stringify(schema.default)
  return snap
}

// Yields [label, childSchema, isRequired] for every child the tree walks into,
// covering properties, the additionalProperties value, union branches, and array
// items — mirroring buildChildren so diff paths match the rendered tree.
function* schemaChildren(node) {
  if (node.properties) {
    const req = new Set(node.required || [])
    for (const [k, v] of Object.entries(node.properties)) yield [k, v, req.has(k)]
  }
  if (isObjectSchema(node.additionalProperties)) {
    yield ['[value]', node.additionalProperties, false]
  }
  const unionKey = unionKeyOf(node)
  if (unionKey) {
    for (const [i, branch] of node[unionKey].entries()) {
      yield [branch.title || `${unionKey}[${i}]`, branch, false]
    }
  }
  if (node.type === 'array' && node.items) {
    yield ['items', node.items, false]
  }
}

// Flattens a resolved schema tree into a map of path → scalar snapshot.
function flattenSchema(schema) {
  const map = {}
  const visited = new WeakSet()

  function walk(node, pathParts) {
    if (!isObjectSchema(node) || visited.has(node)) return
    visited.add(node)
    for (const [label, child, required] of schemaChildren(node)) {
      const path = [...pathParts, label]
      map[path.join('/')] = snapshotOf(child, required)
      walk(child, path)
    }
  }

  walk(schema, [])
  return map
}

// Interprets a jsondiffpatch default-value delta ([added] / [old,0,0] / [old,new]).
function defaultChange(d) {
  if (d.default.length === 1) return { kind: 'default', from: undefined, to: d.default[0] }
  if (d.default.length === 3) return { kind: 'default', from: d.default[0], to: undefined }
  return { kind: 'default', from: d.default[0], to: d.default[1] }
}

// Collects the scalar field changes (type / default / required) from a delta object.
function scalarChanges(d) {
  const changes = []
  if (d.type) changes.push({ kind: 'type', from: d.type[0], to: d.type[1] })
  if (d.default) changes.push(defaultChange(d))
  if (d.required) changes.push({ kind: 'required', from: d.required[0], to: d.required[1] })
  return changes
}

export function computeSchemaDiff(oldSchema, newSchema, differ) {
  const delta = differ.diff(flattenSchema(oldSchema), flattenSchema(newSchema))
  const added = [], removed = [], modified = []
  if (!delta) return { added, removed, modified }

  for (const [path, d] of Object.entries(delta)) {
    if (Array.isArray(d)) {
      if (d.length === 1) added.push(path)
      else if (d.length === 3 && d[1] === 0 && d[2] === 0) removed.push(path)
    } else if (d && typeof d === 'object' && !d._t) {
      const changes = scalarChanges(d)
      if (changes.length) modified.push({ path, changes })
    }
  }

  return { added, removed, modified }
}

// ── Composite field helper ────────────────────────────────────────────────────

export function isComposite(schema) {
  return !!(
    schema.properties ||
    schema.oneOf || schema.anyOf || schema.allOf ||
    (schema.additionalProperties && typeof schema.additionalProperties === 'object') ||
    (schema.type === 'array' && schema.items)
  )
}

// ── Expert field helpers ──────────────────────────────────────────────────────

export function isExpert(desc) {
  return typeof desc === 'string' && desc.startsWith('[Expert]')
}

export function stripExpert(desc) {
  return desc ? desc.replace(/^\[Expert\]\s*/, '') : desc
}
