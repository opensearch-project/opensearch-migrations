from kubernetes import config
import pathlib

import pytest

from console_link.middleware.backfill import describe
from console_link.models.backfill_rfs import DockerRFSBackfill, ECSRFSBackfill, K8sRFSBackfill
from console_link.models.factories import get_backfill
from tests.utils import create_valid_cluster


TEST_DATA_DIRECTORY = pathlib.Path(__file__).parent / "data"
AWS_REGION = "us-east-1"


@pytest.fixture(autouse=True)
def mock_kube_config(monkeypatch):
    # Prevent actual config loading
    monkeypatch.setattr(config, "load_incluster_config", lambda: None)
    monkeypatch.setattr(config, "load_kube_config", lambda: None)


@pytest.fixture
def docker_rfs_backfill() -> DockerRFSBackfill:
    docker_rfs_config = {
        "reindex_from_snapshot": {
            "docker": None
        }
    }
    return get_backfill(docker_rfs_config, create_valid_cluster())


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
    return get_backfill(ecs_rfs_config, create_valid_cluster())


@pytest.fixture
def k8s_rfs_backfill() -> ECSRFSBackfill:
    k8s_rfs_config = {
        "reindex_from_snapshot": {
            "k8s": {
                "namespace": "ma",
                "deployment_name": "ma-backfill-rfs"
            }
        }
    }
    return get_backfill(k8s_rfs_config, create_valid_cluster())


def test_backfill_describe_includes_salient_details_docker_rfs(docker_rfs_backfill: DockerRFSBackfill):
    # I'm trying to be quite non-prescriptive about what should be included in describe
    # but at a minimum, the backfill strategy and deployment type need to be present.
    result = describe(docker_rfs_backfill)
    description = result[1]
    assert "reindex_from_snapshot" in description
    assert "docker" in description

    assert "ecs" not in description
    assert "opensearch_ingestion" not in description
    assert "k8s" not in description


def test_backfill_describe_includes_salient_details_ecs_rfs(ecs_rfs_backfill: ECSRFSBackfill):
    # I'm trying to be quite non-prescriptive about what should be included in describe
    # but at a minimum, the backfill strategy and deployment type need to be present.
    result = describe(ecs_rfs_backfill)
    description = result[1]
    assert "reindex_from_snapshot" in description
    assert "ecs" in description
    assert ecs_rfs_backfill.ecs_config.get("service_name") in description

    assert "docker" not in description
    assert "opensearch_ingestion" not in description
    assert "k8s" not in description


def test_backfill_describe_includes_salient_details_k8s_rfs(k8s_rfs_backfill: K8sRFSBackfill):
    # I'm trying to be quite non-prescriptive about what should be included in describe
    # but at a minimum, the backfill strategy and deployment type need to be present.
    result = describe(k8s_rfs_backfill)
    description = result[1]
    assert "reindex_from_snapshot" in description
    assert "k8s" in description
    assert k8s_rfs_backfill.k8s_config.get("deployment_name") in description

    assert "docker" not in description
    assert "opensearch_ingestion" not in description
    assert "ecs" not in description
