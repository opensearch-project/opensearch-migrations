import json
import logging
import sys
from console_link.cli import Context
from .MultiplicationTestUtils import (
    run_console_command,
    extract_account_id_from_config,
    check_and_prepare_s3_bucket,
    modify_temp_config_file,
    display_final_results
)
from .JenkinsParamConstants import (
    CONFIG_FILE_PATH,
    TEMP_CONFIG_FILE_PATH,
    INDEX_NAME,
    INGESTED_DOC_COUNT,
    INDEX_SHARD_COUNT,
    TEST_REGION,
    LARGE_SNAPSHOT_BUCKET_PREFIX,
    LARGE_SNAPSHOT_BUCKET_SUFFIX,
    LARGE_S3_BASE_PATH,
    ROLE_ARN_PREFIX,
    ROLE_ARN_SUFFIX,
    MULTIPLICATION_FACTOR_WITH_ORIGINAL
)

logger = logging.getLogger(__name__)


class CreateFinalSnapshot:
    """
    Phase : Create Final Snapshot
    
    - check_and_prepare_s3_bucket()
    - modify_temp_config_file("create")
    - take_large_snapshot_with_curl()
    - modify_temp_config_file("delete")
    - display_final_results()
    """

    def __init__(self):
        """Initialize the preparation class"""
        self.CONSOLE_ENV = Context(CONFIG_FILE_PATH).env

    def take_large_snapshot_with_curl(self):
        """Take large snapshot using direct curl commands"""
        
        # Get account ID from config
        ACCOUNT_ID = extract_account_id_from_config(CONFIG_FILE_PATH)
        
        # Build dynamic values
        JENKINS_BUCKET_NAME = f"{LARGE_SNAPSHOT_BUCKET_PREFIX}{ACCOUNT_ID}{LARGE_SNAPSHOT_BUCKET_SUFFIX}"
        ROLE_ARN = f"{ROLE_ARN_PREFIX}{ACCOUNT_ID}{ROLE_ARN_SUFFIX}"
        
        # Unregister the repo from cluster (if exists)
        logger.info("Unregistering existing repository (if exists)")
        try:
            run_console_command([
                "console", "snapshot", "unregister-repo", "--acknowledge-risk"
            ])
            logger.info("Successfully unregistered existing repository")
        except Exception:
            logger.info("No existing RFS repo to unregister, proceeding with final snapshot creation")
        
        # Register repository with working role
        REPO_CONFIG = {
            "type": "s3",
            "settings": {
                "bucket": JENKINS_BUCKET_NAME,
                "base_path": LARGE_S3_BASE_PATH,
                "region": TEST_REGION,
                "role_arn": ROLE_ARN
            }
        }
        
        RESULT = run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XPUT", "/_snapshot/migration_assistant_repo",
            "-H", "Content-Type: application/json",
            "-d", json.dumps(REPO_CONFIG)
        ])
        logger.info(f"Repository registration result: {RESULT.stdout}")
        
        # Create snapshot
        SNAPSHOT_CONFIG = {
            "indices": INDEX_NAME,
            "ignore_unavailable": True,
            "include_global_state": False
        }
        
        RESULT = run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XPUT", "/_snapshot/migration_assistant_repo/large-snapshot",
            "-H", "Content-Type: application/json",
            "-d", json.dumps(SNAPSHOT_CONFIG)
        ])
        logger.info(f"Snapshot creation result: {RESULT.stdout}")
        
        # Verify snapshot completion using console command
        logger.info("Verifying large snapshot completion")
        RESULT = run_console_command([
            "console", "--config-file", TEMP_CONFIG_FILE_PATH, "snapshot", "status", "--deep-check"
        ])
        
        # Check if snapshot was successful
        if "SUCCESS" in RESULT.stdout and "Percent completed: 100.00%" in RESULT.stdout:
            logger.info("Large snapshot completed successfully!")
            logger.info(f"Snapshot details:\n{RESULT.stdout}")
        else:
            logger.warning(f"Large snapshot may not be complete: {RESULT.stdout}")
        
        return f"s3://{JENKINS_BUCKET_NAME}/{LARGE_S3_BASE_PATH}/"

    def run(self):
        """
        Main method that performs the serving phase of the migration workflow.
        
        Assumption: Clusters and console are connected successfully.
        Error handling only around temp config file operations and snapshot creation.
        """
        logger.info("=== Starting Large Snapshot Serving Phase ===")
        
        # Get account ID from config
        ACCOUNT_ID = extract_account_id_from_config(CONFIG_FILE_PATH)
        
        # Build S3 URIs dynamically
        JENKINS_BUCKET_NAME = f"{LARGE_SNAPSHOT_BUCKET_PREFIX}{ACCOUNT_ID}{LARGE_SNAPSHOT_BUCKET_SUFFIX}"
        
        # Step 1: Preparation for Large Snapshot
        logger.info("Phase 3, Step 1: Preparing for large snapshot")

        # Step 2: Check and prepare S3 bucket and directory
        logger.info("Phase 3, Step 2: Checking and preparing large S3 bucket and directory")
        check_and_prepare_s3_bucket(JENKINS_BUCKET_NAME, LARGE_S3_BASE_PATH, TEST_REGION)

        # Step 3: Create temp config file for console commands
        logger.info("Phase 3, Step 3: Creating temporary config file for console commands")
        modify_temp_config_file("create", CONFIG_FILE_PATH, TEMP_CONFIG_FILE_PATH)
        
        # Step 4: Take large snapshot
        logger.info("Phase 3, Step 4: Taking large snapshot using curl commands")
        LARGE_SNAPSHOT_URI = self.take_large_snapshot_with_curl()

        # Step 5: Delete the temporary config file
        logger.info("Phase 3, Step 5: Deleting temporary config file")
        modify_temp_config_file("delete", CONFIG_FILE_PATH, TEMP_CONFIG_FILE_PATH)
        
        # Step 6: Display final results
        logger.info("Phase 3, Step 6: Displaying final results")
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
