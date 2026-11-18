"""Submit command for workflow CLI - submits workflows to Argo Workflows.

If a workflow already exists, it is stopped, deleted, and resubmitted while
preserving any migration CRD-owned resources.
"""

import logging
import json
import subprocess
import click
import time

from typing import Optional

from ..models.utils import ExitCode, load_k8s_config, get_current_namespace
from ..models.workflow_config_store import WorkflowConfigStore
from ..services.workflow_service import WorkflowService
from ..services.script_runner import ScriptRunner
from ..services.config_edit_service import (
    AdmissionPreflightBlocked,
    ConfigEditService,
)
from ..services.admission_preflight import AdmissionPreflightReport
from .argo_utils import workflow_exists, stop_workflow, delete_workflow, wait_until_workflow_deleted
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .secret_utils import get_credentials_secret_store_for_namespace, verify_configured_secrets_exist
from .hints import (
    hint_after_submit,
    hint_after_submit_wait,
    hint_after_submit_wait_error,
    hint_on_submit_error,
)

logger = logging.getLogger(__name__)


def _handle_workflow_wait(
        service: WorkflowService,
        namespace: str,
        workflow_name: str,
        timeout: int,
        wait_interval: int) -> Optional[str]:
    """Handle waiting for workflow completion.

    Returns the final phase string, or ``None`` if monitoring failed before a phase could be
    determined (the workflow was still submitted; its state is simply unknown).
    """
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

        return phase

    except TimeoutError as e:
        click.echo(f"\n{str(e)}", err=True)
        click.echo(f"Workflow {workflow_name} is still running", err=True)
        return 'Running'
    except Exception as e:
        click.echo(f"\nError monitoring workflow: {str(e)}", err=True)
        return None


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
@click.option(
    '--verbose-submit-output',
    is_flag=True,
    default=False,
    help='Show detailed Kubernetes resource output from the submit script.'
)
@click.option(
    '--dry-run',
    is_flag=True,
    default=False,
    help='Validate and admission-check the exact submission without applying it.'
)
@click.option(
    '--output',
    'output_format',
    type=click.Choice(['text', 'json']),
    default='text',
    show_default=True,
    help='Preflight output format; JSON is available with --dry-run.'
)
@click.pass_context
def submit_command(
        ctx, namespace, wait, timeout, wait_interval, session, workflow_name, unique_run_nonce,
        verbose_submit_output, dry_run, output_format):
    """Submit a migration workflow using the config processor.

    If a workflow already exists, it is automatically stopped, deleted, and
    resubmitted while preserving existing CRD-owned resources.

    Example:
        workflow submit
        workflow submit --wait
        workflow submit --wait --timeout 300
    """
    if output_format != "text" and not dry_run:
        raise click.UsageError("--output json requires --dry-run")
    if wait and dry_run:
        raise click.UsageError("--wait cannot be combined with --dry-run")

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
        edit_service = ConfigEditService(
            namespace=namespace,
            runner=runner,
            secret_store=secret_store,
        )
        edit_service.validate_raw_config_for_submit(config_yaml)
        prepared = runner.prepare_workflow(
            config_yaml,
            [
                "--workflow-name", workflow_name,
                "--namespace", namespace,
                "--unique-run-nonce", unique_run_nonce,
            ],
            quiet=not verbose_submit_output,
        )
        try:
            preflight = AdmissionPreflightReport.from_payload(
                prepared.report
            )
            if dry_run and output_format == "json":
                click.echo(json.dumps(prepared.report, indent=2))
                if not preflight.allowed:
                    ctx.exit(ExitCode.FAILURE.value)
                return

            if not preflight.allowed:
                raise AdmissionPreflightBlocked(preflight)

            if dry_run:
                click.echo(
                    "Admission preflight passed for "
                    f"{preflight.checked_resources} resources."
                )
                for action in preflight.deployment_actions:
                    click.echo(
                        f"Planned {action.action}: "
                        f"{action.kind} {action.name} - {action.message}"
                    )
                for issue in preflight.warning_issues:
                    click.echo(
                        f"Warning: {issue.kind} {issue.name}: "
                        f"{issue.message}"
                    )
                return

            for issue in preflight.warning_issues:
                click.echo(
                    f"Admission preflight warning for "
                    f"{issue.kind} {issue.name}: {issue.message}",
                    err=True,
                )

            for action in preflight.deployment_actions:
                click.echo(
                    f"Planned {action.action}: "
                    f"{action.kind} {action.name} - {action.message}"
                )

            click.echo(f"Initializing workflow from session: {session}")
            _remove_existing_workflow(workflow_name, namespace)

            click.echo(f"Submitting workflow to namespace: {namespace}")
            submit_result = runner.commit_prepared_workflow(prepared)

            workflow_name = submit_result.get('workflow_name', 'unknown')

            click.echo("\nWorkflow submitted successfully")
            click.echo(f"  Name: {workflow_name}")
            click.echo(f"  Namespace: {namespace}")

            for warning in submit_result.get('warnings', []):
                click.echo(f"\n{warning}", err=True)

            logger.info(f"Workflow {workflow_name} submitted successfully with namespace {namespace}")

            if wait:
                service = WorkflowService()
                phase = _handle_workflow_wait(service, namespace, workflow_name, timeout, wait_interval)
                if phase is None:
                    hint_after_submit_wait_error()
                else:
                    hint_after_submit_wait(phase)
            else:
                hint_after_submit()

        except FileNotFoundError as e:
            click.echo(f"Error: {str(e)}", err=True)
            click.echo("\nEnsure CONFIG_PROCESSOR_DIR is set correctly and contains:", err=True)
            click.echo("  - createMigrationWorkflowFromUserConfiguration.sh", err=True)
            ctx.exit(ExitCode.FAILURE.value)
        except subprocess.CalledProcessError as e:
            click.echo(f"Script failed with exit code {e.returncode}", err=True)
            if e.stderr:
                click.echo(e.stderr, err=True)
            hint_on_submit_error()
            ctx.exit(ExitCode.FAILURE.value)
        except click.exceptions.Exit:
            raise
        except Exception as e:
            click.echo(f"Error submitting workflow: {str(e)}", err=True)
            hint_on_submit_error()
            ctx.exit(ExitCode.FAILURE.value)
        finally:
            prepared.cleanup()

    except click.exceptions.Exit:
        raise
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
