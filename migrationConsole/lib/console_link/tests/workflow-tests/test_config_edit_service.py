import base64
import json
import subprocess
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest

from console_link.workflow.models.config import WorkflowConfig
from console_link.workflow.services.config_edit_service import ConfigEditService


class FakeStore:
    def __init__(self, config=None):
        self.config = config
        self.saved = []

    def load_config(self, session_name="default"):
        return self.config

    def save_config(self, config, session_name="default"):
        self.saved.append((session_name, config.raw_yaml))
        return "saved"


def encoded(value):
    return base64.b64encode(value.encode("utf-8")).decode("utf-8")


def fake_k8s_item(name, **kwargs):
    return SimpleNamespace(metadata=SimpleNamespace(name=name), **kwargs)


def test_load_pending_resolved_config_uses_config_processor():
    runner = MagicMock()
    runner.run_config_processor_node_script.return_value = '{"resources":[]}'
    service = ConfigEditService(
        namespace="test",
        store=FakeStore(WorkflowConfig(raw_yaml="sourceClusters: {}")),
        runner=runner,
    )

    result = service.load_pending_resolved_config("migration")

    assert result == {"resources": []}
    args = runner.run_config_processor_node_script.call_args.args
    assert args[0] == "resolveMigrationResources"
    assert args[1] == "--user-config"
    assert args[3:] == ("--workflow-name", "migration")


@patch("console_link.workflow.services.config_edit_service.list_resources_full", return_value={"migrationruns": []})
def test_load_resource_config_snapshots_uses_loose_pending_projection(_list_resources):
    runner = MagicMock()
    runner.run_config_processor_node_script.return_value = """
    {
      "resources": [],
      "consoleResources": {"sources": [], "targets": [], "kafkas": [], "consumerGroups": []}
    }
    """
    service = ConfigEditService(
        namespace="test",
        store=FakeStore(WorkflowConfig(raw_yaml="sourceClusters: {}")),
        runner=runner,
    )

    result = service.load_resource_config_snapshots("migration")

    assert result["pending"] is not None
    assert result["pending_console"] == {"sources": [], "targets": [], "kafkas": [], "consumerGroups": []}
    args = runner.run_config_processor_node_script.call_args.args
    assert args[:4] == ("resolveMigrationResources", "--user-config", args[2], "--workflow-name")
    assert args[4:] == ("migration", "--validation-mode", "loose")


def test_list_external_resources_uses_tls_content_validation_hints():
    service = ConfigEditService(namespace="test", store=FakeStore())
    core = MagicMock()
    core.list_namespaced_secret.return_value = SimpleNamespace(items=[
        fake_k8s_item(
            "valid-tls",
            type="kubernetes.io/tls",
            data={
                "tls.crt": encoded("-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----\n"),
                "tls.key": encoded("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n"),
            },
        ),
        fake_k8s_item(
            "bad-tls",
            type="kubernetes.io/tls",
            data={
                "tls.crt": encoded("not a cert"),
                "tls.key": encoded("not a key"),
            },
        ),
    ])
    external_ref = {
        "kind": "secret",
        "k8s": {
            "resource": "Secret",
            "acceptedSecretTypes": ["kubernetes.io/tls", "Opaque"],
            "requiredKeys": ["tls.crt", "tls.key"],
            "contentValidationIds": ["tls-certificate-key-pair"],
        },
    }

    with patch.object(service, "_core_v1", return_value=core):
        rows = service.list_external_resources(external_ref)

    by_name = {row["name"]: row for row in rows}
    assert by_name["valid-tls"]["status"] == "matching"
    assert by_name["bad-tls"]["status"] == "warn"
    assert "tls.crt is not a PEM certificate" in by_name["bad-tls"]["message"]
    assert "tls.key is not a PEM private key" in by_name["bad-tls"]["message"]


def test_list_external_resources_uses_standalone_pem_certificate_chain_validation():
    service = ConfigEditService(namespace="test", store=FakeStore())
    core = MagicMock()
    core.list_namespaced_secret.return_value = SimpleNamespace(items=[
        fake_k8s_item(
            "valid-ca",
            type="Opaque",
            data={"ca.crt": encoded("-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----\n")},
        ),
        fake_k8s_item(
            "bad-ca",
            type="Opaque",
            data={"ca.crt": encoded("not a cert")},
        ),
    ])
    external_ref = {
        "kind": "kubernetesResource",
        "k8s": {
            "resourceTypes": [{"group": "", "version": "v1", "kind": "Secret", "namespaced": True}],
            "match": {
                "requiredKeys": ["ca.crt"],
                "contentValidationIds": ["pem-certificate-chain"],
            },
        },
    }

    with patch.object(service, "_core_v1", return_value=core):
        rows = service.list_external_resources(external_ref)

    by_name = {row["name"]: row for row in rows}
    assert by_name["valid-ca"]["status"] == "matching"
    assert by_name["bad-ca"]["status"] == "warn"
    assert "ca.crt is not a PEM certificate" in by_name["bad-ca"]["message"]


def test_list_external_resources_uses_config_map_content_validation_hints():
    service = ConfigEditService(namespace="test", store=FakeStore())
    core = MagicMock()
    core.list_namespaced_config_map.return_value = SimpleNamespace(items=[
        fake_k8s_item(
            "valid-log4j",
            data={"log4j2.properties": "status = warn\nrootLogger.level = info\n"},
        ),
        fake_k8s_item(
            "bad-log4j",
            data={"log4j2.properties": "not properties"},
        ),
        fake_k8s_item(
            "missing-log4j",
            data={"application.properties": "x = y"},
        ),
    ])
    external_ref = {
        "kind": "configMap",
        "k8s": {
            "resource": "ConfigMap",
            "requiredKeys": ["log4j2.properties"],
            "contentValidationIds": ["log4j-properties"],
        },
    }

    with patch.object(service, "_core_v1", return_value=core):
        rows = service.list_external_resources(external_ref)

    by_name = {row["name"]: row for row in rows}
    assert by_name["valid-log4j"]["status"] == "matching"
    assert by_name["bad-log4j"]["status"] == "warn"
    assert "does not look like Log4j2 properties" in by_name["bad-log4j"]["message"]
    assert by_name["missing-log4j"]["status"] == "warn"
    assert by_name["missing-log4j"]["message"] == "missing log4j2.properties"


def test_list_external_resources_uses_generic_kubernetes_resource_types_for_issuers():
    service = ConfigEditService(namespace="test", store=FakeStore())
    custom = MagicMock()
    custom.list_cluster_custom_object.return_value = {
        "items": [
            {
                "metadata": {"name": "migrations-ca"},
                "status": {"conditions": [{"type": "Ready", "status": "True"}]},
            },
            {
                "metadata": {"name": "not-ready"},
                "status": {"conditions": [{"type": "Ready", "status": "False", "message": "issuer is not ready"}]},
            },
        ]
    }
    external_ref = {
        "kind": "kubernetesResource",
        "purpose": "cert-manager-issuer",
        "selection": {"target": "objectRef"},
        "k8s": {
            "resourceTypes": [
                {"group": "cert-manager.io", "version": "v1", "kind": "ClusterIssuer", "namespaced": False},
            ],
        },
    }

    with patch.object(service, "_custom_objects", return_value=custom):
        rows = service.list_external_resources(
            external_ref,
            {"name": "migrations-ca", "kind": "ClusterIssuer", "group": "cert-manager.io"},
        )

    custom.list_cluster_custom_object.assert_called_once_with("cert-manager.io", "v1", "clusterissuers")
    by_name = {row["name"]: row for row in rows}
    assert by_name["migrations-ca"]["status"] == "matching"
    assert by_name["migrations-ca"]["current"] is True
    assert by_name["migrations-ca"]["kind"] == "ClusterIssuer"
    assert by_name["migrations-ca"]["group"] == "cert-manager.io"
    assert by_name["not-ready"]["status"] == "warn"
    assert by_name["not-ready"]["message"] == "issuer is not ready"


@patch(
    "console_link.workflow.services.config_edit_service.list_resources_full",
    return_value={
        "migrationruns": [
            {
                "metadata": {"name": "other-run-3"},
                "spec": {
                    "workflowName": "other",
                    "runNumber": 3,
                    "resolvedConfig": {"marker": "wrong-workflow"},
                },
            },
            {
                "metadata": {"name": "migration-run-2"},
                "spec": {
                    "workflowName": "migration",
                    "runNumber": 2,
                    "resolvedConfig": {"marker": "selected"},
                },
            },
        ]
    },
)
def test_load_latest_submitted_resolved_config_filters_by_workflow(_list_resources):
    service = ConfigEditService(namespace="test", store=FakeStore())

    assert service.load_latest_submitted_resolved_config("migration") == {"marker": "selected"}
    assert service.load_latest_submitted_resolved_config("missing") is None


def test_apply_operation_reports_config_processor_stderr():
    runner = MagicMock()
    runner.run_config_processor_node_script.side_effect = subprocess.CalledProcessError(
        2,
        ["node-22", "/root/configProcessor/index.js"],
        output="",
        stderr="Usage: editConfig apply --pending-config <file|-> --operation <json-file|->",
    )
    service = ConfigEditService(namespace="test", store=FakeStore(), runner=runner)

    with pytest.raises(RuntimeError) as error:
        service.apply_operation(
            "sourceClusters: {}\n",
            {"op": "set", "path": ["snapshotMigrationConfigs", "0", "fromSource"], "value": "aux-source"},
        )

    assert "config processor failed with exit code 2" in str(error.value)
    assert "Usage: editConfig apply --pending-config" in str(error.value)


@patch("console_link.workflow.services.config_edit_service.load_k8s_config")
@patch("console_link.workflow.services.config_edit_service.workflow_exists")
def test_submit_saved_config_validates_before_touching_workflow(_workflow_exists, _load_k8s):
    runner = MagicMock()
    runner.run_config_processor_node_script.return_value = json.dumps({
        "validation": {
            "valid": False,
            "errors": [
                "traffic.replayers.sourceTarget.replayerConfig.requestTransforms.0: "
                "Exactly one of entryPoint or transformName is required",
            ],
            "diagnostics": [
                {
                    "severity": "required",
                    "path": [
                        "traffic", "replayers", "sourceTarget", "replayerConfig",
                        "requestTransforms", "0",
                    ],
                    "message": "Exactly one of entryPoint or transformName is required",
                },
            ],
        }
    })
    service = ConfigEditService(
        namespace="test",
        store=FakeStore(WorkflowConfig(raw_yaml=(
            "traffic:\n"
            "  replayers:\n"
            "    sourceTarget:\n"
            "      replayerConfig:\n"
            "        requestTransforms:\n"
            "        - {}\n"
        ))),
        runner=runner,
    )

    with pytest.raises(ValueError) as error:
        service.submit_saved_config("migration", unique_run_nonce="123")

    message = str(error.value)
    assert "Workflow configuration is not valid" in message
    assert "traffic.replayers.sourceTarget.replayerConfig.requestTransforms.0" in message
    assert "Exactly one of entryPoint or transformName is required" in message
    runner.submit_workflow.assert_not_called()
    _load_k8s.assert_not_called()
    _workflow_exists.assert_not_called()


@patch("console_link.workflow.services.config_edit_service.wait_until_workflow_deleted", return_value=True)
@patch("console_link.workflow.services.config_edit_service.delete_workflow", return_value=True)
@patch("console_link.workflow.services.config_edit_service.stop_workflow", return_value=True)
@patch("console_link.workflow.services.config_edit_service.workflow_exists", return_value=True)
@patch("console_link.workflow.services.config_edit_service.verify_configured_secrets_exist")
@patch("console_link.workflow.services.config_edit_service.get_credentials_secret_store_for_namespace")
@patch("console_link.workflow.services.config_edit_service.load_k8s_config")
def test_submit_saved_config_replaces_existing_workflow(
    _load_k8s,
    _secret_store,
    _verify_secrets,
    _workflow_exists,
    _stop_workflow,
    _delete_workflow,
    _wait_deleted,
):
    runner = MagicMock()
    runner.run_config_processor_node_script.return_value = json.dumps({
        "validation": {"valid": True, "errors": []},
    })
    runner.submit_workflow.return_value = {"workflow_name": "migration"}
    service = ConfigEditService(
        namespace="test",
        store=FakeStore(WorkflowConfig(raw_yaml="sourceClusters: {}")),
        runner=runner,
    )

    result = service.submit_saved_config("migration", unique_run_nonce="123")

    assert result == {"workflow_name": "migration"}
    runner.submit_workflow.assert_called_once_with(
        "sourceClusters: {}",
        ["--workflow-name", "migration", "--unique-run-nonce", "123"],
    )
    _stop_workflow.assert_called_once_with("test", "migration")
    _delete_workflow.assert_called_once_with("test", "migration")
