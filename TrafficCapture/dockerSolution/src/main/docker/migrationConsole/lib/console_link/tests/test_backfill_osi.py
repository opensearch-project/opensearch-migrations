import os

import pytest  # type: ignore

from console_link.models.backfill_osi import OpenSearchIngestionBackfill
from tests.utils import create_valid_cluster

# Define a valid cluster configuration
valid_osi_migration_config = {
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
# Set mock AWS region for unit tests
os.environ['AWS_DEFAULT_REGION'] = 'us-east-1'
mock_cluster = create_valid_cluster()


def missing_attribute_test_helper(config, attribute):
    with pytest.raises(ValueError) as excinfo:
        OpenSearchIngestionBackfill(config, source_cluster=mock_cluster, target_cluster=mock_cluster)
    assert excinfo.value.args[1][attribute] == ["required field"]


def test_valid_full_osi_migration_config():
    migration = OpenSearchIngestionBackfill(valid_osi_migration_config, source_cluster=mock_cluster,
                                            target_cluster=mock_cluster)
    assert isinstance(migration, OpenSearchIngestionBackfill)


def test_valid_min_osi_migration_config():
    min_valid_osi_migration_config = {
        "opensearch_ingestion": {
            "pipeline_role_arn": "arn:aws:iam::123456789012:role/OSMigrations-pipelineRole",
            "vpc_subnet_ids": [
                "subnet-024004957a02ce923"
            ],
            "security_group_ids": [
                "sg-04536940716d101f6"
            ],
            "aws_region": "us-west-2"
        }
    }
    migration = OpenSearchIngestionBackfill(min_valid_osi_migration_config, source_cluster=mock_cluster,
                                            target_cluster=mock_cluster)
    assert isinstance(migration, OpenSearchIngestionBackfill)


def test_osi_missing_pipeline_role_refused():
    missing_attribute_config = {
        "opensearch_ingestion": {
            "vpc_subnet_ids": [
                "subnet-024004957a02ce923"
            ],
            "security_group_ids": [
                "sg-04536940716d101f6"
            ],
            "aws_region": "us-west-2"
        }
    }
    missing_attribute_test_helper(config=missing_attribute_config, attribute="pipeline_role_arn")


def test_osi_missing_subnets_refused():
    missing_attribute_config = {
        "opensearch_ingestion": {
            "pipeline_role_arn": "arn:aws:iam::123456789012:role/OSMigrations-pipelineRole",
            "security_group_ids": [
                "sg-04536940716d101f6"
            ],
            "aws_region": "us-west-2"
        }
    }
    missing_attribute_test_helper(config=missing_attribute_config, attribute="vpc_subnet_ids")


def test_osi_missing_security_groups_refused():
    missing_attribute_config = {
        "opensearch_ingestion": {
            "pipeline_role_arn": "arn:aws:iam::123456789012:role/OSMigrations-pipelineRole",
            "vpc_subnet_ids": [
                "subnet-024004957a02ce923"
            ],
            "aws_region": "us-west-2"
        }
    }
    missing_attribute_test_helper(config=missing_attribute_config, attribute="security_group_ids")


def test_osi_missing_aws_region_refused():
    missing_attribute_config = {
        "opensearch_ingestion": {
            "pipeline_role_arn": "arn:aws:iam::123456789012:role/OSMigrations-pipelineRole",
            "vpc_subnet_ids": [
                "subnet-024004957a02ce923"
            ],
            "security_group_ids": [
                "sg-04536940716d101f6"
            ]
        }
    }
    missing_attribute_test_helper(config=missing_attribute_config, attribute="aws_region")
