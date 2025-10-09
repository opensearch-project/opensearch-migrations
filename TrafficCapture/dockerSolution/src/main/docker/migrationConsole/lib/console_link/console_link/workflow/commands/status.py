"""Status command for workflow CLI - shows detailed status of workflows."""

import logging
import os
import click

from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService

logger = logging.getLogger(__name__)


@click.command(name="status")
@click.argument('workflow_name', required=False)
@click.option(
    '--argo-server',
    default=lambda: os.environ.get(
        'ARGO_SERVER',
        f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}:{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}"
    ),
    help='Argo Server URL (default: auto-detected from Kubernetes service env vars, or ARGO_SERVER env var)'
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
@click.pass_context
def status_command(ctx, workflow_name, argo_server, namespace, insecure, token, show_all):
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

        if workflow_name:
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

            _display_workflow_status(result)
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
                else:
                    click.echo(f"No running workflows found in namespace {namespace}")
                    click.echo("Use --all to see completed workflows")
                return

            click.echo(f"Found {list_result['count']} workflow(s) in namespace {namespace}:\n")

            # Sort workflows alphabetically by name
            sorted_workflows = sorted(list_result['workflows'])

            for wf_name in sorted_workflows:
                result = service.get_workflow_status(
                    workflow_name=wf_name,
                    namespace=namespace,
                    argo_server=argo_server,
                    token=token,
                    insecure=insecure
                )

                if result['success']:
                    _display_workflow_status(result)
                    click.echo("")  # Blank line between workflows

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)


def _display_workflow_status(result: dict):
    """Display formatted workflow status.

    Args:
        result: WorkflowStatusResult dict from get_workflow_status
    """
    name = result['workflow_name']
    phase = result['phase']
    progress = result['progress']
    started_at = result['started_at']
    finished_at = result['finished_at']

    # Header with workflow name and overall status
    phase_symbol = {
        'Running': '*',
        'Succeeded': '+',
        'Failed': '-',
        'Error': '-',
        'Pending': '>',
        'Stopped': 'X',
    }.get(phase, '?')

    click.echo(f"[{phase_symbol}] Workflow: {name}")
    click.echo(f"  Phase: {phase}")
    click.echo(f"  Progress: {progress}")
    if started_at:
        click.echo(f"  Started: {started_at}")
    if finished_at:
        click.echo(f"  Finished: {finished_at}")

    # Display steps
    if result['steps']:
        click.echo(f"\n  Steps:")
        for step in result['steps']:
            step_name = step['name']
            step_phase = step['phase']
            step_type = step['type']

            if step_phase == 'Succeeded':
                symbol = '  +'
            elif step_phase == 'Running':
                if step_type == 'Suspend':
                    symbol = '  |'
                else:
                    symbol = '  *'
            elif step_phase == 'Failed' or step_phase == 'Error':
                symbol = '  -'
            elif step_phase == 'Pending':
                symbol = '  >'
            else:
                symbol = '  ?'

            if step_type == 'Suspend' and step_phase == 'Running':
                click.echo(f"{symbol} {step_name} - WAITING FOR APPROVAL")
            else:
                click.echo(f"{symbol} {step_name} ({step_phase})")
