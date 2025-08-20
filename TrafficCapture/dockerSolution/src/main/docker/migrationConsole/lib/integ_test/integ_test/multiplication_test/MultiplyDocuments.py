import logging
import os
import time
import unittest
from console_link.cli import Context
from integ_test.multiplication_test.MultiplicationTestUtils import (
    run_console_command,
    get_target_document_count_for_index,
    get_environment_values,
    BACKFILL_POLL_INTERVAL,
    STABILITY_CHECK_INTERVAL,
    STABILITY_CHECK_COUNT
)

ENV_VALUES = get_environment_values()
CONFIG_FILE_PATH = os.getenv('CONFIG_FILE_PATH', '/config/migration_services.yaml')
INDEX_NAME = os.getenv('INDEX_NAME', 'basic_index')
INGESTED_DOC_COUNT = int(os.getenv('DOCS_PER_BATCH', '50'))
INDEX_SHARD_COUNT = int(os.getenv('NUM_SHARDS', '10'))
BACKFILL_TIMEOUT_MINUTES = int(float(os.getenv('BACKFILL_TIMEOUT_HOURS', '0.5')) * 60)
MULTIPLICATION_FACTOR = ENV_VALUES['multiplication_factor']
RFS_WORKER_COUNT = int(os.getenv('RFS_WORKERS', '5'))

logger = logging.getLogger(__name__)


class ProgressTracker:
    """Helper class to track migration progress and calculate ETAs"""
    
    def __init__(self, expected_count):
        self.expected_count = expected_count
        self.start_time = time.time()
        self.previous_count = 0
        self.previous_time = self.start_time
        self.migration_rates = []
        
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
    Part : Multiply documents using RFS and a transformer
    
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

    def wait_for_backfill_completion_on_index(self, index_name, expected_count,
                                              timeout_minutes=BACKFILL_TIMEOUT_MINUTES):
        """
        Poll specific index document count until expected count is reached.
        Then verify stability with additional checks.
        """
        timeout_seconds = timeout_minutes * 60
        start_time = time.time()
        
        logger.info("Starting backfill monitoring: %s (target: %s documents, timeout: %s minutes)",
                    index_name, expected_count, timeout_minutes)
        
        # Initialize progress tracker
        progress_tracker = ProgressTracker(expected_count)
        
        # Wait for expected document count
        logger.info(f"Migration Progress: 0/{expected_count} documents (0.0%) | Starting backfill monitoring...")
        
        while time.time() - start_time < timeout_seconds:
            actual_count = get_target_document_count_for_index(index_name)
            
            # Update progress and get metrics for logs
            metrics = progress_tracker.update(actual_count)
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
            
            if actual_count == expected_count:
                total_time = time.time() - start_time
                final_rate = expected_count / total_time if total_time > 0 else 0
                logger.info(f"Target reached: {actual_count}/{expected_count} documents (100.0%) | "
                            f"Total time: {ProgressTracker.format_duration(total_time)} | "
                            f"Avg rate: {final_rate:.1f} docs/sec")
                break
            elif actual_count > expected_count:
                self.fail(f"Document count exceeded expected: {actual_count} > {expected_count}")
            
            time.sleep(BACKFILL_POLL_INTERVAL)
        else:
            final_count = get_target_document_count_for_index(index_name)
            self.fail(f"Backfill timed out after {timeout_minutes} minutes. "
                      f"Final count: {final_count}/{expected_count}")
        
        # Verify stability with enhanced logging
        stability_duration = STABILITY_CHECK_COUNT * STABILITY_CHECK_INTERVAL
        logger.info(f"Starting stability verification ({STABILITY_CHECK_COUNT} checks over "
                    f"{ProgressTracker.format_duration(stability_duration)})...")
        
        for i in range(STABILITY_CHECK_COUNT):
            time.sleep(STABILITY_CHECK_INTERVAL)
            actual_count = get_target_document_count_for_index(index_name)
            
            if actual_count != expected_count:
                self.fail(f"Document count became unstable: {actual_count} != {expected_count} "
                          f"(check {i + 1}/{STABILITY_CHECK_COUNT})")
            
            logger.info(f"Stability Check {i + 1}/{STABILITY_CHECK_COUNT}: "
                        f"Count = {actual_count} (STABLE)")
        
        logger.info(f"Document count verified as stable: {expected_count} documents")

    def test_multiply_documents(self):
        """
        Main test method that actually performs the multiplication of documents.
        Assumptions:
            - Clusters and console are connected successfully.
            - Source and target cluster config is exactly the same in `cdk.context.json`.
            - User has performed a CDK deployment.
            - User to run `CleanUpAndPrepare.py` (Part 1) before this part.
        """
        logger.info("=== Starting Document Multiplication Part ===")
        
        # Step 1: Start backfill with scaling
        logger.info(f"Step 1: Starting backfill with {RFS_WORKER_COUNT} workers")
        run_console_command(["console", "backfill", "scale", str(RFS_WORKER_COUNT)])
        
        # Step 2: Wait for backfill completion
        logger.info("Step 2: Waiting for backfill completion")
        expected_count = self.INGESTED_DOC_COUNT * MULTIPLICATION_FACTOR
        logger.info(f"Expecting {expected_count} documents after transformation "
                    f"({self.INGESTED_DOC_COUNT} ingested × {MULTIPLICATION_FACTOR} multiplication)")
        
        self.wait_for_backfill_completion_on_index(
            index_name=INDEX_NAME,
            expected_count=expected_count,
            timeout_minutes=BACKFILL_TIMEOUT_MINUTES
        )
        
        # Step 3: Stop backfill
        logger.info("Step 3: Stopping backfill")
        run_console_command(["console", "backfill", "stop"])
        
        # Step 4: Final verification
        logger.info("Step 4: Final verification")
        final_count = get_target_document_count_for_index(INDEX_NAME)
        self.assertEqual(final_count, expected_count,
                         f"Final document count mismatch in {INDEX_NAME}: {final_count} != {expected_count}")
        
        logger.info("=== Document Multiplication Part Completed Successfully! ===")
        logger.info(f"Successfully migrated {self.INGESTED_DOC_COUNT} documents to '{INDEX_NAME}' "
                    f"with {MULTIPLICATION_FACTOR}x multiplication (total: {final_count} documents)")

    @classmethod
    def tearDownClass(cls):
        """Clean up after test completion"""
        logger.info("Multiplication Test cleanup completed")


if __name__ == "__main__":
    # Configure logging for standalone execution
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # Run the test
    unittest.main()
