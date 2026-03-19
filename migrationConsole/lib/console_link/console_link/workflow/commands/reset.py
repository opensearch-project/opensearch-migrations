"""Reset command for workflow CLI - tears down workflow resources via Argo suspend steps.

Workflow templates define 'reset:' prefixed suspend steps at resource teardown points.
This command discovers and approves those steps, letting the workflow handle cleanup.
"""

import logging
import os

import click

from ..models.utils import ExitCode
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .suspend_steps import (
    RESET_PREFIX,
    fetch_workflow_nodes,
    find_suspend_steps,
    match_steps,
    approve_steps,
    wait_for_workflow_completion,
    delete_workflow,
)

logger = logging.getLogger(__name__)


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
        nodes = fetch_workflow_nodes(workflow_name, namespace, argo_server, token, insecure)
        if nodes is None:
            click.echo(f"Workflow '{workflow_name}' not found.")
            ctx.exit(ExitCode.FAILURE.value)
            return

        all_steps = find_suspend_steps(nodes, prefix=RESET_PREFIX)
        if not all_steps:
            click.echo("No reset steps found in workflow.")
            return

        # Strip reset: prefix from names for display and matching
        all_steps = [(nid, name[len(RESET_PREFIX):], disp, ph) for nid, name, disp, ph in all_steps]

        # LIST mode
        if path is None:
            click.echo(f"Resettable resources for workflow '{workflow_name}':")
            for _, short_name, display_name, phase in all_steps:
                status = "waiting" if phase == "Running" else phase.lower()
                click.echo(f"  {short_name:<30} ({status})")
            click.echo()
            click.echo("Use 'workflow reset <path>' to reset specific resources.")
            click.echo("Use 'workflow reset *' to reset all and delete the workflow.")
            return

        # Find suspended steps matching path
        suspended = [s for s in all_steps if s[3] == 'Running']
        matches = suspended if path == '*' else match_steps(suspended, [path])

        if not matches:
            click.echo(f"No suspended reset steps match '{path}'.")
            waiting = [sn for _, sn, _, ph in all_steps if ph == 'Running']
            if waiting:
                click.echo(f"Available: {', '.join(waiting)}")
            return

        # Re-add prefix for the approve API (it uses the full node ID, so this is fine)
        approved, error = approve_steps(
            matches, workflow_name, namespace, argo_server, token, insecure)

        if error:
            ctx.exit(ExitCode.FAILURE.value)
            return

        # If *, wait for completion and delete
        if path == '*':
            click.echo("Waiting for workflow to complete...")
            phase = wait_for_workflow_completion(
                workflow_name, namespace, argo_server, token, insecure)
            if phase:
                click.echo(f"Workflow finished: {phase}")
            else:
                click.echo("Timed out waiting for workflow completion.", err=True)

            if delete_workflow(workflow_name, namespace, argo_server, token, insecure):
                click.echo(f"  ✓ Deleted workflow '{workflow_name}'")
            else:
                click.echo(f"  ✗ Failed to delete workflow '{workflow_name}'", err=True)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
