"""Shared utilities for the k6 run lifecycle via the k8s API.

A run is an **Argo Workflow** that creates a k6-operator **TestRun** and blocks until the operator
reports a terminal stage. Both halves matter: Argo owns submission, parameters and waiting, while the
operator does the work a plain Workflow could not — splitting the load into k6 execution segments
across `parallelism` runner pods and releasing them together.

The run definition lives in one WorkflowTemplate per scenario, rendered by the standalone k6LoadTest
chart (deployment/k8s/charts/components/k6LoadTest). A WorkflowTemplate is inert until instantiated,
so the chart can ship the definition as a real cluster object. Submitting is then a small Workflow
naming the template plus the parameters that differ from its defaults.

The scenarios and load-profile presets themselves are baked into the load-test-only
migrations/k6_runner image with the pinned k6 executable and extensions, so a run names them rather
than supplying them.
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

# The k6-operator's own labels on the pods it starts for a run. `k6_cr` is the TestRun name, which
# the WorkflowTemplate makes equal to the Workflow name, so a run's one name selects its pods.
# `runner=true` keeps the initializer and starter pods out. Stated once because three things need
# exactly this set: the log stream, the health poll, and any test that fakes either.


def runner_selector(name):
    """The label selector for a run's k6 runner pods."""
    return f"k6_cr={name},runner=true"


def _custom():
    return client.CustomObjectsApi()


def workflow_template_name(profile):
    """The WorkflowTemplate the chart renders for a load profile (e.g. 'ingest-burst')."""
    return f"k6-{profile}"


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
    """Delete a Workflow. Returns True if it was deleted, False if there was no such Workflow.

    There is no graceful pause for a k6 run — stopping one means deleting the Workflow. The TestRun
    carries an owner reference to it, so the CR and the operator's runner pods go with it.

    A 404 is reported as False, not as success. Reporting it as success made `stop` announce that it
    had stopped a run that was never there, which is exactly the case a user needs to be told about:
    a typo in the name, or a run that had already finished and been reaped.
    """
    try:
        _custom().delete_namespaced_custom_object(
            group=ARGO_GROUP, version=ARGO_VERSION, namespace=namespace,
            plural=WORKFLOW_PLURAL, name=name,
        )
        return True
    except ApiException as e:
        if e.status == 404:
            return False
        raise


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


# The container that runs k6 inside a runner pod. Its exit code is k6's own verdict on the run.
K6_CONTAINER = "k6"


def list_runner_pods(namespace, name):
    """A run's k6 runner pods as {pod, ip, phase, exit_code} dicts.

    Only the four fields a caller needs come back, so nothing outside this module handles a
    kubernetes model object. `ip` is None until the pod is scheduled and has an address, and
    `exit_code` is None until the k6 container has exited.

    The pod read is the grant the k6LoadTest chart already gives the console for `loadtest logs`
    (see the chart's rbac.yaml), so nothing new is needed to poll a run's health.
    """
    pods = client.CoreV1Api().list_namespaced_pod(
        namespace=namespace, label_selector=runner_selector(name)).items
    return [{"pod": p.metadata.name,
             "ip": p.status.pod_ip,
             "phase": p.status.phase,
             "exit_code": _k6_exit_code(p)} for p in pods]


def _k6_exit_code(pod):
    """The k6 container's exit code, or None while it is still running (or has no status yet)."""
    for status in (pod.status.container_statuses or []):
        if status.name == K6_CONTAINER and status.state and status.state.terminated:
            return status.state.terminated.exit_code
    return None


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
    to their own default so the UI still works.

    This swallows an ApiException on purpose, because its callers (shell completion, the launch
    form) must degrade to a default rather than fail. Anything that reports to the user whether the
    chart is installed must call list_k6_workflow_templates directly instead, so a cluster error is
    not misreported as "not installed" — see runs.chart_missing_hint.
    """
    try:
        templates = list_k6_workflow_templates(namespace)
    except ApiException:
        return []
    names = {t.get("metadata", {}).get("labels", {}).get("k6-scenario") for t in templates}
    return sorted(n for n in names if n)


def list_profiles(namespace, scenario=None):
    """Launchable load-profile names (the k6-profile label), optionally for one scenario.

    A profile is a whole WorkflowTemplate, so unlike the old preset list this is discovered rather
    than mirrored from the image — whatever the installed chart renders is what can be run.
    Degrades to [] on an ApiException for the same reason as list_scenarios.
    """
    try:
        templates = list_k6_workflow_templates(namespace)
    except ApiException:
        return []
    names = set()
    for t in templates:
        labels = t.get("metadata", {}).get("labels", {})
        if scenario and labels.get("k6-scenario") != scenario:
            continue
        if labels.get("k6-profile"):
            names.add(labels["k6-profile"])
    return sorted(names)
