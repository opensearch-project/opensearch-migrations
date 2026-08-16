"""Shared utilities for the k6 run lifecycle via the k8s API.

A run is an **Argo Workflow** that creates a k6-operator **TestRun** and blocks until the operator
reports a terminal stage. Both halves matter: Argo owns submission, parameters and waiting, while the
operator does the work a plain Workflow could not — splitting the load into k6 execution segments
across `parallelism` runner pods and releasing them together.

The run definition lives in one WorkflowTemplate per scenario, rendered by the standalone k6LoadTest
chart (deployment/k8s/charts/components/k6LoadTest). A WorkflowTemplate is inert until instantiated,
so the chart can ship the definition as a real cluster object. Submitting is then a small Workflow
naming the template plus the parameters that differ from its defaults.

The scenarios and load-profile presets themselves are not in the cluster to be read: they are baked
into a data image (migrations/k6_scripts, built from TrafficCapture/trafficLoadTest) that the
WorkflowTemplate mounts at /scripts, so a run names them rather than supplying them.
"""

import logging

from kubernetes import client
from kubernetes.client.rest import ApiException

logger = logging.getLogger(__name__)

K6_GROUP = "k6.io"
K6_VERSION = "v1alpha1"
K6_PLURAL = "testruns"

ARGO_GROUP = "argoproj.io"
ARGO_VERSION = "v1alpha1"
WORKFLOW_PLURAL = "workflows"
WORKFLOW_TEMPLATE_PLURAL = "workflowtemplates"

# Label the chart puts on every WorkflowTemplate it renders, and that the console puts on every
# Workflow it submits — the one selector that finds k6 objects without name-matching.
K6_APP_LABEL = "k6-load-test"


def _custom():
    return client.CustomObjectsApi()


def workflow_template_name(scenario):
    """The WorkflowTemplate the chart renders for a scenario."""
    return f"k6-{scenario}"


def create_workflow(namespace, body):
    """Create a Workflow; returns the server-assigned name."""
    created = _custom().create_namespaced_custom_object(
        group=ARGO_GROUP, version=ARGO_VERSION, namespace=namespace,
        plural=WORKFLOW_PLURAL, body=body,
    )
    return created.get("metadata", {}).get("name", "")


def list_workflows(namespace, label_selector=None):
    """List Workflows in the namespace, optionally filtered by a label selector."""
    kwargs = {"label_selector": label_selector} if label_selector else {}
    return _custom().list_namespaced_custom_object(
        group=ARGO_GROUP, version=ARGO_VERSION, namespace=namespace,
        plural=WORKFLOW_PLURAL, **kwargs,
    ).get("items", [])


def get_workflow(namespace, name):
    """Get a Workflow by name, or None if not found."""
    try:
        return _custom().get_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION, namespace=namespace,
            plural=WORKFLOW_PLURAL, name=name,
        )
    except ApiException as e:
        if e.status == 404:
            return None
        raise


def delete_workflow(namespace, name):
    """Delete a Workflow. Returns True if gone/deleted.

    There is no graceful pause for a k6 run — stopping one means deleting the Workflow. The TestRun
    carries an owner reference to it, so the CR and the operator's runner pods go with it.
    """
    try:
        _custom().delete_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION, namespace=namespace,
            plural=WORKFLOW_PLURAL, name=name,
        )
        return True
    except ApiException as e:
        return e.status == 404


def get_workflow_template(namespace, name):
    """Get a WorkflowTemplate by name, or None if not found."""
    try:
        return _custom().get_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION, namespace=namespace,
            plural=WORKFLOW_TEMPLATE_PLURAL, name=name,
        )
    except ApiException as e:
        if e.status == 404:
            return None
        raise


def list_k6_workflow_templates(namespace):
    """The chart's k6 WorkflowTemplates, found by label rather than by name."""
    return _custom().list_namespaced_custom_object(
        group=ARGO_GROUP, version=ARGO_VERSION, namespace=namespace,
        plural=WORKFLOW_TEMPLATE_PLURAL, label_selector=f"app={K6_APP_LABEL}",
    ).get("items", [])


def list_scenarios(namespace):
    """Launchable scenario names, discovered from the chart's WorkflowTemplates. Each carries a
    k6-scenario label, and each is what a run is submitted against, so anything listed here is
    guaranteed launchable. Returns a sorted list, or [] when the chart is absent — callers fall back
    to their own default so the UI still works."""
    try:
        templates = list_k6_workflow_templates(namespace)
    except ApiException:
        return []
    names = {t.get("metadata", {}).get("labels", {}).get("k6-scenario") for t in templates}
    return sorted(n for n in names if n)


def loadtest_installed(namespace):
    """True if the k6 load-test infra is usable in this namespace.

    Argo itself always ships with the migration, so its presence proves nothing — the probe is for
    the chart's own WorkflowTemplates. Any error is treated as "not installed", so a normal migration
    deployment leaves the `workflow loadtest` commands inert.
    """
    try:
        return bool(list_k6_workflow_templates(namespace))
    except Exception:
        return False
