from unittest.mock import MagicMock, patch
import pytest
from console_link.environment import Environment, WorkflowConfigException


def test_get_cluster_from_workflow_config_valid():
    config = {
        "targetClusters": {
            "test-target": {
                "endpoint": "http://localhost:9200",
                "auth": {"type": "no_auth"}
            }
        }
    }
    # Mock map_cluster_from_workflow_config to return a valid config dict
    with patch('console_link.environment.map_cluster_from_workflow_config') as mock_map:
        mock_map.return_value = {"endpoint": "http://localhost:9200", "allow_insecure": False}
        # Mock Cluster constructor
        with patch('console_link.environment.Cluster') as mock_cluster:
            mock_cluster_instance = MagicMock()
            mock_cluster.return_value = mock_cluster_instance

            cluster = Environment._get_cluster_from_workflow_config(config, "targetClusters", "target cluster")

            assert cluster is not None
            assert cluster == mock_cluster_instance


def test_get_cluster_from_workflow_config_missing():
    config = {}
    cluster = Environment._get_cluster_from_workflow_config(config, "targetClusters", "target cluster")
    assert cluster is None


def test_get_cluster_from_workflow_config_invalid():
    config = {
        "targetClusters": {
            "test-target": {
                "invalid": "data"
            }
        }
    }
    with patch('console_link.environment.map_cluster_from_workflow_config') as mock_map:
        mock_map.side_effect = ValueError("Invalid config")

        cluster = Environment._get_cluster_from_workflow_config(config, "targetClusters", "target cluster")

        assert cluster is None


@patch('console_link.environment.WorkflowConfigStore')
def test_from_workflow_config(mock_store_cls):
    mock_store = MagicMock()
    mock_store_cls.return_value = mock_store

    mock_config = {
        "targetClusters": {"t1": {}},
        "sourceClusters": {"s1": {}}
    }
    mock_store.load_config.return_value = mock_config

    with patch.object(Environment, '_get_cluster_from_workflow_config') as mock_get_cluster:
        mock_target = MagicMock()
        mock_source = MagicMock()
        mock_get_cluster.side_effect = [mock_target, mock_source]

        env = Environment.from_workflow_config()

        assert env is not None
        assert env.target_cluster == mock_target
        assert env.source_cluster == mock_source

        # Verify calls
        assert mock_get_cluster.call_count == 2
        mock_get_cluster.assert_any_call(mock_config, "targetClusters", "target cluster")
        mock_get_cluster.assert_any_call(mock_config, "sourceClusters", "source cluster")


@patch('console_link.environment.WorkflowConfigStore')
def test_from_workflow_config_no_config(mock_store_cls):
    mock_store = MagicMock()
    mock_store_cls.return_value = mock_store
    mock_store.load_config.return_value = None

    with pytest.raises(WorkflowConfigException):
        Environment.from_workflow_config()


@patch('console_link.cli.can_use_k8s_config_store')
@patch('console_link.environment.WorkflowConfigStore')
def test_from_workflow_config_allow_empty(mock_store_cls, mock_can_use_k8s):
    # Test that allow_empty=True returns an empty Environment when no config is found
    mock_store = MagicMock()
    mock_store_cls.return_value = mock_store
    mock_store.load_config.return_value = None
    mock_can_use_k8s.return_value = True

    # Should not raise exception when allow_empty=True
    env = Environment.from_workflow_config(allow_empty=True)

    assert env is not None
    # Verify it's an empty environment with no clusters configured
    assert env.source_cluster is None
    assert env.target_cluster is None


@patch('console_link.cli.can_use_k8s_config_store')
@patch('console_link.environment.Environment.from_workflow_config')
def test_context_init_with_allow_empty_workflow_config(mock_from_workflow, mock_can_use_k8s):
    # Test that Context.__init__ passes allow_empty_workflow_config to Environment.from_workflow_config
    from console_link.cli import Context

    # Use k8s environment
    mock_can_use_k8s.return_value = True
    mock_env = MagicMock()
    mock_from_workflow.return_value = mock_env

    # Test with allow_empty_workflow_config=True
    ctx = Context(config_file='/fake/path', allow_empty_workflow_config=True)

    # Verify from_workflow_config was called with allow_empty=True
    mock_from_workflow.assert_called_once_with(allow_empty=True)
    assert ctx.env == mock_env
