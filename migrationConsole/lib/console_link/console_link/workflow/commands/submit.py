"""Submit command for workflow CLI - submits workflows to Argo Workflows.

If a workflow already exists, it is automatically stopped, deleted, and
resubmitted — preserving existing CRD-owned resources (proxy, Kafka, etc.).
"""

import logging
import os
import subprocess
import click

from ..models.utils import ExitCode
from ..models.workflow_config_store import WorkflowConfigStore
from ..services.workflow_service import WorkflowService
from ..services.script_runner import ScriptRunner
from .suspend_steps import argo_stop, delete_workflow, workflow_exists
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME

logger = logging.getLogger(__name__)


def _handle_workflow_wait(
        service: WorkflowService,
        namespace: str,
        workflow_name: str,
        timeout: int,
        wait_interval: int):
    """Handle waiting for workflow completion."""
    click.echo(f"\nWaiting for workflow to complete (timeout: {timeout}s)...")

    try:
        phase, output_message = service.wait_for_workflow_completion(
            namespace=namespace,
            workflow_name=workflow_name,
            timeout=timeout,
            interval=wait_interval
        )

        click.echo(f"\nWorkflow completed with phase: {phase}")

        if output_message:
            click.echo(f"Container output: {output_message}")

    except TimeoutError as e:
        click.echo(f"\n{str(e)}", err=True)
        click.echo(f"Workflow {workflow_name} is still running", err=True)
    except Exception as e:
        click.echo(f"\nError monitoring workflow: {str(e)}", err=True)


def _remove_existing_workflow(workflow_name, namespace, argo_server, token, insecure):
    """Stop and delete an existing workflow if one is found. Returns True if removed."""
    if not workflow_exists(workflow_name, namespace, argo_server, token, insecure):
        return False

    click.echo(f"Existing workflow '{workflow_name}' found — replacing...")
    if argo_stop(workflow_name, namespace, argo_server, token, insecure):
        click.echo("  ✓ Stopped")
    else:
        click.echo("  ⚠ Could not stop (may already be finished)")
    if delete_workflow(workflow_name, namespace, argo_server, token, insecure):
        click.echo("  ✓ Deleted")
    else:
        click.echo("  ⚠ Could not delete")
    return True


@click.command(name="submit")
@click.option(
    '--namespace',
    default='ma',
    help='Kubernetes namespace for the workflow (default: ma)'
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
@click.option(
    '--workflow-name',
    default=DEFAULT_WORKFLOW_NAME,
    help='Name of the workflow to replace if it already exists'
)
@click.option(
    '--argo-server',
    default=lambda: os.environ.get(
        'ARGO_SERVER',
        f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}:"
        f"{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}"
    ),
    help='Argo Server URL'
)
@click.option('--insecure', is_flag=True, default=False, help='Skip TLS verification for Argo server')
@click.option('--token', default=None, help='Bearer token for Argo authentication')
@click.pass_context
def submit_command(ctx, namespace, wait, timeout, wait_interval, session,
                   workflow_name, argo_server, insecure, token):
    """Submit a migration workflow using the config processor.

    If a workflow already exists, it is automatically stopped, deleted, and
    resubmitted — preserving existing CRD-owned resources (proxy, Kafka, etc.).

    The workflow is created using the config processor scripts located in
    CONFIG_PROCESSOR_DIR (default: /root/configProcessor).

    Example:
        workflow submit
        workflow submit --wait
        workflow submit --wait --timeout 300
    """
    store = WorkflowConfigStore(namespace=namespace)
    config = store.load_config(session_name=session)

    if not config or not config.data:
        click.echo(f"Error: No workflow configuration found for session '{session}'", err=True)
        click.echo("\nPlease configure the workflow first using 'workflow configure edit'", err=True)
        ctx.exit(ExitCode.FAILURE.value)

    click.echo("NOT checking if all secrets have been created.  Run `workflow configure edit` to confirm")

    try:
        runner = ScriptRunner()
        config_yaml = config.raw_yaml

        click.echo(f"Initializing workflow from session: {session}")

        # Remove existing workflow if present
        _remove_existing_workflow(workflow_name, namespace, argo_server, token, insecure)

        # Submit workflow
        click.echo(f"Submitting workflow to namespace: {namespace}")
        try:
            submit_result = runner.submit_workflow(config_yaml, [])
            workflow_name = submit_result.get('workflow_name', 'unknown')

            click.echo("\nWorkflow submitted successfully")
            click.echo(f"  Name: {workflow_name}")
            click.echo(f"  Namespace: {namespace}")

            logger.info(f"Workflow {workflow_name} submitted successfully with namespace {namespace}")

            if wait:
                service = WorkflowService()
                _handle_workflow_wait(service, namespace, workflow_name, timeout, wait_interval)

        except FileNotFoundError as e:
            click.echo(f"Error: {str(e)}", err=True)
            click.echo("\nEnsure CONFIG_PROCESSOR_DIR is set correctly and contains:", err=True)
            click.echo("  - createMigrationWorkflowFromUserConfiguration.sh", err=True)
            ctx.exit(ExitCode.FAILURE.value)
        except subprocess.CalledProcessError as e:
            stderr = e.stderr or ''
            click.echo(f"Script failed with exit code {e.returncode}", err=True)
            if stderr:
                logger.info(f"stderr: {stderr}")
            ctx.exit(ExitCode.FAILURE.value)
        except Exception as e:
            click.echo(f"Error submitting workflow: {str(e)}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
