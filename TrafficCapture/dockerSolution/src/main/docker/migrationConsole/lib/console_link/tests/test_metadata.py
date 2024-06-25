import pytest
from console_link.models.metadata import Metadata
from console_link.models.snapshot import S3Snapshot
from tests.utils import create_valid_cluster


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
        "component-template-allowlist": [
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


def test_metadata_init_with_minimal_config_and_external_snapshot_succeeds():
    snapshot_config = {
        "snapshot_name": "reindex_from_snapshot",
        "s3": {
            "repo_uri": "s3://my-bucket",
            "aws_region": "us-east-1"
        }
    }
    snapshot = S3Snapshot(snapshot_config, create_valid_cluster(), create_valid_cluster())
    config = {
        "from_snapshot": None,
    }
    metadata = Metadata(config, create_valid_cluster(), snapshot)
    assert metadata._config == config
    assert metadata._snapshot_name == snapshot_config["snapshot_name"]
    assert isinstance(metadata, Metadata)


@pytest.mark.skip("Need to tighten up the schema specification to catch this case")
def test_metadata_init_with_partial_config_and_external_snapshot_fails():
    snapshot_config = {
        "snapshot_name": "reindex_from_snapshot",
        "s3": {
            "repo_uri": "s3://my-bucket",
            "aws_region": "us-east-1"
        }
    }
    snapshot = S3Snapshot(snapshot_config, create_valid_cluster(), create_valid_cluster())
    config = {
        "from_snapshot": {
            "local_dir": "/tmp/s3",
            "snapshot_name": "reindex_from_snapshot",
        }
    }
    with pytest.raises(ValueError) as excinfo:
        Metadata(config, create_valid_cluster(), snapshot)
    print(excinfo)
    assert 's3' in excinfo.value.args[0]['from_snapshot'][0]
    assert 'required field' == excinfo.value.args[0]['from_snapshot'][0]['s3'][0]


def test_full_config_and_snapshot_gives_priority_to_config():
    snapshot_config = {
        "snapshot_name": "reindex_from_snapshot",
        "s3": {
            "repo_uri": "s3://my-bucket",
            "aws_region": "us-east-1"
        }
    }
    snapshot = S3Snapshot(snapshot_config, create_valid_cluster(), create_valid_cluster())
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
    metadata = Metadata(config, create_valid_cluster(), snapshot)
    assert isinstance(metadata, Metadata)
    assert metadata._snapshot_name == config["from_snapshot"]["snapshot_name"]
    assert metadata._s3_uri == config["from_snapshot"]["s3"]["repo_uri"]
    assert metadata._aws_region == config["from_snapshot"]["s3"]["aws_region"]
    assert metadata._local_dir == config["from_snapshot"]["local_dir"]
