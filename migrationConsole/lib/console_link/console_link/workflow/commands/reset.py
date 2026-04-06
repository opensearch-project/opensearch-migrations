"""Reset command for workflow CLI - tears down migration resources.

Deletes migration CRDs with foreground propagation. Kubernetes ownerReferences
ensure dependents (Deployments, Pods) are cleaned up automatically.
After CRDs are gone, stops and deletes any Argo workflow in the namespace.

Dependencies are read from spec.dependsOn on each CRD instance, so the CLI
does not hardcode any dependency order.
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
    'kafkaclusters', 'capturedtraffics', 'datasnapshots',
    'snapshotmigrations', 'trafficreplays', 'approvalgates',
]

DISPLAY_NAMES = {
    'kafkaclusters': 'Kafka Cluster',
    'capturedtraffics': 'Capture Proxy',
    'datasnapshots': 'Data Snapshot',
    'snapshotmigrations': 'Snapshot Migration',
    'trafficreplays': 'Traffic Replay',
    'approvalgates': 'Approval Gate',
}


def _list_migration_resources(namespace):
    """List all migration resources. Returns list of (plural, name, phase, dependsOn)."""
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
                deps = item.get('spec', {}).get('dependsOn', []) or []
                results.append((plural, name, phase, deps))
        except ApiException:
            pass
    return results


def _find_dependents(target_names, all_resources):
    """Find all resources that transitively depend on any of target_names."""
    dependents = []
    found = set(target_names)
    # Iterate until no new dependents are found
    changed = True
    while changed:
        changed = False
        for _, name, _, deps in all_resources:
            if name not in found and any(d in found for d in deps):
                dependents.append(name)
                found.add(name)
                changed = True
    return dependents


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
    """Find a resource by name across all types."""
    custom = client.CustomObjectsApi()
    for plural in RESETTABLE_PLURALS:
        try:
            item = custom.get_namespaced_custom_object(
                group=CRD_GROUP, version=CRD_VERSION,
                namespace=namespace, plural=plural, name=name
            )
            phase = item.get('status', {}).get('phase', 'Unknown')
            deps = item.get('spec', {}).get('dependsOn', []) or []
            return (plural, name, phase, deps)
        except ApiException:
            pass
    return None


def _get_resource_completions(ctx, param, incomplete):
    try:
        load_k8s_config()
        crds = _list_migration_resources(ctx.params.get('namespace', 'ma'))
        return [
            n for _, n, phase, _ in crds
            if n.startswith(incomplete) and phase != 'Teardown'
        ]
    except Exception:
        return []


def _resolve_targets(namespace, path):
    """Resolve a name or glob to matching resources."""
    if has_glob(path):
        crds = _list_migration_resources(namespace)
        matched = set(match_names([n for _, n, _, _ in crds], path))
        return [(p, n, ph, d) for p, n, ph, d in crds if n in matched]
    match = _find_resource_by_name(namespace, path)
    return [match] if match else []


def _delete_targets(targets, namespace):
    """Delete targets in dependency order (leaves first), waiting per layer.

    Groups targets into layers: resources with no dependents in the set
    are deleted first, then their parents, etc. Independent resources
    within a layer are deleted in parallel.
    """
    remaining = {t[1]: t for t in targets}
    # Build dependency edges within the target set
    deps_in_set = {}
    for item in targets:
        name, deps = item[1], item[3] if len(item) > 3 else []
        deps_in_set[name] = {d for d in deps if d in remaining}

    failed = False
    while remaining:
        # Find leaves: targets with no deps remaining in the set
        leaves = [
            n for n, deps in deps_in_set.items()
            if n in remaining and not deps
        ]
        if not leaves:
            # Cycle or error — delete everything remaining
            leaves = list(remaining.keys())

        # Delete this layer
        for name in leaves:
            item = remaining[name]
            if _delete_crd(namespace, item[0], name):
                click.echo(f"  ✓ Deleted {name}")
            else:
                click.echo(f"  ✗ Failed to delete {name}", err=True)
                failed = True

        # Wait for this layer to be fully gone
        by_plural = {}
        for name in leaves:
            item = remaining[name]
            by_plural.setdefault(item[0], []).append(name)
        for plural, names in by_plural.items():
            _wait_until_gone(namespace, plural, names)

        # Remove deleted from remaining and from dep edges
        for name in leaves:
            del remaining[name]
            del deps_in_set[name]
        for deps in deps_in_set.values():
            deps -= set(leaves)

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
@click.option(
    '--cascade', is_flag=True, default=False,
    help='Also delete dependent resources',
)
@click.option('--namespace', default='ma')
@click.pass_context
def reset_command(ctx, path, reset_all, cascade, namespace):
    """Reset migration resources by deleting CRDs.

    With no arguments, lists migration resources and their status.
    With a NAME or glob, deletes matching resources.
    With --all, deletes all resources and workflows.

    Dependencies are read from spec.dependsOn on each CRD. Deleting a
    resource that others depend on is blocked unless --cascade is used.

    Example:
        workflow reset                     # list resources
        workflow reset source-proxy        # delete one resource
        workflow reset --cascade snap1     # delete snap1 + its dependents
        workflow reset --all               # delete everything
    """
    try:
        load_k8s_config()

        if path is not None:
            targets = _resolve_targets(namespace, path)
            if not targets:
                click.echo(f"No resources matching '{path}'.")
                return
            # Check for live dependents via spec.dependsOn
            target_names = {t[1] for t in targets}
            all_crds = _list_migration_resources(namespace)
            dep_names = _find_dependents(target_names, all_crds)
            if dep_names:
                blocking = [
                    r for r in all_crds if r[1] in dep_names
                ]
                if cascade:
                    # Merge dependents into targets — topo delete handles order
                    target_names.update(dep_names)
                    targets = [r for r in all_crds if r[1] in target_names]
                else:
                    click.echo("Cannot delete — dependent resources exist:")
                    for p, n, _, _ in blocking:
                        click.echo(
                            f"  {DISPLAY_NAMES.get(p, p)}: {n}"
                        )
                    click.echo()
                    click.echo("Use --cascade to delete them too.")
                    ctx.exit(ExitCode.FAILURE.value)
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
            for plural, name, phase, deps in crds:
                display = DISPLAY_NAMES.get(plural, plural)
                dep_str = f" (depends on: {', '.join(deps)})" if deps else ""
                click.echo(f"  {display}: {name:<35} ({phase}){dep_str}")
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
