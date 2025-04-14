import logging
import pytest
import unittest
import json
from console_link.middleware.clusters import connection_check, clear_cluster, ConnectionResult
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from console_link.models.snapshot import Snapshot
from console_link.cli import Context
from console_link.models.snapshot import S3Snapshot  # Import S3Snapshot
from console_link.models.backfill_rfs import RfsWorkersInProgress
from console_link.models.command_runner import CommandRunner
from .default_operations import DefaultOperationsLibrary
from .common_utils import execute_api_call
from datetime import datetime
import time
import shutil
import os

# Global environment variables
NUM_SHARDS = int(os.getenv("NUM_SHARDS", 10)) # Index setting for number of shards
MULTIPLICATION_FACTOR = int(os.getenv("MULTIPLICATION_FACTOR", 1000)) # Transformer multiplication factor
BATCH_COUNT = int(os.getenv("BATCH_COUNT", 3)) # Number of bulk ingestion batches
DOCS_PER_BATCH = int(os.getenv("DOCS_PER_BATCH", 100)) # Number of documents per batch for Bulk Ingest
BACKFILL_TIMEOUT_HOURS = int(os.getenv("BACKFILL_TIMEOUT_HOURS", 45)) # Timeout for backfill completion in hours
TRANSFORMATION_DIRECTORY = str(os.getenv("TRANSFORMATION_DIRECTORY", "/shared-logs-output/test-transformations"))  # Directory for transformation files
TRANSFORMATION_FILE_PATH = str(os.path.join(TRANSFORMATION_DIRECTORY, "transformation.json"))  # Path to the transformation file
LARGE_SNAPSHOT_S3_URI = str(os.getenv("LARGE_SNAPSHOT_S3_URI", "s3://test-large-snapshot-bucket/es56-10tb-snapshot/"))  # S3 URI for large snapshot
LARGE_SNAPSHOT_AWS_REGION = str(os.getenv("LARGE_SNAPSHOT_AWS_REGION", "us-east-1"))  # AWS region for S3 Bucket of large snapshot
LARGE_SNAPSHOT_RATE_MB_PER_NODE = int(os.getenv("LARGE_SNAPSHOT_RATE_MB_PER_NODE", 2000))  # Rate for large snapshot creation

#Calculated values
TOTAL_SOURCE_DOCS = BATCH_COUNT * DOCS_PER_BATCH  
EXPECTED_TOTAL_TARGET_DOCS = TOTAL_SOURCE_DOCS * MULTIPLICATION_FACTOR

logger = logging.getLogger(__name__)
ops = DefaultOperationsLibrary()

def preload_data(source_cluster: Cluster):
    """Setup test data"""
    # Confirm cluster connection
    source_con_result: ConnectionResult = connection_check(source_cluster)
    assert source_con_result.connection_established is True

    # Clear indices and snapshots at the start
    logger.info("Clearing indices and snapshots before starting test...")
    clear_cluster(source_cluster)

    # Cleanup generated transformation files
    try:
        shutil.rmtree(TRANSFORMATION_DIRECTORY)
        logger.info("Removed existing " + TRANSFORMATION_DIRECTORY + " directory")
    except FileNotFoundError:
        logger.info("No transformation files detected to cleanup")

    # Transformer structure
    transform_config = {
    "JsonJSTransformerProvider": {
        "initializationScript": "const MULTIPLICATION_FACTOR = " + str(MULTIPLICATION_FACTOR)+ "; function transform(document) { if (!document) { throw new Error(\"No source_document was defined - nothing to transform!\"); } const indexCommandMap = document.get(\"index\"); const originalSource = document.get(\"source\"); const docsToCreate = []; for (let i = 0; i < MULTIPLICATION_FACTOR; i++) { const newIndexMap = new Map(indexCommandMap); const newId = newIndexMap.get(\"_id\") + ((i !== 0) ? `_${i}` : \"\"); newIndexMap.set(\"_id\", newId); docsToCreate.push(new Map([[\"index\", newIndexMap], [\"source\", originalSource]])); } return docsToCreate; } function main(context) { console.log(\"Context: \", JSON.stringify(context, null, 2)); return (document) => { if (Array.isArray(document)) { return document.flatMap((item) => transform(item, context)); } return transform(document); }; } (() => main)();",
        "bindingsObject": "{}"
        }
    }
    ops.create_transformation_json_file([transform_config], TRANSFORMATION_FILE_PATH)

    # Create source index with settings for ES 5.6
    index_settings_es56 = {
        "settings": {
            "number_of_shards": str(NUM_SHARDS),
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

    pilot_index = f"pilot_index"
    logger.info("Creating index %s with settings: %s", pilot_index, index_settings_es56)
    ops.create_index_es56(cluster=source_cluster, index_name=pilot_index, data=json.dumps(index_settings_es56))
    
    # Create documents with timestamp in bulk
    for j in range(BATCH_COUNT):
        bulk_data = []
        for i in range(DOCS_PER_BATCH):
            doc_id = f"doc_{j}_{i}"
            bulk_data.extend([
                {"index": {"_index": pilot_index, "_type": "doc", "_id": doc_id}},
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
    
    # Refresh indices before creating snapshot
    execute_api_call(
        cluster=source_cluster,
        method=HttpMethod.POST,
        path="/_refresh"
    )
    logger.info(f"Created {TOTAL_SOURCE_DOCS} documents in bulk in index %s", pilot_index)


@pytest.fixture(scope="class")
def setup_backfill(request):
    """Test setup with backfill lifecycle management"""
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    pytest.console_env = Context(config_path).env
    pytest.unique_id = unique_id

    # Preload data on pilot index
    preload_data(source_cluster=pytest.console_env.source_cluster)

    # Get components
    backfill: Backfill = pytest.console_env.backfill
    assert backfill is not None
    snapshot: Snapshot = pytest.console_env.snapshot
    assert snapshot is not None

    # Initialize backfill first (creates .migrations_working_state)
    backfill_create_result: CommandResult = backfill.create()
    assert backfill_create_result.success
    logger.info("EXHIBIT A Backfill initialized successfully")

    # Create initial RFS snapshot and wait for completion
    snapshot_result: CommandResult = snapshot.create(wait=True)
    assert snapshot_result.success
    logger.info("Snapshot creation completed successfully")

    # Start backfill process
    backfill_start_result: CommandResult = backfill.start()
    assert backfill_start_result.success
    logger.info("EXHIBIT A Backfill started successfully")

    # Scale up backfill workers
    backfill_scale_result: CommandResult = backfill.scale(5)
    assert backfill_scale_result.success
    logger.info("EXHIBIT A Backfill scaled successfully")

    yield

    # Cleanup - stop backfill
    logger.info("EXHIBIT A Cleaning up test environment...")
    try:
        backfill.stop()
        logger.info("EXHIBIT A Backfill stopped and snapshots cleaned up.")
    except Exception as e:
        logger.error(f"EXHIBIT A Error during cleanup: {str(e)}")


@pytest.fixture(scope="session", autouse=True)
def setup_environment(request):
    """Initialize test environment"""
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    pytest.console_env = Context(config_path).env
    pytest.unique_id = unique_id
    
    logger.info("EXHIBIT B Starting backfill tests...")
    yield
    # Note: Individual tests handle their own cleanup
    logger.info("EXHIBIT B Test environment teardown complete")


@pytest.mark.usefixtures("setup_backfill")
class BackfillTest(unittest.TestCase):
    """Test backfill functionality"""

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

    def wait_for_backfill_completion(self, cluster: Cluster, pilot_index: str, timeout_hours: int = BACKFILL_TIMEOUT_HOURS):
        """Wait until document count stabilizes or bulk-loader pods terminate"""
        previous_count = 0
        stable_count = 0
        required_stable_checks = 3  # Need 3 consecutive stable counts at EXPECTED_TOTAL_TARGET_DOCS
        start_time = time.time()
        timeout_seconds = timeout_hours * 3600
        
        while True:  
            if time.time() - start_time > timeout_seconds:
                raise TimeoutError(f"Backfill monitoring timed out after {timeout_hours} hours. Last count: {previous_count:,}")

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
            logger.info(f"- Target doc count: {EXPECTED_TOTAL_TARGET_DOCS:,}")
            logger.info(f"- Progress: {(current_count/EXPECTED_TOTAL_TARGET_DOCS*100):.2f}%")
            logger.info(f"- Bulk loader active: {bulk_loader_active}")
            
            stuck_count = 0
            # Don't consider it stable if count is 0 and bulk loader is still active
            if current_count == 0 and bulk_loader_active:
                logger.info("Waiting for documents to start appearing...")
                stable_count = 0
                stuck_count = 0
            # Only consider it stable if count matches previous and is non-zero
            elif current_count == EXPECTED_TOTAL_TARGET_DOCS:
                stable_count += 1
                logger.info(f"Count stable at target {EXPECTED_TOTAL_TARGET_DOCS:,} for {stable_count}/{required_stable_checks} checks")
                if stable_count >= required_stable_checks:
                    logger.info(f"Document count reached target {EXPECTED_TOTAL_TARGET_DOCS:,} and stabilized for {required_stable_checks} consecutive checks")
                    return
            # If count is less than expected and not zero, check for stuck condition
            elif 0 < current_count < EXPECTED_TOTAL_TARGET_DOCS:
                if current_count == previous_count:
                    stuck_count += 1
                    logger.warning(f"Count has been stuck at {current_count:,} for {stuck_count}/3 checks")
                    if stuck_count >= 3:
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
        index_name = f"pilot_index"
        backfill = pytest.console_env.backfill

        logger.info("\n" + "="*50)
        logger.info("EXHIBIT C Starting Document Multiplication Test")
        logger.info("="*50)

        # Initial index stats
        initial_doc_count, initial_index_size = self.get_cluster_stats(source, index_name)
        logger.info("\n=== Initial Source Cluster Stats ===")
        logger.info(f"Source Index: {index_name}")
        logger.info(f"Documents: {initial_doc_count:,}")
        logger.info(f"Index Size: {initial_index_size:.2f} MB")

        # Start backfill
        logger.info("\n=== Starting Backfill Process ===")
        logger.info(f"Expected Document Multiplication Factor: {MULTIPLICATION_FACTOR}")
        logger.info(f"Expected Final Document Count: {TOTAL_SOURCE_DOCS * MULTIPLICATION_FACTOR:,}")
        logger.info("Starting backfill...")
        backfill_start_result: CommandResult = backfill.start()
        assert backfill_start_result.success, f"Failed to start backfill: {backfill_start_result.error}"

        # Scale backfill workers
        logger.info("EXHIBIT C Scaling backfill...")
        backfill_scale_result: CommandResult = backfill.scale(units=8)
        assert backfill_scale_result.success, f"EXHIBIT C Failed to scale backfill: {backfill_scale_result.error}"

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
        assert final_doc_count == EXPECTED_TOTAL_TARGET_DOCS, f"Document count mismatch: source={initial_doc_count}, target={final_doc_count}"

        # Stop backfill
        logger.info("\n=== Stopping Backfill ===")
        stop_result = backfill.stop()
        assert stop_result.success, f"EXHIBIT C Failed to stop backfill: {stop_result.error}"
        logger.info("EXHIBIT C Backfill stopped successfully")

        logger.info("Archiving the working state of the backfill operation...")
        archive_result = backfill.archive()

        while isinstance(archive_result.value, RfsWorkersInProgress):
            logger.info("RFS Workers are still running, waiting for them to complete...")
            time.sleep(5)
            archive_result = backfill.archive()
 
        assert archive_result.success, f"Failed to archive backfill: {archive_result.value}"
        logger.info(f"Backfill working state archived to: {archive_result.value}")

        snapshot: Snapshot = pytest.console_env.snapshot
        assert snapshot is not None
        migrationAssistant_deployTimeRole = snapshot.config['s3']['role']
        snapshot.delete()
        snapshot.delete_snapshot_repo()

        # Clean up S3 bucket contents
        logger.info("\n=== Cleaning up S3 bucket contents ===")
        s3_cleanup_cmd = CommandRunner(
            command_root="aws",
            command_args={
                "s3": None,
                "rm": None,
                LARGE_SNAPSHOT_S3_URI: None,
                "--recursive": None
            }
        )
        result = s3_cleanup_cmd.run()
        assert result.success, f"Failed to clean up S3 bucket: {result.display()}"
        logger.info("Successfully cleaned up S3 bucket contents")

        logger.info("\n=== Creating Final Snapshot ===")
        final_snapshot_config = {
            'snapshot_name': f'final-snapshot-{pytest.unique_id}',  # Use unique ID to avoid conflicts
            's3': {
                'repo_uri': LARGE_SNAPSHOT_S3_URI,  # New folder
                'aws_region': LARGE_SNAPSHOT_AWS_REGION,
                'role': migrationAssistant_deployTimeRole
            }
        }
        final_snapshot = S3Snapshot(final_snapshot_config, pytest.console_env.source_cluster)
        final_snapshot_result: CommandResult = final_snapshot.create(
            wait=True,
            max_snapshot_rate_mb_per_node=LARGE_SNAPSHOT_RATE_MB_PER_NODE
        )
        assert final_snapshot_result.success, f"Failed to create final snapshot: {final_snapshot_result.error}"
        logger.info("Final Snapshot after migration and multiplication was created successfully")

        logger.info("\n=== Test Completed Successfully ===")
        logger.info("Document multiplication verified with correct count")
