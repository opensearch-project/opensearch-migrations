"""Status command for workflow CLI - shows detailed status of workflows."""

import logging
import os
import click
from rich.console import Console
from rich.tree import Tree
from pprint import pprint
import json
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import sys
import yaml

# Add console_link to path for direct function calls
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService
from ..tree_utils import (
    build_nested_workflow_tree, 
    filter_tree_nodes, 
    display_workflow_tree
)
from console_link.environment import Environment
from console_link.middleware import snapshot as snapshot_middleware
from console_link.middleware import backfill as backfill_middleware

logger = logging.getLogger(__name__)


def _get_step_config_contents(workflow_data, step_name):
    """Extract configContents from a workflow step's inputs."""
    logger.info(f"Looking for configContents in step: {step_name}")
    nodes = workflow_data.get("status", {}).get("nodes", {})
    logger.debug(f"Found {len(nodes)} nodes in workflow")
    
    for node_id, node in nodes.items():
        display_name = node.get("displayName", "")
        if step_name in display_name:
            logger.info(f"Found matching node {node_id} with display name: {display_name}")
            inputs = node.get("inputs", {})
            parameters = inputs.get("parameters", [])
            logger.debug(f"Node has {len(parameters)} input parameters")
            
            for param in parameters:
                param_name = param.get("name")
                if param_name == "configContents":
                    logger.info(f"Found configContents parameter in node {node_id}")
                    return param.get("value")
                logger.debug(f"Skipping parameter: {param_name}")
    
    logger.warning(f"No configContents found for step: {step_name}")
    return None


def _get_step_status_output(workflow_data, node_id):
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


def _convert_config_with_jq(config_contents):
    """Convert workflow config to services config using jq."""
    logger.info("Converting config with jq")
    logger.debug(f"Input config length: {len(config_contents)} characters")
    
    # Get jq script path from environment variable
    jq_script_path = os.environ.get('WORKFLOW_CONFIG_JQ_SCRIPT', 'workflowConfigToServicesConfig.jq')
    
    try:
        result = subprocess.run([
            'jq', '-f', jq_script_path
        ], input=config_contents, text=True, capture_output=True)
        
        if result.returncode == 0:
            logger.info("jq conversion successful")
            logger.debug(f"Output config length: {len(result.stdout)} characters")
            return result.stdout
        else:
            logger.error(f"jq conversion failed with return code {result.returncode}")
            logger.error(f"jq stderr: {result.stderr}")
            return None
    except Exception as e:
        logger.error(f"Error running jq: {e}")
        return None


def _check_snapshot_status(env):
    """Check snapshot status for a given environment."""
    logger.info("Starting snapshot status check")
    if not env.snapshot:
        logger.warning("No snapshot configured in environment")
        return {"error": "No snapshot configured"}
    
    logger.info(f"Snapshot config: {env.snapshot}")
    logger.info(f"Source cluster: {env.source_cluster}")
    
    try:
        logger.info("Calling snapshot middleware status")
        result = snapshot_middleware.status(env.snapshot, deep_check=True)
        logger.info(f"Snapshot status result: success={result.success}, value={result.value}")
        return {"success": result.success, "value": result.value}
    except Exception as e:
        logger.error(f"Exception in snapshot status check: {e}")
        return {"error": str(e)}


def _check_backfill_status(env):
    """Check backfill status for a given environment."""
    logger.info("Starting backfill status check")
    if not env.backfill:
        logger.warning("No backfill configured in environment")
        return {"error": "No backfill configured"}
    
    logger.info(f"Backfill config: {env.backfill}")
    logger.info(f"Target cluster: {env.target_cluster}")
    
    try:
        logger.info("Calling backfill middleware status")
        result = backfill_middleware.status(env.backfill, deep_check=True)
        logger.info(f"Backfill status result: success={result.success}, value={result.value}")
        
        if result.success:
            status_enum, message = result.value
            logger.debug(f"Backfill status: {status_enum}, message: {message}")
            return {"success": True, "status": str(status_enum), "message": message}
        return {"success": False, "error": str(result.value)}
    except Exception as e:
        logger.error(f"Exception in backfill status check: {e}")
        return {"error": str(e)}


def _walk_up_for_config(nodes, start_node_id, param_name):
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


def _walk_up_for_config(nodes, start_node_id, param_name):
    """Get deep check data for relevant steps using proper tree walking."""
    if not deep_check:
        logger.info("Deep check disabled, skipping")
        return {}
    
    logger.info("Starting deep check data collection with tree walking")
    nodes = workflow_data.get("status", {}).get("nodes", {})
    check_tasks = []
    
    # Find checkSnapshotCompletion and checkHistoricalBackfillCompletion nodes
    for node_id, node in nodes.items():
        display_name = node.get("displayName", "")
        phase = node.get("phase", "")
        node_type = node.get("type", "")
        
        # Skip completed steps
        if phase == "Succeeded":
            continue
            
        # Look for status check nodes
        if "checkSnapshotCompletion" in display_name and node_type == "Pod":
            logger.info(f"Found checkSnapshotCompletion node: {display_name}")
            
            # Walk up the tree to find config
            config_contents = _walk_up_for_config(nodes, node_id, "configContents")
            if config_contents:
                logger.info("Found config contents, converting with jq")
                services_config = _convert_config_with_jq(config_contents)
                if services_config:
                    config_dict = yaml.safe_load(services_config)
                    check_tasks.append({
                        'node_id': node_id,
                        'step_name': display_name,
                        'type': 'snapshot',
                        'env': Environment(config=config_dict)
                    })
        
        elif "checkHistoricalBackfillCompletion" in display_name and node_type == "Pod":
            logger.info(f"Found checkHistoricalBackfillCompletion node: {display_name}")
            
            # Walk up the tree to find config
            config_contents = _walk_up_for_config(nodes, node_id, "configContents")
            if config_contents:
                logger.info("Found config contents, converting with jq")
                services_config = _convert_config_with_jq(config_contents)
                if services_config:
                    config_dict = yaml.safe_load(services_config)
                    check_tasks.append({
                        'node_id': node_id,
                        'step_name': display_name,
                        'type': 'backfill',
                        'env': Environment(config=config_dict)
                    })
    
    logger.info(f"Found {len(check_tasks)} tasks for deep checking")
    
    # Run all checks in parallel
    deep_check_results = {}
    
    if check_tasks:
        with ThreadPoolExecutor(max_workers=len(check_tasks) * 2) as executor:
            futures = {}
            
            for task in check_tasks:
                node_id = task['node_id']
                check_type = task['type']
                env = task['env']
                
                if check_type == 'snapshot':
                    futures[(node_id, 'snapshot')] = executor.submit(_check_snapshot_status, env)
                elif check_type == 'backfill':
                    futures[(node_id, 'backfill')] = executor.submit(_check_backfill_status, env)
            
            # Collect results
            for (node_id, check_type), future in futures.items():
                task = next(t for t in check_tasks if t['node_id'] == node_id)
                
                if node_id not in deep_check_results:
                    deep_check_results[node_id] = {
                        'step_name': task['step_name'],
                        'type': task['type'],
                        'status': {}
                    }
                
                try:
                    result = future.result()
                    deep_check_results[node_id]['status'][check_type] = result
                except Exception as e:
                    logger.error(f"Error getting result for {check_type} check: {e}")
                    deep_check_results[node_id]['status'][check_type] = {"error": str(e)}
    
    return deep_check_results


def _walk_up_for_config(nodes, start_node_id, param_name):
    """Walk up the tree to find a parameter in parent nodes."""
    current_id = start_node_id
    visited = set()
    
    while current_id and current_id not in visited:
        visited.add(current_id)
        node = nodes.get(current_id)
        if not node:
            break
            
        # Check inputs for the parameter
        inputs = node.get("inputs", {})
        parameters = inputs.get("parameters", [])
        for param in parameters:
            if param.get("name") == param_name:
                logger.info(f"Found {param_name} in node {current_id}")
                return param.get("value")
        
        # Move to parent
        current_id = node.get("boundaryID")
    
    logger.warning(f"No {param_name} found walking up from {start_node_id}")
    return None


@click.command(name="status")
@click.argument('workflow_name', required=False)
@click.option(
    '--argo-server',
    default=f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
    f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}",
    help='Argo Server URL (default: ARGO_SERVER env var, or ARGO_SERVER_SERVICE_HOST:ARGO_SERVER_SERVICE_PORT)'
)
@click.option(
    '--namespace',
    default='ma',
    help='Kubernetes namespace for the workflow (default: ma)'
)
@click.option(
    '--insecure',
    is_flag=True,
    default=False,
    help='Skip TLS certificate verification'
)
@click.option(
    '--token',
    help='Bearer token for authentication'
)
@click.option(
    '--all',
    'show_all',
    is_flag=True,
    default=False,
    help='Show all workflows including completed ones (default: only running)'
)
@click.option(
    '--deep-check',
    is_flag=True,
    default=False,
    help='Run deep status checks for snapshot and backfill steps'
)
@click.pass_context
def status_command(ctx, workflow_name, argo_server, namespace, insecure, token, show_all, deep_check):
    """Show detailed status of workflows.

    Displays workflow progress, completed steps, and approval status.
    By default, only shows running workflows. Use --all to see completed workflows too.

    Example:
        workflow status
        workflow status my-workflow
        workflow status --all
    """

    logger.info(f"Status command called with deep_check={deep_check}")
    click.echo(f"DEBUG: deep_check flag = {deep_check}")

    try:
        service = WorkflowService()

        if workflow_name:
            logger.info(f"Getting status for specific workflow: {workflow_name}")
            # Show detailed status for specific workflow
            result = service.get_workflow_status(
                workflow_name=workflow_name,
                namespace=namespace,
                argo_server=argo_server,
                token=token,
                insecure=insecure
            )

            if not result['success']:
                click.echo(f"Error: {result['error']}", err=True)
                ctx.exit(ExitCode.FAILURE.value)

            # Always get workflow data for proper display
            workflow_data = None
            headers = {"Authorization": f"Bearer {token}"} if token else {}
            url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
            import requests
            response = requests.get(url, headers=headers, verify=not insecure)
            if response.status_code == 200:
                workflow_data = response.json()

            # Get deep check data if requested
            deep_check_data = {}
            if deep_check and workflow_data:
                deep_check_data = _get_deep_check_data(workflow_data, deep_check)

            _display_workflow_status(result, deep_check_data=deep_check_data, workflow_data=workflow_data, show_deep_check=deep_check)
        else:
            # List all workflows with status
            list_result = service.list_workflows(
                namespace=namespace,
                argo_server=argo_server,
                token=token,
                insecure=insecure,
                exclude_completed=not show_all
            )

            if not list_result['success']:
                click.echo(f"Error: {list_result['error']}", err=True)
                ctx.exit(ExitCode.FAILURE.value)

            if list_result['count'] == 0:
                if show_all:
                    click.echo(f"No workflows found in namespace {namespace}")
                    return
                else:
                    # No active workflows, try to get the last completed workflow
                    click.echo(f"No running workflows found in namespace {namespace}")

                    # Try to get completed workflows
                    completed_result = service.list_workflows(
                        namespace=namespace,
                        argo_server=argo_server,
                        token=token,
                        insecure=insecure,
                        exclude_completed=False
                    )

                    if completed_result['success'] and completed_result['count'] > 0:
                        # Get the most recent completed workflow by finish time
                        click.echo("\nShowing last completed workflow:")
                        click.echo("")

                        # Get status for all workflows to find the most recent by finish time
                        workflow_statuses = []
                        for wf_name in completed_result['workflows']:
                            wf_result = service.get_workflow_status(
                                workflow_name=wf_name,
                                namespace=namespace,
                                argo_server=argo_server,
                                token=token,
                                insecure=insecure
                            )
                            if wf_result['success'] and wf_result['finished_at']:
                                workflow_statuses.append(wf_result)

                        # Sort by finished_at timestamp (most recent first)
                        if workflow_statuses:
                            workflow_statuses.sort(key=lambda x: x['finished_at'], reverse=True)
                            
                            # Get workflow data and deep check data for latest completed workflow
                            latest_result = workflow_statuses[0]
                            workflow_data = None
                            deep_check_data = {}
                            
                            headers = {"Authorization": f"Bearer {token}"} if token else {}
                            url = f"{argo_server}/api/v1/workflows/{namespace}/{latest_result['workflow_name']}"
                            
                            import requests
                            response = requests.get(url, headers=headers, verify=not insecure)
                            
                            if response.status_code == 200:
                                workflow_data = response.json()
                                if deep_check:
                                    deep_check_data = _get_deep_check_data(workflow_data, deep_check)
                            
                            _display_workflow_status(latest_result, deep_check_data=deep_check_data, workflow_data=workflow_data, show_deep_check=deep_check)
                        elif completed_result['workflows']:
                            # Fallback to first workflow if no finish times available
                            fallback_workflow = completed_result['workflows'][0]
                            result = service.get_workflow_status(
                                workflow_name=fallback_workflow,
                                namespace=namespace,
                                argo_server=argo_server,
                                token=token,
                                insecure=insecure
                            )
                            if result['success']:
                                # Get workflow data and deep check data
                                workflow_data = None
                                deep_check_data = {}
                                
                                headers = {"Authorization": f"Bearer {token}"} if token else {}
                                url = f"{argo_server}/api/v1/workflows/{namespace}/{fallback_workflow}"
                                
                                import requests
                                response = requests.get(url, headers=headers, verify=not insecure)
                                
                                if response.status_code == 200:
                                    workflow_data = response.json()
                                    if deep_check:
                                        deep_check_data = _get_deep_check_data(workflow_data, deep_check)
                                
                                _display_workflow_status(result, deep_check_data=deep_check_data, workflow_data=workflow_data, show_deep_check=deep_check)

                        click.echo("\nUse --all to see all completed workflows")
                    else:
                        click.echo("Use --all to see completed workflows")
                    return

            click.echo(f"Found {list_result['count']} workflow(s) in namespace {namespace}:")
            click.echo("")

            # Get status for all workflows to sort chronologically
            workflow_statuses = []
            for wf_name in list_result['workflows']:
                result = service.get_workflow_status(
                    workflow_name=wf_name,
                    namespace=namespace,
                    argo_server=argo_server,
                    token=token,
                    insecure=insecure
                )
                if result['success']:
                    workflow_statuses.append(result)

            # Sort workflows chronologically by start time (oldest first)
            workflow_statuses.sort(key=lambda x: x['started_at'] or '')

            for result in workflow_statuses:
                # Get workflow data for proper display (both regular and deep check)
                workflow_data = None
                deep_check_data = {}
                headers = {"Authorization": f"Bearer {token}"} if token else {}
                url = f"{argo_server}/api/v1/workflows/{namespace}/{result['workflow_name']}"
                
                import requests
                response = requests.get(url, headers=headers, verify=not insecure)
                
                if response.status_code == 200:
                    workflow_data = response.json()
                    if deep_check:
                        deep_check_data = _get_deep_check_data(workflow_data, deep_check)
                
                _display_workflow_status(result, deep_check_data=deep_check_data, workflow_data=workflow_data, show_deep_check=deep_check)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)


def _get_phase_symbol(phase: str) -> str:
    """Get symbol for workflow phase.

    Args:
        phase: Workflow phase

    Returns:
        Symbol character for the phase
    """
    phase_symbols = {
        'Running': '*',
        'Succeeded': '+',
        'Failed': '-',
        'Error': '-',
        'Pending': '>',
        'Stopped': 'X',
    }
    return phase_symbols.get(phase, '?')


def _get_step_symbol(step_phase: str, step_type: str) -> str:
    """Get symbol for workflow step.

    Args:
        step_phase: Step phase
        step_type: Step type

    Returns:
        Symbol string for the step
    """
    if step_phase == 'Succeeded':
        return '  +'
    elif step_phase == 'Running':
        return '  |' if step_type == 'Suspend' else '  *'
    elif step_phase in ('Failed', 'Error'):
        return '  -'
    elif step_phase == 'Pending':
        return '  >'
    elif step_phase == 'Skipped':
        return '  ~'
    else:
        return '  ?'


def _display_workflow_header(name: str, phase: str, started_at: str, finished_at: str):
    """Display workflow header information.

    Args:
        name: Workflow name
        phase: Workflow phase
        started_at: Start timestamp
        finished_at: Finish timestamp
    """
    phase_symbol = _get_phase_symbol(phase)
    click.echo(f"[{phase_symbol}] Workflow: {name}")
    click.echo(f"  Phase: {phase}")
    if started_at:
        click.echo(f"  Started: {started_at}")
    if finished_at:
        click.echo(f"  Finished: {finished_at}")


def _get_step_rich_label(node: dict) -> str:
    """Get rich-formatted label for a workflow step node.

    Args:
        node: WorkflowNode dictionary

    Returns:
        Rich-formatted string with color and styling
    """
    step_name = node['display_name']
    step_phase = node['phase']
    step_type = node['type']

    # Color based on phase
    if step_phase == 'Succeeded':
        color = "green"
        symbol = "✓"
    elif step_phase == 'Running':
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

    # Special handling for Suspend steps
    if step_type == 'Suspend':
        if step_phase == 'Running':
            return f"[{color}]{symbol} {step_name} - WAITING FOR APPROVAL[/{color}]"
        elif step_phase == 'Succeeded':
            return f"[{color}]{symbol} {step_name} (Approved)[/{color}]"
        else:
            return f"[{color}]{symbol} {step_name} ({step_phase})[/{color}]"
    # Special handling for Skipped steps with approval-related names
    elif step_phase == 'Skipped' and 'approval' in step_name.lower():
        return f"[{color}]{symbol} {step_name} (Not Required)[/{color}]"
    else:
        return f"[{color}]{symbol} {step_name} ({step_phase})[/{color}]"


def _display_workflow_tree(step_tree: list):
    """Display workflow steps in tree format using Rich.

    Args:
        step_tree: List of WorkflowNode dictionaries with hierarchy
    """
    if not step_tree:
        return

    console = Console()
    tree = Tree("[bold]Workflow Steps[/bold]")

    # Group nodes by depth to build the tree structure
    nodes_by_depth = {}
    for node in step_tree:
        depth = node['depth']
        if depth not in nodes_by_depth:
            nodes_by_depth[depth] = []
        nodes_by_depth[depth].append(node)

    # Build tree level by level
    node_to_tree = {}

    for depth in sorted(nodes_by_depth.keys()):
        for node in nodes_by_depth[depth]:
            label = _get_step_rich_label(node)

            if depth == 0:
                # Root level nodes
                node_to_tree[node['id']] = tree.add(label)
            else:
                # Find parent and add as child
                parent_id = node.get('parent')
                if parent_id and parent_id in node_to_tree:
                    node_to_tree[node['id']] = node_to_tree[parent_id].add(label)
                else:
                    # Fallback: add to root if parent not found
                    node_to_tree[node['id']] = tree.add(label)

    click.echo("")
    console.print(tree)


def _display_workflow_steps(steps: list, step_tree: list = None):
    """Display workflow steps.

    Args:
        steps: List of step dictionaries (backward compatibility)
        step_tree: Optional list of WorkflowNode dictionaries with hierarchy
    """
    # Use tree display if available
    if step_tree:
        _display_workflow_tree(step_tree)
        return

    # Fallback to flat display
    if not steps:
        return

    click.echo("\n  Steps:")
    for step in steps:
        step_name = step['name']
        step_phase = step['phase']
        step_type = step['type']

        symbol = _get_step_symbol(step_phase, step_type)

        # Special handling for Suspend steps
        if step_type == 'Suspend':
            if step_phase == 'Running':
                click.echo(f"{symbol} {step_name} - WAITING FOR APPROVAL")
            elif step_phase == 'Succeeded':
                click.echo(f"{symbol} {step_name} (Approved)")
            else:
                click.echo(f"{symbol} {step_name} ({step_phase})")
        # Special handling for Skipped steps with approval-related names
        elif step_phase == 'Skipped' and 'approval' in step_name.lower():
            click.echo(f"{symbol} {step_name} (Not Required)")
        else:
            click.echo(f"{symbol} {step_name} ({step_phase})")


def _display_workflow_status(result: dict, show_output_hint: bool = True, deep_check_data: dict = None, workflow_data: dict = None, show_deep_check: bool = False):
    """Display formatted workflow status.

    Args:
        result: WorkflowStatusResult dict from get_workflow_status
        show_output_hint: Whether to show the hint about viewing step outputs (default: True)
        deep_check_data: Optional deep check results for steps
    """

    # print(json.dumps(result))

    name = result['workflow_name']
    phase = result['phase']
    started_at = result['started_at']
    finished_at = result['finished_at']

    _display_workflow_header(name, phase, started_at, finished_at)

    # Build and display nested tree
    if workflow_data:
        tree_nodes = build_nested_workflow_tree(workflow_data)
        
        # Apply smart filtering (keeps parallel work, flattens linear chains)
        tree_nodes = filter_tree_nodes(tree_nodes)
        
        click.echo("")
        display_workflow_tree(tree_nodes, deep_check_data, workflow_data, show_deep_check)
    else:
        # Fallback to old flat display - but this shouldn't happen anymore
        step_tree = result.get('step_tree', [])
        _display_workflow_steps(result.get('steps', []), step_tree)

    # Add message about viewing step outputs for active workflows
    if show_output_hint and phase in ('Running', 'Pending'):
        click.echo("")
        click.echo(f"To view step outputs, run: workflow output {name}")


def _get_deep_check_data(workflow_data, deep_check):
    """Get deep check data for relevant steps using proper tree walking."""
    if not deep_check:
        logger.info("Deep check disabled, skipping")
        return {}
    
    logger.info("Starting deep check data collection with tree walking")
    nodes = workflow_data.get("status", {}).get("nodes", {})
    check_tasks = []
    
    # Find checkSnapshotCompletion and checkHistoricalBackfillCompletion nodes
    status_check_nodes = []
    for node_id, node in nodes.items():
        display_name = node.get("displayName", "")
        phase = node.get("phase", "")
        node_type = node.get("type", "")
        started_at = node.get("startedAt", "")
        
        # Look for status check nodes
        if (("checkSnapshotCompletion" in display_name or "checkHistoricalBackfillCompletion" in display_name)
            and node_type == "Pod"):
            logger.info(f"Found status check node: {display_name} (phase: {phase}, started: {started_at})")
            status_check_nodes.append({
                'node_id': node_id,
                'display_name': display_name,
                'phase': phase,
                'started_at': started_at,
                'type': 'snapshot' if 'checkSnapshotCompletion' in display_name else 'backfill'
            })
    
    # Sort by start time to find the chronologically last one
    if status_check_nodes:
        logger.info(f"Found {len(status_check_nodes)} total status check nodes")
        status_check_nodes.sort(key=lambda n: n['started_at'] or '0000-00-00T00:00:00Z')
        last_check = status_check_nodes[-1]
        
        logger.info(f"Last status check: {last_check['display_name']} ({last_check['phase']})")
        
        # Only run deep check if the last status check was not successful
        if last_check['phase'] != 'Succeeded':
            logger.info(f"Last status check failed, running deep check for: {last_check['display_name']}")
            
            # Walk up the tree to find config
            config_contents = _walk_up_for_config(nodes, last_check['node_id'], "configContents")
            if config_contents:
                logger.info("Found config contents, converting with jq")
                services_config = _convert_config_with_jq(config_contents)
                if services_config:
                    config_dict = yaml.safe_load(services_config)
                    check_tasks.append({
                        'node_id': last_check['node_id'],
                        'step_name': last_check['display_name'],
                        'type': last_check['type'],
                        'env': Environment(config=config_dict)
                    })
        else:
            logger.info(f"Last status check succeeded, skipping deep check for {last_check['display_name']} (type: {last_check['type']})")
    else:
        logger.info("No status check nodes found")
    
    logger.info(f"Found {len(check_tasks)} tasks for deep checking")
    
    # Run all checks in parallel
    deep_check_results = {}
    
    if check_tasks:
        with ThreadPoolExecutor(max_workers=len(check_tasks) * 2) as executor:
            futures = {}
            
            for task in check_tasks:
                node_id = task['node_id']
                check_type = task['type']
                env = task['env']
                
                if check_type == 'snapshot':
                    futures[(node_id, 'snapshot')] = executor.submit(_check_snapshot_status, env)
                elif check_type == 'backfill':
                    futures[(node_id, 'backfill')] = executor.submit(_check_backfill_status, env)
            
            # Collect results
            for (node_id, check_type), future in futures.items():
                task = next(t for t in check_tasks if t['node_id'] == node_id)
                
                if node_id not in deep_check_results:
                    deep_check_results[node_id] = {
                        'step_name': task['step_name'],
                        'type': task['type'],
                        'status': {}
                    }
                
                try:
                    result = future.result()
                    deep_check_results[node_id]['status'][check_type] = result
                except Exception as e:
                    logger.error(f"Error getting result for {check_type} check: {e}")
                    deep_check_results[node_id]['status'][check_type] = {"error": str(e)}
    
    return deep_check_results
