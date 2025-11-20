"""Shared utility functions for workflow commands."""

import logging
import click

from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService

logger = logging.getLogger(__name__)


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
            # Check if any workflows exist at all (without phase filter)
            all_workflows_result = service.list_workflows(
                namespace=namespace,
                argo_server=argo_server,
                token=token,
                insecure=insecure
            )

            if all_workflows_result['success'] and all_workflows_result['count'] > 0:
                # Workflows exist but none match the filter
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
            else:
                # No workflows exist at all
                click.echo(f"No workflows found in namespace {namespace}")
                return None
            ctx.exit(ExitCode.FAILURE.value)
        else:
            click.echo(f"No workflows found in namespace {namespace}")
            return None

    elif list_result['count'] > 1:
        workflows_list = ', '.join(list_result['workflows'])
        action = "approve" if phase_filter == 'Running' else "view"
        click.echo(
            f"Error: Multiple workflows found. Please specify which one to {action}.\n"
            f"Found workflows: {workflows_list}",
            err=True
        )
        ctx.exit(ExitCode.FAILURE.value)

    workflow_name = list_result['workflows'][0]
    click.echo(f"Auto-detected workflow: {workflow_name}")
    return workflow_name
