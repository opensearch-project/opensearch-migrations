"""Reset command for workflow CLI - tears down migration resources.

Deletes migration CRDs with foreground propagation. Kubernetes ownerReferences
ensure dependents (Deployments, Pods) are cleaned up automatically.
After CRDs are gone, stops and deletes any Argo workflow in the namespace.

Dependencies are read from spec.dependsOn on each CRD instance, so the CLI
does not hardcode any dependency order.
"""

import json
import logging
import tempfile
import time
from pathlib import Path

import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config
from .crd_utils import CRD_GROUP, CRD_VERSION, has_glob, match_names

logger = logging.getLogger(__name__)

_AUTOCOMPLETE_RESET_CACHE_TTL_SECONDS = 10

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


def _get_reset_cache_file() -> Path:
    cache_dir = Path(tempfile.gettempdir()) / "workflow_completions"
    cache_dir.mkdir(exist_ok=True)
    return cache_dir / "reset_resources.json"


def _get_cached_resource_names(ctx) -> list[str]:
    """Fetch and cache resettable resource names."""
    cache_file = _get_reset_cache_file()

    if cache_file.exists() and (time.time() - cache_file.stat().st_mtime) < _AUTOCOMPLETE_RESET_CACHE_TTL_SECONDS:
        try:
            return json.loads(cache_file.read_text()).get('names', [])
        except Exception:
            pass

    try:
        load_k8s_config()
        crds = _list_migration_resources(ctx.params.get('namespace', 'ma'))
        names = [n for _, n, phase, _ in crds if phase != 'Teardown']
        cache_file.write_text(json.dumps({'names': names}))
        return names
    except Exception:
        return []


def _get_resource_completions(ctx, param, incomplete):
    return [n for n in _get_cached_resource_names(ctx) if n.startswith(incomplete)]


def _resolve_targets(namespace, path):
    """Resolve a name or glob to matching resources."""
    if has_glob(path):
        crds = _list_migration_resources(namespace)
        matched = set(match_names([n for _, n, _, _ in crds], path))
        return [(p, n, ph, d) for p, n, ph, d in crds if n in matched]
    match = _find_resource_by_name(namespace, path)
    return [match] if match else []


def _delete_and_wait(namespace, plural, name):
    """Delete a single CRD and poll until it's gone. Returns (name, success)."""
    ok = _delete_crd(namespace, plural, name)
    if ok:
        _wait_until_gone(namespace, plural, [name])
    return (name, ok)


def _build_child_map(targets):
    """Build reverse dependency map: name → set of children that must be deleted first."""
    target_names = {t[1] for t in targets}
    children = {name: set() for name in target_names}
    for item in targets:
        name, deps = item[1], item[3] if len(item) > 3 else []
        for dep in deps:
            if dep in children:
                children[dep].add(name)
    return children


def _delete_targets(targets, namespace):
    """Delete targets respecting dependencies, with maximum concurrency.

    Each resource is deleted as soon as all resources that depend on it
    (its children in the DAG) are gone. Independent branches proceed in
    parallel without waiting for each other.
    """
    from concurrent.futures import ThreadPoolExecutor, as_completed

    target_map = {t[1]: t for t in targets}
    pending_children = {n: set(c) for n, c in _build_child_map(targets).items()}
    failed = False

    with ThreadPoolExecutor(max_workers=8) as pool:
        in_flight = {}

        def submit_ready():
            for name, kids in pending_children.items():
                if name not in in_flight and not kids:
                    item = target_map[name]
                    in_flight[name] = pool.submit(
                        _delete_and_wait, namespace, item[0], name)

        submit_ready()

        while in_flight:
            done = next(as_completed(in_flight.values()))
            name = next(n for n, f in in_flight.items() if f is done)
            del in_flight[name]
            del pending_children[name]

            _, ok = done.result()
            if ok:
                click.echo(f"  ✓ Deleted {name}")
            else:
                click.echo(f"  ✗ Failed to delete {name}", err=True)
                failed = True

            for kids in pending_children.values():
                kids.discard(name)

            submit_ready()

    return not failed


def _resolve_cascade_targets(targets, namespace, cascade):
    """Check for dependents. Returns expanded targets or None if blocked."""
    target_names = {t[1] for t in targets}
    all_crds = _list_migration_resources(namespace)
    dep_names = _find_dependents(target_names, all_crds)
    if not dep_names:
        return targets

    if cascade:
        target_names.update(dep_names)
        return [r for r in all_crds if r[1] in target_names]

    blocking = [r for r in all_crds if r[1] in dep_names]
    click.echo("Cannot delete — dependent resources exist:")
    for p, n, _, _ in blocking:
        click.echo(f"  {DISPLAY_NAMES.get(p, p)}: {n}")
    click.echo()
    click.echo("Use --cascade to delete them too.")
    return None


def _show_resource_list(crds):
    """Display migration resources and their dependencies."""
    click.echo("Migration resources:")
    for plural, name, phase, deps in crds:
        display = DISPLAY_NAMES.get(plural, plural)
        dep_str = f" (depends on: {', '.join(deps)})" if deps else ""
        click.echo(f"  {display}: {name:<35} ({phase}){dep_str}")
    click.echo()
    click.echo("Use 'workflow reset <name>' to delete a resource.")
    click.echo("Use 'workflow reset --all' to delete everything.")


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
            targets = _resolve_cascade_targets(targets, namespace, cascade)
            if targets is None:
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
            _show_resource_list(crds)
            return

        if crds:
            click.echo("Deleting migration resources...")
            _delete_targets(crds, namespace)

        click.echo("Cleaning up workflows...")
        _stop_and_delete_workflows(namespace)
        click.echo("Done.")

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
