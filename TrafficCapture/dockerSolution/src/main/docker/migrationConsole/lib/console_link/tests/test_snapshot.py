from console_link.models.snapshot import S3Snapshot, FileSystemSnapshot, Snapshot
from console_link.environment import get_snapshot
from console_link.models.cluster import AuthMethod
from tests.utils import create_valid_cluster


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
    snapshot = S3Snapshot(config['snapshot'], create_valid_cluster(), None)
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
    snapshot = get_snapshot(config["snapshot"], create_valid_cluster(), None)
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
