"""Shared utility functions for workflow commands."""

import logging
import click

from ..models.utils import ExitCode

logger = logging.getLogger(__name__)


def _handle_no_workflows_with_filter(service, namespace, argo_server, token, insecure, phase_filter, ctx):
    """Handle case when no workflows match the phase filter."""
    all_workflows_result = service.list_workflows(
        namespace=namespace,
        argo_server=argo_server,
        token=token,
        insecure=insecure
    )

    if all_workflows_result['success'] and all_workflows_result['count'] > 0:
        _display_no_matching_workflows_message(namespace, phase_filter)
        ctx.exit(ExitCode.FAILURE.value)

    # No workflows exist at all
    click.echo(f"No workflows found in namespace {namespace}")
    return None


def _display_no_matching_workflows_message(namespace, phase_filter):
    """Display appropriate message when workflows exist but none match the filter."""
    if phase_filter == 'Running':
        click.echo(
            f"No workflows require approval in namespace {namespace}.\n"
            f"Use 'workflow status' to see workflow details.",
            err=True
        )
    else:
        click.echo(
            f"No workflows with phase '{phase_filter}' found in namespace {namespace}.",
            err=True
        )


def _handle_multiple_workflows(workflows, phase_filter, ctx):
    """Handle case when multiple workflows are found."""
    workflows_list = ', '.join(workflows)
    action = "approve" if phase_filter == 'Running' else "view"
    click.echo(
        f"Error: Multiple workflows found. Please specify which one to {action}.\n"
        f"Found workflows: {workflows_list}",
        err=True
    )
    ctx.exit(ExitCode.FAILURE.value)


def auto_detect_workflow(service, namespace, argo_server, token, insecure, ctx, phase_filter=None):
    """Auto-detect workflow when name is not provided.

    Args:
        service: WorkflowService instance
        namespace: Kubernetes namespace
        argo_server: Argo Server URL
        token: Bearer token for authentication
        insecure: Skip TLS certificate verification
        ctx: Click context for exit handling
        phase_filter: Optional phase filter (e.g., 'Running')

    Returns:
        str: Workflow name if exactly one workflow found, None otherwise

    Exits:
        - If error listing workflows
        - If multiple workflows found
        - If no workflows found (when phase_filter is used)
    """
    list_result = service.list_workflows(
        namespace=namespace,
        argo_server=argo_server,
        token=token,
        insecure=insecure,
        phase_filter=phase_filter
    )

    if not list_result['success']:
        click.echo(f"Error listing workflows: {list_result['error']}", err=True)
        ctx.exit(ExitCode.FAILURE.value)

    if list_result['count'] == 0:
        if phase_filter:
            return _handle_no_workflows_with_filter(
                service, namespace, argo_server, token, insecure, phase_filter, ctx
            )
        click.echo(f"No workflows found in namespace {namespace}")
        return None

    if list_result['count'] > 1:
        _handle_multiple_workflows(list_result['workflows'], phase_filter, ctx)

    workflow_name = list_result['workflows'][0]
    click.echo(f"Auto-detected workflow: {workflow_name}")
    return workflow_name
