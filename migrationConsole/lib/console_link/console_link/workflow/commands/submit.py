"""Submit command for workflow CLI - submits workflows to Argo Workflows."""

import logging
import os
import subprocess
import click

from ..models.utils import ExitCode
from ..models.workflow_config_store import WorkflowConfigStore
from ..services.workflow_service import WorkflowService
from ..services.script_runner import ScriptRunner

logger = logging.getLogger(__name__)

# Terminal workflow phases
ENDING_PHASES = ["Succeeded", "Failed", "Error", "Stopped", "Terminated"]


def _handle_workflow_wait(
        service: WorkflowService,
        namespace: str,
        workflow_name: str,
        argo_server: str,
        timeout: int,
        wait_interval: int,
        token: str = None,
        insecure: bool = False):
    """Handle waiting for workflow completion.

    Args:
        service: WorkflowService instance
        namespace: Kubernetes namespace
        workflow_name: Name of workflow
        argo_server: Argo Server URL
        timeout: Timeout in seconds
        wait_interval: Interval between checks
        token: Optional bearer token for authentication
        insecure: Whether to skip TLS verification
    """
    click.echo(f"\nWaiting for workflow to complete (timeout: {timeout}s)...")

    try:
        phase, output_message = service.wait_for_workflow_completion(
            namespace=namespace,
            workflow_name=workflow_name,
            argo_server=argo_server,
            token=token,
            insecure=insecure,
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
    '--argo-server',
    default=f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
            f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}",
    help='Argo Server URL (default: ARGO_SERVER_SERVICE_HOST:ARGO_SERVER_SERVICE_PORT)'
)
@click.option('--insecure', is_flag=True, default=False, help='Skip TLS verification')
@click.option('--token', default=None, help='Bearer token for Argo Server authentication')
@click.option(
    '--session',
    default='default',
    help='Configuration session name to load parameters from (default: default)'
)
@click.pass_context
def submit_command(ctx, namespace, wait, timeout, wait_interval, argo_server, insecure, token, session):
    """Submit a migration workflow using the config processor.

    This command submits a migration workflow by:
    1. Loading the configuration from the specified session
    2. Initializing the workflow state in etcd
    3. Submitting the workflow to Kubernetes via the config processor script

    The workflow is created using the actual config processor and submission scripts
    located in CONFIG_PROCESSOR_DIR (default: /root/configProcessor).

    Environment variables:
        - CONFIG_PROCESSOR_DIR: Path to config processor (default: /root/configProcessor)
        - ETCD_ENDPOINTS: etcd endpoints (default: http://etcd.{namespace}.svc.cluster.local:2379)

    Example:
        workflow submit
        workflow submit --wait
        workflow submit --wait --timeout 300
    """
    # Check if configuration exists
    store = WorkflowConfigStore(namespace=namespace)
    config = store.load_config(session_name=session)

    if not config or not config.data:
        click.echo(f"Error: No workflow configuration found for session '{session}'", err=True)
        click.echo("\nPlease configure the workflow first using 'workflow configure edit'", err=True)
        ctx.exit(ExitCode.FAILURE.value)

    click.echo("NOT checking if all secrets have been created.  Run `workflow configure edit` to confirm")

    try:
        # Initialize ScriptRunner
        runner = ScriptRunner()

        # Get config data as YAML
        config_yaml = config.to_yaml()

        # Get etcd_endpoints from environment variable or use default
        etcd_endpoints = os.getenv('ETCD_ENDPOINTS')
        if not etcd_endpoints:
            etcd_endpoints = f"http://etcd.{namespace}.svc.cluster.local:2379"

        click.echo(f"Initializing workflow from session: {session}")

        # Step 2: Submit workflow to Kubernetes
        click.echo(f"Submitting workflow to namespace: {namespace}")
        try:
            # Construct arguments for the submission script
            args = [
                f"--prefix {namespace}",
                f"--etcd-endpoints {etcd_endpoints}"
            ]

            submit_result = runner.submit_workflow(config_yaml, args)

            workflow_name = submit_result.get('workflow_name', 'unknown')

            click.echo("\nWorkflow submitted successfully")
            click.echo(f"  Name: {workflow_name}")
            click.echo(f"  Namespace: {namespace}")

            logger.info(f"Workflow {workflow_name} submitted successfully with namespace {namespace}")

            # Wait for workflow completion if requested
            if wait:
                service = WorkflowService()
                _handle_workflow_wait(service, namespace, workflow_name, argo_server,
                                     timeout, wait_interval, token, insecure)

        except FileNotFoundError as e:
            click.echo(f"Error: {str(e)}", err=True)
            click.echo("\nEnsure CONFIG_PROCESSOR_DIR is set correctly and contains:", err=True)
            click.echo("  - createMigrationWorkflowFromUserConfiguration.sh", err=True)
            ctx.exit(ExitCode.FAILURE.value)
        except subprocess.CalledProcessError:
            # Error details already printed by script_runner with direct_output=True
            ctx.exit(ExitCode.FAILURE.value)
        except Exception as e:
            click.echo(f"Error submitting workflow: {str(e)}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
