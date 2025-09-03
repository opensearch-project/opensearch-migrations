import logging
import os
import sys
from console_link.cli import Context
from integ_test.snapshot_generator.CreateFinalSnapshotUtils import (
    build_bucket_names_and_paths,
    check_and_prepare_s3_bucket,
    modify_temp_config_file,
    take_large_snapshot_with_console,
    update_snapshot_catalog,
    display_final_results
)
from integ_test.snapshot_generator.MultiplicationTestUtils import (
    get_environment_values,
    FINAL_SNAPSHOT_CONFIG_PATH
)
from integ_test.snapshot_generator.MultiplicationTestDataIngestion import (
    get_version_info_from_config
)

CONFIG_FILE_PATH = os.getenv('CONFIG_FILE_PATH', '/config/migration_services.yaml')
ENV_VALUES = get_environment_values()
INDEX_NAME = ENV_VALUES['index_name']
INGESTED_DOC_COUNT = ENV_VALUES['batch_count'] * ENV_VALUES['docs_per_batch']
INDEX_SHARD_COUNT = ENV_VALUES['num_shards']
MULTIPLICATION_FACTOR = ENV_VALUES['multiplication_factor']
TEST_REGION = ENV_VALUES['snapshot_region']

logger = logging.getLogger(__name__)


class CreateFinalSnapshot:
    """
    Part 3 : Create Final Snapshot
    Pre-requisites:
        - Clusters and console are connected successfully.
        - Source and target cluster config is exactly the same in `cdk.context.json`.
        - User has performed CleanUpAndPrepare and MultiplyDocuments parts.
        - Final Snapshot S3 Bucket exists and is accessible.
    """

    def __init__(self):
        self.CONSOLE_ENV = Context(CONFIG_FILE_PATH).env

    def run(self):
        logger.info("=== Starting Part 3: CreateFinalSnapshot ===")
        
        version_info = get_version_info_from_config(CONFIG_FILE_PATH)
        cluster_version = version_info['cluster_version']
        bucket_info = build_bucket_names_and_paths(ENV_VALUES, cluster_version)
        final_snapshot_uri = bucket_info['final_snapshot_uri']

        logger.info("Step 1: Checking and preparing large S3 bucket and directory")
        check_and_prepare_s3_bucket(
            final_snapshot_uri,
            TEST_REGION
        )

        logger.info("Step 2: Creating temporary config file for console commands")
        modify_temp_config_file("create", CONFIG_FILE_PATH, FINAL_SNAPSHOT_CONFIG_PATH, bucket_info=bucket_info)
        
        logger.info("Step 3: Taking large snapshot using console commands")
        large_snapshot_uri = take_large_snapshot_with_console(
            self.CONSOLE_ENV, CONFIG_FILE_PATH, FINAL_SNAPSHOT_CONFIG_PATH, bucket_info
        )

        logger.info("Step 4: Updating snapshot catalog")
        try:
            update_snapshot_catalog(CONFIG_FILE_PATH, bucket_info, FINAL_SNAPSHOT_CONFIG_PATH)
            logger.info("Successfully updated snapshot catalog")
        except Exception as e:
            logger.warning(f"Failed to update catalog (non-critical): {e}")
        
        logger.info("Step 5: Unregistering migrations-jenkins-repo repository")
        try:
            # Create a temporary context with the temp config file for this operation
            temp_context = Context(FINAL_SNAPSHOT_CONFIG_PATH).env
            temp_context.snapshot.delete_snapshot_repo()
            logger.info("Successfully unregistered migrations-jenkins-repo repository")
        except Exception as e:
            logger.warning(f"Failed to unregister repository (may not exist): {e}")
        
        logger.info("Step 6: Deleting temporary config file")
        modify_temp_config_file("delete", CONFIG_FILE_PATH, FINAL_SNAPSHOT_CONFIG_PATH)

        logger.info("Step 7: Extracting final results")
        final_count = INGESTED_DOC_COUNT * MULTIPLICATION_FACTOR
        display_final_results(
            large_snapshot_uri, final_count, INGESTED_DOC_COUNT,
            INDEX_SHARD_COUNT, MULTIPLICATION_FACTOR
        )
        
        logger.info("=== Completed Part 3: CreateFinalSnapshot ===")
        logger.info(f"Successfully created large snapshot from {INGESTED_DOC_COUNT} documents in "
                    f"'{INDEX_NAME}' with {MULTIPLICATION_FACTOR}x multiplication "
                    f"(total: {final_count} documents)")
        logger.info(f"Large snapshot available at: {large_snapshot_uri}")
        
        return 0


def main():
    """Main function for direct execution"""
    # Configure logging for standalone execution
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # Create and run the server
    server = CreateFinalSnapshot()
    exit_code = server.run()
    
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
