import json
import logging
import os
import subprocess
import time
import unittest
from console_link.cli import Context
from .large_snapshot_constants import (
    CONFIG_FILE_PATH,
    UNIQUE_ID,
    INDEX_NAME,
    INGESTED_DOC_COUNT,
    INDEX_SHARD_COUNT,
    PHASE_STATUS_DIR,
    SHARED_STATE_DIR,
    COMMAND_TIMEOUT_SECONDS,
    PREPARE_COMPLETE_FILE,
    BACKFILL_TIMEOUT_MINUTES,
    BACKFILL_POLL_INTERVAL,
    STABILITY_CHECK_COUNT,
    STABILITY_CHECK_INTERVAL,
    FINAL_COUNT_FILE,
    COOK_COMPLETE_FILE,
    MULTIPLICATION_FACTOR_WITH_ORIGINAL,
    RFS_WORKER_COUNT
)

logger = logging.getLogger(__name__)


class LargeSnapshotCookTest(unittest.TestCase):
    """
    Phase 2: Large Snapshot Cook Test (Metadata Migration and Backfill)
    
    This test performs the cooking phase of the large snapshot migration:
    1. Verify prepare phase completed
    2. Migrate metadata
    3. Start backfill with scaling and wait for completion
    4. Stop backfill
    5. Final verification and save results
    """

    @classmethod
    def setUpClass(cls):
        """Set up test environment and configuration"""
        cls.CONSOLE_ENV = Context(CONFIG_FILE_PATH).env
        cls.UNIQUE_ID = UNIQUE_ID
        cls.INDEX_NAME = INDEX_NAME
        cls.INGESTED_DOC_COUNT = INGESTED_DOC_COUNT
        cls.INDEX_SHARD_COUNT = INDEX_SHARD_COUNT
        
        # Create phase status and shared state directories
        os.makedirs(PHASE_STATUS_DIR, exist_ok=True)
        os.makedirs(SHARED_STATE_DIR, exist_ok=True)
        
        logger.info(f"Starting large snapshot cooking test with unique_id: {cls.UNIQUE_ID}")
        logger.info(f"Expected {cls.INGESTED_DOC_COUNT} documents with {cls.INDEX_SHARD_COUNT} shards")

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

    def verify_prepare_phase_complete(self):
        """Verify that the prepare phase completed successfully"""
        logger.info("Verifying prepare phase completion")
        
        if not os.path.exists(PREPARE_COMPLETE_FILE):
            self.fail(f"Prepare phase not completed. Missing file: {PREPARE_COMPLETE_FILE}")
        
        with open(PREPARE_COMPLETE_FILE, 'r') as f:
            content = f.read()
            logger.info(f"Prepare phase status: {content.strip()}")
        
        logger.info("Prepare phase verification successful")

    def get_target_document_count_for_index(self, index_name):
        """Get document count from target cluster for any index"""
        try:
            result = self.run_console_command([
                "console", "clusters", "curl", "target_cluster",
                "-XGET", f"/{index_name}/_count"
            ])
            
            # Parse JSON response to get count
            response_data = json.loads(result.stdout)
            count = response_data.get("count", 0)
            logger.debug(f"Target cluster document count for {index_name}: {count}")
            return count
        except (json.JSONDecodeError, KeyError) as e:
            logger.warning("Failed to parse document count response for %s: %s", index_name, e)
            return 0

    def wait_for_backfill_completion_on_index(self, index_name, expected_count,
                                              timeout_minutes=BACKFILL_TIMEOUT_MINUTES):
        """
        Poll specific index document count until expected count is reached.
        Then verify stability with additional checks.
        """
        timeout_seconds = timeout_minutes * 60
        start_time = time.time()
        
        logger.info("Waiting for backfill completion on %s (expected count: %s, timeout: %s minutes)",
                    index_name, expected_count, timeout_minutes)
        
        # Phase 1: Wait for expected document count
        while time.time() - start_time < timeout_seconds:
            actual_count = self.get_target_document_count_for_index(index_name)
            
            if actual_count == expected_count:
                logger.info(f"Expected document count reached: {actual_count}")
                break
            elif actual_count > expected_count:
                self.fail(f"Document count exceeded expected: {actual_count} > {expected_count}")
            
            logger.debug(f"Current count: {actual_count}, expected: {expected_count}, "
                         f"waiting {BACKFILL_POLL_INTERVAL} seconds...")
            time.sleep(BACKFILL_POLL_INTERVAL)
        else:
            final_count = self.get_target_document_count_for_index(index_name)
            self.fail(f"Backfill timed out after {timeout_minutes} minutes. "
                      f"Final count: {final_count}")
        
        # Phase 2: Verify stability with additional checks
        logger.info("Verifying document count stability...")
        for i in range(STABILITY_CHECK_COUNT):
            time.sleep(STABILITY_CHECK_INTERVAL)
            actual_count = self.get_target_document_count_for_index(index_name)
            if actual_count != expected_count:
                self.fail(f"Document count became unstable: {actual_count} != {expected_count}")
            logger.debug(f"Stability check {i + 1}/{STABILITY_CHECK_COUNT}: count = {actual_count}")
        
        logger.info("Backfill completed successfully with stable document count")

    def save_final_count(self, final_count):
        """Save the final document count for the serve phase"""
        with open(FINAL_COUNT_FILE, 'w') as f:
            f.write(str(final_count))
        logger.info(f"Saved final count {final_count} to {FINAL_COUNT_FILE}")

    def mark_phase_complete(self, final_count):
        """Mark the cook phase as complete"""
        with open(COOK_COMPLETE_FILE, 'w') as f:
            f.write(f"Cook phase completed at {time.time()}\n")
            f.write(f"Index: {self.INDEX_NAME}\n")
            f.write(f"Final document count: {final_count}\n")
            f.write(f"Expected count: {self.INGESTED_DOC_COUNT * MULTIPLICATION_FACTOR_WITH_ORIGINAL}\n")
        logger.info(f"Marked cook phase complete: {COOK_COMPLETE_FILE}")

    def test_large_snapshot_cook(self):
        """
        Main test method that performs the cooking phase of the migration workflow.
        """
        logger.info("=== Starting Large Snapshot Cooking Phase ===")
        
        # Verify prepare phase completed
        self.verify_prepare_phase_complete()
        
        # Step 4: Migrate metadata (no transformations needed for metadata)
        logger.info("Step 4: Migrating metadata")
        self.run_console_command([
            "console", "metadata", "migrate",
            "--index-allowlist", "basic_index",
            "--index-template-allowlist", "''",
            "--component-template-allowlist", "''",
            "--allow-loose-version-matching"
        ])
        
        # Step 5: Start backfill with scaling and wait for completion
        logger.info(f"Step 5: Starting backfill with {RFS_WORKER_COUNT} workers")
        self.run_console_command(["console", "backfill", "scale", str(RFS_WORKER_COUNT)])
        
        # Calculate expected count dynamically (50 docs × 10 multiplication = 500 total)
        EXPECTED_COUNT = self.INGESTED_DOC_COUNT * MULTIPLICATION_FACTOR_WITH_ORIGINAL
        logger.info(f"Expecting {EXPECTED_COUNT} documents after transformation "
                    f"({self.INGESTED_DOC_COUNT} ingested × {MULTIPLICATION_FACTOR_WITH_ORIGINAL} multiplication)")
        
        # Wait for documents to appear on target cluster (same index name)
        self.wait_for_backfill_completion_on_index(
            index_name=INDEX_NAME,
            expected_count=EXPECTED_COUNT,
            timeout_minutes=BACKFILL_TIMEOUT_MINUTES
        )
        
        # Step 6: Stop backfill
        logger.info("Step 6: Stopping backfill")
        self.run_console_command(["console", "backfill", "stop"])
        
        # Final verification (check the same index)
        FINAL_COUNT = self.get_target_document_count_for_index(INDEX_NAME)
        self.assertEqual(FINAL_COUNT, EXPECTED_COUNT,
                         f"Final document count mismatch in {INDEX_NAME}: {FINAL_COUNT} != {EXPECTED_COUNT}")
        
        # Save results for serve phase
        self.save_final_count(FINAL_COUNT)
        self.mark_phase_complete(FINAL_COUNT)
        
        logger.info("=== Large Snapshot Cooking Phase Completed Successfully! ===")
        logger.info(f"Successfully migrated {self.INGESTED_DOC_COUNT} documents to '{INDEX_NAME}' "
                    f"with {MULTIPLICATION_FACTOR_WITH_ORIGINAL}x multiplication (total: {FINAL_COUNT} documents)")
        logger.info("Ready for serving phase (large snapshot creation)")

    @classmethod
    def tearDownClass(cls):
        """Clean up after test completion"""
        logger.info("Cook phase cleanup completed")


if __name__ == "__main__":
    # Configure logging for standalone execution
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # Run the test
    unittest.main()
