import logging
import os
import sys
from console_link.cli import Context
from console_link.models.snapshot import Snapshot
from console_link.models.command_result import CommandResult
from integ_test.snapshot_generator.MultiplicationTestUtils import (
    cleanup_snapshots_and_repo,
    create_transformation_config,
    get_environment_values
)
from integ_test.snapshot_generator.MultiplicationTestDataIngestion import (
    create_test_index,
    ingest_test_documents
)

CONFIG_FILE_PATH = os.getenv('CONFIG_FILE_PATH', '/config/migration_services.yaml')
ENV_VALUES = get_environment_values()
MULTIPLICATION_FACTOR = ENV_VALUES['multiplication_factor']
TEST_STAGE = ENV_VALUES['stage']
TEST_REGION = ENV_VALUES['snapshot_region']

logger = logging.getLogger(__name__)


class CleanUpAndPrepare:
    """
    Part 1 : Clean up and Preparation
    Pre-requisites:
        - Clusters and console are connected successfully.
        - Source and target cluster config is exactly the same in `cdk.context.json`.
    """

    def __init__(self):
        self.CONSOLE_ENV = Context(CONFIG_FILE_PATH).env

    def run(self):
        logger.info("=== Starting Part 1: CleanUpAndPrepare ===")
        source_cluster = self.CONSOLE_ENV.source_cluster
        snapshot: Snapshot = self.CONSOLE_ENV.snapshot
        index_name = ENV_VALUES['index_name']
        num_shards = ENV_VALUES['num_shards']
        batch_count = ENV_VALUES['batch_count']
        docs_per_batch = ENV_VALUES['docs_per_batch']
        total_docs = batch_count * docs_per_batch
        
        logger.info("Step 1: Clearing source and target clusters")
        cleanup_snapshots_and_repo(source_cluster, TEST_STAGE, TEST_REGION)
        
        logger.info("Step 2: Creating index with shards")
        create_test_index(index_name, num_shards, source_cluster)
        
        logger.info("Step 3: Ingesting test documents")
        try:
            ingest_test_documents(index_name, total_docs, None, source_cluster)
        except Exception as e:
            logger.error(f"Data ingestion failed: {e}")
            return 1
        
        logger.info("Step 4: Creating transformation configuration")
        create_transformation_config(MULTIPLICATION_FACTOR)
        
        logger.info("Step 5: Creating initial RFS snapshot")
        snapshot_result: CommandResult = snapshot.create(wait=True)
        if not snapshot_result.success:
            logger.error(f"Initial RFS snapshot creation failed: {snapshot_result.value}")
            return 1
   
        logger.info("=== Completed Part 1: CleanUpAndPrepare ===")
        logger.info(f"Successfully prepared {total_docs} documents using BATCH_COUNT × DOCS_PER_BATCH approach")

        return 0


def main():
    # Configure logging for standalone execution
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # Create and run the preparation
    preparer = CleanUpAndPrepare()
    exit_code = preparer.run()
    
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
