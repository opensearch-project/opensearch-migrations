import json
from kubernetes import config
import pathlib

import pytest

from console_link.middleware.backfill import describe
from console_link.models.backfill_rfs import DockerRFSBackfill, K8sRFSBackfill
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
def k8s_rfs_backfill() -> K8sRFSBackfill:
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


def test_deep_status_text_lists_failed_documents_overall_and_per_shard(monkeypatch):
    from console_link.models import backfill_rfs
    from console_link.models.backfill_base import BackfillOverallStatus, ShardFailedDocuments
    from console_link.models.step_state import StepStateWithPause

    status = BackfillOverallStatus(
        status=StepStateWithPause.RUNNING, percentage_completed=50.0, shard_total=4, shard_complete=2,
        shard_in_progress=1, shard_waiting=1, documents_failed=8, documents_failed_non_retryable=7,
        documents_failed_retries_exhausted=1,
        shards_with_failures=[ShardFailedDocuments(index="idx-b", shard=0, non_retryable=5, retries_exhausted=0),
                              ShardFailedDocuments(index="idx-a", shard=0, non_retryable=2, retries_exhausted=1)])
    monkeypatch.setattr(backfill_rfs, "get_detailed_status_obj", lambda **kwargs: status)

    lines = backfill_rfs.get_detailed_status(target_cluster=None, session_name="s").splitlines()

    assert lines[-3:] == [
        "Failed documents: 8 (non-retryable 7, retries exhausted 1)",
        "  idx-b shard 0: 5 (non-retryable 5, retries exhausted 0)",
        "  idx-a shard 0: 3 (non-retryable 2, retries exhausted 1)",
    ]


def test_deep_status_text_says_failed_documents_could_not_be_counted(monkeypatch):
    from console_link.models import backfill_rfs
    from console_link.models.backfill_base import BackfillOverallStatus
    from console_link.models.step_state import StepStateWithPause

    status = BackfillOverallStatus(status=StepStateWithPause.RUNNING, percentage_completed=100.0, shard_total=4,
                                   shard_complete=4, shard_in_progress=0, shard_waiting=0,
                                   failed_documents_count_error="ConnectionError: coordinator unreachable")
    monkeypatch.setattr(backfill_rfs, "get_detailed_status_obj", lambda **kwargs: status)

    text = backfill_rfs.get_detailed_status(target_cluster=None, session_name="s")

    assert text.splitlines()[-1] == "Failed documents: couldn't be counted (ConnectionError: coordinator unreachable)"


class _RecordingCluster:
    """Answers the status's working-state requests with a finished backfill and records each one."""

    def __init__(self):
        self.requests = []

    def call_api(self, path, method=None, data=None, headers=None, **kwargs):
        from unittest.mock import MagicMock
        self.requests.append((getattr(method, "name", "GET"), path))
        body = json.loads(data) if data else {}
        aggs = body.get("aggs") or {}
        if "unique_pair_count" in aggs:  # every progress query: 2 shards, so total == completed (finished)
            result = {"aggregations": {"unique_pair_count": {"value": 2}}, "hits": {"total": {"value": 2}, "hits": []}}
        elif "shards" in aggs:
            result = {"aggregations": {"shards": {"buckets": []}}, "_shards": {"failed": 0}}
        elif "non_retryable" in aggs:
            result = {"aggregations": {"non_retryable": {"value": 0}, "retries_exhausted": {"value": 0}},
                      "_shards": {"failed": 0}}
        elif "max_completed" in aggs:
            result = {"aggregations": {"max_completed": {"value": 1700000000}}}
        elif "_mapping" in path:
            result = {}
        else:
            result = {"hits": {"total": {"value": 1}, "hits": [{"_source": {"completedAt": 1700000000}}]},
                      "_source": {"completedAt": 1700000000}}
        response = MagicMock()
        response.json.return_value = result
        return response


def test_status_refreshes_the_working_state_first_and_reads_one_copy_of_it():
    from console_link.models import backfill_rfs

    cluster = _RecordingCluster()
    backfill_rfs.get_detailed_status_obj(cluster, session_name="s")

    searches = [i for i, (_, path) in enumerate(cluster.requests) if "/_search" in path]
    refresh = cluster.requests.index(("POST", "/.migrations_working_state_s/_refresh"))
    assert searches and refresh < searches[0], cluster.requests
    assert all(cluster.requests[i][1].endswith("/_search?preference=" + backfill_rfs.STATUS_SEARCH_PREFERENCE)
               for i in searches), cluster.requests


@pytest.mark.parametrize("incomplete", [{"timed_out": True}, {"_shards": {"total": 1, "failed": 1}}],
                         ids=["timed-out", "shard-failed"])
def test_failed_document_count_refuses_a_partial_search(incomplete):
    from unittest.mock import MagicMock
    from console_link.models import backfill_rfs

    cluster = MagicMock()
    cluster.call_api.return_value.json.return_value = {
        "aggregations": {"non_retryable": {"value": 1}, "retries_exhausted": {"value": 0}}, **incomplete}

    with pytest.raises(backfill_rfs.FailedDocumentCountUnavailable, match="incomplete search"):
        backfill_rfs.get_failed_document_counts(cluster, ".migrations_working_state")


def test_deep_status_text_omits_failed_documents_when_they_could_not_be_counted(monkeypatch):
    from console_link.models import backfill_rfs
    from console_link.models.backfill_base import BackfillOverallStatus
    from console_link.models.step_state import StepStateWithPause

    status = BackfillOverallStatus(status=StepStateWithPause.RUNNING, percentage_completed=50.0, shard_total=4,
                                   shard_complete=2, shard_in_progress=1, shard_waiting=1)
    monkeypatch.setattr(backfill_rfs, "get_detailed_status_obj", lambda **kwargs: status)

    text = backfill_rfs.get_detailed_status(target_cluster=None, session_name="s")

    assert text.splitlines()[-1] == "Waiting shards: 1" and "Failed documents" not in text
