import json
import logging
import subprocess
import time
import unittest
from console_link.cli import Context

logger = logging.getLogger(__name__)

# Test Configuration Constants - Modify these values as needed
CONFIG_FILE_PATH = "/config/migration_services.yaml"
UNIQUE_ID = "very_basic_test"
INDEX_NAME = "basic_index"
DOCUMENT_ID = "1"
EXPECTED_DOC_COUNT = 1

# Timeout Configuration (in minutes)
SNAPSHOT_TIMEOUT_MINUTES = 5
BACKFILL_TIMEOUT_MINUTES = 30
COMMAND_TIMEOUT_SECONDS = 300

# Polling Intervals (in seconds)
SNAPSHOT_POLL_INTERVAL = 3
BACKFILL_POLL_INTERVAL = 5
STABILITY_CHECK_INTERVAL = 15
STABILITY_CHECK_COUNT = 4

# Test Document Template
TEST_DOCUMENT = {
    "title": "Basic Migration Test Document",
    "content": "This document tests basic metadata migration and backfill",
    "timestamp": "2025-01-01T00:00:00Z"
}


class VeryBasicTest(unittest.TestCase):
    """
    Very basic end-to-end migration test using console commands directly.
    
    This test performs:
    1. Clear source and target clusters
    2. Ingest 1 document into source cluster (basic_index)
    3. Create snapshot and wait for completion
    4. Migrate metadata
    5. Start backfill and wait for document to appear on target
    6. Stop backfill
    """

    @classmethod
    def setUpClass(cls):
        """Set up test environment and configuration"""
        cls.console_env = Context(CONFIG_FILE_PATH).env
        cls.unique_id = UNIQUE_ID
        cls.index_name = INDEX_NAME
        cls.doc_id = DOCUMENT_ID
        cls.expected_doc_count = EXPECTED_DOC_COUNT
        
        # Test document to ingest (add unique_id to template)
        cls.test_document = TEST_DOCUMENT.copy()
        cls.test_document["test_id"] = cls.unique_id
        
        logger.info(f"Starting very basic migration test with unique_id: {cls.unique_id}")

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

    def get_target_document_count(self):
        """Get document count from target cluster for basic_index"""
        try:
            result = self.run_console_command([
                "console", "clusters", "curl", "target_cluster",
                "-XGET", f"/{self.index_name}/_count"
            ])
            
            # Parse JSON response to get count
            response_data = json.loads(result.stdout)
            count = response_data.get("count", 0)
            logger.debug(f"Target cluster document count: {count}")
            return count
        except (json.JSONDecodeError, KeyError) as e:
            logger.warning(f"Failed to parse document count response: {e}")
            return 0

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

    def wait_for_backfill_completion(self, expected_count, timeout_minutes=BACKFILL_TIMEOUT_MINUTES):
        """
        Poll target cluster document count until expected count is reached.
        Then verify stability with additional checks.
        """
        timeout_seconds = timeout_minutes * 60
        start_time = time.time()
        
        logger.info(f"Waiting for backfill completion (expected count: {expected_count}, "
                    f"timeout: {timeout_minutes} minutes)")
        
        # Phase 1: Wait for expected document count
        while time.time() - start_time < timeout_seconds:
            actual_count = self.get_target_document_count()
            
            if actual_count == expected_count:
                logger.info(f"Expected document count reached: {actual_count}")
                break
            elif actual_count > expected_count:
                self.fail(f"Document count exceeded expected: {actual_count} > {expected_count}")
            
            logger.debug(f"Current count: {actual_count}, expected: {expected_count}, "
                         f"waiting {BACKFILL_POLL_INTERVAL} seconds...")
            time.sleep(BACKFILL_POLL_INTERVAL)
        else:
            self.fail(f"Backfill timed out after {timeout_minutes} minutes. "
                      f"Final count: {self.get_target_document_count()}")
        
        # Phase 2: Verify stability with additional checks
        logger.info("Verifying document count stability...")
        for i in range(STABILITY_CHECK_COUNT):
            time.sleep(STABILITY_CHECK_INTERVAL)
            actual_count = self.get_target_document_count()
            if actual_count != expected_count:
                self.fail(f"Document count became unstable: {actual_count} != {expected_count}")
            logger.debug(f"Stability check {i + 1}/{STABILITY_CHECK_COUNT}: count = {actual_count}")
        
        logger.info("Backfill completed successfully with stable document count")

    def test_very_basic_migration(self):
        """
        Main test method that performs the complete migration workflow.
        """
        logger.info("=== Starting Very Basic Migration Test ===")
        
        # Step 1: Clear both clusters
        logger.info("Step 1: Clearing source and target clusters")
        self.run_console_command(["console", "clusters", "clear-indices", "source"])
        self.run_console_command(["console", "clusters", "clear-indices", "target"])
        
        # Step 2: Ingest test document to source cluster
        logger.info(f"Step 2: Ingesting test document to source cluster (index: {self.index_name})")
        self.run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XPUT", f"/{self.index_name}/_doc/{self.doc_id}",
            "-d", json.dumps(self.test_document),
            "-H", "Content-Type: application/json"
        ])
        
        # Verify document was created on source
        logger.info("Verifying document exists on source cluster")
        self.run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XGET", f"/{self.index_name}/_doc/{self.doc_id}"
        ])
        
        # Step 3: Create snapshot and wait for completion
        logger.info("Step 3: Creating snapshot")
        self.run_console_command(["console", "snapshot", "create"])
        self.wait_for_snapshot_completion(timeout_minutes=SNAPSHOT_TIMEOUT_MINUTES)
        
        # Step 4: Migrate metadata (synchronous operation)
        logger.info("Step 4: Migrating metadata")
        self.run_console_command(["console", "metadata", "migrate"])
        
        # Step 5: Start backfill and wait for completion
        logger.info("Step 5: Starting backfill")
        self.run_console_command(["console", "backfill", "start"])
        
        # Wait for document to appear on target cluster
        self.wait_for_backfill_completion(
            expected_count=self.expected_doc_count,
            timeout_minutes=BACKFILL_TIMEOUT_MINUTES
        )
        
        # Step 6: Stop backfill
        logger.info("Step 6: Stopping backfill")
        self.run_console_command(["console", "backfill", "stop"])
        
        # Final verification
        final_count = self.get_target_document_count()
        self.assertEqual(final_count, self.expected_doc_count,
                         f"Final document count mismatch: {final_count} != {self.expected_doc_count}")
        
        logger.info("=== Very Basic Migration Test Completed Successfully! ===")

    @classmethod
    def tearDownClass(cls):
        """Clean up after test completion"""
        logger.info("Test cleanup completed")


if __name__ == "__main__":
    # Configure logging for standalone execution
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # Run the test
    unittest.main()
