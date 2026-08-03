"""Shared utilities for k6-operator TestRun lifecycle via the k8s API.

Load tests run as TestRun CRs (k6.io/v1alpha1) managed by the k6-operator that ships in the
standalone k6LoadTest chart — NOT as Argo workflows. This module is a thin CustomObjectsApi
wrapper plus helpers to read that chart's ConfigMaps and detect whether the load-test infra is
installed at all (so the `workflow k6` commands stay inert in a normal migration deployment).
"""

import logging

from kubernetes import client
from kubernetes.client.rest import ApiException

logger = logging.getLogger(__name__)

K6_GROUP = "k6.io"
K6_VERSION = "v1alpha1"
K6_PLURAL = "testruns"

# ConfigMaps rendered by the k6LoadTest chart.
SCENARIOS_CONFIGMAP = "k6-scenarios"
# One ready-to-run TestRun (JSON) per scenario — the single source of the run spec (items mount,
# image, K6_OUT, default envFrom preset). The console loads and patches these rather than building
# a spec from scratch, so Helm stays the one definition.
EXAMPLES_CONFIGMAP = "k6-testrun-examples"
# Each load-profile preset is rendered as a ConfigMap named k6-preset-<name>, labeled
# k6-preset=<name> (see charts/components/k6LoadTest/templates/k6-presets-configmap.yaml). The
# label is how we enumerate the presets that actually exist in the cluster instead of hardcoding
# them. The chart's RBAC already grants list on configmaps.
PRESET_LABEL = "k6-preset"


def create_testrun(namespace, body):
    """Create a TestRun; returns the server-assigned name."""
    custom = client.CustomObjectsApi()
    created = custom.create_namespaced_custom_object(
        group=K6_GROUP, version=K6_VERSION, namespace=namespace, plural=K6_PLURAL, body=body,
    )
    return created.get("metadata", {}).get("name", "")


def list_testruns(namespace, label_selector=None):
    """List TestRuns in the namespace, optionally filtered by a label selector."""
    custom = client.CustomObjectsApi()
    kwargs = {}
    if label_selector:
        kwargs["label_selector"] = label_selector
    return custom.list_namespaced_custom_object(
        group=K6_GROUP, version=K6_VERSION, namespace=namespace, plural=K6_PLURAL, **kwargs,
    ).get("items", [])


def get_testrun(namespace, name):
    """Get a TestRun by name, or None if not found."""
    custom = client.CustomObjectsApi()
    try:
        return custom.get_namespaced_custom_object(
            group=K6_GROUP, version=K6_VERSION, namespace=namespace, plural=K6_PLURAL, name=name,
        )
    except ApiException as e:
        if e.status == 404:
            return None
        raise


def delete_testrun(namespace, name):
    """Delete a TestRun (the operator garbage-collects its pods). Returns True if gone/deleted.

    There is no graceful pause for a TestRun — stopping a run means deleting the CR, which the
    operator reconciles by tearing down its runner/initializer pods.
    """
    custom = client.CustomObjectsApi()
    try:
        custom.delete_namespaced_custom_object(
            group=K6_GROUP, version=K6_VERSION, namespace=namespace, plural=K6_PLURAL, name=name,
        )
        return True
    except ApiException as e:
        return e.status == 404


def read_configmap(namespace, name):
    """Return a ConfigMap's data dict, or {} if the ConfigMap is absent."""
    core = client.CoreV1Api()
    try:
        cm = core.read_namespaced_config_map(name=name, namespace=namespace)
        return cm.data or {}
    except ApiException as e:
        if e.status == 404:
            return {}
        raise


def list_presets(namespace):
    """Config preset names available in the cluster, discovered from the k6-preset-* ConfigMaps the
    k6LoadTest chart renders (each labeled k6-preset=<name>). Returns a sorted list, or [] when none
    are found or the API call fails — callers fall back to their own default so the UI still works."""
    core = client.CoreV1Api()
    try:
        result = core.list_namespaced_config_map(namespace=namespace, label_selector=PRESET_LABEL)
    except ApiException:
        return []
    names = ((cm.metadata.labels or {}).get(PRESET_LABEL) for cm in result.items)
    return sorted(name for name in names if name)


def list_scenarios(namespace):
    """Launchable scenario names, discovered from the k6-testrun-examples ConfigMap — one JSON
    example per scenario, keyed by scenario name. These are the same keys build_testrun_spec looks
    up via load_example, so anything listed here is guaranteed launchable. Returns a sorted list, or
    [] when the ConfigMap is absent — callers fall back to their own default so the UI still works."""
    return sorted(read_configmap(namespace, EXAMPLES_CONFIGMAP).keys())


def loadtest_installed(namespace):
    """True if the k6 load-test infra (TestRun CRD) is usable in this namespace.

    A namespaced testruns list returns 200 when the CRD exists (RBAC ships with the same chart),
    404 when the CRD is absent. Any error is treated as "not installed", so a normal migration
    deployment (no operator) leaves the `workflow k6` commands inert.
    """
    try:
        client.CustomObjectsApi().list_namespaced_custom_object(
            group=K6_GROUP, version=K6_VERSION, namespace=namespace, plural=K6_PLURAL, limit=1,
        )
        return True
    except Exception:
        return False
