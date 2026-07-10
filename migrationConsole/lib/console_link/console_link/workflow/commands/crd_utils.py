"""Shared CRD constants and utilities for migration commands."""

import fnmatch
import json
import tempfile
import time
from pathlib import Path

from kubernetes import client
from kubernetes.client.rest import ApiException

from ..models.utils import load_k8s_config

CRD_GROUP = 'migrations.opensearch.org'
CRD_VERSION = 'v1alpha1'

RESETTABLE_PLURALS = [
    'kafkaclusters',
    'capturedtraffics',
    'captureproxies',
    'datasnapshots',
    'snapshotmigrations',
    'trafficreplays',
]

DISPLAY_NAMES = {
    'kafkaclusters': 'kafkacluster',
    'capturedtraffics': 'capturedtraffic',
    'captureproxies': 'captureproxy',
    'datasnapshots': 'datasnapshot',
    'snapshotmigrations': 'snapshotmigration',
    'trafficreplays': 'trafficreplay',
    'approvalgates': 'approvalgate',
}

PLURAL_FROM_TYPE = {v: k for k, v in DISPLAY_NAMES.items()}


def resource_display_name(plural, name):
    """Format a resource as type.name for display."""
    return f"{DISPLAY_NAMES.get(plural, plural)}.{name}"


def parse_resource_path(path):
    """Parse a type.name string into (plural, name). Returns None if no dot."""
    dot = path.find('.')
    if dot < 0:
        return None
    type_part = path[:dot]
    name_part = path[dot + 1:]
    plural = PLURAL_FROM_TYPE.get(type_part)
    if plural and name_part:
        return (plural, name_part)
    return None


def has_glob(pattern):
    """Check if a string contains glob wildcard characters."""
    return any(c in pattern for c in '*?[')


def match_names(names, pattern):
    """Filter a list of names by exact match or glob pattern."""
    if has_glob(pattern):
        return [n for n in names if fnmatch.fnmatch(n, pattern)]
    return [n for n in names if n == pattern]


def list_migration_resources(namespace, plurals=None):
    """List CRD instances. Returns list of (plural, name, phase, deps)."""
    custom = client.CustomObjectsApi()
    results = []
    for plural in plurals or RESETTABLE_PLURALS:
        try:
            items = custom.list_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=namespace,
                plural=plural,
            ).get('items', [])
            for item in items:
                results.append((
                    plural,
                    item['metadata']['name'],
                    item.get('status', {}).get('phase', 'Unknown'),
                    item.get('spec', {}).get('dependsOn', []) or [],
                ))
        except ApiException:
            pass
    return results


def list_migration_resources_full(namespace):
    """List all migration CRD instances with full objects."""
    return list_resources_full(namespace, RESETTABLE_PLURALS)


def list_resources_full(namespace, resource_type_filter):
    """List CRD instances with full objects.

    Returns dict keyed by plural containing lists of CR dicts.
    resource_type_filter is required — caller must specify which resource types to list.
    """
    custom = client.CustomObjectsApi()
    results = {}
    for plural in resource_type_filter:
        try:
            items = custom.list_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=namespace,
                plural=plural,
            ).get('items', [])
            if items:
                results[plural] = items
        except ApiException:
            pass
    return results


def cached_crd_completions(namespace, cache_key, fetch_names, ttl=10):
    """Generic TTL-cached name fetcher for shell autocompletion."""
    cache_dir = Path(tempfile.gettempdir()) / "workflow_completions"
    cache_dir.mkdir(exist_ok=True)
    cache_file = cache_dir / f"{cache_key}_{namespace}.json"

    if cache_file.exists() and (time.time() - cache_file.stat().st_mtime) < ttl:
        try:
            return json.loads(cache_file.read_text()).get('names', [])
        except Exception:
            pass

    try:
        load_k8s_config()
        names = fetch_names(namespace)
        cache_file.write_text(json.dumps({'names': names}))
        return names
    except Exception:
        return []
