import logging
import pytest
import unittest
import json
from console_link.middleware.clusters import clear_indices
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from console_link.models.snapshot import Snapshot, delete_snapshot_repo, delete_all_snapshots
from console_link.cli import Context
from console_link.models.snapshot import S3Snapshot
from console_link.models.backfill_rfs import RfsWorkersInProgress
from console_link.models.command_runner import CommandRunner, CommandRunnerError
from .default_operations import DefaultOperationsLibrary
from .common_utils import execute_api_call
from datetime import datetime
import time
import shutil
import os


# Constants
PILOT_INDEX = "pilot_index"  # Name of the index used for testing

logger = logging.getLogger(__name__)
ops = DefaultOperationsLibrary()


# Test configuration from pytest options
@pytest.fixture(scope="session")
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
        # 'DEPLOY_REGION': request.config.getoption("--deploy_region"),
        'SNAPSHOT_REGION': request.config.getoption("--snapshot_region"),
        'LARGE_SNAPSHOT_RATE_MB_PER_NODE': request.config.getoption("--large_snapshot_rate_mb_per_node"),
        'RFS_WORKERS': request.config.getoption("--rfs_workers"),
        # 'STAGE': request.config.getoption("--stage"),
        'CLUSTER_VERSION': request.config.getoption("--cluster_version")
    }


def preload_data_cluster_es56(target_cluster: Cluster, test_config):
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
    ops.create_custom_index(cluster=target_cluster, index_name=PILOT_INDEX, data=json.dumps(index_settings_es56))
    
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
                    "description": (
                        f"This is a detailed description for document {doc_id} "
                        "containing information about the test data and its purpose "
                        "in the large snapshot creation process."
                    ),
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
                    "content": (
                        f"Main content for document {doc_id}. This section contains the primary information "
                        "and data relevant to the testing process. The content is designed to create minimal "
                        "document for migration and multiplication."
                    ),
                    "additional_info": (
                        f"Supplementary information for document {doc_id} "
                        "providing extra context and details about the test data."
                    )
                }
            ])

        # Bulk index documents
        execute_api_call(
            cluster=target_cluster,
            method=HttpMethod.POST,
            path="/_bulk",
            data="\n".join(json.dumps(d) for d in bulk_data) + "\n",
            headers={"Content-Type": "application/x-ndjson"}
        )


def preload_data_cluster_es710(target_cluster: Cluster, test_config):
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
    ops.create_custom_index(cluster=target_cluster, index_name=PILOT_INDEX, body=index_settings_es710)
    
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
                    "description": (
                        f"This is a detailed description for document {doc_id} "
                        "containing information about the test data and its purpose "
                        "in the large snapshot creation process."
                    ),
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
                    "content": (
                        f"Main content for document {doc_id}. This section contains the primary information "
                        "and data relevant to the testing process. The content is designed to create minimal "
                        "document for migration and multiplication."
                    ),
                    "additional_info": (
                        f"Supplementary information for document {doc_id} "
                        "providing extra context and details about the test data."
                    )
                }
            ])

        # Bulk index documents
        execute_api_call(
            cluster=target_cluster,
            method=HttpMethod.POST,
            path="/_bulk",
            data="\n".join(json.dumps(d) for d in bulk_data) + "\n",
            headers={"Content-Type": "application/x-ndjson"}
        )


def preload_data_cluster_os217(target_cluster: Cluster, test_config):
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
    ops.create_custom_index(cluster=target_cluster, index_name=PILOT_INDEX, body=index_settings_os217)

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
                    "description": (
                        f"This is a detailed description for document {doc_id} "
                        "containing information about the test data and its purpose "
                        "in the large snapshot creation process."
                    ),
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
                    "content": (
                        f"Main content for document {doc_id}. This section contains the primary information "
                        "and data relevant to the testing process. The content is designed to create minimal "
                        "document for migration and multiplication."
                    ),
                    "additional_info": (
                        f"Supplementary information for document {doc_id} "
                        "providing extra context and details about the test data."
                    )
                }
            ])

        # Bulk index documents
        execute_api_call(
            cluster=target_cluster,
            method=HttpMethod.POST,
            path="/_bulk",
            data="\n".join(json.dumps(d) for d in bulk_data) + "\n",
            headers={"Content-Type": "application/x-ndjson"}
        )


def setup_test_environment(target_cluster: Cluster, test_config):
    """Setup test data"""
    # If target_cluster is None, we'll need to get the target cluster from environment
    if target_cluster is None:
        logger.info("Target cluster is None, using target cluster from environment instead")
        config_path = "/config/migration_services.yaml"
        env = Context(config_path).env
        target_cluster = env.target_cluster

        if target_cluster is None:
            raise Exception("Target cluster is not available")

    logger.info(f"Using cluster endpoint: {target_cluster.endpoint}")
    logger.info(f"Target Cluster Auth Type: {pytest.console_env.target_cluster.auth_type}")
    logger.info(f"Target Cluster Auth Details: {pytest.console_env.target_cluster.auth_details}")
    
    # Clear indices
    logger.info("Clearing indices in the target cluster...")
    try:
        response = clear_indices(target_cluster)
        logger.info(f"Indices cleared successfully. Response: {response}")
    except Exception as e:
        logger.warning(f"Failed to clear indices: {str(e)}. Continuing with test...")

    # Clear snapshots from the repository
    repository = "migration_assistant_repo"
    logger.info(f"Clearing all snapshots from repository '{repository}'...")
    try:
        delete_all_snapshots(target_cluster, repository)
        logger.info(f"Successfully cleared all snapshots from '{repository}'.")
    except Exception as e:
        logger.warning(f"Failed to clear snapshots from '{repository}': {str(e)}")

    # Delete the repository itself
    logger.info(f"Deleting snapshot repository '{repository}'...")
    try:
        delete_snapshot_repo(target_cluster, repository)
        logger.info(f"Successfully deleted repository '{repository}'.")
    except Exception as e:
        logger.warning(f"Failed to delete repository '{repository}': {str(e)}")

    # Cleanup generated transformation files
    try:
        shutil.rmtree(test_config['TRANSFORMATION_DIRECTORY'])
        logger.info("Removed existing " + test_config['TRANSFORMATION_DIRECTORY'] + " directory")
    except FileNotFoundError:
        logger.info("No transformation files detected to cleanup")

    # Transformer structure
    config = test_config
    multiplication_factor = str(config['MULTIPLICATION_FACTOR'])
    initialization_script = (
        f"const MULTIPLICATION_FACTOR = {multiplication_factor}; "
        "function transform(document) { "
        "if (!document) { throw new Error(\"No source_document was defined - nothing to transform!\"); } "
        "const indexCommandMap = document.get(\"index\"); "
        "const originalSource = document.get(\"source\"); "
        "const docsToCreate = []; "
        "for (let i = 0; i < MULTIPLICATION_FACTOR; i++) { "
        "const newIndexMap = new Map(indexCommandMap); "
        "const newId = newIndexMap.get(\"_id\") + ((i !== 0) ? `_${i}` : \"\"); "
        "newIndexMap.set(\"_id\", newId); "
        "docsToCreate.push(new Map([[\"index\", newIndexMap], [\"source\", originalSource]])); "
        "} "
        "return docsToCreate; "
        "} "
        "function main(context) { "
        "console.log(\"Context: \", JSON.stringify(context, null, 2)); "
        "return (document) => { "
        "if (Array.isArray(document)) { "
        "return document.flatMap((item) => transform(item, context)); "
        "} "
        "return transform(document); "
        "}; "
        "} "
        "(() => main)();"
    )
    transform_config = {
        "JsonJSTransformerProvider": {
            "initializationScript": initialization_script,
            "bindingsObject": "{}"
        }
    }
    ops.create_transformation_json_file(
        [transform_config],
        os.path.join(config['TRANSFORMATION_DIRECTORY'], "transformation.json")
    )

    # Select appropriate preload function based on cluster version
    cluster_version = config['CLUSTER_VERSION']
    logger.info(f"Using cluster version: {cluster_version}")

    if cluster_version == "es5x" or cluster_version == "es6x":
        logger.info("Using ES5.x/ES6.x preload function")
        preload_data_cluster_es56(target_cluster, test_config)
    elif cluster_version == "es7x":
        logger.info("Using ES7.x preload function")
        preload_data_cluster_es710(target_cluster, test_config)
    elif cluster_version == "os1x":
        logger.info("Using OpenSearch 1.x preload function")
        # OpenSearch 1.x is compatible with ES7.x API
        preload_data_cluster_es710(target_cluster, test_config)
    elif cluster_version == "os2x":
        logger.info("Using OpenSearch 2.x preload function")
        preload_data_cluster_os217(target_cluster, test_config)
    else:
        logger.warning(f"Unknown cluster version '{cluster_version}', defaulting to ES5.x/ES6.x")
        preload_data_cluster_es56(target_cluster, test_config)
    
    # Refresh indices before creating initial snapshot
    execute_api_call(
        cluster=target_cluster,
        method=HttpMethod.POST,
        path="/_refresh"
    )
    logger.info(
        f"Created {config['BATCH_COUNT'] * config['DOCS_PER_BATCH']} documents "
        f"in bulk in index %s",
        PILOT_INDEX
    )


@pytest.fixture(scope="class")
def setup_backfill(test_config, request):
    """Test setup with backfill lifecycle management"""
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    # Log config file path
    logger.info(f"Using config file: {config_path}")
    
    # Try to read the config file directly
    try:
        with open(config_path, 'r') as f:
            config_content = f.read()
            logger.info(f"Config file content:\n{config_content}")
    except Exception as e:
        logger.error(f"Failed to read config file: {str(e)}")
    
    # Load environment
    env = Context(config_path).env
    pytest.console_env = env
    pytest.unique_id = unique_id
    
    # Log target cluster details
    if env.target_cluster:
        logger.info("Setting Target cluster auth type from default NO_AUTH to be SIGV4_AUTH for Multiplication test")
        logger.info(f"Target cluster endpoint: {env.target_cluster.endpoint}")
        logger.info(f"Target cluster auth type: {env.target_cluster.auth_type}")
        if hasattr(env.target_cluster, 'auth_details'):
            logger.info(f"Target cluster auth details: {env.target_cluster.auth_details}")
        
        # Try to get version info
        try:
            version_info = execute_api_call(
                cluster=env.target_cluster,
                method=HttpMethod.GET,
                path="/"
            ).json()
            logger.info(f"Target cluster version: {version_info.get('version', {}).get('number', 'unknown')}")
        except Exception as e:
            logger.error(f"Failed to get cluster version: {str(e)}")
            
        # Try to get indices
        try:
            indices = execute_api_call(
                cluster=env.target_cluster,
                method=HttpMethod.GET,
                path="/_cat/indices?format=json"
            ).json()
            logger.info(f"Target cluster indices: {indices}")
        except Exception as e:
            logger.error(f"Failed to get indices: {str(e)}")
    else:
        logger.warning("Target cluster is not configured!")

    # Preload data on pilot index
    setup_test_environment(target_cluster=pytest.console_env.target_cluster, test_config=test_config)

    # Get components
    backfill: Backfill = pytest.console_env.backfill
    logger.info(f"Backfill object: {backfill}")
    logger.info(f"Backfill type: {type(backfill)}")
    assert backfill is not None
    
    snapshot: Snapshot = pytest.console_env.snapshot
    logger.info(f"Snapshot object: {snapshot}")
    logger.info(f"Snapshot type: {type(snapshot)}")
    assert snapshot is not None

    # Initialize backfill first (creates .migrations_working_state)
    try:
        backfill_create_result: CommandResult = backfill.create()
        logger.info(f"Backfill create result: {backfill_create_result}")
        assert backfill_create_result.success
        logger.info("Backfill initialized successfully. Created working state at %s", backfill_create_result.value)
    except Exception as e:
        logger.error(f"Failed to create backfill: {str(e)}")
        raise

    # Create initial RFS snapshot and wait for completion
    try:
        # Log snapshot settings
        logger.info(f"Snapshot settings: {snapshot.__dict__}")
        
        # Try to create snapshot
        logger.info("Creating snapshot...")
        snapshot_result: CommandResult = snapshot.create(wait=True)
        logger.info(f"Snapshot create result: {snapshot_result}")
        
        # Check result
        if not snapshot_result.success:
            logger.error(f"Snapshot creation failed with error: {snapshot_result.error}")
            logger.error(f"Snapshot creation output: {snapshot_result.output}")
        
        assert snapshot_result.success
        logger.info("Snapshot creation completed successfully")
    except Exception as e:
        logger.error(f"Failed to create snapshot: {str(e)}")
        import traceback
        logger.error(f"Snapshot creation error traceback: {traceback.format_exc()}")
        raise

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
    logger.info(f"Target Cluster Auth Type: {pytest.console_env.target_cluster.auth_type}")
    logger.info(f"Target Cluster Auth Details: {pytest.console_env.target_cluster.auth_details}")
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

        def _calculate_expected_doc_count():
            return int(
                self.config['BATCH_COUNT'] *
                self.config['DOCS_PER_BATCH'] *
                self.config['MULTIPLICATION_FACTOR']
            )

        expected_doc_count = _calculate_expected_doc_count()
        previous_count = 0
        stable_count = 0
        required_stable_checks = 3  # Need 3 consecutive stable counts at EXPECTED_TOTAL_TARGET_DOCS
        start_time = time.time()
        timeout_seconds = timeout_hours * 3600 if timeout_hours else self.config['BACKFILL_TIMEOUT_HOURS'] * 3600
        stuck_count = 0
        
        while True:
            if time.time() - start_time > timeout_seconds:
                raise TimeoutError(
                    f"Backfill monitoring timed out after "
                    f"{timeout_hours if timeout_hours else self.config['BACKFILL_TIMEOUT_HOURS']} "
                    f"hours. Last count: {previous_count:,}"
                )

            cluster_response = execute_api_call(
                cluster=cluster,
                method=HttpMethod.GET,
                path=f"/{pilot_index}/_count?format=json"
            )
            current_count = cluster_response.json()['count']
            
            # Get bulk loader pod status
            try:
                bulk_loader_pods = execute_api_call(
                    cluster=cluster,
                    method=HttpMethod.GET,
                    path="/_cat/tasks?detailed",
                    headers={"Accept": "application/json"}
                ).json()
                bulk_loader_active = any(
                    task.get('action', '').startswith('indices:data/write/bulk')
                    for task in bulk_loader_pods
                )
            except Exception as e:
                logger.warning(f"Failed to check bulk loader status: {e}")
                bulk_loader_active = True  # Assume active if we can't check
            
            elapsed_hours = (time.time() - start_time) / 3600
            progress = ((current_count / expected_doc_count) * 100)
            logger.info(f"Backfill Progress - {elapsed_hours:.2f} hours elapsed:")
            logger.info(f"- Current doc count: {current_count:,}")
            logger.info(f"- Expected doc count: {expected_doc_count:,}")
            logger.info(f"- Progress: {progress:.2f}%")
            logger.info(f"- Bulk loader active: {bulk_loader_active}")
            
            # Don't consider it stable if count is 0 and bulk loader is still active
            if current_count == 0 and bulk_loader_active:
                logger.info("Waiting for documents to start appearing...")
                stable_count = 0
                stuck_count = 0
            # Only consider it stable if count matches previous and is non-zero
            elif current_count == expected_doc_count:
                stable_count += 1
                logger.info(
                    f"Count stable at target {expected_doc_count:,} "
                    f"for {stable_count}/{required_stable_checks} checks"
                )
                if stable_count >= required_stable_checks:
                    logger.info(
                        f"Document count reached value {current_count:,} and "
                        f"stabilized for {required_stable_checks} consecutive checks"
                    )
                    return
            # If count is less than expected and not zero, check for stuck condition
            elif 0 < current_count < expected_doc_count:
                if current_count == previous_count:
                    stuck_count += 1
                    logger.warning(f"Count has been stuck at {current_count:,} for {stuck_count}/60 checks")
                    if stuck_count >= 60:
                        raise SystemExit(
                            f"Document count has been stuck at {current_count:,} for too long. "
                            "Possible issue with backfill."
                        )
                else:
                    stuck_count = 0

                if current_count != previous_count:
                    logger.info(f"Count changed from {previous_count:,} to {current_count:,}")
                stable_count = 0
                
            previous_count = current_count
            time.sleep(30)

    def wait_for_working_state_archive(self, backfill, max_retries=30, retry_interval=10):
        """Wait for the working state to be properly archived before proceeding."""
        logger.info("Archiving the working state of the backfill operation...")
        retries = 0
        archive_success = False
        index_deleted = False
        last_check_time = 0
        
        while retries < max_retries and (not archive_success or not index_deleted):
            current_time = time.time()
            
            # Check for archive status
            if not archive_success:
                archive_result = backfill.archive()
                
                # First wait for RFS workers to complete
                if isinstance(archive_result.value, RfsWorkersInProgress):
                    logger.info("RFS Workers are still running, waiting for them to complete...")
                    time.sleep(5)  # Keep original 5 second wait for RFS workers
                    continue
                    
                # Then check for successful archive
                if isinstance(archive_result.value, str) and "working_state_backup" in archive_result.value:
                    logger.info(f"Working state archived to: {archive_result.value}")
                    archive_success = True
                    index_deleted = True  # Archive success means index was deleted
                    break

            # Only check index if archive wasn't successful
            # If checking, check every 5 seconds
            if not archive_success and not index_deleted and (current_time - last_check_time) >= 5:
                try:
                    response = execute_api_call(
                        cluster=pytest.console_env.target_cluster,
                        method=HttpMethod.GET,
                        path="/_cat/indices/.migrations_working_state?format=json"
                    )
                    
                    if response.status_code == 404:
                        logger.info("Migrations working state index has been deleted (404 response)")
                        index_deleted = True
                        break
                    elif response.status_code == 200 and len(response.json()) == 0:
                        logger.info("Migrations working state index has been deleted (empty response)")
                        index_deleted = True
                        break
                    else:
                        logger.info("Waiting for migrations working state index to be deleted...")
                except Exception as e:
                    # Check if the error is a 404 response
                    if '"status":404' in str(e) or 'index_not_found_exception' in str(e):
                        logger.info("Migrations working state index has been deleted (404 exception)")
                        index_deleted = True
                        break
                    else:
                        logger.warning(f"Error checking index status: {e}")
                
                last_check_time = current_time
                    
            if not archive_success or not index_deleted:
                time.sleep(retry_interval)
                retries += 1
                
        if retries >= max_retries:
            logger.warning(
                f"Timeout after {max_retries * retry_interval} seconds. "
                f"Archive success result: {archive_success}, "
                f"Index deletion result: {index_deleted}"
            )
        return archive_result if archive_success else None

    def test_data_multiplication(self):
        """Monitor backfill progress and report final stats"""
        source = pytest.console_env.target_cluster
        index_name = PILOT_INDEX
        backfill = pytest.console_env.backfill

        logger.info("\n" + "=" * 50)
        logger.info("Starting Document Multiplication Test")
        logger.info("=" * 50)

        # Initial index stats
        initial_doc_count, initial_index_size = self.get_cluster_stats(source, index_name)
        logger.info("\n=== Initial Source Cluster Stats ===")
        logger.info(f"Source Index: {index_name}")
        logger.info(f"Documents: {initial_doc_count:,}")
        logger.info(f"Index Size: {initial_index_size:.2f} MB")

        # Start backfill
        logger.info("\n=== Starting Backfill Process ===")
        logger.info(f"Expected Document Multiplication Factor: {self.config['MULTIPLICATION_FACTOR']}")
        expected_final_doc_count = (
            self.config['BATCH_COUNT'] *
            self.config['DOCS_PER_BATCH'] *
            self.config['MULTIPLICATION_FACTOR']
        )
        logger.info(f"Expected Final Document Count: {expected_final_doc_count:,}")
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
        calculate_final_doc_count = (
            self.config['BATCH_COUNT'] *
            self.config['DOCS_PER_BATCH'] *
            self.config['MULTIPLICATION_FACTOR']
        )
        assert final_doc_count == calculate_final_doc_count, (
            f"Document count mismatch: source={initial_doc_count}, target={final_doc_count}"
        )

        # Stop backfill
        logger.info("\n=== Stopping Backfill ===")
        stop_result = backfill.stop()
        assert stop_result.success, f"Failed to stop backfill: {stop_result.error}"
        logger.info("Backfill stopped successfully")

        # Archive working state
        archive_result = self.wait_for_working_state_archive(backfill)
        if archive_result and archive_result.success:
            logger.info("Backfill archive completed successfully")
        else:
            logger.warning(
                "Could not fully verify backfill archive completion. "
                "Proceeding with incomplete backfill stop."
            )

        # Setup S3 Bucket
        snapshot: Snapshot = pytest.console_env.snapshot
        assert snapshot is not None
        migrationAssistant_deployTimeRole = snapshot.config['s3']['role']
        # Extract account ID from the role ARN
        account_number = migrationAssistant_deployTimeRole.split(':')[4]
        snapshot_region = self.config['SNAPSHOT_REGION']
        updated_s3_uri = self.setup_s3_bucket(account_number, snapshot_region, self.config)
        logger.info(f"Updated S3 URI: {updated_s3_uri}")

        # Delete the existing snapshot and snapshot repo from the cluster
        max_retries = 5
        retry_interval = 10

        for attempt in range(max_retries):
            try:
                snapshot.delete()
                snapshot.delete_snapshot_repo()
                logger.info("Successfully deleted existing snapshot and repository")
                break
            except Exception as e:
                if attempt < max_retries - 1:
                    logger.warning(f"Attempt {attempt + 1}/{max_retries} to delete snapshot failed: {str(e)}")
                    # Run aws configure to refresh credentials
                    aws_cmd = CommandRunner(
                        command_root="aws",
                        command_args={
                            "__positional__": ["configure", "list"],
                            "--profile": "default"
                        }
                    )
                    aws_cmd.run()
                    time.sleep(retry_interval)
                else:
                    logger.error(f"Failed to delete snapshot after {max_retries} attempts: {str(e)}")
                    raise

        # Create final snapshot
        logger.info("\n=== Creating Final Snapshot ===")
        large_snapshot_role = f"arn:aws:iam::{account_number}:role/LargeSnapshotAccessRole"
        snapshot_name = 'large-snapshot'
        snapshot_repo = 'migration_assistant_repo'
        endpoint = f"s3.{snapshot_region}.amazonaws.com"
        
        # Print all parameters for better logging and debugging
        logger.info("Snapshot Parameters:")
        logger.info(f"  - Snapshot Name: {snapshot_name}")
        logger.info(f"  - Repository Name: {snapshot_repo}")
        logger.info(f"  - S3 Bucket: {updated_s3_uri.split('/')[2]}")
        logger.info(f"  - S3 URI: {updated_s3_uri}")
        logger.info(f"  - S3 Region: {snapshot_region}")
        logger.info(f"  - S3 Endpoint: {endpoint}")
        logger.info(f"  - IAM Role: {large_snapshot_role}")
        logger.info(f"  - Max Snapshot Rate (MB/Node): {self.config['LARGE_SNAPSHOT_RATE_MB_PER_NODE']}")
        
        final_snapshot_config = {
            'snapshot_name': snapshot_name,
            's3': {
                'repo_uri': updated_s3_uri,
                'role': large_snapshot_role,
                'aws_region': snapshot_region,
                'endpoint': endpoint
            }
        }
        final_snapshot = S3Snapshot(final_snapshot_config, pytest.console_env.target_cluster)
        final_snapshot_result: CommandResult = final_snapshot.create(
            wait=True,
            max_snapshot_rate_mb_per_node=self.config['LARGE_SNAPSHOT_RATE_MB_PER_NODE']
        )
        assert final_snapshot_result.success, f"Failed to create final snapshot: {final_snapshot_result.error}"
        logger.info("Final Snapshot after migration and multiplication was created successfully")
        
        # Add detailed success information for easier snapshot retrieval
        logger.info("\n=== Final Snapshot Details ===")
        logger.info("Snapshot successfully stored at:")
        logger.info(f"  - S3 Bucket: {updated_s3_uri.split('/')[2]}")
        logger.info(f"  - S3 Path: {updated_s3_uri}")
        logger.info(f"  - Snapshot Name: {snapshot_name}")
        logger.info(f"  - Repository Name: {snapshot_repo}")
        logger.info(f"  - Region: {snapshot_region}")
        logger.info(f"To retrieve this snapshot, use the S3 URI: {updated_s3_uri}")

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
        
    def setup_s3_bucket(self, account_number: str, snapshot_region: str, test_config):
        """Check and create S3 bucket to store large snapshot"""
        cluster_version = test_config['CLUSTER_VERSION']
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
