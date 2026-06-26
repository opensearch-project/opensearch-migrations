import subprocess
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
