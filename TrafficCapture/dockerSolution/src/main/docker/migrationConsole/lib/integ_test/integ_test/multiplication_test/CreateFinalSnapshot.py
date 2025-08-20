import logging
import os
import sys
from console_link.cli import Context
from integ_test.multiplication_test.MultiplicationTestUtils import (
    run_console_command,
    extract_account_id_from_config,
    check_and_prepare_s3_bucket,
    modify_temp_config_file,
    display_final_results,
    get_environment_values,
    build_bucket_names_and_paths,
    get_config_values,
    TEMP_CONFIG_FILE_PATH
)

# Get values from environment variables with defaults
env_values = get_environment_values()

# Override with environment variables if present (Jenkins parameter injection)
CONFIG_FILE_PATH = os.getenv('CONFIG_FILE_PATH', '/config/migration_services.yaml')
INDEX_NAME = os.getenv('INDEX_NAME', 'basic_index')
INGESTED_DOC_COUNT = int(os.getenv('DOCS_PER_BATCH', '50'))
INDEX_SHARD_COUNT = int(os.getenv('NUM_SHARDS', '10'))
TEST_REGION = env_values['snapshot_region']
MULTIPLICATION_FACTOR = env_values['multiplication_factor']

logger = logging.getLogger(__name__)


class CreateFinalSnapshot:
    """
    Phase : Create Final Snapshot
    
    - check_and_prepare_s3_bucket()
    - modify_temp_config_file("create")
    - take_large_snapshot_with_console()
    - modify_temp_config_file("delete")
    - display_final_results()
    """

    def __init__(self):
        """Initialize the preparation class"""
        self.CONSOLE_ENV = Context(CONFIG_FILE_PATH).env

    def take_large_snapshot_with_console(self):
        """Take large snapshot using console commands with temp config"""
        
        # Get account ID and bucket info for building S3 URI
        account_id = extract_account_id_from_config(CONFIG_FILE_PATH)
        config_values = get_config_values(CONFIG_FILE_PATH)
        bucket_info = build_bucket_names_and_paths(account_id, env_values, config_values)
        
        # Step 1: Unregister existing repo (if exists) using temp config
        logger.info("Unregistering existing repository (if exists)")
        try:
            run_console_command([
                "console", "snapshot", "unregister-repo", "--acknowledge-risk"
            ])
            logger.info("Successfully unregistered existing repository")
        except Exception:
            logger.info("No existing repo to unregister, proceeding with snapshot creation")
        
        # Step 2: Create snapshot using temp config (console will handle repo registration automatically)
        logger.info("Creating large snapshot using console command with temp config")
        run_console_command([
            "console", "--config-file", TEMP_CONFIG_FILE_PATH,
            "snapshot", "create"
        ])
        logger.info("Large snapshot creation initiated successfully")
        
        # Step 3: Verify snapshot completion using console command
        logger.info("Verifying large snapshot completion")
        result = run_console_command([
            "console", "--config-file", TEMP_CONFIG_FILE_PATH,
            "snapshot", "status", "--deep-check"
        ])
        
        # Check if snapshot was successful
        if "SUCCESS" in result.stdout and "Percent completed: 100.00%" in result.stdout:
            logger.info("Large snapshot completed successfully!")
            logger.info(f"Snapshot details:\n{result.stdout}")
        else:
            logger.warning(f"Large snapshot may not be complete: {result.stdout}")
        
        # Return the S3 URI for final results
        return bucket_info['large_snapshot_uri']

    def run(self):
        """
        Main method that performs the serving phase of the migration workflow.
        
        Assumptions:
            - Clusters and console are connected successfully.
            - Source and target cluster config is exactly the same in `cdk.context.json`.
            - User has performed a CDK deployment.
            - User has manually created the S3 Bucket for Final Snapshot in the same account and region.
            - User has manually created a IAM Role with S3 access for Final Snapshot.
        """
        logger.info("=== Starting Large Snapshot Serving Phase ===")
        
        account_id = extract_account_id_from_config(CONFIG_FILE_PATH)
        config_values = get_config_values(CONFIG_FILE_PATH)
        bucket_info = build_bucket_names_and_paths(account_id, env_values, config_values)

        logger.info("Phase 3, Step 1: Checking and preparing large S3 bucket and directory")
        check_and_prepare_s3_bucket(
            bucket_info['large_snapshot_bucket_name'],
            bucket_info['large_s3_base_path'],
            TEST_REGION
        )

        logger.info("Phase 3, Step 2: Creating temporary config file for console commands")
        modify_temp_config_file("create", CONFIG_FILE_PATH, TEMP_CONFIG_FILE_PATH)
        
        logger.info("Phase 3, Step 3: Taking large snapshot using console commands")
        large_snapshot_uri = self.take_large_snapshot_with_console()

        logger.info("Phase 3, Step 4: Cleaning up repository and deleting temporary config file")
        
        # Unregister the migrations-jenkins-repo using temp config
        logger.info("Unregistering migrations-jenkins-repo repository")
        try:
            run_console_command([
                "console", "--config-file", TEMP_CONFIG_FILE_PATH,
                "snapshot", "unregister-repo", "--acknowledge-risk"
            ])
            logger.info("Successfully unregistered migrations-jenkins-repo repository")
        except Exception as e:
            logger.warning(f"Failed to unregister repository (may not exist): {e}")
        
        # Delete the temporary config file
        modify_temp_config_file("delete", CONFIG_FILE_PATH, TEMP_CONFIG_FILE_PATH)
        
        logger.info("Phase 3, Step 5: Displaying final results")
        final_count = INGESTED_DOC_COUNT * MULTIPLICATION_FACTOR
        display_final_results(
            large_snapshot_uri, final_count, INGESTED_DOC_COUNT,
            INDEX_SHARD_COUNT, MULTIPLICATION_FACTOR
        )
        
        logger.info("=== Large Snapshot Serving Phase Completed Successfully! ===")
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
