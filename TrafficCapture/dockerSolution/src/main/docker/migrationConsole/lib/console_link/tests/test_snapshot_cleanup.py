import pytest
from unittest.mock import MagicMock

from console_link.models.cluster import Cluster
from console_link.models.snapshot import S3Snapshot
from console_link.models.utils import DEFAULT_SNAPSHOT_REPO_NAME
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
                'reason': f'[{DEFAULT_SNAPSHOT_REPO_NAME}] missing'
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


def test_delete_all_snapshots_repository_missing(mock_cluster_with_missing_repo, caplog):
    config = {
        "snapshot": {
            "otel_endpoint": "http://otel:1111",
            "snapshot_name": "reindex_from_snapshot",
            "snapshot_repo_name": "test-repo",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    snapshot = S3Snapshot(config=config["snapshot"], source_cluster=mock_cluster_with_missing_repo)
    with caplog.at_level(logging.INFO, logger='console_link.models.snapshot'):
        snapshot.delete_all_snapshots(cluster=mock_cluster_with_missing_repo, repository=snapshot.snapshot_repo_name)
        assert "Repository 'test-repo' is missing. Skipping snapshot clearing." in caplog.text


def test_delete_all_snapshots_success(mock_cluster_with_snapshots, caplog):
    config = {
        "snapshot": {
            "otel_endpoint": "http://otel:1111",
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    snapshot = S3Snapshot(config=config["snapshot"], source_cluster=mock_cluster_with_snapshots)
    with caplog.at_level(logging.INFO, logger='console_link.models.snapshot'):
        snapshot.delete_all_snapshots(cluster=mock_cluster_with_snapshots, repository=snapshot.snapshot_repo_name)
        assert f"Deleted snapshot: snapshot_1 from repository '{snapshot.snapshot_repo_name}'." in caplog.text
        assert f"Deleted snapshot: snapshot_2 from repository '{snapshot.snapshot_repo_name}'." in caplog.text
