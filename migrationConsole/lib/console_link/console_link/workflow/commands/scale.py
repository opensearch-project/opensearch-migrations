"""Scale command for workflow CLI - sets replica count for pausable Deployments."""

import logging

import click

from ..models.utils import ExitCode
from ..services.deployment_service import DeploymentService
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .autocomplete_deployments import get_all_deployment_completions

logger = logging.getLogger(__name__)


@click.command(name="scale")
@click.argument('task-names', nargs=-1, shell_complete=get_all_deployment_completions)
@click.option('--replicas', required=True, type=int, help='Target replica count')
@click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions)
@click.option('--namespace', default='ma', help='Kubernetes namespace (default: ma)')
@click.option('-y', '--yes', is_flag=True, default=False, help='Skip confirmation prompt')
@click.pass_context
def scale_command(ctx, task_names, replicas, workflow_name, namespace, yes):
    """Set replica count for backfill and replay Deployments.

    TASK_NAMES are optional glob patterns to select specific pipelines.
    With no arguments, all pausable Deployments are scaled (with confirmation).

    Example:
        workflow scale --replicas 3
        workflow scale "*.replayer" --replicas 5
        workflow scale source1.target1.backfill --replicas 0
    """
    try:
        service = DeploymentService()
        deployments = service.discover_pausable_deployments(workflow_name, namespace)

        if not deployments:
            click.echo("No pausable Deployments found.")
            ctx.exit(ExitCode.FAILURE.value)
            return

        targets = service.filter_by_task_names(deployments, task_names)

        if not targets:
            click.echo("No matching Deployments found.")
            if task_names:
                click.echo("Available Deployments:")
                for d in deployments:
                    status = "paused" if d.is_paused else f"running ({d.replicas} replicas)"
                    click.echo(f"  - {d.display_name} ({status})")
            ctx.exit(ExitCode.FAILURE.value)
            return

        click.echo(f"The following Deployments will be scaled to {replicas} replicas:")
        for d in targets:
            click.echo(f"  - {d.display_name} (currently {d.replicas} replicas)")

        if not task_names and not yes:
            click.confirm("Proceed?", abort=True)

        succeeded = 0
        for dep in targets:
            result = service.scale_deployment(dep, replicas)
            symbol = "✓" if result["success"] else "✗"
            click.echo(f"  {symbol} {result['message']}")
            if result["success"]:
                succeeded += 1

        click.echo(f"\nScaled {succeeded} of {len(targets)} Deployment(s).")

    except click.Abort:
        click.echo("Aborted.")
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
