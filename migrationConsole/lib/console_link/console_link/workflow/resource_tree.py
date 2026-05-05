"""Resource tree: build and display a resource-centric status tree from migration CRs."""

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from rich.console import Console
from rich.tree import Tree

from .commands.crd_utils import RESETTABLE_PLURALS, list_migration_resources_full


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


def display_resource_tree(groups: List[ResourceGroup]) -> None:
    """Render the resource tree using Rich."""
    console = Console()
    has_any = any(g.resources for g in groups)
    if not has_any:
        console.print("[dim]No migration resources found.[/dim]")
        return

    for group in groups:
        if not group.resources:
            continue
        group_tree = Tree(f"[bold]{group.display_name}[/bold]")
        for resource in sorted(group.resources, key=lambda r: r.name):
            symbol, color = PHASE_SYMBOLS.get(resource.phase, ('?', 'white'))
            label = f"[{color}]{symbol} {resource.name} ({resource.phase})[/{color}]"
            node = group_tree.add(label)
            _add_resource_details(node, resource)
        console.print(group_tree)


def _add_resource_details(node, resource: ResourceNode) -> None:
    """Add spec/status detail lines under a resource node."""
    details = _format_spec_fields(resource)
    if details:
        node.add(f"[dim]{details}[/dim]")
    if resource.depends_on:
        deps = ", ".join(resource.depends_on)
        node.add(f"[dim]Depends on: {deps}[/dim]")


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
