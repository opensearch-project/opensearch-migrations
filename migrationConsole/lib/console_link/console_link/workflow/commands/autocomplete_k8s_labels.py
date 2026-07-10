from click.shell_completion import CompletionItem
import json
import logging
import os
import re
import tempfile
import time

import requests
from pathlib import Path

from console_link.workflow.commands.autocomplete_workflows import DEFAULT_WORKFLOW_NAME
from console_link.workflow.commands.argo_utils import DEFAULT_ARGO_SERVER_URL

logger = logging.getLogger(__name__)

_LABEL_CACHE_TTL = 120
_FILTER_OPTION_LABELS = {
    'source': 'source',
    'target': 'target',
    'snapshot': 'snapshot',
    'task': 'task',
    'from_snapshot_migration': 'from-snapshot-migration',
}


def _camel_to_kebab(name: str) -> str:
    """Convert camelCase to kebab-case."""
    return re.sub(r'([a-z])([A-Z])', r'\1-\2', name).lower()


def _get_cache_file(workflow_name: str) -> Path:
    """Get cache file path in proper temp directory."""
    cache_dir = Path(tempfile.gettempdir()) / "workflow_completions"
    cache_dir.mkdir(exist_ok=True)
    return cache_dir / f"labels_{workflow_name}.json"


def _fetch_workflow_labels(workflow_name: str, namespace: str, argo_server: str,
                           token: str, insecure: bool) -> tuple:
    """Fetch labels from workflow via Argo API, extracting K8sLabel parameters.

    Returns:
        (label_map, valid_combos) where:
        - label_map: {key: set(values)}
        - valid_combos: list of frozensets of (key, value) tuples
    """
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"

    response = requests.get(url, headers=headers, verify=not insecure, timeout=5)
    if response.status_code != 200:
        return {}, []

    data = response.json()
    nodes = data.get('status', {}).get('nodes', {})

    label_map = {}
    combos = set()

    for node in nodes.values():
        inputs = node.get('inputs', {}).get('parameters', [])
        node_labels = {}
        for p in inputs:
            name = p.get('name', '')
            if name.endswith('K8sLabel'):
                key = _camel_to_kebab(name[:-8])
                val = p.get('value', '')
                if val:
                    node_labels[key] = val
                    if key not in label_map:
                        label_map[key] = set()
                    label_map[key].add(val)

        if node_labels:
            combos.add(frozenset(node_labels.items()))

    return label_map, list(combos)


def _get_cached_label_data(ctx):
    """Fetch and cache label data from workflow via Argo API.

    Returns:
        (label_map, valid_combos)
    """
    workflow_name = ctx.params.get('workflow_name') or DEFAULT_WORKFLOW_NAME
    all_workflows = ctx.params.get('all_workflows', False)

    if all_workflows:
        workflow_name = "all"

    cache_file = _get_cache_file(workflow_name)

    # Check cache
    if cache_file.exists() and (time.time() - cache_file.stat().st_mtime) < _LABEL_CACHE_TTL:
        try:
            raw_data = json.loads(cache_file.read_text())
            label_map = {k: set(v) for k, v in raw_data['labels'].items()}
            valid_combos = [frozenset(tuple(x) for x in c) for c in raw_data['combos']]
            return label_map, valid_combos
        except Exception:
            pass

    try:
        namespace = ctx.params.get('namespace', 'ma')
        argo_server = ctx.params.get('argo_server') or os.environ.get('ARGO_SERVER') or DEFAULT_ARGO_SERVER_URL
        token = ctx.params.get('token')
        insecure = ctx.params.get('insecure', True)

        label_map, valid_combos = _fetch_workflow_labels(workflow_name, namespace, argo_server, token, insecure)

        # Cache results
        serializable = {
            'labels': {k: list(v) for k, v in label_map.items()},
            'combos': [list(c) for c in valid_combos]
        }
        cache_file.write_text(json.dumps(serializable))
        return label_map, valid_combos

    except Exception:
        return {}, []


def complete_label_value(label_key):
    """Build a Click value completer for a known workflow log label."""
    def complete(ctx, _, incomplete):
        all_labels, valid_combos = _get_cached_label_data(ctx)
        selected_pairs = _get_selected_filter_pairs(ctx, exclude_key=label_key)
        completions = []

        for value in sorted(all_labels.get(label_key, set())):
            if not value.startswith(incomplete):
                continue
            if _is_valid_label_combo(selected_pairs + [(label_key, value)], valid_combos):
                completions.append(CompletionItem(value))

        return completions[:20]

    return complete


def _get_selected_filter_pairs(ctx, exclude_key=None):
    pairs = []
    for param_name, label_key in _FILTER_OPTION_LABELS.items():
        if label_key == exclude_key:
            continue
        value = ctx.params.get(param_name)
        if value:
            pairs.append((label_key, value))

    for selector in ctx.params.get('labels', ()) or ():
        if not isinstance(selector, str) or '=' not in selector:
            continue
        key, value = selector.split('=', 1)
        if key != exclude_key and value:
            pairs.append((key, value))
    return pairs


def _is_valid_label_combo(pairs, valid_combos):
    if not valid_combos:
        return True
    return any(set(pairs) <= combo for combo in valid_combos)
