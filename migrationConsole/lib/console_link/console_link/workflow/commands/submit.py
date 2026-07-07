"""Submit command for workflow CLI - submits workflows to Argo Workflows.

If a workflow already exists, it is stopped, deleted, and resubmitted while
preserving any migration CRD-owned resources.
"""

import logging
import subprocess
import click
import time

from ..models.utils import ExitCode, load_k8s_config, get_current_namespace
from ..models.workflow_config_store import WorkflowConfigStore
from ..services.workflow_service import WorkflowService
from ..services.script_runner import ScriptRunner
from .argo_utils import workflow_exists, stop_workflow, delete_workflow, wait_until_workflow_deleted
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .secret_utils import get_credentials_secret_store_for_namespace, verify_configured_secrets_exist

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


def _remove_existing_workflow(workflow_name, namespace):
    """Stop and delete an existing workflow if one is found. Returns True if removed."""
    if not workflow_exists(namespace, workflow_name):
        return False

    click.echo(f"Existing workflow '{workflow_name}' found; replacing...")
    if stop_workflow(namespace, workflow_name):
        click.echo("  Stopped")
    else:
        click.echo("  Could not stop (may already be finished)")

    if delete_workflow(namespace, workflow_name):
        click.echo("  Deleted")
    else:
        click.echo("  Could not delete")
        return True

    if not wait_until_workflow_deleted(namespace, workflow_name):
        raise click.ClickException(
            f"Timed out waiting for workflow '{workflow_name}' to be deleted"
        )

    return True


@click.command(name="submit")
@click.option(
    '--namespace',
    default=get_current_namespace, hidden=True, envvar='WORKFLOW_NAMESPACE',
    help='Kubernetes namespace for the workflow'
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
    hidden=True,
    help='Configuration session name to load parameters from (default: default)'
)
@click.option(
    '--workflow-name',
    default=DEFAULT_WORKFLOW_NAME,
    shell_complete=get_workflow_completions,
    hidden=True,
    help='Name of the workflow to replace if it already exists'
)
@click.option(
    '--unique-run-nonce',
    default=str(int(time.time())),
    hidden=True,
    help='id that gets appended to downstream as uniqueRunNonce arg (and is appended to some naming such as '
         'snapshotName downstream)'
)
@click.pass_context
def submit_command(
        ctx, namespace, wait, timeout, wait_interval, session, workflow_name, unique_run_nonce):
    """Submit a migration workflow using the config processor.

    If a workflow already exists, it is automatically stopped, deleted, and
    resubmitted while preserving existing CRD-owned resources.

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

    try:
        load_k8s_config()

        # Verify that every HTTP-Basic secret referenced by the saved config still
        # exists in the cluster. If the config has changed or a secret has been
        # deleted since `workflow configure edit`, fail fast with a clear error
        # rather than letting the workflow fail mid-run.
        secret_store = get_credentials_secret_store_for_namespace(namespace)
        verify_configured_secrets_exist(secret_store, config.raw_yaml)

        runner = ScriptRunner()

        config_yaml = config.raw_yaml

        click.echo(f"Initializing workflow from session: {session}")
        _remove_existing_workflow(workflow_name, namespace)

        click.echo(f"Submitting workflow to namespace: {namespace}")
        try:
            submit_result = runner.submit_workflow(
                config_yaml,
                [
                    "--workflow-name", workflow_name,
                    "--unique-run-nonce", unique_run_nonce
                ],
            )

            workflow_name = submit_result.get('workflow_name', 'unknown')

            click.echo("\nWorkflow submitted successfully")
            click.echo(f"  Name: {workflow_name}")
            click.echo(f"  Namespace: {namespace}")

            for warning in submit_result.get('warnings', []):
                click.echo(f"\n{warning}", err=True)

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
            click.echo(f"Script failed with exit code {e.returncode}", err=True)
            if e.stderr:
                click.echo(e.stderr, err=True)
            ctx.exit(ExitCode.FAILURE.value)
        except Exception as e:
            click.echo(f"Error submitting workflow: {str(e)}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
