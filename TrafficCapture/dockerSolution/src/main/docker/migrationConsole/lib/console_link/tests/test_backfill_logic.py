import pathlib

import pytest

from console_link.logic.backfill import get_backfill, describe
from console_link.models.backfill_osi import OpenSearchIngestionBackfill
from console_link.models.backfill_rfs import DockerRFSBackfill, ECSRFSBackfill

from tests.utils import create_valid_cluster

TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
AWS_REGION = "us-east-1"


@pytest.fixture
def docker_rfs_backfill() -> DockerRFSBackfill:
    docker_rfs_config = {
        "reindex_from_snapshot": {
            "docker": None
        }
    }
    return get_backfill(docker_rfs_config, None, create_valid_cluster())


@pytest.fixture
def ecs_rfs_backfill() -> ECSRFSBackfill:
    ecs_rfs_config = {
        "reindex_from_snapshot": {
            "ecs": {
                "cluster_name": "migration-aws-integ-ecs-cluster",
                "service_name": "migration-aws-integ-reindex-from-snapshot"
            }
        }
    }
    return get_backfill(ecs_rfs_config, None, create_valid_cluster())


@pytest.fixture
def osi_backfill() -> OpenSearchIngestionBackfill:
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
    return get_backfill(osi_config, create_valid_cluster(), create_valid_cluster())


def test_backfill_describe_includes_salient_details_docker_rfs(docker_rfs_backfill: DockerRFSBackfill):
    # I'm trying to be quite non-prescriptive about what should be included in describe
    # but at a minimum, the backfill strategy and deployment type need to be present.
    description = describe(docker_rfs_backfill)
    assert "reindex_from_snapshot" in description
    assert "docker" in description

    assert "ecs" not in description
    assert "opensearch_ingestion" not in description


def test_backfill_describe_includes_salient_details_ecs_rfs(ecs_rfs_backfill: ECSRFSBackfill):
    # I'm trying to be quite non-prescriptive about what should be included in describe
    # but at a minimum, the backfill strategy and deployment type need to be present.
    description = describe(ecs_rfs_backfill)
    assert "reindex_from_snapshot" in description
    assert "ecs" in description
    assert ecs_rfs_backfill.ecs_config.get("service_name") in description

    assert "docker" not in description
    assert "opensearch_ingestion" not in description


def test_backfill_describe_includes_salient_details_osi(osi_backfill: OpenSearchIngestionBackfill):
    # I'm trying to be quite non-prescriptive about what should be included in describe
    # but at a minimum, the backfill strategy and deployment type need to be present.
    description = describe(osi_backfill)
    assert "opensearch_ingestion" in description
    assert "unit-test-pipeline" in description
    assert "us-west-2" in description
    assert "migration_deployment=1.0.0" in description

    assert "docker" not in description
    assert "ecs" not in description
    assert "reindex_from_snapshot" not in description
