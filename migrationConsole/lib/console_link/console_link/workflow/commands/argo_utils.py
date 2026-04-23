"""Shared utilities for Argo workflow lifecycle operations via k8s API."""

import os
import time
from kubernetes import client
from kubernetes.client.rest import ApiException

DEFAULT_ARGO_SERVER_URL = (
    f"https://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
    f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}"
)

ARGO_GROUP = 'argoproj.io'
ARGO_VERSION = 'v1alpha1'


def workflow_exists(namespace, name):
    """Check if an Argo workflow exists."""
    custom = client.CustomObjectsApi()
    try:
        custom.get_namespaced_custom_object(
            group=ARGO_GROUP,
            version=ARGO_VERSION,
            namespace=namespace,
            plural='workflows',
            name=name,
        )
        return True
    except ApiException as e:
        if e.status == 404:
            return False
        raise


def stop_workflow(namespace, name):
    """Stop an Argo workflow by patching spec.shutdown."""
    custom = client.CustomObjectsApi()
    try:
        custom.patch_namespaced_custom_object(
            group=ARGO_GROUP,
            version=ARGO_VERSION,
            namespace=namespace,
            plural='workflows',
            name=name,
            body={'spec': {'shutdown': 'Stop'}},
        )
        return True
    except ApiException:
        return False


def delete_workflow(namespace, name):
    """Delete an Argo workflow."""
    custom = client.CustomObjectsApi()
    try:
        custom.delete_namespaced_custom_object(
            group=ARGO_GROUP,
            version=ARGO_VERSION,
            namespace=namespace,
            plural='workflows',
            name=name,
        )
        return True
    except ApiException as e:
        return e.status == 404


def wait_until_workflow_deleted(namespace, name, timeout_seconds=30, interval_seconds=1):
    """Poll until a workflow is gone, or time out."""
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if not workflow_exists(namespace, name):
            return True
        time.sleep(interval_seconds)
    return not workflow_exists(namespace, name)


def get_workflow(namespace, name):
    """Get a workflow object by name. Returns None if not found."""
    custom = client.CustomObjectsApi()
    try:
        return custom.get_namespaced_custom_object(
            group=ARGO_GROUP,
            version=ARGO_VERSION,
            namespace=namespace,
            plural='workflows',
            name=name,
        )
    except ApiException as e:
        if e.status == 404:
            return None
        raise


def list_workflows(namespace):
    """List all Argo workflows in the namespace."""
    custom = client.CustomObjectsApi()
    try:
        return custom.list_namespaced_custom_object(
            group=ARGO_GROUP,
            version=ARGO_VERSION,
            namespace=namespace,
            plural='workflows',
        ).get('items', [])
    except ApiException:
        return []
