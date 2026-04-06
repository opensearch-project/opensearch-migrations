"""Reset command for workflow CLI - tears down migration resources.

How it works (for developers):

Each long-running migration component has a corresponding custom resource (CRD)
that tracks its lifecycle. The Argo workflow templates block on these CRDs via
waitForCrdDeletion (kubectl wait --for=delete).

Teardown works by DELETING CRDs with foreground propagation policy. Kubernetes
ownerReferences on Deployments/StatefulSets ensure dependents are deleted first:
  SnapshotMigration CRD → Coordinator StatefulSet → RFS Deployment → Pods
  CapturedTraffic CRD → Proxy Deployment → Pods
  TrafficReplay CRD → Replayer Deployment → Pods
  KafkaCluster CRD → Strimzi Kafka resources

The --all flag deletes CRDs in reverse dependency order, then stops the workflow:
  1. TrafficReplay (replayer dies first)
  2. SnapshotMigration (RFS dies, then coordinator)
  3. KafkaCluster (Kafka dies, proxy switches to non-capture)
  4. CapturedTraffic (proxy dies)
  5. ApprovalGate (no dependents)
  6. argo stop + delete workflow
"""

import logging
import os
import time

import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config
from .autocomplete_workflows import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from .crd_utils import CRD_GROUP, CRD_VERSION, has_glob, match_names
from .suspend_steps import (
    argo_stop,
    delete_workflow,
)

logger = logging.getLogger(__name__)

# All resettable resource types (used for listing and single-resource reset)
TEARDOWN_RESOURCES = ['capturedtraffics', 'snapshotmigrations', 'trafficreplays', 'kafkaclusters']

# Ordered phases for --all reset (reverse dependency order)
ORDERED_TEARDOWN_PHASES = [
    'trafficreplays',
    'snapshotmigrations',
    'kafkaclusters',
    'capturedtraffics',
    'approvalgates',
]

DISPLAY_NAMES = {
    'capturedtraffics': 'Capture Proxy',
    'snapshotmigrations': 'Snapshot Migration',
    'trafficreplays': 'Traffic Replay',
    'kafkaclusters': 'Kafka Cluster',
    'approvalgates': 'Approval Gate',
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


def _delete_crd(namespace, plural, name):
    """Delete a CRD instance with foreground cascading deletion. Returns True if successful."""
    custom = client.CustomObjectsApi()
    try:
        custom.delete_namespaced_custom_object(
            group=CRD_GROUP, version=CRD_VERSION,
            namespace=namespace, plural=plural, name=name,
            body=client.V1DeleteOptions(propagation_policy='Foreground')
        )
        return True
    except ApiException as e:
        if e.status == 404:
            return True  # Already gone
        logger.error(f"Failed to delete {plural}/{name}: {e}")
        return False


def _wait_until_gone(namespace, plural, names, timeout=120):
    """Wait until all named CRDs are fully deleted (dependents gone, object removed)."""
    custom = client.CustomObjectsApi()
    deadline = time.time() + timeout
    remaining = set(names)
    while remaining and time.time() < deadline:
        gone = set()
        for name in remaining:
            try:
                custom.get_namespaced_custom_object(
                    group=CRD_GROUP, version=CRD_VERSION,
                    namespace=namespace, plural=plural, name=name
                )
            except ApiException as e:
                if e.status == 404:
                    gone.add(name)
        remaining -= gone
        if remaining:
            time.sleep(2)
    if remaining:
        logger.warning(f"Timed out waiting for deletion: {remaining}")


def _delete_crds_and_wait(namespace, plural, timeout=120):
    """Delete all CRDs of a type with foreground cascading, wait until gone."""
    custom = client.CustomObjectsApi()
    try:
        items = custom.list_namespaced_custom_object(
            group=CRD_GROUP, version=CRD_VERSION,
            namespace=namespace, plural=plural
        ).get('items', [])
    except ApiException:
        return

    names = []
    for item in items:
        name = item['metadata']['name']
        if _delete_crd(namespace, plural, name):
            click.echo(f"    ✓ Deleted {DISPLAY_NAMES.get(plural, plural)}: {name}")
            names.append(name)
        else:
            click.echo(f"    ✗ Failed to delete {name}", err=True)

    if names:
        _wait_until_gone(namespace, plural, names, timeout)


def _show_resources(crds):
    """List mode: show all migration resources with friendly display names."""
    click.echo("Migration resources:")
    for plural, name, phase in crds:
        display = DISPLAY_NAMES.get(plural, plural)
        click.echo(f"  {display}: {name:<35} ({phase})")
    click.echo()
    click.echo("Use 'workflow reset <name>' to delete a specific resource.")
    click.echo("Use 'workflow reset --all' to teardown all resources and delete the workflow.")


def _reset_all(namespace, workflow_name, argo_server, token, insecure):
    """Ordered CRD deletion, then argo stop + delete workflow."""
    click.echo("Tearing down migration resources (ordered)...")
    for i, plural in enumerate(ORDERED_TEARDOWN_PHASES, 1):
        display = DISPLAY_NAMES.get(plural, plural)
        click.echo(f"  Phase {i}: {display}")
        _delete_crds_and_wait(namespace, plural)

    click.echo("Stopping workflow...")
    if argo_stop(workflow_name, namespace, argo_server, token, insecure):
        click.echo(f"  ✓ Stopped workflow '{workflow_name}'")
    else:
        click.echo("  ⚠ Could not stop workflow (may already be finished)", err=True)

    if delete_workflow(workflow_name, namespace, argo_server, token, insecure):
        click.echo(f"  ✓ Deleted workflow '{workflow_name}'")
    else:
        click.echo(f"  ✗ Failed to delete workflow '{workflow_name}'", err=True)

    click.echo("Ready for resubmit.")


def _get_resource_completions(ctx, param, incomplete):
    try:
        load_k8s_config()
        crds = _list_migration_resources(ctx.params.get('namespace', 'ma'))
        return [n for _, n, _ in crds if n.startswith(incomplete)]
    except Exception:
        return []


def _resolve_targets(namespace, path):
    """Resolve a path (exact name or glob) to a list of targets."""
    if has_glob(path):
        crds = _list_migration_resources(namespace)
        matched_names = set(match_names([n for _, n, _ in crds], path))
        return [(p, n, ph) for p, n, ph in crds if n in matched_names]
    match = _find_resource_by_name(namespace, path)
    return [match] if match else []


def _handle_targeted_reset(ctx, namespace, path):
    """Handle reset of specific resource(s) by name or glob pattern."""
    targets = _resolve_targets(namespace, path)
    if not targets:
        click.echo(f"No resources matching '{path}'.")
        return
    for plural, name, phase in targets:
        if _delete_crd(namespace, plural, name):
            click.echo(f"  ✓ Deleted {name}")
        else:
            click.echo(f"  ✗ Failed to delete {name}", err=True)
            ctx.exit(ExitCode.FAILURE.value)
            return
    # Wait for all deletions to complete
    by_plural = {}
    for plural, name, _ in targets:
        by_plural.setdefault(plural, []).append(name)
    for plural, names in by_plural.items():
        _wait_until_gone(namespace, plural, names)


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
    """Reset workflow resources by deleting CRDs with foreground cascading.

    With no arguments, lists migration resources and their status.
    With a NAME or glob pattern, deletes matching resources.
    With --all, deletes all resources in dependency order, stops and deletes workflow.

    Example:
        workflow reset                     # list resettable resources
        workflow reset source-proxy        # delete just capture proxy
        workflow reset "source-*"          # delete all matching resources
        workflow reset --all               # teardown everything and delete workflow
    """
    try:
        load_k8s_config()

        if path is not None:
            _handle_targeted_reset(ctx, namespace, path)
            return

        crds = _list_migration_resources(namespace)

        if not crds and not reset_all:
            click.echo("No migration resources found.")
            return

        if not reset_all:
            _show_resources(crds)
            return

        _reset_all(namespace, workflow_name, argo_server, token, insecure)

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
