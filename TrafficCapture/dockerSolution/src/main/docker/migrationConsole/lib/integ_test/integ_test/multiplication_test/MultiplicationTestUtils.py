"""
Utility functions for multiplication test suite.
Organized by usage pattern: CleanUpAndPrepare, GenerateLargeSnapshot, CreateFinalSnapshot.
"""

import json
import logging
import os
import shutil
import subprocess
import yaml
from ..default_operations import DefaultOperationsLibrary
from .JenkinsParamConstants import (
    COMMAND_TIMEOUT_SECONDS,
    S3_BUCKET_URI_PREFIX,
    S3_BUCKET_SUFFIX,
    SNAPSHOT_REPO_NAME,
    LARGE_SNAPSHOT_BUCKET_PREFIX,
    LARGE_SNAPSHOT_BUCKET_SUFFIX,
    LARGE_S3_BASE_PATH,
    TRANSFORMATION_DIRECTORY,
    MULTIPLICATION_FACTOR_WITH_ORIGINAL,
    TRANSFORMATION_FILE_PATH
)

logger = logging.getLogger(__name__)


# =============================================================================
# SHARED UTILITIES (Used by multiple files)
# =============================================================================

def run_console_command(COMMAND_ARGS, TIMEOUT=COMMAND_TIMEOUT_SECONDS):
    """Execute console command and return result with error handling."""
    try:
        logger.info(f"Executing: {' '.join(COMMAND_ARGS)}")
        RESULT = subprocess.run(
            COMMAND_ARGS,
            capture_output=True,
            text=True,
            timeout=TIMEOUT,
            check=True
        )
        logger.info("Command completed successfully")
        return RESULT
    except subprocess.CalledProcessError as e:
        ERROR_MSG = f"Command failed: {' '.join(COMMAND_ARGS)}\nStdout: {e.stdout}\nStderr: {e.stderr}"
        logger.error(ERROR_MSG)
        raise RuntimeError(ERROR_MSG)
    except subprocess.TimeoutExpired:
        ERROR_MSG = f"Command timed out after {TIMEOUT}s: {' '.join(COMMAND_ARGS)}"
        logger.error(ERROR_MSG)
        raise TimeoutError(ERROR_MSG)


def extract_account_id_from_config(CONFIG_FILE_PATH=None):
    """Extract AWS account ID from migration services configuration file."""
    if CONFIG_FILE_PATH is None:
        CONFIG_FILE_PATH = "/config/migration_services.yaml"
        
    try:
        with open(CONFIG_FILE_PATH, 'r') as f:
            CONFIG = yaml.safe_load(f)
        
        # Extract role ARN from snapshot.s3.role
        ROLE_ARN = CONFIG.get('snapshot', {}).get('s3', {}).get('role', '')
        
        if not ROLE_ARN:
            raise ValueError("No role ARN found in snapshot.s3.role")
        
        # Parse account ID from ARN format: arn:aws:iam::ACCOUNT_ID:role/ROLE_NAME
        if ':' in ROLE_ARN:
            ARN_PARTS = ROLE_ARN.split(':')
            if len(ARN_PARTS) >= 5:
                ACCOUNT_ID = ARN_PARTS[4]
                if ACCOUNT_ID and ACCOUNT_ID.isdigit():
                    logger.debug(f"Extracted account ID: {ACCOUNT_ID}")
                    return ACCOUNT_ID
        
        raise ValueError(f"Could not extract account ID from role ARN: {ROLE_ARN}")
        
    except FileNotFoundError:
        raise ValueError(f"Config file not found: {CONFIG_FILE_PATH}")
    except yaml.YAMLError as e:
        raise ValueError(f"Error parsing YAML config file: {e}")
    except Exception as e:
        raise ValueError(f"Error extracting account ID from config: {e}")


# =============================================================================
# CLEANUP AND PREPARE UTILITIES
# =============================================================================

def cleanup_snapshots_and_repos(ACCOUNT_ID, TEST_STAGE, REGION):
    """Clean up existing snapshots and repositories for fresh test state."""
    logger.info("Cleaning up existing snapshots and repositories")
    
    # Build S3 bucket URI dynamically
    S3_BUCKET_URI = f"{S3_BUCKET_URI_PREFIX}{ACCOUNT_ID}-{TEST_STAGE}-{REGION}{S3_BUCKET_SUFFIX}"
    
    CLEANUP_COMMANDS = [
        ["console", "snapshot", "delete", "--acknowledge-risk"],  # Delete snapshot
        ["console", "clusters", "curl", "source_cluster", "-XDELETE", 
         f"/_snapshot/{SNAPSHOT_REPO_NAME}"],  # Delete repo
        ["aws", "s3", "rm", S3_BUCKET_URI, "--recursive"]  # Clear S3 contents
    ]
    
    for CMD in CLEANUP_COMMANDS:
        try:
            run_console_command(CMD)
            logger.info(f"Cleanup succeeded: {' '.join(CMD)}")
        except Exception:
            logger.warning(f"Cleanup failed (expected if resource doesn't exist): {' '.join(CMD)}")


def clear_clusters(TEST_STAGE, TEST_REGION):
    """Clear both source and target clusters and clean up existing snapshots."""
    # Clean up snapshots/repos first
    ACCOUNT_ID = extract_account_id_from_config()
    cleanup_snapshots_and_repos(ACCOUNT_ID, TEST_STAGE, TEST_REGION)
    
    # Clear cluster indices
    run_console_command([
        "console", "clusters", "clear-indices", "--cluster", "source",
        "--acknowledge-risk"
    ])


def create_index_with_shards(INDEX_NAME, INDEX_SHARD_COUNT):
    """Create index with specified number of shards."""
    INDEX_SETTINGS = {
        "settings": {
            "number_of_shards": INDEX_SHARD_COUNT,
            "number_of_replicas": 0
        }
    }
    
    run_console_command([
        "console", "clusters", "curl", "source_cluster",
        "-XPUT", f"/{INDEX_NAME}",
        "-d", json.dumps(INDEX_SETTINGS),
        "-H", "Content-Type: application/json"
    ])
    
    logger.info(f"Index '{INDEX_NAME}' created with {INDEX_SHARD_COUNT} shards")


def ingest_test_data(INDEX_NAME, INGESTED_DOC_COUNT, INGEST_DOC):
    """Ingest test documents to source cluster using bulk API."""
    BULK_BODY_LINES = []
    for DOC_INDEX in range(1, INGESTED_DOC_COUNT + 1):
        DOC_ID = str(DOC_INDEX)
        
        # Create document from template
        DOCUMENT = INGEST_DOC.copy()
        DOCUMENT["doc_number"] = str(DOC_INDEX)
        
        # Add bulk API format: index action + document source
        INDEX_ACTION = {"index": {"_index": INDEX_NAME, "_id": DOC_ID}}
        BULK_BODY_LINES.append(json.dumps(INDEX_ACTION))
        BULK_BODY_LINES.append(json.dumps(DOCUMENT))
    
    # Join with newlines and add final newline (bulk API requirement)
    BULK_BODY = "\n".join(BULK_BODY_LINES) + "\n"
    
    # Execute bulk request
    run_console_command([
        "console", "clusters", "curl", "source_cluster",
        "-XPOST", "/_bulk",
        "-d", BULK_BODY,
        "-H", "Content-Type: application/x-ndjson"
    ])
    
    # Verify document count
    logger.info("Verifying document ingestion")
    RESULT = run_console_command([
        "console", "clusters", "curl", "source_cluster",
        "-XGET", f"/{INDEX_NAME}/_count"
    ])
    
    # Parse and validate count
    RESPONSE_DATA = json.loads(RESULT.stdout)
    ACTUAL_COUNT = RESPONSE_DATA.get("count", 0)
    if ACTUAL_COUNT != INGESTED_DOC_COUNT:
        raise RuntimeError(f"Document count mismatch: Expected {INGESTED_DOC_COUNT}, found {ACTUAL_COUNT}")
    
    logger.info(f"Successfully ingested {ACTUAL_COUNT} documents to source cluster")


def create_transformation_config():
    """Create transformation file with document multiplication configuration."""
    try:
        shutil.rmtree(TRANSFORMATION_DIRECTORY)
        logger.info("Removed existing transformation directory")
    except FileNotFoundError:
        logger.info("No existing transformation files to cleanup")
    
    # Create multiplication transformation JavaScript
    INITIALIZATION_SCRIPT = (
        f"const MULTIPLICATION_FACTOR_WITH_ORIGINAL = {MULTIPLICATION_FACTOR_WITH_ORIGINAL}; "
        "function transform(document) { "
        "if (!document) { throw new Error(\"No source_document was defined - nothing to transform!\"); } "
        "const indexCommandMap = document.get(\"index\"); "
        "const originalSource = document.get(\"source\"); "
        "const originalId = indexCommandMap.get(\"_id\"); "
        "const docsToCreate = []; "
        "for (let i = 0; i < MULTIPLICATION_FACTOR_WITH_ORIGINAL; i++) { "
        "const newIndexMap = new Map(indexCommandMap); "
        "const newId = (i === 0) ? originalId : originalId + '_' + i; "
        "newIndexMap.set(\"_id\", newId); "
        "docsToCreate.push(new Map([[\"index\", newIndexMap], [\"source\", originalSource]])); "
        "} "
        "return docsToCreate; "
        "} "
        "function main(context) { "
        "console.log(\"Context: \", JSON.stringify(context, null, 2)); "
        "return (document) => { "
        "if (Array.isArray(document)) { "
        "return document.flatMap((item) => transform(item, context)); "
        "} "
        "return transform(document); "
        "}; "
        "} "
        "(() => main)();"
    )
    
    MULTIPLICATION_TRANSFORM = {
        "JsonJSTransformerProvider": {
            "initializationScript": INITIALIZATION_SCRIPT,
            "bindingsObject": "{}"
        }
    }
    
    # Create transformation config
    COMBINED_CONFIG = [MULTIPLICATION_TRANSFORM]
    
    # Save to file using default operations library
    OPS = DefaultOperationsLibrary()
    OPS.create_transformation_json_file(COMBINED_CONFIG, TRANSFORMATION_FILE_PATH)
    logger.info(f"Created transformation config at {TRANSFORMATION_FILE_PATH}")


# =============================================================================
# GENERATE LARGE SNAPSHOT UTILITIES
# =============================================================================

def get_target_document_count_for_index(INDEX_NAME):
    """Get document count from target cluster for specified index."""
    try:
        RESULT = run_console_command([
            "console", "clusters", "curl", "target_cluster",
            "-XGET", f"/{INDEX_NAME}/_count"
        ])
        
        # Parse JSON response to extract count
        RESPONSE_DATA = json.loads(RESULT.stdout)
        COUNT = RESPONSE_DATA.get("count", 0)
        logger.debug(f"Target cluster document count for {INDEX_NAME}: {COUNT}")
        return COUNT
    except (json.JSONDecodeError, KeyError) as e:
        logger.warning(f"Failed to parse document count response for {INDEX_NAME}: {e}")
        return 0


# =============================================================================
# CREATE FINAL SNAPSHOT UTILITIES
# =============================================================================

def check_and_prepare_s3_bucket(JENKINS_BUCKET_NAME, DIRECTORY_PATH, REGION):
    """Check and prepare S3 bucket and directory for large snapshot storage."""
    logger.info("Checking and preparing S3 bucket and directory")
    
    S3_BUCKET_URI = f"s3://{JENKINS_BUCKET_NAME}/"
    S3_DIRECTORY_URI = f"s3://{JENKINS_BUCKET_NAME}/{DIRECTORY_PATH}/"
    
    logger.info(f"Target bucket: {JENKINS_BUCKET_NAME}")
    logger.info(f"Target directory: {DIRECTORY_PATH}")
    
    # Check if bucket exists
    BUCKET_EXISTS = False
    try:
        run_console_command(["aws", "s3", "ls", S3_BUCKET_URI, "--region", REGION])
        BUCKET_EXISTS = True
        logger.info(f"S3 bucket {JENKINS_BUCKET_NAME} exists")
    except Exception:
        logger.info(f"S3 bucket {JENKINS_BUCKET_NAME} does not exist")
    
    # Create bucket if needed
    if not BUCKET_EXISTS:
        logger.info("Creating S3 bucket")
        try:
            run_console_command(["aws", "s3", "mb", S3_BUCKET_URI, "--region", REGION])
            logger.info(f"S3 bucket created: {JENKINS_BUCKET_NAME}")
            logger.info("Directory will be created automatically when snapshot is uploaded")
            return
        except Exception as e:
            logger.error(f"Failed to create S3 bucket: {e}")
            raise
    
    # Check if directory exists with content
    DIRECTORY_EXISTS = False
    try:
        RESULT = run_console_command(["aws", "s3", "ls", S3_DIRECTORY_URI, "--region", REGION])
        if RESULT.stdout.strip():  # Directory has content
            DIRECTORY_EXISTS = True
            logger.info(f"S3 directory {S3_DIRECTORY_URI} exists with content")
        else:
            logger.info("S3 directory path exists but is empty")
    except Exception:
        logger.info(f"S3 directory {S3_DIRECTORY_URI} does not exist (will be created automatically)")
    
    # Clear directory if it has content
    if DIRECTORY_EXISTS:
        logger.info("Clearing existing S3 directory contents")
        try:
            run_console_command(["aws", "s3", "rm", S3_DIRECTORY_URI, "--recursive", "--region", REGION])
            logger.info(f"Cleared S3 directory: {S3_DIRECTORY_URI}")
        except Exception as e:
            logger.warning(f"Failed to clear S3 directory (may already be empty): {e}")
    
    logger.info("S3 bucket and directory preparation completed")


def modify_temp_config_file(ACTION, CONFIG_FILE_PATH, TEMP_CONFIG_FILE_PATH, **KWARGS):
    """Create or delete temporary config file for console commands."""
    if ACTION == "create":
        logger.info("Creating temporary config file")
        
        # Get account ID and build S3 URI
        ACCOUNT_ID = extract_account_id_from_config(CONFIG_FILE_PATH)
        JENKINS_BUCKET_NAME = f"{LARGE_SNAPSHOT_BUCKET_PREFIX}{ACCOUNT_ID}{LARGE_SNAPSHOT_BUCKET_SUFFIX}"
        LARGE_SNAPSHOT_URI = f"s3://{JENKINS_BUCKET_NAME}/{LARGE_S3_BASE_PATH}/"
        
        # Read and modify config
        with open(CONFIG_FILE_PATH, 'r') as f:
            CONFIG = yaml.safe_load(f)
        
        CONFIG['snapshot']['snapshot_name'] = 'large-snapshot'
        CONFIG['snapshot']['s3']['repo_uri'] = LARGE_SNAPSHOT_URI
        
        # Write temporary config
        with open(TEMP_CONFIG_FILE_PATH, 'w') as f:
            yaml.dump(CONFIG, f, default_flow_style=False)
        
        logger.info(f"Created temporary config file: {TEMP_CONFIG_FILE_PATH}")
        logger.info(f"Large snapshot URI: {LARGE_SNAPSHOT_URI}")
        
    elif ACTION == "delete":
        logger.info("Deleting temporary config file")
        try:
            os.remove(TEMP_CONFIG_FILE_PATH)
            logger.info(f"Deleted temporary config file: {TEMP_CONFIG_FILE_PATH}")
        except FileNotFoundError:
            logger.info(f"Temporary config file not found: {TEMP_CONFIG_FILE_PATH}")
    else:
        raise ValueError(f"Invalid action: {ACTION}. Must be 'create' or 'delete'")


def display_final_results(LARGE_SNAPSHOT_URI, FINAL_COUNT, INGESTED_DOC_COUNT, INDEX_SHARD_COUNT):
    """Display comprehensive migration test results."""
    logger.info("=== MIGRATION TEST RESULTS ===")
    logger.info(f"Original Documents Ingested: {INGESTED_DOC_COUNT}")
    logger.info(f"Index Configuration: {INDEX_SHARD_COUNT} shards, 0 replicas")
    logger.info(f"Transformation Applied: {MULTIPLICATION_FACTOR_WITH_ORIGINAL}x multiplication")
    
    # Final results
    logger.info(f"Final Document Count: {FINAL_COUNT}")
    MULTIPLICATION_SUCCESS = FINAL_COUNT == (INGESTED_DOC_COUNT * MULTIPLICATION_FACTOR_WITH_ORIGINAL)
    logger.info(f"Multiplication Success: {MULTIPLICATION_SUCCESS}")
    
    # Snapshot information
    logger.info(f"Large Snapshot Location: {LARGE_SNAPSHOT_URI}")
    logger.info("Large Snapshot Name: 'large-snapshot'")
    logger.info("Large Snapshot Repository: 'migration_assistant_repo'")
    
    # Summary
    logger.info("=== SUMMARY ===")
    logger.info(f"Successfully migrated {INGESTED_DOC_COUNT} → {FINAL_COUNT} documents")
    logger.info(f"Large snapshot available at: {LARGE_SNAPSHOT_URI}")
    logger.info("Migration test completed successfully!")
