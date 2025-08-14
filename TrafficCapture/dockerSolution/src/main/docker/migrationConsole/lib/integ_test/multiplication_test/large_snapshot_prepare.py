import json
import logging
import os
import shutil
import subprocess
import time
import unittest
from console_link.cli import Context
from .default_operations import DefaultOperationsLibrary
from .large_snapshot_constants import (
    CONFIG_FILE_PATH,
    UNIQUE_ID,
    INDEX_NAME,
    INGESTED_DOC_COUNT,
    INDEX_SHARD_COUNT,
    TEST_DOCUMENT,
    PHASE_STATUS_DIR,
    SHARED_STATE_DIR,
    COMMAND_TIMEOUT_SECONDS,
    SNAPSHOT_REPO_NAME,
    S3_BUCKET_URI_PREFIX,
    S3_BUCKET_SUFFIX,
    TEST_REGION,
    TEST_STAGE,
    TRANSFORMATION_DIRECTORY,
    MULTIPLICATION_FACTOR_WITH_ORIGINAL,
    TRANSFORMATION_FILE_PATH,
    SNAPSHOT_TIMEOUT_MINUTES,
    SNAPSHOT_POLL_INTERVAL,
    PREPARE_COMPLETE_FILE
)

logger = logging.getLogger(__name__)


class LargeSnapshotPrepareTest(unittest.TestCase):
    """
    Phase 1: Large Snapshot Preparation Test
    
    This test performs the preparation phase of the large snapshot migration:
    1. Clear source and target clusters
    2. Create index with specified shards
    3. Ingest test data and verify
    4. Create transformation configuration
    5. Create snapshot and wait for completion
    """

    @classmethod
    def setUpClass(cls):
        """Set up test environment and configuration"""
        cls.CONSOLE_ENV = Context(CONFIG_FILE_PATH).env
        cls.UNIQUE_ID = UNIQUE_ID
        cls.INDEX_NAME = INDEX_NAME
        cls.INGESTED_DOC_COUNT = INGESTED_DOC_COUNT
        cls.INDEX_SHARD_COUNT = INDEX_SHARD_COUNT
        
        # Test document template (add unique_id to template)
        cls.TEST_DOCUMENT_TEMPLATE = TEST_DOCUMENT.copy()
        cls.TEST_DOCUMENT_TEMPLATE["test_id"] = cls.UNIQUE_ID
        
        # Create phase status and shared state directories
        os.makedirs(PHASE_STATUS_DIR, exist_ok=True)
        os.makedirs(SHARED_STATE_DIR, exist_ok=True)
        
        logger.info(f"Starting large snapshot preparation test with unique_id: {cls.UNIQUE_ID}")
        logger.info(f"Will ingest {cls.INGESTED_DOC_COUNT} documents with {cls.INDEX_SHARD_COUNT} shards")

    def run_console_command(self, command_args, timeout=COMMAND_TIMEOUT_SECONDS):
        """
        Execute a console command and return the result.
        Fails immediately on any error (no retries).
        """
        try:
            logger.info(f"Executing command: {' '.join(command_args)}")
            result = subprocess.run(
                command_args,
                capture_output=True,
                text=True,
                timeout=timeout,
                check=True
            )
            logger.info(f"Command succeeded: {result.stdout.strip()}")
            return result
        except subprocess.CalledProcessError as e:
            error_msg = f"Command failed: {' '.join(command_args)}\nStdout: {e.stdout}\nStderr: {e.stderr}"
            logger.error(error_msg)
            self.fail(error_msg)
        except subprocess.TimeoutExpired:
            error_msg = f"Command timed out after {timeout}s: {' '.join(command_args)}"
            logger.error(error_msg)
            self.fail(error_msg)

    def cleanup_existing_snapshots(self):
        """
        Clean up any existing snapshots and repositories to ensure clean test state.
        Continues on errors since resources may not exist.
        """
        logger.info("Cleaning up existing snapshots and repositories")
        
        # Get account ID dynamically
        ops = DefaultOperationsLibrary()
        account_id = ops.extract_account_id_from_config()
        
        # Build S3 bucket URI dynamically
        s3_bucket_uri = f"{S3_BUCKET_URI_PREFIX}{account_id}-{TEST_STAGE}-{TEST_REGION}{S3_BUCKET_SUFFIX}"
        
        cleanup_commands = [
            # 1. Delete snapshot (ignore errors if doesn't exist)
            ["console", "snapshot", "delete", "--acknowledge-risk"],
            
            # 2. Delete repository from cluster
            ["console", "clusters", "curl", "source_cluster", "-XDELETE",
             f"/_snapshot/{SNAPSHOT_REPO_NAME}"],
            
            # 3. Clear S3 repository contents and directory
            ["aws", "s3", "rm", s3_bucket_uri, "--recursive"]
        ]
        
        for cmd in cleanup_commands:
            try:
                self.run_console_command(cmd)
                logger.info(f"Cleanup command succeeded: {' '.join(cmd)}")
            except Exception:
                logger.warning("Cleanup command failed (expected if resource doesn't exist): "
                               f"{' '.join(cmd)}")

    def clear_clusters(self):
        """Clear both source and target clusters and clean up existing snapshots"""
        
        # First clean up any existing snapshots/repos
        self.cleanup_existing_snapshots()
        
        # Then clear cluster indices
        self.run_console_command([
            "console", "clusters", "clear-indices", "--cluster", "source",
            "--acknowledge-risk"
        ])
        self.run_console_command([
            "console", "clusters", "clear-indices", "--cluster", "target",
            "--acknowledge-risk"
        ])

    def create_index_with_shards(self):
        """Create index with specified number of shards"""
        INDEX_SETTINGS = {
            "settings": {
                "number_of_shards": self.INDEX_SHARD_COUNT,
                "number_of_replicas": 0
            }
        }
        
        self.run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XPUT", f"/{self.INDEX_NAME}",
            "-d", json.dumps(INDEX_SETTINGS),
            "-H", "Content-Type: application/json"
        ])
        
        logger.info(f"Index '{self.INDEX_NAME}' created successfully")

    def ingest_test_data(self):
        """Ingest test documents to source cluster using bulk API"""
        
        BULK_BODY_LINES = []
        for DOC_INDEX in range(1, self.INGESTED_DOC_COUNT + 1):
            DOC_ID = str(DOC_INDEX)
            
            # Create document from template
            TEST_DOCUMENT = self.TEST_DOCUMENT_TEMPLATE.copy()
            TEST_DOCUMENT["doc_number"] = str(DOC_INDEX)
            
            # Add index action line
            INDEX_ACTION = {"index": {"_index": self.INDEX_NAME, "_id": DOC_ID}}
            BULK_BODY_LINES.append(json.dumps(INDEX_ACTION))
            
            # Add document source line
            BULK_BODY_LINES.append(json.dumps(TEST_DOCUMENT))
        
        # Join with newlines and add final newline (required by bulk API)
        BULK_BODY = "\n".join(BULK_BODY_LINES) + "\n"
        
        # Execute bulk request
        self.run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XPOST", "/_bulk",
            "-d", BULK_BODY,
            "-H", "Content-Type: application/x-ndjson"
        ])
        
        # Verify documents were created by checking count
        logger.info("Verifying documents exist on source cluster")
        RESULT = self.run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XGET", f"/{self.INDEX_NAME}/_count"
        ])
        
        # Parse response and assert correct count
        RESPONSE_DATA = json.loads(RESULT.stdout)
        ACTUAL_COUNT = RESPONSE_DATA.get("count", 0)
        self.assertEqual(ACTUAL_COUNT, self.INGESTED_DOC_COUNT,
                         f"Expected {self.INGESTED_DOC_COUNT} documents, found {ACTUAL_COUNT}")
        
        logger.info(f"Successfully ingested {ACTUAL_COUNT} documents to source cluster")

    def create_transformation_config(self):
        """Create transformation file with document multiplication only"""
        
        try:
            shutil.rmtree(TRANSFORMATION_DIRECTORY)
            logger.info(f"Removed existing {TRANSFORMATION_DIRECTORY} directory")
        except FileNotFoundError:
            logger.info("No transformation files detected to cleanup")
        
        # Create multiplication transformation JavaScript
        initialization_script = (
            f"const MULTIPLICATION_FACTOR_WITH_ORIGINAL = {MULTIPLICATION_FACTOR_WITH_ORIGINAL}; "
            "function transform(document) { "
            "if (!document) { throw new Error(\"No source_document was defined - nothing to transform!\"); } "
            "const indexCommandMap = document.get(\"index\"); "
            "const originalSource = document.get(\"source\"); "
            "const originalId = indexCommandMap.get(\"_id\"); "
            "const docsToCreate = []; "
            "for (let i = 0; i < MULTIPLICATION_FACTOR_WITH_ORIGINAL; i++) { "
            "const newIndexMap = new Map(indexCommandMap); "
            "const newId = (i === 0) ? originalId : originalId + '_' + i; "
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
        
        multiplication_transform = {
            "JsonJSTransformerProvider": {
                "initializationScript": initialization_script,
                "bindingsObject": "{}"
            }
        }
        
        # Create transformation config with only multiplication
        combined_config = [multiplication_transform]
        
        # Save to file
        ops = DefaultOperationsLibrary()
        ops.create_transformation_json_file(combined_config, TRANSFORMATION_FILE_PATH)
        logger.info(f"Created transformation config at {TRANSFORMATION_FILE_PATH}")

    def wait_for_snapshot_completion(self, timeout_minutes=SNAPSHOT_TIMEOUT_MINUTES):
        """
        Poll snapshot status every few seconds until completion or timeout.
        """
        timeout_seconds = timeout_minutes * 60
        start_time = time.time()
        
        logger.info(f"Waiting for snapshot completion (timeout: {timeout_minutes} minutes)")
        
        while time.time() - start_time < timeout_seconds:
            result = self.run_console_command(["console", "snapshot", "status"])
            
            # Check if snapshot is complete (looking for success indicators in output)
            output = result.stdout.lower()
            if "success" in output or "completed" in output:
                logger.info("Snapshot creation completed successfully")
                return
            elif "failed" in output or "error" in output:
                self.fail(f"Snapshot creation failed: {result.stdout}")
            
            logger.debug(f"Snapshot still in progress, waiting {SNAPSHOT_POLL_INTERVAL} seconds...")
            time.sleep(SNAPSHOT_POLL_INTERVAL)
        
        self.fail(f"Snapshot creation timed out after {timeout_minutes} minutes")

    def mark_phase_complete(self):
        """Mark the prepare phase as complete"""
        with open(PREPARE_COMPLETE_FILE, 'w') as f:
            f.write(f"Prepare phase completed at {time.time()}\n")
            f.write(f"Index: {self.INDEX_NAME}\n")
            f.write(f"Documents ingested: {self.INGESTED_DOC_COUNT}\n")
            f.write(f"Shards: {self.INDEX_SHARD_COUNT}\n")
        logger.info(f"Marked prepare phase complete: {PREPARE_COMPLETE_FILE}")

    def test_large_snapshot_prepare(self):
        """
        Main test method that performs the preparation phase of the migration workflow.
        """
        logger.info("=== Starting Large Snapshot Preparation Phase ===")
        
        # Step 1: Clear clusters and cleanup (combined)
        self.clear_clusters()
        logger.info("Step 1: Clearing source and target clusters")
        
        # Step 1.5: Create index with specified shards
        self.create_index_with_shards()
        logger.info(f"Step 1.5: Creating index '{self.INDEX_NAME}' "
                    f"with {self.INDEX_SHARD_COUNT} shards")
        
        # Step 2: Ingest test data and verify
        self.ingest_test_data()
        logger.info(f"Step 2: Ingesting {self.INGESTED_DOC_COUNT} test documents "
                    f"to source cluster (index: {self.INDEX_NAME})")
        
        # Step 2.5: Create transformation configuration
        self.create_transformation_config()
        logger.info("Step 2.5: Creating transformation configuration")
        
        # Step 3: Create snapshot and wait for completion
        logger.info("Step 3: Creating snapshot")
        self.run_console_command(["console", "snapshot", "create"])
        self.wait_for_snapshot_completion(timeout_minutes=SNAPSHOT_TIMEOUT_MINUTES)
        
        # Mark phase complete
        self.mark_phase_complete()
        
        logger.info("=== Large Snapshot Preparation Phase Completed Successfully! ===")
        logger.info(f"Successfully prepared {self.INGESTED_DOC_COUNT} documents in '{self.INDEX_NAME}' "
                    f"with {self.INDEX_SHARD_COUNT} shards")
        logger.info("Ready for cooking phase (metadata migration and backfill)")

    @classmethod
    def tearDownClass(cls):
        """Clean up after test completion"""
        logger.info("Prepare phase cleanup completed")


if __name__ == "__main__":
    # Configure logging for standalone execution
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # Run the test
    unittest.main()
