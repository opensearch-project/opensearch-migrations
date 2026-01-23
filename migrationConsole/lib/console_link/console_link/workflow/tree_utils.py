"""Workflow tree processing and display utilities."""

import logging
from typing import Dict, List, Any, Optional
from rich.console import Console
from rich.tree import Tree
import json

logger = logging.getLogger(__name__)


class WorkflowDisplayer:
    """Base class for workflow display implementations."""

    def display_workflow_status(self, workflow_name: str, phase: str, started_at: str,
                                finished_at: str, tree_nodes: List[Dict[str, Any]],
                                workflow_data: Dict[str, Any] = None) -> None:
        """Display complete workflow status. Must be implemented by subclasses."""
        raise NotImplementedError

    def get_phase_symbol(self, phase: str) -> str:
        """Get symbol for workflow phase. Must be implemented by subclasses."""
        raise NotImplementedError

    def get_step_symbol(self, step_phase: str, step_type: str) -> str:
        """Get symbol for workflow step. Must be implemented by subclasses."""
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
        # Extract groupName if available
        inputs = node.get('inputs', {})
        parameters = inputs.get('parameters', [])
        group_name = None

        for param in parameters:
            if param.get('name') == 'groupName':
                group_name = param.get('value', '')
                break

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
        tree_nodes[node_id] = tree_node

    # Second pass: establish parent-child relationships
    for node_id, tree_node in tree_nodes.items():
        boundary_id = tree_node['boundary_id']
        if boundary_id and boundary_id in tree_nodes:
            # This node is a child of boundary_id
            tree_nodes[boundary_id]['children'].append(tree_node)
        else:
            # This is a root node
            root_nodes.append(tree_node)

    return root_nodes


def filter_tree_nodes(tree_nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Filter tree nodes: preserve Pod/Suspend/Skipped nodes and containers with groupName."""

    def should_keep_by_type(node):
        # Keep leaf nodes (actual work)
        return node['type'] in ["Pod", "Suspend", "Skipped"]

    def has_group_name(node):
        # Keep containers that have a groupName (meaningful grouping)
        for param in node.get('inputs', {}).get('parameters', []):
            if param.get('name') == 'groupName':
                return True
        return False

    def filter_recursive(nodes):
        filtered = []
        for node in nodes:
            if should_keep_by_type(node) or has_group_name(node):
                # Keep leaf nodes (Pod, Suspend, Skipped) or containers with a meaningful groupName
                filtered_node = node.copy()
                filtered_node['children'] = filter_recursive(node['children'])
                filtered.append(filtered_node)
            else:
                # Skip container, lift children up
                filtered.extend(filter_recursive(node['children']))

        return filtered

    return filter_recursive(tree_nodes)


def get_step_status_output(workflow_data: Dict[str, Any], node_id: str) -> Optional[str]:
    """Extract statusOutput from a workflow step or its children by node ID."""
    logger.debug(f"Looking for statusOutput in node: {node_id}")
    nodes = workflow_data.get("status", {}).get("nodes", {})

    # Check the specific node and its children for statusOutput
    def check_node_for_status_output(current_node_id, depth=0):
        logger.debug(f"{'  ' * depth}Checking node {current_node_id} for statusOutput")
        node = nodes.get(current_node_id, {})

        # Check outputs first
        outputs = node.get("outputs", {})
        parameters = outputs.get("parameters", [])
        for param in parameters:
            if param.get("name") == "statusOutput":
                logger.debug(f"Found statusOutput in node {current_node_id}: {param.get('value')}")
                return param.get("value")

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
        logger.debug(f"No statusOutput found for node: {node_id}")  # Changed from warning to debug
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
    import re
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


def get_step_rich_label(node: dict, status_output: str, show_approval_name: bool = True) -> str:
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
    if node['type'] not in ['Pod', 'Suspend', 'Skipped']:
        step_name = clean_display_name(step_name)

    if node.get('group_name'):
        step_name = f"{step_name} ({node['group_name']})"

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
    return f"[{color}]{symbol} {full_unformatted_line}{': ' + status_output if status_output else ''} [/{color}]"


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
                          workflow_data: Optional[Dict] = None) -> None:
    """Display workflow tree using Rich with proper nesting and live check results."""

    # Sort nodes chronologically by startedAt time (ascending - earliest first)
    sorted_nodes = sorted(tree_nodes, key=lambda n: n.get('started_at') or '9999-12-31T23:59:59Z')
    logger.info("display_workflow_tree running ")

    console = Console()
    tree = Tree("[bold]Workflow Steps[/bold]")

    def add_nodes_to_tree(nodes, parent_tree):
        sorted_children = sorted(nodes, key=lambda n: n.get('started_at') or '9999-12-31T23:59:59Z')

        for node in sorted_children:
            # Get statusOutput for all nodes that might have it
            status_output = get_step_status_output(workflow_data, node['id']) if workflow_data else ""

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
