"""Resource tree: build and display a resource-centric status tree from migration CRs."""

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from rich.console import Console
from rich.tree import Tree

from .commands.crd_utils import list_migration_resources_full
from .tree_utils import (
    build_nested_workflow_tree, filter_tree_nodes, get_node_input_parameter,
    is_approval_node, get_step_rich_label, get_step_status_output,
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
    'captureproxies': ['podReplicas', 'listenPort', 'internetFacing'],
    'datasnapshots': ['snapshotPrefix', 'indexAllowlist'],
    'snapshotmigrations': ['documentBackfillPodReplicas', 'sourceVersion'],
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

    # Future phases
    config_diff: Optional[Dict[str, Any]] = None
    workflow_step: Optional[List[Dict[str, Any]]] = None
    live_status: Optional[Dict[str, Any]] = None


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


def display_resource_tree(sections: List[ResourceSection], workflow_unavailable: bool = False) -> None:
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
            _render_group(section_tree, group)
        console.print(section_tree)

    if workflow_unavailable:
        console.print("\n[dim](Workflow progress unavailable)[/dim]")


def _render_group(parent_tree, group: ResourceGroup) -> None:
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
        _render_resource(group_node, resource)


def _render_resource(parent_node, resource: ResourceNode) -> None:
    """Render a single resource node with its children."""
    symbol, color = PHASE_SYMBOLS.get(resource.phase, ('?', 'white'))
    label = f"[{color}]{symbol} {resource.name} ({resource.phase})[/{color}]"
    node = parent_node.add(label)
    _add_resource_details(node, resource)
    for child in resource.children:
        _render_resource(node, child)


def _add_resource_details(node, resource: ResourceNode) -> None:
    """Add spec/status detail lines under a resource node."""
    details = _format_spec_fields(resource)
    if details:
        node.add(f"[dim]{details}[/dim]")
    if resource.depends_on:
        deps = ", ".join(resource.depends_on)
        node.add(f"[dim]Depends on: {deps}[/dim]")
    if resource.live_status:
        _add_live_status(node, resource.live_status)
    if resource.workflow_step:
        _add_workflow_subtree(node, resource.workflow_step)


def _add_workflow_subtree(parent_node, steps: List[Dict[str, Any]]) -> None:
    """Render the workflow subtree under a resource."""
    workflow_node = parent_node.add("[bold]Workflow progress:[/bold]")
    for step in steps:
        _render_workflow_step(workflow_node, step)


def _render_workflow_step(parent_node, step: Dict[str, Any]) -> None:
    """Recursively render a workflow step node using the same formatting as workflow status."""
    label = get_step_rich_label(step, status_output=None, show_approval_name=False)
    node = parent_node.add(label)
    for child in step.get('children', []):
        _render_workflow_step(node, child)


def _node_phase(node: Dict[str, Any]) -> str:
    """Get the display phase for a workflow node."""
    return node.get('phase', 'Unknown')


def _add_live_status(node, live_status: Dict[str, Any]) -> None:
    """Render live status data under a resource."""
    if live_status.get('type') == 'backfill':
        message = live_status.get('message', '')
        for line in message.strip().split('\n'):
            if line.strip():
                node.add(f"[cyan]{line.strip()}[/cyan]")


def _format_spec_fields(resource: ResourceNode) -> str:
    """Extract key spec fields for display."""
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
    return ' | '.join(parts)


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


def extract_backfill_config(workflow_data: Dict[str, Any]) -> Optional[str]:
    """Extract configContents from a backfill status check node in the workflow.

    Returns the raw JSON config string that can be converted to services.yaml via jq.
    """
    if not workflow_data or not workflow_data.get('status', {}).get('nodes'):
        return None

    nodes = workflow_data.get('status', {}).get('nodes', {})
    for node in nodes.values():
        if 'checkBackfillStatus' in node.get('displayName', ''):
            for p in node.get('inputs', {}).get('parameters', []):
                if p.get('name') == 'configContents':
                    return p.get('value')
    return None


def enrich_with_backfill_status(sections: List[ResourceSection], workflow_data: Dict[str, Any]) -> None:
    """Fetch live backfill status and attach to the SnapshotMigration resource."""
    config_json = extract_backfill_config(workflow_data)
    if not config_json:
        return

    migration_resource = _find_active_migration(sections)
    if not migration_resource:
        return

    live_status = _fetch_backfill_live_status(config_json)
    if live_status:
        migration_resource.live_status = live_status


def _find_active_migration(sections: List[ResourceSection]) -> Optional[ResourceNode]:
    """Find the first non-completed SnapshotMigration resource."""
    for section in sections:
        for group in section.groups:
            for resource in group.resources:
                if resource.plural == 'snapshotmigrations' and resource.phase != 'Completed':
                    return resource
    return None


def _fetch_backfill_live_status(config_json: str) -> Optional[Dict[str, Any]]:
    """Convert workflow config and fetch backfill status. Returns None on any failure."""
    import logging
    import subprocess
    import os
    import yaml

    jq_script = os.environ.get('WORKFLOW_CONFIG_JQ_SCRIPT',
                               '/root/workflowConfigToServicesConfig.jq')
    try:
        result = subprocess.run(['jq', '-f', jq_script],
                                input=config_json, text=True, capture_output=True)
        if result.returncode != 0:
            return

        from console_link.environment import Environment
        env = Environment(config=yaml.safe_load(result.stdout))
        if not env.backfill:
            return

        # Temporarily suppress noisy error logs from backfill internals
        backfill_logger = logging.getLogger('console_link.models.backfill_rfs')
        prev_level = backfill_logger.level
        backfill_logger.setLevel(logging.CRITICAL)
        try:
            status_result = env.backfill.get_status(deep_check=True)
        finally:
            backfill_logger.setLevel(prev_level)

        if status_result.success:
            backfill_status, message = status_result.value
            return {
                'type': 'backfill',
                'status': str(backfill_status),
                'message': message,
            }
    except Exception:
        pass
