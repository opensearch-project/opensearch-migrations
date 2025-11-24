"""Approve command for workflow CLI - resumes suspended workflows in Argo Workflows."""

import logging
import os
import click

from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService
from .utils import auto_detect_workflow

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
@click.option(
    '--acknowledge',
    is_flag=True,
    default=False,
    help='Skip interactive confirmation and approve immediately'
)
@click.pass_context
def approve_command(ctx, workflow_name, argo_server, namespace, insecure, token, acknowledge):
    """Approve/resume a suspended workflow in Argo Workflows.

    If workflow_name is not provided, auto-detects the single workflow
    in the specified namespace.

    Example:
        workflow approve
        workflow approve my-workflow
        workflow approve --acknowledge
        workflow approve --argo-server https://10.105.13.185:2746 --insecure
    """

    try:
        service = WorkflowService()

        # Auto-detect workflow if name not provided
        if not workflow_name:
            workflow_name = auto_detect_workflow(
                service, namespace, argo_server, token, insecure, ctx, phase_filter='Running'
            )
            if not workflow_name:
                ctx.exit(ExitCode.FAILURE.value)

        # Get workflow status to display details
        status_result = service.get_workflow_status(
            workflow_name=workflow_name,
            namespace=namespace,
            argo_server=argo_server,
            token=token,
            insecure=insecure
        )

        if not status_result['success']:
            click.echo(f"Error getting workflow status: {status_result['error']}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

        # Display workflow information
        click.echo("\n" + "=" * 60)
        click.echo(f"Workflow: {workflow_name}")
        click.echo(f"Namespace: {namespace}")
        click.echo(f"Phase: {status_result['phase']}")
        if status_result['started_at']:
            click.echo(f"Started: {status_result['started_at']}")
        click.echo("=" * 60)

        # Display steps and collect suspended steps
        suspended_steps = []
        if status_result['steps']:
            click.echo("\nWorkflow Steps:")
            click.echo("-" * 60)
            for step in status_result['steps']:
                step_type = step['type']
                step_name = step['name']
                step_phase = step['phase']

                # Collect suspended steps for approval selection
                if step_type == 'Suspend' and step_phase == 'Running':
                    suspended_steps.append(step_name)
                    click.echo(f"  > {step_name} [{step_type}] - {step_phase} (WAITING FOR APPROVAL)")
                else:
                    status_icon = "+" if step_phase == "Succeeded" else "*"
                    click.echo(f"  {status_icon} {step_name} [{step_type}] - {step_phase}")
            click.echo("-" * 60)

        # Interactive confirmation unless --acknowledge is passed
        if not acknowledge:
            if not suspended_steps:
                click.echo("\nNo suspended steps found waiting for approval.")
                ctx.exit(ExitCode.SUCCESS.value)

            click.echo("\nSelect an action:")
            click.echo("-" * 60)

            # Display suspended steps with numbers
            for idx, step_name in enumerate(suspended_steps):
                click.echo(f"  [{idx}] Approve and resume: {step_name}")

            click.echo("  [c] Cancel")
            click.echo("-" * 60)
            click.echo(f"\nTip: To view step outputs, run: workflow output {workflow_name}")

            # Get user selection
            choice = click.prompt("\nEnter your choice", type=str).strip().lower()

            if choice == 'c':
                click.echo("Approval cancelled.")
                ctx.exit(ExitCode.SUCCESS.value)

            # Validate numeric choice
            try:
                choice_idx = int(choice)
                if choice_idx < 0 or choice_idx >= len(suspended_steps):
                    click.echo(f"Error: Invalid choice. Please select 0-{len(suspended_steps) - 1} or 'c'", err=True)
                    ctx.exit(ExitCode.FAILURE.value)

                selected_step = suspended_steps[choice_idx]
                click.echo(f"\nApproving step: {selected_step}")
            except ValueError:
                click.echo(
                    f"Error: Invalid input. Please enter a number (0-{len(suspended_steps) - 1}) or 'c'",
                    err=True
                )
                ctx.exit(ExitCode.FAILURE.value)

        # Resume the workflow
        result = service.approve_workflow(
            workflow_name=workflow_name,
            namespace=namespace,
            argo_server=argo_server,
            token=token,
            insecure=insecure
        )

        if result['success']:
            click.echo(f"\nWorkflow {workflow_name} resumed successfully")
        else:
            click.echo(f"\nError: {result['message']}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
