"""
Integration tests for workflow reset and approve commands using real Kubernetes.

Uses the same k3s/kind cluster infrastructure pattern as test_workflow_integration.py.
Creates migration CRDs, then tests the full reset and approve CLI flows.

## Running Tests Locally

### With existing cluster (fastest):
    minikube start  # or: kind create cluster
    pytest tests/workflow-tests/test_reset_integration.py -m slow

### Without existing cluster (automatic fallback):
    pytest tests/workflow-tests/test_reset_integration.py -m slow
    # Tests will automatically create a k3s container
"""

import logging
import os
import tempfile
import time
import uuid

import pytest
from click.testing import CliRunner
from kubernetes import client, config
from kubernetes.client.rest import ApiException
from testcontainers.k3s import K3SContainer

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands.reset import (
    _delete_crd,
)
from console_link.workflow.commands.crd_utils import CRD_GROUP, CRD_VERSION, list_migration_resources
from console_link.workflow.commands.approve import approve_gate

logger = logging.getLogger(__name__)


# ============================================================================
# CRD Definitions (expanded from Helm template)
# ============================================================================

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


# ============================================================================
# Fixtures
# ============================================================================

def _detect_existing_cluster():
    """Check if a k8s cluster is already accessible."""
    try:
        config.load_kube_config()
        client.CoreV1Api().list_namespace(timeout_seconds=5)
        return True
    except Exception:
        return False


@pytest.fixture(scope="session")
def k8s_cluster():
    """Provide a Kubernetes cluster — reuse existing or start k3s."""
    if _detect_existing_cluster():
        logger.info("Using existing Kubernetes cluster")
        yield
    else:
        logger.info("Starting k3s container...")
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
    """Install migration CRDs into the cluster. Session-scoped — installed once."""
    ext = client.ApiextensionsV1Api()
    for crd in CRD_MANIFESTS:
        try:
            ext.create_custom_resource_definition(body=crd)
            logger.info(f"Created CRD: {crd['metadata']['name']}")
        except ApiException as e:
            if e.status == 409:
                logger.info(f"CRD already exists: {crd['metadata']['name']}")
            else:
                raise
    # Wait for CRDs to be established
    for crd in CRD_MANIFESTS:
        _wait_for_crd(ext, crd["metadata"]["name"])
    yield
    # No cleanup — CRDs persist for the session


@pytest.fixture
def reset_ns(k8s_cluster, migration_crds):
    """Create a fresh namespace for each test, clean up after."""
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


# ============================================================================
# Helpers
# ============================================================================

def _wait_for_crd(ext_api, crd_name, timeout=30):
    """Wait for a CRD to be established."""
    start = time.time()
    while time.time() - start < timeout:
        crd = ext_api.read_custom_resource_definition(crd_name)
        for cond in (crd.status.conditions or []):
            if cond.type == "Established" and cond.status == "True":
                return
        time.sleep(1)
    raise TimeoutError(f"CRD {crd_name} not established in {timeout}s")


def _create_crd_instance(namespace, plural, name, phase=None):
    """Create a migration CRD instance, optionally with a status phase."""
    custom = client.CustomObjectsApi()
    body = {
        "apiVersion": f"{CRD_GROUP}/{CRD_VERSION}",
        "kind": plural.rstrip("s").capitalize() if plural != "capturedtraffics" else "CapturedTraffic",
        "metadata": {"name": name, "namespace": namespace},
        "spec": {},
    }
    # Map plural to kind properly
    kind_map = {
        "capturedtraffics": "CapturedTraffic",
        "datasnapshots": "DataSnapshot",
        "kafkaclusters": "KafkaCluster",
        "snapshotmigrations": "SnapshotMigration",
        "trafficreplays": "TrafficReplay",
        "approvalgates": "ApprovalGate",
    }
    body["kind"] = kind_map[plural]

    custom.create_namespaced_custom_object(
        group=CRD_GROUP, version=CRD_VERSION,
        namespace=namespace, plural=plural, body=body,
    )
    if phase:
        custom.patch_namespaced_custom_object_status(
            group=CRD_GROUP, version=CRD_VERSION,
            namespace=namespace, plural=plural, name=name,
            body={"status": {"phase": phase}},
        )


def _get_phase(namespace, plural, name):
    """Read the current phase of a CRD instance."""
    custom = client.CustomObjectsApi()
    obj = custom.get_namespaced_custom_object(
        group=CRD_GROUP, version=CRD_VERSION,
        namespace=namespace, plural=plural, name=name,
    )
    return obj.get("status", {}).get("phase", "Unknown")


# ============================================================================
# Tests: list_migration_resources
# ============================================================================

@pytest.mark.slow
class TestListMigrationCrdsIntegration:

    def test_lists_crds_across_types(self, reset_ns):
        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-a", phase="Ready")
        _create_crd_instance(reset_ns, "snapshotmigrations", "snap-a", phase="Running")
        _create_crd_instance(reset_ns, "trafficreplays", "replay-a", phase="Ready")

        results = list_migration_resources(reset_ns)
        names = {n for _, n, _, _ in results}
        assert names == {"proxy-a", "snap-a", "replay-a"}

    def test_empty_namespace(self, reset_ns):
        assert list_migration_resources(reset_ns) == []

    def test_shows_unknown_when_no_status(self, reset_ns):
        _create_crd_instance(reset_ns, "capturedtraffics", "no-status")
        results = list_migration_resources(reset_ns)
        assert results == [("capturedtraffics", "no-status", "Unknown", [])]


# ============================================================================
# Tests: _patch_teardown
# ============================================================================

@pytest.mark.slow
class TestDeleteCrdIntegration:

    def test_deletes_crd(self, reset_ns):
        _create_crd_instance(reset_ns, "snapshotmigrations", "snap-b", phase="Ready")
        assert _delete_crd(reset_ns, "snapshotmigrations", "snap-b") is True
        # Foreground deletion may take time — poll until gone
        custom = client.CustomObjectsApi()
        deadline = time.time() + 30
        while time.time() < deadline:
            try:
                custom.get_namespaced_custom_object(
                    group=CRD_GROUP, version=CRD_VERSION,
                    namespace=reset_ns, plural="snapshotmigrations", name="snap-b"
                )
                time.sleep(1)
            except ApiException as e:
                assert e.status == 404
                break
        else:
            pytest.fail("CRD snap-b was not deleted within 30s")

    def test_delete_nonexistent_returns_true(self, reset_ns):
        assert _delete_crd(reset_ns, "snapshotmigrations", "does-not-exist") is True


# ============================================================================
# Tests: workflow reset CLI (list mode)
# ============================================================================

@pytest.mark.slow
class TestResetListIntegration:

    def test_list_shows_friendly_names(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "capturedtraffics", "my-proxy", phase="Ready")
        _create_crd_instance(reset_ns, "snapshotmigrations", "my-snap", phase="Running")

        result = runner.invoke(workflow_cli, ["reset", "--namespace", reset_ns])
        assert result.exit_code == 0
        assert "Capture Proxy" in result.output
        assert "my-proxy" in result.output
        assert "Snapshot Migration" in result.output
        assert "my-snap" in result.output
        assert "workflow reset --all" in result.output

    def test_list_empty_namespace(self, runner, reset_ns):
        result = runner.invoke(workflow_cli, ["reset", "--namespace", reset_ns])
        assert "No migration resources" in result.output


# ============================================================================
# Tests: workflow reset CLI (single resource)
# ============================================================================

@pytest.mark.slow
class TestResetSingleIntegration:

    def test_reset_specific_resource(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-c", phase="Ready")
        _create_crd_instance(reset_ns, "snapshotmigrations", "snap-c", phase="Ready")

        result = runner.invoke(workflow_cli, ["reset", "proxy-c", "--include-proxies", "--namespace", reset_ns])
        assert result.exit_code == 0
        assert "✓ Deleted proxy-c" in result.output

        # proxy-c should be gone (404), snap-c should be untouched
        import time
        time.sleep(2)
        custom = client.CustomObjectsApi()
        with pytest.raises(ApiException) as exc_info:
            custom.get_namespaced_custom_object(
                group=CRD_GROUP, version=CRD_VERSION,
                namespace=reset_ns, plural="capturedtraffics", name="proxy-c"
            )
        assert exc_info.value.status == 404
        assert _get_phase(reset_ns, "snapshotmigrations", "snap-c") == "Ready"

    def test_reset_nonexistent_resource(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-e", phase="Ready")

        result = runner.invoke(workflow_cli, ["reset", "no-such-thing", "--namespace", reset_ns])
        assert "No resources matching" in result.output


# ============================================================================
# Tests: workflow reset --all
# ============================================================================

@pytest.mark.slow
class TestResetAllIntegration:

    def test_reset_all_deletes_all_crds(self, runner, reset_ns):
        """--all deletes all CRDs with foreground cascading."""
        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-f", phase="Ready")
        _create_crd_instance(reset_ns, "snapshotmigrations", "snap-f", phase="Ready")
        _create_crd_instance(reset_ns, "trafficreplays", "replay-f", phase="Ready")

        result = runner.invoke(workflow_cli, [
            "reset", "--all", "--include-proxies", "--namespace", reset_ns,
        ])

        # All CRDs should be deleted (404)
        custom = client.CustomObjectsApi()
        targets = [("capturedtraffics", "proxy-f"), ("snapshotmigrations", "snap-f"), ("trafficreplays", "replay-f")]
        for plural, name in targets:
            with pytest.raises(ApiException) as exc_info:
                custom.get_namespaced_custom_object(
                    group=CRD_GROUP, version=CRD_VERSION,
                    namespace=reset_ns, plural=plural, name=name,
                )
            assert exc_info.value.status == 404
        assert "Deleted" in result.output

    def test_reset_all_skips_already_teardown(self, runner, reset_ns):
        """--all should still proceed even when all CRDs are already torn down."""
        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-g", phase="Teardown")

        result = runner.invoke(workflow_cli, [
            "reset", "--all", "--namespace", reset_ns,
        ])
        # Should not fail — proceeds to workflow cleanup
        assert result.exit_code == 0


# ============================================================================
# Tests: approval gates
# ============================================================================

@pytest.mark.slow
class TestApproveIntegration:

    def test_list_approval_gates(self, reset_ns):
        _create_crd_instance(reset_ns, "approvalgates", "gate-a", phase="Pending")
        _create_crd_instance(reset_ns, "approvalgates", "gate-b", phase="Approved")

        gates = list_migration_resources(reset_ns, ['approvalgates'])
        gate_dict = {n: p for _, n, p, _ in gates}
        assert gate_dict["gate-a"] == "Pending"
        assert gate_dict["gate-b"] == "Approved"

    def test_approve_gate(self, reset_ns):
        _create_crd_instance(reset_ns, "approvalgates", "gate-c", phase="Pending")

        assert approve_gate(reset_ns, "gate-c") is True
        assert _get_phase(reset_ns, "approvalgates", "gate-c") == "Approved"

    def test_approve_cli_with_glob(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "approvalgates", "eval-metadata", phase="Pending")
        _create_crd_instance(reset_ns, "approvalgates", "migrate-metadata", phase="Pending")

        result = runner.invoke(workflow_cli, [
            "approve", "eval-*", "--namespace", reset_ns,
        ])
        assert result.exit_code == 0
        assert "Approved eval-metadata" in result.output

        # Only the matched gate should be approved
        assert _get_phase(reset_ns, "approvalgates", "eval-metadata") == "Approved"
        assert _get_phase(reset_ns, "approvalgates", "migrate-metadata") == "Pending"

    def test_approve_cli_no_pending(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "approvalgates", "gate-d", phase="Approved")

        result = runner.invoke(workflow_cli, [
            "approve", "gate-d", "--namespace", reset_ns,
        ])
        assert "No pending" in result.output

    def test_approve_cli_no_match(self, runner, reset_ns):
        _create_crd_instance(reset_ns, "approvalgates", "gate-e", phase="Pending")

        result = runner.invoke(workflow_cli, [
            "approve", "nonexistent-*", "--namespace", reset_ns,
        ])
        assert "No pending gates match" in result.output
        assert "gate-e" in result.output  # should show available gates


# ============================================================================
# Tests: autocomplete
# ============================================================================

@pytest.mark.slow
class TestAutocompleteIntegration:

    def test_autocomplete_returns_non_teardown_resources(self, reset_ns):
        from console_link.workflow.commands.reset import _get_resource_completions
        from pathlib import Path
        import tempfile

        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-ac", phase="Ready")
        _create_crd_instance(reset_ns, "snapshotmigrations", "snap-ac", phase="Teardown")

        cache_file = Path(tempfile.gettempdir()) / "workflow_completions" / f"reset_resources_{reset_ns}.json"
        cache_file.unlink(missing_ok=True)

        class FakeCtx:
            params = {'namespace': reset_ns}

        completions = _get_resource_completions(FakeCtx(), None, "")
        assert "proxy-ac" in completions
        assert "snap-ac" not in completions  # already Teardown

    def test_autocomplete_filters_by_prefix(self, reset_ns):
        from console_link.workflow.commands.reset import _get_resource_completions
        from pathlib import Path
        import tempfile

        _create_crd_instance(reset_ns, "capturedtraffics", "proxy-x", phase="Ready")
        _create_crd_instance(reset_ns, "capturedtraffics", "other-y", phase="Ready")

        cache_file = Path(tempfile.gettempdir()) / "workflow_completions" / f"reset_resources_{reset_ns}.json"
        cache_file.unlink(missing_ok=True)

        class FakeCtx:
            params = {'namespace': reset_ns}

        completions = _get_resource_completions(FakeCtx(), None, "proxy")
        assert "proxy-x" in completions
        assert "other-y" not in completions


# ============================================================================
# Tests: _patch_targets failure
# ============================================================================

@pytest.mark.slow
class TestPatchTargetsFailureIntegration:

    def test_reset_nonexistent_crd_instance_fails(self, runner, reset_ns):
        """Reset a resource name that doesn't exist — should report no matching resources."""
        # Use a name that was never created, so there's no race with k8s deletion
        result = runner.invoke(workflow_cli, ["reset", "does-not-exist", "--namespace", reset_ns])
        assert result.exit_code == 0
        assert "No resources matching" in result.output


# ============================================================================
# Tests: workflow submit error paths
# ============================================================================

@pytest.mark.slow
class TestSubmitErrorPathsIntegration:

    def test_submit_no_config(self, runner, reset_ns):
        """Submit with no saved config shows error."""
        result = runner.invoke(workflow_cli, ["submit", "--namespace", reset_ns])
        assert result.exit_code != 0
        assert "No workflow configuration" in result.output

    def test_submit_script_not_found(self, runner, reset_ns, monkeypatch, tmp_path):
        """Submit with missing script shows CONFIG_PROCESSOR_DIR hint."""
        # Point to a dir that exists but has no script
        monkeypatch.setenv("CONFIG_PROCESSOR_DIR", str(tmp_path))

        # Need a config so we get past the config check
        from console_link.workflow.models.workflow_config_store import WorkflowConfigStore
        store = WorkflowConfigStore(namespace=reset_ns)
        from console_link.workflow.models.config import WorkflowConfig
        store.save_config(WorkflowConfig({"targets": {"t": {"endpoint": "http://x:9200"}}}), "default")

        result = runner.invoke(workflow_cli, ["submit", "--namespace", reset_ns])
        assert result.exit_code != 0
        assert "CONFIG_PROCESSOR_DIR" in result.output

    def test_submit_script_fails_with_already_exists(self, runner, reset_ns, monkeypatch, tmp_path):
        """Submit with a script that fails with AlreadyExists is treated as a script failure."""
        script = tmp_path / "createMigrationWorkflowFromUserConfiguration.sh"
        script.write_text("#!/bin/bash\necho 'AlreadyExists' >&2; exit 1\n")
        script.chmod(0o755)
        monkeypatch.setenv("CONFIG_PROCESSOR_DIR", str(tmp_path))

        from console_link.workflow.models.workflow_config_store import WorkflowConfigStore
        store = WorkflowConfigStore(namespace=reset_ns)
        from console_link.workflow.models.config import WorkflowConfig
        store.save_config(WorkflowConfig({"targets": {"t": {"endpoint": "http://x:9200"}}}), "default")

        result = runner.invoke(workflow_cli, ["submit", "--namespace", reset_ns])
        assert result.exit_code != 0
        assert "failed" in result.output.lower()

    def test_submit_script_fails_generic(self, runner, reset_ns, monkeypatch, tmp_path):
        """Submit with a script that fails generically shows exit code."""
        script = tmp_path / "createMigrationWorkflowFromUserConfiguration.sh"
        script.write_text("#!/bin/bash\necho 'something broke' >&2; exit 1\n")
        script.chmod(0o755)
        monkeypatch.setenv("CONFIG_PROCESSOR_DIR", str(tmp_path))

        from console_link.workflow.models.workflow_config_store import WorkflowConfigStore
        store = WorkflowConfigStore(namespace=reset_ns)
        from console_link.workflow.models.config import WorkflowConfig
        store.save_config(WorkflowConfig({"targets": {"t": {"endpoint": "http://x:9200"}}}), "default")

        result = runner.invoke(workflow_cli, ["submit", "--namespace", reset_ns])
        assert result.exit_code != 0
        assert "Script failed with exit code 1" in result.output


# ============================================================================
# Tests: workflow submit AlreadyExists hint
# ============================================================================

@pytest.mark.slow
class TestSubmitAlreadyExistsIntegration:
    """Covered by TestSubmitErrorPathsIntegration above."""
    pass
