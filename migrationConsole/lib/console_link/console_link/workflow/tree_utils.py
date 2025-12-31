"""Workflow tree processing and display utilities."""

import logging
from typing import Dict, List, Any, Optional
from rich.console import Console
from rich.tree import Tree

logger = logging.getLogger(__name__)


def build_nested_workflow_tree(workflow_data: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Build a properly nested tree structure from workflow nodes."""
    nodes = workflow_data.get("status", {}).get("nodes", {})
    
    # Build parent-child relationships
    tree_nodes = {}
    root_nodes = []
    
    # First pass: create all nodes
    for node_id, node in nodes.items():
        tree_nodes[node_id] = {
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
    """Smart filter: show Pod/Suspend/Skipped nodes, and containers only when they have parallel work."""
    
    def should_keep_by_type(node):
        # Original type-based filtering
        return node['type'] in ["Pod", "Suspend", "Skipped"]
    
    def has_parallel_children(node, filtered_children):
        """Check if filtered children actually ran in parallel."""
        if len(filtered_children) <= 1:
            return False
        
        # Get start times of children
        start_times = []
        for child in filtered_children:
            start_time = child.get('started_at')
            if start_time:
                start_times.append(start_time)
        
        if len(start_times) <= 1:
            return False
        
        # Sort times and check if any overlap (started within same minute = parallel)
        sorted_times = sorted(start_times)
        for i in range(len(sorted_times) - 1):
            time1 = sorted_times[i][:16]  # YYYY-MM-DDTHH:MM (minute precision)
            time2 = sorted_times[i + 1][:16]
            if time1 == time2:  # Started in same minute = parallel
                return True
        
        return False
    
    def filter_recursive(nodes):
        filtered = []
        for node in nodes:
            if should_keep_by_type(node):
                # Keep leaf nodes (Pod, Suspend, Skipped)
                filtered_node = node.copy()
                filtered_node['children'] = filter_recursive(node['children'])
                filtered.append(filtered_node)
            else:
                # For container nodes, check if they have parallel work
                filtered_children = filter_recursive(node['children'])
                
                if has_parallel_children(node, filtered_children):
                    # Has parallel work - keep the container to show structure
                    filtered_node = node.copy()
                    filtered_node['children'] = filtered_children
                    filtered.append(filtered_node)
                else:
                    # Sequential work - flatten (lift children up)
                    filtered.extend(filtered_children)
        
        return filtered
    
    return filter_recursive(tree_nodes)


def get_step_status_output(workflow_data: Dict[str, Any], node_id: str) -> Optional[str]:
    """Extract statusOutput from a workflow step or its children by node ID."""
    logger.info(f"Looking for statusOutput in node: {node_id}")
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
                logger.info(f"Found statusOutput in node {current_node_id}: {param.get('value')}")
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
        logger.warning(f"No statusOutput found for node: {node_id}")
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


def display_workflow_tree(tree_nodes: List[Dict[str, Any]], 
                         deep_check_data: Optional[Dict] = None,
                         workflow_data: Optional[Dict] = None,
                         show_deep_check: bool = False) -> None:
    """Display workflow tree using Rich with proper nesting and deep check results."""
    
    # Sort nodes chronologically by startedAt time (ascending - earliest first)
    sorted_nodes = sorted(tree_nodes, key=lambda n: n.get('started_at') or '9999-12-31T23:59:59Z')
    
    # Find the last failed node with deep check data
    last_failed_node_id = None
    if show_deep_check and deep_check_data:
        failed_nodes = []
        for node in sorted_nodes:
            if node['phase'] == 'Failed' and node['id'] in deep_check_data:
                failed_nodes.append(node)
        
        if failed_nodes:
            last_failed_node_id = failed_nodes[-1]['id']
            logger.info(f"Last failed node for deep check: {last_failed_node_id}")
    
    console = Console()
    tree = Tree("[bold]Workflow Steps[/bold]")
    
    def add_nodes_to_tree(nodes, parent_tree):
        sorted_children = sorted(nodes, key=lambda n: n.get('started_at') or '9999-12-31T23:59:59Z')
        
        for node in sorted_children:
            symbol = get_node_symbol(node['phase'], node['type'])
            display_name = node['display_name']
            
            # Clean up container node names (non-Pod types)
            if node['type'] not in ['Pod', 'Suspend', 'Skipped']:
                display_name = clean_display_name(display_name)
            
            # Build the node label
            node_label = f"{symbol} {display_name}"
            
            # Add statusOutput for any failed node that has it
            if node['phase'] == 'Failed' and workflow_data:
                status_output = get_step_status_output(workflow_data, node['id'])
                if status_output:
                    node_label += f": {status_output}"
                    logger.info(f"Added statusOutput for {display_name}: {status_output}")
                else:
                    # Show phase for nodes without statusOutput
                    node_label += f" ({node['phase']})"
            else:
                # Show phase for other nodes
                if node['phase'] != 'Succeeded':
                    node_label += f" ({node['phase']})"
            
            # Add node to tree
            node_tree = parent_tree.add(node_label)
            
            # Add deep check info for the last failed node
            if show_deep_check and deep_check_data and node['id'] == last_failed_node_id:
                logger.info(f"Adding deep check for last failed node: {display_name}")
                check_data = deep_check_data[node['id']]
                status_results = check_data['status']
                
                # Show live status from API calls
                if 'snapshot' in status_results:
                    snap_result = status_results['snapshot']
                    if snap_result.get('success'):
                        # Handle multi-line output
                        value = snap_result['value']
                        lines = value.split('\n')
                        deep_check_tree = node_tree.add(f"⚡ {lines[0]}")
                        for line in lines[1:]:
                            if line.strip():
                                deep_check_tree.add(line)
                    else:
                        error_msg = snap_result.get('error', 'Unknown error')
                        node_tree.add(f"⚡ Error: {error_msg}")
                
                if 'backfill' in status_results:
                    backfill_result = status_results['backfill']
                    if backfill_result.get('success'):
                        node_tree.add(f"⚡ Backfill: {backfill_result['status']}")
                    else:
                        error_msg = backfill_result.get('error', 'Unknown error')
                        node_tree.add(f"⚡ Error: {error_msg}")
            
            # Recursively add children
            if node['children']:
                add_nodes_to_tree(node['children'], node_tree)
    
    add_nodes_to_tree(sorted_nodes, tree)
    console.print(tree)
