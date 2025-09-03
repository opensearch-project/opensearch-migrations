import logging
import os
import sys
from console_link.cli import Context
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from integ_test.snapshot_generator.MultiplicationTestUtils import (
    get_target_document_count_for_index,
    get_environment_values,
    wait_for_backfill_completion_on_index,
    run_console_command
)

CONFIG_FILE_PATH = os.getenv('CONFIG_FILE_PATH', '/config/migration_services.yaml')
ENV_VALUES = get_environment_values()
INDEX_NAME = ENV_VALUES['index_name']
INGESTED_DOC_COUNT = ENV_VALUES['batch_count'] * ENV_VALUES['docs_per_batch']
INDEX_SHARD_COUNT = ENV_VALUES['num_shards']
BACKFILL_TIMEOUT_MINUTES = int(float(os.getenv('BACKFILL_TIMEOUT_HOURS', '0.5')) * 60)
MULTIPLICATION_FACTOR = ENV_VALUES['multiplication_factor']
RFS_WORKER_COUNT = int(os.getenv('RFS_WORKERS', '5'))

logger = logging.getLogger(__name__)


class MultiplyDocuments:
    """
    Part 2 : Multiply documents using RFS and a transformer
    Pre-requisites:
        - Clusters and console are connected successfully.
        - Source and target cluster config is exactly the same in `cdk.context.json`.
        - User has performed CleanUpAndPrepare and MultiplyDocuments parts.
    """

    def __init__(self):
        self.CONSOLE_ENV = Context(CONFIG_FILE_PATH).env
        self.INDEX_NAME = INDEX_NAME
        self.INGESTED_DOC_COUNT = INGESTED_DOC_COUNT
        self.INDEX_SHARD_COUNT = INDEX_SHARD_COUNT
        
    def run(self):
        logger.info("=== Starting Part 2: MultiplyDocuments ===")
        logger.info(f"Index has {self.INGESTED_DOC_COUNT} documents with {self.INDEX_SHARD_COUNT} shards")
        expected_count = self.INGESTED_DOC_COUNT * MULTIPLICATION_FACTOR

        try:
            logger.info(f"Step 1: Starting backfill with {RFS_WORKER_COUNT} workers")
            backfill: Backfill = self.CONSOLE_ENV.backfill
            assert backfill is not None
            
            backfill_start_result: CommandResult = backfill.start()
            if not backfill_start_result.success:
                logger.error(f"Failed to start backfill: {backfill_start_result.value}")
                return 1
            
            backfill_scale_result: CommandResult = backfill.scale(units=RFS_WORKER_COUNT)
            if not backfill_scale_result.success:
                logger.error(f"Failed to scale backfill: {backfill_scale_result.value}")
                return 1
            
            logger.info(f"Successfully started and scaled backfill to {RFS_WORKER_COUNT} workers")
            logger.info(f"Expecting {expected_count} documents after transformation "
                        f"({self.INGESTED_DOC_COUNT} ingested × {MULTIPLICATION_FACTOR} multiplication)")
            
            logger.info("Step 2: Waiting for backfill completion")
            success = wait_for_backfill_completion_on_index(
                index_name=INDEX_NAME,
                expected_count=expected_count,
                backfill=backfill
            )
            if not success:
                logger.warning("Backfill monitoring completed with warnings")
            
            logger.info("Step 3: Stopping backfill")
            # Step 3a: Scale down ECS workers
            backfill_stop_result: CommandResult = backfill.stop()
            if not backfill_stop_result.success:
                logger.error(f"Failed to stop backfill: {backfill_stop_result.value}")
                return 1
            logger.info("Successfully scaled down backfill workers")
            
            # Step 3b: Completely stop migration and clean up state
            logger.info("Completely stopping backfill using console command")
            try:
                run_console_command(["console", "backfill", "stop"])
                logger.info("Successfully stopped backfill migration completely")
            except Exception as e:
                logger.warning(f"Failed to completely stop backfill: {e}")
            
            logger.info("Step 4: Final verification")
            final_count = get_target_document_count_for_index(INDEX_NAME)
            if final_count != expected_count:
                logger.error(f"Final document count mismatch in {INDEX_NAME}: {final_count} != {expected_count}")
                return 1
            logger.info("=== Completed Part 2: MultiplyDocuments ===")
            logger.info(f"Successfully migrated {self.INGESTED_DOC_COUNT} documents to '{INDEX_NAME}' "
                        f"with {MULTIPLICATION_FACTOR}x multiplication (total: {final_count} documents)")
            
            return 0
            
        except Exception as e:
            logger.error(f"Document multiplication failed: {e}")
            return 1


def main():
    # Configure logging for standalone execution
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # Create and run the multiplier
    multiplier = MultiplyDocuments()
    exit_code = multiplier.run()
    
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
