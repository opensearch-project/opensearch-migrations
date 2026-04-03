"""Pause command for workflow CLI - scales pausable Deployments to 0."""

import logging

import click

from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .autocomplete_deployments import get_running_deployment_completions
from .deployment_helpers import resolve_targets, confirm_if_needed, execute_and_report

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
        result = resolve_targets(
            ctx, workflow_name, namespace, task_names,
            empty_message="No pausable Deployments found.",
            no_match_message="No matching running Deployments found.",
            auto_filter=lambda d: not d.is_paused,
        )
        if not result:
            return
        service, targets = result

        click.echo("The following Deployments will be paused:")
        for d in targets:
            click.echo(f"  - {d.display_name} ({d.replicas} replicas)")

        confirm_if_needed(task_names, yes)
        execute_and_report(targets, service.pause_deployment, "Paused")

    except click.Abort:
        click.echo("Aborted.")
