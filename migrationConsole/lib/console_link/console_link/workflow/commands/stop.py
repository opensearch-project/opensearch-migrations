"""Stop command for workflow CLI - stops running workflows in Argo Workflows."""

import logging
import os
import click

from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService

logger = logging.getLogger(__name__)


@click.command(name="stop")
@click.argument('workflow_name', required=False)
@click.option(
    '--argo-server',
    default=f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
    f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}",
    help='Argo Server URL (default: ARGO_SERVER env var, or ARGO_SERVER_SERVICE_HOST:ARGO_SERVER_SERVICE_PORT)'
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
def stop_command(ctx, workflow_name, argo_server, namespace, insecure, token):
    """Stop a running workflow in Argo Workflows.

    If workflow_name is not provided, auto-detects the single workflow
    in the specified namespace.

    Example:
        workflow stop
        workflow stop my-workflow
        workflow stop --argo-server https://10.105.13.185:2746 --insecure
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
                exclude_completed=True
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
                    f"Error: Multiple workflows found. Please specify which one to stop.\n"
                    f"Found workflows: {workflows_list}",
                    err=True
                )
                ctx.exit(ExitCode.FAILURE.value)

            workflow_name = list_result['workflows'][0]
            click.echo(f"Auto-detected workflow: {workflow_name}")

        # Stop the workflow
        result = service.stop_workflow(
            workflow_name=workflow_name,
            namespace=namespace,
            argo_server=argo_server,
            token=token,
            insecure=insecure
        )

        if result['success']:
            click.echo(f"Workflow {workflow_name} stopped successfully")
            click.echo("\nBefore starting a new workflow, delete this workflow with:")
            click.echo(f"  kubectl delete workflow {workflow_name} -n {namespace}")
        else:
            click.echo(f"Error: {result['message']}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
