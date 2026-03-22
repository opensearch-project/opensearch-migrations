"""Reset command for workflow CLI - tears down workflow resources via CRD status patching.

Teardown uses a two-pronged mechanism:
1. CRD patch: Sets status.phase = Teardown on migration CRDs. The Argo workflow
   templates watch for this phase and initiate graceful shutdown of their pods.
2. Deployment deletion: Deletes Kubernetes Deployments owned by the workflow to
   immediately kill any running tasks that don't respond to the graceful signal.

Together these ensure resources are cleaned up promptly even if the graceful path stalls.
"""

import logging
import os

import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .approve import list_approval_gates, approve_gate
from .suspend_steps import (
    wait_for_workflow_completion,
    delete_workflow,
)

logger = logging.getLogger(__name__)

CRD_GROUP = 'migrations.opensearch.org'
CRD_VERSION = 'v1alpha1'
TEARDOWN_CRDS = ['capturedtraffics', 'snapshotmigrations', 'trafficreplays']
DISPLAY_NAMES = {
    'capturedtraffics': 'Capture Proxy',
    'snapshotmigrations': 'Snapshot Migration',
    'trafficreplays': 'Traffic Replay',
}


def _list_migration_crds(namespace):
    """List all migration CRDs with their status phase. Returns list of (plural, name, phase)."""
    custom = client.CustomObjectsApi()
    results = []
    for plural in TEARDOWN_CRDS:
        try:
            items = custom.list_namespaced_custom_object(
                group=CRD_GROUP, version=CRD_VERSION,
                namespace=namespace, plural=plural
            ).get('items', [])
            for item in items:
                name = item['metadata']['name']
                phase = item.get('status', {}).get('phase', 'Unknown')
                results.append((plural, name, phase))
        except ApiException:
            pass
    return results


def _patch_teardown(namespace, plural, name):
    """Patch a CRD's status.phase to Teardown. Returns True if patched."""
    custom = client.CustomObjectsApi()
    try:
        custom.patch_namespaced_custom_object_status(
            group=CRD_GROUP, version=CRD_VERSION,
            namespace=namespace, plural=plural, name=name,
            body={'status': {'phase': 'Teardown'}}
        )
        return True
    except ApiException as e:
        logger.error(f"Failed to patch {plural}/{name}: {e}")
        return False


def _show_resources(crds):
    """List mode: show all migration CRDs with friendly display names."""
    click.echo("Migration resources:")
    for plural, name, phase in crds:
        display = DISPLAY_NAMES.get(plural, plural)
        click.echo(f"  {display}: {name:<35} ({phase})")
    click.echo()
    click.echo("Use 'workflow reset <name>' to teardown a specific resource.")
    click.echo("Use 'workflow reset --all' to teardown all resources and delete the workflow.")


def _patch_targets(namespace, targets):
    """Patch a list of CRDs to Teardown. Returns True if all succeeded."""
    for plural, name, phase in targets:
        if _patch_teardown(namespace, plural, name):
            click.echo(f"  ✓ Patched {name} to Teardown")
        else:
            click.echo(f"  ✗ Failed to patch {name}", err=True)
            return False
    return True


def _delete_migration_deployments(namespace):
    """Delete Deployments owned by the workflow to force-kill running tasks."""
    apps = client.AppsV1Api()
    try:
        deployments = apps.list_namespaced_deployment(
            namespace=namespace,
            label_selector='workflows.argoproj.io/workflow'
        )
        for dep in deployments.items:
            name = dep.metadata.name
            try:
                apps.delete_namespaced_deployment(name=name, namespace=namespace)
                click.echo(f"  ✓ Deleted deployment {name}")
            except ApiException as e:
                logger.warning(f"Failed to delete deployment {name}: {e}")
    except ApiException:
        pass


def _reset_all(namespace, workflow_name, argo_server, token, insecure):
    """Approve all gates, delete deployments, wait for completion, delete workflow."""
    _delete_migration_deployments(namespace)

    for name, phase in list_approval_gates(namespace):
        if phase == 'Pending' and approve_gate(namespace, name):
            click.echo(f"  ✓ Approved gate {name}")

    click.echo("Waiting for workflow to complete...")
    phase = wait_for_workflow_completion(workflow_name, namespace, argo_server, token, insecure)
    if phase:
        click.echo(f"Workflow finished: {phase}")
    else:
        click.echo("Timed out waiting for workflow completion.", err=True)

    if delete_workflow(workflow_name, namespace, argo_server, token, insecure):
        click.echo(f"  ✓ Deleted workflow '{workflow_name}'")
    else:
        click.echo(f"  ✗ Failed to delete workflow '{workflow_name}'", err=True)


def _get_resource_completions(ctx, param, incomplete):
    try:
        load_k8s_config()
        crds = _list_migration_crds(ctx.params.get('namespace', 'ma'))
        return [n for _, n, ph in crds if n.startswith(incomplete) and ph != 'Teardown']
    except Exception:
        return []


@click.command(name="reset")
@click.argument('path', required=False, default=None, shell_complete=_get_resource_completions)
@click.option('--all', 'reset_all', is_flag=True, default=False, help='Teardown all resources and delete workflow')
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
def reset_command(ctx, path, reset_all, workflow_name, argo_server, namespace, insecure, token):
    """Reset workflow resources by signaling teardown via CRD status.

    With no arguments, lists migration CRDs and their status.
    With a NAME, patches matching CRDs to Teardown phase.
    With --all, patches all non-Teardown CRDs, waits for workflow completion, deletes workflow.

    Example:
        workflow reset                     # list resettable resources
        workflow reset source-proxy        # teardown just capture proxy
        workflow reset --all               # teardown everything and delete workflow
    """
    try:
        load_k8s_config()

        crds = _list_migration_crds(namespace)

        if not crds and not reset_all:
            click.echo("No migration resources found.")
            return

        if path is None and not reset_all:
            _show_resources(crds)
            return

        # Find CRDs to patch
        if reset_all:
            targets = [(p, n, ph) for p, n, ph in crds if ph != 'Teardown']
        else:
            targets = [(p, n, ph) for p, n, ph in crds if n == path and ph != 'Teardown']

        if targets:
            if not _patch_targets(namespace, targets):
                ctx.exit(ExitCode.FAILURE.value)
                return
        elif not reset_all:
            click.echo(f"No resources to teardown matching '{path}'.")
            return

        if reset_all:
            _reset_all(namespace, workflow_name, argo_server, token, insecure)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
