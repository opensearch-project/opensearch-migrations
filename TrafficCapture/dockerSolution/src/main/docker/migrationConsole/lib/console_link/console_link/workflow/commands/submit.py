"""Submit command for workflow CLI - submits workflows to Argo Workflows."""

import logging
import os
import click

from ..models.utils import ExitCode
from ..models.store import WorkflowConfigStore
from ..services.workflow_service import WorkflowService

logger = logging.getLogger(__name__)

# Terminal workflow phases
ENDING_PHASES = ["Succeeded", "Failed", "Error", "Stopped", "Terminated"]


@click.command(name="submit")
@click.option(
    '--argo-server',
    default=f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
            f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}",
    help='Argo Server URL (default: auto-detected from Kubernetes service env vars, or ARGO_SERVER env var)'
)
@click.option(
    '--namespace',
    default='ma',
    help='Kubernetes namespace for the workflow (default: ma)'
)
@click.option(
    '--name',
    help='Workflow name (will use generateName if not provided)'
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
    '--wait',
    is_flag=True,
    default=False,
    help='Wait for workflow completion (default: return immediately after submission)'
)
@click.option(
    '--timeout',
    default=120,
    type=int,
    help='Timeout in seconds to wait for workflow completion (only used with --wait, default: 120)'
)
@click.option(
    '--wait-interval',
    default=2,
    type=int,
    help='Interval in seconds between status checks (only used with --wait, default: 2)'
)
@click.option(
    '--session',
    default='default',
    help='Configuration session name to load parameters from (default: default)'
)
@click.pass_context
def submit_command(ctx, argo_server, namespace, name, insecure, token, wait, timeout, wait_interval, session):
    """Submit a workflow to Argo Workflows.

    This command submits a workflow to Argo Workflows using the Argo Server REST API.
    By default, it returns immediately after submission without waiting for completion.
    Use the --wait flag to wait for the workflow to complete.

    The default workflow includes a suspend step that requires manual approval via
    'workflow approve' before completion.

    To use a custom workflow template, set the WORKFLOW_TEMPLATE_PATH environment
    variable to point to a workflow YAML file. Parameters can be injected from a
    WorkflowConfig stored in a Kubernetes ConfigMap (use 'workflow configure' to set).

    Example:
        workflow submit
        workflow submit --wait
        workflow submit --wait --timeout 300
        WORKFLOW_TEMPLATE_PATH=/path/to/workflow.yaml workflow submit
    """
    try:
        # Initialize the service layer
        service = WorkflowService()

        # Load workflow template (from WORKFLOW_TEMPLATE_PATH or default)
        template_result = service.load_workflow_template()

        if template_result['error']:
            click.echo(f"Warning: {template_result['error']}", err=True)
            click.echo("Using default workflow instead", err=True)

        click.echo(f"Using workflow template from: {template_result['source']}")
        workflow_spec = template_result['workflow_spec']

        # Try to load configuration from store for parameter injection
        try:
            store = WorkflowConfigStore(namespace=namespace)
            config = store.load_config(session_name=session)

            if config:
                click.echo(f"Injecting parameters from session: {session}")
                workflow_spec = service.inject_parameters(workflow_spec, config)
            else:
                logger.debug(f"No configuration found for session: {session}")
        except Exception as e:
            logger.warning(f"Could not load workflow config: {e}")
            # Continue without parameter injection

        # Add name or generateName if provided
        if name:
            workflow_spec['metadata']['name'] = name
        elif 'name' not in workflow_spec['metadata'] and 'generateName' not in workflow_spec['metadata']:
            # Set a default generateName if neither name nor generateName is present
            workflow_spec['metadata']['generateName'] = 'workflow-'

        # Submit the workflow using the service layer
        submit_result = service.submit_workflow_to_argo(
            workflow_spec=workflow_spec,
            namespace=namespace,
            argo_server=argo_server,
            token=token,
            insecure=insecure
        )

        if not submit_result['success']:
            click.echo(f"Error: {submit_result['error']}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

        workflow_name = submit_result['workflow_name']
        workflow_uid = submit_result['workflow_uid']

        click.echo(f"Workflow submitted successfully!")
        click.echo(f"  Name: {workflow_name}")
        click.echo(f"  UID: {workflow_uid}")
        click.echo(f"  Namespace: {namespace}")

        logger.info(f"Workflow {workflow_name} submitted successfully")

        # Wait for workflow completion if requested
        if wait:
            click.echo(f"\nWaiting for workflow to complete (timeout: {timeout}s)...")

            try:
                phase, output_message = service.wait_for_workflow_completion(
                    namespace=namespace,
                    workflow_name=workflow_name,
                    timeout=timeout,
                    interval=wait_interval
                )

                click.echo(f"\nWorkflow completed with phase: {phase}")

                # Display output if available
                if output_message:
                    click.echo(f"Container output: {output_message}")

            except TimeoutError as e:
                click.echo(f"\n{str(e)}", err=True)
                click.echo(f"Workflow {workflow_name} is still running", err=True)
            except Exception as e:
                click.echo(f"\nError monitoring workflow: {str(e)}", err=True)

    except Exception as e:
        logger.exception(f"Unexpected error submitting workflow: {e}")
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
