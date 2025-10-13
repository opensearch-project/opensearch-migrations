"""Approve command for workflow CLI - resumes suspended workflows in Argo Workflows."""

import logging
import os
import click

from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService

logger = logging.getLogger(__name__)


@click.command(name="approve")
@click.argument('workflow_name', required=False)
@click.option(
    '--argo-server',
    default=lambda: os.environ.get(
        'ARGO_SERVER',
        f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}:"
        f"{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}"
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
@click.pass_context
def approve_command(ctx, workflow_name, argo_server, namespace, insecure, token):
    """Approve/resume a suspended workflow in Argo Workflows.

    If workflow_name is not provided, auto-detects the single workflow
    in the specified namespace.

    Example:
        workflow approve
        workflow approve my-workflow
        workflow approve --argo-server https://10.105.13.185:2746 --insecure
    """

    try:
        service = WorkflowService()

        # Auto-detect workflow if name not provided
        if not workflow_name:
            list_result = service.list_workflows(
                namespace=namespace,
                argo_server=argo_server,
                token=token,
                insecure=insecure,
                phase_filter='Running'
            )

            if not list_result['success']:
                click.echo(f"Error listing workflows: {list_result['error']}", err=True)
                ctx.exit(ExitCode.FAILURE.value)

            if list_result['count'] == 0:
                click.echo(f"Error: No workflows found in namespace {namespace}", err=True)
                ctx.exit(ExitCode.FAILURE.value)
            elif list_result['count'] > 1:
                workflows_list = ', '.join(list_result['workflows'])
                click.echo(
                    f"Error: Multiple workflows found. Please specify which one to approve.\n"
                    f"Found workflows: {workflows_list}",
                    err=True
                )
                ctx.exit(ExitCode.FAILURE.value)

            workflow_name = list_result['workflows'][0]
            click.echo(f"Auto-detected workflow: {workflow_name}")

        # Resume the workflow
        result = service.approve_workflow(
            workflow_name=workflow_name,
            namespace=namespace,
            argo_server=argo_server,
            token=token,
            insecure=insecure
        )

        if result['success']:
            click.echo(f"Workflow {workflow_name} resumed successfully")
        else:
            click.echo(f"Error: {result['message']}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
