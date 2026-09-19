import base64
import json
import subprocess
from types import SimpleNamespace
from unittest.mock import MagicMock, patch

import pytest
from kubernetes.client.rest import ApiException

from console_link.workflow.models.config import WorkflowConfig
from console_link.workflow.services.config_edit_service import (
    AdmissionPreflightBlocked,
    ConfigEditService,
)


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


def basic_auth_secret_external_ref():
    return {
        "kind": "secret",
        "displayName": "HTTP Basic Auth Secret",
        "k8s": {
            "resource": "Secret",
            "requiredKeys": ["username", "password"],
            "acceptedSecretTypes": ["kubernetes.io/basic-auth", "Opaque"],
            "contentValidationIds": ["non-empty-keys"],
        },
        "create": {
            "apply": {"nameField": "secretName"},
            "fields": [
                {"name": "secretName", "label": "Secret name", "required": True},
                {"name": "username", "label": "Username", "required": True},
                {"name": "password", "label": "Password", "required": True, "sensitive": True},
            ],
            "output": {
                "kind": "Secret",
                "type": "kubernetes.io/basic-auth",
                "stringData": {
                    "username": {"fromField": "username"},
                    "password": {"fromField": "password"},
                },
            },
        },
    }


def config_map_external_ref():
    return {
        "kind": "configMap",
        "displayName": "Log4j2 ConfigMap",
        "k8s": {
            "resource": "ConfigMap",
            "requiredKeys": ["log4j2.properties"],
            "contentValidationIds": ["log4j-properties"],
        },
    }


def edit_state_with_external_ref(value, external_ref):
    path = ["targetClusters", "target", "authConfig", "basic", "secretName"]
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:targetClusters",
                "path": ["targetClusters"],
                "label": "Targets",
                "valueKind": "record",
                "status": "ok",
                "statusCounts": {},
                "children": [
                    {
                        "id": "edit:targetClusters.target",
                        "path": ["targetClusters", "target"],
                        "label": "target",
                        "valueKind": "object",
                        "status": "ok",
                        "statusCounts": {},
                        "children": [
                            {
                                "id": "edit:targetClusters.target.authConfig.basic.secretName",
                                "path": path,
                                "label": f"secretName: {value}",
                                "value": value,
                                "valueKind": "scalar",
                                "description": "Name of a Kubernetes Secret containing credentials.",
                                "status": "ok",
                                "statusCounts": {},
                                "externalRef": external_ref,
                            },
                        ],
                    },
                ],
            },
        ],
        "validation": {"valid": True, "errors": []},
    }


def edit_state_with_replayer():
    return {
        "formatVersion": 1,
        "provenance": {"source": "pending-yaml", "lossy": False, "warnings": []},
        "nodes": [
            {
                "id": "edit:traffic",
                "path": ["traffic"],
                "label": "Live Traffic Migration",
                "valueKind": "object",
                "status": "ok",
                "statusCounts": {},
                "children": [
                    {
                        "id": "edit:traffic.replayers",
                        "path": ["traffic", "replayers"],
                        "label": "Replay",
                        "valueKind": "record",
                        "status": "ok",
                        "statusCounts": {},
                        "children": [
                            {
                                "id": "edit:traffic.replayers.sourceTarget",
                                "path": ["traffic", "replayers", "sourceTarget"],
                                "label": "sourceTarget",
                                "valueKind": "object",
                                "status": "ok",
                                "statusCounts": {},
                                "children": [
                                    {
                                        "id": "edit:traffic.replayers.sourceTarget.replayerConfig",
                                        "path": ["traffic", "replayers", "sourceTarget", "replayerConfig"],
                                        "label": "replayerConfig",
                                        "valueKind": "object",
                                        "status": "ok",
                                        "statusCounts": {},
                                        "children": [],
                                    },
                                ],
                            },
                        ],
                    },
                ],
            },
        ],
        "validation": {"valid": True, "errors": []},
    }


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


def test_resolve_console_resources_uses_strict_user_config():
    runner = MagicMock()
    runner.run_config_processor_node_script.return_value = (
        '{"sources":[],"targets":[],"kafkas":[],"consumerGroups":[]}'
    )
    service = ConfigEditService(namespace="test", runner=runner)

    result = service.resolve_console_resources(
        "sourceClusters: {}\ntargetClusters: {}\n"
    )

    assert result["sources"] == []
    args = runner.run_config_processor_node_script.call_args.args
    assert args[:2] == ("resolveConsoleResources", "--user-config")
    assert args[2].endswith(".json")


def test_save_validation_uses_schema_projection_without_external_checks():
    service = ConfigEditService(namespace="test", store=FakeStore())
    service._run_edit_state = MagicMock(return_value={
        "validation": {"valid": True},
    })

    service.validate_raw_config_for_save("sourceClusters: {}\n")

    service._run_edit_state.assert_called_once_with(
        "sourceClusters: {}\n",
        validate_external_refs=False,
    )


def test_save_validation_error_names_save_instead_of_submit():
    service = ConfigEditService(namespace="test", store=FakeStore())
    service._run_edit_state = MagicMock(return_value={
        "validation": {
            "valid": False,
            "diagnostics": [{"message": "Endpoint is required", "path": []}],
        },
    })

    with pytest.raises(
        ValueError,
    ) as error:
        service.validate_raw_config_for_save("sourceClusters: {}\n")

    assert "before save" in str(error.value)
    assert "Endpoint is required" in str(error.value)


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


@patch("console_link.workflow.services.config_edit_service.list_resources_full")
def test_load_resource_config_snapshots_keeps_pending_when_historical_projection_fails(
    list_resources,
):
    list_resources.return_value = {
        "migrationruns": [{
            "spec": {
                "workflowName": "migration",
                "resolvedConfig": {"workflowConfig": {"historical": "invalid"}},
            },
        }],
    }
    pending_console = {
        "sources": [{"refName": "source"}],
        "targets": [{"refName": "target"}],
        "kafkas": [],
        "consumerGroups": [],
    }
    runner = MagicMock()
    runner.run_config_processor_node_script.side_effect = [
        json.dumps({
            "resources": [],
            "consoleResources": pending_console,
        }),
        subprocess.CalledProcessError(
            1,
            ["node", "resolveConsoleResources"],
            stderr="historical config no longer passes the current schema",
        ),
    ]
    service = ConfigEditService(
        namespace="test",
        store=FakeStore(WorkflowConfig(raw_yaml="sourceClusters: {}")),
        runner=runner,
    )

    result = service.load_resource_config_snapshots("migration")

    assert result["submitted_console"] is None
    assert result["pending_console"] == pending_console
    assert runner.run_config_processor_node_script.call_count == 2


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


def test_list_external_resources_marks_missing_current_value_as_error():
    service = ConfigEditService(namespace="ma", store=FakeStore())
    core = MagicMock()
    core.list_namespaced_secret.return_value = SimpleNamespace(items=[])

    with patch.object(service, "_core_v1", return_value=core):
        rows = service.list_external_resources(basic_auth_secret_external_ref(), "a")

    assert rows == [{
        "name": "a",
        "kind": "Secret",
        "group": "",
        "version": "v1",
        "keys": [],
        "status": "error",
        "message": "ERROR: current YAML value was not found in Kubernetes",
        "current": True,
    }]


def test_list_external_resources_accepts_unmanaged_matching_secret():
    service = ConfigEditService(namespace="ma", store=FakeStore())
    core = MagicMock()
    core.list_namespaced_secret.return_value = SimpleNamespace(items=[
        fake_k8s_item(
            "a",
            type="kubernetes.io/basic-auth",
            data={"username": encoded("admin"), "password": encoded("pw")},
        )
    ])

    with patch.object(service, "_core_v1", return_value=core):
        rows = service.list_external_resources(basic_auth_secret_external_ref(), "a")

    assert rows == [{
        "name": "a",
        "kind": "Secret",
        "group": "",
        "version": "v1",
        "namespaced": True,
        "type": "kubernetes.io/basic-auth",
        "keys": ["password", "username"],
        "status": "matching",
        "message": "",
        "current": True,
    }]


def test_external_diagnostics_mark_missing_secret_as_error():
    runner = MagicMock()
    runner.run_config_processor_node_script.return_value = json.dumps(
        edit_state_with_external_ref("target-creds", basic_auth_secret_external_ref())
    )
    core = MagicMock()
    core.read_namespaced_secret.side_effect = ApiException(status=404, reason="Not Found")
    service = ConfigEditService(namespace="ma", runner=runner)

    with patch.object(service, "_core_v1", return_value=core):
        result = service.diagnose_external_resources("targetClusters: {}\n")

    assert result["status"] == "error"
    assert result["diagnostics"][0]["severity"] == "error"
    assert "Secret 'target-creds' was not found in namespace 'ma'" in result["diagnostics"][0]["message"]
    assert result["diagnostics"][0]["path"] == [
        "targetClusters", "target", "authConfig", "basic", "secretName",
    ]


def test_external_diagnostics_accept_unmanaged_matching_secret():
    runner = MagicMock()
    runner.run_config_processor_node_script.return_value = json.dumps(
        edit_state_with_external_ref("a", basic_auth_secret_external_ref())
    )
    core = MagicMock()
    core.read_namespaced_secret.return_value = fake_k8s_item(
        "a",
        type="kubernetes.io/basic-auth",
        data={"username": encoded("admin"), "password": encoded("pw")},
    )
    service = ConfigEditService(
        namespace="ma",
        store=FakeStore(WorkflowConfig(raw_yaml=(
            "targetClusters:\n"
            "  target:\n"
            "    authConfig:\n"
            "      basic:\n"
            "        secretName: a\n"
        ))),
        runner=runner,
    )

    with patch.object(service, "_core_v1", return_value=core):
        result = service.diagnose_external_resources(service.store.config.raw_yaml)

    assert result == {"status": "valid", "diagnostics": []}
    core.read_namespaced_secret.assert_called_once_with(name="a", namespace="ma")


def test_external_diagnostics_use_pending_yaml_value():
    state = edit_state_with_external_ref("", basic_auth_secret_external_ref())
    secret_node = state["nodes"][0]["children"][0]["children"][0]
    secret_node.pop("value", None)
    secret_node["label"] = "secretName: <unset>"
    runner = MagicMock()
    runner.run_config_processor_node_script.return_value = json.dumps(state)
    core = MagicMock()
    core.read_namespaced_secret.side_effect = ApiException(status=404, reason="Not Found")
    service = ConfigEditService(
        namespace="ma",
        store=FakeStore(WorkflowConfig(raw_yaml=(
            "targetClusters:\n"
            "  target:\n"
            "    authConfig:\n"
            "      basic:\n"
            "        secretName: a\n"
        ))),
        runner=runner,
    )

    with patch.object(service, "_core_v1", return_value=core):
        result = service.diagnose_external_resources(service.store.config.raw_yaml)

    assert result["status"] == "error"
    assert "Secret 'a' was not found in namespace 'ma'" in result["diagnostics"][0]["message"]
    core.read_namespaced_secret.assert_called_once_with(name="a", namespace="ma")


def test_external_diagnostics_mark_config_map_with_bad_keys_as_error():
    state = edit_state_with_external_ref("proxy-log-config", config_map_external_ref())
    node = state["nodes"][0]["children"][0]["children"][0]
    node["path"] = ["traffic", "proxies", "cap", "proxyConfig", "loggingConfigurationOverrideConfigMap"]
    node["id"] = "edit:traffic.proxies.cap.proxyConfig.loggingConfigurationOverrideConfigMap"
    runner = MagicMock()
    runner.run_config_processor_node_script.return_value = json.dumps(state)
    core = MagicMock()
    core.read_namespaced_config_map.return_value = fake_k8s_item(
        "proxy-log-config",
        data={"application.properties": "x = y"},
    )
    service = ConfigEditService(
        namespace="ma",
        store=FakeStore(WorkflowConfig(raw_yaml="traffic: {}\n")),
        runner=runner,
    )

    with patch.object(service, "_core_v1", return_value=core):
        result = service.diagnose_external_resources("traffic: {}\n")

    assert result["status"] == "error"
    assert "ConfigMap 'proxy-log-config' does not satisfy this reference" in result["diagnostics"][0]["message"]
    assert "missing log4j2.properties" in result["diagnostics"][0]["message"]


def test_read_external_resource_returns_missing_payload_for_not_found_secret():
    service = ConfigEditService(namespace="ma", store=FakeStore())
    core = MagicMock()
    core.read_namespaced_secret.side_effect = ApiException(status=404, reason="Not Found")

    with patch.object(service, "_core_v1", return_value=core):
        resource = service.read_external_resource(basic_auth_secret_external_ref(), "target-creds")

    assert resource == {
        "kind": "Secret",
        "name": "target-creds",
        "keys": [],
        "values": {},
        "missing": True,
        "message": "Secret 'target-creds' was not found in namespace 'ma'.",
    }


def test_save_external_secret_creates_when_updated_resource_was_deleted():
    service = ConfigEditService(namespace="ma", store=FakeStore())
    core = MagicMock()
    core.read_namespaced_secret.side_effect = ApiException(status=404, reason="Not Found")
    core.patch_namespaced_secret.side_effect = ApiException(status=404, reason="Not Found")

    with patch.object(service, "_core_v1", return_value=core):
        result = service.save_external_resource(
            basic_auth_secret_external_ref(),
            {"secretName": "target-creds", "username": "admin", "password": "pw"},
            existing_name="target-creds",
        )

    assert result == {"name": "target-creds", "message": "Secret created: target-creds"}
    core.create_namespaced_secret.assert_called_once()


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
    runner.prepare_workflow.assert_not_called()
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
    prepared = MagicMock(
        report={
            "formatVersion": 1,
            "allowed": True,
            "checkedResources": 0,
            "issues": [],
        },
    )
    runner.prepare_workflow.return_value = prepared
    runner.commit_prepared_workflow.return_value = {
        "workflow_name": "migration"
    }
    service = ConfigEditService(
        namespace="test",
        store=FakeStore(WorkflowConfig(raw_yaml="sourceClusters: {}")),
        runner=runner,
    )

    result = service.submit_saved_config("migration", unique_run_nonce="123")

    assert result == {"workflow_name": "migration"}
    runner.prepare_workflow.assert_called_once_with(
        "sourceClusters: {}",
        [
            "--workflow-name", "migration",
            "--namespace", "test",
            "--unique-run-nonce", "123",
        ],
    )
    runner.commit_prepared_workflow.assert_called_once_with(prepared)
    prepared.cleanup.assert_called_once()
    _stop_workflow.assert_called_once_with("test", "migration")
    _delete_workflow.assert_called_once_with("test", "migration")


@patch(
    "console_link.workflow.services.config_edit_service.workflow_exists",
    return_value=False,
)
@patch(
    "console_link.workflow.services.config_edit_service."
    "verify_configured_secrets_exist",
)
def test_submit_raw_config_uses_captured_yaml_instead_of_reloading_store(
    _verify_secrets,
    _workflow_exists,
):
    runner = MagicMock()
    runner.run_config_processor_node_script.return_value = json.dumps({
        "validation": {"valid": True, "errors": []},
    })
    prepared = MagicMock(report={
        "formatVersion": 1,
        "allowed": True,
        "checkedResources": 0,
        "issues": [],
    })
    runner.prepare_workflow.return_value = prepared
    runner.commit_prepared_workflow.return_value = {
        "workflow_name": "migration",
    }
    service = ConfigEditService(
        namespace="test",
        store=FakeStore(WorkflowConfig(
            raw_yaml="sourceClusters:\n  stale: {}\n",
        )),
        runner=runner,
        secret_store=MagicMock(),
    )

    result = service.submit_raw_config(
        "targetClusters:\n  captured: {}\n",
        "migration",
        unique_run_nonce="123",
    )

    assert result == {"workflow_name": "migration"}
    runner.prepare_workflow.assert_called_once_with(
        "targetClusters:\n  captured: {}\n",
        [
            "--workflow-name", "migration",
            "--namespace", "test",
            "--unique-run-nonce", "123",
        ],
    )
    _verify_secrets.assert_called_once()
    prepared.cleanup.assert_called_once()


@patch("console_link.workflow.services.config_edit_service.workflow_exists")
@patch("console_link.workflow.services.config_edit_service.verify_configured_secrets_exist")
def test_submit_preflight_blocks_before_workflow_is_touched(
    _verify_secrets,
    _workflow_exists,
):
    runner = MagicMock()
    runner.run_config_processor_node_script.return_value = json.dumps({
        "validation": {"valid": True, "errors": []},
    })
    prepared = MagicMock(report={
        "formatVersion": 1,
        "allowed": False,
        "checkedResources": 1,
        "issues": [{
            "kind": "CapturedTraffic",
            "name": "p2-topic",
            "plural": "capturedtraffics",
            "classification": "recreate-required",
            "message": "sourceLabel cannot be changed",
            "source": "kubernetes",
        }],
    })
    runner.prepare_workflow.return_value = prepared
    service = ConfigEditService(
        namespace="test",
        store=FakeStore(WorkflowConfig(raw_yaml="sourceClusters: {}")),
        runner=runner,
        secret_store=MagicMock(),
    )

    with pytest.raises(AdmissionPreflightBlocked):
        service.submit_saved_config("migration", unique_run_nonce="123")

    _verify_secrets.assert_called_once()
    _workflow_exists.assert_not_called()
    runner.commit_prepared_workflow.assert_not_called()
    prepared.cleanup.assert_called_once()


@patch(
    "console_link.workflow.services.config_edit_service.wait_until_workflow_deleted",
    return_value=True,
)
@patch(
    "console_link.workflow.services.config_edit_service.delete_workflow",
    return_value=True,
)
@patch(
    "console_link.workflow.services.config_edit_service.stop_workflow",
    return_value=True,
)
@patch(
    "console_link.workflow.services.config_edit_service.workflow_exists",
    return_value=True,
)
@patch("console_link.workflow.services.config_edit_service.verify_configured_secrets_exist")
def test_submit_continues_with_nonblocking_preflight_warnings(
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
    prepared = MagicMock(report={
        "formatVersion": 1,
        "allowed": True,
        "checkedResources": 1,
        "issues": [{
            "kind": "CapturedTraffic",
            "name": "p2-topic",
            "plural": "capturedtraffics",
            "classification": "approval-required",
            "message": "Approval will be required",
            "source": "kubernetes",
        }],
    })
    runner.prepare_workflow.return_value = prepared
    runner.commit_prepared_workflow.return_value = {
        "workflow_name": "migration",
        "warnings": ["initializer warning"],
    }
    service = ConfigEditService(
        namespace="test",
        store=FakeStore(WorkflowConfig(raw_yaml="sourceClusters: {}")),
        runner=runner,
        secret_store=MagicMock(),
    )

    result = service.submit_saved_config("migration", unique_run_nonce="123")

    assert result["warnings"] == [
        "initializer warning",
        "CapturedTraffic p2-topic: Approval will be required",
    ]
    runner.commit_prepared_workflow.assert_called_once_with(prepared)
