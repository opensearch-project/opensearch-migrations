"""Approve command for workflow CLI - approves pending gates via CRD status patching."""

import logging
import os

import click
from click.shell_completion import CompletionItem
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .argo_utils import DEFAULT_ARGO_SERVER_URL
from .crd_utils import (
    CRD_GROUP,
    CRD_VERSION,
    cached_crd_completions,
    list_migration_resources,
    match_names,
)

logger = logging.getLogger(__name__)


def _pending_gate_names(namespace):
    return [
        name
        for _, name, phase, _ in list_migration_resources(namespace, ['approvalgates'])
        if phase in ('Initialized', 'Pending')
    ]


def approve_gate(namespace, name):
    """Patch an ApprovalGate's status.phase to Approved. Returns True if patched."""
    custom = client.CustomObjectsApi()
    try:
        custom.patch_namespaced_custom_object_status(
            group=CRD_GROUP, version=CRD_VERSION,
            namespace=namespace, plural='approvalgates', name=name,
            body={'status': {'phase': 'Approved'}}
        )
        return True
    except ApiException as e:
        logger.error(f"Failed to approve {name}: {e}")
        return False


def get_approval_task_name_completions(ctx, _, incomplete):
    """Shell completion for pending approval gate names."""
    namespace = ctx.params.get('namespace', 'ma')
    return [
        CompletionItem(name)
        for name in cached_crd_completions(namespace, 'approval_gates', _pending_gate_names)
        if name.startswith(incomplete)
    ]


@click.command(name="approve")
@click.argument('task-names', nargs=-1, required=True, shell_complete=get_approval_task_name_completions)
@click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions)
@click.option(
    '--argo-server',
    default=lambda: os.environ.get(
        'ARGO_SERVER',
        DEFAULT_ARGO_SERVER_URL
    ),
    help='Argo Server URL'
)
@click.option('--namespace', default='ma')
@click.option('--insecure', is_flag=True, default=True)
@click.option('--token', help='Bearer token for authentication')
@click.pass_context
def approve_command(ctx, task_names, workflow_name, argo_server, namespace, insecure, token):
    """Approve pending workflow gates matching TASK_NAMES.

    Each TASK_NAME can be an exact name or glob pattern (e.g., *.evaluateMetadata).

    Example:
        workflow approve source.target.evaluateMetadata
        workflow approve "*.migrateMetadata"
        workflow approve step1 step2 step3
    """
    try:
        load_k8s_config()
        pending_names = _pending_gate_names(namespace)
        if not pending_names:
            click.echo("No pending approval gates found.")
            ctx.exit(ExitCode.FAILURE.value)
            return

        matches = []
        for pattern in task_names:
            for name in match_names(pending_names, pattern):
                if name not in matches:
                    matches.append(name)

        if not matches:
            click.echo(f"No pending gates match {task_names}.")
            click.echo("Available pending gates:")
            for name in pending_names:
                click.echo(f"  - {name}")
            ctx.exit(ExitCode.FAILURE.value)
            return

        for name in matches:
            if approve_gate(namespace, name):
                click.echo(f"  ✓ Approved {name}")
            else:
                click.echo(f"  ✗ Failed to approve {name}", err=True)
                ctx.exit(ExitCode.FAILURE.value)
                return

        click.echo(f"\nApproved {len(matches)} gate(s).")

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
