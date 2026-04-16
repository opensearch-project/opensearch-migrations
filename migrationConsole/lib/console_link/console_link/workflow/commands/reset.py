"""Reset command for workflow CLI - delete migration CRDs safely."""

import logging
import time

import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import ExitCode, load_k8s_config
from .crd_utils import (
    CRD_GROUP,
    CRD_VERSION,
    DISPLAY_NAMES,
    RESETTABLE_PLURALS,
    cached_crd_completions,
    has_glob,
    list_migration_resources,
    match_names,
)

logger = logging.getLogger(__name__)


def _resettable_names(namespace):
    return [name for _, name, _, _ in list_migration_resources(namespace)]


def _find_resource_by_name(namespace, name):
    """Find a single resource by name across all migration resource types."""
    custom = client.CustomObjectsApi()
    for plural in RESETTABLE_PLURALS:
        try:
            item = custom.get_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=namespace,
                plural=plural,
                name=name,
            )
            phase = item.get('status', {}).get('phase', 'Unknown')
            deps = item.get('spec', {}).get('dependsOn', []) or []
            return (plural, name, phase, deps)
        except ApiException:
            pass
    return None


def _delete_crd(namespace, plural, name):
    """Delete a CRD instance with foreground cascading. Returns True on success."""
    custom = client.CustomObjectsApi()
    try:
        custom.delete_namespaced_custom_object(
            group=CRD_GROUP,
            version=CRD_VERSION,
            namespace=namespace,
            plural=plural,
            name=name,
            body=client.V1DeleteOptions(propagation_policy='Foreground'),
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
                    group=CRD_GROUP,
                    version=CRD_VERSION,
                    namespace=namespace,
                    plural=plural,
                    name=name,
                )
            except ApiException as e:
                if e.status == 404:
                    gone.add(name)
        remaining -= gone
        if remaining:
            time.sleep(2)
    if remaining:
        logger.warning(f"Timed out waiting for deletion: {remaining}")


def _get_resource_completions(ctx, _, incomplete):
    namespace = ctx.params.get('namespace', 'ma')
    return [
        name for name in cached_crd_completions(namespace, 'reset_resources', _resettable_names)
        if name.startswith(incomplete)
    ]


def _find_dependents(target_names, all_resources):
    """Find all resources that transitively depend on any of target_names."""
    dependents = []
    found = set(target_names)
    changed = True
    while changed:
        changed = False
        for _, name, _, deps in all_resources:
            if name not in found and any(dep in found for dep in deps):
                dependents.append(name)
                found.add(name)
                changed = True
    return dependents


def _resolve_targets(namespace, path):
    """Resolve a name or glob to matching resources."""
    if has_glob(path):
        resources = list_migration_resources(namespace)
        matched = set(match_names([name for _, name, _, _ in resources], path))
        return [resource for resource in resources if resource[1] in matched]
    match = _find_resource_by_name(namespace, path)
    return [match] if match else []


def _delete_and_wait(namespace, plural, name):
    """Delete a single CRD and poll until it is gone."""
    ok = _delete_crd(namespace, plural, name)
    if ok:
        _wait_until_gone(namespace, plural, [name])
    return name, ok


def _build_child_map(targets):
    """Build reverse dependency map: name -> set of children."""
    target_names = {target[1] for target in targets}
    children = {name: set() for name in target_names}
    for _, name, _, deps in targets:
        for dep in deps:
            if dep in children:
                children[dep].add(name)
    return children


def _delete_targets(targets, namespace):
    """Delete targets in dependency-safe order with concurrency."""
    from concurrent.futures import ThreadPoolExecutor, as_completed

    target_map = {target[1]: target for target in targets}
    pending_children = {name: set(children) for name, children in _build_child_map(targets).items()}
    failed = False

    with ThreadPoolExecutor(max_workers=8) as pool:
        in_flight = {}

        def submit_ready():
            for name, children in pending_children.items():
                if name not in in_flight and not children:
                    plural = target_map[name][0]
                    in_flight[name] = pool.submit(_delete_and_wait, namespace, plural, name)

        submit_ready()

        while in_flight:
            done = next(as_completed(in_flight.values()))
            name = next(candidate for candidate, future in in_flight.items() if future is done)
            del in_flight[name]
            del pending_children[name]

            _, ok = done.result()
            if ok:
                click.echo(f"  Deleted {name}")
            else:
                click.echo(f"  Failed to delete {name}", err=True)
                failed = True

            for children in pending_children.values():
                children.discard(name)

            submit_ready()

    return not failed


def _show_resource_list(resources):
    """Display migration resources and their dependencies."""
    click.echo("Migration resources:")
    for plural, name, phase, deps in resources:
        display = DISPLAY_NAMES.get(plural, plural)
        dep_str = f" (depends on: {', '.join(deps)})" if deps else ""
        click.echo(f"  {display}: {name:<35} ({phase}){dep_str}")
    click.echo()
    click.echo("Use 'workflow reset <name>' to delete a resource.")
    click.echo("Use 'workflow reset --all' to delete everything.")
    click.echo("Use 'workflow submit' to replace the Argo workflow without deleting migration resources.")


def _filter_proxy_targets(targets, include_proxies):
    """Block or remove proxy targets unless explicitly included."""
    proxy_targets = [target for target in targets if target[0] == 'captureproxies']
    if include_proxies or not proxy_targets:
        return targets

    names = ', '.join(target[1] for target in proxy_targets)
    click.echo(f"Proxies are protected by default: {names}")
    click.echo("Use --include-proxies to delete them.")
    return None


def _resolve_cascade_targets(targets, namespace, cascade, include_proxies):
    """Return expanded delete set or None when blocked.

    Protected proxies (captureproxies) are never included in the delete set
    unless --include-proxies is passed. When a target has a protected proxy
    as a dependent, the proxy is silently skipped — it does not block deletion
    of the target.
    """
    filtered = _filter_proxy_targets(targets, include_proxies)
    if filtered is None:
        return None

    target_names = {target[1] for target in filtered}
    all_resources = list_migration_resources(namespace)
    dependent_names = _find_dependents(target_names, all_resources)
    if not dependent_names:
        return filtered

    blocking = [resource for resource in all_resources if resource[1] in dependent_names]

    # Separate proxy dependents from non-proxy dependents
    blocking_proxy = [r for r in blocking if r[0] == 'captureproxies']
    blocking_non_proxy = [r for r in blocking if r[0] != 'captureproxies']

    # Protected proxies don't block — they're just skipped
    if not include_proxies and blocking_proxy:
        proxy_names = ', '.join(r[1] for r in blocking_proxy)
        click.echo(f"Keeping protected proxies alive: {proxy_names}")

    # Non-proxy dependents still require --cascade
    if blocking_non_proxy and not cascade:
        click.echo("Cannot delete because dependent resources still exist:")
        for plural, name, _, _ in blocking_non_proxy:
            click.echo(f"  {DISPLAY_NAMES.get(plural, plural)}: {name}")
        click.echo()
        click.echo("Use --cascade to delete them too.")
        return None

    # Build the final delete set: targets + non-proxy dependents (cascade)
    # Proxy dependents are excluded unless --include-proxies
    expanded_names = target_names | {r[1] for r in blocking_non_proxy}
    if include_proxies:
        expanded_names |= {r[1] for r in blocking_proxy}
    expanded = [resource for resource in all_resources if resource[1] in expanded_names]
    return expanded


def _find_ancestors(target_names, all_resources):
    """Find all resources transitively referenced by dependsOn from target_names."""
    resource_map = {name: deps for _, name, _, deps in all_resources}
    ancestors = set()
    pending = list(target_names)
    while pending:
        name = pending.pop()
        for dep in resource_map.get(name, []):
            if dep not in ancestors:
                ancestors.add(dep)
                pending.append(dep)
    return ancestors


def _prune_ancestors_of_protected_proxies(resources, include_proxies):
    """When proxies are protected, remove only the proxy CRs from the delete set.

    Unlike before, upstream dependencies (topics, kafka clusters) are NOT
    protected — they can be deleted independently. The proxy stays alive
    to continue serving traffic even if its upstream is torn down.
    """
    if include_proxies:
        return resources, set()

    protected_proxy_names = {
        name for plural, name, _, _ in resources if plural == 'captureproxies'
    }
    if not protected_proxy_names:
        return resources, set()

    filtered = [
        resource for resource in resources
        if resource[0] != 'captureproxies'
    ]
    return filtered, protected_proxy_names


def _reset_by_path(ctx, path, namespace, cascade, include_proxies):
    """Handle reset for a specific resource path/pattern."""
    targets = _resolve_targets(namespace, path)
    if not targets:
        click.echo(f"No resources matching '{path}'.")
        return
    targets = _resolve_cascade_targets(targets, namespace, cascade, include_proxies)
    if targets is None:
        ctx.exit(ExitCode.FAILURE.value)
        return
    if not _delete_targets(targets, namespace):
        ctx.exit(ExitCode.FAILURE.value)


@click.command(name="reset")
@click.argument('path', required=False, default=None, shell_complete=_get_resource_completions)
@click.option('--all', 'reset_all', is_flag=True, default=False, help='Delete all migration resources')
@click.option('--cascade', is_flag=True, default=False, help='Also delete dependent resources')
@click.option('--include-proxies', is_flag=True, default=False,
              help='Also delete capture proxies (they are protected by default)')
@click.option('--namespace', default='ma')
@click.pass_context
def reset_command(ctx, path, reset_all, cascade, include_proxies, namespace):
    """Reset workflow resources by deleting CRDs.

    With no arguments, lists migration resources and their status.
    With a NAME or glob pattern, deletes matching resources.
    With --all, deletes all matching migration resources.
    """
    try:
        load_k8s_config()

        if path is not None:
            _reset_by_path(ctx, path, namespace, cascade, include_proxies)
            return

        resources = list_migration_resources(namespace)

        if not resources and not reset_all:
            click.echo("No migration resources found.")
            return

        if not reset_all:
            _show_resource_list(resources)
            return

        delete_targets, protected_proxy_names = _prune_ancestors_of_protected_proxies(
            resources, include_proxies
        )
        if not include_proxies and protected_proxy_names:
            proxy_names = ", ".join(sorted(protected_proxy_names))
            click.echo(f"Keeping protected proxies alive: {proxy_names}")
            click.echo("Use --include-proxies to delete them.")

        if delete_targets and not _delete_targets(delete_targets, namespace):
            ctx.exit(ExitCode.FAILURE.value)
            return

        click.echo("Done.")

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
