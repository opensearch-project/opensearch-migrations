"""Reset command for workflow CLI - tears down migration resources.

How it works (for developers):

Each long-running migration component (proxy, replayer, backfill) has a corresponding
custom resource (CRD) that tracks its lifecycle phase: Created → Ready → Teardown.
The Argo workflow templates block on these resources via waitForExistingResource,
so patching status.phase to "Teardown" unblocks the workflow and lets it run its
own cleanup logic (deleting Deployments, coordinator clusters, etc.).

This file has both PATCH and DELETE operations because they serve different purposes:
- PATCH (status.phase = Teardown): signals each component to begin its teardown
  sequence. The workflow handles the actual cleanup.
- DELETE (workflow deletion): after all components have torn down and the workflow
  has reached a terminal phase, we delete the workflow itself so a new one can be
  submitted.

The --all flag combines both: patch all resources → wait for workflow completion → delete.
Single-resource reset only patches — it doesn't touch the workflow.
"""

import fnmatch
import logging
import os

import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .crd_utils import CRD_GROUP, CRD_VERSION, has_glob
from .suspend_steps import (
    wait_for_workflow_completion,
    delete_workflow,
)

logger = logging.getLogger(__name__)
TEARDOWN_RESOURCES = ['capturedtraffics', 'snapshotmigrations', 'trafficreplays']
DISPLAY_NAMES = {
    'capturedtraffics': 'Capture Proxy',
    'snapshotmigrations': 'Snapshot Migration',
    'trafficreplays': 'Traffic Replay',
}


def _list_migration_resources(namespace):
    """List all migration resources with their status phase. Returns list of (plural, name, phase)."""
    custom = client.CustomObjectsApi()
    results = []
    for plural in TEARDOWN_RESOURCES:
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


def _find_resource_by_name(namespace, name):
    """Find a single resource by name across all migration resource types. Returns (plural, name, phase) or None."""
    custom = client.CustomObjectsApi()
    for plural in TEARDOWN_RESOURCES:
        try:
            item = custom.get_namespaced_custom_object(
                group=CRD_GROUP, version=CRD_VERSION,
                namespace=namespace, plural=plural, name=name
            )
            phase = item.get('status', {}).get('phase', 'Unknown')
            return (plural, name, phase)
        except ApiException:
            pass
    return None


def _patch_teardown(namespace, plural, name):
    """Signal a resource to begin teardown. Returns True if successful."""
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
    """List mode: show all migration resources with friendly display names."""
    click.echo("Migration resources:")
    for plural, name, phase in crds:
        display = DISPLAY_NAMES.get(plural, plural)
        click.echo(f"  {display}: {name:<35} ({phase})")
    click.echo()
    click.echo("Use 'workflow reset <name>' to teardown a specific resource.")
    click.echo("Use 'workflow reset --all' to teardown all resources and delete the workflow.")


def _patch_targets(namespace, targets):
    """Patch a list of resources to Teardown. Returns True if all succeeded."""
    for plural, name, phase in targets:
        if _patch_teardown(namespace, plural, name):
            click.echo(f"  ✓ Patched {name} to Teardown")
        else:
            click.echo(f"  ✗ Failed to patch {name}", err=True)
            return False
    return True


def _reset_all(namespace, workflow_name, argo_server, token, insecure):
    """Wait for workflow to complete after teardown signals, then delete workflow."""
    click.echo("Waiting for workflow to complete...")
    phase = wait_for_workflow_completion(workflow_name, namespace, argo_server, token, insecure)
    if phase:
        click.echo(f"Workflow finished: {phase}")
    else:
        click.echo("Timed out waiting — force deleting workflow.", err=True)

    if delete_workflow(workflow_name, namespace, argo_server, token, insecure):
        click.echo(f"  ✓ Deleted workflow '{workflow_name}'")
    else:
        click.echo(f"  ✗ Failed to delete workflow '{workflow_name}'", err=True)


def _get_resource_completions(ctx, param, incomplete):
    try:
        load_k8s_config()
        crds = _list_migration_resources(ctx.params.get('namespace', 'ma'))
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
    """Reset workflow resources by signaling teardown.

    With no arguments, lists migration resources and their status.
    With a NAME or glob pattern, signals matching resources to begin teardown.
    With --all, tears down all active resources, waits for workflow completion, deletes workflow.

    Example:
        workflow reset                     # list resettable resources
        workflow reset source-proxy        # teardown just capture proxy
        workflow reset "source-*"          # teardown all matching resources
        workflow reset --all               # teardown everything and delete workflow
    """
    try:
        load_k8s_config()

        if path is not None:
            if has_glob(path):
                # Glob pattern — list all and filter
                crds = _list_migration_resources(namespace)
                targets = [(p, n, ph) for p, n, ph in crds
                           if fnmatch.fnmatch(n, path) and ph != 'Teardown']
            else:
                # Exact name — direct lookup
                match = _find_resource_by_name(namespace, path)
                targets = [match] if match and match[2] != 'Teardown' else []

            if not targets:
                click.echo(f"No resources to teardown matching '{path}'.")
                return
            if not _patch_targets(namespace, targets):
                ctx.exit(ExitCode.FAILURE.value)
            return

        crds = _list_migration_resources(namespace)

        if not crds and not reset_all:
            click.echo("No migration resources found.")
            return

        if not reset_all:
            _show_resources(crds)
            return

        targets = [(p, n, ph) for p, n, ph in crds if ph != 'Teardown']
        if targets:
            if not _patch_targets(namespace, targets):
                ctx.exit(ExitCode.FAILURE.value)
                return

        _reset_all(namespace, workflow_name, argo_server, token, insecure)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
