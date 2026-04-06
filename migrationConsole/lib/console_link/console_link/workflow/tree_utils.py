"""Workflow tree processing and display utilities."""

import logging
import re
from dataclasses import dataclass
from typing import Callable, Dict, List, Any, Optional, Union
from rich.console import Console
from rich.tree import Tree
import json

logger = logging.getLogger(__name__)


@dataclass
class ArtifactRef:
    """Reference to an artifact output that needs to be fetched from the Argo API."""
    node_id: str
    artifact_name: str


class WorkflowDisplayer:
    """Base class for workflow display implementations."""

    def display_workflow_status(self, workflow_name: str, phase: str, started_at: str,
                                finished_at: str, tree_nodes: List[Dict[str, Any]],
                                workflow_data: Dict[str, Any] = None,
                                artifact_resolver: Optional[Callable] = None) -> None:
        """Display complete workflow status. Must be implemented by subclasses."""
        raise NotImplementedError

    def get_phase_symbol(self, phase: str) -> str:
        """Get symbol for workflow phase. Must be implemented by subclasses."""
        raise NotImplementedError

    def display_workflow_header(self, name: str, phase: str, started_at: str, finished_at: str) -> None:
        """Display workflow header. Must be implemented by subclasses."""
        raise NotImplementedError


def get_node_input_parameter(node: Dict[str, Any], param_name: str) -> Optional[str]:
    """Get a parameter value from a node's inputs."""
    inputs = node.get('inputs', {})
    parameters = inputs.get('parameters', [])
    for param in parameters:
        if param.get('name') == param_name:
            return param.get('value')
    return None


def build_nested_workflow_tree(workflow_data: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Build a properly nested tree structure from workflow nodes."""
    nodes = workflow_data.get("status", {}).get("nodes", {})

    # Build parent-child relationships
    tree_nodes = {}
    root_nodes = []

    # First pass: create all nodes
    for node_id, node in nodes.items():
        inputs = node.get('inputs', {})
        parameters = inputs.get('parameters', [])
        group_name = None
        sort_order = None

        for param in parameters:
            if param.get('name') == 'groupName_view':
                group_name = param.get('value', '')
            elif param.get('name') == 'sortOrder_view':
                try:
                    sort_order = int(param.get('value', ''))
                except (ValueError, TypeError):
                    pass

        tree_node = {
            'id': node_id,
            'display_name': node.get('displayName', ''),
            'phase': node.get('phase', 'Unknown'),
            'type': node.get('type', ''),
            'boundary_id': node.get('boundaryID'),
            'children': [],
            'inputs': node.get('inputs', {}),
            'outputs': node.get('outputs', {}),
            'started_at': node.get('startedAt'),
            'finished_at': node.get('finishedAt')
        }
        if group_name:
            tree_node['group_name'] = group_name
        if sort_order is not None:
            tree_node['sort_order'] = sort_order
        tree_nodes[node_id] = tree_node

    # Second pass: establish parent-child relationships via boundaryID
    for node_id, tree_node in tree_nodes.items():
        boundary_id = tree_node['boundary_id']
        if boundary_id and boundary_id in tree_nodes:
            tree_nodes[boundary_id]['children'].append(tree_node)
        else:
            root_nodes.append(tree_node)

    # Third pass: reparent Retry node children.  Argo's boundaryID places retry
    # attempt Pods as siblings of the Retry node; use the explicit children array
    # to fix the nesting.
    for node_id, node in nodes.items():
        if node.get('type') != 'Retry':
            continue
        retry_tree_node = tree_nodes[node_id]
        for child_id in node.get('children', []):
            child_tree_node = tree_nodes.get(child_id)
            if child_tree_node and child_tree_node not in retry_tree_node['children']:
                current_parent_id = child_tree_node['boundary_id']
                if current_parent_id and current_parent_id in tree_nodes:
                    parent = tree_nodes[current_parent_id]
                    if child_tree_node in parent['children']:
                        parent['children'].remove(child_tree_node)
                elif child_tree_node in root_nodes:
                    root_nodes.remove(child_tree_node)
                retry_tree_node['children'].append(child_tree_node)

    return root_nodes


_ATTEMPT_SUFFIX_REGEX_PATTERN = re.compile(r'\((\d+)\)$')


def _normalize_attempt_suffix(name: str) -> str:
    """Strip trailing attempt suffix like '(0)', '(1)' from display names."""
    return _ATTEMPT_SUFFIX_REGEX_PATTERN.sub('', name).strip()


def _is_leaf_only_retry(node: Dict[str, Any]) -> bool:
    """True if all children are bare leaf Pods (no statusOutput, groupName, or nested children).

    Currently affects: RFS coordinator resource templates and similar infrastructure retries.
    """
    children = node.get('children', [])
    if not children:
        return True
    for child in children:
        if child.get('children'):
            return False
        if any(p.get('name') == 'statusOutput' for p in child.get('outputs', {}).get('parameters', [])):
            return False
        if any(p.get('name') == 'groupName_view' for p in child.get('inputs', {}).get('parameters', [])):
            return False
    return True


def _get_attempt_number(name: str) -> int:
    """Extract retry attempt index from display name like 'foo(2)'. Returns -1 if none."""
    m = _ATTEMPT_SUFFIX_REGEX_PATTERN.search(name)
    return int(m.group(1)) if m else -1


def _collapse_retry(node: Dict[str, Any]) -> Dict[str, Any]:
    """Collapse a Retry node to its best attempt: highest-indexed succeeded, or highest-indexed overall."""
    children = node.get('children', [])
    if not children:
        collapsed = node.copy()
        collapsed['display_name'] = _normalize_attempt_suffix(collapsed.get('display_name', ''))
        collapsed['children'] = []
        return collapsed

    candidates = [c for c in children if c.get('phase') == 'Succeeded'] or children
    best = max(candidates, key=lambda c: (
        _get_attempt_number(c.get('display_name', '')),
        c.get('finished_at') or ''
    ))

    collapsed = best.copy()
    collapsed['display_name'] = _normalize_attempt_suffix(collapsed.get('display_name', ''))
    collapsed['children'] = []
    return collapsed


def _is_retry_group(node: Dict[str, Any]) -> bool:
    """Check if a node is a *WithRetry Steps node (tryApply/waitForFix/retryLoop pattern)."""
    if node.get('type') not in ('Steps',):
        return False
    child_names = {c.get('display_name', '').split('(')[0] for c in node.get('children', [])}
    return 'tryApply' in child_names and 'waitForFix' in child_names


def _collapse_retry_group(node: Dict[str, Any]) -> Dict[str, Any]:
    """Collapse a *WithRetry Steps node into a single display node."""
    children = node.get('children', [])
    child_map = {}
    for c in children:
        base = c.get('display_name', '').split('(')[0]
        child_map[base] = c

    try_apply = child_map.get('tryApply')
    wait_for_fix = child_map.get('waitForFix')
    retry_loop = child_map.get('retryLoop')

    if not try_apply:
        return node

    # Walk recursive retryLoop chain to find final attempt
    attempt = 1
    final_try = try_apply
    final_wait = wait_for_fix
    current_retry = retry_loop
    while current_retry and current_retry.get('phase') != 'Skipped':
        retry_child_map = {}
        for c in current_retry.get('children', []):
            retry_child_map[c.get('display_name', '').split('(')[0]] = c
        next_try = retry_child_map.get('tryApply')
        if next_try:
            attempt += 1
            final_try = next_try
            final_wait = retry_child_map.get('waitForFix', final_wait)
            current_retry = retry_child_map.get('retryLoop')
        else:
            break

    group_name = get_node_input_parameter(node, 'retryGroupName_view') or 'Apply'
    display = group_name if attempt == 1 else f"{group_name} (attempt {attempt})"

    collapsed = final_try.copy()
    collapsed['display_name'] = display
    collapsed['children'] = []

    # If tryApply failed and waitForFix is running, show as waiting for approval
    if final_try.get('phase') == 'Failed' and final_wait and final_wait.get('phase') == 'Running':
        collapsed['phase'] = 'Running'
        collapsed['type'] = 'Suspend'

    return collapsed


def filter_tree_nodes(tree_nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Filter tree nodes: preserve Pod/Suspend/Skipped nodes and containers with groupName.

    Collapse infrastructure retry nodes (bare leaf Pods) to a single logical step.
    Collapse *WithRetry retry groups (tryApply/waitForFix/retryLoop) to a single node.
    """

    def should_keep_by_type(node):
        # Keep leaf nodes (actual work)
        return node['type'] in ["Pod", "Suspend", "Skipped"]

    def has_group_name(node):
        # Keep containers that have a groupName (meaningful grouping)
        for param in node.get('inputs', {}).get('parameters', []):
            if param.get('name') == 'groupName_view':
                return True
        return False

    def filter_recursive(nodes):
        filtered = []
        for node in nodes:
            if _is_retry_group(node):
                filtered.append(_collapse_retry_group(node))
            elif node.get('type') == 'Retry' and _is_leaf_only_retry(node):
                filtered.append(_collapse_retry(node))
            elif should_keep_by_type(node) or has_group_name(node):
                filtered_node = node.copy()
                filtered_node['children'] = filter_recursive(node['children'])
                filtered.append(filtered_node)
            else:
                # Skip container, lift children up
                filtered.extend(filter_recursive(node['children']))

        return filtered

    return filter_recursive(tree_nodes)


def get_step_status_output(workflow_data: Dict[str, Any], node_id: str) -> Optional[Union[str, ArtifactRef]]:
    """Extract statusOutput from a workflow step or its children by node ID.

    Returns the parameter value as a string, or an ArtifactRef if the output
    is stored as an artifact (requires a separate API call to fetch content).
    """
    logger.debug(f"Looking for statusOutput in node: {node_id}")
    nodes = workflow_data.get("status", {}).get("nodes", {})

    # Check the specific node and its children for statusOutput
    def check_node_for_status_output(current_node_id, depth=0):
        logger.debug(f"{'  ' * depth}Checking node {current_node_id} for statusOutput")
        node = nodes.get(current_node_id, {})

        # Check parameter outputs first
        outputs = node.get("outputs", {})
        parameters = outputs.get("parameters", [])
        for param in parameters:
            if param.get("name") == "statusOutput":
                logger.debug(f"Found statusOutput parameter in node {current_node_id}: {param.get('value')}")
                return param.get("value")

        # Check artifact outputs
        artifacts = outputs.get("artifacts", [])
        for artifact in artifacts:
            if artifact.get("name") == "statusOutput":
                logger.debug(f"Found statusOutput artifact in node {current_node_id}")
                return ArtifactRef(node_id=current_node_id, artifact_name="statusOutput")

        # Check children
        children = node.get("children", [])
        logger.debug(f"{'  ' * depth}Node {current_node_id} has {len(children)} children")
        for child_id in children:
            result = check_node_for_status_output(child_id, depth + 1)
            if result:
                return result

        return None

    result = check_node_for_status_output(node_id)
    if not result:
        logger.debug(f"No statusOutput found for node: {node_id}")
    return result


def get_node_symbol(phase: str, node_type: str) -> str:
    """Get symbol for workflow node."""
    if phase == 'Succeeded':
        return "✓"
    elif phase in ('Failed', 'Error'):
        return "✗"
    elif phase == 'Running':
        return "⟳" if node_type == 'Suspend' else "▶"
    elif phase == 'Pending':
        return "○"
    elif phase == 'Skipped':
        return "~"
    else:
        return "?"


def clean_display_name(display_name: str) -> str:
    """Clean up display names by removing parameters and converting camelCase to Title Case."""
    # Extract the function name before any parentheses or parameters
    base_name = display_name.split('(')[0]

    # Convert camelCase to Title Case
    # Insert space before uppercase letters that follow lowercase letters
    spaced_name = re.sub(r'([a-z])([A-Z])', r'\1 \2', base_name)

    # Capitalize each word
    title_case = ' '.join(word.capitalize() for word in spaced_name.split())

    return title_case


def get_node_phase(node: dict) -> str:
    """Get phase for a workflow node, preferring overriddenPhase output parameter."""
    outputs = node.get('outputs', {})
    parameters = outputs.get('parameters', [])
    for param in parameters:
        if param.get('name') == 'overriddenPhase':
            custom_phase = param.get('value', '').strip()
            if custom_phase:
                return custom_phase
    return node['phase']


def get_step_rich_label(
    node: dict, status_output: Optional[Union[str, ArtifactRef]],
    show_approval_name: bool = True
) -> str:
    """Get rich-formatted label for a workflow step node.

    Args:
        node: WorkflowNode dictionary
        status_output: Additional status output to append
        show_approval_name: Whether to show the approval name for Suspend nodes

    Returns:
        Rich-formatted string with color and styling
    """
    step_name = node['display_name']

    # Clean up container node names (non-Pod types)
    if node['type'] not in ['Pod', 'Suspend']:
        step_name = clean_display_name(step_name)

    if node.get('group_name'):
        step_name = f"{step_name}: {node['group_name']}"

    # Add timestamp - prefer finished_at, fallback to started_at
    timestamp_str = ""
    if node.get('finished_at'):
        timestamp = node['finished_at'][:19]  # Remove timezone info
        timestamp_str = f" {timestamp.replace('T', ' ')}"
    elif node.get('started_at'):
        timestamp = node['started_at'][:19]  # Remove timezone info
        timestamp_str = f" {timestamp.replace('T', ' ')}"
    # Determine phase - prefer overriddenPhase output parameter over argo phase
    step_phase = get_node_phase(node)

    step_type = node['type']

    # Color based on phase
    if step_phase == 'Succeeded':
        color = "green"
        symbol = "✓"
    elif step_phase == 'Running' or step_phase == 'Checked':
        color = "yellow"
        symbol = "⟳" if step_type == 'Suspend' else "▶"
    elif step_phase in ('Failed', 'Error'):
        color = "red"
        symbol = "✗"
    elif step_phase == 'Pending':
        color = "cyan"
        symbol = "○"
    elif step_phase == 'Skipped':
        color = "dim"
        symbol = "~"
    else:
        color = "white"
        symbol = "?"

    step_name_and_timestamp_str = f"{timestamp_str}: {step_name}"

    # Extract 'name' input parameter for Suspend nodes (only if showing)
    approval_name = None
    if show_approval_name and step_type == 'Suspend':
        for p in node.get('inputs', {}).get('parameters', []):
            if p.get('name') == 'name':
                approval_name = p.get('value')
                break

    full_unformatted_line = _construct_full_label_line(
        step_name_and_timestamp_str, step_phase, step_type, approval_name
    )
    status_suffix = f': {status_output}' if status_output and isinstance(status_output, str) else ''
    return f"[{color}]{symbol} {full_unformatted_line}{status_suffix} [/{color}]"


def _construct_full_label_line(step_name_and_timestamp_str, step_phase, step_type, approval_name=None):
    if step_type == 'Suspend':
        if step_phase == 'Running':
            suffix = f" OF '{approval_name}'" if approval_name else ""
            return f"{step_name_and_timestamp_str} - WAITING FOR APPROVAL{suffix}"
        elif step_phase == 'Succeeded':
            return f"{step_name_and_timestamp_str} (Approved)"
        else:
            return f"{step_name_and_timestamp_str} ({step_phase})"
    # Special handling for Skipped steps with approval-related names
    elif step_phase == 'Skipped' and 'approval' in step_name_and_timestamp_str.lower():
        return f"{step_name_and_timestamp_str} (Not Required)"
    else:
        return f"{step_name_and_timestamp_str} ({step_phase})"


def display_workflow_tree(tree_nodes: List[Dict[str, Any]],
                          workflow_data: Optional[Dict] = None,
                          artifact_resolver: Optional[Callable[[ArtifactRef], Optional[str]]] = None) -> None:
    """Display workflow tree using Rich with proper nesting and live check results.

    Args:
        tree_nodes: List of tree node dictionaries
        workflow_data: Full workflow data for extracting status outputs
        artifact_resolver: Optional callable to lazily resolve artifact content.
            Only invoked for nodes that are actually rendered and have artifact outputs.
    """

    # Sort nodes: by sort_order if present, then by timestamp, then by name
    def _sort_key(n):
        return (n.get('sort_order', 999),
                n.get('finished_at') or n.get('started_at') or '9999-12-31T23:59:59Z',
                n.get('display_name', ''))

    sorted_nodes = sorted(tree_nodes, key=_sort_key)
    logger.info("display_workflow_tree running ")

    console = Console()
    tree = Tree("[bold]Workflow Steps[/bold]")

    def add_nodes_to_tree(nodes, parent_tree):
        sorted_children = sorted(nodes, key=_sort_key)

        for node in sorted_children:
            # Get statusOutput for all nodes that might have it
            status_output = get_step_status_output(workflow_data, node['id']) if workflow_data else ""

            # Lazily resolve artifact references only for nodes being rendered
            if isinstance(status_output, ArtifactRef):
                status_output = artifact_resolver(status_output) if artifact_resolver else ""
                status_output = status_output or ""

            # Use Rich formatting for the label
            node_label = get_step_rich_label(node, status_output)

            # Add node to tree
            node_tree = parent_tree.add(node_label)
            if (live_check := node.get('live_check', None)):
                if live_check.get('success') and 'value' in live_check:
                    # Format the value with proper newlines
                    value = live_check['value'].replace('\\n', '\n')
                    parent_tree.add(f"Live Status: {value}")
                else:
                    parent_tree.add(json.dumps(live_check))

            # Recursively add children
            if node['children']:
                add_nodes_to_tree(node['children'], node_tree)

    add_nodes_to_tree(sorted_nodes, tree)
    console.print(tree)
