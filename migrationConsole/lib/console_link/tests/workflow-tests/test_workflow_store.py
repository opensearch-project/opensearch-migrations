"""Unit tests for WorkflowConfigStore class."""

import pytest
from unittest.mock import Mock
from kubernetes.client.rest import ApiException

from console_link.workflow.models.store import WorkflowConfigStore
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
