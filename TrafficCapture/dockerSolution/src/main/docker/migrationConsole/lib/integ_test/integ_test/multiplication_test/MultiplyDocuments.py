import logging
import os
import time
import unittest
from console_link.cli import Context
from integ_test.multiplication_test.MultiplicationTestUtils import (
    run_console_command,
    get_target_document_count_for_index
)
from integ_test.multiplication_test import JenkinsParamConstants as constants

# Override constants with environment variables if present (Jenkins parameter injection)
CONFIG_FILE_PATH = os.getenv('CONFIG_FILE_PATH', constants.CONFIG_FILE_PATH)
INDEX_NAME = os.getenv('INDEX_NAME', constants.INDEX_NAME)
INGESTED_DOC_COUNT = int(os.getenv('DOCS_PER_BATCH', str(constants.INGESTED_DOC_COUNT)))
INDEX_SHARD_COUNT = int(os.getenv('NUM_SHARDS', str(constants.INDEX_SHARD_COUNT)))
BACKFILL_TIMEOUT_MINUTES = constants.BACKFILL_TIMEOUT_MINUTES  # This can stay as constant
BACKFILL_POLL_INTERVAL = constants.BACKFILL_POLL_INTERVAL  # This can stay as constant
STABILITY_CHECK_COUNT = constants.STABILITY_CHECK_COUNT  # This can stay as constant
STABILITY_CHECK_INTERVAL = constants.STABILITY_CHECK_INTERVAL  # This can stay as constant
MULTIPLICATION_FACTOR_WITH_ORIGINAL = int(os.getenv(
    'MULTIPLICATION_FACTOR', str(constants.MULTIPLICATION_FACTOR_WITH_ORIGINAL)))
RFS_WORKER_COUNT = int(os.getenv('RFS_WORKERS', str(constants.RFS_WORKER_COUNT)))

logger = logging.getLogger(__name__)


class ProgressTracker:
    """Helper class to track migration progress and calculate ETAs"""
    
    def __init__(self, expected_count):
        self.expected_count = expected_count
        self.start_time = time.time()
        self.previous_count = 0
        self.previous_time = self.start_time
        self.migration_rates = []  # Store recent rates for smoothing
        
    def update(self, current_count):
        """Update progress and calculate metrics"""
        current_time = time.time()
        elapsed_total = current_time - self.start_time
        elapsed_since_last = current_time - self.previous_time
        
        # Calculate current migration rate (docs per second)
        if elapsed_since_last > 0 and current_count > self.previous_count:
            current_rate = (current_count - self.previous_count) / elapsed_since_last
            self.migration_rates.append(current_rate)
            # Keep only last 5 rates for smoothing
            if len(self.migration_rates) > 5:
                self.migration_rates.pop(0)
        
        # Calculate average rate for ETA
        avg_rate = sum(self.migration_rates) / len(self.migration_rates) if self.migration_rates else 0
        
        # Calculate progress percentage
        progress_pct = (current_count / self.expected_count) * 100 if self.expected_count > 0 else 0
        
        # Calculate ETA
        remaining_docs = self.expected_count - current_count
        eta_seconds = remaining_docs / avg_rate if avg_rate > 0 and remaining_docs > 0 else 0
        
        # Update tracking variables
        self.previous_count = current_count
        self.previous_time = current_time
        
        return {
            'current_count': current_count,
            'expected_count': self.expected_count,
            'progress_pct': progress_pct,
            'elapsed_total': elapsed_total,
            'current_rate': self.migration_rates[-1] if self.migration_rates else 0,
            'avg_rate': avg_rate,
            'eta_seconds': eta_seconds,
            'remaining_docs': remaining_docs
        }
    
    @staticmethod
    def format_duration(seconds):
        """Format duration in human-readable format"""
        if seconds < 60:
            return f"{int(seconds)}s"
        elif seconds < 3600:
            minutes = int(seconds // 60)
            secs = int(seconds % 60)
            return f"{minutes}m {secs}s"
        else:
            hours = int(seconds // 3600)
            minutes = int((seconds % 3600) // 60)
            return f"{hours}h {minutes}m"


class MultiplyDocuments(unittest.TestCase):
    """
    Phase : Multiply documents using RFS and a transformer
    
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
        
        logger.info("Starting document multiplication test")
        logger.info(f"Expected {cls.INGESTED_DOC_COUNT} documents with {cls.INDEX_SHARD_COUNT} shards")

    def wait_for_backfill_completion_on_index(self, INDEX_NAME, EXPECTED_COUNT,
                                              TIMEOUT_MINUTES=BACKFILL_TIMEOUT_MINUTES):
        """
        Poll specific index document count until expected count is reached.
        Then verify stability with additional checks.
        """
        TIMEOUT_SECONDS = TIMEOUT_MINUTES * 60
        START_TIME = time.time()
        
        logger.info("Starting backfill monitoring: %s (target: %s documents, timeout: %s minutes)",
                    INDEX_NAME, EXPECTED_COUNT, TIMEOUT_MINUTES)
        
        # Initialize progress tracker
        progress_tracker = ProgressTracker(EXPECTED_COUNT)
        
        # Phase 1: Wait for expected document count
        logger.info(f"Migration Progress: 0/{EXPECTED_COUNT} documents (0.0%) | Starting backfill monitoring...")
        
        while time.time() - START_TIME < TIMEOUT_SECONDS:
            ACTUAL_COUNT = get_target_document_count_for_index(INDEX_NAME)
            
            # Update progress and get metrics for logs
            metrics = progress_tracker.update(ACTUAL_COUNT)
            if metrics['current_count'] == 0:
                logger.info(
                    f"Migration Progress: {metrics['current_count']}/{metrics['expected_count']} "
                    f"documents (0.0%) | "
                    f"Elapsed: {ProgressTracker.format_duration(metrics['elapsed_total'])} | "
                    f"Waiting for migration to start..."
                )
            elif metrics['avg_rate'] > 0:
                eta_str = (ProgressTracker.format_duration(metrics['eta_seconds'])
                           if metrics['eta_seconds'] > 0 else "calculating...")
                elapsed_str = ProgressTracker.format_duration(metrics['elapsed_total'])
                progress_str = f"{metrics['current_count']}/{metrics['expected_count']}"
                rate_str = f"{metrics['avg_rate']:.1f} docs/sec"
                pct_str = f"({metrics['progress_pct']:.1f}%)"
                logger.info(f"Migration Progress: {progress_str} documents {pct_str} | "
                            f"Rate: {rate_str} | Elapsed: {elapsed_str} | ETA: {eta_str}")
            else:
                logger.info(f"Migration Progress: {metrics['current_count']}/{metrics['expected_count']} "
                            f"documents ({metrics['progress_pct']:.1f}%) | "
                            f"Elapsed: {ProgressTracker.format_duration(metrics['elapsed_total'])} | "
                            f"Rate: calculating...")
            
            if ACTUAL_COUNT == EXPECTED_COUNT:
                total_time = time.time() - START_TIME
                final_rate = EXPECTED_COUNT / total_time if total_time > 0 else 0
                logger.info(f"Target reached: {ACTUAL_COUNT}/{EXPECTED_COUNT} documents (100.0%) | "
                            f"Total time: {ProgressTracker.format_duration(total_time)} | "
                            f"Avg rate: {final_rate:.1f} docs/sec")
                break
            elif ACTUAL_COUNT > EXPECTED_COUNT:
                self.fail(f"Document count exceeded expected: {ACTUAL_COUNT} > {EXPECTED_COUNT}")
            
            time.sleep(BACKFILL_POLL_INTERVAL)
        else:
            FINAL_COUNT = get_target_document_count_for_index(INDEX_NAME)
            self.fail(f"Backfill timed out after {TIMEOUT_MINUTES} minutes. "
                      f"Final count: {FINAL_COUNT}/{EXPECTED_COUNT}")
        
        # Phase 2: Verify stability with enhanced logging
        stability_duration = STABILITY_CHECK_COUNT * STABILITY_CHECK_INTERVAL
        logger.info(f"Starting stability verification ({STABILITY_CHECK_COUNT} checks over "
                    f"{ProgressTracker.format_duration(stability_duration)})...")
        
        for i in range(STABILITY_CHECK_COUNT):
            time.sleep(STABILITY_CHECK_INTERVAL)
            ACTUAL_COUNT = get_target_document_count_for_index(INDEX_NAME)
            
            if ACTUAL_COUNT != EXPECTED_COUNT:
                self.fail(f"Document count became unstable: {ACTUAL_COUNT} != {EXPECTED_COUNT} "
                          f"(check {i + 1}/{STABILITY_CHECK_COUNT})")
            
            logger.info(f"Stability Check {i + 1}/{STABILITY_CHECK_COUNT}: "
                        f"Count = {ACTUAL_COUNT} (STABLE)")
        
        logger.info(f"Document count verified as stable: {EXPECTED_COUNT} documents")

    def test_multiply_documents(self):
        """
        Main test method that actually performs the multiplication of documents.
        Assumptions:
            - Clusters and console are connected successfully.
            - Source and target cluster config is exactly the same in `cdk.context.json`.
            - User has performed a CDK deployment.
            - User to run `CleanUpAndPrepare.py` (Phase 1) before this phase.
        """
        logger.info("=== Starting Document Multiplication Phase ===")
        
        # Step 1: Start backfill with scaling
        logger.info(f"Phase 2, Step 1: Starting backfill with {RFS_WORKER_COUNT} workers")
        run_console_command(["console", "backfill", "scale", str(RFS_WORKER_COUNT)])
        
        # Step 2: Wait for backfill completion
        logger.info("Phase 2, Step 2: Waiting for backfill completion")
        EXPECTED_COUNT = self.INGESTED_DOC_COUNT * MULTIPLICATION_FACTOR_WITH_ORIGINAL
        logger.info(f"Expecting {EXPECTED_COUNT} documents after transformation "
                    f"({self.INGESTED_DOC_COUNT} ingested × {MULTIPLICATION_FACTOR_WITH_ORIGINAL} multiplication)")
        
        self.wait_for_backfill_completion_on_index(
            INDEX_NAME=INDEX_NAME,
            EXPECTED_COUNT=EXPECTED_COUNT,
            TIMEOUT_MINUTES=BACKFILL_TIMEOUT_MINUTES
        )
        
        # Step 3: Stop backfill
        logger.info("Phase 2, Step 3: Stopping backfill")
        run_console_command(["console", "backfill", "stop"])
        
        # Step 4: Final verification
        logger.info("Phase 2, Step 4: Final verification")
        FINAL_COUNT = get_target_document_count_for_index(INDEX_NAME)
        self.assertEqual(FINAL_COUNT, EXPECTED_COUNT,
                         f"Final document count mismatch in {INDEX_NAME}: {FINAL_COUNT} != {EXPECTED_COUNT}")
        
        logger.info("=== Document Multiplication Phase Completed Successfully! ===")
        logger.info(f"Successfully migrated {self.INGESTED_DOC_COUNT} documents to '{INDEX_NAME}' "
                    f"with {MULTIPLICATION_FACTOR_WITH_ORIGINAL}x multiplication (total: {FINAL_COUNT} documents)")
        logger.info("Ready for serving phase (large snapshot creation)")

    @classmethod
    def tearDownClass(cls):
        """Clean up after test completion"""
        logger.info("Multiplication phase cleanup completed")


if __name__ == "__main__":
    # Configure logging for standalone execution
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # Run the test
    unittest.main()
