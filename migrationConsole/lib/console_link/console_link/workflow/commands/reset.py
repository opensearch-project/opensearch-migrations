"""Reset command for workflow CLI - tears down workflow resources via CRD status patching.

Workflow templates watch for status.phase == Teardown on migration CRDs.
This command patches CRDs to trigger that teardown, letting the workflow handle cleanup.
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


def _show_resources(namespace, crds):
    """List mode: show all migration CRDs and approval gates."""
    click.echo(f"Migration resources in namespace '{namespace}':")
    for plural, name, phase in crds:
        kind = plural.rstrip('s')
        click.echo(f"  {kind}/{name:<35} ({phase})")
    for name, phase in list_approval_gates(namespace):
        click.echo(f"  approvalgate/{name:<30} ({phase})")
    click.echo()
    click.echo("Use 'workflow reset <name>' to teardown a resource.")
    click.echo("Use 'workflow reset *' to teardown all and delete the workflow.")


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


@click.command(name="reset")
@click.argument('path', required=False, default=None)
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
def reset_command(ctx, path, workflow_name, argo_server, namespace, insecure, token):
    """Reset workflow resources by signaling teardown via CRD status.

    With no arguments, lists migration CRDs and their status.
    With a NAME, patches matching CRDs to Teardown phase.
    With '*', patches all non-Teardown CRDs, waits for workflow completion, deletes workflow.

    Example:
        workflow reset                     # list resettable resources
        workflow reset source-proxy        # teardown just capture proxy
        workflow reset *                   # teardown everything and delete workflow
    """
    try:
        load_k8s_config()
        crds = _list_migration_crds(namespace)

        if not crds:
            click.echo("No migration resources found.")
            return

        if path is None:
            _show_resources(namespace, crds)
            return

        # Find CRDs to patch
        if path == '*':
            targets = [(p, n, ph) for p, n, ph in crds if ph != 'Teardown']
        else:
            targets = [(p, n, ph) for p, n, ph in crds if n == path and ph != 'Teardown']

        if not targets:
            click.echo(f"No resources to teardown matching '{path}'.")
            return

        if not _patch_targets(namespace, targets):
            ctx.exit(ExitCode.FAILURE.value)
            return

        if path == '*':
            _reset_all(namespace, workflow_name, argo_server, token, insecure)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
