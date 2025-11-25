import unittest.mock as mock
import pytest
import re
from requests.models import Response, HTTPError
import logging
import subprocess

from console_link.models.command_runner import CommandRunner, CommandRunnerError
from console_link.middleware import snapshot as snapshot_
from console_link.models.cluster import AuthMethod, Cluster, HttpMethod
from console_link.models.command_result import CommandResult
from console_link.models.factories import (UnsupportedSnapshotError,
                                           get_snapshot)
from console_link.models.snapshot import (FailedToCreateSnapshot, FileSystemSnapshot, S3Snapshot,
                                          Snapshot)
from tests.utils import create_valid_cluster

mock_snapshot_api_response = {
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


@pytest.fixture
def fs_snapshot(mock_cluster):
    config = {
        "snapshot_name": "reindex_from_snapshot",
        "fs": {
            "repo_path": "/path/for/snapshot/repo"
        }
    }
    return FileSystemSnapshot(config, mock_cluster)


def snapshot_404_response():
    mock_response = mock.Mock(spec=Response)
    mock_response.status_code = 404
    mock_response.json.return_value = {
        "error": {
            "type": "snapshot_missing_exception",
            "reason": "snapshot does not exist"
        },
        "status": 404
    }
    return mock_response


def snapshot_repo_404_error():
    mock_response = mock.Mock(spec=Response)
    mock_response.status_code = 404
    mock_response.json.return_value = {
        "error": {
            "type": "repository_missing_exception",
            "reason": "snapshot repository does not exist"
        },
        "status": 404
    }
    error = HTTPError()
    error.response = mock_response
    return error


def all_snapshots_response_single():
    mock_response = mock.Mock(spec=Response)
    mock_response.status_code = 200
    mock_response.json.return_value = {
        "snapshots": [
            {
                "snapshot": "test_snapshot"
            }
        ]
    }
    return mock_response


def all_snapshots_response_multiple():
    mock_response = mock.Mock(spec=Response)
    mock_response.status_code = 200
    mock_response.json.return_value = {
        "snapshots": [
            {
                "snapshot": "test_snapshot1"
            },
            {
                "snapshot": "test_snapshot2"
            }
        ]
    }
    return mock_response


def snapshot_delete_response():
    mock_response = mock.Mock()
    mock_response.status_code = 200
    return mock_response


@pytest.mark.parametrize("snapshot_fixture", ['s3_snapshot', 'fs_snapshot'])
def test_snapshot_status(request, snapshot_fixture):
    snapshot = request.getfixturevalue(snapshot_fixture)
    source_cluster = snapshot.source_cluster
    mock_response = mock.Mock()
    mock_response.json.return_value = mock_snapshot_api_response
    source_cluster.call_api.return_value = mock_response

    result = snapshot.status()

    assert isinstance(result, CommandResult)
    assert "SUCCESS" == result.value
    source_cluster.call_api.assert_called_with(
        f"/_snapshot/{snapshot.snapshot_repo_name}/{snapshot.snapshot_name}",
        HttpMethod.GET
    )


@pytest.mark.parametrize("snapshot_fixture", ['s3_snapshot', 'fs_snapshot'])
def test_snapshot_status_full(request, snapshot_fixture):
    snapshot = request.getfixturevalue(snapshot_fixture)
    source_cluster = snapshot.source_cluster

    # Set up mock responses for both API endpoints
    basic_response = mock.Mock()
    basic_response.json.return_value = mock_snapshot_api_response

    status_response = mock.Mock()
    status_response.json.return_value = mock_snapshot_api_response

    # Configure call_api to return different responses based on path
    def mock_call_api(path, *args, **kwargs):
        if "_status" in path:
            return status_response
        return basic_response

    source_cluster.call_api.side_effect = mock_call_api

    result = snapshot_.status(snapshot=snapshot, deep_check=True)

    # Basic result validations
    assert isinstance(result, CommandResult)
    assert result.success

    # Content validations
    assert "SUCCESS" in result.value
    assert "Percent completed: 100.00%" in result.value
    assert "Total shards: 304" in result.value
    assert "Successful shards: 304" in result.value

    # Check format string entries
    assert "Start time:" in result.value
    assert "Estimated time to completion:" in result.value
    assert "Throughput:" in result.value

    # Verify date/time formatting is correct (timezone-agnostic check)
    # The timestamps in mock data are: start=1719343996753ms, duration=79426ms
    # Just verify the date format is present, not the exact time (which varies by timezone)
    assert "2024-06-25" in result.value  # Date is present
    assert re.search(r'\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}', result.value)  # Time format is correct

    # Verify snapshot progress information
    assert "64.000/64.000 MiB" in result.value  # Data processed
    assert "MiB/sec" in result.value  # Throughput format

    # No "N/A" placeholders should be present
    assert "N/A" not in result.value

    # API call verification
    source_cluster.call_api.assert_called_with(
        f"/_snapshot/{snapshot.snapshot_repo_name}/{snapshot.snapshot_name}/_status",
        HttpMethod.GET
    )


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

    mocker.patch("sys.stdout.write")
    mocker.patch("sys.stderr.write")
    mock = mocker.patch("subprocess.run")
    snapshot.create()

    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  '--snapshot-repo-name', snapshot.snapshot_repo_name,
                                  "--source-host", source.endpoint,
                                  "--source-insecure",
                                  "--otel-collector-endpoint", config["snapshot"]["otel_endpoint"],
                                  "--file-system-repo-path", config["snapshot"]["fs"]["repo_path"],
                                  ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)


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
    max_snapshot_rate = 100
    source = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    snapshot = S3Snapshot(config["snapshot"], source)

    mocker.patch("sys.stdout.write")
    mocker.patch("sys.stderr.write")
    mock = mocker.patch("subprocess.run")
    snapshot.create(max_snapshot_rate_mb_per_node=max_snapshot_rate)

    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  '--snapshot-repo-name', snapshot.snapshot_repo_name,
                                  "--source-host", source.endpoint,
                                  "--source-insecure",
                                  "--otel-collector-endpoint", config["snapshot"]["otel_endpoint"],
                                  "--s3-repo-uri", config["snapshot"]["s3"]["repo_uri"],
                                  "--s3-region", config["snapshot"]["s3"]["aws_region"],
                                  "--no-wait",
                                  "--max-snapshot-rate-mb-per-node", str(max_snapshot_rate),
                                  ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)


def test_s3_snapshot_create_with_custom_snapshot_repo_name_calls_subprocess_run_with_correct_args(mocker):
    custom_repo_name = "my-repo"
    config = {
        "snapshot": {
            "otel_endpoint": "http://otel:1111",
            "snapshot_name": "reindex_from_snapshot",
            "snapshot_repo_name": custom_repo_name,
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    max_snapshot_rate = 100
    source = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    snapshot = S3Snapshot(config["snapshot"], source)

    mocker.patch("sys.stdout.write")
    mocker.patch("sys.stderr.write")
    mock = mocker.patch("subprocess.run")
    snapshot.create(max_snapshot_rate_mb_per_node=max_snapshot_rate)

    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  '--snapshot-repo-name', custom_repo_name,
                                  "--source-host", source.endpoint,
                                  "--source-insecure",
                                  "--otel-collector-endpoint", config["snapshot"]["otel_endpoint"],
                                  "--s3-repo-uri", config["snapshot"]["s3"]["repo_uri"],
                                  "--s3-region", config["snapshot"]["s3"]["aws_region"],
                                  "--no-wait",
                                  "--max-snapshot-rate-mb-per-node", str(max_snapshot_rate),
                                  ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)


def test_s3_snapshot_create_calls_subprocess_run_with_correct_s3_role(mocker):
    s3_role = "arn:aws:iam::123456789012:role/OSMigrations-dev-us-west-1-default-SnapshotRole"
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-snapshot-bucket",
                "aws_region": "us-east-2",
                "role": s3_role
            }
        }
    }
    max_snapshot_rate = 100
    source = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    snapshot = S3Snapshot(config["snapshot"], source)

    mocker.patch("sys.stdout.write")
    mocker.patch("sys.stderr.write")
    mock = mocker.patch("subprocess.run")
    snapshot.create(max_snapshot_rate_mb_per_node=max_snapshot_rate)

    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  '--snapshot-repo-name', snapshot.snapshot_repo_name,
                                  "--source-host", source.endpoint,
                                  "--source-insecure",
                                  "--s3-repo-uri", config["snapshot"]["s3"]["repo_uri"],
                                  "--s3-region", config["snapshot"]["s3"]["aws_region"],
                                  "--no-wait",
                                  "--max-snapshot-rate-mb-per-node", str(max_snapshot_rate),
                                  "--s3-role-arn", s3_role,
                                  ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)


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
    mocker.patch("sys.stdout.write")
    mocker.patch("sys.stderr.write")
    mock = mocker.patch("subprocess.run")
    snapshot.create()
    auth_details = snapshot.source_cluster.get_basic_auth_details()
    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  '--snapshot-repo-name', snapshot.snapshot_repo_name,
                                  "--source-host", snapshot.source_cluster.endpoint,
                                  "--source-username", auth_details.username,
                                  "--source-password", auth_details.password,
                                  "--source-insecure",
                                  "--s3-repo-uri", config["snapshot"]["s3"]["repo_uri"],
                                  "--s3-region", config["snapshot"]["s3"]["aws_region"],
                                  "--no-wait"
                                  ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)


def test_fs_snapshot_create_works_for_clusters_with_basic_auth(mocker):
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "/path/to/repo"
            },
        }
    }
    max_snapshot_rate = 100
    snapshot = FileSystemSnapshot(config["snapshot"], create_valid_cluster(auth_type=AuthMethod.BASIC_AUTH))
    mocker.patch("sys.stdout.write")
    mocker.patch("sys.stderr.write")
    mock = mocker.patch("subprocess.run")
    snapshot.create(max_snapshot_rate_mb_per_node=max_snapshot_rate)
    auth_details = snapshot.source_cluster.get_basic_auth_details()
    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  '--snapshot-repo-name', snapshot.snapshot_repo_name,
                                  "--source-host", snapshot.source_cluster.endpoint,
                                  "--source-username", auth_details.username,
                                  "--source-password", auth_details.password,
                                  "--source-insecure",
                                  "--file-system-repo-path", config["snapshot"]["fs"]["repo_path"],
                                  "--max-snapshot-rate-mb-per-node", str(max_snapshot_rate),
                                  ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)


def test_fs_snapshot_create_works_for_clusters_with_sigv4(mocker):
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "/path/to/repo"
            },
        }
    }
    service_name = "aoss"
    signing_region = "us-west-1"
    snapshot = FileSystemSnapshot(config["snapshot"],
                                  create_valid_cluster(auth_type=AuthMethod.SIGV4,
                                                       details={"service": service_name,
                                                                "region": signing_region}))
    mocker.patch("sys.stdout.write")
    mocker.patch("sys.stderr.write")
    mock = mocker.patch("subprocess.run")
    snapshot.create()
    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  '--snapshot-repo-name', snapshot.snapshot_repo_name,
                                  "--source-host", snapshot.source_cluster.endpoint,
                                  "--source-aws-service-signing-name", service_name,
                                  "--source-aws-region", signing_region,
                                  "--source-insecure",
                                  "--file-system-repo-path", config["snapshot"]["fs"]["repo_path"],
                                  ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)


@pytest.mark.parametrize("snapshot_fixture", ['s3_snapshot', 'fs_snapshot'])
def test_snapshot_delete(request, snapshot_fixture):
    snapshot = request.getfixturevalue(snapshot_fixture)
    source_cluster = snapshot.source_cluster
    source_cluster.call_api.side_effect = [
        snapshot_delete_response(),  # DELETE snapshot call
        snapshot_404_response()  # GET check if snapshot is deleted
    ]

    result = snapshot.delete()
    assert "successfully deleted" in result
    source_cluster.call_api.assert_called()
    source_cluster.call_api.assert_has_calls([
        mock.call(f"/_snapshot/{snapshot.snapshot_repo_name}/{snapshot.snapshot_name}", HttpMethod.DELETE),
        mock.call(f"/_snapshot/{snapshot.snapshot_repo_name}/{snapshot.snapshot_name}", raise_error=False)
    ])


@pytest.mark.parametrize("snapshot_fixture", ['s3_snapshot', 'fs_snapshot'])
def test_snapshot_delete_all_snapshots_single_snapshot(request, snapshot_fixture):
    snapshot = request.getfixturevalue(snapshot_fixture)
    source_cluster = snapshot.source_cluster
    source_cluster.call_api.side_effect = [
        all_snapshots_response_single(),  # GET all snapshots
        snapshot_delete_response(),  # DELETE snapshot call
        snapshot_404_response()  # GET check if snapshot is deleted
    ]

    result = snapshot.delete_all_snapshots()
    assert "All snapshots cleared" in result
    source_cluster.call_api.assert_called()
    source_cluster.call_api.assert_has_calls([
        mock.call(f'/_snapshot/{snapshot.snapshot_repo_name}/_all', raise_error=True),
        mock.call(f"/_snapshot/{snapshot.snapshot_repo_name}/test_snapshot", HttpMethod.DELETE),
        mock.call(f"/_snapshot/{snapshot.snapshot_repo_name}/test_snapshot", raise_error=False)
    ])


@pytest.mark.parametrize("snapshot_fixture", ['s3_snapshot', 'fs_snapshot'])
def test_snapshot_delete_all_snapshots_multiple_snapshots(request, snapshot_fixture, caplog):
    snapshot = request.getfixturevalue(snapshot_fixture)
    source_cluster = snapshot.source_cluster
    source_cluster.call_api.side_effect = [
        all_snapshots_response_multiple(),  # GET all snapshots
        snapshot_delete_response(),  # DELETE snapshot call
        snapshot_404_response(),  # GET check if snapshot is deleted
        snapshot_delete_response(),  # DELETE snapshot call
        snapshot_404_response(),  # GET check if snapshot is deleted
    ]

    with caplog.at_level(logging.INFO, logger='console_link.models.snapshot'):
        snapshot.delete_all_snapshots()
        assert (f"Initiated deletion of snapshot: test_snapshot1 from "
                f"repository '{snapshot.snapshot_repo_name}'.") in caplog.text
        assert (f"Initiated deletion of snapshot: test_snapshot2 from "
                f"repository '{snapshot.snapshot_repo_name}'.") in caplog.text

    source_cluster.call_api.assert_called()
    source_cluster.call_api.assert_has_calls([
        mock.call(f'/_snapshot/{snapshot.snapshot_repo_name}/_all', raise_error=True),
        mock.call(f"/_snapshot/{snapshot.snapshot_repo_name}/test_snapshot1", HttpMethod.DELETE),
        mock.call(f"/_snapshot/{snapshot.snapshot_repo_name}/test_snapshot1", raise_error=False),
        mock.call(f"/_snapshot/{snapshot.snapshot_repo_name}/test_snapshot2", HttpMethod.DELETE),
        mock.call(f"/_snapshot/{snapshot.snapshot_repo_name}/test_snapshot2", raise_error=False),
    ])


@pytest.mark.parametrize("snapshot_fixture", ['s3_snapshot', 'fs_snapshot'])
def test_snapshot_delete_repo(request, snapshot_fixture):
    snapshot = request.getfixturevalue(snapshot_fixture)
    source_cluster = snapshot.source_cluster
    snapshot.delete_snapshot_repo()
    source_cluster.call_api.assert_called_once()
    source_cluster.call_api.assert_called_with(f"/_snapshot/{snapshot.snapshot_repo_name}",
                                               method=HttpMethod.DELETE,
                                               raise_error=True)


@pytest.mark.parametrize("snapshot_fixture", ['s3_snapshot', 'fs_snapshot'])
def test_snapshot_create_catches_error(mocker, request, snapshot_fixture):
    snapshot = request.getfixturevalue(snapshot_fixture)
    fake_command = ["/root/createSnapshot/bin/CreateSnapshot", "--snapshot_name=reindex_from_snapshot"]
    mock = mocker.patch.object(CommandRunner, 'run', cmd="abc", autospec=True,
                               side_effect=CommandRunnerError(2, cmd=fake_command, output="Snapshot failure"))

    with pytest.raises(FailedToCreateSnapshot):
        snapshot.create()

    mock.assert_called_once()


@pytest.mark.parametrize("snapshot_fixture", ['s3_snapshot', 'fs_snapshot'])
def test_handling_extra_args(mocker, request, snapshot_fixture):
    snapshot = request.getfixturevalue(snapshot_fixture)
    mocker.patch("sys.stdout.write")
    mocker.patch("sys.stderr.write")
    mock = mocker.patch('subprocess.run', autospec=True)
    extra_args = ['--extra-flag', '--extra-arg', 'extra-arg-value', 'this-is-an-option']

    result = snapshot.create(extra_args=extra_args)

    assert "creation initiated successfully" in result
    mock.assert_called_once()
    assert all([arg in mock.call_args.args[0] for arg in extra_args])


@pytest.mark.parametrize("snapshot_fixture", ['s3_snapshot', 'fs_snapshot'])
def test_delete_all_snapshots_repository_missing(request, snapshot_fixture, caplog):
    snapshot = request.getfixturevalue(snapshot_fixture)
    source_cluster = snapshot.source_cluster
    source_cluster.call_api.side_effect = [
        snapshot_repo_404_error(),  # GET all snapshots
    ]

    with caplog.at_level(logging.INFO, logger='console_link.models.snapshot'):
        snapshot.delete_all_snapshots()
        assert f"Repository '{snapshot.snapshot_repo_name}' is missing. Skipping snapshot clearing." in caplog.text
    source_cluster.call_api.assert_called_once()
    source_cluster.call_api.assert_has_calls([
        mock.call(f'/_snapshot/{snapshot.snapshot_repo_name}/_all', raise_error=True)
    ])
