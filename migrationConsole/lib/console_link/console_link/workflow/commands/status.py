"""Status command for workflow CLI - shows detailed status of workflows."""

import logging
import os
import sys
import json
import subprocess
import tempfile
import yaml
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Dict, List, Any, Optional, Tuple

import click
import requests
from rich.console import Console

# Add console_link to path for direct function calls
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService
from ..tree_utils import (
    build_nested_workflow_tree, 
    filter_tree_nodes, 
    display_workflow_tree,
    get_node_input_parameter,
    WorkflowDisplayer
)
from console_link.environment import Environment
from console_link.middleware import snapshot as snapshot_middleware
from console_link.middleware import backfill as backfill_middleware

logger = logging.getLogger(__name__)


class StatusCommandHandler:
    """Main orchestrator for status command operations."""
    
    def __init__(self, service: WorkflowService, token: Optional[str] = None):
        self.service = service
        self.data_fetcher = WorkflowDataFetcher(service, token)
        self.sorter = WorkflowSorter()
        self.displayer = StatusWorkflowDisplayer()
        self.deep_check_processor = DeepCheckProcessor(ConfigConverter())

    def handle_status_command(self, workflow_name: Optional[str], argo_server: str, 
                            namespace: str, token: Optional[str], insecure: bool, 
                            show_all: bool, deep_check: bool) -> None:
        """Handle the main status command logic."""
        try:
            if workflow_name:
                self._handle_single_workflow(workflow_name, argo_server, namespace, 
                                           insecure, deep_check)
            else:
                self._handle_workflow_list(show_all, argo_server, namespace, 
                                         insecure, deep_check)
        except Exception as e:
            click.echo(f"Error: {str(e)}", err=True)
            raise click.Abort()

    def _handle_single_workflow(self, workflow_name: str, argo_server: str, 
                              namespace: str, insecure: bool, deep_check: bool) -> None:
        """Handle status display for a specific workflow."""
        result, workflow_data = self.data_fetcher.get_workflow_data(
            workflow_name, argo_server, namespace, insecure)
        
        if not result['success']:
            click.echo(f"Error: {result['error']}", err=True)
            raise click.Abort()

        self._display_workflow_with_tree(result, workflow_data, deep_check)

    def _handle_workflow_list(self, show_all: bool, argo_server: str, namespace: str, 
                            insecure: bool, deep_check: bool) -> None:
        """Handle status display for workflow list."""
        workflow_statuses = self.data_fetcher.list_workflows_with_status(
            argo_server, namespace, insecure, exclude_completed=not show_all)

        if not workflow_statuses:
            self._handle_no_workflows(show_all, argo_server, namespace, insecure, deep_check)
            return

        click.echo(f"Found {len(workflow_statuses)} workflow(s) in namespace {namespace}:")
        click.echo("")

        sorted_workflows = self.sorter.sort_workflows_chronologically(workflow_statuses)
        for result in sorted_workflows:
            _, workflow_data = self.data_fetcher.get_workflow_data(
                result['workflow_name'], argo_server, namespace, insecure)
            self._display_workflow_with_tree(result, workflow_data, deep_check)

    def _handle_no_workflows(self, show_all: bool, argo_server: str, namespace: str, 
                           insecure: bool, deep_check: bool) -> None:
        """Handle case when no active workflows are found."""
        if show_all:
            click.echo(f"No workflows found in namespace {namespace}")
            return

        click.echo(f"No running workflows found in namespace {namespace}")
        
        # Try to show most recent completed workflow
        all_workflows = self.data_fetcher.list_workflows_with_status(
            argo_server, namespace, insecure, exclude_completed=False)
        
        if all_workflows:
            most_recent = self.sorter.find_most_recent_completed(all_workflows)
            if most_recent:
                click.echo("\nShowing last completed workflow:")
                click.echo("")
                _, workflow_data = self.data_fetcher.get_workflow_data(
                    most_recent['workflow_name'], argo_server, namespace, insecure)
                self._display_workflow_with_tree(most_recent, workflow_data, deep_check)
            
            click.echo("\nUse --all to see all completed workflows")
        else:
            click.echo("Use --all to see completed workflows")

    def _display_workflow_with_tree(self, result: Dict[str, Any], workflow_data: Dict[str, Any], 
                                  deep_check: bool) -> None:
        """Display workflow using tree structure with optional deep checks."""
        # Build tree
        tree_nodes = build_nested_workflow_tree(workflow_data)
        
        # Run deep checks if requested
        deep_check_data = {}
        if deep_check:
            deep_check_data = self.deep_check_processor.enrich_tree_with_deep_checks(
                tree_nodes, workflow_data)
        
        # Filter tree for display
        filtered_tree = filter_tree_nodes(tree_nodes)
        
        # Display
        self.displayer.display_workflow_status(
            result['workflow_name'], result['phase'], result['started_at'], 
            result['finished_at'], filtered_tree, deep_check_data, deep_check, workflow_data)


class WorkflowDataFetcher:
    """Handles all workflow data retrieval operations."""
    
    def __init__(self, service: WorkflowService, token: Optional[str] = None):
        self.service = service
        self.token = token

    def get_workflow_data(self, workflow_name: str, argo_server: str, namespace: str, 
                         insecure: bool) -> Tuple[Dict[str, Any], Dict[str, Any]]:
        """Get workflow status and raw workflow data."""
        result = self.service.get_workflow_status(
            workflow_name=workflow_name, namespace=namespace, argo_server=argo_server,
            token=self.token, insecure=insecure)
        
        workflow_data = self._fetch_raw_workflow_data(
            workflow_name, argo_server, namespace, insecure)
        
        return result, workflow_data

    def list_workflows_with_status(self, argo_server: str, namespace: str, 
                                 insecure: bool, exclude_completed: bool) -> List[Dict[str, Any]]:
        """List workflows and get their status data."""
        list_result = self.service.list_workflows(
            namespace=namespace, argo_server=argo_server, token=self.token, 
            insecure=insecure, exclude_completed=exclude_completed)

        if not list_result['success'] or list_result['count'] == 0:
            return []

        workflow_statuses = []
        for wf_name in list_result['workflows']:
            result = self.service.get_workflow_status(
                workflow_name=wf_name, namespace=namespace, argo_server=argo_server,
                token=self.token, insecure=insecure)
            if result['success']:
                workflow_statuses.append(result)

        return workflow_statuses

    def _fetch_raw_workflow_data(self, workflow_name: str, argo_server: str, 
                               namespace: str, insecure: bool) -> Dict[str, Any]:
        """Fetch raw workflow data from Argo API."""
        headers = {"Authorization": f"Bearer {self.token}"} if self.token else {}
        url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
        
        response = requests.get(url, headers=headers, verify=not insecure)
        return response.json() if response.status_code == 200 else {}


class WorkflowSorter:
    """Sort a group of workflow (not the tasks within them)."""
    
    @staticmethod
    def sort_workflows_chronologically(workflow_statuses: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Sort workflows by start time (oldest first)."""
        return sorted(workflow_statuses, key=lambda x: x['started_at'] or '')

    @staticmethod
    def find_most_recent_completed(workflow_statuses: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        """Find the most recently completed workflow."""
        completed_workflows = [wf for wf in workflow_statuses if wf.get('finished_at')]
        if not completed_workflows:
            return workflow_statuses[0] if workflow_statuses else None
        
        return max(completed_workflows, key=lambda x: x['finished_at'])


class StatusCheckRunner:
    """Dynamic status check runner based on node type."""
    
    @staticmethod
    def run_status_check(env: Environment, node: Dict[str, Any]) -> Dict[str, Any]:
        """Run appropriate status check based on node's displayName."""
        display_name = node.get('display_name', '')
        
        if 'checkSnapshotCompletion' in display_name:
            return StatusCheckRunners._check_snapshot_status(env)
        elif 'checkHistoricalBackfillCompletion' in display_name:
            return StatusCheckRunners._check_backfill_status(env)
        else:
            return {"error": f"Unknown status check type for: {display_name}"}

    @staticmethod
    def _check_snapshot_status(env: Environment) -> Dict[str, Any]:
        """Check snapshot status for environment."""
        if not env.snapshot:
            return {"error": "No snapshot configured"}
        
        try:
            result = snapshot_middleware.status(env.snapshot, deep_check=True)
            return {"success": result.success, "value": result.value}
        except Exception as e:
            return {"error": str(e)}

    @staticmethod
    def _check_backfill_status(env: Environment) -> Dict[str, Any]:
        """Check backfill status for environment."""
        if not env.backfill:
            return {"error": "No backfill configured"}
        
        try:
            result = backfill_middleware.status(env.backfill, deep_check=True)
            if result.success:
                status_enum, message = result.value
                return {"success": True, "status": str(status_enum), "message": message}
            return {"success": False, "error": str(result.value)}
        except Exception as e:
            return {"error": str(e)}


class ConfigConverter:
    """Handles configuration conversion using jq."""
    
    @staticmethod
    def convert_with_jq(config_contents: str) -> Optional[str]:
        """Convert workflow config to services config using jq."""
        jq_script_path = os.environ.get('WORKFLOW_CONFIG_JQ_SCRIPT', 'workflowConfigToServicesConfig.jq')
        
        try:
            result = subprocess.run([
                'jq', '-f', jq_script_path
            ], input=config_contents, text=True, capture_output=True)
            
            return result.stdout if result.returncode == 0 else None
        except Exception as e:
            logger.error(f"Error running jq: {e}")
            return None


class StatusWorkflowDisplayer(WorkflowDisplayer):
    """Status-specific workflow display implementation."""
    
    def display_workflow_status(self, workflow_name: str, phase: str, started_at: str, 
                              finished_at: str, tree_nodes: List[Dict[str, Any]], 
                              deep_check_data: Dict[str, Any], show_deep_check: bool,
                              workflow_data: Dict[str, Any] = None) -> None:
        """Display complete workflow status with tree."""
        self.display_workflow_header(workflow_name, phase, started_at, finished_at)
        click.echo("")
        display_workflow_tree(tree_nodes, deep_check_data, workflow_data or {}, show_deep_check)
        
        if phase in ('Running', 'Pending'):
            click.echo("")
            click.echo(f"To view step outputs, run: workflow output {workflow_name}")

    def display_workflow_header(self, name: str, phase: str, started_at: str, finished_at: str) -> None:
        """Display workflow header information."""
        phase_symbol = self.get_phase_symbol(phase)
        click.echo(f"[{phase_symbol}] Workflow: {name}")
        click.echo(f"  Phase: {phase}")
        if started_at:
            click.echo(f"  Started: {started_at}")
        if finished_at:
            click.echo(f"  Finished: {finished_at}")

    def get_phase_symbol(self, phase: str) -> str:
        """Get symbol for workflow phase."""
        symbols = {
            'Running': '*', 'Succeeded': '+', 'Failed': '-', 'Error': '-',
            'Pending': '>', 'Stopped': 'X'
        }
        return symbols.get(phase, '?')

    def get_step_symbol(self, step_phase: str, step_type: str) -> str:
        """Get symbol for workflow step."""
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
        return '  ?'


class DeepCheckProcessor:
    """Processes deep status checks for workflow nodes using injected status runner."""
    
    def __init__(self, config_converter: ConfigConverter, status_runner: StatusCheckRunner = None):
        self.config_converter = config_converter
        self.status_runner = status_runner or StatusCheckRunner()

    def enrich_tree_with_deep_checks(self, tree_nodes: List[Dict[str, Any]], 
                                   workflow_data: Dict[str, Any]) -> Dict[str, Any]:
        """Add deep check results to tree nodes."""
        status_nodes = self._find_status_check_nodes(tree_nodes)
        if not status_nodes:
            return {}

        failed_nodes = self._get_last_failed_per_group(status_nodes)
        return self._run_deep_checks_parallel(failed_nodes)

    def _find_status_check_nodes(self, tree_nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Find nodes that need status checking."""
        def find_nodes_recursive(nodes):
            result = []
            for node in nodes:
                if self._is_status_check_node(node):
                    result.append(node)
                result.extend(find_nodes_recursive(node.get('children', [])))
            return result
        
        return find_nodes_recursive(tree_nodes)

    def _is_status_check_node(self, node: Dict[str, Any]) -> bool:
        """Check if node is a status check node."""
        display_name = node.get('display_name', '')
        has_config = get_node_input_parameter(node, 'configContents') is not None
        return has_config and node.get('type') == 'Pod' and (
            'checkSnapshotCompletion' in display_name or 
            'checkHistoricalBackfillCompletion' in display_name
        )

    def _get_last_failed_per_group(self, status_nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Get the last failed node per group."""
        groups = {}
        for node in status_nodes:
            group_name = get_node_input_parameter(node, 'groupName') or 'default'
            if group_name not in groups:
                groups[group_name] = []
            groups[group_name].append(node)

        failed_nodes = []
        for group_nodes in groups.values():
            failed_in_group = [n for n in group_nodes if n.get('phase') != 'Succeeded']
            if failed_in_group:
                # Sort by start time and take the last one
                failed_in_group.sort(key=lambda n: n.get('started_at', ''))
                failed_nodes.append(failed_in_group[-1])

        return failed_nodes

    def _run_deep_checks_parallel(self, failed_nodes: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Run deep checks in parallel for failed nodes."""
        if not failed_nodes:
            return {}

        deep_check_results = {}
        with ThreadPoolExecutor(max_workers=len(failed_nodes) * 2) as executor:
            futures = {}
            
            for node in failed_nodes:
                config_contents = get_node_input_parameter(node, 'configContents')
                services_config = self.config_converter.convert_with_jq(config_contents)
                
                if services_config:
                    env = Environment(config=yaml.safe_load(services_config))
                    node_id = node['id']
                    
                    futures[node_id] = executor.submit(self.status_runner.run_status_check, env, node)

            # Collect results
            for node_id, future in futures.items():
                node = next(n for n in failed_nodes if n['id'] == node_id)
                try:
                    result = future.result()
                    deep_check_results[node_id] = {
                        'step_name': node.get('display_name', ''),
                        'status': result
                    }
                except Exception as e:
                    logger.error(f"Error in deep check for {node_id}: {e}")
                    deep_check_results[node_id] = {
                        'step_name': node.get('display_name', ''),
                        'status': {"error": str(e)}
                    }

        return deep_check_results


@click.command(name="status")
@click.argument('workflow_name', required=False)
@click.option(
    '--argo-server',
    default=f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
    f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}",
    help='Argo Server URL (default: ARGO_SERVER env var, or ARGO_SERVER_SERVICE_HOST:ARGO_SERVER_SERVICE_PORT)'
)
@click.option('--namespace', default='ma', help='Kubernetes namespace for the workflow (default: ma)')
@click.option('--insecure', is_flag=True, default=False, help='Skip TLS certificate verification')
@click.option('--token', help='Bearer token for authentication')
@click.option('--all', 'show_all', is_flag=True, default=False, 
              help='Show all workflows including completed ones (default: only running)')
@click.option('--deep-check', is_flag=True, default=False, 
              help='Run deep status checks for snapshot and backfill steps')
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
    try:
        service = WorkflowService()
        handler = StatusCommandHandler(service, token)
        
        handler.handle_status_command(
            workflow_name, argo_server, namespace, token, insecure, show_all, deep_check)
            
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)


# Compatibility function for output.py
def _display_workflow_status(result: dict, show_output_hint: bool = True, deep_check_data: dict = None, 
                           workflow_data: dict = None, show_deep_check: bool = False):
    """Compatibility function for output.py - displays workflow status using new classes."""
    displayer = StatusWorkflowDisplayer()
    
    # If no workflow_data provided, create minimal tree from step_tree in result
    if not workflow_data and 'step_tree' in result:
        tree_nodes = result['step_tree']
    else:
        # Build tree from workflow_data if available
        tree_nodes = build_nested_workflow_tree(workflow_data) if workflow_data else []
        tree_nodes = filter_tree_nodes(tree_nodes)
    
    displayer.display_workflow_status(
        result['workflow_name'], result['phase'], result['started_at'], 
        result['finished_at'], tree_nodes, deep_check_data or {}, show_deep_check, workflow_data)
    
    if show_output_hint and result['phase'] in ('Running', 'Pending'):
        click.echo("")
        click.echo(f"To view step outputs, run: workflow output {result['workflow_name']}")
