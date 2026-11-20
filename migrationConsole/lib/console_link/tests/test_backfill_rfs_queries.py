import json
import logging
import os
import tempfile
from datetime import datetime, timezone
from typing import List

import pytest
import yaml

from console_link.environment import Environment
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models import backfill_rfs
from console_link.models.backfill_rfs import get_detailed_status_obj, BackfillOverallStatus
from console_link.models.step_state import StepStateWithPause
from tests.search_containers import SearchContainer, Version

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)
WORKING_STATE_INDEX = ".migrations_working_state"
# Constants for test configuration
TEST_VERSION = Version("OPENSEARCH", 2, 19, 1)


def create_working_state_index(cluster: Cluster):
    """Create the migrations working state index with appropriate mappings."""
    mapping = {
        "mappings": {
            "properties": {
                "expiration": {"type": "long"},
                "completedAt": {"type": "long"},
                "leaseHolderId": {"type": "keyword", "norms": False},
                "successor_items": {"type": "keyword", "norms": False},
                "status": {"type": "keyword", "norms": False}
            }
        },
        "settings": {
            "index": {
                "number_of_shards": 1,
                "number_of_replicas": 0
            }
        }
    }

    cluster.call_api(
        f"/{WORKING_STATE_INDEX}",
        HttpMethod.PUT,
        data=json.dumps(mapping),
        headers={"Content-Type": "application/json"}
    )

    # Wait for the index to be created and available
    cluster.call_api("/_refresh", HttpMethod.POST)


# Time constants
current_time = int(datetime.now(timezone.utc).timestamp())
future_time = current_time + 3600  # 1 hour in the future
past_time = current_time - 3600    # 1 hour in the past

# Document count constants for test data
SHARD_COUNT = 2
COMPLETED_DOC_COUNT = 5
IN_PROGRESS_DOC_COUNT = 3
UNCLAIMED_DOC_COUNT = 2
IN_PROGRESS_SUCCESSOR_COUNT = 1


class WorkingStateDoc:
    index: str
    shard: int
    starting_doc_id: int
    completed: bool
    expired: bool
    status: str
    successors: str | None

    def __init__(self,
                 index: str,
                 shard: int,
                 starting_doc_id: int,
                 completed: bool,
                 expired: bool,
                 status: str,
                 successors: str | None = None
                 ) -> None:
        self.index = index
        self.shard = shard
        self.starting_doc_id = starting_doc_id
        self.completed = completed
        self.expired = expired
        self.status = status
        self.successors = successors

    def get_id(self):
        return f"{self.index}__{self.shard}__{self.starting_doc_id}"

    def body(self):
        return json.dumps({
            "expiration": past_time if self.expired else future_time,
            "completedAt": past_time if self.completed else None,
            "successor_items": self.successors,
            "status": self.status
        })


"""
{
    "_index": ".migrations_working_state",
    "_id": "logs-201998__1__1",
    "_score": 1.0,
    "_source": {
        "scriptVersion": "2.0",
        "nextAcquisitionLeaseExponent": 0,
        "creatorId": "ma-bulk-document-loader-88c85578f-rfn5c_05b46f5d-4a6e-4caa-bcb9-06a3620c20e4"
    }
}
"""


def create_test_documents(cluster: Cluster):
    """
    Create test documents for backfill testing.

    This function creates different types of test documents:
    1. Completed documents - documents that have been processed
    2. In-progress documents - documents that are currently being processed
    3. Unclaimed documents - documents that are waiting to be processed
    4. Successor documents - documents that have successors

    Args:
        cluster: The cluster to create documents in
    """
    docs_to_create: List[WorkingStateDoc] = []
    index_counter = 0

    # Create completed documents
    for i in range(COMPLETED_DOC_COUNT):
        index_counter += 1
        for shard in range(SHARD_COUNT):
            docs_to_create.append(WorkingStateDoc(
                index=f"index{index_counter}",
                shard=shard,
                completed=True,
                expired=False,
                starting_doc_id=0,
                status="Completed"
            ))

    # Create in-progress documents
    for i in range(IN_PROGRESS_DOC_COUNT):
        index_counter += 1
        for shard in range(SHARD_COUNT):
            docs_to_create.append(WorkingStateDoc(
                index=f"index{index_counter}",
                shard=shard,
                completed=False,
                expired=False,
                starting_doc_id=0,
                status="in_progress"
            ))

    # Create unclaimed documents
    for i in range(UNCLAIMED_DOC_COUNT):
        index_counter += 1
        for shard in range(SHARD_COUNT):
            docs_to_create.append(WorkingStateDoc(
                index=f"index{index_counter}",
                shard=shard,
                completed=False,
                expired=True,
                starting_doc_id=0,
                status="unclaimed"
            ))

    # Create successor documents
    index_counter += 1
    successor_index = f"index{index_counter}"

    # Add document with successor
    docs_to_create.append(WorkingStateDoc(
        index=successor_index,
        shard=0,
        completed=True,
        expired=False,
        starting_doc_id=0,
        successors="1",
        status="successor_completed"
    ))

    # Add successor document
    docs_to_create.append(WorkingStateDoc(
        index=successor_index,
        shard=0,
        completed=False,
        expired=False,
        starting_doc_id=1,
        status="successor_in_progress"
    ))

    # Index all documents
    for work_item in docs_to_create:
        cluster.call_api(
            f"/{WORKING_STATE_INDEX}/_doc/{work_item.get_id()}",
            HttpMethod.PUT,
            data=work_item.body(),
            headers={"Content-Type": "application/json"}
        )

    # Add a shard_setup document (should be excluded from counts)
    setup_doc = {
        "completedAt": current_time,
        "status": "completed"
    }
    cluster.call_api(
        f"/{WORKING_STATE_INDEX}/_doc/shard_setup",
        HttpMethod.PUT,
        data=json.dumps(setup_doc),
        headers={"Content-Type": "application/json"}
    )

    # Refresh the index to make sure all documents are searchable
    cluster.call_api("/_refresh", HttpMethod.POST)
    response = cluster.call_api(
        f"/{WORKING_STATE_INDEX}/_search?size=100",
        HttpMethod.GET,
        headers={"Content-Type": "application/json"}
    )
    logger.error(f"Raw document info: {json.dumps(response.json())}")


@pytest.fixture()
def env_with_cluster(request):
    """Fixture to set up a test cluster and environment."""
    version = request.param

    # Spin up the Elasticsearch/OpenSearch container
    container = SearchContainer(version, mem_limit="3G")
    container.start()

    base_url = f"http://{container.get_container_host_ip()}:{container.get_exposed_port(9200)}"

    # Create a temporary services.yaml file
    services_config = {
        'source_cluster': {
            'endpoint': "http://source_endpoint_not_used:9200",
            'allow_insecure': True,
            'no_auth': {}
        },
        'target_cluster': {
            'endpoint': base_url,
            'allow_insecure': True,
            'no_auth': {}
        }
    }

    with tempfile.NamedTemporaryFile(mode='w', delete=False) as temp_config:
        yaml.dump(services_config, temp_config)
        temp_config_path = temp_config.name

    # Create the environment with our configuration
    env = Environment(config_file=temp_config_path)

    yield env

    # Clean up after the test
    container.stop()
    os.remove(temp_config_path)


@pytest.mark.slow
@pytest.mark.parametrize(
    "env_with_cluster",
    [TEST_VERSION],
    indirect=True
)
def test_get_detailed_status_obj(env_with_cluster: Environment):
    assert env_with_cluster.target_cluster is not None
    target_cluster = env_with_cluster.target_cluster

    create_working_state_index(target_cluster)
    create_test_documents(target_cluster)

    status_obj = get_detailed_status_obj(target_cluster)

    # Verify results
    assert isinstance(status_obj, BackfillOverallStatus), "Expected BackfillOverallStatus object"
    computed_total = (COMPLETED_DOC_COUNT * SHARD_COUNT +
                      IN_PROGRESS_DOC_COUNT * SHARD_COUNT +
                      UNCLAIMED_DOC_COUNT * SHARD_COUNT +
                      IN_PROGRESS_SUCCESSOR_COUNT)
    assert status_obj.shard_total == computed_total, f"{status_obj}"
    assert status_obj.shard_complete == COMPLETED_DOC_COUNT * SHARD_COUNT, f"{status_obj}"
    assert status_obj.shard_in_progress == (IN_PROGRESS_DOC_COUNT * SHARD_COUNT +
                                            IN_PROGRESS_SUCCESSOR_COUNT), f"{status_obj}"
    assert status_obj.shard_waiting == UNCLAIMED_DOC_COUNT * SHARD_COUNT, f"{status_obj}"


def create_all_completed_working_state(cluster: Cluster):
    """Working state where every shard is completed (total == completed)."""
    docs = [
        WorkingStateDoc(index=f"index{i}", shard=shard, starting_doc_id=0,
                        completed=True, expired=False, status="Completed")
        for i in range(2) for shard in range(SHARD_COUNT)
    ]
    for doc in docs:
        cluster.call_api(f"/{WORKING_STATE_INDEX}/_doc/{doc.get_id()}", HttpMethod.PUT,
                         data=doc.body(), headers={"Content-Type": "application/json"})
    cluster.call_api(f"/{WORKING_STATE_INDEX}/_doc/shard_setup", HttpMethod.PUT,
                     data=json.dumps({"completedAt": current_time, "status": "completed"}),
                     headers={"Content-Type": "application/json"})
    cluster.call_api("/_refresh", HttpMethod.POST)


@pytest.mark.slow
@pytest.mark.parametrize("env_with_cluster", [TEST_VERSION], indirect=True)
def test_get_detailed_status_obj_completed_with_failed_documents(env_with_cluster: Environment):
    # End-to-end: a fully completed backfill is COMPLETED_WITH_ERRORS iff failed documents exist.
    target_cluster = env_with_cluster.target_cluster
    create_working_state_index(target_cluster)
    create_all_completed_working_state(target_cluster)

    with_failures = get_detailed_status_obj(target_cluster, has_failed_documents=True)
    assert with_failures.status == StepStateWithPause.COMPLETED_WITH_ERRORS

    without_failures = get_detailed_status_obj(target_cluster, has_failed_documents=False)
    assert without_failures.status == StepStateWithPause.COMPLETED


def put_work_item(cluster: Cluster, index: str, shard: int, from_position: int, next_position: int,
                  completed: bool, non_retryable: int = 0, retries_exhausted: int = 0,
                  shard_complete: bool = False, successor: str | None = None):
    """A work item as RFS commits it: completedAt plus committed_metrics (omitted while it's in flight)."""
    body = {"expiration": future_time, "completedAt": past_time if completed else None,
            "successor_items": successor, "status": "Completed" if completed else "InProgress"}
    if completed:
        body["committed_metrics"] = {
            "schemaVersion": 1, "snapshotName": "snap", "indexName": index, "shardNumber": shard,
            "fromPosition": from_position, "nextPosition": next_position, "shardComplete": shard_complete,
            "metrics": {"failures": {"nonRetryable": non_retryable, "retryableExhausted": retries_exhausted},
                        "workItem": {"durationMillis": 1000}},
        }
    cluster.call_api(f"/{WORKING_STATE_INDEX}/_doc/{index}__{shard}__{from_position}", HttpMethod.PUT,
                     data=json.dumps(body), headers={"Content-Type": "application/json"})


def create_working_state_with_failures(cluster: Cluster):
    cluster.call_api(f"/{WORKING_STATE_INDEX}/_doc/shard_setup", HttpMethod.PUT,
                     data=json.dumps({"completedAt": current_time, "status": "completed"}),
                     headers={"Content-Type": "application/json"})
    # idx-a shard 0 was checkpointed: its first work item failed 3 documents, its successor none.
    put_work_item(cluster, "idx-a", 0, 0, 100, True, non_retryable=2, retries_exhausted=1,
                  successor="idx-a__0__100")
    put_work_item(cluster, "idx-a", 0, 100, 200, True, shard_complete=True)
    put_work_item(cluster, "idx-a", 1, 0, 50, True, shard_complete=True)
    put_work_item(cluster, "idx-b", 0, 0, 80, True, non_retryable=5, shard_complete=True)
    cluster.call_api("/_refresh", HttpMethod.POST)


@pytest.mark.slow
@pytest.mark.parametrize("env_with_cluster", [TEST_VERSION], indirect=True)
@pytest.mark.parametrize("index_name_mapping", [None, {"type": "keyword"}], ids=["dynamic", "keyword"])
def test_get_detailed_status_obj_sums_failed_documents_from_work_items(env_with_cluster: Environment,
                                                                       index_name_mapping):
    # The index name is aggregatable either way it can be mapped: dynamically (text + .keyword) or as keyword.
    target_cluster = env_with_cluster.target_cluster
    create_working_state_index(target_cluster)
    if index_name_mapping:
        target_cluster.call_api(f"/{WORKING_STATE_INDEX}/_mapping", HttpMethod.PUT, data=json.dumps(
            {"properties": {"committed_metrics": {"properties": {"indexName": index_name_mapping}}}}),
            headers={"Content-Type": "application/json"})
    create_working_state_with_failures(target_cluster)
    # A shard still in flight: nothing committed yet, so nothing counted.
    put_work_item(target_cluster, "idx-b", 1, 0, 0, completed=False)
    target_cluster.call_api("/_refresh", HttpMethod.POST)

    status = get_detailed_status_obj(target_cluster)

    assert (status.documents_failed, status.documents_failed_non_retryable,
            status.documents_failed_retries_exhausted) == (8, 7, 1), f"{status}"
    assert [(s.index, s.shard, s.non_retryable, s.retries_exhausted) for s in status.shards_with_failures] == [
        ("idx-b", 0, 5, 0), ("idx-a", 0, 2, 1)], f"{status}"
    assert status.status == StepStateWithPause.RUNNING


@pytest.mark.slow
@pytest.mark.parametrize("env_with_cluster", [TEST_VERSION], indirect=True)
def test_completed_backfill_with_counted_failures_is_completed_with_errors_without_a_stream(
        env_with_cluster: Environment):
    # No failed document stream configured (has_failed_documents=None): the work items' count decides.
    target_cluster = env_with_cluster.target_cluster
    create_working_state_index(target_cluster)
    create_working_state_with_failures(target_cluster)

    status = get_detailed_status_obj(target_cluster, has_failed_documents=None)

    assert status.status == StepStateWithPause.COMPLETED_WITH_ERRORS, f"{status}"
    assert status.documents_failed == 8


@pytest.mark.slow
@pytest.mark.parametrize("env_with_cluster", [TEST_VERSION], indirect=True)
def test_status_sees_work_items_committed_since_the_last_refresh(env_with_cluster: Environment):
    # RFS creates the index with a 30s refresh interval and refreshes it only for its own checks.
    target_cluster = env_with_cluster.target_cluster
    create_working_state_index(target_cluster)
    target_cluster.call_api(f"/{WORKING_STATE_INDEX}/_settings", HttpMethod.PUT,
                            data=json.dumps({"index": {"refresh_interval": "30s"}}),
                            headers={"Content-Type": "application/json"})
    target_cluster.call_api(f"/{WORKING_STATE_INDEX}/_doc/shard_setup", HttpMethod.PUT,
                            data=json.dumps({"completedAt": current_time, "status": "completed"}),
                            headers={"Content-Type": "application/json"})
    put_work_item(target_cluster, "idx-b", 0, 0, 80, True, non_retryable=5, shard_complete=True)  # not refreshed

    status = get_detailed_status_obj(target_cluster)

    assert (status.status, status.documents_failed) == (StepStateWithPause.COMPLETED_WITH_ERRORS, 5), f"{status}"


@pytest.mark.slow
@pytest.mark.parametrize("env_with_cluster", [TEST_VERSION], indirect=True)
def test_finished_backfill_stays_running_until_its_failed_documents_can_be_counted(
        env_with_cluster: Environment, monkeypatch):
    target_cluster = env_with_cluster.target_cluster
    create_working_state_index(target_cluster)
    create_working_state_with_failures(target_cluster)

    def unreachable(*args, **kwargs):
        raise ConnectionError("coordinator unreachable")
    monkeypatch.setattr(backfill_rfs, "_search", unreachable)  # only the failed-document queries use it

    status = get_detailed_status_obj(target_cluster)

    assert (status.status, status.percentage_completed, status.finished) == (StepStateWithPause.RUNNING, 100.0, None)
    assert status.documents_failed is None
    assert status.failed_documents_count_error == "ConnectionError: coordinator unreachable"


@pytest.mark.slow
@pytest.mark.parametrize("env_with_cluster", [TEST_VERSION], indirect=True)
def test_failed_documents_are_zero_before_any_work_item_commits(env_with_cluster: Environment):
    target_cluster = env_with_cluster.target_cluster
    create_working_state_index(target_cluster)
    create_test_documents(target_cluster)

    status = get_detailed_status_obj(target_cluster)

    assert (status.documents_failed, status.shards_with_failures) == (0, []), f"{status}"
