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
        logger.info(f"Backfill status result: {result}")
        
        if result.success:
            status_enum, message = result.value
            logger.debug(f"Backfill status: {status_enum}, message: {message}")
            return {"success": True, "status": str(status_enum), "message": message}
        return {"success": False, "error": str(result.value)}
    except Exception as e:
        logger.error(f"Exception in backfill status check: {e}")
        return {"error": str(e)}


def _get_node_input_parameter(node, param_name):
    """Get a parameter value from a node's inputs."""
    inputs = node.get('inputs', {})
    parameters = inputs.get('parameters', [])
    for param in parameters:
        if param.get('name') == param_name:
            return param.get('value')
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
                deep_check_data = _calculate_deep_check_results(workflow_data, deep_check)

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
                                    deep_check_data = _calculate_deep_check_results(workflow_data, deep_check)
                            
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
                                        deep_check_data = _calculate_deep_check_results(workflow_data, deep_check)
                                
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
                        deep_check_data = _calculate_deep_check_results(workflow_data, deep_check)
                
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
    if not workflow_data:
        raise RuntimeError("No workflow_data found")
    tree_nodes = build_nested_workflow_tree(workflow_data)

    # Apply smart filtering (keeps parallel work, flattens linear chains)
    tree_nodes = filter_tree_nodes(tree_nodes)

    click.echo("")
    display_workflow_tree(tree_nodes, deep_check_data, workflow_data, show_deep_check)

    # Add message about viewing step outputs for active workflows
    if show_output_hint and phase in ('Running', 'Pending'):
        click.echo("")
        click.echo(f"To view step outputs, run: workflow output {name}")


def _calculate_deep_check_results(workflow_data, deep_check):
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
        
        # Look for nodes with configContents parameter
        if _get_node_input_parameter(node, 'configContents') and node_type == "Pod":
            config_contents = _get_node_input_parameter(node, 'configContents')
            group_name = _get_node_input_parameter(node, 'groupName') or 'default'
            
            logger.info(f"Found status check node: {display_name} (phase: {phase}, started: {started_at}, group: {group_name})")
            status_check_nodes.append({
                'node_id': node_id,
                'display_name': display_name,
                'phase': phase,
                'started_at': started_at,
                'type': 'snapshot' if 'checkSnapshotCompletion' in display_name else 'backfill',
                'group_name': group_name,
                'config_contents': config_contents
            })
    
    # Sort by start time to find the chronologically last one
    if status_check_nodes:
        logger.info(f"Found {len(status_check_nodes)} total status check nodes")
        status_check_nodes.sort(key=lambda n: n['started_at'] or '0000-00-00T00:00:00Z')
        
        # Group nodes by group name and find the last failed one in each group
        groups = {}
        for check_node in status_check_nodes:
            group_name = check_node['group_name']
            if group_name not in groups:
                groups[group_name] = []
            groups[group_name].append(check_node)
        
        # For each group, find the last failed node and run deep check only for that one
        for group_name, group_nodes in groups.items():
            # Find the last failed node in this group
            failed_nodes = [n for n in group_nodes if n['phase'] != 'Succeeded']
            if failed_nodes:
                # Sort by start time and take the last one
                failed_nodes.sort(key=lambda n: n['started_at'] or '0000-00-00T00:00:00Z')
                last_failed = failed_nodes[-1]
                
                logger.info(f"Running deep check for last failed node in group {group_name}: {last_failed['display_name']} ({last_failed['phase']})")
                
                # Use config directly from the node (no tree walking needed)
                config_contents = last_failed['config_contents']
                logger.info("Found config contents, converting with jq")
                services_config = _convert_config_with_jq(config_contents)
                if services_config:
                    config_dict = yaml.safe_load(services_config)
                    check_tasks.append({
                        'node_id': last_failed['node_id'],
                        'step_name': last_failed['display_name'],
                        'type': last_failed['type'],
                        'env': Environment(config=config_dict)
                    })
            else:
                logger.info(f"All status checks succeeded in group {group_name}, skipping deep check")
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
