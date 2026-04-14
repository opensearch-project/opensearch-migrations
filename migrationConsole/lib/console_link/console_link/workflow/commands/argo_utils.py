"""Shared utilities for Argo workflow lifecycle operations via k8s API."""

import logging

from kubernetes import client
from kubernetes.client.rest import ApiException

logger = logging.getLogger(__name__)

ARGO_GROUP = 'argoproj.io'
ARGO_VERSION = 'v1alpha1'


def workflow_exists(namespace, name):
    """Check if an Argo workflow exists. Returns True if it does."""
    custom = client.CustomObjectsApi()
    try:
        custom.get_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION,
            namespace=namespace, plural='workflows', name=name
        )
        return True
    except ApiException:
        return False


def stop_workflow(namespace, name):
    """Stop an Argo workflow by patching spec.shutdown. Returns True on success."""
    custom = client.CustomObjectsApi()
    try:
        custom.patch_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION,
            namespace=namespace, plural='workflows', name=name,
            body={'spec': {'shutdown': 'Stop'}},
        )
        return True
    except ApiException:
        return False


def delete_workflow(namespace, name):
    """Delete an Argo workflow. Returns True on success or if already gone."""
    custom = client.CustomObjectsApi()
    try:
        custom.delete_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION,
            namespace=namespace, plural='workflows', name=name,
        )
        return True
    except ApiException as e:
        return e.status == 404


def stop_and_delete(namespace, name):
    """Stop then delete a single Argo workflow."""
    stop_workflow(namespace, name)
    delete_workflow(namespace, name)


def stop_and_delete_all(namespace):
    """Stop and delete all Argo workflows in the namespace."""
    custom = client.CustomObjectsApi()
    try:
        items = custom.list_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION,
            namespace=namespace, plural='workflows'
        ).get('items', [])
    except ApiException:
        return
    for wf in items:
        stop_and_delete(namespace, wf['metadata']['name'])


def get_workflow(namespace, name):
    """Get a workflow object by name. Returns None if not found."""
    custom = client.CustomObjectsApi()
    try:
        return custom.get_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION,
            namespace=namespace, plural='workflows', name=name
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
            group=ARGO_GROUP, version=ARGO_VERSION,
            namespace=namespace, plural='workflows'
        ).get('items', [])
    except ApiException:
        return []
