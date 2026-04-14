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
    plurals = plurals or RESETTABLE_PLURALS
    results = []
    for plural in plurals:
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
