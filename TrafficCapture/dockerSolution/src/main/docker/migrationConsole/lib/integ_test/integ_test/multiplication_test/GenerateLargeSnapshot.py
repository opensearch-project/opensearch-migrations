import logging
import time
import unittest
from console_link.cli import Context
from .MultiplicationTestUtils import (
    run_console_command,
    get_target_document_count_for_index
)
from .JenkinsParamConstants import (
    CONFIG_FILE_PATH,
    INDEX_NAME,
    INGESTED_DOC_COUNT,
    INDEX_SHARD_COUNT,
    BACKFILL_TIMEOUT_MINUTES,
    BACKFILL_POLL_INTERVAL,
    STABILITY_CHECK_COUNT,
    STABILITY_CHECK_INTERVAL,
    MULTIPLICATION_FACTOR_WITH_ORIGINAL,
    RFS_WORKER_COUNT
)

logger = logging.getLogger(__name__)


class GenerateLargeSnapshot(unittest.TestCase):
    """
    Phase : Generate Large Snapshot
    
    - console metadata migrate
    - console backfill scale
    - wait_for_backfill_completion_on_index()
    - console backfill stop
    """

    @classmethod
    def setUpClass(cls):
        """Set up test environment and configuration"""
        cls.CONSOLE_ENV = Context(CONFIG_FILE_PATH).env
        cls.INDEX_NAME = INDEX_NAME
        cls.INGESTED_DOC_COUNT = INGESTED_DOC_COUNT
        cls.INDEX_SHARD_COUNT = INDEX_SHARD_COUNT
        
        logger.info("Starting large snapshot generation test")
        logger.info(f"Expected {cls.INGESTED_DOC_COUNT} documents with {cls.INDEX_SHARD_COUNT} shards")

    def wait_for_backfill_completion_on_index(self, INDEX_NAME, EXPECTED_COUNT,
                                              TIMEOUT_MINUTES=BACKFILL_TIMEOUT_MINUTES):
        """
        Poll specific index document count until expected count is reached.
        Then verify stability with additional checks.
        """
        TIMEOUT_SECONDS = TIMEOUT_MINUTES * 60
        START_TIME = time.time()
        
        logger.info("Waiting for backfill completion on %s (expected count: %s, timeout: %s minutes)",
                    INDEX_NAME, EXPECTED_COUNT, TIMEOUT_MINUTES)
        
        # Phase 1: Wait for expected document count
        while time.time() - START_TIME < TIMEOUT_SECONDS:
            ACTUAL_COUNT = get_target_document_count_for_index(INDEX_NAME)
            
            if ACTUAL_COUNT == EXPECTED_COUNT:
                logger.info(f"Expected document count reached: {ACTUAL_COUNT}")
                break
            elif ACTUAL_COUNT > EXPECTED_COUNT:
                self.fail(f"Document count exceeded expected: {ACTUAL_COUNT} > {EXPECTED_COUNT}")
            
            logger.debug(f"Current count: {ACTUAL_COUNT}, expected: {EXPECTED_COUNT}, "
                         f"waiting {BACKFILL_POLL_INTERVAL} seconds...")
            time.sleep(BACKFILL_POLL_INTERVAL)
        else:
            FINAL_COUNT = get_target_document_count_for_index(INDEX_NAME)
            self.fail(f"Backfill timed out after {TIMEOUT_MINUTES} minutes. "
                      f"Final count: {FINAL_COUNT}")
        
        # Phase 2: Verify stability with additional checks
        logger.info("Verifying document count stability...")
        for i in range(STABILITY_CHECK_COUNT):
            time.sleep(STABILITY_CHECK_INTERVAL)
            ACTUAL_COUNT = get_target_document_count_for_index(INDEX_NAME)
            if ACTUAL_COUNT != EXPECTED_COUNT:
                self.fail(f"Document count became unstable: {ACTUAL_COUNT} != {EXPECTED_COUNT}")
            logger.debug(f"Stability check {i + 1}/{STABILITY_CHECK_COUNT}: count = {ACTUAL_COUNT}")
        
        logger.info("Backfill completed successfully with stable document count")

    def test_large_snapshot_generation(self):
        """
        Main test method that performs the generation phase of the multiplication workflow.
        """
        logger.info("=== Starting Large Snapshot Generation Phase ===")
        
        # Step 1: Migrate metadata
        logger.info("Phase 2, Step 1: Migrating metadata")
        run_console_command([
            "console", "metadata", "migrate",
            "--index-allowlist", INDEX_NAME,
            "--index-template-allowlist", "''",
            "--component-template-allowlist", "''",
            "--allow-loose-version-matching"
        ])
        
        # Step 2: Start backfill with scaling
        logger.info(f"Phase 2, Step 2: Starting backfill with {RFS_WORKER_COUNT} workers")
        run_console_command(["console", "backfill", "scale", str(RFS_WORKER_COUNT)])
        
        # Step 3: Wait for backfill completion
        logger.info("Phase 2, Step 3: Waiting for backfill completion")
        EXPECTED_COUNT = self.INGESTED_DOC_COUNT * MULTIPLICATION_FACTOR_WITH_ORIGINAL
        logger.info(f"Expecting {EXPECTED_COUNT} documents after transformation "
                    f"({self.INGESTED_DOC_COUNT} ingested × {MULTIPLICATION_FACTOR_WITH_ORIGINAL} multiplication)")
        
        self.wait_for_backfill_completion_on_index(
            INDEX_NAME=INDEX_NAME,
            EXPECTED_COUNT=EXPECTED_COUNT,
            TIMEOUT_MINUTES=BACKFILL_TIMEOUT_MINUTES
        )
        
        # Step 4: Stop backfill
        logger.info("Phase 2, Step 4: Stopping backfill")
        run_console_command(["console", "backfill", "stop"])
        
        # Step 5: Final verification
        logger.info("Phase 2, Step 5: Final verification")
        FINAL_COUNT = get_target_document_count_for_index(INDEX_NAME)
        self.assertEqual(FINAL_COUNT, EXPECTED_COUNT,
                         f"Final document count mismatch in {INDEX_NAME}: {FINAL_COUNT} != {EXPECTED_COUNT}")
        
        logger.info("=== Large Snapshot Generation Phase Completed Successfully! ===")
        logger.info(f"Successfully migrated {self.INGESTED_DOC_COUNT} documents to '{INDEX_NAME}' "
                    f"with {MULTIPLICATION_FACTOR_WITH_ORIGINAL}x multiplication (total: {FINAL_COUNT} documents)")
        logger.info("Ready for serving phase (large snapshot creation)")

    @classmethod
    def tearDownClass(cls):
        """Clean up after test completion"""
        logger.info("Generation phase cleanup completed")


if __name__ == "__main__":
    # Configure logging for standalone execution
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # Run the test
    unittest.main()
