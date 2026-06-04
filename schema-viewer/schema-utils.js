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

export function buildChildren(schema, parentPath) {
  const children = []
  const required = new Set(schema.required || [])

  if (schema.properties) {
    for (const [k, v] of Object.entries(schema.properties)) {
      const node = buildNode(k, v, [...parentPath, k], required)
      if (node) children.push(node)
    }
  }

  if (schema.additionalProperties && typeof schema.additionalProperties === 'object') {
    const node = buildNode('[value]', schema.additionalProperties, [...parentPath, '[value]'])
    if (node) children.push(node)
  }

  const unionKey = schema.oneOf ? 'oneOf' : schema.anyOf ? 'anyOf' : null
  if (unionKey) {
    schema[unionKey].forEach((branch, i) => {
      const label = branch.title || `${unionKey}[${i}]`
      const node = buildNode(label, branch, [...parentPath, label])
      if (node) children.push(node)
    })
  }

  if (schema.type === 'array' && schema.items) {
    const node = buildNode('items', schema.items, [...parentPath, 'items'])
    if (node) children.push(node)
  }

  return children
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
export function computeSchemaDiff(oldSchema, newSchema, differ) {
  function flatten(schema) {
    const map = {}
    const visited = new WeakSet()

    function walk(node, pathParts) {
      if (!node || typeof node !== 'object' || visited.has(node)) return
      visited.add(node)

      const req = new Set(node.required || [])

      if (node.properties) {
        for (const [k, v] of Object.entries(node.properties)) {
          const path = [...pathParts, k]
          const snap = { type: getTypeLabel(v), required: req.has(k) }
          if (v.default !== undefined) snap.default = JSON.stringify(v.default)
          map[path.join('/')] = snap
          walk(v, path)
        }
      }

      if (node.additionalProperties && typeof node.additionalProperties === 'object') {
        const path = [...pathParts, '[value]']
        const v = node.additionalProperties
        const snap = { type: getTypeLabel(v), required: false }
        if (v.default !== undefined) snap.default = JSON.stringify(v.default)
        map[path.join('/')] = snap
        walk(v, path)
      }

      const unionKey = node.oneOf ? 'oneOf' : node.anyOf ? 'anyOf' : null
      if (unionKey) {
        node[unionKey].forEach((branch, i) => {
          const label = branch.title || `${unionKey}[${i}]`
          const path = [...pathParts, label]
          const snap = { type: getTypeLabel(branch), required: false }
          if (branch.default !== undefined) snap.default = JSON.stringify(branch.default)
          map[path.join('/')] = snap
          walk(branch, path)
        })
      }

      if (node.type === 'array' && node.items) {
        const path = [...pathParts, 'items']
        const v = node.items
        const snap = { type: getTypeLabel(v), required: false }
        if (v.default !== undefined) snap.default = JSON.stringify(v.default)
        map[path.join('/')] = snap
        walk(v, path)
      }
    }

    walk(schema, [])
    return map
  }

  const delta = differ.diff(flatten(oldSchema), flatten(newSchema))
  const added = [], removed = [], modified = []
  if (!delta) return { added, removed, modified }

  for (const [path, d] of Object.entries(delta)) {
    if (Array.isArray(d)) {
      if (d.length === 1) added.push(path)
      else if (d.length === 3 && d[1] === 0 && d[2] === 0) removed.push(path)
    } else if (d && typeof d === 'object' && !d._t) {
      const changes = []
      if (d.type) changes.push({ kind: 'type', from: d.type[0], to: d.type[1] })
      if (d.default) {
        if (d.default.length === 1)      changes.push({ kind: 'default', from: undefined,    to: d.default[0] })
        else if (d.default.length === 3) changes.push({ kind: 'default', from: d.default[0], to: undefined })
        else                             changes.push({ kind: 'default', from: d.default[0], to: d.default[1] })
      }
      if (d.required) changes.push({ kind: 'required', from: d.required[0], to: d.required[1] })
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
