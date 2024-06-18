import pathlib

import pytest

from console_link.logic.backfill import get_backfill, UnsupportedBackfillTypeError
from console_link.models.backfill_base import Backfill
from console_link.models.backfill_osi import OpenSearchIngestionBackfill
from console_link.models.backfill_rfs import DockerRFSBackfill, ECSRFSBackfill

from tests.utils import create_valid_cluster

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
AWS_REGION = "us-east-1"


def test_get_backfill_valid_osi():
    osi_config = {
        "opensearch_ingestion": {
            "pipeline_role_arn": "arn:aws:iam::123456789012:role/OSMigrations-pipelineRole",
            "vpc_subnet_ids": [
                "subnet-024004957a02ce923"
            ],
            "security_group_ids": [
                "sg-04536940716d101f6"
            ],
            "aws_region": "us-west-2",
            "pipeline_name": "unit-test-pipeline",
            "index_regex_selection": [
                "index-.*"
            ],
            "log_group_name": "/aws/vendedlogs/osi-unit-test-default",
            "tags": [
                "migration_deployment=1.0.0"
            ]
        }
    }
    osi_backfill = get_backfill(osi_config, source_cluster=create_valid_cluster(),
                                target_cluster=create_valid_cluster())
    assert isinstance(osi_backfill, OpenSearchIngestionBackfill)
    assert isinstance(osi_backfill, Backfill)


def test_get_backfill_osi_missing_clusters():
    osi_config = {
        "opensearch_ingestion": {
            "pipeline_role_arn": "arn:aws:iam::123456789012:role/OSMigrations-pipelineRole",
            "vpc_subnet_ids": [
                "subnet-024004957a02ce923"
            ],
            "security_group_ids": [
                "sg-04536940716d101f6"
            ],
            "aws_region": "us-west-2",
            "pipeline_name": "unit-test-pipeline",
            "index_regex_selection": [
                "index-.*"
            ],
            "log_group_name": "/aws/vendedlogs/osi-unit-test-default",
            "tags": [
                "migration_deployment=1.0.0"
            ]
        }
    }
    with pytest.raises(ValueError) as excinfo:
        get_backfill(osi_config, None, create_valid_cluster())
    assert "source_cluster" in str(excinfo.value.args[0])
    with pytest.raises(ValueError) as excinfo:
        get_backfill(osi_config, create_valid_cluster(), None)
    assert "target_cluster" in str(excinfo.value.args[0])


def test_get_backfill_valid_docker_rfs():
    docker_rfs_config = {
        "reindex_from_snapshot": {
            "docker": None
        }
    }
    docker_rfs_backfill = get_backfill(docker_rfs_config, None, target_cluster=create_valid_cluster())
    assert isinstance(docker_rfs_backfill, DockerRFSBackfill)
    assert isinstance(docker_rfs_backfill, Backfill)


def test_get_backfill_rfs_missing_target_cluster():
    docker_rfs_config = {
        "reindex_from_snapshot": {
            "docker": None
        }
    }
    with pytest.raises(ValueError) as excinfo:
        get_backfill(docker_rfs_config, create_valid_cluster(), None)
    assert "target_cluster" in str(excinfo.value.args[0])


def test_get_backfill_valid_ecs_rfs():
    ecs_rfs_config = {
        "reindex_from_snapshot": {
            "ecs": {
                "cluster_name": "migration-aws-integ-ecs-cluster",
                "service_name": "migration-aws-integ-reindex-from-snapshot"
            }
        }
    }
    ecs_rfs_backfill = get_backfill(ecs_rfs_config, None, target_cluster=create_valid_cluster())
    assert isinstance(ecs_rfs_backfill, ECSRFSBackfill)
    assert isinstance(ecs_rfs_backfill, Backfill)


def test_get_backfill_unsupported_type():
    unknown_config = {
        "fetch": {"data": "xyz"}
    }
    with pytest.raises(UnsupportedBackfillTypeError) as excinfo:
        get_backfill(unknown_config, None, None)
    assert "Unsupported backfill type" in str(excinfo.value.args[0])
    assert "fetch" in str(excinfo.value.args[1])


def test_cant_instantiate_with_multiple_types():
    config = {
        "opensearch_ingestion": {
            "pipeline_role_arn": "arn:aws:iam::123456789012:role/OSMigrations-pipelineRole",
        },
        "reindex_from_snapshot": {
            "docker": None
        }
    }
    with pytest.raises(ValueError) as excinfo:
        get_backfill(config, create_valid_cluster(), create_valid_cluster())
    assert "Invalid config file for backfill" in str(excinfo.value.args[0])
    assert "More than one value is present" in str(excinfo.value.args[1]['backfill'][0])


def test_cant_instantiate_with_multiple_rfs_deployment_types():
    config = {
        "reindex_from_snapshot": {
            "docker": None,
            "ecs": {"aws_region": "us-east-1"}
        }
    }
    with pytest.raises(ValueError) as excinfo:
        get_backfill(config, create_valid_cluster(), create_valid_cluster())
    assert "Invalid config file for RFS backfill" in str(excinfo.value.args[0])
    assert "More than one value is present" in str(excinfo.value.args[1]['reindex_from_snapshot'][0])
