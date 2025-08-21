import json
import logging
import os
import tempfile
from datetime import datetime, timezone
from typing import List

from colorama import init

import pytest
import yaml

from console_link.environment import Environment
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.backfill_rfs import generate_status_queries, get_detailed_status_obj, BackfillOverallStatus
from tests.search_containers import SearchContainer, Version

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

# Test constants
WORKING_STATE_INDEX = ".migrations_working_state"


# We'll test with both Elasticsearch and OpenSearch versions
TEST_VERSIONS = [
    Version("OPENSEARCH", 2, 19, 1)
]


def create_working_state_index(cluster: Cluster):
    """Create the migrations working state index with appropriate mappings."""
    mapping = {
        "mappings": {
            "properties": {
                "expiration": {"type": "long"},
                "completedAt": {"type": "long"},
                "leaseHolderId": {"type": "keyword", "norms": False},
                "successor_items": {"type": "keyword", "norms": False}
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

current_time = int(datetime.now(timezone.utc).timestamp())
future_time = current_time + 3600  # 1 hour in the future
past_time = current_time - 3600    # 1 hour in the past


class WorkingStateDoc:
    index: str
    shard: int
    starting_doc_id: int
    completed: bool
    expired: bool
    active: bool

    def __init__(self, index: str, shard: int, starting_doc_id: int, completed: bool, expired: bool, active: bool) -> None:
        self.index = index
        self.shard = shard
        self.starting_doc_id = starting_doc_id
        self.completed = completed
        self.expired = expired
        self.active = active
        pass
    
    def get_id(self):
        return f"{self.index}__{self.shard}__{self.starting_doc_id}"
    
    def body(self):
        return json.dumps({
            "experation": past_time if self.expired else future_time,
            "completedAt": past_time if self.completed else None
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

    index_count = 7
    shard_count = 2
    docsToCreate: List[WorkingStateDoc] = []
    running_index = 0

    for index_i in range(index_count):
        index = f"index{running_index := running_index + 1}"
        for shard_i in range(shard_count):
            docsToCreate.append(WorkingStateDoc(
                index=index,
                shard=shard_i,
                completed=True,
                expired=False,
                starting_doc_id=0,
                active=False
            ))

    in_progress_count = 5
    for index_i in range(in_progress_count):
        index = f"index{running_index := running_index + 1}"
        for shard_i in range(shard_count):
            docsToCreate.append(WorkingStateDoc(
                index=f"index{index_i + index_count}",
                shard=shard_i,
                completed=False,
                expired=False,
                starting_doc_id=0,
                active=False
            ))

    unclaimed_count = 3
    for index_i in range(unclaimed_count):
        index = f"index{running_index := running_index + 1}"
        for shard_i in range(shard_count):
            docsToCreate.append(WorkingStateDoc(
                index=f"index{index_i + index_count}",
                shard=shard_i,
                completed=False,
                expired=True,
                starting_doc_id=0,
                active=False
            ))

    sucessor_count = 2
    for index_i in range(unclaimed_count):
        index = f"index{running_index := running_index + 1}"
        for shard_i in range(shard_count):
            docsToCreate.append(WorkingStateDoc(
                index=f"index{index_i + index_count}",
                shard=shard_i,
                completed=False,
                expired=True,
                starting_doc_id=0,
                active=False
            ))


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
    
    # Add completed documents
    for work_item in docsToCreate:
        cluster.call_api(
            f"/{WORKING_STATE_INDEX}/_doc/{work_item.get_id()}",
            HttpMethod.PUT,
            data=work_item.body(),
            headers={"Content-Type": "application/json"}
        )
    
    # Add in-progress documents (with future expiration)
    for i in range(config["in_progress_count"]):
        idx = i + config["completed_count"]
        work_item = {
            "indexName": f"index{idx % 3}",
            "shardId": idx % 5,
            "expiration": future_time,
            "startedAt": current_time,
            "status": "in_progress"
        }
        doc_id = f"index{idx % 3}__shard{idx % 5}__{idx}"
        cluster.call_api(
            f"/{WORKING_STATE_INDEX}/_doc/{doc_id}",
            HttpMethod.PUT,
            data=json.dumps(work_item),
            headers={"Content-Type": "application/json"}
        )
    
    # Add unclaimed documents (with past expiration)
    for i in range(config["unclaimed_count"]):
        idx = i + config["completed_count"] + config["in_progress_count"]
        work_item = {
            "indexName": f"index{idx % 3}",
            "shardId": idx % 5,
            "expiration": past_time,
            "status": "unclaimed"
        }
        doc_id = f"index{idx % 3}__shard{idx % 5}__{idx}"
        cluster.call_api(
            f"/{WORKING_STATE_INDEX}/_doc/{doc_id}",
            HttpMethod.PUT,
            data=json.dumps(work_item),
            headers={"Content-Type": "application/json"}
        )

    # Refresh the index to make sure all documents are searchable
    cluster.call_api("/_refresh", HttpMethod.POST)


def execute_query_and_verify(cluster: Cluster, query, expected_count, label):
    """Execute a query and verify the result matches the expected count."""
    response = cluster.call_api(
        f"/{WORKING_STATE_INDEX}/_search",
        HttpMethod.POST,
        data=json.dumps(query),
        headers={"Content-Type": "application/json"}
    )
    body = response.json()
    
    # Extract the count from the aggregation or hits
    if "aggregations" in body and "unique_pair_count" in body["aggregations"]:
        actual_count = body["aggregations"]["unique_pair_count"]["value"]
    else:
        actual_count = body["hits"]["total"]["value"]
    
    assert actual_count == expected_count, f"Expected {expected_count} {label} documents, got {actual_count}"
    logger.info(f"{label} query returned {actual_count} as expected")
    return actual_count

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


@pytest.mark.parametrize(
    "env_with_cluster,doc_config",
    [
        # Test with Elasticsearch 7.10.2
        (TEST_VERSIONS[0], {"completed_count": 5, "in_progress_count": 3, "unclaimed_count": 2}),
        # Test with OpenSearch 2.19.1
        (TEST_VERSIONS[1], {"completed_count": 5, "in_progress_count": 3, "unclaimed_count": 2}),
        # Edge case: all completed
        (TEST_VERSIONS[0], {"completed_count": 10, "in_progress_count": 0, "unclaimed_count": 0}),
        # Edge case: all in progress
        (TEST_VERSIONS[0], {"completed_count": 0, "in_progress_count": 10, "unclaimed_count": 0}),
        # Edge case: all unclaimed
        (TEST_VERSIONS[0], {"completed_count": 0, "in_progress_count": 0, "unclaimed_count": 10}),
        # Edge case: empty index (just the shard_setup document)
        (TEST_VERSIONS[0], {"completed_count": 0, "in_progress_count": 0, "unclaimed_count": 0}),
    ],
    indirect=["env_with_cluster"]
)
def test_generate_status_queries(env_with_cluster: Environment, doc_config):
    """
    Test the generate_status_queries function with various document configurations.
    
    This test:
    1. Sets up the working state index
    2. Creates test documents according to the configuration
    3. Generates the queries using generate_status_queries
    4. Executes each query and verifies the results
    """
    logger.info(f"Testing with document config: {doc_config}")
    
    # Skip test if target_cluster is None
    if env_with_cluster.target_cluster is None:
        pytest.skip("Target cluster is None, skipping test")
        
    # Create the working state index and test documents
    create_working_state_index(env_with_cluster.target_cluster)
    create_test_documents(env_with_cluster.target_cluster, doc_config)
    
    # Generate the queries
    queries = generate_status_queries()
    
    # Expected counts:
    # Total should be the sum of all documents except shard_setup
    expected_total = doc_config["completed_count"] + doc_config["in_progress_count"] + doc_config["unclaimed_count"]
    
    # Incomplete should be in_progress + unclaimed
    expected_incomplete = doc_config["in_progress_count"] + doc_config["unclaimed_count"]
    
    # Execute queries and verify results
    assert env_with_cluster.target_cluster is not None, "Target cluster should not be None at this point"
    target_cluster = env_with_cluster.target_cluster
    
    execute_query_and_verify(target_cluster, queries["total"], expected_total, "total")
    execute_query_and_verify(target_cluster, queries["incomplete"], expected_incomplete, "incomplete")
    execute_query_and_verify(
        target_cluster, 
        queries["in progress"], 
        doc_config["in_progress_count"], 
        "in_progress"
    )
    execute_query_and_verify(
        target_cluster, 
        queries["unclaimed"], 
        doc_config["unclaimed_count"], 
        "unclaimed"
    )


@pytest.mark.parametrize(
    "env_with_cluster,doc_config",
    [
        # Test with OpenSearch 2.19.1
        (TEST_VERSIONS[1], {"completed_count": 8, "in_progress_count": 5, "unclaimed_count": 3}),
    ],
    indirect=["env_with_cluster"]
)
def test_get_detailed_status_obj(env_with_cluster: Environment, doc_config):
    """
    Test the get_detailed_status_obj function.
    
    This test:
    1. Sets up the working state index
    2. Creates test documents according to the configuration
    3. Calls get_detailed_status_obj
    4. Verifies the results
    """
    logger.info(f"Testing get_detailed_status_obj with document config: {doc_config}")
    
    # Skip test if target_cluster is None
    if env_with_cluster.target_cluster is None:
        pytest.skip("Target cluster is None, skipping test")
        
    # Create the working state index and test documents
    assert env_with_cluster.target_cluster is not None, "Target cluster should not be None at this point"
    target_cluster = env_with_cluster.target_cluster
    
    create_working_state_index(target_cluster)
    create_test_documents(target_cluster, doc_config)
    
    # Call get_detailed_status_obj
    status_obj = get_detailed_status_obj(target_cluster)
    
    # Verify results
    assert isinstance(status_obj, BackfillOverallStatus), "Expected BackfillOverallStatus object"
    
    # Total should be the sum of all documents except shard_setup
    expected_total = doc_config["completed_count"] + doc_config["in_progress_count"] + doc_config["unclaimed_count"]
    assert status_obj.shard_total == expected_total, f"Expected {expected_total} total shards, got {status_obj.shard_total}"
    
    # Completed should match our config
    completed = doc_config["completed_count"]
    assert status_obj.shard_complete == completed, \
        f"Expected {completed} completed shards, got {status_obj.shard_complete}"
    
    # In progress should match our config
    in_progress = doc_config["in_progress_count"]
    assert status_obj.shard_in_progress == in_progress, \
        f"Expected {in_progress} in-progress shards, got {status_obj.shard_in_progress}"
    
    # Waiting (unclaimed) should match our config
    unclaimed = doc_config["unclaimed_count"]
    assert status_obj.shard_waiting == unclaimed, \
        f"Expected {unclaimed} waiting shards, got {status_obj.shard_waiting}"
    
    # Calculate expected completion percentage
    if expected_total > 0:
        expected_pct = doc_config["completed_count"] / expected_total * 100.0
    else:
        expected_pct = 0.0
    
    # Verify percentage is close to expected (allowing for floating point precision)
    assert abs(status_obj.percentage_completed - expected_pct) < 0.1, \
        f"Expected percentage ~{expected_pct}%, got {status_obj.percentage_completed}%"
    
    logger.info(f"get_detailed_status_obj test passed with status: {status_obj}")


@pytest.mark.parametrize(
    "env_with_cluster",
    [TEST_VERSIONS[1]],  # Just test with OpenSearch for this case
    indirect=True
)
def test_cardinality_script(env_with_cluster: Environment):
    """
    Test the EXTRACT_UNIQUE_INDEX_SHARD_SCRIPT used in the queries.
    
    This test specifically checks that the script correctly extracts unique index/shard pairs,
    even when there are multiple documents for the same index/shard pair.
    """
    # Skip test if target_cluster is None
    if env_with_cluster.target_cluster is None:
        pytest.skip("Target cluster is None, skipping test")
        
    # Create the working state index
    assert env_with_cluster.target_cluster is not None, "Target cluster should not be None at this point"
    target_cluster = env_with_cluster.target_cluster
    
    create_working_state_index(target_cluster)
    
    # Create multiple documents with the same index/shard pairs
    current_time = int(datetime.now(timezone.utc).timestamp())
    
    # Add documents for index0__shard0 (3 documents)
    for i in range(3):
        doc = {
            "indexName": "index0",
            "shardId": 0,
            "expiration": current_time + 3600,
            "status": "in_progress"
        }
        doc_id = f"index0__shard0__{i}"
        target_cluster.call_api(
            f"/{WORKING_STATE_INDEX}/_doc/{doc_id}",
            HttpMethod.PUT,
            data=json.dumps(doc),
            headers={"Content-Type": "application/json"}
        )
    
    # Add documents for index0__shard1 (2 documents)
    for i in range(2):
        doc = {
            "indexName": "index0",
            "shardId": 1,
            "expiration": current_time + 3600,
            "status": "in_progress"
        }
        doc_id = f"index0__shard1__{i}"
        target_cluster.call_api(
            f"/{WORKING_STATE_INDEX}/_doc/{doc_id}",
            HttpMethod.PUT,
            data=json.dumps(doc),
            headers={"Content-Type": "application/json"}
        )
    
    # Add documents for index1__shard0 (1 document)
    doc = {
        "indexName": "index1",
        "shardId": 0,
        "expiration": current_time + 3600,
        "status": "in_progress"
    }
    target_cluster.call_api(
        f"/{WORKING_STATE_INDEX}/_doc/index1__shard0__0",
        HttpMethod.PUT,
        data=json.dumps(doc),
        headers={"Content-Type": "application/json"}
    )
    
    # Refresh the index
    target_cluster.call_api("/_refresh", HttpMethod.POST)
    
    # Generate the queries
    queries = generate_status_queries()
    
    # The total query should count unique index/shard pairs, which should be 3
    expected_unique_pairs = 3  # index0__shard0, index0__shard1, index1__shard0
    
    # Execute total query and verify result
    execute_query_and_verify(target_cluster, queries["total"], expected_unique_pairs, "unique index/shard pairs")
