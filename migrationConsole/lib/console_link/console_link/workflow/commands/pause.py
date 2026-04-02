"""Pause command for workflow CLI - scales pausable Deployments to 0."""

import logging

import click

from ..models.utils import ExitCode
from ..services.deployment_service import DeploymentService
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .autocomplete_deployments import get_running_deployment_completions

logger = logging.getLogger(__name__)


@click.command(name="pause")
@click.argument('task-names', nargs=-1, shell_complete=get_running_deployment_completions)
@click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions)
@click.option('--namespace', default='ma', help='Kubernetes namespace (default: ma)')
@click.option('-y', '--yes', is_flag=True, default=False, help='Skip confirmation prompt')
@click.pass_context
def pause_command(ctx, task_names, workflow_name, namespace, yes):
    """Pause running backfill and replay Deployments.

    Stores the current replica count and scales to 0. Use `workflow resume` to restore.

    TASK_NAMES are optional glob patterns to select specific pipelines.
    With no arguments, all running pausable Deployments are paused (with confirmation).

    Example:
        workflow pause
        workflow pause source1.target1.backfill
        workflow pause "*.backfill"
    """
    try:
        service = DeploymentService()
        deployments = service.discover_pausable_deployments(workflow_name, namespace)

        if not deployments:
            click.echo("No pausable Deployments found.")
            ctx.exit(ExitCode.FAILURE.value)
            return

        targets = service.filter_by_task_names(deployments, task_names)
        if not task_names:
            targets = [d for d in targets if not d.is_paused]

        if not targets:
            click.echo("No matching running Deployments found.")
            if task_names:
                click.echo("Available Deployments:")
                for d in deployments:
                    status = "paused" if d.is_paused else f"running ({d.replicas} replicas)"
                    click.echo(f"  - {d.display_name} ({status})")
            ctx.exit(ExitCode.FAILURE.value)
            return

        click.echo("The following Deployments will be paused:")
        for d in targets:
            click.echo(f"  - {d.display_name} ({d.replicas} replicas)")

        if not task_names and not yes:
            click.confirm("Proceed?", abort=True)

        succeeded = 0
        for dep in targets:
            result = service.pause_deployment(dep)
            symbol = "✓" if result["success"] else "✗"
            click.echo(f"  {symbol} {result['message']}")
            if result["success"]:
                succeeded += 1

        click.echo(f"\nPaused {succeeded} of {len(targets)} Deployment(s).")

    except click.Abort:
        click.echo("Aborted.")
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
