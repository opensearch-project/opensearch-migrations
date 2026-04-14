"""Shared utilities for Argo workflow lifecycle operations."""

import logging
import time

import requests

logger = logging.getLogger(__name__)

ENDING_PHASES = {'Succeeded', 'Failed', 'Error', 'Stopped'}


def workflow_exists(workflow_name, namespace, argo_server, token, insecure):
    """Check if an Argo workflow exists. Returns True if it does."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
    try:
        resp = requests.get(url, headers=headers, verify=not insecure, timeout=10)
        return resp.status_code == 200
    except requests.RequestException:
        return False


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


def argo_stop(workflow_name, namespace, argo_server, token, insecure):
    """Stop an Argo workflow — prevents new steps from starting."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}/stop"
    try:
        resp = requests.put(url, headers=headers, verify=not insecure, timeout=10)
        return resp.status_code == 200
    except requests.RequestException:
        return False
