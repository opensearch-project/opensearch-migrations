"""Reset command for workflow CLI - tears down migration resources.

Deletes migration CRDs with foreground propagation. Kubernetes ownerReferences
ensure dependents (Deployments, Pods) are cleaned up automatically.
After CRDs are gone, stops and deletes any Argo workflow in the namespace.
"""

import logging
import time

import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config
from .crd_utils import CRD_GROUP, CRD_VERSION, has_glob, match_names

logger = logging.getLogger(__name__)

ARGO_GROUP = 'argoproj.io'
ARGO_VERSION = 'v1alpha1'

# All resettable resource types
RESETTABLE_PLURALS = [
    'capturedtraffics', 'datasnapshots', 'metadatamigrations',
    'snapshotmigrations', 'trafficreplays', 'kafkaclusters',
    'approvalgates',
]

DISPLAY_NAMES = {
    'capturedtraffics': 'Capture Proxy',
    'datasnapshots': 'Data Snapshot',
    'metadatamigrations': 'Metadata Migration',
    'snapshotmigrations': 'Snapshot Migration',
    'trafficreplays': 'Traffic Replay',
    'kafkaclusters': 'Kafka Cluster',
    'approvalgates': 'Approval Gate',
}


def _list_migration_resources(namespace):
    """List all migration resources. Returns list of (plural, name, phase)."""
    custom = client.CustomObjectsApi()
    results = []
    for plural in RESETTABLE_PLURALS:
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


def _delete_crd(namespace, plural, name):
    """Delete a CRD instance with foreground cascading. Returns True on success."""
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
            return True
        logger.error(f"Failed to delete {plural}/{name}: {e}")
        return False


def _wait_until_gone(namespace, plural, names, timeout=120):
    """Wait until all named CRDs are fully deleted."""
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


def _stop_and_delete_workflows(namespace):
    """Stop and delete all Argo workflows in the namespace via k8s API."""
    custom = client.CustomObjectsApi()
    try:
        items = custom.list_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION,
            namespace=namespace, plural='workflows'
        ).get('items', [])
    except ApiException:
        return

    for wf in items:
        name = wf['metadata']['name']
        try:
            # Patch to stop (prevents new steps)
            custom.patch_namespaced_custom_object(
                group=ARGO_GROUP, version=ARGO_VERSION,
                namespace=namespace, plural='workflows', name=name,
                body={'spec': {'shutdown': 'Stop'}},
            )
            click.echo(f"  ✓ Stopped workflow '{name}'")
        except ApiException as e:
            if e.status != 404:
                click.echo(f"  ⚠ Could not stop workflow '{name}'", err=True)

        try:
            custom.delete_namespaced_custom_object(
                group=ARGO_GROUP, version=ARGO_VERSION,
                namespace=namespace, plural='workflows', name=name,
            )
            click.echo(f"  ✓ Deleted workflow '{name}'")
        except ApiException as e:
            if e.status != 404:
                click.echo(
                    f"  ✗ Failed to delete workflow '{name}'", err=True
                )


def _find_resource_by_name(namespace, name):
    """Find a resource by name across all types. Returns (plural, name, phase) or None."""
    custom = client.CustomObjectsApi()
    for plural in RESETTABLE_PLURALS:
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


def _get_resource_completions(ctx, param, incomplete):
    try:
        load_k8s_config()
        crds = _list_migration_resources(ctx.params.get('namespace', 'ma'))
        return [
            n for _, n, phase in crds
            if n.startswith(incomplete) and phase != 'Teardown'
        ]
    except Exception:
        return []


def _resolve_targets(namespace, path):
    """Resolve a name or glob to matching resources."""
    if has_glob(path):
        crds = _list_migration_resources(namespace)
        matched = set(match_names([n for _, n, _ in crds], path))
        return [(p, n, ph) for p, n, ph in crds if n in matched]
    match = _find_resource_by_name(namespace, path)
    return [match] if match else []


def _delete_targets(targets, namespace):
    """Delete a list of (plural, name, phase) targets and wait."""
    failed = False
    for plural, name, _ in targets:
        if _delete_crd(namespace, plural, name):
            click.echo(f"  ✓ Deleted {name}")
        else:
            click.echo(f"  ✗ Failed to delete {name}", err=True)
            failed = True

    by_plural = {}
    for plural, name, _ in targets:
        by_plural.setdefault(plural, []).append(name)
    for plural, names in by_plural.items():
        _wait_until_gone(namespace, plural, names)

    return not failed


@click.command(name="reset")
@click.argument(
    'path', required=False, default=None,
    shell_complete=_get_resource_completions,
)
@click.option(
    '--all', 'reset_all', is_flag=True, default=False,
    help='Delete all migration resources and workflows',
)
@click.option('--namespace', default='ma')
@click.pass_context
def reset_command(ctx, path, reset_all, namespace):
    """Reset migration resources by deleting CRDs.

    With no arguments, lists migration resources and their status.
    With a NAME or glob, deletes matching resources.
    With --all, deletes all resources and workflows.

    Example:
        workflow reset                     # list resources
        workflow reset source-proxy        # delete one resource
        workflow reset "source-*"          # delete matching resources
        workflow reset --all               # delete everything
    """
    try:
        load_k8s_config()

        if path is not None:
            targets = _resolve_targets(namespace, path)
            if not targets:
                click.echo(f"No resources matching '{path}'.")
                return
            if not _delete_targets(targets, namespace):
                ctx.exit(ExitCode.FAILURE.value)
            return

        crds = _list_migration_resources(namespace)

        if not crds and not reset_all:
            click.echo("No migration resources found.")
            return

        if not reset_all:
            click.echo("Migration resources:")
            for plural, name, phase in crds:
                display = DISPLAY_NAMES.get(plural, plural)
                click.echo(f"  {display}: {name:<35} ({phase})")
            click.echo()
            click.echo("Use 'workflow reset <name>' to delete a resource.")
            click.echo("Use 'workflow reset --all' to delete everything.")
            return

        # --all: delete all CRDs, then stop/delete workflows
        if crds:
            click.echo("Deleting migration resources...")
            _delete_targets(crds, namespace)

        click.echo("Cleaning up workflows...")
        _stop_and_delete_workflows(namespace)
        click.echo("Done.")

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
