"""
Integration tests for workflow reset and approve commands using real Kubernetes.

Uses the same k3s/kind cluster infrastructure pattern as test_workflow_integration.py.
Creates migration CRDs, then tests the full reset and approve CLI flows.
"""

import logging
import os
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

import pytest
from click.testing import CliRunner
from kubernetes import client, config
from kubernetes.client.rest import ApiException
from testcontainers.k3s import K3SContainer

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands.approve import approve_gate
from console_link.workflow.commands.crd_utils import CRD_GROUP, CRD_VERSION, list_migration_resources
from console_link.workflow.commands.reset import _delete_crd, _get_resource_completions

logger = logging.getLogger(__name__)

VALID_PHASES = {
    "approvalgates": "Initialized",
    "capturedtraffics": "Ready",
    "datasnapshots": "Completed",
    "kafkaclusters": "Ready",
    "snapshotmigrations": "Running",
    "trafficreplays": "Ready",
}


CRD_MANIFESTS = []
for _kind, _plural, _singular in [
    ("ApprovalGate", "approvalgates", "approvalgate"),
    ("CapturedTraffic", "capturedtraffics", "capturedtraffic"),
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


def _detect_existing_cluster():
    if os.environ.get("USE_EXISTING_TEST_K8S") != "1":
        return False
    try:
        config.load_kube_config()
        client.CoreV1Api().list_namespace(timeout_seconds=5)
        return True
    except Exception:
        return False


def _configure_docker_host_for_testcontainers():
    if os.environ.get("DOCKER_HOST"):
        return

    for socket_path in [
        Path.home() / ".docker/run/docker.sock",
        Path.home() / "Library/Containers/com.docker.docker/Data/docker-cli.sock",
    ]:
        if socket_path.exists():
            os.environ["DOCKER_HOST"] = f"unix://{socket_path}"
            return


@pytest.fixture(scope="session")
def k8s_cluster():
    if _detect_existing_cluster():
        logger.info("Using existing Kubernetes cluster")
        yield
    else:
        logger.info("Starting k3s container...")
        _configure_docker_host_for_testcontainers()
        container = K3SContainer(image="rancher/k3s:latest")
        container.start()
        kubeconfig = container.config_yaml()
        with tempfile.NamedTemporaryFile(mode='w', suffix='.yaml', delete=False) as f:
            f.write(kubeconfig)
            kubeconfig_path = f.name
        os.environ['KUBECONFIG'] = kubeconfig_path
        config.load_kube_config(config_file=kubeconfig_path)
        yield
        container.stop()
        os.unlink(kubeconfig_path)
        del os.environ['KUBECONFIG']


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


def _create_crd_instance(namespace, plural, name, phase=None, depends_on=None):
    custom = client.CustomObjectsApi()
    body = {
        "apiVersion": f"{CRD_GROUP}/{CRD_VERSION}",
        "kind": {
            "approvalgates": "ApprovalGate",
            "capturedtraffics": "CapturedTraffic",
            "datasnapshots": "DataSnapshot",
            "kafkaclusters": "KafkaCluster",
            "snapshotmigrations": "SnapshotMigration",
            "trafficreplays": "TrafficReplay",
        }[plural],
        "metadata": {"name": name, "namespace": namespace},
        "spec": {"dependsOn": depends_on or []},
    }

    create_deadline = time.time() + 15
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
        status_deadline = time.time() + 15
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
    env = {}
    if os.environ.get("KUBECONFIG"):
        env["KUBECONFIG"] = os.environ["KUBECONFIG"]
    return runner.invoke(workflow_cli, args, env=env)


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
        _create_crd_instance(reset_ns, "capturedtraffics", "my-proxy", phase=VALID_PHASES["capturedtraffics"])
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
        assert "Capture Proxy" in result.stdout
        assert "my-proxy" in result.stdout
        assert "Snapshot Migration" in result.stdout
        assert "my-snap" in result.stdout
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
        assert "Deleted snap-c" in result.output
        _assert_deleted(reset_ns, "snapshotmigrations", "snap-c")

    def test_reset_nonexistent_resource(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-e", phase=VALID_PHASES["capturedtraffics"])

        result = _invoke_workflow_cli(runner, ["reset", "no-such-thing", "--namespace", reset_ns])
        assert "No resources matching" in result.output

    def test_reset_proxy_requires_include_proxies(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-p", phase=VALID_PHASES["capturedtraffics"])

        result = _invoke_workflow_cli(runner, ["reset", "proxy-p", "--namespace", reset_ns])
        assert result.exit_code != 0
        assert "Proxies are protected by default" in result.output

    def test_reset_kafka_blocked_when_proxy_depends_on_it(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "kafkaclusters", "kafka-a", phase=VALID_PHASES["kafkaclusters"])
        _create_crd_instance(
            reset_ns,
            "capturedtraffics",
            "proxy-a",
            phase=VALID_PHASES["capturedtraffics"],
            depends_on=["kafka-a"],
        )

        result = _invoke_workflow_cli(runner, ["reset", "kafka-a", "--namespace", reset_ns])
        assert result.exit_code != 0
        assert "Cannot delete because protected proxies still depend on this resource:" in result.output
        assert "Capture Proxy: proxy-a" in result.output
        assert "--include-proxies" in result.output

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
        assert "Snapshot Migration: mig-a" in result.output
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

    def test_reset_all_skips_proxies_and_required_kafka(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "kafkaclusters", "kafka-g", phase=VALID_PHASES["kafkaclusters"])
        _create_crd_instance(
            reset_ns,
            "capturedtraffics",
            "proxy-g",
            phase=VALID_PHASES["capturedtraffics"],
            depends_on=["kafka-g"],
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
        assert _get_phase(reset_ns, "capturedtraffics", "proxy-g") == VALID_PHASES["capturedtraffics"]
        assert _get_phase(reset_ns, "kafkaclusters", "kafka-g") == VALID_PHASES["kafkaclusters"]
        assert "Skipping proxies by default" in result.output
        assert "Keeping dependencies required by protected proxies: kafka-g" in result.output

    def test_reset_all_with_include_proxies_deletes_everything(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "kafkaclusters", "kafka-h", phase=VALID_PHASES["kafkaclusters"])
        _create_crd_instance(
            reset_ns,
            "capturedtraffics",
            "proxy-h",
            phase=VALID_PHASES["capturedtraffics"],
            depends_on=["kafka-h"],
        )

        result = _invoke_workflow_cli(runner, ["reset", "--all", "--include-proxies", "--namespace", reset_ns])
        assert result.exit_code == 0
        _assert_deleted(reset_ns, "capturedtraffics", "proxy-h")
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
        _create_crd_instance(reset_ns, "approvalgates", "eval-metadata", phase=VALID_PHASES["approvalgates"])
        _create_crd_instance(reset_ns, "approvalgates", "migrate-metadata", phase=VALID_PHASES["approvalgates"])

        result = _invoke_workflow_cli(runner, ["approve", "eval-*", "--namespace", reset_ns])
        assert result.exit_code == 0
        assert "Approved eval-metadata" in result.output
        assert _get_phase(reset_ns, "approvalgates", "eval-metadata") == "Approved"
        assert _get_phase(reset_ns, "approvalgates", "migrate-metadata") == VALID_PHASES["approvalgates"]

    def test_approve_cli_no_pending(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "approvalgates", "gate-d", phase="Approved")

        result = _invoke_workflow_cli(runner, ["approve", "gate-d", "--namespace", reset_ns])
        assert "No pending" in result.output

    def test_approve_cli_no_match(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "approvalgates", "gate-e", phase=VALID_PHASES["approvalgates"])

        result = _invoke_workflow_cli(runner, ["approve", "nonexistent-*", "--namespace", reset_ns])
        assert "No pending gates match" in result.output
        assert "gate-e" in result.output


@pytest.mark.slow
class TestAutocompleteIntegration:
    def test_autocomplete_returns_live_resources(self, reset_ns):
        cache_file = (
            Path(tempfile.gettempdir()) / "workflow_completions" / f"reset_resources_{reset_ns}.json"
        )
        cache_file.unlink(missing_ok=True)

        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-ac", phase=VALID_PHASES["capturedtraffics"])
        _create_crd_instance(reset_ns, "snapshotmigrations", "snap-ac", phase=VALID_PHASES["snapshotmigrations"])

        class FakeCtx:
            params = {"namespace": reset_ns}

        completions = _get_resource_completions(FakeCtx(), None, "")
        assert "proxy-ac" in completions
        assert "snap-ac" in completions

    def test_autocomplete_filters_by_prefix(self, reset_ns):
        cache_file = (
            Path(tempfile.gettempdir()) / "workflow_completions" / f"reset_resources_{reset_ns}.json"
        )
        cache_file.unlink(missing_ok=True)

        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-x", phase=VALID_PHASES["capturedtraffics"])
        _create_crd_instance(reset_ns, "capturedtraffics", "other-y", phase=VALID_PHASES["capturedtraffics"])

        class FakeCtx:
            params = {"namespace": reset_ns}

        completions = _get_resource_completions(FakeCtx(), None, "proxy")
        assert "proxy-x" in completions
        assert "other-y" not in completions
