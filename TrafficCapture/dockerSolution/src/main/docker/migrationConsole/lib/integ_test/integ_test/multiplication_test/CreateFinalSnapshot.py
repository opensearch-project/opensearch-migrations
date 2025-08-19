import logging
import os
import sys
from console_link.cli import Context
from integ_test.multiplication_test.MultiplicationTestUtils import (
    run_console_command,
    extract_account_id_from_config,
    check_and_prepare_s3_bucket,
    modify_temp_config_file,
    display_final_results
)
from integ_test.multiplication_test import JenkinsParamConstants as constants

# Override constants with environment variables if present (Jenkins parameter injection)
CONFIG_FILE_PATH = os.getenv('CONFIG_FILE_PATH', constants.CONFIG_FILE_PATH)
TEMP_CONFIG_FILE_PATH = constants.TEMP_CONFIG_FILE_PATH  # This can stay as constant
INDEX_NAME = os.getenv('INDEX_NAME', constants.INDEX_NAME)
INGESTED_DOC_COUNT = int(os.getenv('DOCS_PER_BATCH', str(constants.INGESTED_DOC_COUNT)))
INDEX_SHARD_COUNT = int(os.getenv('NUM_SHARDS', str(constants.INDEX_SHARD_COUNT)))
TEST_REGION = os.getenv('SNAPSHOT_REGION', constants.TEST_REGION)
LARGE_SNAPSHOT_BUCKET_PREFIX = os.getenv('LARGE_SNAPSHOT_BUCKET_PREFIX', constants.LARGE_SNAPSHOT_BUCKET_PREFIX)
LARGE_SNAPSHOT_BUCKET_SUFFIX = constants.LARGE_SNAPSHOT_BUCKET_SUFFIX  # This can stay as constant
LARGE_S3_BASE_PATH = os.getenv('LARGE_S3_DIRECTORY_PREFIX', constants.LARGE_S3_BASE_PATH) + os.getenv('CLUSTER_VERSION', constants.CLUSTER_VERSION)
MULTIPLICATION_FACTOR_WITH_ORIGINAL = int(os.getenv('MULTIPLICATION_FACTOR', str(constants.MULTIPLICATION_FACTOR_WITH_ORIGINAL)))

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
        
        # Get account ID for building S3 URI
        ACCOUNT_ID = extract_account_id_from_config(CONFIG_FILE_PATH)
        JENKINS_BUCKET_NAME = f"{LARGE_SNAPSHOT_BUCKET_PREFIX}{ACCOUNT_ID}{LARGE_SNAPSHOT_BUCKET_SUFFIX}"
        
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
        RESULT = run_console_command([
            "console", "--config-file", TEMP_CONFIG_FILE_PATH,
            "snapshot", "status", "--deep-check"
        ])
        
        # Check if snapshot was successful
        if "SUCCESS" in RESULT.stdout and "Percent completed: 100.00%" in RESULT.stdout:
            logger.info("Large snapshot completed successfully!")
            logger.info(f"Snapshot details:\n{RESULT.stdout}")
        else:
            logger.warning(f"Large snapshot may not be complete: {RESULT.stdout}")
        
        # Return the S3 URI for final results
        return f"s3://{JENKINS_BUCKET_NAME}/{LARGE_S3_BASE_PATH}/"

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
        
        ACCOUNT_ID = extract_account_id_from_config(CONFIG_FILE_PATH)
        JENKINS_BUCKET_NAME = f"{LARGE_SNAPSHOT_BUCKET_PREFIX}{ACCOUNT_ID}{LARGE_SNAPSHOT_BUCKET_SUFFIX}"

        logger.info("Phase 3, Step 1: Checking and preparing large S3 bucket and directory")
        check_and_prepare_s3_bucket(JENKINS_BUCKET_NAME, LARGE_S3_BASE_PATH, TEST_REGION)

        logger.info("Phase 3, Step 2: Creating temporary config file for console commands")
        modify_temp_config_file("create", CONFIG_FILE_PATH, TEMP_CONFIG_FILE_PATH)
        
        logger.info("Phase 3, Step 3: Taking large snapshot using console commands")
        LARGE_SNAPSHOT_URI = self.take_large_snapshot_with_console()

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
        FINAL_COUNT = INGESTED_DOC_COUNT * MULTIPLICATION_FACTOR_WITH_ORIGINAL
        display_final_results(LARGE_SNAPSHOT_URI, FINAL_COUNT, INGESTED_DOC_COUNT, INDEX_SHARD_COUNT)
        
        logger.info("=== Large Snapshot Serving Phase Completed Successfully! ===")
        logger.info(f"Successfully created large snapshot from {INGESTED_DOC_COUNT} documents in "
                    f"'{INDEX_NAME}' with {MULTIPLICATION_FACTOR_WITH_ORIGINAL}x multiplication "
                    f"(total: {FINAL_COUNT} documents)")
        logger.info(f"Large snapshot available at: {LARGE_SNAPSHOT_URI}")
        
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
