import unittest.mock as mock

import pytest

from console_link.middleware import snapshot as snapshot_
from console_link.models.cluster import AuthMethod, Cluster, HttpMethod
from console_link.models.command_result import CommandResult
from console_link.models.factories import (UnsupportedSnapshotError,
                                           get_snapshot)
from console_link.models.snapshot import (FileSystemSnapshot, S3Snapshot,
                                          Snapshot)
from tests.utils import create_valid_cluster


@pytest.fixture
def mock_cluster():
    cluster = mock.Mock(spec=Cluster)
    return cluster


@pytest.fixture
def s3_snapshot(mock_cluster):
    config = {
        "snapshot_name": "test_snapshot",
        "s3": {
            "repo_uri": "s3://test-bucket",
            "aws_region": "us-west-2"
        }
    }
    return S3Snapshot(config, mock_cluster)


def test_s3_snapshot_status(s3_snapshot, mock_cluster):
    mock_response = mock.Mock()
    mock_response.json.return_value = {
        "snapshots": [
            {
                "snapshot": "test_snapshot",
                "state": "SUCCESS"
            }
        ]
    }
    mock_cluster.call_api.return_value = mock_response

    result = s3_snapshot.status()

    assert isinstance(result, CommandResult)
    assert result.success
    assert result.value == "SUCCESS"
    mock_cluster.call_api.assert_called_once_with("/_snapshot/migration_assistant_repo/test_snapshot",
                                                  HttpMethod.GET)


def test_s3_snapshot_status_full(s3_snapshot, mock_cluster):
    mock_response = mock.Mock()
    mock_response.json.return_value = {
        "snapshots": [
            {
                "snapshot": "rfs-snapshot",
                "repository": "migration_assistant_repo",
                "uuid": "7JFrWqraSJ20anKfiSIj1Q",
                "state": "SUCCESS",
                "include_global_state": True,
                "shards_stats": {
                    "initializing": 0,
                    "started": 0,
                    "finalizing": 0,
                    "done": 304,
                    "failed": 0,
                    "total": 304
                },
                "stats": {
                    "incremental": {
                        "file_count": 67041,
                        "size_in_bytes": 67108864
                    },
                    "total": {
                        "file_count": 67041,
                        "size_in_bytes": 67108864
                    },
                    "start_time_in_millis": 1719343996753,
                    "time_in_millis": 79426
                }
            }
        ]
    }
    mock_cluster.call_api.return_value = mock_response

    result = snapshot_.status(snapshot=s3_snapshot, deep_check=True)

    assert isinstance(result, CommandResult)
    assert result.success
    assert "SUCCESS" in result.value
    assert "Percent completed: 100.00%" in result.value
    assert "Total shards: 304" in result.value
    assert "Successful shards: 304" in result.value
    assert "Failed shards: 0" in result.value
    assert "Start time:" in result.value
    assert "Duration:" in result.value
    assert "Anticipated duration remaining:" in result.value
    assert "Throughput:" in result.value

    assert "N/A" not in result.value

    mock_cluster.call_api.assert_called_with("/_snapshot/migration_assistant_repo/test_snapshot/_status",
                                             HttpMethod.GET)


def test_s3_snapshot_init_succeeds():
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    snapshot = S3Snapshot(config['snapshot'], create_valid_cluster())
    assert isinstance(snapshot, Snapshot)


def test_fs_snapshot_init_succeeds():
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "/path/for/snapshot/repo"
            },
        }
    }
    snapshot = FileSystemSnapshot(config["snapshot"], create_valid_cluster(auth_type=AuthMethod.NO_AUTH))
    assert isinstance(snapshot, Snapshot)


def test_get_snapshot_for_s3_config():
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    snapshot = get_snapshot(config["snapshot"], create_valid_cluster())
    assert isinstance(snapshot, S3Snapshot)


def test_get_snapshot_for_fs_config():
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "/path/for/snapshot/repo"
            },
        }
    }
    snapshot = get_snapshot(config["snapshot"], create_valid_cluster(auth_type=AuthMethod.NO_AUTH))
    assert isinstance(snapshot, FileSystemSnapshot)


def test_get_snapshot_fails_for_invalid_config():
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "invalid": {
                "key": "value"
            },
        }
    }
    with pytest.raises(UnsupportedSnapshotError) as excinfo:
        get_snapshot(config["snapshot"], create_valid_cluster())
    assert "Unsupported snapshot type" in excinfo.value.args[0]
    assert "invalid" in excinfo.value.args[1]


def test_get_snpashot_fails_for_config_with_fs_and_s3():
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "/path/for/snapshot/repo"
            },
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    with pytest.raises(ValueError) as excinfo:
        get_snapshot(config["snapshot"], create_valid_cluster())
    assert "Invalid config file for snapshot" in str(excinfo.value.args[0])


def test_fs_snapshot_create_calls_subprocess_run_with_correct_args(mocker):
    config = {
        "snapshot": {
            "otel_endpoint": "http://otel:1111",
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "/path/for/snapshot/repo"
            },
        }
    }
    source = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    snapshot = FileSystemSnapshot(config["snapshot"], source)

    mock = mocker.patch("subprocess.run")
    snapshot.create()

    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  "--source-host", source.endpoint,
                                  "--source-insecure",
                                  "--otel-collector-endpoint", config["snapshot"]["otel_endpoint"],
                                  "--file-system-repo-path", config["snapshot"]["fs"]["repo_path"],
                                  ], stdout=None, stderr=None, text=True, check=True)


def test_s3_snapshot_create_calls_subprocess_run_with_correct_args(mocker):
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
    source = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    snapshot = S3Snapshot(config["snapshot"], source)

    mock = mocker.patch("subprocess.run")
    snapshot.create()

    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  "--source-host", source.endpoint,
                                  "--source-insecure",
                                  "--otel-collector-endpoint", config["snapshot"]["otel_endpoint"],
                                  "--s3-repo-uri", config["snapshot"]["s3"]["repo_uri"],
                                  "--s3-region", config["snapshot"]["s3"]["aws_region"],
                                  "--no-wait",
                                  ], stdout=None, stderr=None, text=True, check=True)


def test_s3_snapshot_create_fails_for_clusters_with_auth(mocker):
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    snapshot = S3Snapshot(config["snapshot"], create_valid_cluster(auth_type=AuthMethod.BASIC_AUTH))
    mock = mocker.patch("subprocess.run")
    snapshot.create()
    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  "--source-host", snapshot.source_cluster.endpoint,
                                  "--source-username", snapshot.source_cluster.auth_details.get("username"),
                                  "--source-password", snapshot.source_cluster.get_basic_auth_password(),
                                  "--source-insecure",
                                  "--s3-repo-uri", config["snapshot"]["s3"]["repo_uri"],
                                  "--s3-region", config["snapshot"]["s3"]["aws_region"],
                                  "--no-wait"
                                  ], stdout=None, stderr=None, text=True, check=True)


def test_fs_snapshot_create_fails_for_clusters_with_auth(mocker):
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "/path/to/repo"
            },
        }
    }
    snapshot = FileSystemSnapshot(config["snapshot"], create_valid_cluster(auth_type=AuthMethod.BASIC_AUTH))
    mock = mocker.patch("subprocess.run")
    snapshot.create()
    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  "--source-host", snapshot.source_cluster.endpoint,
                                  "--source-username", snapshot.source_cluster.auth_details.get("username"),
                                  "--source-password", snapshot.source_cluster.get_basic_auth_password(),
                                  "--source-insecure",
                                  "--file-system-repo-path", config["snapshot"]["fs"]["repo_path"],
                                  ], stdout=None, stderr=None, text=True, check=True)
