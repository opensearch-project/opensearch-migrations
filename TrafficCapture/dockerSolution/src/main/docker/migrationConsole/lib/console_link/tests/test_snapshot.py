from console_link.models.snapshot import S3Snapshot, FileSystemSnapshot, Snapshot
from console_link.environment import get_snapshot
from console_link.models.cluster import AuthMethod
from tests.utils import create_valid_cluster
import pytest


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
    snapshot = S3Snapshot(config['snapshot'], create_valid_cluster(), create_valid_cluster())
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
    snapshot = FileSystemSnapshot(config["snapshot"], create_valid_cluster(auth_type=AuthMethod.NO_AUTH),
                                  create_valid_cluster(auth_type=AuthMethod.NO_AUTH))
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
    snapshot = get_snapshot(config["snapshot"], create_valid_cluster(), create_valid_cluster())
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
    snapshot = get_snapshot(config["snapshot"], create_valid_cluster(auth_type=AuthMethod.NO_AUTH),
                            create_valid_cluster(auth_type=AuthMethod.NO_AUTH))
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
    with pytest.raises(ValueError) as excinfo:
        get_snapshot(config["snapshot"], create_valid_cluster(), create_valid_cluster())
    assert "Invalid config file for snapshot" in str(excinfo.value.args[0])


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
        get_snapshot(config["snapshot"], create_valid_cluster(), create_valid_cluster())
    assert "Invalid config file for snapshot" in str(excinfo.value.args[0])


def test_fs_snapshot_create_calls_subprocess_run_with_correct_args(mocker):
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "/path/for/snapshot/repo"
            },
        }
    }
    source = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    snapshot = FileSystemSnapshot(config["snapshot"], source,
                                  create_valid_cluster(auth_type=AuthMethod.NO_AUTH))

    mock = mocker.patch("subprocess.run")
    snapshot.create()

    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  "--file-system-repo-path", config["snapshot"]["fs"]["repo_path"],
                                  "--source-host", source.endpoint,
                                  "--source-insecure", "--target-insecure"],
                                 stdout=None, stderr=None, text=True, check=True)


def test_s3_snapshot_create_calls_subprocess_run_with_correct_args(mocker):
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    source = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    snapshot = S3Snapshot(config["snapshot"], source, create_valid_cluster(auth_type=AuthMethod.NO_AUTH))

    mock = mocker.patch("subprocess.run")
    snapshot.create()

    mock.assert_called_once_with(["/root/createSnapshot/bin/CreateSnapshot",
                                  "--snapshot-name", config["snapshot"]["snapshot_name"],
                                  "--s3-repo-uri", config["snapshot"]["s3"]["repo_uri"],
                                  "--s3-region", config["snapshot"]["s3"]["aws_region"],
                                  "--source-host", source.endpoint,
                                  "--source-insecure", "--target-insecure"],
                                 stdout=None, stderr=None, text=True, check=True)


@pytest.mark.parametrize("source_auth,target_auth", [(AuthMethod.NO_AUTH, AuthMethod.BASIC_AUTH),
                                                     (AuthMethod.BASIC_AUTH, AuthMethod.NO_AUTH)])
def test_s3_snapshot_create_fails_for_clusters_with_auth(source_auth, target_auth):
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    snapshot = S3Snapshot(config["snapshot"], create_valid_cluster(auth_type=source_auth),
                          create_valid_cluster(auth_type=target_auth))
    with pytest.raises(NotImplementedError) as excinfo:
        snapshot.create()
    assert "authentication is not supported" in str(excinfo.value.args[0])


@pytest.mark.parametrize("source_auth,target_auth", [(AuthMethod.NO_AUTH, AuthMethod.BASIC_AUTH),
                                                     (AuthMethod.BASIC_AUTH, AuthMethod.NO_AUTH)])
def test_fs_snapshot_create_fails_for_clusters_with_auth(source_auth, target_auth):
    config = {
        "snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "/path/to/repo"
            },
        }
    }
    with pytest.raises(NotImplementedError) as excinfo:
        snapshot = FileSystemSnapshot(config["snapshot"], create_valid_cluster(auth_type=source_auth),
                                      create_valid_cluster(auth_type=target_auth))
        snapshot.create()
    assert "authentication is not supported" in str(excinfo.value.args[0])
