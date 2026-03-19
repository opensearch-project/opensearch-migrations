"""Tests for workflow reset command."""
import json
from unittest.mock import patch, Mock, MagicMock

import pytest
from click.testing import CliRunner
from kubernetes.client.rest import ApiException

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands.reset import (
    extract_reset_actions,
    _execute_reset_action,
    _kind_to_plural,
    reset_workflow_resources,
)


class TestExtractResetActions:
    def test_extracts_reset_action_from_node(self):
        action = {"action": "delete", "apiVersion": "apps/v1", "kind": "Deployment", "name": "my-proxy"}
        nodes = {
            "node-1": {
                "displayName": "deploy-proxy",
                "inputs": {"parameters": [
                    {"name": "resetAction", "value": json.dumps(action)}
                ]}
            }
        }
        result = extract_reset_actions(nodes)
        assert len(result) == 1
        assert result[0] == ("deploy-proxy", action)

    def test_skips_empty_reset_action(self):
        nodes = {
            "node-1": {
                "displayName": "step",
                "inputs": {"parameters": [{"name": "resetAction", "value": ""}]}
            }
        }
        assert extract_reset_actions(nodes) == []

    def test_skips_invalid_json(self):
        nodes = {
            "node-1": {
                "displayName": "step",
                "inputs": {"parameters": [{"name": "resetAction", "value": "not-json"}]}
            }
        }
        assert extract_reset_actions(nodes) == []

    def test_skips_nodes_without_reset_action(self):
        nodes = {
            "node-1": {
                "displayName": "step",
                "inputs": {"parameters": [{"name": "otherParam", "value": "foo"}]}
            }
        }
        assert extract_reset_actions(nodes) == []

    def test_excludes_reset_done_from_succeeded_nodes(self):
        action = {"action": "delete", "apiVersion": "apps/v1", "kind": "Deployment", "name": "my-rfs"}
        nodes = {
            "create-node": {
                "displayName": "create-rfs",
                "inputs": {"parameters": [
                    {"name": "resetAction", "value": json.dumps(action)}
                ]}
            },
            "delete-node": {
                "phase": "Succeeded",
                "displayName": "delete-rfs",
                "inputs": {"parameters": [
                    {"name": "resetDone", "value": "apps/v1/Deployment/my-rfs"}
                ]}
            }
        }
        assert extract_reset_actions(nodes) == []

    def test_does_not_exclude_reset_done_from_failed_nodes(self):
        action = {"action": "delete", "apiVersion": "apps/v1", "kind": "Deployment", "name": "my-rfs"}
        nodes = {
            "create-node": {
                "displayName": "create-rfs",
                "inputs": {"parameters": [
                    {"name": "resetAction", "value": json.dumps(action)}
                ]}
            },
            "delete-node": {
                "phase": "Failed",
                "displayName": "delete-rfs",
                "inputs": {"parameters": [
                    {"name": "resetDone", "value": "apps/v1/Deployment/my-rfs"}
                ]}
            }
        }
        result = extract_reset_actions(nodes)
        assert len(result) == 1

    def test_multiple_actions_and_partial_done(self):
        nodes = {
            "n1": {
                "displayName": "create-proxy",
                "inputs": {"parameters": [{"name": "resetAction", "value": json.dumps(
                    {"action": "delete", "apiVersion": "apps/v1", "kind": "Deployment", "name": "proxy-1"}
                )}]}
            },
            "n2": {
                "displayName": "create-kafka",
                "inputs": {"parameters": [{"name": "resetAction", "value": json.dumps(
                    {"action": "delete", "apiVersion": "kafka.strimzi.io/v1", "kind": "Kafka", "name": "kafka-1"}
                )}]}
            },
            "n3": {
                "phase": "Succeeded",
                "displayName": "delete-proxy",
                "inputs": {"parameters": [
                    {"name": "resetDone", "value": "apps/v1/Deployment/proxy-1"}
                ]}
            }
        }
        result = extract_reset_actions(nodes)
        assert len(result) == 1
        assert result[0][1]["name"] == "kafka-1"


class TestKindToPlural:
    def test_kafka_kinds(self):
        assert _kind_to_plural("Kafka") == "kafkas"
        assert _kind_to_plural("KafkaNodePool") == "kafkanodepools"
        assert _kind_to_plural("KafkaTopic") == "kafkatopics"

    def test_unknown_kind_adds_s(self):
        assert _kind_to_plural("Widget") == "widgets"


class TestExecuteResetAction:
    @patch('console_link.workflow.commands.reset.client')
    def test_delete_deployment(self, mock_client):
        mock_apps = Mock()
        mock_client.AppsV1Api.return_value = mock_apps

        action = {"action": "delete", "apiVersion": "apps/v1", "kind": "Deployment", "name": "my-deploy"}
        result = _execute_reset_action(action, "ma")
        assert result is True
        mock_apps.delete_namespaced_deployment.assert_called_once_with(name="my-deploy", namespace="ma")

    @patch('console_link.workflow.commands.reset.client')
    def test_delete_statefulset(self, mock_client):
        mock_apps = Mock()
        mock_client.AppsV1Api.return_value = mock_apps

        action = {"action": "delete", "apiVersion": "apps/v1", "kind": "StatefulSet", "name": "my-ss"}
        result = _execute_reset_action(action, "ma")
        assert result is True
        mock_apps.delete_namespaced_stateful_set.assert_called_once_with(name="my-ss", namespace="ma")

    @patch('console_link.workflow.commands.reset.client')
    def test_delete_service(self, mock_client):
        mock_core = Mock()
        mock_client.CoreV1Api.return_value = mock_core

        action = {"action": "delete", "apiVersion": "v1", "kind": "Service", "name": "my-svc"}
        result = _execute_reset_action(action, "ma")
        assert result is True
        mock_core.delete_namespaced_service.assert_called_once_with(name="my-svc", namespace="ma")

    @patch('console_link.workflow.commands.reset.client')
    def test_delete_secret(self, mock_client):
        mock_core = Mock()
        mock_client.CoreV1Api.return_value = mock_core

        action = {"action": "delete", "apiVersion": "v1", "kind": "Secret", "name": "my-secret"}
        result = _execute_reset_action(action, "ma")
        assert result is True
        mock_core.delete_namespaced_secret.assert_called_once_with(name="my-secret", namespace="ma")

    @patch('console_link.workflow.commands.reset.client')
    def test_delete_custom_resource(self, mock_client):
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom

        action = {"action": "delete", "apiVersion": "kafka.strimzi.io/v1", "kind": "Kafka", "name": "my-kafka"}
        result = _execute_reset_action(action, "ma")
        assert result is True
        mock_custom.delete_namespaced_custom_object.assert_called_once_with(
            group="kafka.strimzi.io", version="v1",
            namespace="ma", plural="kafkas", name="my-kafka"
        )

    @patch('console_link.workflow.commands.reset.client')
    def test_returns_false_on_404(self, mock_client):
        mock_apps = Mock()
        mock_apps.delete_namespaced_deployment.side_effect = ApiException(status=404)
        mock_client.AppsV1Api.return_value = mock_apps

        action = {"action": "delete", "apiVersion": "apps/v1", "kind": "Deployment", "name": "gone"}
        result = _execute_reset_action(action, "ma")
        assert result is False

    @patch('console_link.workflow.commands.reset.client')
    def test_raises_on_non_404_error(self, mock_client):
        mock_apps = Mock()
        mock_apps.delete_namespaced_deployment.side_effect = ApiException(status=500)
        mock_client.AppsV1Api.return_value = mock_apps

        action = {"action": "delete", "apiVersion": "apps/v1", "kind": "Deployment", "name": "err"}
        with pytest.raises(ApiException):
            _execute_reset_action(action, "ma")

    def test_skips_malformed_action(self):
        assert _execute_reset_action({"action": "delete"}, "ma") is False

    @patch('console_link.workflow.commands.reset.client')
    def test_patch_deployment(self, mock_client):
        mock_apps = Mock()
        mock_client.AppsV1Api.return_value = mock_apps

        patch_body = {"spec": {"replicas": 0}}
        action = {"action": "patch", "apiVersion": "apps/v1", "kind": "Deployment", "name": "my-d", "patch": patch_body}
        result = _execute_reset_action(action, "ma")
        assert result is True
        mock_apps.patch_namespaced_deployment.assert_called_once_with(name="my-d", namespace="ma", body=patch_body)

    @patch('console_link.workflow.commands.reset.client')
    def test_namespace_override(self, mock_client):
        mock_apps = Mock()
        mock_client.AppsV1Api.return_value = mock_apps

        action = {"action": "delete", "apiVersion": "apps/v1", "kind": "Deployment", "name": "d", "namespace": "other"}
        _execute_reset_action(action, "ma")
        mock_apps.delete_namespaced_deployment.assert_called_once_with(name="d", namespace="other")


class TestResetWorkflowResources:
    @patch('console_link.workflow.commands.reset.client')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._fetch_reset_actions')
    def test_returns_negative_one_when_workflow_not_found(self, mock_fetch, mock_k8s, mock_client):
        mock_fetch.return_value = None
        result = reset_workflow_resources("wf", "ma", "http://localhost:2746")
        assert result == -1

    @patch('console_link.workflow.commands.reset.client')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._fetch_reset_actions')
    def test_executes_actions_and_deletes_workflow(self, mock_fetch, mock_k8s, mock_client):
        action = {"action": "delete", "apiVersion": "apps/v1", "kind": "Deployment", "name": "my-d"}
        mock_fetch.return_value = [("step", action)]

        mock_apps = Mock()
        mock_client.AppsV1Api.return_value = mock_apps
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom

        result = reset_workflow_resources("wf", "ma", "http://localhost:2746")
        assert result == 2  # 1 action + 1 workflow delete
        mock_apps.delete_namespaced_deployment.assert_called_once()
        mock_custom.delete_namespaced_custom_object.assert_called_once()

    @patch('console_link.workflow.commands.reset.client')
    @patch('console_link.workflow.commands.reset.load_k8s_config')
    @patch('console_link.workflow.commands.reset._fetch_reset_actions')
    def test_skip_workflow_delete_when_flag_false(self, mock_fetch, mock_k8s, mock_client):
        mock_fetch.return_value = []
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom

        result = reset_workflow_resources("wf", "ma", "http://localhost:2746", delete_workflow=False)
        assert result == 0
        mock_custom.delete_namespaced_custom_object.assert_not_called()


class TestResetCommand:
    @patch('console_link.workflow.commands.reset.reset_workflow_resources')
    @patch('console_link.workflow.commands.reset._fetch_reset_actions')
    def test_reset_command_with_yes(self, mock_fetch, mock_reset):
        action = {"action": "delete", "apiVersion": "apps/v1", "kind": "Deployment", "name": "my-d"}
        mock_fetch.return_value = [("step", action)]
        mock_reset.return_value = 2

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--yes'])
        assert result.exit_code == 0
        assert 'Reset complete' in result.output

    @patch('console_link.workflow.commands.reset._fetch_reset_actions')
    def test_reset_command_workflow_not_found(self, mock_fetch):
        mock_fetch.return_value = None

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '--yes'])
        assert 'not found' in result.output
