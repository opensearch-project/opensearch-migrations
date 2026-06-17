"""Resource tree: build and display a resource-centric status tree from migration CRs."""

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
import json

from rich.console import Console
from rich.markup import escape
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
    'Pending Config': ('○', 'cyan'),
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
    'sourceconfigs': [
        'endpoint', 'version', 'allow_insecure',
        'basic_auth.k8s_secret_name', 'sigv4.region', 'sigv4.service',
    ],
    'targetconfigs': [
        'endpoint', 'version', 'allow_insecure',
        'basic_auth.k8s_secret_name', 'sigv4.region', 'sigv4.service',
    ],
    'kafkaconfigs': [
        'type', 'clusterName', 'authType', 'listenerName',
    ],
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

RESOURCE_KIND_TO_PLURAL = {
    'KafkaCluster': 'kafkaclusters',
    'CapturedTraffic': 'capturedtraffics',
    'CaptureProxy': 'captureproxies',
    'DataSnapshot': 'datasnapshots',
    'SnapshotMigration': 'snapshotmigrations',
    'TrafficReplay': 'trafficreplays',
}

VIRTUAL_CONFIG_PLURALS = {'sourceconfigs', 'targetconfigs', 'kafkaconfigs'}

CONFIG_MODE_ALL = 'all'
CONFIG_MODE_DEPLOYED = 'deployed'
CONFIG_MODE_CURRENT_WORKFLOW = 'currentWorkflow'
CONFIG_MODE_PENDING_SUBMIT = 'pendingSubmit'

CONFIG_MODE_LABELS = {
    CONFIG_MODE_ALL: 'All',
    CONFIG_MODE_DEPLOYED: 'Deployed',
    CONFIG_MODE_CURRENT_WORKFLOW: 'Pending',
    CONFIG_MODE_PENDING_SUBMIT: 'To Submit',
}

CONFIG_VALUE_LABELS = {
    CONFIG_MODE_DEPLOYED: 'deployed',
    CONFIG_MODE_CURRENT_WORKFLOW: 'pending',
    CONFIG_MODE_PENDING_SUBMIT: 'to-submit',
}

CONFIG_VALUE_STYLES = {
    CONFIG_MODE_DEPLOYED: '',
    CONFIG_MODE_CURRENT_WORKFLOW: 'grey50',
    CONFIG_MODE_PENDING_SUBMIT: 'green',
}

CONFIG_PHASE_KEY = {
    CONFIG_MODE_DEPLOYED: 'deployed',
    CONFIG_MODE_CURRENT_WORKFLOW: 'submitted',
    CONFIG_MODE_PENDING_SUBMIT: 'pending',
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
    config_diff: Optional[Dict[str, Any]] = None
    config_presence: Dict[str, bool] = field(default_factory=dict)
    diagnostics: List[Dict[str, Any]] = field(default_factory=list)


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


def apply_config_overlays(
    sections: List[ResourceSection],
    submitted_resolved_config: Optional[Dict[str, Any]] = None,
    pending_resolved_config: Optional[Dict[str, Any]] = None,
    submitted_console_config: Optional[Dict[str, Any]] = None,
    pending_console_config: Optional[Dict[str, Any]] = None,
) -> None:
    """Attach submitted/pending config projections to deployed resource nodes.

    The TS config processor projects workflow YAML into CR-like resource specs.
    This function compares those projections against deployed CR specs so the
    resource tree can show three value phases:
    deployed, pending current workflow rollout, and saved config to submit.
    """
    submitted = _resolved_resource_map(submitted_resolved_config)
    pending = _resolved_resource_map(pending_resolved_config)
    submitted.update(_console_resource_map(submitted_console_config))
    pending.update(_console_resource_map(pending_console_config))
    submitted_available = submitted_resolved_config is not None or submitted_console_config is not None
    pending_available = pending_resolved_config is not None or pending_console_config is not None
    if not submitted and not pending and not submitted_available and not pending_available:
        return

    deployed = {
        (node.plural, node.name): node
        for node in _iter_resource_nodes(sections)
    }

    for key, node in deployed.items():
        node.diagnostics = _merged_resource_diagnostics(submitted.get(key), pending.get(key))
        node.config_presence = _build_config_presence(
            deployed=True,
            submitted=key in submitted if submitted_available else None,
            pending=key in pending if pending_available else None,
        )
        node.config_diff = _build_config_diff(
            node.plural,
            node.spec,
            submitted.get(key),
            pending.get(key),
            submitted_available=submitted_available,
            pending_available=pending_available,
        )

    for key in sorted((set(submitted) | set(pending)) - set(deployed)):
        plural, name = key
        resolved = pending.get(key) or submitted.get(key)
        parameters = (resolved or {}).get('parameters') or {}
        virtual = ResourceNode(
            name=name,
            plural=plural,
            phase='Pending Config',
            depends_on=parameters.get('dependsOn', []) or [],
            spec={},
            status={},
            diagnostics=_merged_resource_diagnostics(submitted.get(key), pending.get(key)),
            config_presence=_build_config_presence(
                deployed=False,
                submitted=key in submitted if submitted_available else None,
                pending=key in pending if pending_available else None,
            ),
        )
        virtual.config_diff = _build_config_diff(
            plural,
            {},
            submitted.get(key),
            pending.get(key),
            submitted_available=submitted_available,
            pending_available=pending_available,
        )
        _add_virtual_resource(sections, virtual)


def resource_visible_in_config_mode(resource: ResourceNode, value_mode: str) -> bool:
    """Return whether a resource exists in the selected rollout/config phase."""
    presence = resource.config_presence or {}
    if value_mode == CONFIG_MODE_ALL or not presence:
        return True
    if value_mode == CONFIG_MODE_DEPLOYED:
        return presence.get('deployed', True)
    if value_mode == CONFIG_MODE_CURRENT_WORKFLOW:
        if 'submitted' in presence:
            return presence['submitted']
        return presence.get('deployed', True)
    if value_mode == CONFIG_MODE_PENDING_SUBMIT:
        if 'pending' in presence:
            return presence['pending']
        if 'submitted' in presence:
            return presence['submitted']
        return presence.get('deployed', True)
    return True


def _build_config_presence(
    deployed: bool,
    submitted: Optional[bool],
    pending: Optional[bool],
) -> Dict[str, bool]:
    presence = {'deployed': deployed}
    if submitted is not None:
        presence['submitted'] = submitted
    if pending is not None:
        presence['pending'] = pending
    return presence


def resource_config_change_summary(sections: List[ResourceSection]) -> Dict[str, int]:
    summary = {'pending': 0, 'to_submit': 0, 'resources': 0}
    for node in _iter_resource_nodes(sections):
        diff = node.config_diff or {}
        if not diff:
            continue
        summary['resources'] += 1
        if diff.get('has_submitted_changes'):
            summary['pending'] += 1
        if diff.get('has_pending_submit_changes'):
            summary['to_submit'] += 1
    return summary


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


def _iter_resource_nodes(sections: List[ResourceSection]):
    for section in sections:
        for group in section.groups:
            for resource in group.resources:
                yield resource
                yield from _iter_resource_node_children(resource)


def _iter_resource_node_children(resource: ResourceNode):
    for child in resource.children:
        yield child
        yield from _iter_resource_node_children(child)


def _resolved_resource_map(resolved_config: Optional[Dict[str, Any]]) -> Dict[tuple[str, str], Dict[str, Any]]:
    if not resolved_config:
        return {}
    result = {}
    for resource in resolved_config.get('resources') or []:
        plural = RESOURCE_KIND_TO_PLURAL.get(resource.get('kind'))
        name = resource.get('name')
        if plural and name:
            result[(plural, name)] = resource
    return result


def _console_resource_map(console_config: Optional[Dict[str, Any]]) -> Dict[tuple[str, str], Dict[str, Any]]:
    if not console_config:
        return {}
    result = {}
    for source in console_config.get('sources') or []:
        name = source.get('refName')
        if name:
            result[('sourceconfigs', name)] = {
                'kind': 'SourceConfig',
                'name': name,
                'parameters': source.get('clientConfig') or {},
                'diagnostics': source.get('diagnostics') or [],
            }
    for target in console_config.get('targets') or []:
        name = target.get('refName')
        if name:
            result[('targetconfigs', name)] = {
                'kind': 'TargetConfig',
                'name': name,
                'parameters': target.get('clientConfig') or {},
                'diagnostics': target.get('diagnostics') or [],
            }
    for kafka in console_config.get('kafkas') or []:
        name = kafka.get('refName')
        if name:
            result[('kafkaconfigs', name)] = {
                'kind': 'KafkaConfig',
                'name': name,
                'parameters': kafka.get('runtime') or {},
                'diagnostics': kafka.get('diagnostics') or [],
            }
    return result


def _add_virtual_resource(sections: List[ResourceSection], resource: ResourceNode) -> None:
    for section in sections:
        for group in section.groups:
            group_plurals = next(
                (plurals for _, grps in RESOURCE_SECTIONS for plurals, _ in grps if plurals[0] == group.plural),
                [group.plural]
            )
            if resource.plural in group_plurals:
                if resource.plural == 'capturedtraffics':
                    parent_name = resource.spec.get('kafkaClusterName') or _pending_field_value(
                        resource.config_diff,
                        'kafkaClusterName',
                    )
                    parent = next((item for item in group.resources if item.name == parent_name), None)
                    if parent:
                        group.not_configured = False
                        parent.children.append(resource)
                        return
                group.not_configured = False
                group.resources.append(resource)
                return
    display_names = {
        'sourceconfigs': 'Sources',
        'targetconfigs': 'Targets',
        'kafkaconfigs': 'Kafka Clients',
    }
    display_name = display_names.get(resource.plural)
    if not display_name:
        return
    section = next((item for item in sections if item.name == 'Workflow Configuration'), None)
    if section is None:
        section = ResourceSection(name='Workflow Configuration', groups=[])
        sections.insert(0, section)
    group = next((item for item in section.groups if item.plural == resource.plural), None)
    if group is None:
        group = ResourceGroup(plural=resource.plural, display_name=display_name, resources=[])
        section.groups.append(group)
    group.resources.append(resource)


def _merged_resource_diagnostics(*resources: Optional[Dict[str, Any]]) -> List[Dict[str, Any]]:
    result = {}
    for resource in resources:
        for diagnostic in (resource or {}).get('diagnostics') or []:
            key = (
                diagnostic.get('severity'),
                tuple(diagnostic.get('path') or []),
                diagnostic.get('message'),
            )
            result[key] = diagnostic
    return list(result.values())


def _pending_field_value(config_diff: Optional[Dict[str, Any]], path: str):
    for field in (config_diff or {}).get('fields', []):
        if field.get('path') == path:
            state = field.get('values', {}).get('pending') or {}
            if state.get('present'):
                return state.get('value')
    return None


def _build_config_diff(
    plural: str,
    deployed_parameters: Dict[str, Any],
    submitted_resource: Optional[Dict[str, Any]],
    pending_resource: Optional[Dict[str, Any]],
    submitted_available: bool = False,
    pending_available: bool = False,
) -> Optional[Dict[str, Any]]:
    submitted_parameters = (
        (submitted_resource or {}).get('parameters') if submitted_resource else (
            None if submitted_available else deployed_parameters
        )
    )
    pending_parameters = (
        (pending_resource or {}).get('parameters') if pending_resource else (
            None if pending_available else submitted_parameters
        )
    )
    if submitted_parameters is None and pending_parameters is None and not deployed_parameters:
        return None

    paths = _ordered_config_paths(plural, deployed_parameters, submitted_parameters, pending_parameters)
    fields = []
    has_submitted_changes = False
    has_pending_submit_changes = False
    compare_submitted_to_deployed = plural not in VIRTUAL_CONFIG_PLURALS
    for path in paths:
        values = {
            'deployed': _value_state(deployed_parameters, path),
            'submitted': _value_state(submitted_parameters, path),
            'pending': _value_state(pending_parameters, path),
        }
        submitted_changed = (
            compare_submitted_to_deployed and
            not _same_state(values['deployed'], values['submitted'])
        )
        pending_submit_changed = not _same_state(values['submitted'], values['pending'])
        if not submitted_changed and not pending_submit_changed:
            continue
        if submitted_changed:
            has_submitted_changes = True
        if pending_submit_changed:
            has_pending_submit_changes = True
        fields.append({
            'path': '.'.join(path),
            'label': path[-1],
            'values': values,
        })

    if not fields:
        return None

    return {
        'status': 'to_submit' if has_pending_submit_changes else 'pending',
        'has_submitted_changes': has_submitted_changes,
        'has_pending_submit_changes': has_pending_submit_changes,
        'fields': fields,
    }


def _ordered_config_paths(
    plural: str,
    deployed_parameters: Dict[str, Any],
    submitted_parameters: Optional[Dict[str, Any]],
    pending_parameters: Optional[Dict[str, Any]],
) -> List[List[str]]:
    configured = [field.split('.') for field in SPEC_DISPLAY_FIELDS.get(plural, [])]
    discovered = set()
    for source in (deployed_parameters, submitted_parameters, pending_parameters):
        for path in _leaf_paths(source or {}):
            discovered.add(tuple(path))

    ordered = []
    seen = set()
    for path in configured:
        key = tuple(path)
        if key in discovered and key not in seen:
            ordered.append(path)
            seen.add(key)
    for key in sorted(discovered - seen):
        ordered.append(list(key))
    return ordered


def _leaf_paths(value: Any, prefix: Optional[List[str]] = None):
    prefix = prefix or []
    if isinstance(value, dict):
        if not value:
            if prefix:
                yield prefix
            return
        for key, child in value.items():
            yield from _leaf_paths(child, [*prefix, key])
        return
    if prefix:
        yield prefix


def _value_state(source: Optional[Dict[str, Any]], path: List[str]) -> Dict[str, Any]:
    if source is None:
        return {'present': False}
    if not _has_nested(source, path):
        return {'present': False}
    return {'present': True, 'value': _get_nested(source, '.'.join(path))}


def _same_state(left: Dict[str, Any], right: Dict[str, Any]) -> bool:
    if bool(left.get('present')) != bool(right.get('present')):
        return False
    if not left.get('present'):
        return True
    return json.dumps(left.get('value'), sort_keys=True) == json.dumps(right.get('value'), sort_keys=True)


def _has_nested(data: Dict[str, Any], path: List[str]) -> bool:
    cur = data
    for part in path:
        if not isinstance(cur, dict) or part not in cur:
            return False
        cur = cur[part]
    return True


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
DISPLAY_PHASES = {'Ready', 'Completed', 'Failed', 'Error', 'Pending Config'}


def _render_resource(parent_node, resource: ResourceNode, show_live_status: bool = True) -> None:
    """Render a single resource node with its children."""
    symbol, color = PHASE_SYMBOLS.get(resource.phase, ('?', 'white'))
    change_label = _resource_change_label(resource)
    if resource.phase in DISPLAY_PHASES:
        label = (
            f"[{color}]{symbol}[/{color}] [bold]{resource.name}[/bold] "
            f"[{color}]({resource.phase})[/{color}]{change_label}"
        )
    else:
        label = f"[{color}]{symbol}[/{color}] [bold]{resource.name}[/bold]{change_label}"
    node = parent_node.add(label)
    _add_resource_details(node, resource, show_live_status)
    for child in resource.children:
        _render_resource(node, child, show_live_status)


def _add_resource_details(node, resource: ResourceNode, show_live_status: bool = True) -> None:
    """Add spec/status detail lines under a resource node."""
    for spec_line in format_spec_fields(resource):
        node.add(f"[dim]{spec_line}[/dim]")
    for config_line in format_config_diff_fields(resource):
        node.add(f"[cyan]{config_line}[/cyan]")
    for diagnostic in format_resource_diagnostics(resource):
        style = _diagnostic_style(diagnostic.get('severity', 'error'))
        node.add(f"[{style}]{diagnostic['label']}[/{style}]")
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


def _resource_change_label(resource: ResourceNode) -> str:
    diagnostic = _highest_priority_diagnostic(resource)
    if diagnostic:
        severity = diagnostic.get('severity') or 'error'
        style = _diagnostic_style(severity)
        label = 'required' if severity == 'required' else severity
        return f' [{style}]({escape(str(label))})[/{style}]'
    diff = resource.config_diff or {}
    if not diff:
        return ''
    if diff.get('has_pending_submit_changes'):
        return ' [cyan](to submit)[/cyan]'
    if diff.get('has_submitted_changes'):
        return ' [magenta](pending)[/magenta]'
    return ''


def _highest_priority_diagnostic(resource: ResourceNode) -> Optional[Dict[str, Any]]:
    rank = {'error': 4, 'required': 3, 'blocked': 3, 'gated': 2, 'warning': 1}
    diagnostics = resource.diagnostics or []
    if not diagnostics:
        return None
    return max(diagnostics, key=lambda item: rank.get(item.get('severity'), 0))


def _diagnostic_style(severity: str) -> str:
    if severity in ('error', 'blocked'):
        return 'red'
    if severity == 'required':
        return 'yellow'
    if severity == 'gated':
        return 'magenta'
    return 'yellow'


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


def format_config_diff_fields(
    resource: ResourceNode,
    value_mode: str = CONFIG_MODE_ALL,
    rich_markup: bool = False,
) -> List[str]:
    diff = resource.config_diff or {}
    fields = diff.get('fields') or []
    if not fields:
        return []

    result = []
    for field in fields:
        label = field.get('label') or field.get('path')
        values = field.get('values') or {}
        display_label = escape(str(label)) if rich_markup else str(label)
        if value_mode == CONFIG_MODE_ALL:
            parts = []
            for mode in (CONFIG_MODE_DEPLOYED, CONFIG_MODE_CURRENT_WORKFLOW, CONFIG_MODE_PENDING_SUBMIT):
                key = CONFIG_PHASE_KEY[mode]
                parts.append(_format_config_value_segment(mode, values.get(key) or {}, rich_markup))
            result.append(f"{display_label}: {' | '.join(parts)}")
            continue

        key = CONFIG_PHASE_KEY.get(value_mode)
        if key:
            result.append(f"{display_label}: {_format_config_value_segment(value_mode, values.get(key) or {}, rich_markup)}")
    return result


def _format_config_value_segment(mode: str, state: Dict[str, Any], rich_markup: bool) -> str:
    segment = f"{CONFIG_VALUE_LABELS.get(mode, mode)}={_format_config_value(state)}"
    if not rich_markup:
        return segment
    style = CONFIG_VALUE_STYLES.get(mode)
    escaped = escape(segment)
    return f"[{style}]{escaped}[/{style}]" if style else escaped


def _format_config_value(state: Dict[str, Any]) -> str:
    if not state.get('present'):
        return '<absent>'
    value = state.get('value')
    if isinstance(value, bool):
        return 'true' if value else 'false'
    if isinstance(value, list):
        return '[' + ', '.join(str(item) for item in value) + ']'
    if isinstance(value, dict):
        return json.dumps(value, sort_keys=True)
    if value is None:
        return 'null'
    return str(value)


def format_resource_diagnostics(resource: ResourceNode) -> List[Dict[str, str]]:
    result = []
    for diagnostic in resource.diagnostics or []:
        severity = str(diagnostic.get('severity') or 'error')
        message = escape(str(diagnostic.get('message') or 'Invalid value'))
        path = escape('.'.join(str(part) for part in diagnostic.get('path') or []))
        label = f"{severity}: {path}: {message}" if path else f"{severity}: {message}"
        result.append({'severity': severity, 'label': label})
    return result


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
