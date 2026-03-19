"""Approve command for workflow CLI - approves pending gates via CRD status patching."""

import logging
import os

import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions

logger = logging.getLogger(__name__)

CRD_GROUP = 'migrations.opensearch.org'
CRD_VERSION = 'v1alpha1'


def list_approval_gates(namespace):
    """List ApprovalGate CRDs. Returns list of (name, phase)."""
    custom = client.CustomObjectsApi()
    try:
        items = custom.list_namespaced_custom_object(
            group=CRD_GROUP, version=CRD_VERSION,
            namespace=namespace, plural='approvalgates'
        ).get('items', [])
        return [(item['metadata']['name'], item.get('status', {}).get('phase', 'Unknown'))
                for item in items]
    except ApiException:
        return []


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


@click.command(name="approve")
@click.argument('task-names', nargs=-1, required=True)
@click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions)
@click.option(
    '--argo-server',
    default=lambda: os.environ.get(
        'ARGO_SERVER',
        f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}:"
        f"{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}"
    ),
    help='Argo Server URL'
)
@click.option('--namespace', default='ma')
@click.option('--insecure', is_flag=True, default=False)
@click.option('--token', help='Bearer token for authentication')
@click.pass_context
def approve_command(ctx, task_names, workflow_name, argo_server, namespace, insecure, token):
    """Approve pending workflow gates matching TASK_NAMES.

    Each TASK_NAME can be an exact name or glob pattern (e.g., *.evaluateMetadata).

    Example:
        workflow approve source.target.snap.migration.evaluateMetadata
        workflow approve "*.evaluateMetadata"
        workflow approve gate1 gate2
    """
    import fnmatch
    try:
        load_k8s_config()
        gates = list_approval_gates(namespace)

        pending = [(name, phase) for name, phase in gates if phase == 'Pending']
        if not pending:
            click.echo("No pending approval gates found.")
            ctx.exit(ExitCode.FAILURE.value)
            return

        # Match against patterns
        matches = []
        for pattern in task_names:
            for name, phase in pending:
                if fnmatch.fnmatch(name, pattern) and name not in matches:
                    matches.append(name)

        if not matches:
            click.echo(f"No pending gates match {task_names}.")
            click.echo("Available pending gates:")
            for name, _ in pending:
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
