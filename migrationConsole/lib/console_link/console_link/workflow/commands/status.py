"""Status command for workflow CLI - shows detailed status of workflows."""

import logging
import os
import sys
import subprocess
import yaml
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Dict, List, Any, Optional

import click
import requests

# Add console_link to path for direct function calls
sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService, ENDING_PHASES
from ..tree_utils import (
    build_nested_workflow_tree,
    filter_tree_nodes,
    display_workflow_tree,
    get_node_input_parameter,
    WorkflowDisplayer
)
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
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
        self.live_check_processor = LiveCheckProcessor(ConfigConverter())

    def handle_status_command(self, workflow_name: Optional[str], argo_server: str,
                              namespace: str, insecure: bool,
                              show_all: bool, live_check: bool) -> None:
        """Handle the main status command logic."""
        try:
            if workflow_name:
                self._handle_single_workflow(workflow_name, argo_server, namespace,
                                             insecure, live_check)
            else:
                self._handle_workflow_list(show_all, argo_server, namespace,
                                           insecure, live_check)
        except click.Abort:
            raise
        except Exception as e:
            click.echo(f"Error: {str(e)}", err=True)
            raise click.Abort()

    def _handle_single_workflow(self, workflow_name: str, argo_server: str,
                                namespace: str, insecure: bool, live_check: bool) -> None:
        """Handle status display for a specific workflow."""
        workflow_data = self.data_fetcher.get_workflow_data(
            workflow_name, argo_server, namespace, insecure)

        if not workflow_data:
            click.echo(f"Error: Could not find workflow {workflow_name}", err=True)
            raise click.Abort()

        self._display_workflow_with_tree(workflow_data, live_check)

    def _handle_workflow_list(self, show_all: bool, argo_server: str, namespace: str,
                              insecure: bool, live_check: bool) -> None:
        """Handle status display for workflow list."""
        workflow_list = self.data_fetcher.list_workflows(
            argo_server, namespace, insecure, exclude_completed=not show_all)

        if not workflow_list:
            self._handle_no_workflows(show_all, argo_server, namespace, insecure, live_check)
            return

        click.echo(f"Found {len(workflow_list)} workflow(s) in namespace {namespace}:")
        click.echo("")

        sorted_workflows = self.sorter.sort_workflows_chronologically(workflow_list)
        logger.info(f"Processing {len(sorted_workflows)} sorted workflows")
        for i, workflow_data in enumerate(sorted_workflows):
            workflow_name = self._get_workflow_name(workflow_data)
            logger.info(
                f"Processing workflow {i + 1}/{len(sorted_workflows)}: "
                f"{workflow_name}"
            )
            self._display_workflow_with_tree(workflow_data, live_check)

    def _handle_no_workflows(self, show_all: bool, argo_server: str, namespace: str,
                             insecure: bool, live_check: bool) -> None:
        """Handle case when no active workflows are found."""
        if show_all:
            click.echo(f"No workflows found in namespace {namespace}")
            return

        click.echo(f"No running workflows found in namespace {namespace}")

        # Try to show most recent completed workflow
        all_workflows = self.data_fetcher.list_workflows(
            argo_server, namespace, insecure, exclude_completed=False)

        if all_workflows:
            most_recent = self.sorter.find_most_recent_completed(all_workflows)
            if most_recent:
                click.echo("\nShowing last completed workflow:\n")
                self._display_workflow_with_tree(most_recent, live_check)

            click.echo("\nUse --all to see all completed workflows")
        else:
            click.echo("Use --all to see completed workflows")

    def _get_workflow_name(self, workflow_data: Dict[str, Any]) -> str:
        """Extract workflow name from workflow data."""
        return workflow_data.get('metadata', {}).get('name', 'unknown')

    def _display_workflow_with_tree(self, workflow_data: Dict[str, Any], live_check: bool) -> None:
        """Display workflow using tree structure with optional live status checks."""
        tree_nodes = build_nested_workflow_tree(workflow_data)
        if live_check:
            self.live_check_processor.enrich_tree_with_live_checks(tree_nodes)
        filtered_tree = filter_tree_nodes(tree_nodes)

        # Extract status info from workflow data
        status = workflow_data.get('status', {})
        metadata = workflow_data.get('metadata', {})

        self.displayer.display_workflow_status(
            metadata.get('name', 'unknown'),
            status.get('phase', 'Unknown'),
            status.get('startedAt'),
            status.get('finishedAt'),
            filtered_tree,
            workflow_data)


class WorkflowDataFetcher:
    """Handles all workflow data retrieval operations."""

    def __init__(self, service: WorkflowService, token: Optional[str] = None):
        self.service = service
        self.token = token

    def get_workflow_data(self, workflow_name: str, argo_server: str, namespace: str,
                          insecure: bool) -> Dict[str, Any]:
        """Get workflow data from Argo API, falling back to archive if not found."""
        headers = {"Authorization": f"Bearer {self.token}"} if self.token else {}
        url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"

        response = requests.get(url, headers=headers, verify=not insecure, timeout=30)
        if response.status_code == 200:
            return response.json()

        # Fall back to archive API
        archived = self.service.fetch_archived_workflow(
            workflow_name, namespace, argo_server, self.token, insecure)
        return archived or {}

    def list_workflows(self, argo_server: str, namespace: str,
                       insecure: bool, exclude_completed: bool) -> List[Dict[str, Any]]:
        """List workflows with full data. Uses list API response directly to avoid N+1 calls."""
        headers = {"Authorization": f"Bearer {self.token}"} if self.token else {}
        url = f"{argo_server}/api/v1/workflows/{namespace}"

        workflows = []
        seen_names = set()

        try:
            response = requests.get(url, headers=headers, verify=not insecure, timeout=30)
            if response.status_code == 200:
                items = response.json().get("items") or []
                for item in items:
                    name = item.get("metadata", {}).get("name", "")
                    if not name:
                        continue
                    phase = item.get("status", {}).get("phase", "Unknown")
                    if exclude_completed and phase in ENDING_PHASES:
                        continue
                    workflows.append(item)
                    seen_names.add(name)
        except Exception as e:
            logger.error(f"Failed to list workflows: {e}")
            return []

        # Include archived workflows when showing completed
        if not exclude_completed:
            archived = self._list_archived_workflows(argo_server, namespace, insecure)
            for wf in archived:
                name = wf.get('metadata', {}).get('name', '')
                if name and name not in seen_names:
                    workflows.append(wf)
                    seen_names.add(name)

        return workflows

    def _list_archived_workflows(self, argo_server: str, namespace: str,
                                 insecure: bool, limit: int = 50) -> List[Dict[str, Any]]:
        """List workflows from the Argo archive."""
        try:
            headers = {"Authorization": f"Bearer {self.token}"} if self.token else {}
            url = f"{argo_server}/api/v1/archived-workflows"
            params = {
                "listOptions.fieldSelector": f"metadata.namespace={namespace}",
                "listOptions.limit": str(limit)
            }
            response = requests.get(url, headers=headers, params=params,
                                    verify=not insecure, timeout=30)
            if response.status_code == 200:
                return response.json().get("items") or []
        except Exception as e:
            logger.warning(f"Failed to list archived workflows: {e}")
        return []


class WorkflowSorter:
    """Sort a group of workflow (not the tasks within them)."""

    @staticmethod
    def sort_workflows_chronologically(workflows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Sort workflows by start time (newest first)."""
        return sorted(workflows, key=lambda x: x.get('status', {}).get('startedAt', ''), reverse=True)

    @staticmethod
    def find_most_recent_completed(workflows: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
        """Find the most recently completed workflow."""
        completed_workflows = [wf for wf in workflows if wf.get('status', {}).get('finishedAt')]
        if not completed_workflows:
            return workflows[0] if workflows else None

        return max(completed_workflows, key=lambda x: x.get('status', {}).get('finishedAt', ''))


class StatusCheckRunner:
    """Dynamic status check runner based on node type."""

    @staticmethod
    def run_status_check(env: Environment, node: Dict[str, Any]) -> Dict[str, Any]:
        """Run appropriate status check based on check_type."""
        check_type = node.get('check_type', '')
        logger.info(f"Running {check_type} status check for node: {node.get('display_name')}")

        if check_type == 'snapshot':
            logger.info("Calling snapshot status middleware")
            return StatusCheckRunner._check_snapshot_status(env)
        elif check_type == 'backfill':
            logger.info("Calling backfill status middleware")
            return StatusCheckRunner._check_backfill_status(env)
        else:
            logger.warning(f"Unknown check type: {check_type}")
            return {"error": f"Unknown check type: {check_type}"}

    @staticmethod
    def _check_snapshot_status(env: Environment) -> Dict[str, Any]:
        """Check snapshot status for environment."""
        logger.info("Starting snapshot status check")
        if not env.snapshot:
            logger.warning("No snapshot configured in environment")
            return {"error": "No snapshot configured"}

        try:
            logger.info("Calling snapshot_middleware.status() with deep_check=True")
            result = snapshot_middleware.status(env.snapshot, deep_check=True)
            logger.info(f"Snapshot status result type: {type(result)}")

            # Handle both CommandResult and tuple returns
            if hasattr(result, 'success'):
                # CommandResult object
                logger.info(f"Snapshot status result: success={result.success}")
                return {"success": result.success, "value": result.value}
            else:
                # Tuple from @handle_errors decorator
                exit_code, message = result
                success = exit_code.value == 0
                logger.info(f"Snapshot status result: success={success}")
                return {"success": success, "value": message}
        except Exception as e:
            logger.error(f"Exception in snapshot status check: {e}")
            return {"error": str(e)}

    @staticmethod
    def _check_backfill_status(env: Environment) -> Dict[str, Any]:
        """Check backfill status for environment."""
        logger.info("Starting backfill status check")
        if not env.backfill:
            logger.warning("No backfill configured in environment")
            return {"error": "No backfill configured"}

        try:
            logger.info("Calling backfill_middleware.status() with deep_check=True")
            result = backfill_middleware.status(env.backfill, deep_check=True)
            logger.info(f"Backfill status result type: {type(result)}")

            # Handle both CommandResult and tuple returns
            if hasattr(result, 'success'):
                # CommandResult object
                logger.info(f"Backfill status result: success={result.success}")
                if result.success:
                    status_enum, message = result.value
                    return {"success": True, "status": str(status_enum), "message": message}
                return {"success": False, "error": str(result.value)}
            else:
                # Tuple from @handle_errors decorator
                exit_code, message = result
                success = exit_code.value == 0
                logger.info(f"Backfill status result: success={success}")
                return {"success": success, "value": message}
        except Exception as e:
            logger.error(f"Exception in backfill status check: {e}")
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
                                workflow_data: Dict[str, Any] = None) -> None:
        """Display complete workflow status with tree."""
        self.display_workflow_header(workflow_name, phase, started_at, finished_at)
        click.echo("")
        display_workflow_tree(tree_nodes, workflow_data or {})

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


class LiveCheckProcessor:
    """Processes live status checks for workflow nodes using injected status runner."""

    def __init__(self, config_converter: ConfigConverter, status_runner: StatusCheckRunner = None):
        self.config_converter = config_converter
        self.status_runner = status_runner or StatusCheckRunner()

    def enrich_tree_with_live_checks(self, tree_nodes: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Add live check results to tree nodes."""
        logger.info("Starting live check enrichment process")

        pending_groups = self._find_pending_nodes(tree_nodes)
        logger.info(f"Found {len(pending_groups)} pending groups")

        return self._run_live_checks_parallel(pending_groups)

    def _find_pending_nodes(self, tree_nodes: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Find groups with no successful status checks yet, return last node per group."""

        def find_intermediate_nodes(nodes):
            """Find createSnapshot/bulkLoadDocuments nodes, don't descend into them."""
            for node in nodes:
                display_name = node.get('display_name', '')
                node_name = display_name.split('(')[0] if '(' in display_name else display_name

                if node_name in ('createSnapshot', 'bulkLoadDocuments'):
                    yield node
                else:
                    yield from find_intermediate_nodes(node.get('children', []))

        def find_most_recent_with_status(node):
            def walk(n):
                if self._has_status_output(n):
                    yield n
                for child in n.get('children', []):
                    yield from walk(child)

            return max(walk(node), key=lambda n: n.get('started_at', ''), default=None)

        def annotate_node(intermediate_node, node):
            """Add group metadata to status node."""
            display_name = intermediate_node.get('display_name', '')
            node_name = display_name.split('(')[0] if '(' in display_name else display_name

            node['check_type'] = 'snapshot' if node_name == 'createSnapshot' else 'backfill'
            node['check_group'] = get_node_input_parameter(intermediate_node, 'groupName') or 'default'
            return node

        # Pipeline: find intermediate -> map to most recent status -> filter/annotate
        return [
            annotate_node(intermediate, status)
            for intermediate in find_intermediate_nodes(tree_nodes)
            if (status := find_most_recent_with_status(intermediate)) is not None and status.get('phase') != 'Succeeded'
        ]

    def _has_status_output(self, node: Dict[str, Any]) -> bool:
        """Check if node has statusOutput parameter and configContents."""
        has_config = get_node_input_parameter(node, 'configContents') is not None
        outputs = node.get('outputs', {}).get('parameters', [])
        has_status_output = any(p.get('name') == 'statusOutput' for p in outputs)
        return has_config and has_status_output and node.get('type') == 'Pod'

    def _run_live_checks_parallel(self, in_progress_nodes: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Run live checks in parallel for failed nodes."""
        if not in_progress_nodes:
            logger.info("No failed nodes to process for live checks")
            return {}

        logger.info(f"Starting parallel live checks for {len(in_progress_nodes)} nodes")

        with ThreadPoolExecutor(max_workers=len(in_progress_nodes) * 2) as executor:
            futures = []
            for node in in_progress_nodes:
                config_contents = get_node_input_parameter(node, 'configContents')
                if not config_contents:
                    logger.warning(f"No configContents found for node {node['id']}")
                    continue

                logger.info(f"Converting config for node {node['id']} ({node.get('display_name')})")
                services_config = self.config_converter.convert_with_jq(config_contents)

                if services_config:
                    env = Environment(config=yaml.safe_load(services_config))
                    logger.info(f"Submitting live check task for node {node['id']}")
                    future = executor.submit(self.status_runner.run_status_check, env, node)
                    futures.append((node, future))
                else:
                    logger.error(f"Failed to convert config for node {node['id']}")

            # All tasks are submitted, now collect results as they complete
            for node, future in futures:
                try:
                    node['live_check'] = future.result()  # Blocks until this specific task completes
                except Exception as e:
                    logger.error(f"Live check failed for node {node['id']}: {e}")


@click.command(name="status")
@click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions)
@click.option('--all-workflows', is_flag=True, default=False, help='Show status for all workflows')
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
@click.option('--live-status', is_flag=True, default=False,
              help='Run a current status check for each snapshot and backfill still running')
@click.pass_context
def status_command(ctx, workflow_name, all_workflows, argo_server, namespace, insecure, token, show_all, live_status):
    """Show detailed status of workflows.

    Displays workflow progress, completed steps, and approval status.
    By default, only shows running workflows. Use --all to see completed workflows too.

    \b
    Example:
        workflow status
        workflow status --workflow-name my-workflow
        workflow status --all-workflows
        workflow status --all
    """
    if all_workflows and ctx.get_parameter_source('workflow_name') != click.core.ParameterSource.DEFAULT:
        click.echo("Error: --workflow-name and --all-workflows are mutually exclusive", err=True)
        ctx.exit(ExitCode.FAILURE.value)

    try:
        service = WorkflowService()
        handler = StatusCommandHandler(service, token)

        if all_workflows:
            handler.handle_status_command(None, argo_server, namespace, insecure, show_all, live_status)
        else:
            handler.handle_status_command(workflow_name, argo_server, namespace, insecure, show_all, live_status)

    except click.Abort:
        ctx.exit(ExitCode.FAILURE.value)
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
