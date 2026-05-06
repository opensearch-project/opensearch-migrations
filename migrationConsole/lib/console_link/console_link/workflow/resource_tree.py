"""Resource tree: build and display a resource-centric status tree from migration CRs."""

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from rich.console import Console
from rich.tree import Tree

from .commands.crd_utils import RESETTABLE_PLURALS, list_migration_resources_full
from .tree_utils import (
    build_nested_workflow_tree, filter_tree_nodes, get_node_input_parameter,
    is_approval_node,
)


# Display order and human-readable names for resource groups
# Tuples of (list of plurals, display name)
RESOURCE_GROUP_ORDER = [
    (['datasnapshots'], 'Source Snapshot'),
    (['snapshotmigrations'], 'Backfill From Snapshot'),
    (['captureproxies'], 'Traffic Capture'),
    (['kafkaclusters', 'capturedtraffics'], 'Traffic Buffer'),
    (['trafficreplays'], 'Traffic Replay'),
]

PHASE_SYMBOLS = {
    'Ready': ('✓', 'green'),
    'Completed': ('✓', 'green'),
    'Running': ('▶', 'yellow'),
    'Initialized': ('○', 'cyan'),
    'Error': ('✗', 'red'),
    'Unknown': ('?', 'white'),
}

# Key spec fields to display per resource type
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

    # Future phases
    config_diff: Optional[Dict[str, Any]] = None
    workflow_step: Optional[Dict[str, Any]] = None
    live_status: Optional[Dict[str, Any]] = None


@dataclass
class ResourceGroup:
    """A group of resources of the same CRD type."""
    plural: str
    display_name: str
    resources: List[ResourceNode] = field(default_factory=list)
    not_configured: bool = False


def build_resource_tree(namespace: str) -> List[ResourceGroup]:
    """Query all migration CRs and build a resource tree grouped by type."""
    raw = list_migration_resources_full(namespace)
    return _build_tree_from_raw(raw)


def _build_tree_from_raw(raw: Dict[str, List[Dict[str, Any]]]) -> List[ResourceGroup]:
    """Build resource tree from raw CR data (testable without K8s)."""
    groups = []
    for plurals, display_name in RESOURCE_GROUP_ORDER:
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
        groups.append(ResourceGroup(plural=plurals[0], display_name=display_name, resources=resources))
    return groups


def extract_workflow_steps_by_resource(workflow_data: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    """Extract the active workflow step for each resource from Argo workflow data.

    Returns a dict mapping CR name → step info dict with keys:
        display_name, phase, is_approval, denial_reason
    """
    if not workflow_data or not workflow_data.get('status', {}).get('nodes'):
        return {}

    tree = build_nested_workflow_tree(workflow_data)
    filtered = filter_tree_nodes(tree)
    result = {}

    for node in filtered:
        _extract_steps_recursive(node, result)

    return result


def _extract_steps_recursive(node: Dict[str, Any], result: Dict[str, Dict[str, Any]]) -> None:
    """Walk the tree and extract steps for the innermost resource-tagged nodes."""
    cr_name = _resolve_cr_name(node)
    if cr_name:
        # If we resolved via resourceName or name param, this is authoritative
        has_explicit_name = (
            get_node_input_parameter(node, 'resourceName') or
            get_node_input_parameter(node, 'name')
        )
        if has_explicit_name:
            # If the resource node itself succeeded, report completed
            if node.get('phase') == 'Succeeded':
                result[cr_name] = {'display_name': 'completed', 'phase': 'Succeeded',
                                   'is_approval': False, 'denial_reason': None}
            else:
                step = _find_active_step(node.get('children', []))
                if step:
                    result[cr_name] = step
            return

        # Fallback: using groupName_view as CR name — skip if children have their own tags
        has_inner_tagged = any(
            child.get('group_name') for child in node.get('children', [])
        )
        if not has_inner_tagged:
            if node.get('phase') == 'Succeeded':
                result[cr_name] = {'display_name': 'completed', 'phase': 'Succeeded',
                                   'is_approval': False, 'denial_reason': None}
            else:
                step = _find_active_step(node.get('children', []))
                if step:
                    result[cr_name] = step
            return

    # Recurse into children
    for child in node.get('children', []):
        _extract_steps_recursive(child, result)


def _resolve_cr_name(node: Dict[str, Any]) -> Optional[str]:
    """Resolve the CR name from a node with groupName_view."""
    if not node.get('group_name'):
        return None
    resource_name = get_node_input_parameter(node, 'resourceName')
    if resource_name:
        return resource_name
    name = get_node_input_parameter(node, 'name')
    if name:
        return name
    return node['group_name']


def _find_active_step(children: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    """Find the most relevant step to display from a resource's subtree."""
    # Priority: running approval > running leaf > failed leaf > all succeeded
    running_approval = None
    running_leaf = None
    failed_leaf = None
    all_succeeded = True

    for node in _iter_nodes(children):
        phase = node.get('phase', '')
        if phase == 'Running' and is_approval_node(node):
            running_approval = {
                'display_name': _clean_step_name(node.get('display_name', '')),
                'phase': 'Running',
                'is_approval': True,
                'denial_reason': node.get('denial_reason'),
            }
        elif phase == 'Running' and not node.get('children'):
            running_leaf = {
                'display_name': _clean_step_name(node.get('display_name', '')),
                'phase': 'Running',
                'is_approval': False,
                'denial_reason': None,
            }
        elif phase == 'Pending' and not node.get('children'):
            if not running_leaf:
                running_leaf = {
                    'display_name': _clean_step_name(node.get('display_name', '')),
                    'phase': 'Pending',
                    'is_approval': False,
                    'denial_reason': None,
                }
        elif phase in ('Failed', 'Error') and not node.get('children'):
            failed_leaf = {
                'display_name': _clean_step_name(node.get('display_name', '')),
                'phase': phase,
                'is_approval': False,
                'denial_reason': None,
            }
        if phase not in ('Succeeded', 'Skipped') and not node.get('children'):
            all_succeeded = False

    if running_approval:
        return running_approval
    if running_leaf:
        return running_leaf
    if failed_leaf:
        return failed_leaf
    if all_succeeded and children:
        return {'display_name': 'completed', 'phase': 'Succeeded', 'is_approval': False, 'denial_reason': None}
    return None


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


def mark_not_configured_groups(groups: List[ResourceGroup], workflow_data: Dict[str, Any]) -> None:
    """Mark groups as 'not configured' if they have no CRs but the workflow skipped them."""
    if not workflow_data or not workflow_data.get('status', {}).get('nodes'):
        return

    tree = build_nested_workflow_tree(workflow_data)
    filtered = filter_tree_nodes(tree)
    _mark_not_configured_from_filtered(groups, filtered)


def _mark_not_configured_from_filtered(groups: List[ResourceGroup], filtered: List[Dict[str, Any]]) -> None:
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

    for group in groups:
        if not group.resources and group.plural in skipped_plurals:
            group.not_configured = True


def display_resource_tree(groups: List[ResourceGroup], workflow_unavailable: bool = False) -> None:
    """Render the resource tree using Rich."""
    console = Console()
    has_any = any(g.resources or g.not_configured for g in groups)
    if not has_any:
        console.print("[dim]No migration resources found.[/dim]")
        return

    for group in groups:
        if not group.resources and not group.not_configured:
            continue
        group_tree = Tree(f"[bold]{group.display_name}[/bold]")
        if group.not_configured:
            group_tree.add("[dim](not configured)[/dim]")
        else:
            # Find the plural ordering for this group
            group_plurals = next(
                (plurals for plurals, _ in RESOURCE_GROUP_ORDER if group.plural == plurals[0]),
                [group.plural]
            )
            plural_order = {p: i for i, p in enumerate(group_plurals)}
            for resource in sorted(group.resources, key=lambda r: (plural_order.get(r.plural, 99), r.name)):
                symbol, color = PHASE_SYMBOLS.get(resource.phase, ('?', 'white'))
                label = f"[{color}]{symbol} {resource.name} ({resource.phase})[/{color}]"
                node = group_tree.add(label)
                _add_resource_details(node, resource)
        console.print(group_tree)

    if workflow_unavailable:
        console.print("\n[dim](Workflow progress unavailable)[/dim]")


def _add_resource_details(node, resource: ResourceNode) -> None:
    """Add spec/status detail lines under a resource node."""
    details = _format_spec_fields(resource)
    if details:
        node.add(f"[dim]{details}[/dim]")
    if resource.depends_on:
        deps = ", ".join(resource.depends_on)
        node.add(f"[dim]Depends on: {deps}[/dim]")
    if resource.workflow_step:
        step = resource.workflow_step
        # Don't show "completed" if the CR phase already indicates completion
        if step['phase'] == 'Succeeded' and resource.phase in ('Ready', 'Completed'):
            return
        _add_workflow_step(node, step)


def _add_workflow_step(node, step: Dict[str, Any]) -> None:
    """Render a workflow progress line under a resource."""
    phase = step['phase']
    name = step['display_name']

    if step.get('is_approval'):
        reason = step.get('denial_reason') or ''
        suffix = f" ({reason})" if reason else ""
        label = f"[yellow]⟳ Workflow progress: WAITING FOR APPROVAL{suffix}[/yellow]"
    elif phase == 'Succeeded':
        label = f"[green]✓ Workflow progress: {name}[/green]"
    elif phase in ('Running', 'Pending'):
        label = f"[yellow]▶ Workflow progress: {name} ({phase})[/yellow]"
    elif phase in ('Failed', 'Error'):
        label = f"[red]✗ Workflow progress: {name} ({phase})[/red]"
    else:
        label = f"[dim]Workflow progress: {name} ({phase})[/dim]"

    node.add(label)


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
