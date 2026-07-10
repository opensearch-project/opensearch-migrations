"""
Integration tests for workflow reset and approve commands using real Kubernetes.

These tests require the dedicated workflow test kube context and create migration
CRDs plus namespaced resources inside that pre-created test cluster.
"""

import logging
import os
import subprocess
import sys
import tempfile
import time
import uuid
import json
from pathlib import Path

import pytest
from click.testing import CliRunner
from kubernetes import client
from kubernetes.client.rest import ApiException

from console_link.workflow.cli import workflow_cli  # noqa: F401
from console_link.workflow.commands.approve import LABEL_WORKFLOW, approve_gate
from console_link.workflow.commands.crd_utils import CRD_GROUP, CRD_VERSION, list_migration_resources
from console_link.workflow.commands.reset import _delete_crd, _get_resource_completions  # noqa: F401

logger = logging.getLogger(__name__)

VALID_PHASES = {
    "approvalgates": "Initialized",
    "capturedtraffics": "Ready",
    "captureproxies": "Ready",
    "datasnapshots": "Completed",
    "kafkaclusters": "Ready",
    "snapshotmigrations": "Running",
    "trafficreplays": "Ready",
}


CRD_MANIFESTS = []
for _kind, _plural, _singular in [
    ("ApprovalGate", "approvalgates", "approvalgate"),
    ("CapturedTraffic", "capturedtraffics", "capturedtraffic"),
    ("CaptureProxy", "captureproxies", "captureproxy"),
    ("DataSnapshot", "datasnapshots", "datasnapshot"),
    ("SnapshotMigration", "snapshotmigrations", "snapshotmigration"),
    ("TrafficReplay", "trafficreplays", "trafficreplay"),
    ("KafkaCluster", "kafkaclusters", "kafkacluster"),
]:
    CRD_MANIFESTS.append({
        "apiVersion": "apiextensions.k8s.io/v1",
        "kind": "CustomResourceDefinition",
        "metadata": {"name": f"{_plural}.{CRD_GROUP}"},
        "spec": {
            "group": CRD_GROUP,
            "names": {"kind": _kind, "plural": _plural, "singular": _singular},
            "scope": "Namespaced",
            "versions": [{
                "name": CRD_VERSION,
                "served": True,
                "storage": True,
                "subresources": {"status": {}},
                "schema": {
                    "openAPIV3Schema": {
                        "type": "object",
                        "properties": {
                            "spec": {"type": "object", "x-kubernetes-preserve-unknown-fields": True},
                            "status": {"type": "object", "x-kubernetes-preserve-unknown-fields": True},
                        }
                    }
                }
            }]
        }
    })


@pytest.fixture(scope="session")
def k8s_cluster(required_workflow_test_kube_context):
    logger.info(
        "Using workflow test kube context: %s",
        required_workflow_test_kube_context["active_context"],
    )
    return required_workflow_test_kube_context


@pytest.fixture(scope="session")
def migration_crds(k8s_cluster):
    ext = client.ApiextensionsV1Api()
    for crd in CRD_MANIFESTS:
        try:
            ext.create_custom_resource_definition(body=crd)
        except ApiException as e:
            if e.status != 409:
                raise
    for crd in CRD_MANIFESTS:
        _wait_for_crd(ext, crd["metadata"]["name"])
    yield


@pytest.fixture
def reset_ns(k8s_cluster, migration_crds):
    ns_name = f"reset-test-{uuid.uuid4().hex[:8]}"
    v1 = client.CoreV1Api()
    v1.create_namespace(body=client.V1Namespace(metadata=client.V1ObjectMeta(name=ns_name)))
    yield ns_name
    try:
        v1.delete_namespace(name=ns_name, grace_period_seconds=0)
    except ApiException:
        pass


@pytest.fixture
def runner():
    return CliRunner()


def _wait_for_crd(ext_api, crd_name, timeout=30):
    start = time.time()
    while time.time() - start < timeout:
        crd = ext_api.read_custom_resource_definition(crd_name)
        for cond in (crd.status.conditions or []):
            if cond.type == "Established" and cond.status == "True":
                return
        time.sleep(1)
    raise TimeoutError(f"CRD {crd_name} not established in {timeout}s")


def _wait_for_crd_endpoint(custom, namespace, plural, timeout=60):
    deadline = time.time() + timeout
    while True:
        try:
            custom.list_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=namespace,
                plural=plural,
            )
            return
        except ApiException as e:
            if e.status != 404 or time.time() >= deadline:
                raise
            time.sleep(0.5)


def _create_crd_instance(namespace, plural, name, phase=None, depends_on=None, labels=None):
    custom = client.CustomObjectsApi()
    _wait_for_crd_endpoint(custom, namespace, plural)
    body = {
        "apiVersion": f"{CRD_GROUP}/{CRD_VERSION}",
        "kind": {
            "approvalgates": "ApprovalGate",
            "capturedtraffics": "CapturedTraffic",
            "captureproxies": "CaptureProxy",
            "datasnapshots": "DataSnapshot",
            "kafkaclusters": "KafkaCluster",
            "snapshotmigrations": "SnapshotMigration",
            "trafficreplays": "TrafficReplay",
        }[plural],
        "metadata": {"name": name, "namespace": namespace, "labels": labels or {}},
        "spec": {"dependsOn": depends_on or []},
    }

    create_deadline = time.time() + 60
    while True:
        try:
            custom.create_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=namespace,
                plural=plural,
                body=body,
            )
            break
        except ApiException as e:
            if e.status != 404 or time.time() >= create_deadline:
                raise
            time.sleep(0.5)
    if phase:
        status_deadline = time.time() + 60
        while True:
            try:
                custom.patch_namespaced_custom_object_status(
                    group=CRD_GROUP,
                    version=CRD_VERSION,
                    namespace=namespace,
                    plural=plural,
                    name=name,
                    body={"status": {"phase": phase}},
                )
                break
            except ApiException as e:
                if e.status != 404 or time.time() >= status_deadline:
                    raise
                time.sleep(0.5)


def _invoke_workflow_cli(runner, args):
    env = os.environ.copy()
    if os.environ.get("KUBECONFIG"):
        env["KUBECONFIG"] = os.environ["KUBECONFIG"]
    completed = subprocess.run(
        [sys.executable, "-m", "console_link.workflow.cli", *args],
        capture_output=True,
        text=True,
        env=env,
        check=False,
    )
    return type("CliResult", (), {
        "exit_code": completed.returncode,
        "output": completed.stdout + completed.stderr,
        "stdout": completed.stdout,
        "stderr": completed.stderr,
    })()


def _get_resource_completions_subprocess(namespace, incomplete):
    env = os.environ.copy()
    if os.environ.get("KUBECONFIG"):
        env["KUBECONFIG"] = os.environ["KUBECONFIG"]
    completed = subprocess.run(
        [
            sys.executable,
            "-c",
            (
                "import json; "
                "from console_link.workflow.commands.reset import _get_resource_completions; "
                "FakeCtx = type('FakeCtx', (), {'params': {'namespace': %r}}); "
                "print(json.dumps(_get_resource_completions(FakeCtx(), None, %r)))"
            ) % (namespace, incomplete)
        ],
        capture_output=True,
        text=True,
        env=env,
        check=False,
    )
    assert completed.returncode == 0, completed.stdout + completed.stderr
    return json.loads(completed.stdout)


def _get_phase(namespace, plural, name):
    custom = client.CustomObjectsApi()
    obj = custom.get_namespaced_custom_object(
        group=CRD_GROUP,
        version=CRD_VERSION,
        namespace=namespace,
        plural=plural,
        name=name,
    )
    return obj.get("status", {}).get("phase", "Unknown")


def _assert_deleted(namespace, plural, name, timeout=30):
    custom = client.CustomObjectsApi()
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            custom.get_namespaced_custom_object(
                group=CRD_GROUP,
                version=CRD_VERSION,
                namespace=namespace,
                plural=plural,
                name=name,
            )
            time.sleep(1)
        except ApiException as e:
            assert e.status == 404
            return
    pytest.fail(f"{plural}/{name} was not deleted within {timeout}s")


def _wait_for_resource_names(namespace, expected_names, timeout=30):
    deadline = time.time() + timeout
    while time.time() < deadline:
        names = {name for _, name, _, _ in list_migration_resources(namespace)}
        if expected_names.issubset(names):
            return
        time.sleep(1)
    pytest.fail(f"Resources were not listable within {timeout}s: {sorted(expected_names)}")


@pytest.mark.slow
class TestListMigrationCrdsIntegration:
    def test_lists_crds_across_types(self, reset_ns):
        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-a", phase=VALID_PHASES["capturedtraffics"])
        _create_crd_instance(reset_ns, "snapshotmigrations", "snap-a", phase=VALID_PHASES["snapshotmigrations"])
        _create_crd_instance(reset_ns, "trafficreplays", "replay-a", phase=VALID_PHASES["trafficreplays"])

        results = list_migration_resources(reset_ns)
        names = {n for _, n, _, _ in results}
        assert names == {"proxy-a", "snap-a", "replay-a"}

    def test_empty_namespace(self, reset_ns):
        assert list_migration_resources(reset_ns) == []

    def test_shows_unknown_when_no_status(self, reset_ns):
        _create_crd_instance(reset_ns, "capturedtraffics", "no-status")
        results = list_migration_resources(reset_ns)
        assert results == [("capturedtraffics", "no-status", "Unknown", [])]


@pytest.mark.slow
class TestDeleteCrdIntegration:
    def test_deletes_crd(self, reset_ns):
        _create_crd_instance(reset_ns, "snapshotmigrations", "snap-b", phase=VALID_PHASES["snapshotmigrations"])
        assert _delete_crd(reset_ns, "snapshotmigrations", "snap-b") is True
        _assert_deleted(reset_ns, "snapshotmigrations", "snap-b")

    def test_delete_nonexistent_returns_true(self, reset_ns):
        assert _delete_crd(reset_ns, "snapshotmigrations", "does-not-exist") is True


@pytest.mark.slow
class TestResetListIntegration:
    def test_list_shows_friendly_names(self, reset_ns):
        _create_crd_instance(reset_ns, "captureproxies", "my-proxy", phase=VALID_PHASES["captureproxies"])
        _create_crd_instance(reset_ns, "snapshotmigrations", "my-snap", phase=VALID_PHASES["snapshotmigrations"])
        _wait_for_resource_names(reset_ns, {"my-proxy", "my-snap"})

        result = subprocess.run(
            [sys.executable, "-m", "console_link.workflow.cli", "reset", "--namespace", reset_ns],
            capture_output=True,
            text=True,
            env=os.environ.copy(),
            check=False,
        )
        assert result.returncode == 0
        assert "captureproxy.my-proxy" in result.stdout
        assert "snapshotmigration.my-snap" in result.stdout
        assert "workflow reset --all" in result.stdout
        assert "workflow submit" in result.stdout

    def test_list_empty_namespace(self, runner, reset_ns):
        result = _invoke_workflow_cli(runner, ["reset", "--namespace", reset_ns])
        assert "No migration resources" in result.output


@pytest.mark.slow
class TestResetSingleIntegration:
    def test_reset_specific_resource(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "snapshotmigrations", "snap-c", phase=VALID_PHASES["snapshotmigrations"])

        result = _invoke_workflow_cli(runner, ["reset", "snap-c", "--namespace", reset_ns])
        assert result.exit_code == 0
        assert "Deleted snapshotmigration.snap-c" in result.output
        _assert_deleted(reset_ns, "snapshotmigrations", "snap-c")

    def test_reset_nonexistent_resource(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-e", phase=VALID_PHASES["capturedtraffics"])

        result = _invoke_workflow_cli(runner, ["reset", "no-such-thing", "--namespace", reset_ns])
        assert "No resources matching" in result.output

    def test_reset_proxy_requires_include_proxies(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "captureproxies", "proxy-p", phase=VALID_PHASES["captureproxies"])

        result = _invoke_workflow_cli(runner, ["reset", "proxy-p", "--namespace", reset_ns])
        assert result.exit_code != 0
        assert "Proxies are protected by default" in result.output

    def test_reset_kafka_blocked_by_captured_traffic_dependency(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "kafkaclusters", "kafka-a", phase=VALID_PHASES["kafkaclusters"])
        _create_crd_instance(
            reset_ns,
            "capturedtraffics",
            "topic-a",
            phase=VALID_PHASES["capturedtraffics"],
            depends_on=["kafka-a"],
        )
        _create_crd_instance(
            reset_ns,
            "captureproxies",
            "proxy-a",
            phase=VALID_PHASES["captureproxies"],
            depends_on=["topic-a"],
        )

        result = _invoke_workflow_cli(runner, ["reset", "kafka-a", "--namespace", reset_ns])
        assert result.exit_code != 0
        assert "Cannot delete because dependent resources still exist:" in result.output
        assert "capturedtraffic.topic-a" in result.output
        assert "--cascade" in result.output
        assert _get_phase(reset_ns, "kafkaclusters", "kafka-a") == VALID_PHASES["kafkaclusters"]
        assert _get_phase(reset_ns, "capturedtraffics", "topic-a") == VALID_PHASES["capturedtraffics"]
        assert _get_phase(reset_ns, "captureproxies", "proxy-a") == VALID_PHASES["captureproxies"]

    def test_reset_blocks_on_dependents_without_cascade(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "datasnapshots", "snap-a", phase=VALID_PHASES["datasnapshots"])
        _create_crd_instance(
            reset_ns,
            "snapshotmigrations",
            "mig-a",
            phase=VALID_PHASES["snapshotmigrations"],
            depends_on=["snap-a"],
        )

        result = _invoke_workflow_cli(runner, ["reset", "snap-a", "--namespace", reset_ns])
        assert result.exit_code != 0
        assert "Cannot delete because dependent resources still exist:" in result.output
        assert "snapshotmigration.mig-a" in result.output
        assert "--cascade" in result.output

    def test_reset_with_cascade_deletes_dependents(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "datasnapshots", "snap-z", phase=VALID_PHASES["datasnapshots"])
        _create_crd_instance(
            reset_ns,
            "snapshotmigrations",
            "mig-z",
            phase=VALID_PHASES["snapshotmigrations"],
            depends_on=["snap-z"],
        )

        result = _invoke_workflow_cli(runner, ["reset", "snap-z", "--cascade", "--namespace", reset_ns])
        assert result.exit_code == 0
        _assert_deleted(reset_ns, "snapshotmigrations", "mig-z")
        _assert_deleted(reset_ns, "datasnapshots", "snap-z")

    def test_reset_with_cascade_converges_after_partial_prior_deletion(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "datasnapshots", "snap-r", phase=VALID_PHASES["datasnapshots"])
        _create_crd_instance(
            reset_ns,
            "snapshotmigrations",
            "mig-r",
            phase=VALID_PHASES["snapshotmigrations"],
            depends_on=["snap-r"],
        )

        assert _delete_crd(reset_ns, "snapshotmigrations", "mig-r") is True
        _assert_deleted(reset_ns, "snapshotmigrations", "mig-r")

        result = _invoke_workflow_cli(runner, ["reset", "snap-r", "--cascade", "--namespace", reset_ns])
        assert result.exit_code == 0
        _assert_deleted(reset_ns, "datasnapshots", "snap-r")


@pytest.mark.slow
class TestResetAllIntegration:
    def test_reset_all_deletes_all_crds(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "snapshotmigrations", "snap-f", phase=VALID_PHASES["snapshotmigrations"])
        _create_crd_instance(reset_ns, "trafficreplays", "replay-f", phase=VALID_PHASES["trafficreplays"])

        result = _invoke_workflow_cli(runner, ["reset", "--all", "--namespace", reset_ns])
        assert result.exit_code == 0
        _assert_deleted(reset_ns, "snapshotmigrations", "snap-f")
        _assert_deleted(reset_ns, "trafficreplays", "replay-f")
        assert "Deleted" in result.output

    def test_reset_all_skips_only_proxies(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "kafkaclusters", "kafka-g", phase=VALID_PHASES["kafkaclusters"])
        _create_crd_instance(
            reset_ns,
            "capturedtraffics",
            "topic-g",
            phase=VALID_PHASES["capturedtraffics"],
            depends_on=["kafka-g"],
        )
        _create_crd_instance(
            reset_ns,
            "captureproxies",
            "proxy-g",
            phase=VALID_PHASES["captureproxies"],
            depends_on=["topic-g"],
        )
        _create_crd_instance(
            reset_ns,
            "trafficreplays",
            "replay-g",
            phase=VALID_PHASES["trafficreplays"],
            depends_on=["proxy-g"],
        )

        result = _invoke_workflow_cli(runner, ["reset", "--all", "--namespace", reset_ns])
        assert result.exit_code == 0
        _assert_deleted(reset_ns, "trafficreplays", "replay-g")
        _assert_deleted(reset_ns, "capturedtraffics", "topic-g")
        _assert_deleted(reset_ns, "kafkaclusters", "kafka-g")
        assert _get_phase(reset_ns, "captureproxies", "proxy-g") == VALID_PHASES["captureproxies"]
        assert "Keeping protected proxies alive: captureproxy.proxy-g" in result.output
        assert "Use --include-proxies to delete them." in result.output

    def test_reset_all_with_include_proxies_deletes_everything(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "kafkaclusters", "kafka-h", phase=VALID_PHASES["kafkaclusters"])
        _create_crd_instance(
            reset_ns,
            "capturedtraffics",
            "topic-h",
            phase=VALID_PHASES["capturedtraffics"],
            depends_on=["kafka-h"],
        )
        _create_crd_instance(
            reset_ns,
            "captureproxies",
            "proxy-h",
            phase=VALID_PHASES["captureproxies"],
            depends_on=["topic-h"],
        )

        result = _invoke_workflow_cli(runner, ["reset", "--all", "--include-proxies", "--namespace", reset_ns])
        assert result.exit_code == 0
        _assert_deleted(reset_ns, "capturedtraffics", "topic-h")
        _assert_deleted(reset_ns, "captureproxies", "proxy-h")
        _assert_deleted(reset_ns, "kafkaclusters", "kafka-h")


@pytest.mark.slow
class TestApproveIntegration:
    def test_list_approval_gates(self, reset_ns):
        _create_crd_instance(reset_ns, "approvalgates", "gate-a", phase=VALID_PHASES["approvalgates"])
        _create_crd_instance(reset_ns, "approvalgates", "gate-b", phase="Approved")

        gates = list_migration_resources(reset_ns, ["approvalgates"])
        gate_dict = {n: p for _, n, p, _ in gates}
        assert gate_dict["gate-a"] == VALID_PHASES["approvalgates"]
        assert gate_dict["gate-b"] == "Approved"

    def test_approve_gate(self, reset_ns):
        _create_crd_instance(reset_ns, "approvalgates", "gate-c", phase=VALID_PHASES["approvalgates"])

        assert approve_gate(reset_ns, "gate-c") is True
        assert _get_phase(reset_ns, "approvalgates", "gate-c") == "Approved"

    def test_approve_cli_with_glob(self, runner, reset_ns):
        workflow_labels = {LABEL_WORKFLOW: "migration-workflow"}
        _create_crd_instance(
            reset_ns, "approvalgates", "eval-metadata",
            phase=VALID_PHASES["approvalgates"], labels=workflow_labels,
        )
        _create_crd_instance(
            reset_ns, "approvalgates", "migrate-metadata",
            phase=VALID_PHASES["approvalgates"], labels=workflow_labels,
        )

        result = _invoke_workflow_cli(runner, ["approve", "step", "--pre-approve", "eval-*", "--namespace", reset_ns])
        assert result.exit_code == 0
        assert "Approved eval-metadata" in result.output
        assert _get_phase(reset_ns, "approvalgates", "eval-metadata") == "Approved"
        assert _get_phase(reset_ns, "approvalgates", "migrate-metadata") == VALID_PHASES["approvalgates"]

    def test_approve_cli_no_pending(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "approvalgates", "gate-d", phase="Approved")

        result = _invoke_workflow_cli(runner, ["approve", "step", "gate-d", "--namespace", reset_ns])
        assert "No steps are currently being waited on" in result.output

    def test_approve_cli_no_match(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "approvalgates", "gate-e", phase=VALID_PHASES["approvalgates"])

        result = _invoke_workflow_cli(runner, ["approve", "step", "nonexistent-*", "--namespace", reset_ns])
        assert "No steps are currently being waited on" in result.output


@pytest.mark.slow
class TestAutocompleteIntegration:
    def test_autocomplete_returns_live_resources(self, reset_ns):
        cache_file = (
            Path(tempfile.gettempdir()) / "workflow_completions" / f"reset_resources_{reset_ns}.json"
        )
        cache_file.unlink(missing_ok=True)

        _create_crd_instance(reset_ns, "captureproxies", "proxy-ac", phase=VALID_PHASES["captureproxies"])
        _create_crd_instance(reset_ns, "snapshotmigrations", "snap-ac", phase=VALID_PHASES["snapshotmigrations"])
        completions = _get_resource_completions_subprocess(reset_ns, "")
        assert "captureproxy.proxy-ac" in completions
        assert "snapshotmigration.snap-ac" in completions

    def test_autocomplete_filters_by_prefix(self, reset_ns):
        cache_file = (
            Path(tempfile.gettempdir()) / "workflow_completions" / f"reset_resources_{reset_ns}.json"
        )
        cache_file.unlink(missing_ok=True)

        _create_crd_instance(reset_ns, "captureproxies", "proxy-x", phase=VALID_PHASES["captureproxies"])
        _create_crd_instance(reset_ns, "captureproxies", "other-y", phase=VALID_PHASES["captureproxies"])
        completions = _get_resource_completions_subprocess(reset_ns, "captureproxy.proxy")
        assert "captureproxy.proxy-x" in completions
        assert "captureproxy.other-y" not in completions
