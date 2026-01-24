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

logger = logging.getLogger(__name__)

_LABEL_CACHE_TTL = 120


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
        argo_server = ctx.params.get('argo_server') or os.environ.get('ARGO_SERVER') or (
            f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
            f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}"
        )
        token = ctx.params.get('token')
        insecure = ctx.params.get('insecure', False)

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


def _is_valid_combo(suggestion: str, valid_combos: list) -> bool:
    """Check if suggestion labels are a subset of any valid combo."""
    # Parse suggestion into set of (key, value) tuples
    suggestion_set = set()
    for part in suggestion.split(","):
        if "=" in part:
            k, v = part.split("=", 1)
            suggestion_set.add((k, v))

    # Check if this is a subset of any valid combo
    return any(suggestion_set <= combo for combo in valid_combos)


def get_label_completions(ctx, _, incomplete):
    """Handles label completions showing key=value pairs and combinations."""
    prefix, active_part = _split_incomplete_path(incomplete)
    selected_keys = _get_selected_keys(prefix)
    all_labels, valid_combos = _get_cached_label_data(ctx)

    suggestions = []

    if "=" in active_part:
        # User has typed "key=" or "key=val"
        key, val_prefix = active_part.split("=", 1)
        suggestions = _get_value_completions(prefix, key, val_prefix, selected_keys, all_labels, valid_combos)
    else:
        # User is typing a new key
        suggestions = _get_key_completions(prefix, active_part, selected_keys, all_labels, valid_combos)

    return [CompletionItem(s) for s in suggestions[:20]]


def _split_incomplete_path(incomplete):
    """Splits 'k1=v1,k2' into ('k1=v1,', 'k2')."""
    if "," in incomplete:
        prefix, active = incomplete.rsplit(",", 1)
        return f"{prefix},", active
    return "", incomplete


def _get_selected_keys(prefix):
    """Extracts keys already present in the prefix to avoid duplicates."""
    keys = set()
    if not prefix:
        return keys
    for part in prefix.rstrip(",").split(","):
        if "=" in part:
            keys.add(part.split("=", 1)[0])
    return keys


def _get_value_completions(prefix, key, val_prefix, selected_keys, all_labels, valid_combos):
    """Generates suggestions for values and possible next-label extensions."""
    results = []
    values = all_labels.get(key, set())

    for v in sorted(values):
        if not v.startswith(val_prefix):
            continue

        base = f"{prefix}{key}={v}"
        if _is_valid_combo(base, valid_combos):
            results.append(base)

        # Look ahead for next possible key-value pairs
        for next_key, next_vals in sorted(all_labels.items()):
            if next_key != key and next_key not in selected_keys:
                for nv in sorted(next_vals):
                    extended = f"{base},{next_key}={nv}"
                    if _is_valid_combo(extended, valid_combos):
                        results.append(extended)
    return results


def _get_key_completions(prefix, active_part, selected_keys, all_labels, valid_combos):
    """Generates initial key=value suggestions for keys not yet selected."""
    results = []
    for key, values in sorted(all_labels.items()):
        if key.startswith(active_part) and key not in selected_keys:
            for v in sorted(values):
                suggestion = f"{prefix}{key}={v}"
                if _is_valid_combo(suggestion, valid_combos):
                    results.append(suggestion)
    return results


def _get_label_selector(selector_str, prefix, workflow_name):
    """Parses and prefixes label selectors."""
    parts = selector_str.split(',') if selector_str else []
    prefixed_parts = []
    for part in parts:
        if '=' in part:
            k, v = part.split('=', 1)
            key = f"{prefix}{k}" if '/' not in k else k
            prefixed_parts.append(f"{key}={v}")
        else:
            prefixed_parts.append(part)
    if workflow_name:
        prefixed_parts.append(f"workflows.argoproj.io/workflow={workflow_name}")
    return ",".join(prefixed_parts)
