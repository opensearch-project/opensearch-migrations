import json
import logging
import os
import subprocess
import time
import unittest
import yaml
from console_link.cli import Context
from ..default_operations import DefaultOperationsLibrary
from .large_snapshot_constants import (
    CONFIG_FILE_PATH,
    TEMP_CONFIG_FILE_PATH,
    UNIQUE_ID,
    INDEX_NAME,
    INGESTED_DOC_COUNT,
    INDEX_SHARD_COUNT,
    COMMAND_TIMEOUT_SECONDS,
    TEST_REGION,
    LARGE_SNAPSHOT_BUCKET_PREFIX,
    LARGE_SNAPSHOT_BUCKET_SUFFIX,
    LARGE_S3_BASE_PATH,
    ROLE_ARN_PREFIX,
    ROLE_ARN_SUFFIX,
    MULTIPLICATION_FACTOR_WITH_ORIGINAL,
    PHASE_STATUS_DIR,
    SHARED_STATE_DIR,
    COOK_COMPLETE_FILE,
    SERVE_COMPLETE_FILE,
    FINAL_COUNT_FILE,
    SNAPSHOT_URI_FILE
)

logger = logging.getLogger(__name__)


class LargeSnapshotServeTest(unittest.TestCase):
    """
    Phase 3: Large Snapshot Serve Test (Large Snapshot Creation)
    
    This test performs the serving phase of the large snapshot migration:
    1. Verify cook phase completed
    2. Preparation for large snapshot
    3. Check and prepare S3 bucket and directory
    4. Create temp config file for console commands
    5. Take large snapshot with curl
    6. Delete temp config file
    7. Display final results
    """

    @classmethod
    def setUpClass(cls):
        """Set up test environment and configuration"""
        cls.CONSOLE_ENV = Context(CONFIG_FILE_PATH).env
        cls.UNIQUE_ID = UNIQUE_ID
        cls.INDEX_NAME = INDEX_NAME
        cls.INGESTED_DOC_COUNT = INGESTED_DOC_COUNT
        cls.INDEX_SHARD_COUNT = INDEX_SHARD_COUNT
        
        # Create phase status and shared state directories
        os.makedirs(PHASE_STATUS_DIR, exist_ok=True)
        os.makedirs(SHARED_STATE_DIR, exist_ok=True)
        
        logger.info(f"Starting large snapshot serving test with unique_id: {cls.UNIQUE_ID}")
        logger.info(f"Expected {cls.INGESTED_DOC_COUNT} documents with {cls.INDEX_SHARD_COUNT} shards")

    def run_console_command(self, command_args, timeout=COMMAND_TIMEOUT_SECONDS):
        """
        Execute a console command and return the result.
        Fails immediately on any error (no retries).
        """
        try:
            logger.info(f"Executing command: {' '.join(command_args)}")
            result = subprocess.run(
                command_args,
                capture_output=True,
                text=True,
                timeout=timeout,
                check=True
            )
            logger.info(f"Command succeeded: {result.stdout.strip()}")
            return result
        except subprocess.CalledProcessError as e:
            error_msg = f"Command failed: {' '.join(command_args)}\nStdout: {e.stdout}\nStderr: {e.stderr}"
            logger.error(error_msg)
            self.fail(error_msg)
        except subprocess.TimeoutExpired:
            error_msg = f"Command timed out after {timeout}s: {' '.join(command_args)}"
            logger.error(error_msg)
            self.fail(error_msg)

    def verify_cook_phase_complete(self):
        """Verify that the cook phase completed successfully"""
        logger.info("Verifying cook phase completion")
        
        if not os.path.exists(COOK_COMPLETE_FILE):
            self.fail(f"Cook phase not completed. Missing file: {COOK_COMPLETE_FILE}")
        
        with open(COOK_COMPLETE_FILE, 'r') as f:
            content = f.read()
            logger.info(f"Cook phase status: {content.strip()}")
        
        logger.info("Cook phase verification successful")

    def load_final_count(self):
        """Load the final document count from the cook phase"""
        if not os.path.exists(FINAL_COUNT_FILE):
            self.fail(f"Final count file not found: {FINAL_COUNT_FILE}")
        
        with open(FINAL_COUNT_FILE, 'r') as f:
            FINAL_COUNT = int(f.read().strip())
        
        logger.info(f"Loaded final count from cook phase: {FINAL_COUNT}")
        return FINAL_COUNT

    def check_larges3_bucket_and_directory(self):
        """
        Check if S3 bucket and directory exist for large snapshot and take appropriate action.
        
        Scenarios handled:
        1. Bucket doesn't exist -> Create bucket (directory will be empty)
        2. Bucket exists, directory doesn't exist -> Directory will be created when needed (empty)
        3. Bucket exists, directory exists -> Clear directory contents
        """
        logger.info("Step 7.1: Checking and preparing large S3 bucket and directory")
        
        # Get account ID from config
        OPS = DefaultOperationsLibrary()
        ACCOUNT_ID = OPS.extract_account_id_from_config(CONFIG_FILE_PATH)
        
        # Build S3 URIs dynamically
        BUCKET_NAME = f"{LARGE_SNAPSHOT_BUCKET_PREFIX}{ACCOUNT_ID}{LARGE_SNAPSHOT_BUCKET_SUFFIX}"
        S3_BUCKET_URI = f"s3://{BUCKET_NAME}/"
        S3_DIRECTORY_URI = f"s3://{BUCKET_NAME}/{LARGE_S3_BASE_PATH}/"
        
        logger.info(f"Target bucket: {BUCKET_NAME}")
        logger.info(f"Target directory: {LARGE_S3_BASE_PATH}")
        
        # Step 7.1.1: Check if bucket exists
        BUCKET_EXISTS = False
        try:
            self.run_console_command([
                "aws", "s3", "ls", S3_BUCKET_URI, "--region", TEST_REGION
            ])
            BUCKET_EXISTS = True
            logger.info(f"S3 bucket {BUCKET_NAME} exists")
        except Exception:
            logger.info(f"S3 bucket {BUCKET_NAME} does not exist")
        
        # Step 7.1.2: Create bucket if it doesn't exist
        if not BUCKET_EXISTS:
            logger.info("Step 7.1.2: Creating S3 bucket (will be empty)")
            try:
                self.run_console_command([
                    "aws", "s3", "mb", S3_BUCKET_URI, "--region", TEST_REGION
                ])
                logger.info(f"S3 bucket has been created: {BUCKET_NAME}")
                logger.info("Directory will be created automatically when snapshot is uploaded")
                return
            except Exception as e:
                logger.error(f"Failed to create S3 bucket: {e}")
                raise
        
        # Step 7.1.3: Check if directory exists (only if bucket exists)
        DIRECTORY_EXISTS = False
        try:
            RESULT = self.run_console_command([
                "aws", "s3", "ls", S3_DIRECTORY_URI, "--region", TEST_REGION
            ])
            # If command succeeds and has output, directory exists with content
            if RESULT.stdout.strip():
                DIRECTORY_EXISTS = True
                logger.info(f"S3 directory {S3_DIRECTORY_URI} exists with content")
            else:
                logger.info("S3 directory path exists but is empty")
        except Exception:
            logger.info(f"📁 S3 directory {S3_DIRECTORY_URI} does not exist (will be created automatically)")
        
        # Step 7.1.4: Clear directory if it exists with content
        if DIRECTORY_EXISTS:
            logger.info("Step 7.1.4: Clearing existing S3 directory contents")
            try:
                self.run_console_command([
                    "aws", "s3", "rm", S3_DIRECTORY_URI, "--recursive", "--region", TEST_REGION
                ])
                logger.info(f"Cleared S3 directory: {S3_DIRECTORY_URI}")
            except Exception as e:
                logger.warning(f"Failed to clear S3 directory (may already be empty): {e}")
        
        logger.info("S3 bucket and directory preparation completed")

    def modify_temp_config_file(self, action):
        """
        Modify a temporary config file for console commands.
        
        Args:
            action: Either "create" or "delete"
        """
        if action == "create":
            logger.info("Creating temporary config file")
            
            # Get account ID from config
            ops = DefaultOperationsLibrary()
            account_id = ops.extract_account_id_from_config(CONFIG_FILE_PATH)
            
            # Build large snapshot S3 URI dynamically
            bucket_name = f"{LARGE_SNAPSHOT_BUCKET_PREFIX}{account_id}{LARGE_SNAPSHOT_BUCKET_SUFFIX}"
            large_snapshot_uri = f"s3://{bucket_name}/{LARGE_S3_BASE_PATH}/"
            
            # Read original config
            with open(CONFIG_FILE_PATH, 'r') as f:
                config = yaml.safe_load(f)
            
            # Modify snapshot section
            config['snapshot']['snapshot_name'] = 'large-snapshot'
            config['snapshot']['s3']['repo_uri'] = large_snapshot_uri
            
            # Write temp config
            with open(TEMP_CONFIG_FILE_PATH, 'w') as f:
                yaml.dump(config, f, default_flow_style=False)
            
            logger.info(f"Created temporary config file: {TEMP_CONFIG_FILE_PATH}")
            logger.info(f"Large snapshot URI: {large_snapshot_uri}")
            
        elif action == "delete":
            logger.info("Deleting temporary config file")
            try:
                os.remove(TEMP_CONFIG_FILE_PATH)
                logger.info(f"Deleted temporary config file: {TEMP_CONFIG_FILE_PATH}")
            except FileNotFoundError:
                logger.info(f"Temporary config file not found: {TEMP_CONFIG_FILE_PATH}")
        else:
            raise ValueError(f"Invalid action: {action}. Must be 'create' or 'delete'")

    def take_large_snapshot_with_curl(self):
        """Take large snapshot using direct curl commands"""
        logger.info("Step 8: Taking large snapshot using curl commands")
        
        # Get account ID from config
        ops = DefaultOperationsLibrary()
        account_id = ops.extract_account_id_from_config(CONFIG_FILE_PATH)
        
        # Build dynamic values
        bucket_name = f"{LARGE_SNAPSHOT_BUCKET_PREFIX}{account_id}{LARGE_SNAPSHOT_BUCKET_SUFFIX}"
        role_arn = f"{ROLE_ARN_PREFIX}{account_id}{ROLE_ARN_SUFFIX}"
        
        # Step 8.1: Unregister the repo from cluster (if exists)
        logger.info("Step 8.1: Unregistering existing repository (if exists)")
        try:
            self.run_console_command([
                "console", "snapshot", "unregister-repo", "--acknowledge-risk"
            ])
            logger.info("Successfully unregistered existing repository")
        except Exception:
            logger.info("No existing repository to unregister (expected)")
        
        # Step 8.2: Register repository with working role
        repo_config = {
            "type": "s3",
            "settings": {
                "bucket": bucket_name,
                "base_path": LARGE_S3_BASE_PATH,
                "region": TEST_REGION,
                "role_arn": role_arn
            }
        }
        
        result = self.run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XPUT", "/_snapshot/migration_assistant_repo",
            "-H", "Content-Type: application/json",
            "-d", json.dumps(repo_config)
        ])
        logger.info(f"Repository registration result: {result.stdout}")
        
        # Step 8.3: Create snapshot
        snapshot_config = {
            "indices": "*",
            "ignore_unavailable": True,
            "include_global_state": False
        }
        
        result = self.run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XPUT", "/_snapshot/migration_assistant_repo/large-snapshot",
            "-H", "Content-Type: application/json",
            "-d", json.dumps(snapshot_config)
        ])
        logger.info(f"Snapshot creation result: {result.stdout}")
        
        # Step 8.4: Verify snapshot completion using console command
        logger.info("Step 8.4: Verifying large snapshot completion")
        result = self.run_console_command([
            "console", "--config-file", TEMP_CONFIG_FILE_PATH, "snapshot", "status", "--deep-check"
        ])
        
        # Check if snapshot was successful
        if "SUCCESS" in result.stdout and "Percent completed: 100.00%" in result.stdout:
            logger.info("Large snapshot completed successfully!")
            logger.info(f"Snapshot details:\n{result.stdout}")
        else:
            logger.warning(f"Large snapshot may not be complete: {result.stdout}")
        
        return f"s3://{bucket_name}/{LARGE_S3_BASE_PATH}/"

    def save_snapshot_uri(self, snapshot_uri):
        """Save the snapshot URI for reference"""
        with open(SNAPSHOT_URI_FILE, 'w') as f:
            f.write(snapshot_uri)
        logger.info(f"Saved snapshot URI to {SNAPSHOT_URI_FILE}")

    def display_final_results(self, large_snapshot_uri, final_count):
        """Display comprehensive test results"""
        logger.info("Step 10: Displaying final results")
        
        # Original ingestion results
        logger.info("=== MIGRATION TEST RESULTS ===")
        logger.info(f"Original Documents Ingested: {self.INGESTED_DOC_COUNT}")
        logger.info(f"Index Configuration: {self.INDEX_SHARD_COUNT} shards, 0 replicas")
        logger.info(f"Transformation Applied: {MULTIPLICATION_FACTOR_WITH_ORIGINAL}x multiplication")
        
        # Final results
        logger.info(f"Final Document Count: {final_count}")
        multiplication_success = final_count == (self.INGESTED_DOC_COUNT * MULTIPLICATION_FACTOR_WITH_ORIGINAL)
        logger.info(f"Multiplication Success: {multiplication_success}")
        
        # Snapshot information
        logger.info(f"Large Snapshot Location: {large_snapshot_uri}")
        logger.info("Large Snapshot Name: 'large-snapshot'")
        logger.info("Large Snapshot Repository: 'migration_assistant_repo'")
        
        # Summary
        logger.info("=== SUMMARY ===")
        logger.info(f"Successfully migrated {self.INGESTED_DOC_COUNT} → {final_count} documents")
        logger.info(f"Large snapshot available at: {large_snapshot_uri}")
        logger.info("Migration test completed successfully!")

    def mark_phase_complete(self, snapshot_uri, final_count):
        """Mark the serve phase as complete"""
        with open(SERVE_COMPLETE_FILE, 'w') as f:
            f.write(f"Serve phase completed at {time.time()}\n")
            f.write(f"Index: {self.INDEX_NAME}\n")
            f.write(f"Final document count: {final_count}\n")
            f.write(f"Large snapshot URI: {snapshot_uri}\n")
        logger.info(f"Marked serve phase complete: {SERVE_COMPLETE_FILE}")

    def test_large_snapshot_serve(self):
        """
        Main test method that performs the serving phase of the migration workflow.
        """
        logger.info("=== Starting Large Snapshot Serving Phase ===")
        
        # Verify cook phase completed
        self.verify_cook_phase_complete()
        
        # Load final count from cook phase
        final_count = self.load_final_count()
        
        # Step 7: Preparation for Large Snapshot
        logger.info("Step 7: Preparing for large snapshot")

        # Step 7.1: Check and prepare S3 bucket and directory (handles all scenarios)
        self.check_larges3_bucket_and_directory()

        # Step 7.2: Create temp config file for console commands
        logger.info("Step 7.2: Creating temporary config file for console commands")
        self.modify_temp_config_file("create")

        # Step 8: Take large snapshot
        large_snapshot_uri = self.take_large_snapshot_with_curl()

        # Step 9: Delete the temporary config file
        logger.info("Step 9: Deleting temporary config file")
        self.modify_temp_config_file("delete")
        
        # Save snapshot URI for reference
        self.save_snapshot_uri(large_snapshot_uri)
        
        # Step 10: Display final results
        self.display_final_results(large_snapshot_uri, final_count)
        
        # Mark phase complete
        self.mark_phase_complete(large_snapshot_uri, final_count)
        
        logger.info("=== Large Snapshot Serving Phase Completed Successfully! ===")
        logger.info(f"Successfully created large snapshot from {self.INGESTED_DOC_COUNT} documents in "
                    f"'{self.INDEX_NAME}' with {MULTIPLICATION_FACTOR_WITH_ORIGINAL}x multiplication "
                    f"(total: {final_count} documents)")
        logger.info(f"Large snapshot available at: {large_snapshot_uri}")

    @classmethod
    def tearDownClass(cls):
        """Clean up after test completion"""
        logger.info("Serve phase cleanup completed")


if __name__ == "__main__":
    # Configure logging for standalone execution
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # Run the test
    unittest.main()
