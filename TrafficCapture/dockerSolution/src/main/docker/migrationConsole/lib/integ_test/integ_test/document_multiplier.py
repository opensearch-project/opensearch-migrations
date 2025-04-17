import logging
import pytest
import unittest
import json
from requests.exceptions import HTTPError
from console_link.middleware.clusters import connection_check, clear_cluster, ConnectionResult
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from console_link.models.snapshot import Snapshot
from console_link.cli import Context
from console_link.models.snapshot import S3Snapshot  # Import S3Snapshot
from console_link.models.backfill_rfs import RfsWorkersInProgress
from console_link.models.command_runner import CommandRunner, CommandRunnerError
from .default_operations import DefaultOperationsLibrary
from .common_utils import execute_api_call
from datetime import datetime
import time
import shutil
import os

# Test configuration from pytest options
@pytest.fixture(scope="class")
def test_config(request):
    """Fixture to provide test configuration at class level"""
    return {
        'NUM_SHARDS': request.config.getoption("--num_shards"),
        'MULTIPLICATION_FACTOR': request.config.getoption("--multiplication_factor"),
        'BATCH_COUNT': request.config.getoption("--batch_count"),
        'DOCS_PER_BATCH': request.config.getoption("--docs_per_batch"),
        'BACKFILL_TIMEOUT_HOURS': request.config.getoption("--backfill_timeout_hours"),
        'TRANSFORMATION_DIRECTORY': request.config.getoption("--transformation_directory"),
        'LARGE_SNAPSHOT_S3_URI': request.config.getoption("--large_snapshot_s3_uri"),
        'LARGE_SNAPSHOT_AWS_REGION': request.config.getoption("--large_snapshot_aws_region"),
        'LARGE_SNAPSHOT_RATE_MB_PER_NODE': request.config.getoption("--large_snapshot_rate_mb_per_node"),
        'RFS_WORKERS': request.config.getoption("--rfs_workers"),
        'STAGE': request.config.getoption("--stage"),
        'CLUSTER_VERSION': request.config.getoption("--cluster_version")
    }

# Constants
PILOT_INDEX = "pilot_index"  # Name of the index used for testing

logger = logging.getLogger(__name__)
ops = DefaultOperationsLibrary()

def preload_data_cluster_es56(source_cluster: Cluster, test_config):
    config = test_config
    # Create source index with settings for ES 5.6 and ES 6.8
    index_settings_es56 = {
        "settings": {
            "number_of_shards": str(config['NUM_SHARDS']),
            "number_of_replicas": "0"
        },
        "mappings": {
            "doc": {  
                "properties": {
                    "timestamp": {"type": "date"},
                    "value": {"type": "keyword"},
                    "doc_number": {"type": "integer"},
                    "description": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}},
                    "metadata": {
                        "properties": {  
                            "tags": {"type": "keyword"},
                            "category": {"type": "keyword"},
                            "subcategories": {"type": "keyword"},
                            "attributes": {"type": "keyword"},
                            "status": {"type": "keyword"},
                            "version": {"type": "keyword"},
                            "region": {"type": "keyword"},
                            "details": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}}
                        }
                    },
                    "content": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}},
                    "additional_info": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}}
                }
            }
        }
    }

    logger.info("Creating index %s with settings: %s", PILOT_INDEX, index_settings_es56)
    ops.create_custom_index(cluster=source_cluster, index_name=PILOT_INDEX, data=json.dumps(index_settings_es56))
    
    # Create documents with timestamp in bulk
    for j in range(config['BATCH_COUNT']):
        bulk_data = []
        for i in range(config['DOCS_PER_BATCH']):
            doc_id = f"doc_{j}_{i}"
            bulk_data.extend([
                {"index": {"_index": PILOT_INDEX, "_type": "doc", "_id": doc_id}},
                {
                    "timestamp": datetime.now().isoformat(),
                    "value": f"test_value_{i}",
                    "doc_number": i,
                    "description": f"This is a detailed description for document {doc_id} containing information about the test data and its purpose in the large snapshot creation process.",
                    "metadata": {
                        "tags": [f"tag1_{i}", f"tag2_{i}", f"tag3_{i}"],
                        "category": f"category_{i % 10}",
                        "subcategories": [f"subcat1_{i % 5}", f"subcat2_{i % 5}"],
                        "attributes": [f"attr1_{i % 8}", f"attr2_{i % 8}"],
                        "status": f"status_{i % 6}",
                        "version": f"1.{i % 10}.{i % 5}",
                        "region": f"region_{i % 12}",
                        "details": f"Detailed metadata information for document {doc_id} including test parameters."
                    },
                    "content": f"Main content for document {doc_id}. This section contains the primary information and data relevant to the testing process. The content is designed to create minimal document for migration and multiplication.",
                    "additional_info": f"Supplementary information for document {doc_id} providing extra context and details about the test data."
                }
            ])

        # Bulk index documents
        execute_api_call(
            cluster=source_cluster,
            method=HttpMethod.POST,
            path="/_bulk",
            data="\n".join(json.dumps(d) for d in bulk_data) + "\n",
            headers={"Content-Type": "application/x-ndjson"}
        )


def preload_data_cluster_es710(source_cluster: Cluster, test_config):
    config = test_config
    # Create source index with settings for ES 7.10
    index_settings_es710 = {
        "settings": {
            "number_of_shards": str(config['NUM_SHARDS']),
            "number_of_replicas": "0"
        },
        "mappings": {  
            "properties": {
                "timestamp": {"type": "date"},
                "value": {"type": "keyword"},
                "doc_number": {"type": "integer"},
                "description": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}},
                "metadata": {
                    "properties": {  
                        "tags": {"type": "keyword"},
                        "category": {"type": "keyword"},
                        "subcategories": {"type": "keyword"},
                        "attributes": {"type": "keyword"},
                        "status": {"type": "keyword"},
                        "version": {"type": "keyword"},
                        "region": {"type": "keyword"},
                        "details": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}}
                    }
                },
                "content": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}},
                "additional_info": {"type": "text", "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}}
            }
        }
    }

    logger.info("Creating index %s with settings: %s", PILOT_INDEX, index_settings_es710)
    ops.create_custom_index(cluster=source_cluster, index_name=PILOT_INDEX, body=index_settings_es710)
    
    # Create documents with timestamp in bulk for ES 7.10
    for j in range(config['BATCH_COUNT']):
        bulk_data = []
        for i in range(config['DOCS_PER_BATCH']):
            doc_id = f"doc_{j}_{i}"
            bulk_data.extend([
                {"index": {"_index": PILOT_INDEX, "_id": doc_id}},  
                {
                    "timestamp": datetime.now().isoformat(),
                    "value": f"test_value_{i}",
                    "doc_number": i,
                    "description": f"This is a detailed description for document {doc_id} containing information about the test data and its purpose in the large snapshot creation process.",
                    "metadata": {
                        "tags": [f"tag1_{i}", f"tag2_{i}", f"tag3_{i}"],
                        "category": f"category_{i % 10}",
                        "subcategories": [f"subcat1_{i % 5}", f"subcat2_{i % 5}"],
                        "attributes": [f"attr1_{i % 8}", f"attr2_{i % 8}"],
                        "status": f"status_{i % 6}",
                        "version": f"1.{i % 10}.{i % 5}",
                        "region": f"region_{i % 12}",
                        "details": f"Detailed metadata information for document {doc_id} including test parameters."
                    },
                    "content": f"Main content for document {doc_id}. This section contains the primary information and data relevant to the testing process. The content is designed to create minimal document for migration and multiplication.",
                    "additional_info": f"Supplementary information for document {doc_id} providing extra context and details about the test data."
                }
            ])

        # Bulk index documents
        execute_api_call(
            cluster=source_cluster,
            method=HttpMethod.POST,
            path="/_bulk",
            data="\n".join(json.dumps(d) for d in bulk_data) + "\n",
            headers={"Content-Type": "application/x-ndjson"}
        )


def preload_data_cluster_os217(source_cluster: Cluster, test_config):
    config = test_config

    # Index settings for OpenSearch 2.17
    index_settings_os217 = {
        "settings": {
            "number_of_shards": str(config['NUM_SHARDS']),
            "number_of_replicas": "0"
        },
        "mappings": {
            "properties": {
                "timestamp": {"type": "date"},
                "value": {"type": "keyword"},
                "doc_number": {"type": "integer"},
                "description": {
                    "type": "text",
                    "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}
                },
                "metadata": {
                    "properties": {
                        "tags": {"type": "keyword"},
                        "category": {"type": "keyword"},
                        "subcategories": {"type": "keyword"},
                        "attributes": {"type": "keyword"},
                        "status": {"type": "keyword"},
                        "version": {"type": "keyword"},
                        "region": {"type": "keyword"},
                        "details": {
                            "type": "text",
                            "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}
                        }
                    }
                },
                "content": {
                    "type": "text",
                    "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}
                },
                "additional_info": {
                    "type": "text",
                    "fields": {"keyword": {"type": "keyword", "ignore_above": 256}}
                }
            }
        }
    }

    logger.info("Creating index %s with settings: %s", PILOT_INDEX, index_settings_os217)
    ops.create_custom_index(cluster=source_cluster, index_name=PILOT_INDEX, body=index_settings_os217)

    # Create documents with timestamp in bulk for OS 2.17
    for j in range(config['BATCH_COUNT']):
        bulk_data = []
        for i in range(config['DOCS_PER_BATCH']):
            doc_id = f"doc_{j}_{i}"
            bulk_data.extend([
                {"index": {"_index": PILOT_INDEX, "_id": doc_id}},
                {
                    "timestamp": datetime.now().isoformat(),
                    "value": f"test_value_{i}",
                    "doc_number": i,
                    "description": f"This is a detailed description for document {doc_id} containing information about the test data and its purpose in the large snapshot creation process.",
                    "metadata": {
                        "tags": [f"tag1_{i}", f"tag2_{i}", f"tag3_{i}"],
                        "category": f"category_{i % 10}",
                        "subcategories": [f"subcat1_{i % 5}", f"subcat2_{i % 5}"],
                        "attributes": [f"attr1_{i % 8}", f"attr2_{i % 8}"],
                        "status": f"status_{i % 6}",
                        "version": f"1.{i % 10}.{i % 5}",
                        "region": f"region_{i % 12}",
                        "details": f"Detailed metadata information for document {doc_id} including test parameters."
                    },
                    "content": f"Main content for document {doc_id}. This section contains the primary information and data relevant to the testing process. The content is designed to create minimal document for migration and multiplication.",
                    "additional_info": f"Supplementary information for document {doc_id} providing extra context and details about the test data."
                }
            ])

        # Bulk index documents
        execute_api_call(
            cluster=source_cluster,
            method=HttpMethod.POST,
            path="/_bulk",
            data="\n".join(json.dumps(d) for d in bulk_data) + "\n",
            headers={"Content-Type": "application/x-ndjson"}
        )


def setup_test_environment(source_cluster: Cluster, test_config):
    """Setup test data"""
    # If source_cluster is None, we'll need to get the target cluster from environment
    if source_cluster is None:
        logger.info("Source cluster is None, using target cluster instead")
        config_path = "/config/migration_services.yaml"
        env = Context(config_path).env
        source_cluster = env.target_cluster
        if source_cluster is None:
            raise Exception("Neither source nor target cluster is available")
    
    # # Confirm cluster connection
    # source_con_result: ConnectionResult = connection_check(source_cluster)
    # assert source_con_result.connection_established is True, f"Failed to connect to cluster: {source_con_result.connection_message}"

    # Clear indices and snapshots at the start
    logger.info("Clearing indices and snapshots before starting test...")
    try:
        clear_cluster(source_cluster)
        # Directly delete the index to ensure it's gone
        logger.info(f"Explicitly deleting {PILOT_INDEX} if it exists...")
        try:
            execute_api_call(
                cluster=source_cluster,
                method=HttpMethod.DELETE,
                path=f"/{PILOT_INDEX}"
            )
            logger.info(f"Successfully deleted {PILOT_INDEX}")
        except Exception as e:
            logger.info(f"Index delete returned: {str(e)}, continuing...")
    except Exception as e:
        logger.warning(f"Non-fatal error during cluster cleanup: {str(e)}. Continuing with test...")
    
    # Cleanup generated transformation files
    try:
        shutil.rmtree(test_config['TRANSFORMATION_DIRECTORY'])
        logger.info("Removed existing " + test_config['TRANSFORMATION_DIRECTORY'] + " directory")
    except FileNotFoundError:
        logger.info("No transformation files detected to cleanup")

    # Transformer structure
    config = test_config
    transform_config = {
    "JsonJSTransformerProvider": {
        "initializationScript": "const MULTIPLICATION_FACTOR = " + str(config['MULTIPLICATION_FACTOR'])+ "; function transform(document) { if (!document) { throw new Error(\"No source_document was defined - nothing to transform!\"); } const indexCommandMap = document.get(\"index\"); const originalSource = document.get(\"source\"); const docsToCreate = []; for (let i = 0; i < MULTIPLICATION_FACTOR; i++) { const newIndexMap = new Map(indexCommandMap); const newId = newIndexMap.get(\"_id\") + ((i !== 0) ? `_${i}` : \"\"); newIndexMap.set(\"_id\", newId); docsToCreate.push(new Map([[\"index\", newIndexMap], [\"source\", originalSource]])); } return docsToCreate; } function main(context) { console.log(\"Context: \", JSON.stringify(context, null, 2)); return (document) => { if (Array.isArray(document)) { return document.flatMap((item) => transform(item, context)); } return transform(document); }; } (() => main)();",
        "bindingsObject": "{}"
        }
    }
    ops.create_transformation_json_file([transform_config], os.path.join(config['TRANSFORMATION_DIRECTORY'], "transformation.json"))

    # Select appropriate preload function based on cluster version
    cluster_version = config['CLUSTER_VERSION']
    logger.info(f"Using cluster version: {cluster_version}")

    if cluster_version == "es5x" or cluster_version == "es6x":
        logger.info("Using ES5.x/ES6.x preload function")
        preload_data_cluster_es56(source_cluster, test_config)
    elif cluster_version == "es7x":
        logger.info("Using ES7.x preload function")
        preload_data_cluster_es710(source_cluster, test_config)
    elif cluster_version == "os2x":
        logger.info("Using OpenSearch 2.x preload function")
        preload_data_cluster_os217(source_cluster, test_config)
    else:
        logger.warning(f"Unknown cluster version '{cluster_version}', defaulting to ES5.x/ES6.x")
        preload_data_cluster_es56(source_cluster, test_config)
    
    # Refresh indices before creating initial snapshot
    execute_api_call(
        cluster=source_cluster,
        method=HttpMethod.POST,
        path="/_refresh"
    )
    logger.info(f"Created {config['BATCH_COUNT'] * config['DOCS_PER_BATCH']} documents in bulk in index %s", PILOT_INDEX)


@pytest.fixture(scope="class")
def setup_backfill(test_config, request):
    """Test setup with backfill lifecycle management"""
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    pytest.console_env = Context(config_path).env
    pytest.unique_id = unique_id

    # Preload data on pilot index
    setup_test_environment(source_cluster=pytest.console_env.source_cluster, test_config=test_config)

    # Get components
    backfill: Backfill = pytest.console_env.backfill
    assert backfill is not None
    snapshot: Snapshot = pytest.console_env.snapshot
    assert snapshot is not None

    # Initialize backfill first (creates .migrations_working_state)
    backfill_create_result: CommandResult = backfill.create()
    assert backfill_create_result.success
    logger.info("Backfill initialized successfully. Created working state at %s", backfill_create_result.value)

    # Create initial RFS snapshot and wait for completion
    snapshot_result: CommandResult = snapshot.create(wait=True)
    assert snapshot_result.success
    logger.info("Snapshot creation completed successfully")

    yield

    # Cleanup - stop backfill
    logger.info("Cleaning up test environment...")
    try:
        backfill.stop()
        logger.info("Backfill stopped and snapshots cleaned up.")
    except Exception as e:
        logger.error(f"Error during cleanup: {str(e)}")


@pytest.fixture(scope="session", autouse=True)
def setup_environment(request):
    """Initialize test environment"""
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    pytest.console_env = Context(config_path).env
    pytest.unique_id = unique_id
    
    logger.info("Starting tests...")
    yield
    # Note: Individual tests handle their own cleanup
    logger.info("Test environment teardown complete")


@pytest.mark.usefixtures("setup_backfill", "test_config")
class BackfillTest(unittest.TestCase):
    """Test backfill functionality"""

    @pytest.fixture(autouse=True)
    def setup_test(self, test_config, request):
        """Setup test configuration before each test method"""
        self.config = test_config
        self.request = request

    def wait_for_backfill_completion(self, cluster: Cluster, pilot_index: str, timeout_hours: int = None):
        """Wait until document count stabilizes or bulk-loader pods terminate"""
        previous_count = 0
        stable_count = 0
        required_stable_checks = 3  # Need 3 consecutive stable counts at EXPECTED_TOTAL_TARGET_DOCS
        start_time = time.time()
        timeout_seconds = timeout_hours * 3600 if timeout_hours else self.config['BACKFILL_TIMEOUT_HOURS'] * 3600
        
        while True:  
            if time.time() - start_time > timeout_seconds:
                raise TimeoutError(f"Backfill monitoring timed out after {timeout_hours if timeout_hours else self.config['BACKFILL_TIMEOUT_HOURS']} hours. Last count: {previous_count:,}")

            cluster_response = execute_api_call(cluster=cluster, method=HttpMethod.GET, path=f"/{pilot_index}/_count?format=json")
            current_count = cluster_response.json()['count']
            
            # Get bulk loader pod status
            try:
                bulk_loader_pods = execute_api_call(
                    cluster=cluster,
                    method=HttpMethod.GET,
                    path="/_cat/tasks?detailed",
                    headers={"Accept": "application/json"}
                ).json()
                bulk_loader_active = any(task.get('action', '').startswith('indices:data/write/bulk') for task in bulk_loader_pods)
            except Exception as e:
                logger.warning(f"Failed to check bulk loader status: {e}")
                bulk_loader_active = True  # Assume active if we can't check
            
            elapsed_hours = (time.time() - start_time) / 3600
            logger.info(f"Backfill Progress - {elapsed_hours:.2f} hours elapsed:")
            logger.info(f"- Current doc count: {current_count:,}")
            logger.info(f"- Target doc count: {self.config['BATCH_COUNT'] * self.config['DOCS_PER_BATCH'] * self.config['MULTIPLICATION_FACTOR']:,}")
            logger.info(f"- Progress: {(current_count/(self.config['BATCH_COUNT'] * self.config['DOCS_PER_BATCH'] * self.config['MULTIPLICATION_FACTOR']))*100:.2f}%")
            logger.info(f"- Bulk loader active: {bulk_loader_active}")
            
            stuck_count = 0
            # Don't consider it stable if count is 0 and bulk loader is still active
            if current_count == 0 and bulk_loader_active:
                logger.info("Waiting for documents to start appearing...")
                stable_count = 0
                stuck_count = 0
            # Only consider it stable if count matches previous and is non-zero
            elif current_count == self.config['BATCH_COUNT'] * self.config['DOCS_PER_BATCH'] * self.config['MULTIPLICATION_FACTOR']:
                stable_count += 1
                logger.info(f"Count stable at target {self.config['BATCH_COUNT'] * self.config['DOCS_PER_BATCH'] * self.config['MULTIPLICATION_FACTOR']:,} for {stable_count}/{required_stable_checks} checks")
                if stable_count >= required_stable_checks:
                    logger.info(f"Document count reached target {self.config['BATCH_COUNT'] * self.config['DOCS_PER_BATCH'] * self.config['MULTIPLICATION_FACTOR']:,} and stabilized for {required_stable_checks} consecutive checks")
                    return
            # If count is less than expected and not zero, check for stuck condition
            elif 0 < current_count < self.config['BATCH_COUNT'] * self.config['DOCS_PER_BATCH'] * self.config['MULTIPLICATION_FACTOR']:
                if current_count == previous_count:
                    stuck_count += 1
                    logger.warning(f"Count has been stuck at {current_count:,} for {stuck_count}/10 checks")
                    if stuck_count >= 10:
                        raise SystemExit(f"Document count has been stuck at {current_count:,} for too long. Possible issue with backfill.")
                else:
                    stuck_count = 0

                if current_count != previous_count:
                    logger.info(f"Count changed from {previous_count:,} to {current_count:,}")
                stable_count = 0
                
            previous_count = current_count
            time.sleep(30)

    def test_data_multiplication(self):
        """Monitor backfill progress and report final stats"""
        source = pytest.console_env.source_cluster
        index_name = PILOT_INDEX
        backfill = pytest.console_env.backfill

        logger.info("\n" + "="*50)
        logger.info("Starting Document Multiplication Test")
        logger.info("="*50)

        # Initial index stats
        initial_doc_count, initial_index_size = self.get_cluster_stats(source, index_name)
        logger.info("\n=== Initial Source Cluster Stats ===")
        logger.info(f"Source Index: {index_name}")
        logger.info(f"Documents: {initial_doc_count:,}")
        logger.info(f"Index Size: {initial_index_size:.2f} MB")

        # Start backfill
        logger.info("\n=== Starting Backfill Process ===")
        logger.info(f"Expected Document Multiplication Factor: {self.config['MULTIPLICATION_FACTOR']}")
        logger.info(f"Expected Final Document Count: {self.config['BATCH_COUNT'] * self.config['DOCS_PER_BATCH'] * self.config['MULTIPLICATION_FACTOR']:,}")
        logger.info("Starting backfill...")
        backfill_start_result: CommandResult = backfill.start()
        assert backfill_start_result.success, f"Failed to start backfill: {backfill_start_result.error}"

        # Scale backfill workers
        logger.info("Scaling backfill...")
        rfs_workers = self.config['RFS_WORKERS']
        logger.info(f"Scaling to {rfs_workers} RFS workers")
        backfill_scale_result: CommandResult = backfill.scale(units=rfs_workers)
        assert backfill_scale_result.success, f"Failed to scale backfill: {backfill_scale_result.error}"

        # Wait for backfill to complete
        logger.info("\n=== Monitoring Backfill Progress ===")
        self.wait_for_backfill_completion(source, index_name)

        # Get final stats
        logger.info("\n=== Final Cluster Stats ===")
        final_doc_count, final_index_size = self.get_cluster_stats(source, index_name)
        
        logger.info("\nInitial Cluster Stats:")
        logger.info(f"- Index: {index_name}")
        logger.info(f"- Total Documents: {initial_doc_count:,}")
        logger.info(f"- Total Size: {initial_index_size:.2f} MB")
        
        logger.info("\nFinal Cluster Stats:")
        logger.info(f"- Index: {index_name}")
        logger.info(f"- Total Documents: {final_doc_count:,}")
        logger.info(f"- Total Size: {final_index_size:.2f} MB")
        logger.info(f"- Multiplication Factor Achieved: {final_doc_count/initial_doc_count:.2f}x")

        # Assert that documents were actually migrated
        assert final_doc_count > 0, "No documents were migrated to target index"
        assert final_doc_count == self.config['BATCH_COUNT'] * self.config['DOCS_PER_BATCH'] * self.config['MULTIPLICATION_FACTOR'], f"Document count mismatch: source={initial_doc_count}, target={final_doc_count}"

        # Stop backfill
        logger.info("\n=== Stopping Backfill ===")
        stop_result = backfill.stop()
        assert stop_result.success, f"Failed to stop backfill: {stop_result.error}"
        logger.info("Backfill stopped successfully")

        logger.info("Archiving the working state of the backfill operation...")
        archive_result = backfill.archive()

        while isinstance(archive_result.value, RfsWorkersInProgress):
            logger.info("RFS Workers are still running, waiting for them to complete...")
            time.sleep(5)
            archive_result = backfill.archive()
 
        assert archive_result.success, f"Failed to archive backfill: {archive_result.value}"
        logger.info(f"Backfill working state archived to: {archive_result.value}")

        # Setup S3 Bucket
        snapshot: Snapshot = pytest.console_env.snapshot
        assert snapshot is not None
        migrationAssistant_deployTimeRole = snapshot.config['s3']['role']
        # Extract account ID from the role ARN
        account_number = migrationAssistant_deployTimeRole.split(':')[4]
        region = self.config['LARGE_SNAPSHOT_AWS_REGION']
        updated_s3_uri = self.setup_s3_bucket(account_number, region, self.config)
        logger.info(f"Updated S3 URI: {updated_s3_uri}")

        # Delete the existing snapshot and snapshot repo from the cluster
        snapshot.delete()
        snapshot.delete_snapshot_repo()

        # Create final snapshot
        logger.info("\n=== Creating Final Snapshot ===")
        final_snapshot_config = {
            'snapshot_name': 'large-snapshot',
            's3': {
                'repo_uri': updated_s3_uri,  
                'role': migrationAssistant_deployTimeRole,
                'aws_region': region,
                'role': migrationAssistant_deployTimeRole
            }
        }
        final_snapshot = S3Snapshot(final_snapshot_config, pytest.console_env.source_cluster)
        final_snapshot_result: CommandResult = final_snapshot.create(
            wait=True,
            max_snapshot_rate_mb_per_node=self.config['LARGE_SNAPSHOT_RATE_MB_PER_NODE']
        )
        assert final_snapshot_result.success, f"Failed to create final snapshot: {final_snapshot_result.error}"
        logger.info("Final Snapshot after migration and multiplication was created successfully")

        logger.info("\n=== Test Completed Successfully ===")
        logger.info("Document multiplication verified with correct count")

    def get_cluster_stats(self, cluster: Cluster, pilot_index: str = None):
        """Get document count and size stats for a cluster (primary shards only)"""
        try:
            if pilot_index:
                path = f"/{pilot_index}/_stats"
            else:
                path = "/_stats"

            stats = execute_api_call(cluster=cluster, method=HttpMethod.GET, path=path).json()
            total_docs = stats['_all']['primaries']['docs']['count']
            total_size_bytes = stats['_all']['primaries']['store']['size_in_bytes']
            total_size_mb = total_size_bytes / (1024 * 1024)
            
            return total_docs, total_size_mb
        except Exception as e:
            logger.error(f"Error getting cluster stats: {str(e)}")
            return 0, 0
        
    def setup_s3_bucket(self, account_number: str, region: str, test_config):
        """Check and create S3 bucket to store large snapshot"""
        cluster_version = test_config['CLUSTER_VERSION']
        # Always use us-east-1 for the snapshot bucket
        snapshot_region = "us-east-1"
        bucket_name = f"migration-jenkins-snapshot-{account_number}-{snapshot_region}"
        snapshot_folder = f"large-snapshot-{cluster_version}"

        # Check if bucket exists
        logger.info(f"Checking if S3 bucket {bucket_name} exists in region {snapshot_region}...")
        check_bucket_cmd = CommandRunner(
            command_root="aws",
            command_args={
                "__positional__": ["s3api", "head-bucket"],
                "--bucket": bucket_name,
                "--region": snapshot_region
            }
        )
        
        try:
            check_result = check_bucket_cmd.run()
            bucket_exists = check_result.success
        except CommandRunnerError:
            bucket_exists = False
            
        if bucket_exists:
            logger.info(f"S3 bucket {bucket_name} already exists.")
            logger.info("\n=== Cleaning up S3 bucket contents ===")
            s3_cleanup_cmd = CommandRunner(
                command_root="aws",
                command_args={
                    "__positional__": ["s3", "rm", f"s3://{bucket_name}/{snapshot_folder}/"],
                    "--recursive": None,
                    "--region": snapshot_region
                }
            )
            cleanup_result = s3_cleanup_cmd.run()
            assert cleanup_result.success, f"Failed to clean up S3 bucket: {cleanup_result.display()}"
            logger.info("Successfully cleaned up S3 bucket contents")
        else:
            logger.info(f"S3 bucket {bucket_name} does not exist. Creating it...")
            logger.info("\n=== Creating new S3 bucket as it does not exist  ===")
            create_args = {
                "__positional__": ["s3api", "create-bucket"],
                "--bucket": bucket_name,
                "--region": snapshot_region,
            }
            
            # Only add LocationConstraint for non-us-east-1 regions
            if snapshot_region != "us-east-1":
                create_args["--create-bucket-configuration"] = f"LocationConstraint={snapshot_region}"
                
            create_bucket_cmd = CommandRunner(
                command_root="aws",
                command_args=create_args
            )
            create_result = create_bucket_cmd.run()
            assert create_result.success, f"Failed to create S3 bucket: {create_result.display()}"
            logger.info(f"S3 bucket {bucket_name} created successfully.")
        
        return f"s3://{bucket_name}/{snapshot_folder}/"
