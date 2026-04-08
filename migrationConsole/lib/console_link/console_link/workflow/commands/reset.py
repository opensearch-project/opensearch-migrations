"""Reset command for workflow CLI - lists and deletes migration CRD resources."""

import fnmatch
import logging

import click
from kubernetes import client
from kubernetes.client.rest import ApiException

from .crd_utils import CRD_GROUP, CRD_VERSION, MIGRATION_CRD_TYPES

logger = logging.getLogger(__name__)


def _list_migration_resources(namespace):
    """List all migration CRD instances in a namespace.

    Returns list of (plural, name, phase, finalizers) tuples.
    """
    custom = client.CustomObjectsApi()
    results = []
    for plural in MIGRATION_CRD_TYPES:
        try:
            resp = custom.list_namespaced_custom_object(
                group=CRD_GROUP, version=CRD_VERSION,
                namespace=namespace, plural=plural,
            )
            for item in resp.get("items", []):
                name = item["metadata"]["name"]
                phase = item.get("status", {}).get("phase", "Unknown")
                finalizers = item["metadata"].get("finalizers", [])
                results.append((plural, name, phase, finalizers))
        except ApiException as e:
            if e.status != 404:
                logger.warning("Failed to list %s: %s", plural, e.reason)
    return results


def _delete_crd(namespace, plural, name):
    """Delete a single CRD instance. Returns True on success or if already gone."""
    custom = client.CustomObjectsApi()
    try:
        custom.delete_namespaced_custom_object(
            group=CRD_GROUP, version=CRD_VERSION,
            namespace=namespace, plural=plural, name=name,
            body=client.V1DeleteOptions(propagation_policy="Foreground"),
        )
    except ApiException as e:
        if e.status != 404:
            logger.error("Failed to delete %s/%s: %s", plural, name, e.reason)
            return False
    return True


def _get_resource_completions(ctx, _param, incomplete):
    """Shell completion for resource names (excludes Teardown resources)."""
    namespace = ctx.params.get("namespace", "ma")
    try:
        resources = _list_migration_resources(namespace)
        return [
            name for _, name, phase, _ in resources
            if name.startswith(incomplete) and phase != "Teardown"
        ]
    except Exception:
        return []


def _reset_all(namespace, resources):
    """Delete all migration resources."""
    if not resources:
        click.echo("No migration resources found.")
        return
    for plural, name, _phase, _fin in resources:
        if _delete_crd(namespace, plural, name):
            click.echo(f"✓ Deleted {name}")
        else:
            click.echo(f"✗ Failed to delete {name}", err=True)


def _list_resources(namespace, resources):
    """Display all migration resources."""
    if not resources:
        click.echo("No migration resources found.")
        return
    click.echo("Migration resources:\n")
    for plural, name, phase, _fin in resources:
        friendly = MIGRATION_CRD_TYPES.get(plural, plural)
        click.echo(f"  {friendly:20s} {name:30s} {phase}")
    click.echo(f"\nTo delete all: workflow reset --all --namespace {namespace}")


def _delete_matching(namespace, resources, resource_name):
    """Delete resources matching the given name or glob pattern."""
    matches = [(p, n) for p, n, _ph, _f in resources if n == resource_name]
    if not matches:
        matches = [(p, n) for p, n, _ph, _f in resources
                   if fnmatch.fnmatch(n, resource_name)]
    if not matches:
        click.echo(f"No resources matching '{resource_name}' found.")
        return
    for plural, name in matches:
        if _delete_crd(namespace, plural, name):
            click.echo(f"✓ Deleted {name}")
        else:
            click.echo(f"✗ Failed to delete {name}", err=True)


@click.command(name="reset")
@click.argument("resource-name", required=False, default=None,
                shell_complete=_get_resource_completions)
@click.option("--all", "reset_all", is_flag=True, default=False,
              help="Delete all migration resources")
@click.option("--namespace", default="ma", help="Kubernetes namespace")
@click.pass_context
def reset_command(ctx, resource_name, reset_all, namespace):
    """Reset migration resources by deleting CRDs.

    With no arguments, lists current migration resources.
    With a RESOURCE_NAME, deletes that specific resource.
    With --all, deletes all migration resources.

    Example:
        workflow reset
        workflow reset my-proxy
        workflow reset --all --namespace ma
    """
    resources = _list_migration_resources(namespace)

    if reset_all:
        _reset_all(namespace, resources)
    elif resource_name is None:
        _list_resources(namespace, resources)
    else:
        _delete_matching(namespace, resources, resource_name)
