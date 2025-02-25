import json
import pathlib
import pytest
from unittest.mock import patch, MagicMock
import yaml

from kubernetes.client import CoreV1Api

from configmap2yaml.config_watcher import ConfigMapWatcher
from kubernetes.client import V1ConfigMap, V1ConfigMapList, V1ObjectMeta
from kubernetes.watch import Watch

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"

@pytest.fixture
def mock_k8s_client():
    """Mock Kubernetes client."""
    with patch("kubernetes.client.CoreV1Api") as mock_api:
        mock_instance = MagicMock()
        mock_api.return_value = mock_instance
        yield mock_instance

@pytest.fixture
def mock_k8s_config():
    """Mock Kubernetes config."""
    with patch("kubernetes.config") as mock_config:
        mock_instance = MagicMock()
        mock_config.return_value = mock_instance
        yield mock_instance


def test_valid_config_map_creates_proper_migration_services_yaml(tmp_path, mock_k8s_client, mock_k8s_config):
    generated_services_yaml_path = tmp_path / "migration_services.yaml"
    mock_k8s_config.load_incluster_config.return_value = None
    watcher = ConfigMapWatcher(label_selector=None, namespace="ma", output_file=generated_services_yaml_path)

    with open(TEST_DATA_DIRECTORY / "0001_list_config_map_sample_response.json") as input:
        json_response = json.load(input)
        mock_k8s_response_obj = _create_k8s_list_config_maps_response_obj_from_json(json_response)
        mock_k8s_client.list_namespaced_config_map.return_value = mock_k8s_response_obj
        watcher.init_config_map_data()
        mock_k8s_client.list_namespaced_config_map.assert_called_once()
        with open(TEST_DATA_DIRECTORY / "0001_expected_migration_services_yaml.yaml") as expected_output, \
             open(generated_services_yaml_path) as actual_output:
            yaml1 = yaml.safe_load(expected_output)
            yaml2 = yaml.safe_load(actual_output)
            assert yaml1 == yaml2, "Expected migration_services.yaml and actual migration_services.yaml do not match"


# def test_empty_config_map_creates_min_migration_services_yaml(tmp_path, mocker):
#     generated_services_yaml_path = tmp_path / "migration_services.yaml"
#     watcher = ConfigMapWatcher(label_selector=None, namespace="ma", output_file=generated_services_yaml_path)
#
#     with open(TEST_DATA_DIRECTORY / "0002_list_config_map_empty_response.json") as input:
#         json_response = json.load(input)
#         mock_k8s_response_obj = _create_k8s_list_config_maps_response_obj_from_json(json_response)
#         k8s_mock = mocker.patch.object(CoreV1Api, 'list_namespaced_config_map', autospec=True,
#                                        return_value=mock_k8s_response_obj)
#         watcher.init_config_map_data()
#         k8s_mock.assert_called_once()
#         with open(TEST_DATA_DIRECTORY / "0002_expected_migration_services_yaml.yaml") as expected_output, \
#                 open(generated_services_yaml_path) as actual_output:
#             yaml1 = yaml.safe_load(expected_output)
#             yaml2 = yaml.safe_load(actual_output)
#             assert yaml1 == yaml2, "Expected migration_services.yaml and actual migration_services.yaml do not match"


# def test_watch_config_maps_updates_migration_services_yaml(tmp_path, mocker):
#     generated_services_yaml_path = tmp_path / "migration_services.yaml"
#     watcher = ConfigMapWatcher(label_selector=None, namespace="ma", output_file=generated_services_yaml_path)
#
#     with open(TEST_DATA_DIRECTORY / "0001_list_config_map_sample_response.json") as input:
#         json_response = json.load(input)
#         mock_k8s_response_obj = _create_k8s_list_config_maps_response_obj_from_json(json_response)
#         k8s_mock = mocker.patch.object(Watch, 'stream', autospec=True,
#                                        return_value=None)
#         watcher.watch_configmaps()
#         k8s_mock.assert_called_once()
#         with open(TEST_DATA_DIRECTORY / "0001_expected_migration_services_yaml.yaml") as expected_output, \
#                 open(generated_services_yaml_path) as actual_output:
#             yaml1 = yaml.safe_load(expected_output)
#             yaml2 = yaml.safe_load(actual_output)
#             assert yaml1 == yaml2, "Expected migration_services.yaml and actual migration_services.yaml do not match"



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
