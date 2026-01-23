import heapq
from contextlib import ExitStack

import click
from click.shell_completion import CompletionItem
import json
import logging
import os
import re
import subprocess
import tempfile
import time

import requests
from kubernetes import client
from pathlib import Path

from .utils import DEFAULT_WORKFLOW_NAME, get_workflow_completions
from ..models.utils import ExitCode, load_k8s_config

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


def get_label_completions(ctx, param, incomplete):
    """
    Handles label completions showing key=value pairs and extended combinations.
    Filters out combinations that don't exist in the workflow.
    """
    prefix_path = ""
    active_part = incomplete
    if "," in incomplete:
        prefix_path, active_part = incomplete.rsplit(",", 1)
        prefix_path += ","

    # Parse already-selected keys from prefix
    selected_keys = set()
    if prefix_path:
        for part in prefix_path.rstrip(",").split(","):
            if "=" in part:
                selected_keys.add(part.split("=", 1)[0])

    all_labels, valid_combos = _get_cached_label_data(ctx)
    suggestions = []

    if "=" in active_part:
        # --- Value Completion ---
        key_part, val_incomplete = active_part.split("=", 1)
        values = all_labels.get(key_part, set())
        for v in sorted(values):
            if v.startswith(val_incomplete):
                base = f"{prefix_path}{key_part}={v}"
                if _is_valid_combo(base, valid_combos):
                    suggestions.append(base)
                # Add extended combinations
                for next_key, next_vals in sorted(all_labels.items()):
                    if next_key not in selected_keys and next_key != key_part:
                        for nv in sorted(next_vals):
                            extended = f"{base},{next_key}={nv}"
                            if _is_valid_combo(extended, valid_combos):
                                suggestions.append(extended)
    else:
        # --- Key Completion - show all key=value pairs for unselected keys ---
        for key, values in sorted(all_labels.items()):
            if key not in selected_keys and key.startswith(active_part):
                for v in sorted(values):
                    suggestion = f"{prefix_path}{key}={v}"
                    if _is_valid_combo(suggestion, valid_combos):
                        suggestions.append(suggestion)

    return [CompletionItem(s) for s in suggestions[:20]]


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


@click.command(name="output", context_settings=dict(
    ignore_unknown_options=True,
    allow_extra_args=True,
))
@click.option('--workflow-name', default=DEFAULT_WORKFLOW_NAME, shell_complete=get_workflow_completions)
@click.option('--all-workflows', is_flag=True, default=False, help='Show output for all workflows')
@click.option(
    '--argo-server',
    default=f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
            f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}",
    help='Argo Server URL (default: ARGO_SERVER env var, or ARGO_SERVER_SERVICE_HOST:ARGO_SERVER_SERVICE_PORT)'
)
@click.option('--namespace', default='ma')
@click.option('--insecure', is_flag=True, default=False)
@click.option('--token', help='Bearer token for authentication')
@click.option('--prefix', default='migrations.opensearch.org/', help='Label prefix for filters')
@click.option(
    '-l', '--selector',
    help='Label selector (e.g. source=a,target=b)',
    shell_complete=get_label_completions
)
# We define these just so Click "eats" them from the command line and keeps ctx.args clean
@click.option('-f', '--follow', is_flag=True, expose_value=False)
@click.option('--timestamps', is_flag=True, expose_value=False)
@click.pass_context
def output_command(ctx, workflow_name, all_workflows, argo_server, namespace, insecure, token, prefix, selector):
    """
    View or tail workflow logs.

    Tailing Mode (uses Stern):
      workflow output -l task=create -f --since 5m

    History Mode (uses sort -m):
      workflow output -l task=create --all-containers
    """
    if all_workflows and ctx.get_parameter_source('workflow_name') != click.core.ParameterSource.DEFAULT:
        click.echo("Error: --workflow-name and --all-workflows are mutually exclusive", err=True)
        ctx.exit(ExitCode.FAILURE.value)

    effective_workflow_name = None if all_workflows else workflow_name
    full_selector = _get_label_selector(selector, prefix, effective_workflow_name)

    # Check what was passed without manual parsing
    is_follow = ctx.get_parameter_source('follow') != click.core.ParameterSource.DEFAULT
    user_requested_ts = ctx.get_parameter_source('timestamps') != click.core.ParameterSource.DEFAULT

    # ctx.args now automatically excludes everything defined as a @click.option above
    clean_args = ctx.args

    if is_follow:
        # Tailing: Use stern
        cmd = ["stern", "-l", full_selector, "-n", namespace] + clean_args
        logger.info(f"Executing: {' '.join(cmd)}")
        subprocess.run(cmd)
    else:
        try:
            load_k8s_config()
            v1 = client.CoreV1Api()
            pods = v1.list_namespaced_pod(namespace, label_selector=full_selector)
        except Exception as e:
            click.echo(f"Error listing pods: {e}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

        if not pods.items:
            click.echo("No pods found matching the selector.")
            return

        # ExitStack ensures all processes are killed when we exit the block
        with ExitStack() as stack:
            processes = []
            streams = []

            for pod in pods.items:
                p_name = pod.metadata.name
                # Always use --timestamps for the internal merge logic
                cmd = ["kubectl", "logs", "--timestamps", p_name, "-n", namespace] + clean_args

                # We pipe stderr so we can report failures
                p = stack.enter_context(subprocess.Popen(
                    cmd,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                    text=True,
                    bufsize=1  # Line buffered
                ))
                processes.append((p_name, p))
                streams.append(p.stdout)

            # heapq.merge is a generator; it doesn't load everything into memory
            merged_logs = heapq.merge(*streams)

            try:
                for line in merged_logs:
                    if not user_requested_ts:
                        # Strip RFC3339 timestamp (everything before the first space)
                        parts = line.split(" ", 1)
                        output_line = parts[1] if len(parts) > 1 else line
                    else:
                        output_line = line

                    click.echo(output_line.rstrip())
            except KeyboardInterrupt:
                click.echo("\nInterrupted by user.", err=True)
            finally:
                # After logs finish, check if any process failed
                for name, p in processes:
                    # Non-blocking check for process exit
                    if p.poll() is not None and p.returncode != 0:
                        err_out = p.stderr.read().strip()
                        if err_out:
                            click.echo(f"[{name}] Error: {err_out}", err=True)
