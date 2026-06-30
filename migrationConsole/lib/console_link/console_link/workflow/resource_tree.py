"""Resource tree: build and display a resource-centric status tree from migration CRs."""

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from rich.console import Console
from rich.tree import Tree

from .commands.crd_utils import list_migration_resources_full
from .tree_utils import (
    get_node_input_parameter, get_step_rich_label, is_approval_node, get_node_phase,
)


# Sections and their resource groups
# Each section contains (list of plurals, display name) tuples
RESOURCE_SECTIONS = [
    ('Snapshot Migration', [
        (['datasnapshots'], 'Snapshot'),
        (['snapshotmigrations'], 'Backfill'),
    ]),
    ('Live Traffic Migration', [
        (['captureproxies'], 'Capture'),
        (['kafkaclusters', 'capturedtraffics'], 'Buffer'),
        (['trafficreplays'], 'Replay'),
    ]),
]

PHASE_SYMBOLS = {
    'Ready': ('✓', 'green'),
    'Completed': ('✓', 'green'),
    'Succeeded': ('✓', 'green'),
    'Running': ('▶', 'yellow'),
    'Pending': ('○', 'cyan'),
    'Initialized': ('○', 'cyan'),
    'Failed': ('✗', 'red'),
    'Error': ('✗', 'red'),
    'Skipped': ('~', 'dim'),
    'Unknown': ('?', 'white'),
}

# Key spec fields to display per resource type.
# TODO: Derive these from the generated JSON schema instead of hardcoding here.
# The Zod schemas in orchestrationSpecs/packages/schemas/src/userSchemas.ts already emit
# x-change-restriction via getSchemaFromZod.ts; a similar x-user-visible annotation could
# be added, then this code would filter the JSON schema (at runtime or build time).
SPEC_DISPLAY_FIELDS = {
    'kafkaclusters': ['version', 'auth.type', 'nodePool.replicas'],
    'capturedtraffics': ['topicName', 'partitions', 'replicas'],
    'captureproxies': ['podReplicas', 'listenPort', 'internetFacing', 'serviceType'],
    'datasnapshots': ['snapshotPrefix', 'indexAllowlist'],
    'snapshotmigrations': [
        'documentBackfillPodReplicas', 'sourceVersion',
        'documentBackfillIndexAllowlist', 'metadataMigrationIndexAllowlist',
    ],
    'trafficreplays': ['podReplicas', 'speedupFactor', 'removeAuthHeader'],
}


@dataclass
class ResourceNode:
    """A single migration resource (CR instance)."""
    name: str
    plural: str
    phase: str
    depends_on: List[str]
    spec: Dict[str, Any]
    status: Dict[str, Any]
    created_at: Optional[str] = None
    children: List['ResourceNode'] = field(default_factory=list)

    workflow_progress: Optional[List[Dict[str, Any]]] = None
    config_diff: Optional[Dict[str, Any]] = None  # Future: pending config changes


@dataclass
class ResourceGroup:
    """A group of resources of the same CRD type."""
    plural: str
    display_name: str
    resources: List[ResourceNode] = field(default_factory=list)
    not_configured: bool = False


@dataclass
class ResourceSection:
    """A top-level section grouping related resource groups."""
    name: str
    groups: List[ResourceGroup] = field(default_factory=list)


def build_resource_tree(namespace: str) -> List[ResourceSection]:
    """Query all migration CRs and build a resource tree grouped by type."""
    raw = list_migration_resources_full(namespace)
    return _build_tree_from_raw(raw)


def _build_tree_from_raw(raw: Dict[str, List[Dict[str, Any]]]) -> List[ResourceSection]:
    """Build resource tree from raw CR data (testable without K8s)."""
    sections = []
    for section_name, group_defs in RESOURCE_SECTIONS:
        groups = []
        for plurals, display_name in group_defs:
            resources = []
            for plural in plurals:
                for item in raw.get(plural, []):
                    resources.append(ResourceNode(
                        name=item['metadata']['name'],
                        plural=plural,
                        phase=item.get('status', {}).get('phase', 'Unknown'),
                        depends_on=item.get('spec', {}).get('dependsOn', []) or [],
                        spec=item.get('spec', {}),
                        status=item.get('status', {}),
                        created_at=item.get('metadata', {}).get('creationTimestamp'),
                    ))
            # Nest captured traffics under their parent kafka cluster
            _nest_topics_under_kafka(resources)
            groups.append(ResourceGroup(plural=plurals[0], display_name=display_name, resources=resources))
        sections.append(ResourceSection(name=section_name, groups=groups))
    return sections


def _nest_topics_under_kafka(resources: List[ResourceNode]) -> None:
    """Move CapturedTraffic resources under their parent KafkaCluster."""
    kafka_by_name = {r.name: r for r in resources if r.plural == 'kafkaclusters'}
    topics_to_remove = []
    for resource in resources:
        if resource.plural == 'capturedtraffics':
            parent_name = resource.spec.get('kafkaClusterName') or ''
            if parent_name in kafka_by_name:
                kafka_by_name[parent_name].children.append(resource)
                topics_to_remove.append(resource)
    for topic in topics_to_remove:
        resources.remove(topic)


def extract_workflow_steps_by_resource(filtered_tree: List[Dict[str, Any]]) -> Dict[str, List[Dict[str, Any]]]:
    """Extract the workflow subtree for each resource from a filtered Argo tree.

    Returns a dict mapping CR name → list of filtered workflow step nodes.
    """
    result = {}
    for node in filtered_tree:
        _extract_steps_recursive(node, result)
    return result


def _extract_steps_recursive(node: Dict[str, Any], result: Dict[str, Dict[str, Any]]) -> None:
    """Walk the tree and extract steps for nodes with resourceName."""
    cr_name = _resolve_cr_name(node)
    if cr_name:
        result[cr_name] = _step_for_node(node)
        return

    for child in node.get('children', []):
        _extract_steps_recursive(child, result)


def _step_for_node(node: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Get the workflow subtree for a resource node."""
    return node.get('children', [])


def _resolve_cr_name(node: Dict[str, Any]) -> Optional[str]:
    """Resolve the CR name from a resource-level node's resourceName input parameter."""
    if not node.get('group_name'):
        return None
    return get_node_input_parameter(node, 'resourceName')


def _clean_step_name(name: str) -> str:
    """Clean up Argo step display names by removing parameters in parentheses."""
    paren = name.find('(')
    if paren > 0:
        return name[:paren].strip()
    return name


def _iter_nodes(nodes: List[Dict[str, Any]]):
    """Recursively iterate all nodes in a tree."""
    for node in nodes:
        yield node
        yield from _iter_nodes(node.get('children', []))


def mark_not_configured_groups(sections: List[ResourceSection], filtered_tree: List[Dict[str, Any]]) -> None:
    """Mark groups as 'not configured' if they have no CRs but the workflow skipped them."""
    if not filtered_tree:
        return
    _mark_not_configured_from_filtered(sections, filtered_tree)


def _mark_not_configured_from_filtered(sections: List[ResourceSection], filtered: List[Dict[str, Any]]) -> None:
    """Mark groups using a pre-built filtered tree."""
    # Find skipped top-level nodes
    skipped_groups = set()
    for node in filtered:
        if node.get('phase') == 'Skipped':
            skipped_groups.add(node.get('display_name', ''))

    # Map skipped display names to resource group plurals
    skip_map = {
        'createKafka': 'kafkaclusters',
        'createProxy': 'captureproxies',
        'createTrafficReplayer': 'trafficreplays',
    }

    skipped_plurals = set()
    for name in skipped_groups:
        plural = skip_map.get(name)
        if plural:
            skipped_plurals.add(plural)

    for section in sections:
        for group in section.groups:
            if not group.resources and group.plural in skipped_plurals:
                group.not_configured = True


def display_resource_tree(sections: List[ResourceSection], workflow_unavailable: bool = False,
                          show_live_status: bool = True) -> None:
    """Render the resource tree using Rich."""
    console = Console()
    has_any = any(
        g.resources or g.not_configured
        for s in sections for g in s.groups
    )
    if not has_any:
        console.print("[dim]No migration resources found.[/dim]")
        return

    for section in sections:
        section_has_content = any(g.resources or g.not_configured for g in section.groups)
        if not section_has_content:
            continue
        section_tree = Tree(f"[bold]{section.name}[/bold]")
        for group in section.groups:
            _render_group(section_tree, group, show_live_status)
        console.print(section_tree)

    if workflow_unavailable:
        console.print("\n[dim](Workflow progress unavailable)[/dim]")


def _render_group(parent_tree, group: ResourceGroup, show_live_status: bool = True) -> None:
    """Render a resource group as a subtree."""
    if not group.resources and not group.not_configured:
        return
    group_node = parent_tree.add(f"[bold]{group.display_name}[/bold]")
    if group.not_configured:
        group_node.add("[dim](not configured)[/dim]")
        return

    group_plurals = next(
        (plurals for _, grps in RESOURCE_SECTIONS for plurals, _ in grps if plurals[0] == group.plural),
        [group.plural]
    )
    plural_order = {p: i for i, p in enumerate(group_plurals)}
    for resource in sorted(group.resources, key=lambda r: (plural_order.get(r.plural, 99), r.name)):
        _render_resource(group_node, resource, show_live_status)


# Phases shown in the resource label (settled states)
DISPLAY_PHASES = {'Ready', 'Completed', 'Failed', 'Error'}


def _render_resource(parent_node, resource: ResourceNode, show_live_status: bool = True) -> None:
    """Render a single resource node with its children."""
    symbol, color = PHASE_SYMBOLS.get(resource.phase, ('?', 'white'))
    if resource.phase in DISPLAY_PHASES:
        label = f"[{color}]{symbol}[/{color}] [bold]{resource.name}[/bold] [{color}]({resource.phase})[/{color}]"
    else:
        label = f"[{color}]{symbol}[/{color}] [bold]{resource.name}[/bold]"
    node = parent_node.add(label)
    _add_resource_details(node, resource, show_live_status)
    for child in resource.children:
        _render_resource(node, child, show_live_status)


def _add_resource_details(node, resource: ResourceNode, show_live_status: bool = True) -> None:
    """Add spec/status detail lines under a resource node."""
    for spec_line in format_spec_fields(resource):
        node.add(f"[dim]{spec_line}[/dim]")
    if resource.depends_on and resource.phase not in ('Ready', 'Completed'):
        deps = ", ".join(resource.depends_on)
        node.add(f"[dim]Depends on: {deps}[/dim]")
    if show_live_status:
        live = format_live_status(resource)
        if live:
            summary_line, detail_lines = live
            status_node = node.add(f"[cyan]{summary_line}[/cyan]")
            for line in detail_lines:
                status_node.add(f"[cyan]{line}[/cyan]")
    if resource.workflow_progress:
        if has_notable_steps(resource.workflow_progress):
            _add_workflow_subtree(node, resource.workflow_progress)


def _should_show_step(step: Dict[str, Any]) -> bool:
    """Return True if a workflow step should be shown in the filtered view."""
    phase = get_node_phase(step) if 'phase' in step else step.get('phase', '')
    if phase in ('Failed', 'Error', 'Running', 'Pending'):
        return True
    if step.get('live_check'):
        return True
    if is_approval_node(step) and phase == 'Running':
        return True
    return False


def has_notable_steps(steps: List[Dict[str, Any]]) -> bool:
    """Return True if any step in the subtree should be shown."""
    for step in steps:
        if _should_show_step(step):
            return True
        if has_notable_steps(step.get('children', [])):
            return True
    return False


def step_timestamp(step: Dict[str, Any]) -> str:
    """Get the display timestamp for sorting (finished_at or started_at)."""
    return step.get('finished_at') or step.get('started_at') or ''


def _add_workflow_subtree(parent_node, steps: List[Dict[str, Any]]) -> None:
    """Render only notable workflow steps under a resource."""
    notable = collect_notable_steps(steps)
    if not notable:
        return
    # Add the most recent succeeded step for transition context
    last_succeeded = find_last_succeeded(steps)
    if last_succeeded and last_succeeded not in notable:
        notable.append(last_succeeded)
    notable.sort(key=step_timestamp)
    workflow_node = parent_node.add("Workflow progress:")
    for step in notable:
        _render_workflow_step(workflow_node, step)


def find_last_succeeded(steps: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """Find the most recently completed step (by finished_at) among top-level steps only."""
    best = None
    for step in steps:
        phase = get_node_phase(step) if 'phase' in step else ''
        if phase in ('Succeeded', 'Checked'):
            if not best or (step.get('finished_at') or '') > (best.get('finished_at') or ''):
                best = step
    return best


def collect_notable_steps(steps: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Filter steps to only those worth showing, preserving hierarchy for notable children."""
    result = []
    for step in steps:
        if _should_show_step(step):
            result.append(step)
        else:
            # Check if any children are notable — if so, lift them up
            notable_children = collect_notable_steps(step.get('children', []))
            result.extend(notable_children)
    return result


def maybe_rewrite_wait_step(step: Dict[str, Any]) -> Dict[str, Any]:
    """Rewrite waitFor steps to show the resource being waited on."""
    if is_approval_node(step):
        return step
    display_name = step.get('display_name', '')
    base_name = display_name.split('(')[0].strip() if '(' in display_name else display_name
    if not base_name.startswith('wait'):
        return step
    resource_name = get_node_input_parameter(step, 'resourceName')
    if resource_name and resource_name.strip():
        rewritten = step.copy()
        rewritten['display_name'] = f"Waiting for: {resource_name}"
        return rewritten
    # No resourceName — strip the loop parameters but keep the base name
    rewritten = step.copy()
    rewritten['display_name'] = base_name
    return rewritten


def _render_workflow_step(parent_node, step: Dict[str, Any]) -> None:
    """Recursively render a workflow step node using the same formatting as workflow status."""
    display_step = maybe_rewrite_wait_step(step)
    label = get_step_rich_label(display_step, status_output=None, show_approval_name=False)
    node = parent_node.add(label)
    # Show live check results if present (from LiveCheckProcessor)
    live_check = step.get('live_check')
    if live_check and live_check.get('success') and 'value' in live_check:
        value = live_check['value'].replace('\\n', '\n')
        for line in value.strip().split('\n'):
            if line.strip():
                node.add(f"[cyan]{line.strip()}[/cyan]")
    for child in sorted(collect_notable_steps(step.get('children', [])), key=step_timestamp):
        _render_workflow_step(node, child)


def _node_phase(node: Dict[str, Any]) -> str:
    """Get the display phase for a workflow node."""
    return node.get('phase', 'Unknown')


def format_spec_fields(resource: ResourceNode) -> List[str]:
    """Extract key spec fields for display. Returns list of 'field: value' strings."""
    fields = SPEC_DISPLAY_FIELDS.get(resource.plural, [])
    parts = []
    for field_path in fields:
        value = _get_nested(resource.spec, field_path)
        if value is not None and value != '' and value != []:
            label = field_path.split('.')[-1]
            if isinstance(value, list):
                value = ', '.join(str(v) for v in value[:3])
                if len(resource.spec.get(field_path, [])) > 3:
                    value += '...'
            parts.append(f"{label}: {value}")
    return parts


def format_live_status(resource: ResourceNode):
    """Format live progress from CR status fields.

    Returns (summary_line, detail_lines) or None if no status data.
    """
    if resource.plural == 'snapshotmigrations':
        backfill = resource.status.get('documentBackfill')
        if isinstance(backfill, dict):
            return _format_backfill_status(backfill)
    elif resource.plural == 'datasnapshots':
        creation = resource.status.get('snapshotCreation')
        if isinstance(creation, dict):
            return _format_snapshot_creation_status(creation)
    elif resource.plural == 'captureproxies':
        endpoint = (resource.status.get('loadBalancerEndpoint') or '').strip()
        if endpoint:
            return f"endpoint: {endpoint}", []
    return None


def _format_backfill_status(backfill: Dict[str, Any]):
    summary = backfill.get('summary') or {}
    parts = []
    details = []
    phase = backfill.get('phase')
    if phase:
        details.append(f"phase: {phase}")
    pct = summary.get('percentageCompleted')
    if pct is not None:
        parts.append(f"{pct:.0f}%")
    shards_total = summary.get('shardsTotal')
    shards_migrated = summary.get('shardsMigrated')
    if shards_total is not None:
        parts.append(f"shards {shards_migrated or 0}/{shards_total}")
        details.append(f"shards migrated: {shards_migrated or 0}/{shards_total}")
    shards_in_progress = summary.get('shardsInProgress')
    if shards_in_progress:
        details.append(f"shards in progress: {shards_in_progress}")
    shards_waiting = summary.get('shardsWaiting')
    if shards_waiting:
        details.append(f"shards waiting: {shards_waiting}")
    eta_ms = summary.get('etaMs')
    if eta_ms:
        secs = int(eta_ms / 1000)
        m, s = divmod(secs, 60)
        h, m = divmod(m, 60)
        parts.append(f"ETA {h}h {m}m {s}s")
        details.append(f"ETA: {h}h {m}m {s}s")
    started = summary.get('started')
    if started:
        details.append(f"started: {started}")
    finished = summary.get('finished')
    if finished:
        details.append(f"finished: {finished}")
    updated_at = backfill.get('updatedAt')
    if updated_at:
        details.append(f"updated at: {updated_at}")
    if parts:
        return f"Backfill status: {', '.join(parts)}", details
    return None


def _format_snapshot_creation_status(creation: Dict[str, Any]):
    summary = creation.get('summary') or {}
    parts = []
    details = []
    phase = creation.get('phase')
    if phase:
        details.append(f"phase: {phase}")
    shards_total = summary.get('shardsTotal')
    shards_done = summary.get('shardsSuccessful')
    if shards_total is not None:
        parts.append(f"shards {shards_done or 0}/{shards_total}")
        details.append(f"shards: {shards_done or 0}/{shards_total}")
    shards_failed = summary.get('shardsFailed')
    if shards_failed:
        details.append(f"shards failed: {shards_failed}")
    data_processed = summary.get('dataProcessed')
    if data_processed and phase != 'Completed':
        unit = summary.get('dataProcessedUnit', 'MiB')
        details.append(f"data processed: {data_processed} {unit}")
    updated_at = creation.get('updatedAt')
    if updated_at:
        details.append(f"updated at: {updated_at}")
    if parts:
        return f"Snapshot status: {', '.join(parts)}", details
    return None


def _get_nested(d: Dict[str, Any], path: str) -> Any:
    """Get a nested dict value by dot-separated path."""
    keys = path.split('.')
    for key in keys:
        if not isinstance(d, dict):
            return None
        d = d.get(key)
        if d is None:
            return None
    return d
