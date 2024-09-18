import pytest

from console_link.models.cluster import AuthMethod
from console_link.models.metadata import Metadata
from console_link.models.snapshot import FileSystemSnapshot, S3Snapshot
from tests.utils import create_valid_cluster


@pytest.fixture()
def s3_snapshot():
    snapshot_config = {
        "snapshot_name": "reindex_from_snapshot",
        "s3": {
            "repo_uri": "s3://my-bucket",
            "aws_region": "us-east-1"
        }
    }
    return S3Snapshot(snapshot_config, create_valid_cluster(auth_type=AuthMethod.NO_AUTH))


@pytest.fixture()
def fs_snapshot():
    snapshot_config = {
        "snapshot_name": "reindex_from_snapshot",
        "fs": {
            "repo_path": "/path/for/repo"
        }
    }
    return FileSystemSnapshot(snapshot_config, create_valid_cluster(auth_type=AuthMethod.NO_AUTH))


def test_metadata_init_with_fully_specified_config_succeeds():
    config = {
        "from_snapshot": {
            "local_dir": "/tmp/s3",
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        },
        "min_replicas": 1,
        "index_allowlist": [
            "my_index", "my_second_index"
        ],
        "index_template_allowlist": [
            "my_index_template", "my_second_index_template"
        ],
        "component_template_allowlist": [
            "my_component_template", "my_second_component_template"
        ]
    }
    metadata = Metadata(config, create_valid_cluster(), None)
    assert metadata._config == config
    assert isinstance(metadata, Metadata)


def test_metadata_init_with_minimal_config_no_external_snapshot_succeeds():
    config = {
        "from_snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    metadata = Metadata(config, create_valid_cluster(), None)
    assert metadata._config == config
    assert isinstance(metadata, Metadata)


def test_metadata_init_with_missing_snapshot_config_no_external_snapshot_fails():
    config = {
        "from_snapshot": None
    }
    with pytest.raises(ValueError) as excinfo:
        Metadata(config, create_valid_cluster(), None)
    assert "No snapshot is specified" in str(excinfo.value)


def test_metadata_init_with_partial_snapshot_config_no_external_snapshot_fails():
    config = {
        "from_snapshot": {
            "s3": {
                "aws_region": "us-east-1"
            },
        }
    }
    with pytest.raises(ValueError) as excinfo:
        Metadata(config, create_valid_cluster(), None)
    print(excinfo)
    assert 'snapshot_name' in excinfo.value.args[0]['from_snapshot'][0]
    assert 'required field' == excinfo.value.args[0]['from_snapshot'][0]['snapshot_name'][0]

    assert 'repo_uri' in excinfo.value.args[0]['from_snapshot'][0]['s3'][0]
    assert 'required field' == excinfo.value.args[0]['from_snapshot'][0]['s3'][0]['repo_uri'][0]


def test_metadata_init_with_minimal_config_and_external_snapshot_succeeds(s3_snapshot):
    config = {
        "from_snapshot": None,
    }
    metadata = Metadata(config, create_valid_cluster(), s3_snapshot)
    assert metadata._config == config
    assert metadata._snapshot_name == s3_snapshot.snapshot_name
    assert isinstance(metadata, Metadata)


@pytest.mark.skip("Need to tighten up the schema specification to catch this case")
def test_metadata_init_with_partial_config_and_external_snapshot_fails(s3_snapshot):
    config = {
        "from_snapshot": {
            "local_dir": "/tmp/s3",
            "snapshot_name": "reindex_from_snapshot",
        }
    }
    with pytest.raises(ValueError) as excinfo:
        Metadata(config, create_valid_cluster(), s3_snapshot)
    print(excinfo)
    assert 's3' in excinfo.value.args[0]['from_snapshot'][0]
    assert 'required field' == excinfo.value.args[0]['from_snapshot'][0]['s3'][0]


def test_full_config_and_snapshot_gives_priority_to_config(s3_snapshot):
    config = {
        "from_snapshot": {
            "local_dir": "/tmp/s3",
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    metadata = Metadata(config, create_valid_cluster(), s3_snapshot)
    assert isinstance(metadata, Metadata)
    assert metadata._snapshot_name == config["from_snapshot"]["snapshot_name"]
    assert metadata._s3_uri == config["from_snapshot"]["s3"]["repo_uri"]
    assert metadata._aws_region == config["from_snapshot"]["s3"]["aws_region"]
    assert metadata._local_dir == config["from_snapshot"]["local_dir"]


def test_full_config_with_version_includes_version_string_in_subprocess(s3_snapshot, mocker):
    config = {
        "from_snapshot": {
            "local_dir": "/tmp/s3",
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        },
        "source_cluster_version": "ES_6.8"

    }
    metadata = Metadata(config, create_valid_cluster(), s3_snapshot)

    mock = mocker.patch("subprocess.run")
    metadata.migrate()

    mock.assert_called_once()
    actual_call_args = mock.call_args.args[0]
    assert '--source-version' in actual_call_args
    assert config['source_cluster_version'] in actual_call_args


def test_metadata_with_s3_snapshot_makes_correct_subprocess_call(mocker):
    config = {
        "from_snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "local_dir": "/tmp/s3",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        },
        "otel_endpoint": "http://otel:1111",
    }
    target = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    metadata = Metadata(config, target, None)

    mock = mocker.patch("subprocess.run")
    metadata.migrate()

    mock.assert_called_once_with([
        "/root/metadataMigration/bin/MetadataMigration",
        "--otel-collector-endpoint", config["otel_endpoint"],
        "migrate",
        "--snapshot-name", config["from_snapshot"]["snapshot_name"],
        "--target-host", target.endpoint,
        "--min-replicas", '0',
        "--s3-local-dir", config["from_snapshot"]["local_dir"],
        "--s3-repo-uri", config["from_snapshot"]["s3"]["repo_uri"],
        "--s3-region", config["from_snapshot"]["s3"]["aws_region"],
        "--target-insecure",
    ], stdout=None, stderr=None, text=True, check=True
    )


def test_metadata_with_fs_snapshot_makes_correct_subprocess_call(mocker):
    config = {
        "from_snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "path/to/repo"
            },
        },
        "otel_endpoint": "http://otel:1111",
    }
    target = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    metadata = Metadata(config, target, None)

    mock = mocker.patch("subprocess.run")
    metadata.migrate()

    mock.assert_called_once_with([
        "/root/metadataMigration/bin/MetadataMigration",
        "--otel-collector-endpoint", config["otel_endpoint"],
        "migrate",
        "--snapshot-name", config["from_snapshot"]["snapshot_name"],
        "--target-host", target.endpoint,
        "--min-replicas", '0',
        "--file-system-repo-path", config["from_snapshot"]["fs"]["repo_path"],
        "--target-insecure",
    ], stdout=None, stderr=None, text=True, check=True)


def test_metadata_with_min_replicas_makes_correct_subprocess_call(mocker):
    config = {
        "from_snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "path/to/repo"
            },
        },
        "min_replicas": 2
    }
    target = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    metadata = Metadata(config, target, None)

    mock = mocker.patch("subprocess.run")
    metadata.migrate()

    mock.assert_called_once_with([
        "/root/metadataMigration/bin/MetadataMigration",
        "migrate",
        "--snapshot-name", config["from_snapshot"]["snapshot_name"],
        "--target-host", target.endpoint,
        "--min-replicas", '2',
        "--file-system-repo-path", config["from_snapshot"]["fs"]["repo_path"],
        '--target-insecure'
    ], stdout=None, stderr=None, text=True, check=True
    )


def test_metadata_with_allowlists_makes_correct_subprocess_call(mocker):
    config = {
        "from_snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "fs": {
                "repo_path": "path/to/repo"
            },
        },
        "otel_endpoint": "http://otel:1111",
        "index_allowlist": ["index1", "index2"],
        "index_template_allowlist": ["index_template1", "index_template2"],
        "component_template_allowlist": ["component_template1", "component_template2"]
    }
    target = create_valid_cluster(auth_type=AuthMethod.NO_AUTH)
    metadata = Metadata(config, target, None)

    mock = mocker.patch("subprocess.run")
    metadata.migrate()

    mock.assert_called_once_with([
        "/root/metadataMigration/bin/MetadataMigration",
        "--otel-collector-endpoint", config["otel_endpoint"],
        "migrate",
        "--snapshot-name", config["from_snapshot"]["snapshot_name"],
        "--target-host", target.endpoint,
        "--min-replicas", '0',
        "--file-system-repo-path", config["from_snapshot"]["fs"]["repo_path"],
        "--target-insecure",
        "--index-allowlist", "index1,index2",
        "--index-template-allowlist", "index_template1,index_template2",
        "--component-template-allowlist", "component_template1,component_template2",
    ], stdout=None, stderr=None, text=True, check=True
    )


def test_metadata_with_target_config_auth_makes_correct_subprocess_call(mocker):
    config = {
        "from_snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "local_dir": "/tmp/s3",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    target = create_valid_cluster(auth_type=AuthMethod.BASIC_AUTH)
    metadata = Metadata(config, target, None)

    mock = mocker.patch("subprocess.run")
    metadata.migrate()

    mock.assert_called_once_with([
        "/root/metadataMigration/bin/MetadataMigration",
        "migrate",
        "--snapshot-name", config["from_snapshot"]["snapshot_name"],
        "--target-host", target.endpoint,
        "--min-replicas", '0',
        "--s3-local-dir", config["from_snapshot"]["local_dir"],
        "--s3-repo-uri", config["from_snapshot"]["s3"]["repo_uri"],
        "--s3-region", config["from_snapshot"]["s3"]["aws_region"],
        "--target-username", target.auth_details.get("username"),
        "--target-password", target.get_basic_auth_password(),
        "--target-insecure",
    ], stdout=None, stderr=None, text=True, check=True
    )


def test_metadata_with_target_sigv4_makes_correct_subprocess_call(mocker):
    config = {
        "from_snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "local_dir": "/tmp/s3",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    service_name = "aoss"
    signing_region = "us-west-1"
    target = create_valid_cluster(auth_type=AuthMethod.SIGV4, details={"service": service_name,
                                                                       "region": signing_region})
    metadata = Metadata(config, target, None)

    mock = mocker.patch("subprocess.run")
    metadata.migrate()

    mock.assert_called_once_with([
        "/root/metadataMigration/bin/MetadataMigration",
        "migrate",
        "--snapshot-name", config["from_snapshot"]["snapshot_name"],
        "--target-host", target.endpoint,
        "--min-replicas", '0',
        "--s3-local-dir", config["from_snapshot"]["local_dir"],
        "--s3-repo-uri", config["from_snapshot"]["s3"]["repo_uri"],
        "--s3-region", config["from_snapshot"]["s3"]["aws_region"],
        "--target-aws-service-signing-name", service_name,
        "--target-aws-region", signing_region,
        "--target-insecure",
    ], stdout=None, stderr=None, text=True, check=True
    )


def test_metadata_init_with_minimal_config_and_extra_args(mocker):
    config = {
        "from_snapshot": {
            "snapshot_name": "reindex_from_snapshot",
            "s3": {
                "repo_uri": "s3://my-bucket",
                "aws_region": "us-east-1"
            },
        }
    }
    metadata = Metadata(config, create_valid_cluster(), None)

    mock = mocker.patch("subprocess.run")
    metadata.evaluate(extra_args=[
        "--foo", "bar",  # Pair of command and value
        "--flag",  # Flag with no value afterward
        "--bar", "baz",  # Another pair of command and value
        "bazzy"  # Lone value, will be ignored
    ])

    print(mock.call_args_list)

    mock.assert_called_once_with([
        '/root/metadataMigration/bin/MetadataMigration',
        "evaluate",
        "--snapshot-name", config["from_snapshot"]["snapshot_name"],
        '--target-host', 'https://opensearchtarget:9200',
        '--min-replicas', '0',
        "--s3-local-dir", mocker.ANY,
        "--s3-repo-uri", config["from_snapshot"]["s3"]["repo_uri"],
        "--s3-region", config["from_snapshot"]["s3"]["aws_region"],
        '--target-username', 'admin',
        '--target-password', 'myStrongPassword123!',
        '--target-insecure',
        '--foo', 'bar',
        '--flag',
        '--bar', 'baz'
    ], stdout=None, stderr=None, text=True, check=True)
