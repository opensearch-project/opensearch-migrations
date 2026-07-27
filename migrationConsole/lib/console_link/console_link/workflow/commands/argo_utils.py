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


def list_workflows(namespace, label_selector=None):
    """List Argo workflows in the namespace, optionally filtered by a label selector."""
    custom = client.CustomObjectsApi()
    kwargs = {}
    if label_selector:
        kwargs['label_selector'] = label_selector
    try:
        return custom.list_namespaced_custom_object(
            group=ARGO_GROUP,
            version=ARGO_VERSION,
            namespace=namespace,
            plural='workflows',
            **kwargs,
        ).get('items', [])
    except ApiException:
        return []


def submit_workflow_from_template(namespace, template_name, parameters=None,
                                  labels=None, service_account=None, generate_name='wf-'):
    """Submit a Workflow that references an existing WorkflowTemplate.

    Mirrors `argo submit --from workflowtemplate/<name> -p k=v ...` but via the k8s API, so it
    needs no Argo Server URL/token. Returns the server-assigned (generateName) workflow name.
    """
    spec = {'workflowTemplateRef': {'name': template_name}}
    if service_account:
        spec['serviceAccountName'] = service_account
    if parameters:
        spec['arguments'] = {
            'parameters': [{'name': k, 'value': v} for k, v in parameters.items()]
        }
    body = {
        'apiVersion': f'{ARGO_GROUP}/{ARGO_VERSION}',
        'kind': 'Workflow',
        'metadata': {'generateName': generate_name, 'labels': labels or {}},
        'spec': spec,
    }
    custom = client.CustomObjectsApi()
    created = custom.create_namespaced_custom_object(
        group=ARGO_GROUP,
        version=ARGO_VERSION,
        namespace=namespace,
        plural='workflows',
        body=body,
    )
    return created.get('metadata', {}).get('name', '')
