"""Resume command for workflow CLI - restores paused Deployments to pre-pause replica count."""

import logging

import click

from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .autocomplete_deployments import get_paused_deployment_completions
from .deployment_helpers import resolve_targets, confirm_if_needed, execute_and_report

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
        result = resolve_targets(
            ctx, workflow_name, namespace, task_names,
            empty_message="No resumable Deployments found.",
            no_match_message="No matching paused Deployments found.",
            auto_filter=lambda d: d.is_paused,
        )
        if not result:
            return
        service, targets = result

        click.echo("The following Deployments will be resumed:")
        for d in targets:
            click.echo(f"  - {d.display_name} (will restore to {d.pre_pause_replicas} replicas)")

        confirm_if_needed(task_names, yes)
        execute_and_report(targets, service.resume_deployment, "Resumed")

    except click.Abort:
        click.echo("Aborted.")
