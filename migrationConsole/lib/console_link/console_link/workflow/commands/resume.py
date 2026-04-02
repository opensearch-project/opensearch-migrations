"""Resume command for workflow CLI - restores paused Deployments to pre-pause replica count."""

import logging

import click

from ..models.utils import ExitCode
from ..services.deployment_service import DeploymentService
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .autocomplete_deployments import get_paused_deployment_completions

logger = logging.getLogger(__name__)


@click.command(name="resume")
@click.argument('task-names', nargs=-1, shell_complete=get_paused_deployment_completions)
@click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions)
@click.option('--namespace', default='ma', help='Kubernetes namespace (default: ma)')
@click.option('-y', '--yes', is_flag=True, default=False, help='Skip confirmation prompt')
@click.pass_context
def resume_command(ctx, task_names, workflow_name, namespace, yes):
    """Resume paused backfill and replay Deployments.

    Restores the pre-pause replica count from the stored annotation.

    TASK_NAMES are optional glob patterns to select specific pipelines.
    With no arguments, all paused Deployments are resumed (with confirmation).

    Example:
        workflow resume
        workflow resume source1.target1.backfill
        workflow resume "*.replayer"
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
            targets = [d for d in targets if d.is_paused]

        if not targets:
            click.echo("No matching paused Deployments found.")
            if task_names:
                click.echo("Available Deployments:")
                for d in deployments:
                    status = "paused" if d.is_paused else f"running ({d.replicas} replicas)"
                    click.echo(f"  - {d.display_name} ({status})")
            ctx.exit(ExitCode.FAILURE.value)
            return

        click.echo("The following Deployments will be resumed:")
        for d in targets:
            click.echo(f"  - {d.display_name} (will restore to {d.pre_pause_replicas} replicas)")

        if not task_names and not yes:
            click.confirm("Proceed?", abort=True)

        succeeded = 0
        for dep in targets:
            result = service.resume_deployment(dep)
            symbol = "✓" if result["success"] else "✗"
            click.echo(f"  {symbol} {result['message']}")
            if result["success"]:
                succeeded += 1

        click.echo(f"\nResumed {succeeded} of {len(targets)} Deployment(s).")

    except click.Abort:
        click.echo("Aborted.")
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
