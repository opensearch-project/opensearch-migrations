"""Reset command for workflow CLI - tears down workflow resources via Argo suspend steps.

Workflow templates define 'reset:' prefixed suspend steps at resource teardown points.
This command discovers and approves those steps, letting the workflow handle cleanup.
"""

import fnmatch
import logging
import os
import time

import click
import requests

from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions

logger = logging.getLogger(__name__)

RESET_PREFIX = 'reset:'
ENDING_PHASES = {'Succeeded', 'Failed', 'Error', 'Stopped'}


def _fetch_all_reset_steps(workflow_name, namespace, argo_server, token, insecure):
    """Fetch all reset: suspend nodes with their current phase.

    Returns list of (node_id, short_name, display_name, phase) or None if workflow not found.
    """
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
    response = requests.get(url, headers=headers, verify=not insecure, timeout=10)
    if response.status_code != 200:
        return None

    nodes = response.json().get('status', {}).get('nodes', {})
    steps = []
    for node_id, node in nodes.items():
        if node.get('type') != 'Suspend':
            continue
        for p in node.get('inputs', {}).get('parameters', []):
            if p.get('name') == 'name' and p.get('value', '').startswith(RESET_PREFIX):
                short_name = p['value'][len(RESET_PREFIX):]
                steps.append((node_id, short_name, node.get('displayName', ''), node.get('phase', '')))
                break
    return steps


def _wait_for_workflow_completion(workflow_name, namespace, argo_server, token, insecure,
                                  timeout_seconds=300):
    """Poll until workflow reaches an ending phase. Returns final phase or None on timeout."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        try:
            resp = requests.get(url, headers=headers, verify=not insecure, timeout=10)
            if resp.status_code == 200:
                phase = resp.json().get('status', {}).get('phase', '')
                if phase in ENDING_PHASES:
                    return phase
        except requests.RequestException:
            pass
        time.sleep(5)
    return None


def _delete_workflow(workflow_name, namespace, argo_server, token, insecure):
    """Delete the Argo workflow. Returns True if deleted."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
    try:
        resp = requests.delete(url, headers=headers, verify=not insecure, timeout=10)
        return resp.status_code == 200
    except requests.RequestException:
        return False


@click.command(name="reset")
@click.argument('path', required=False, default=None)
@click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions)
@click.option(
    '--argo-server',
    default=lambda: os.environ.get(
        'ARGO_SERVER',
        f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}:"
        f"{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}"
    ),
    help='Argo Server URL'
)
@click.option('--namespace', default='ma')
@click.option('--insecure', is_flag=True, default=False)
@click.option('--token', help='Bearer token for authentication')
@click.pass_context
def reset_command(ctx, path, workflow_name, argo_server, namespace, insecure, token):
    """Reset workflow resources by approving teardown suspend steps.

    With no arguments, lists available reset steps and their status.
    With a PATH, approves matching reset steps (glob patterns supported).
    With '*', approves all reset steps, waits for completion, and deletes the workflow.

    Example:
        workflow reset                     # list reset steps
        workflow reset teardown-capture    # reset just capture
        workflow reset *                   # reset everything and delete workflow
    """
    try:
        steps = _fetch_all_reset_steps(workflow_name, namespace, argo_server, token, insecure)
        if steps is None:
            click.echo(f"Workflow '{workflow_name}' not found.")
            ctx.exit(ExitCode.FAILURE.value)
            return

        if not steps:
            click.echo("No reset steps found in workflow.")
            return

        # LIST mode
        if path is None:
            click.echo(f"Resettable resources for workflow '{workflow_name}':")
            for _, short_name, display_name, phase in steps:
                status = "waiting" if phase == "Running" else phase.lower()
                click.echo(f"  {short_name:<30} ({status})")
            click.echo()
            click.echo("Use 'workflow reset <path>' to reset specific resources.")
            click.echo("Use 'workflow reset *' to reset all and delete the workflow.")
            return

        # Find suspended steps matching path
        suspended = [(nid, sn, dn, ph) for nid, sn, dn, ph in steps if ph == 'Running']
        if path == '*':
            matches = suspended
        else:
            matches = [(nid, sn, dn, ph) for nid, sn, dn, ph in suspended
                       if fnmatch.fnmatch(sn, path)]

        if not matches:
            click.echo(f"No suspended reset steps match '{path}'.")
            waiting = [sn for _, sn, _, ph in steps if ph == 'Running']
            if waiting:
                click.echo(f"Available: {', '.join(waiting)}")
            return

        # Approve matching steps
        service = WorkflowService()
        for node_id, short_name, display_name, _ in matches:
            result = service.approve_workflow(
                workflow_name=workflow_name,
                namespace=namespace,
                argo_server=argo_server,
                token=token,
                insecure=insecure,
                node_field_selector=f"id={node_id}"
            )
            if result['success']:
                click.echo(f"  ✓ Approved {short_name}")
            else:
                click.echo(f"  ✗ Failed {short_name}: {result['message']}", err=True)
                ctx.exit(ExitCode.FAILURE.value)
                return

        # If *, wait for completion and delete
        if path == '*':
            click.echo("Waiting for workflow to complete...")
            phase = _wait_for_workflow_completion(
                workflow_name, namespace, argo_server, token, insecure)
            if phase:
                click.echo(f"Workflow finished: {phase}")
            else:
                click.echo("Timed out waiting for workflow completion.", err=True)

            if _delete_workflow(workflow_name, namespace, argo_server, token, insecure):
                click.echo(f"  ✓ Deleted workflow '{workflow_name}'")
            else:
                click.echo(f"  ✗ Failed to delete workflow '{workflow_name}'", err=True)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
