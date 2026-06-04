import $RefParser from 'https://esm.sh/@apidevtools/json-schema-ref-parser@15'
import { create as createDiffer } from 'https://esm.sh/jsondiffpatch@0.6.0'
import {
  buildNode, nodeMatchesSearch,
  getTypeLabel, typeBadgeClass, variantTitle, variantDesc,
  isExpert, stripExpert, computeSchemaDiff, isComposite,
} from './schema-utils.js'

const differ = createDiffer()

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
  s.hidden = false
  s.textContent = text
  s.className = `badge-pill ${cls}`
}

function clearStatus() {
  document.getElementById('status').hidden = true
}

// ── State ─────────────────────────────────────────────────────────────────────

const expandedKeys = new Set()
let selectedKey = null
let rootNodes = []
let allVersions = []          // newest-first; populated in init()
let currentVersion = null     // currently displayed version
let currentResolved = null    // resolved schema for currentVersion; used by recomputeDiff
let currentDiff = null        // { fromVersion, diff } for the welcome panel

function collectAllKeys(nodes, acc = new Set()) {
  for (const node of nodes) {
    if (node.children.length > 0) {
      acc.add(node.key)
      collectAllKeys(node.children, acc)
    }
  }
  return acc
}

function findNodeByKey(key, nodes = rootNodes) {
  for (const node of nodes) {
    if (node.key === key) return node
    const found = findNodeByKey(key, node.children)
    if (found) return found
  }
  return null
}

function navigateTo(node) {
  let pathSoFar = []
  for (const segment of node.pathArr.slice(0, -1)) {
    pathSoFar.push(segment)
    expandedKeys.add(pathSoFar.join('/'))
  }
  selectedKey = node.key
  renderTree(document.getElementById('search').value.toLowerCase())
  requestAnimationFrame(() => {
    document.querySelector('.tree-item.selected')?.scrollIntoView({ block: 'nearest' })
  })
  renderCard(node)
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

// ── Change summary ────────────────────────────────────────────────────────────

function buildChangeSummarySection({ fromVersion, diff }) {
  const { added, removed, modified } = diff
  const section = el('div', 'welcome-section')

  // Title row with compare-to selector
  const titleRow = el('div', 'diff-title-row')
  titleRow.appendChild(el('span', 'welcome-section-title', 'Change summary'))

  const compareWrap = el('span', 'diff-compare-wrap')
  compareWrap.appendChild(el('label', 'diff-compare-label', 'Compare to'))
  const compareSelect = el('select', 'diff-compare-select')
  const currentIdx = allVersions.indexOf(currentVersion)
  const olderVersions = currentIdx >= 0 ? allVersions.slice(currentIdx + 1) : []
  olderVersions.forEach(v => {
    const opt = document.createElement('option')
    opt.value = v
    opt.textContent = `v${v}`
    if (v === fromVersion) opt.selected = true
    compareSelect.appendChild(opt)
  })
  compareSelect.addEventListener('change', () => recomputeDiff(compareSelect.value))
  compareWrap.appendChild(compareSelect)
  titleRow.appendChild(compareWrap)
  section.appendChild(titleRow)

  const total = added.length + removed.length + modified.length
  if (total === 0) {
    section.appendChild(el('p', 'welcome-text', `No top-level field changes from v${fromVersion}.`))
    return section
  }

  const note = el('p', 'diff-note',
    'Covers top-level field additions, removals, and scalar changes. ' +
    'Structural refactors inside shared definitions may not appear — see Known Limitations in the README.')
  section.appendChild(note)

  function renderGroup(label, cls, items, renderItem) {
    if (!items.length) return
    const group = el('div', 'diff-group')
    group.appendChild(el('span', `diff-label ${cls}`, `${label} (${items.length})`))
    const list = el('ul', 'diff-list')
    items.forEach(item => list.appendChild(renderItem(item)))
    group.appendChild(list)
    section.appendChild(group)
  }

  const fmt = path => path.split('/').join(' › ')

  renderGroup('Added', 'diff-added', added, path => {
    const li = el('li', 'diff-item')
    li.appendChild(el('code', 'diff-field', fmt(path)))
    return li
  })

  renderGroup('Removed', 'diff-removed', removed, path => {
    const li = el('li', 'diff-item')
    li.appendChild(el('code', 'diff-field', fmt(path)))
    return li
  })

  renderGroup('Modified', 'diff-modified', modified, ({ path, changes }) => {
    const li = el('li', 'diff-item')
    li.appendChild(el('code', 'diff-field', fmt(path)))
    const descs = changes.map(({ kind, from, to }) => {
      if (kind === 'type')     return `type: ${from} → ${to}`
      if (kind === 'required') return to ? 'became required' : 'became optional'
      if (kind === 'default') {
        if (from === undefined) return `default added: ${to}`
        if (to   === undefined) return `default removed`
        return `default: ${from} → ${to}`
      }
      return kind
    })
    li.appendChild(el('span', 'diff-desc', ` — ${descs.join(', ')}`))
    return li
  })

  return section
}

// ── Welcome panel ─────────────────────────────────────────────────────────────

function buildWelcomePanel() {
  const panel = el('div', 'welcome-panel')

  // Change summary (shown whenever there is a predecessor version)
  if (currentDiff) panel.appendChild(buildChangeSummarySection(currentDiff))

  // About
  const about = el('div', 'welcome-section')
  about.appendChild(el('div', 'welcome-section-title', 'About this schema'))
  about.appendChild(el('p', 'welcome-text',
    'The workflow migration schema defines every configuration option for the ' +
    'OpenSearch Migration Assistant — source and target cluster connections, ' +
    'traffic capture, replayer behaviour, metadata migration, and more. ' +
    'Each field in the tree corresponds to a key you can set in your migration configuration.'
  ))
  const docsLink = el('a', 'welcome-link', 'Migration Assistant documentation ↗')
  docsLink.href = 'https://docs.opensearch.org/latest/migration-assistant/'
  docsLink.target = '_blank'
  docsLink.rel = 'noopener noreferrer'
  about.appendChild(docsLink)
  panel.appendChild(about)

  // How to use
  const howto = el('div', 'welcome-section')
  howto.appendChild(el('div', 'welcome-section-title', 'How to use'))
  const steps = el('ul', 'welcome-steps')
  ;[
    'Click the ▸ arrow next to any field in the tree to expand its children.',
    'Click a field name to see its full details in this panel.',
    'Use the search box to filter the tree by field name.',
    'Use the version selector to browse schemas from previous releases.',
  ].forEach(text => { steps.appendChild(el('li', null, text)) })
  howto.appendChild(steps)
  panel.appendChild(howto)

  // Badge legend
  const legend = el('div', 'welcome-section')
  legend.appendChild(el('div', 'welcome-section-title', 'Badge legend'))
  const rows = el('div', 'legend-rows')
  ;[
    [badge('Required', 'badge-required'), 'Must be set for the configuration to be valid.'],
    [badge('Optional', 'badge-optional'), 'Has a default value or can be omitted.'],
    [badge('Default: value', 'badge-default'), 'The value used when the field is not specified.'],
    [badge('Expert', 'badge-expert'), 'Advanced option — most migrations do not need to change this.'],
  ].forEach(([b, desc]) => {
    const row = el('div', 'legend-row')
    row.appendChild(b)
    row.appendChild(el('span', 'legend-desc', desc))
    rows.appendChild(row)
  })
  legend.appendChild(rows)
  panel.appendChild(legend)

  return panel
}

// ── Field card ────────────────────────────────────────────────────────────────

function renderCard(node) {
  const main = document.getElementById('main')
  main.innerHTML = ''
  main.appendChild(buildCardEl(node))
}

function buildCardEl(node) {
  if (!node) return buildWelcomePanel()

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
      block.appendChild(buildPropsTable(branch, [...pathArr, variantTitle(branch, i, unionKey)]))
      section.appendChild(block)
    })
    card.appendChild(section)
  } else if (!unionKey && schema.properties) {
    const section = el('div', 'section')
    section.appendChild(el('div', 'section-title', 'Properties'))
    section.appendChild(buildPropsTable(schema, pathArr))
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

function buildPropsTable(schema, parentPathArr = []) {
  const required = new Set(schema.required || [])
  const table = el('table', 'props-table')
  const tbody = el('tbody')

  for (const [key, val] of Object.entries(schema.properties || {})) {
    const t = getTypeLabel(val)
    const isReq = required.has(key)
    const tr = document.createElement('tr')

    const nameTd = el('td', 'prop-name-cell')
    if (isComposite(val)) {
      const link = el('a', 'prop-name prop-drill', key)
      link.href = '#'
      link.addEventListener('click', e => {
        e.preventDefault()
        const childKey = [...parentPathArr, key].join('/')
        const node = findNodeByKey(childKey) || buildNode(key, val, [...parentPathArr, key], required)
        navigateTo(node)
      })
      nameTd.appendChild(link)
    } else {
      nameTd.appendChild(el('span', 'prop-name', key))
    }
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

// ── Resizable divider ─────────────────────────────────────────────────────────

function initDivider() {
  const divider = document.getElementById('divider')
  const viewer  = divider.closest('.viewer')
  const sidebar = document.getElementById('sidebar')

  let dragging = false
  let startPos = 0
  let startSize = 0

  function isMobile() {
    return getComputedStyle(viewer).flexDirection === 'column'
  }

  function onStart(pos) {
    dragging = true
    divider.classList.add('dragging')
    if (isMobile()) {
      startPos  = pos.y
      startSize = sidebar.getBoundingClientRect().height
    } else {
      startPos  = pos.x
      startSize = sidebar.getBoundingClientRect().width
    }
  }

  function onMove(pos) {
    if (!dragging) return
    const rect = viewer.getBoundingClientRect()
    if (isMobile()) {
      const h = Math.max(120, Math.min(startSize + pos.y - startPos, rect.height - 120))
      sidebar.style.height = h + 'px'
    } else {
      const w = Math.max(150, Math.min(startSize + pos.x - startPos, rect.width - 200))
      sidebar.style.width = w + 'px'
    }
  }

  function onEnd() {
    dragging = false
    divider.classList.remove('dragging')
  }

  divider.addEventListener('mousedown',  e => { onStart({ x: e.clientX, y: e.clientY }); e.preventDefault() })
  document.addEventListener('mousemove', e => onMove({ x: e.clientX, y: e.clientY }))
  document.addEventListener('mouseup',   onEnd)

  divider.addEventListener('touchstart', e => {
    const t = e.touches[0]
    onStart({ x: t.clientX, y: t.clientY })
    e.preventDefault()
  }, { passive: false })
  document.addEventListener('touchmove', e => {
    if (!dragging) return
    const t = e.touches[0]
    onMove({ x: t.clientX, y: t.clientY })
    e.preventDefault()
  }, { passive: false })
  document.addEventListener('touchend', onEnd)

  // Clear the axis-specific inline size when the layout orientation changes
  window.addEventListener('resize', () => {
    if (isMobile()) sidebar.style.width = ''
    else            sidebar.style.height = ''
  })
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

async function recomputeDiff(compareVersion) {
  if (!compareVersion || !currentResolved) return
  setStatus('Loading comparison…', 'info')
  try {
    const raw = await fetch(`./schemas/${compareVersion}.json`).then(r => r.json())
    const resolved = await $RefParser.dereference(raw)
    currentDiff = { fromVersion: compareVersion, diff: computeSchemaDiff(resolved, currentResolved, differ) }
  } catch (_) {
    currentDiff = null
  }
  clearStatus()
  renderCard(null)
}

async function loadSchema(version) {
  setStatus('Loading…', 'info')
  currentVersion = version
  currentResolved = null
  currentDiff = null

  let rawSchema
  try {
    rawSchema = await fetch(`./schemas/${version}.json`).then(r => r.json())
  } catch (e) {
    setStatus('Failed to load schema: ' + e.message, 'warn')
    return
  }

  // Resolve current schema and, for the change summary, also resolve the
  // immediate predecessor — both needed so the diff sees actual field structures
  // rather than opaque $ref strings.
  const vIdx = allVersions.indexOf(version)
  const prevVersion = vIdx >= 0 && vIdx < allVersions.length - 1 ? allVersions[vIdx + 1] : null

  const prevRawPromise = prevVersion
    ? fetch(`./schemas/${prevVersion}.json`).then(r => r.json()).catch(() => null)
    : Promise.resolve(null)

  let schema = rawSchema
  try {
    schema = await $RefParser.dereference(rawSchema)
    clearStatus()
  } catch (e) {
    setStatus('Warning: $ref resolution failed — some fields may be incomplete.', 'warn')
  }
  currentResolved = schema

  // Compute change summary against the default predecessor (best-effort)
  const prevRaw = await prevRawPromise
  if (prevRaw) {
    try {
      const prevResolved = await $RefParser.dereference(prevRaw)
      currentDiff = { fromVersion: prevVersion, diff: computeSchemaDiff(prevResolved, schema, differ) }
    } catch (_) { /* silently skip on resolution error */ }
  }

  rebuildFromSchema(schema)
}

async function init() {
  initDivider()

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

  let latest
  try {
    const meta = await fetch('./schemas/versions.json').then(r => r.json())
    allVersions = meta.versions
    latest = meta.latest
    const sel = document.getElementById('version-select')
    meta.versions.forEach(v => {
      const opt = document.createElement('option')
      opt.value = v
      opt.textContent = v
      if (v === latest) opt.selected = true
      sel.appendChild(opt)
    })
    sel.addEventListener('change', () => loadSchema(sel.value))
  } catch (e) {
    setStatus('Failed to load versions: ' + e.message, 'warn')
    return
  }

  await loadSchema(latest)
}

init()
