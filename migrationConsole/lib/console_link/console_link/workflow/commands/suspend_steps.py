"""Shared utilities for discovering and approving Argo workflow suspend steps."""

import fnmatch
import logging
import time

import click
import requests

from ..services.workflow_service import WorkflowService

logger = logging.getLogger(__name__)

RESET_PREFIX = 'reset:'
ENDING_PHASES = {'Succeeded', 'Failed', 'Error', 'Stopped'}


def fetch_workflow_nodes(workflow_name, namespace, argo_server, token, insecure):
    """Fetch workflow node data. Returns nodes dict or None if not found."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
    response = requests.get(url, headers=headers, verify=not insecure, timeout=10)
    if response.status_code != 200:
        return None
    return response.json().get('status', {}).get('nodes', {})


def _get_step_name(node):
    """Extract the 'name' input parameter from a suspend node."""
    for p in node.get('inputs', {}).get('parameters', []):
        if p.get('name') == 'name':
            return p.get('value', '')
    return ''


def find_suspend_steps(nodes, prefix=None, exclude_prefix=None, phase_filter=None):
    """Find suspend steps in workflow nodes.

    Returns list of (node_id, name_param, display_name, phase).
    - prefix: only include steps whose name starts with this
    - exclude_prefix: skip steps whose name starts with this
    - phase_filter: only include steps with this phase (e.g. 'Running')
    """
    steps = []
    for node_id, node in nodes.items():
        if node.get('type') != 'Suspend':
            continue
        name = _get_step_name(node)
        if not name:
            continue
        if prefix and not name.startswith(prefix):
            continue
        if exclude_prefix and name.startswith(exclude_prefix):
            continue
        phase = node.get('phase', '')
        if phase_filter and phase != phase_filter:
            continue
        steps.append((node_id, name, node.get('displayName', ''), phase))
    return steps


def match_steps(steps, patterns):
    """Filter steps by glob patterns against name_param. Returns matched steps."""
    matches = []
    for pattern in patterns:
        for step in steps:
            if fnmatch.fnmatch(step[1], pattern) and step not in matches:
                matches.append(step)
    return matches


def approve_steps(matches, workflow_name, namespace, argo_server, token, insecure):
    """Approve a list of suspend steps. Returns (approved_count, first_error_message)."""
    service = WorkflowService()
    approved = 0
    for node_id, name_param, display_name, _ in matches:
        result = service.approve_workflow(
            workflow_name=workflow_name,
            namespace=namespace,
            argo_server=argo_server,
            token=token,
            insecure=insecure,
            node_field_selector=f"id={node_id}"
        )
        if result['success']:
            click.echo(f"  ✓ Approved {name_param}")
            approved += 1
        else:
            click.echo(f"  ✗ Failed {name_param}: {result['message']}", err=True)
            return approved, result['message']
    return approved, None


def wait_for_workflow_completion(workflow_name, namespace, argo_server, token, insecure,
                                 timeout_seconds=300):
    """Poll until workflow reaches an ending phase. Returns final phase or None on timeout."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
    deadline = time.time() + timeout_seconds
    while time.time() < deadline:
        try:
            resp = requests.get(url, headers=headers, verify=not insecure, timeout=10)
            if resp.status_code == 200:
                phase = resp.json().get('status', {}).get('phase', '')
                if phase in ENDING_PHASES:
                    return phase
        except requests.RequestException:
            pass
        time.sleep(5)
    return None


def delete_workflow(workflow_name, namespace, argo_server, token, insecure):
    """Delete the Argo workflow. Returns True if deleted."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
    try:
        resp = requests.delete(url, headers=headers, verify=not insecure, timeout=10)
        return resp.status_code == 200
    except requests.RequestException:
        return False
