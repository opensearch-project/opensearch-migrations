import logging
import os
import sys
from console_link.cli import Context
from integ_test.multiplication_test.MultiplicationTestUtils import (
    run_console_command,
    clear_clusters,
    create_index_with_shards,
    ingest_test_data,
    create_transformation_config,
    get_environment_values,
    INGEST_DOC
)

# Get values from environment variables with defaults
env_values = get_environment_values()

# Override with environment variables if present (Jenkins parameter injection)
CONFIG_FILE_PATH = os.getenv('CONFIG_FILE_PATH', '/config/migration_services.yaml')
INDEX_NAME = os.getenv('INDEX_NAME', 'basic_index')
INGESTED_DOC_COUNT = int(os.getenv('DOCS_PER_BATCH', '50'))
INDEX_SHARD_COUNT = int(os.getenv('NUM_SHARDS', '10'))
TEST_REGION = env_values['snapshot_region']
TEST_STAGE = env_values['stage']
MULTIPLICATION_FACTOR = env_values['multiplication_factor']

logger = logging.getLogger(__name__)


class CleanUpAndPrepare:
    """
    Phase : Clean up and Preparation
    
    - clear_clusters()
        - cleanup_snapshots_and_repos()
        - console clusters clear-indices --cluster source
    - create_index_with_shards()
    - ingest_test_data()
    - create_transformation_config()
    - console snapshot create //takes (initial) RFS snapshot
    """

    def __init__(self):
        """Initialize the preparation class"""
        self.CONSOLE_ENV = Context(CONFIG_FILE_PATH).env

    def run(self):
        """
        Main method that performs the preparation phase of the migration workflow.
        Assumptions:
            - Clusters and console are connected successfully.
            - Source and target cluster config is exactly the same in `cdk.context.json`.
            - User has performed a CDK deployment.
        """
        logger.info("=== Starting Large Snapshot Preparation Phase ===")
        
        logger.info("Phase 1, Step 1: Clearing source and target clusters")
        clear_clusters(TEST_STAGE, TEST_REGION)
        
        logger.info(f"Phase 1, Step 2: Creating index '{INDEX_NAME}' with {INDEX_SHARD_COUNT} shards")
        create_index_with_shards(INDEX_NAME, INDEX_SHARD_COUNT)
        
        logger.info(f"Phase 1, Step 3: Ingesting {INGESTED_DOC_COUNT} test documents to source cluster")
        try:
            ingest_test_data(INDEX_NAME, INGESTED_DOC_COUNT, INGEST_DOC)
        except Exception as e:
            logger.error(f"Data ingestion failed: {e}")
            return 1
        
        logger.info("Phase 1, Step 4: Creating transformation configuration")
        create_transformation_config(MULTIPLICATION_FACTOR)
        
        logger.info("Phase 1, Step 5: Creating initial RFS snapshot")
        try:
            run_console_command(["console", "snapshot", "create"])
        except Exception as e:
            logger.error(f"Initial RFS snapshot creation failed: {e}")
            return 1
   
        logger.info("=== Preparation Phase Completed Successfully! ===")
        logger.info(f"Successfully prepared {INGESTED_DOC_COUNT} documents in '{INDEX_NAME}' "
                    f"with {INDEX_SHARD_COUNT} shards")

        return 0


def main():
    """Main function for direct execution"""
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
