"""Scale command for workflow CLI - sets replica count for pausable Deployments."""

import logging

import click

from ..models.utils import ExitCode
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .autocomplete_deployments import get_all_deployment_completions
from .deployment_helpers import resolve_targets, confirm_if_needed, execute_and_report

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
    With no arguments, all scaleable Deployments are scaled (with confirmation).

    Example:
        workflow scale --replicas 3
        workflow scale "*.replayer" --replicas 5
        workflow scale source1.target1.backfill --replicas 0
    """
    try:
        result = resolve_targets(
            ctx, workflow_name, namespace, task_names,
            empty_message="No scaleable Deployments found.",
            no_match_message="No matching Deployments found.",
        )
        if not result:
            return
        service, targets = result

        click.echo(f"The following Deployments will be scaled to {replicas} replicas:")
        for d in targets:
            click.echo(f"  - {d.display_name} (currently {d.replicas} replicas)")

        if replicas > 0:
            over_limit = [d for d in targets if 0 < d.max_replicas < replicas]
            if over_limit:
                for d in over_limit:
                    click.echo(f"Error: {d.display_name} cannot scale to {replicas} replicas (max: {d.max_replicas})")
                ctx.exit(ExitCode.FAILURE.value)
                return

        confirm_if_needed(task_names, yes)
        execute_and_report(targets, lambda dep: service.scale_deployment(dep, replicas), "Scaled")

    except click.Abort:
        click.echo("Aborted.")
