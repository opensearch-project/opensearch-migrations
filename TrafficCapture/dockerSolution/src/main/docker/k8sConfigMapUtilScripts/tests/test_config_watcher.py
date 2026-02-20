import json
import pathlib
from typing import Dict

import pytest
from unittest.mock import patch, MagicMock
import yaml

from configmap2yaml.config_watcher import ConfigMapWatcher, parse_args
from kubernetes.client import V1ConfigMap, V1ConfigMapList, V1ObjectMeta

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"


@pytest.fixture
def mock_k8s_client():
    """Mock Kubernetes client."""
    with patch("kubernetes.client.CoreV1Api") as mock_api:
        mock_instance = MagicMock()
        mock_api.return_value = mock_instance
        yield mock_instance


@pytest.fixture
def mock_k8s_watcher():
    """Mock Kubernetes client."""
    with patch("kubernetes.watch.Watch") as mock_watch:
        mock_instance = MagicMock()
        mock_watch.return_value = mock_instance
        yield mock_instance


@pytest.fixture
def mock_k8s_config():
    """Mock Kubernetes config (Prevent loading in-cluster config during tests)"""
    with patch("kubernetes.config.load_incluster_config") as mock_config:
        mock_config.return_value = None
        yield mock_config


def test_valid_config_map_creates_proper_migration_services_yaml(tmp_path, mock_k8s_client, mock_k8s_config):
    generated_services_yaml_path = tmp_path / "migration_services.yaml"
    watcher = ConfigMapWatcher(label_selector=None, namespace="ma", output_file=generated_services_yaml_path)

    with open(TEST_DATA_DIRECTORY / "0001_list_config_map_sample_response.json") as input_data:
        json_response = json.load(input_data)
        mock_k8s_response_obj = _create_k8s_list_config_maps_response_obj_from_json(json_response)
        mock_k8s_client.list_namespaced_config_map.return_value = mock_k8s_response_obj
        watcher.init_config_map_data()
        mock_k8s_client.list_namespaced_config_map.assert_called_once()
        mock_k8s_config.assert_called_once()
        with open(TEST_DATA_DIRECTORY / "0001_expected_migration_services_yaml.yaml") as expected_output, \
                open(generated_services_yaml_path) as actual_output:
            yaml1 = yaml.safe_load(expected_output)
            yaml2 = yaml.safe_load(actual_output)
            assert yaml1 == yaml2, "Expected migration_services.yaml and actual migration_services.yaml do not match"


def test_empty_config_map_creates_min_migration_services_yaml(tmp_path, mock_k8s_client, mock_k8s_config):
    generated_services_yaml_path = tmp_path / "migration_services.yaml"
    watcher = ConfigMapWatcher(label_selector=None, namespace="ma", output_file=generated_services_yaml_path)

    with open(TEST_DATA_DIRECTORY / "0002_list_config_map_empty_response.json") as input_data:
        json_response = json.load(input_data)
        mock_k8s_response_obj = _create_k8s_list_config_maps_response_obj_from_json(json_response)
        mock_k8s_client.list_namespaced_config_map.return_value = mock_k8s_response_obj
        watcher.init_config_map_data()
        mock_k8s_client.list_namespaced_config_map.assert_called_once()
        mock_k8s_config.assert_called_once()
        with open(generated_services_yaml_path) as actual_output:
            expected_services_dict = _default_migration_services_dict()
            actual_dict = yaml.safe_load(actual_output)
            assert expected_services_dict == actual_dict, ("Expected migration_services.yaml and actual "
                                                           "migration_services.yaml do not match")


def test_process_add_event(tmp_path, mock_k8s_watcher, mock_k8s_config):
    generated_services_yaml_path = tmp_path / "migration_services.yaml"
    watcher = ConfigMapWatcher(label_selector=None, namespace="ma", output_file=generated_services_yaml_path)

    mock_data = {
        "endpoint": "http://test.endpoint:9200",
        "authType": "no_auth"
    }

    mock_k8s_watcher.stream.return_value = [
        _create_mock_watcher_event("source-cluster-default", mock_data, "ADDED")
    ]

    watcher.process_events(mock_k8s_watcher)
    mock_k8s_watcher.stream.assert_called_once()

    expected_services_dict = _default_migration_services_dict()
    expected_services_dict["source_cluster"] = {
        "endpoint": mock_data["endpoint"],
        mock_data["authType"]: None
    }

    with open(generated_services_yaml_path) as actual_output:
        actual_dict = yaml.safe_load(actual_output)
        assert expected_services_dict == actual_dict, ("Expected migration_services.yaml and actual "
                                                       "migration_services.yaml do not match")


def test_process_add_then_delete_event(tmp_path, mock_k8s_watcher, mock_k8s_config):
    generated_services_yaml_path = tmp_path / "migration_services.yaml"
    watcher = ConfigMapWatcher(label_selector=None, namespace="ma", output_file=generated_services_yaml_path)

    mock_add_data = {
        "endpoint": "http://test.endpoint:9200",
        "authType": "no_auth"
    }

    mock_k8s_watcher.stream.return_value = [
        _create_mock_watcher_event("source-cluster-default", mock_add_data, "ADDED"),
        _create_mock_watcher_event("source-cluster-default", mock_add_data, "DELETED"),
    ]

    watcher.process_events(mock_k8s_watcher)
    mock_k8s_watcher.stream.assert_called_once()

    expected_services_dict = _default_migration_services_dict()

    with open(generated_services_yaml_path) as actual_output:
        actual_dict = yaml.safe_load(actual_output)
        assert expected_services_dict == actual_dict, ("Expected migration_services.yaml and actual "
                                                       "migration_services.yaml do not match")


def test_parse_args_required_outfile(monkeypatch):
    monkeypatch.setattr("sys.argv", ["test.py"])
    # argparse should exit if required arg is missing
    with pytest.raises(SystemExit):
        parse_args()


def test_parse_args_defaults(monkeypatch):
    monkeypatch.setattr("sys.argv", ["test.py", "--outfile", "services.yaml"])
    args = parse_args()
    assert args.outfile == "services.yaml"
    assert args.label_selector == ""
    assert args.namespace == "ma"


def _default_migration_services_dict(namespace="ma"):
    return {
        "backfill": {
            "reindex_from_snapshot": {
                "k8s": {
                    "deployment_name": f"{namespace}-bulk-document-loader",
                    "namespace": namespace
                }
            }
        },
        "replay": {
            "k8s": {
                "deployment_name": f"{namespace}-replayer",
                "namespace": namespace
            }
        }
    }


def _create_mock_watcher_event(config_map_name: str, data: Dict[any, any], event_type: str, namespace="ma"):
    config_map = V1ConfigMap(
        api_version="v1",
        kind="ConfigMap",
        metadata=V1ObjectMeta(
            name=config_map_name,
            namespace=namespace,
        ),
        data=data,
    )
    return {
        "object": config_map,
        "type": event_type
    }


# Given a json dict created from raw json output of get configmap e.g. 'kubectl get configmap -n ma -o json',
# create an equivalent response object for the k8s client list_namespaced_config_map() function
def _create_k8s_list_config_maps_response_obj_from_json(json_data):
    # Parse metadata for the list
    list_metadata = json_data.get("metadata", {})

    # Parse each ConfigMap in the 'items' list
    config_maps = []
    for item in json_data.get("items", []):
        metadata = item.get("metadata", {})

        # Create V1ConfigMap object
        config_map = V1ConfigMap(
            api_version="v1",
            kind="ConfigMap",
            metadata=V1ObjectMeta(
                name=metadata.get("name"),
                namespace=metadata.get("namespace"),
                annotations=metadata.get("annotations"),
                resource_version=metadata.get("resource_version"),
                uid=metadata.get("uid"),
            ),
            data=item.get("data", {}),
        )
        config_maps.append(config_map)

    # Create and return V1ConfigMapList
    return V1ConfigMapList(
        api_version="v1",
        kind="ConfigMapList",
        metadata=V1ObjectMeta(resource_version=list_metadata.get("resource_version")),
        items=config_maps
    )
