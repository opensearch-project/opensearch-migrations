"""Resubmit command for workflow CLI - stops current workflow and submits a new one.

Preserves existing CRDs and their owned resources (proxy, Kafka, etc.).
Only resources whose CRDs were explicitly deleted via 'workflow reset' are recreated.
"""

import os

import click

from ..models.utils import ExitCode
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .suspend_steps import argo_stop, delete_workflow


@click.command(name="resubmit")
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
def resubmit_command(ctx, workflow_name, argo_server, namespace, insecure, token):
    """Stop the current workflow and submit a new one.

    Existing resources (proxy, Kafka) are preserved — only resources whose
    CRDs were deleted via 'workflow reset <name>' will be recreated.

    Example:
        workflow reset snapshotmigration/*   # reset just backfill
        workflow resubmit                    # resubmit with new config
    """
    try:
        click.echo(f"Stopping workflow '{workflow_name}'...")
        if argo_stop(workflow_name, namespace, argo_server, token, insecure):
            click.echo(f"  ✓ Stopped")
        else:
            click.echo("  ⚠ Could not stop (may already be finished)")

        click.echo(f"Deleting workflow '{workflow_name}'...")
        if delete_workflow(workflow_name, namespace, argo_server, token, insecure):
            click.echo(f"  ✓ Deleted")
        else:
            click.echo("  ⚠ Could not delete (may not exist)")

        click.echo("Submitting new workflow...")
        # Import here to avoid circular imports
        from .submit import submit_command
        ctx.invoke(submit_command, namespace=namespace)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
