"""Unit tests for WorkflowConfigStore class."""

from types import SimpleNamespace

import pytest
from unittest.mock import Mock
from kubernetes.client.rest import ApiException

from console_link.workflow.models.workflow_config_store import (
    MISSING_CONFIG_REVISION,
    WorkflowConfigConflict,
    WorkflowConfigStore,
)
from console_link.workflow.models.config import WorkflowConfig


class TestWorkflowConfigStore:
    """Test suite for WorkflowConfigStore CRUD operations."""

    def test_save_config_creates_new_configmap(self):
        """Test save_config creates a new ConfigMap when it doesn't exist."""
        # Mock Kubernetes API client
        mock_v1 = Mock()
        mock_v1.patch_namespaced_config_map.side_effect = ApiException(status=404)
        mock_v1.create_namespaced_config_map.return_value = None

        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        config = WorkflowConfig({"key": "value"})
        result = store.save_config(config, "test-session")

        assert "created" in result.lower()
        mock_v1.create_namespaced_config_map.assert_called_once()

    def test_save_config_updates_existing_configmap(self):
        """Test save_config updates an existing ConfigMap."""
        # Mock Kubernetes API client
        mock_v1 = Mock()
        mock_v1.patch_namespaced_config_map.return_value = None

        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        config = WorkflowConfig({"key": "updated_value"})
        result = store.save_config(config, "test-session")

        assert "updated" in result.lower()
        mock_v1.patch_namespaced_config_map.assert_called_once()

    def test_load_config_success(self):
        """Test load_config successfully retrieves an existing config."""
        # Mock Kubernetes API client
        mock_v1 = Mock()
        mock_config_map = Mock()
        mock_config_map.data = {
            "workflow_config.yaml": 'key: value\n'
        }
        mock_v1.read_namespaced_config_map.return_value = mock_config_map

        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        config = store.load_config("test-session")

        assert config is not None
        assert config.get("key") == "value"
        mock_v1.read_namespaced_config_map.assert_called_once()

    def test_load_config_success_with_json(self):
        """Test load_config successfully retrieves an existing config."""
        # Mock Kubernetes API client
        mock_v1 = Mock()
        mock_config_map = Mock()
        mock_config_map.data = {
            "workflow_config.yaml": '{"key": "value"}'
        }
        mock_v1.read_namespaced_config_map.return_value = mock_config_map

        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        config = store.load_config("test-session")

        assert config is not None
        assert config.get("key") == "value"
        mock_v1.read_namespaced_config_map.assert_called_once()

    def test_load_config_not_found(self):
        """Test load_config returns None when ConfigMap doesn't exist."""
        # Mock Kubernetes API client
        mock_v1 = Mock()
        mock_v1.read_namespaced_config_map.side_effect = ApiException(status=404)

        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        config = store.load_config("nonexistent-session")

        assert config is None

    def test_load_document_returns_raw_yaml_and_kubernetes_revision(self):
        mock_v1 = Mock()
        mock_v1.read_namespaced_config_map.return_value = SimpleNamespace(
            metadata=SimpleNamespace(resource_version="17"),
            data={"workflow_config.yaml": "sourceClusters: {}\n"},
        )
        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        document = store.load_document("test-session")

        assert document.raw_yaml == "sourceClusters: {}\n"
        assert document.persisted_revision == "17"

    def test_load_document_represents_a_missing_config_without_creating_it(self):
        mock_v1 = Mock()
        mock_v1.read_namespaced_config_map.side_effect = ApiException(status=404)
        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        document = store.load_document("test-session")

        assert document.raw_yaml == ""
        assert document.persisted_revision == MISSING_CONFIG_REVISION
        mock_v1.create_namespaced_config_map.assert_not_called()

    def test_save_document_creates_only_when_the_expected_document_is_missing(self):
        mock_v1 = Mock()
        mock_v1.read_namespaced_config_map.side_effect = ApiException(status=404)
        mock_v1.create_namespaced_config_map.return_value = SimpleNamespace(
            metadata=SimpleNamespace(resource_version="18"),
            data={"workflow_config.yaml": "sourceClusters: {}\n"},
        )
        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        document = store.save_document(
            WorkflowConfig(raw_yaml="sourceClusters: {}\n"),
            MISSING_CONFIG_REVISION,
            "test-session",
        )

        assert document.persisted_revision == "18"
        body = mock_v1.create_namespaced_config_map.call_args.kwargs["body"]
        assert body.data["workflow_config.yaml"] == "sourceClusters: {}\n"
        mock_v1.replace_namespaced_config_map.assert_not_called()

    def test_save_document_replaces_with_a_resource_version_precondition(self):
        mock_v1 = Mock()
        current = SimpleNamespace(
            metadata=SimpleNamespace(
                name="test-session",
                resource_version="17",
                labels={"owner": "test"},
            ),
            data={
                "workflow_config.yaml": "old: value\n",
                "unrelated": "preserved",
            },
        )
        mock_v1.read_namespaced_config_map.return_value = current
        mock_v1.replace_namespaced_config_map.return_value = SimpleNamespace(
            metadata=SimpleNamespace(resource_version="18"),
            data={"workflow_config.yaml": "new: value\n"},
        )
        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        document = store.save_document(
            WorkflowConfig(raw_yaml="new: value\n"),
            "17",
            "test-session",
        )

        assert document.persisted_revision == "18"
        body = mock_v1.replace_namespaced_config_map.call_args.kwargs["body"]
        assert body.metadata.resource_version == "17"
        assert body.data["workflow_config.yaml"] == "new: value\n"
        assert body.data["unrelated"] == "preserved"

    def test_save_document_rejects_a_stale_revision_before_writing(self):
        mock_v1 = Mock()
        mock_v1.read_namespaced_config_map.return_value = SimpleNamespace(
            metadata=SimpleNamespace(resource_version="18"),
            data={"workflow_config.yaml": "changed: elsewhere\n"},
        )
        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        with pytest.raises(WorkflowConfigConflict) as error:
            store.save_document(
                WorkflowConfig(raw_yaml="my: change\n"),
                "17",
                "test-session",
            )

        assert error.value.current.persisted_revision == "18"
        assert error.value.current.raw_yaml == "changed: elsewhere\n"
        mock_v1.replace_namespaced_config_map.assert_not_called()

    def test_save_document_converts_a_kubernetes_conflict_to_current_document(self):
        mock_v1 = Mock()
        current = SimpleNamespace(
            metadata=SimpleNamespace(resource_version="17"),
            data={"workflow_config.yaml": "old: value\n"},
        )
        changed = SimpleNamespace(
            metadata=SimpleNamespace(resource_version="18"),
            data={"workflow_config.yaml": "changed: elsewhere\n"},
        )
        mock_v1.read_namespaced_config_map.side_effect = [current, changed]
        mock_v1.replace_namespaced_config_map.side_effect = ApiException(status=409)
        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        with pytest.raises(WorkflowConfigConflict) as error:
            store.save_document(
                WorkflowConfig(raw_yaml="my: change\n"),
                "17",
                "test-session",
            )

        assert error.value.current.persisted_revision == "18"

    def test_delete_config_success(self):
        """Test delete_config successfully deletes a ConfigMap."""
        # Mock Kubernetes API client
        mock_v1 = Mock()
        mock_v1.delete_namespaced_config_map.return_value = None

        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        result = store.delete_config("test-session")

        assert "deleted" in result.lower()
        mock_v1.delete_namespaced_config_map.assert_called_once()

    def test_delete_config_not_found(self):
        """Test delete_config raises exception when ConfigMap doesn't exist."""
        # Mock Kubernetes API client
        mock_v1 = Mock()
        mock_v1.delete_namespaced_config_map.side_effect = ApiException(status=404)

        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        with pytest.raises(ApiException) as exc_info:
            store.delete_config("nonexistent-session")

        assert exc_info.value.status == 404

    def test_list_sessions(self):
        """Test list_sessions returns all session names."""
        # Mock Kubernetes API client
        mock_v1 = Mock()

        # Create mock ConfigMaps
        mock_cm1 = Mock()
        mock_cm1.data = {"session_name": "session1"}
        mock_cm1.metadata.labels = {"session": "session1"}

        mock_cm2 = Mock()
        mock_cm2.data = {"session_name": "session2"}
        mock_cm2.metadata.labels = {"session": "session2"}

        mock_list = Mock()
        mock_list.items = [mock_cm1, mock_cm2]
        mock_v1.list_namespaced_config_map.return_value = mock_list

        store = WorkflowConfigStore(namespace="test-ns", k8s_client=mock_v1)

        sessions = store.list_sessions()

        assert len(sessions) == 2
        assert "session1" in sessions
        assert "session2" in sessions
