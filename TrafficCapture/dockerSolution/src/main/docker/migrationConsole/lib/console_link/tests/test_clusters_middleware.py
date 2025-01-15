import pytest
from unittest.mock import MagicMock
from console_link.middleware.clusters import clear_snapshots
from console_link.models.cluster import Cluster
import requests
import logging


# Helper to mock HTTPError with response
def create_http_error_mock(status_code, json_data):
    response_mock = MagicMock()
    response_mock.status_code = status_code
    response_mock.json.return_value = json_data
    return requests.exceptions.HTTPError(response=response_mock)


@pytest.fixture
def mock_cluster_with_missing_repo(mocker):
    cluster = MagicMock(spec=Cluster)
    # Simulate repository missing exception
    error_mock = create_http_error_mock(
        status_code=404,
        json_data={
            'error': {
                'type': 'repository_missing_exception',
                'reason': '[migration_assistant_repo] missing'
            }
        }
    )
    mocker.patch.object(cluster, 'call_api', side_effect=error_mock)
    return cluster


@pytest.fixture
def mock_cluster_with_snapshots(mocker):
    cluster = MagicMock(spec=Cluster)
    # Mock the response for listing snapshots
    mock_response = MagicMock()
    mock_response.json.return_value = {
        'snapshots': [
            {'snapshot': 'snapshot_1'},
            {'snapshot': 'snapshot_2'}
        ]
    }
    mock_response.status_code = 200

    def mock_call_api(path, *args, **kwargs):
        if "_all" in path:
            return mock_response
        elif "snapshot_1" in path or "snapshot_2" in path:
            return MagicMock()  # Simulate successful deletion
        raise ValueError(f"Unexpected path: {path}")

    mocker.patch.object(cluster, 'call_api', side_effect=mock_call_api)
    return cluster


def test_clear_snapshots_repository_missing(mock_cluster_with_missing_repo, caplog):
    with caplog.at_level(logging.INFO, logger='console_link.middleware.clusters'):
        clear_snapshots(mock_cluster_with_missing_repo, 'migration_assistant_repo')
        assert "Repository 'migration_assistant_repo' is missing. Skipping snapshot clearing." in caplog.text


def test_clear_snapshots_success(mock_cluster_with_snapshots, caplog):
    with caplog.at_level(logging.INFO, logger='console_link.middleware.clusters'):
        clear_snapshots(mock_cluster_with_snapshots, 'migration_assistant_repo')
        assert "Deleted snapshot: snapshot_1 from repository 'migration_assistant_repo'." in caplog.text
        assert "Deleted snapshot: snapshot_2 from repository 'migration_assistant_repo'." in caplog.text
