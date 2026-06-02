import $RefParser from 'https://esm.sh/@apidevtools/json-schema-ref-parser@15'

// ── Tree builder ──────────────────────────────────────────────────────────────

function buildNode(name, schema, pathArr, requiredSet = new Set()) {
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

function buildChildren(schema, parentPath) {
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

function nodeMatchesSearch(node, term) {
  if (node.name.toLowerCase().includes(term)) return true
  return node.children.some(c => nodeMatchesSearch(c, term))
}

// ── Type helpers ──────────────────────────────────────────────────────────────

function getTypeLabel(schema) {
  if (schema.oneOf || schema.anyOf) return 'union'
  if (schema.allOf) return 'allOf'
  if (Array.isArray(schema.type)) return schema.type.join(' | ')
  return schema.type || 'any'
}

function typeBadgeClass(t) {
  const map = {
    string: 'type-string', object: 'type-object', boolean: 'type-boolean',
    array: 'type-array', integer: 'type-integer', number: 'type-number', union: 'type-union',
  }
  return `badge type-badge ${map[t] || ''}`
}

function variantTitle(branch, i, unionKey) {
  if (branch.title) return branch.title
  const enumVal = branch.properties?.type?.enum?.[0]
  if (enumVal) return enumVal
  return `${unionKey}[${i}]`
}

function variantDesc(branch) {
  const props = Object.keys(branch.properties || {}).filter(k => k !== 'type')
  return props.length ? `Requires ${props.join(', ')}.` : 'No additional properties required.'
}

// ── Expert field helpers ──────────────────────────────────────────────────────

function isExpert(desc) {
  return typeof desc === 'string' && desc.startsWith('[Expert]')
}

function stripExpert(desc) {
  return desc ? desc.replace(/^\[Expert\]\s*/, '') : desc
}

// ── DOM helpers ───────────────────────────────────────────────────────────────

function el(tag, cls, text) {
  const e = document.createElement(tag)
  if (cls) e.className = cls
  if (text !== undefined) e.textContent = text
  return e
}

function badge(text, cls) {
  return el('span', `badge ${cls}`, text)
}

function setStatus(text, cls) {
  const s = document.getElementById('status')
  s.textContent = text
  s.className = `badge-pill ${cls}`
}

// ── State ─────────────────────────────────────────────────────────────────────

const expandedKeys = new Set()
let selectedKey = null
let rootNodes = []

function collectAllKeys(nodes, acc = new Set()) {
  for (const node of nodes) {
    if (node.children.length > 0) {
      acc.add(node.key)
      collectAllKeys(node.children, acc)
    }
  }
  return acc
}

// ── Sidebar tree ──────────────────────────────────────────────────────────────

function buildTreeFragment(nodes, depth, term) {
  const frag = document.createDocumentFragment()
  for (const node of nodes) {
    if (term && !nodeMatchesSearch(node, term)) continue
    frag.appendChild(buildTreeNodeEl(node, depth, term))
  }
  return frag
}

function buildTreeNodeEl(node, depth, term) {
  const hasChildren = node.children.length > 0
  const isExpanded = expandedKeys.has(node.key)
  const isSelected = selectedKey === node.key

  const wrapper = document.createElement('div')

  const row = el('div', `tree-item${isSelected ? ' selected' : ''}`)
  row.style.paddingLeft = `${12 + depth * 14}px`

  const toggle = el('span', 'tree-toggle', hasChildren ? (isExpanded ? '▾' : '▸') : '')
  const nameEl = el('span', 'tree-name', node.name)
  row.appendChild(toggle)
  row.appendChild(nameEl)

  const childWrap = document.createElement('div')
  if (hasChildren && isExpanded) {
    childWrap.appendChild(buildTreeFragment(node.children, depth + 1, term))
  } else {
    childWrap.style.display = 'none'
  }

  row.addEventListener('click', e => {
    e.stopPropagation()

    if (hasChildren) {
      if (expandedKeys.has(node.key)) {
        expandedKeys.delete(node.key)
        toggle.textContent = '▸'
        childWrap.style.display = 'none'
        childWrap.innerHTML = ''
      } else {
        expandedKeys.add(node.key)
        toggle.textContent = '▾'
        childWrap.style.display = ''
        childWrap.appendChild(buildTreeFragment(node.children, depth + 1, term))
      }
    }

    document.querySelectorAll('.tree-item.selected').forEach(el => el.classList.remove('selected'))
    row.classList.add('selected')
    selectedKey = node.key
    renderCard(node)
  })

  wrapper.appendChild(row)
  wrapper.appendChild(childWrap)
  return wrapper
}

function renderTree(term = '') {
  const container = document.getElementById('tree')
  container.innerHTML = ''
  container.appendChild(buildTreeFragment(rootNodes, 0, term))
}

// ── Field card ────────────────────────────────────────────────────────────────

function renderCard(node) {
  const main = document.getElementById('main')
  main.innerHTML = ''
  main.appendChild(buildCardEl(node))
}

function buildCardEl(node) {
  if (!node) return el('div', 'empty-state', 'Select a field from the tree to see its details.')

  const { name, schema, required, pathArr } = node
  const parentPath = pathArr.slice(0, -1)
  const t = getTypeLabel(schema)
  const unionKey = schema.oneOf ? 'oneOf' : schema.anyOf ? 'anyOf' : null
  const branches = unionKey ? schema[unionKey] : []

  const card = el('div', 'field-card')

  // Breadcrumb
  if (parentPath.length > 0) {
    card.appendChild(el('div', 'breadcrumb', parentPath.join(' › ')))
  }

  // Title + expert badge
  const titleRow = el('div', 'field-title-row')
  titleRow.appendChild(el('span', 'field-title', name))
  if (isExpert(schema.description)) {
    titleRow.appendChild(badge('Expert', 'badge-expert'))
  }
  card.appendChild(titleRow)

  // Description (strip [Expert] prefix if present)
  if (schema.description) {
    card.appendChild(el('p', 'field-desc', stripExpert(schema.description)))
  }

  // Meta row
  const meta = el('div', 'meta-row')
  meta.appendChild(metaItem('Data type', badge(t, typeBadgeClass(t))))
  meta.appendChild(metaItem('Requirement',
    badge(required ? 'Required' : 'Optional', required ? 'badge-required' : 'badge-optional')))
  if (schema.default !== undefined) {
    meta.appendChild(metaItem('Default', badge(JSON.stringify(schema.default), 'badge-default')))
  }
  card.appendChild(meta)

  // Union variants
  if (unionKey && branches.length > 0) {
    const section = el('div', 'section')
    section.appendChild(el('div', 'section-title', 'Allowed enums / structures'))
    const list = el('div', 'union-list')
    branches.forEach((branch, i) => {
      const row = el('div', 'union-row')
      row.appendChild(el('span', 'union-value', `"${variantTitle(branch, i, unionKey)}"`))
      row.appendChild(el('span', 'union-desc', branch.description || variantDesc(branch)))
      list.appendChild(row)
    })
    section.appendChild(list)
    card.appendChild(section)
  }

  // Properties — grouped by variant for union types, flat otherwise
  const branchesWithProps = branches.filter(b => b.properties)
  if (unionKey && branchesWithProps.length > 0) {
    const section = el('div', 'section')
    section.appendChild(el('div', 'section-title', 'Properties'))
    branchesWithProps.forEach((branch, i) => {
      const block = el('div', 'variant-block')
      const lbl = el('div', 'variant-label', 'When ')
      lbl.appendChild(el('code', '', `"${variantTitle(branch, i, unionKey)}"`))
      block.appendChild(lbl)
      block.appendChild(buildPropsTable(branch))
      section.appendChild(block)
    })
    card.appendChild(section)
  } else if (!unionKey && schema.properties) {
    const section = el('div', 'section')
    section.appendChild(el('div', 'section-title', 'Properties'))
    section.appendChild(buildPropsTable(schema))
    card.appendChild(section)
  }

  // Map value schema — show union branches if the value type has them
  if (!schema.properties && schema.additionalProperties && typeof schema.additionalProperties === 'object') {
    const ap = schema.additionalProperties
    const apUnionKey = ap.oneOf ? 'oneOf' : ap.anyOf ? 'anyOf' : null
    if (apUnionKey) {
      const section = el('div', 'section')
      section.appendChild(el('div', 'section-title', 'Map value schema — allowed structures'))
      const list = el('div', 'union-list')
      ap[apUnionKey].forEach((branch, i) => {
        const row = el('div', 'union-row')
        row.appendChild(el('span', 'union-value', `"${variantTitle(branch, i, apUnionKey)}"`))
        row.appendChild(el('span', 'union-desc', branch.description || variantDesc(branch)))
        list.appendChild(row)
      })
      section.appendChild(list)
      card.appendChild(section)
    }
  }

  return card
}

function metaItem(labelText, valueEl) {
  const item = el('div', 'meta-item')
  item.appendChild(el('span', 'meta-label', labelText))
  item.appendChild(valueEl)
  return item
}

function buildPropsTable(schema) {
  const required = new Set(schema.required || [])
  const table = el('table', 'props-table')
  const tbody = el('tbody')

  for (const [key, val] of Object.entries(schema.properties || {})) {
    const t = getTypeLabel(val)
    const isReq = required.has(key)
    const tr = document.createElement('tr')

    const nameTd = el('td', 'prop-name-cell')
    nameTd.appendChild(el('span', 'prop-name', key))
    nameTd.appendChild(el('span', 'prop-type', t))
    tr.appendChild(nameTd)

    const descTd = el('td', 'prop-desc-cell')
    if (val.description) descTd.appendChild(el('div', '', stripExpert(val.description)))
    const propBadges = el('div', 'prop-badges')
    if (isExpert(val.description)) propBadges.appendChild(badge('Expert', 'badge-expert'))
    propBadges.appendChild(badge(isReq ? 'Required' : 'Optional', isReq ? 'badge-required' : 'badge-optional'))
    if (val.default !== undefined) {
      propBadges.appendChild(badge(`Default: ${JSON.stringify(val.default)}`, 'badge-default'))
    }
    descTd.appendChild(propBadges)
    tr.appendChild(descTd)

    tbody.appendChild(tr)
  }

  table.appendChild(tbody)
  return table
}

// ── Init ──────────────────────────────────────────────────────────────────────

function rebuildFromSchema(schema) {
  const required = new Set(schema.required || [])
  rootNodes = Object.entries(schema.properties || {})
    .map(([k, v]) => buildNode(k, v, [k], required))
    .filter(Boolean)
  selectedKey = null
  expandedKeys.clear()
  renderTree()
  renderCard(null)
}

async function init() {
  let rawSchema

  try {
    rawSchema = await fetch('./workflowMigration.schema.json').then(r => r.json())
  } catch (e) {
    setStatus('Failed to load schema: ' + e.message, 'warn')
    return
  }

  let resolvedSchema = rawSchema
  let useRaw = false

  document.getElementById('toggle-mode').addEventListener('click', () => {
    useRaw = !useRaw
    document.getElementById('toggle-mode').textContent =
      `Input: ${useRaw ? 'raw' : 'resolved'} (click to toggle)`
    rebuildFromSchema(useRaw ? rawSchema : resolvedSchema)
  })

  document.getElementById('search').addEventListener('input', e => {
    renderTree(e.target.value.toLowerCase())
  })

  document.getElementById('expand-all').addEventListener('click', e => {
    e.preventDefault()
    collectAllKeys(rootNodes).forEach(k => expandedKeys.add(k))
    renderTree(document.getElementById('search').value.toLowerCase())
  })

  document.getElementById('collapse-all').addEventListener('click', e => {
    e.preventDefault()
    expandedKeys.clear()
    renderTree(document.getElementById('search').value.toLowerCase())
  })

  try {
    resolvedSchema = await $RefParser.dereference(rawSchema)
    console.log('Resolved schema:', resolvedSchema)
    setStatus('$refs resolved OK', 'ok')
  } catch (e) {
    setStatus('Resolution error: ' + e.message + ' — showing raw schema', 'warn')
  }

  rebuildFromSchema(resolvedSchema)
}

init()
