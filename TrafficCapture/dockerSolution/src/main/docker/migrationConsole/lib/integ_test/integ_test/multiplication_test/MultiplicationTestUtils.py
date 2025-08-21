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
from integ_test.default_operations import DefaultOperationsLibrary

logger = logging.getLogger(__name__)

COMMAND_TIMEOUT_SECONDS = 300
SNAPSHOT_POLL_INTERVAL = 3
BACKFILL_POLL_INTERVAL = 5
STABILITY_CHECK_INTERVAL = 15
STABILITY_CHECK_COUNT = 4
UNIQUE_ID = "large_snapshot_test"
TEMP_CONFIG_FILE_PATH = "/config/migration_large_snapshot.yaml"
TRANSFORMATION_DIRECTORY = "/shared-logs-output/test-transformations"
INGEST_DOC = {"title": "Large Snapshot Migration Test Document"}

# lets gooooo


def get_config_values(CONFIG_FILE_PATH=None):
    """Extract all needed values from migration services configuration."""
    if CONFIG_FILE_PATH is None:
        CONFIG_FILE_PATH = "/config/migration_services.yaml"
    
    try:
        with open(CONFIG_FILE_PATH, 'r') as f:
            CONFIG = yaml.safe_load(f)
        
        SNAPSHOT_CONFIG = CONFIG.get('snapshot', {})
        S3_CONFIG = SNAPSHOT_CONFIG.get('s3', {})
        
        return {
            'snapshot_repo_name': SNAPSHOT_CONFIG.get('snapshot_repo_name', 'migration_assistant_repo'),
            'repo_uri': S3_CONFIG.get('repo_uri', ''),
            'role_arn': S3_CONFIG.get('role', ''),
            'aws_region': S3_CONFIG.get('aws_region', 'us-west-2'),
        }
    except FileNotFoundError:
        raise ValueError(f"Config file not found: {CONFIG_FILE_PATH}")
    except yaml.YAMLError as e:
        raise ValueError(f"Error parsing YAML config file: {e}")


def get_environment_values():
    """Get values from environment variables with defaults."""
    return {
        'stage': os.getenv('STAGE', 'dev'),
        'large_snapshot_bucket_prefix': os.getenv('LARGE_SNAPSHOT_BUCKET_PREFIX', 'migrations-jenkins-snapshot-'),
        'large_s3_directory_prefix': os.getenv('LARGE_S3_DIRECTORY_PREFIX', 'large-snapshot-'),
        'cluster_version': os.getenv('CLUSTER_VERSION', 'es7x'),
        'snapshot_region': os.getenv('SNAPSHOT_REGION', 'us-west-2'),
        'multiplication_factor': int(os.getenv('MULTIPLICATION_FACTOR', '10')),
    }


def build_bucket_names_and_paths(ACCOUNT_ID, ENV_VALUES, CONFIG_VALUES):
    """Build S3 bucket names and paths using account ID and configuration."""
    STAGE = ENV_VALUES['stage']
    REGION = ENV_VALUES['snapshot_region']
    
    # Default RFS bucket pattern: migration-artifacts-{account_id}-{stage}-{region}
    DEFAULT_S3_BUCKET_URI = f"s3://migration-artifacts-{ACCOUNT_ID}-{STAGE}-{REGION}/rfs-snapshot-repo/"
    
    # Large snapshot bucket pattern: migrations-jenkins-snapshot-{account_id}-{region}
    LARGE_SNAPSHOT_BUCKET_NAME = f"{ENV_VALUES['large_snapshot_bucket_prefix']}{ACCOUNT_ID}-{REGION}"
    
    # Large S3 directory path
    LARGE_S3_BASE_PATH = f"{ENV_VALUES['large_s3_directory_prefix']}{ENV_VALUES['cluster_version']}"
    
    # Large snapshot S3 URI
    LARGE_SNAPSHOT_URI = f"s3://{LARGE_SNAPSHOT_BUCKET_NAME}/{LARGE_S3_BASE_PATH}/"
    
    # Role ARN for large snapshot
    LARGE_SNAPSHOT_ROLE_ARN = f"arn:aws:iam::{ACCOUNT_ID}:role/largesnapshotfinal"
    
    return {
        'default_s3_bucket_uri': DEFAULT_S3_BUCKET_URI,
        'large_snapshot_bucket_name': LARGE_SNAPSHOT_BUCKET_NAME,
        'large_s3_base_path': LARGE_S3_BASE_PATH,
        'large_snapshot_uri': LARGE_SNAPSHOT_URI,
        'large_snapshot_role_arn': LARGE_SNAPSHOT_ROLE_ARN,
    }


def get_transformation_file_path():
    """Get the transformation file path."""
    return f"{TRANSFORMATION_DIRECTORY}/transformation.json"


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


def extract_account_id_from_config(config_file_path=None):
    """Extract AWS account ID from migration services configuration file."""
    if config_file_path is None:
        config_file_path = "/config/migration_services.yaml"
        
    try:
        with open(config_file_path, 'r') as f:
            config = yaml.safe_load(f)
        
        # Extract role ARN from snapshot.s3.role
        role_arn = config.get('snapshot', {}).get('s3', {}).get('role', '')
        
        if not role_arn:
            raise ValueError("No role ARN found in snapshot.s3.role")
        
        # Parse account ID from ARN format: arn:aws:iam::ACCOUNT_ID:role/ROLE_NAME
        if ':' in role_arn:
            arn_parts = role_arn.split(':')
            if len(arn_parts) >= 5:
                account_id = arn_parts[4]
                if account_id and account_id.isdigit():
                    logger.debug(f"Extracted account ID: {account_id}")
                    return account_id
        
        raise ValueError(f"Could not extract account ID from role ARN: {role_arn}")
        
    except FileNotFoundError:
        raise ValueError(f"Config file not found: {config_file_path}")
    except yaml.YAMLError as e:
        raise ValueError(f"Error parsing YAML config file: {e}")
    except Exception as e:
        raise ValueError(f"Error extracting account ID from config: {e}")


# =============================================================================
# CLEANUP AND PREPARE UTILITIES
# =============================================================================

def cleanup_snapshots_and_repos(account_id, stage, region):
    """Clean up existing snapshots and repositories for fresh test state."""
    logger.info("Cleaning up existing snapshots and repositories")
    
    # Get config values
    config_values = get_config_values()
    snapshot_repo_name = config_values['snapshot_repo_name']
    
    # Build S3 bucket URI dynamically using the standard pattern
    s3_bucket_uri = f"s3://migration-artifacts-{account_id}-{stage}-{region}/rfs-snapshot-repo/"
    
    cleanup_commands = [
        ["console", "snapshot", "delete", "--acknowledge-risk"],  # Delete snapshot
        ["console", "clusters", "curl", "source_cluster", "-XDELETE",
         f"/_snapshot/{snapshot_repo_name}"],  # Delete repo
        ["aws", "s3", "rm", s3_bucket_uri, "--recursive"]  # Clear S3 contents
    ]
    
    for cmd in cleanup_commands:
        try:
            run_console_command(cmd)
            logger.info(f"Cleanup succeeded: {' '.join(cmd)}")
        except Exception:
            logger.warning(f"Cleanup failed (expected if resource doesn't exist): {' '.join(cmd)}")


def clear_clusters(stage, region):
    """Clear both source and target clusters and clean up existing snapshots."""
    # Clean up snapshots/repos first
    account_id = extract_account_id_from_config()
    cleanup_snapshots_and_repos(account_id, stage, region)
    
    # Clear cluster indices
    run_console_command([
        "console", "clusters", "clear-indices", "--cluster", "source",
        "--acknowledge-risk"
    ])


def create_index_with_shards(index_name, index_shard_count):
    """Create index with specified number of shards."""
    index_settings = {
        "settings": {
            "number_of_shards": index_shard_count,
            "number_of_replicas": 0
        }
    }
    
    run_console_command([
        "console", "clusters", "curl", "source_cluster",
        "-XPUT", f"/{index_name}",
        "-d", json.dumps(index_settings),
        "-H", "Content-Type: application/json"
    ])
    
    logger.info(f"Index '{index_name}' created with {index_shard_count} shards")


def calculate_optimal_batch_size(total_docs, max_batch_size=1000):
    """Calculate optimal batch size to prevent bulk request errors."""
    if total_docs <= max_batch_size:
        return total_docs
    
    optimal_size = max_batch_size
    while total_docs % optimal_size > optimal_size * 0.1 and optimal_size > 100:
        optimal_size -= 50
    
    return max(optimal_size, 100)


def ingest_test_data(index_name, ingested_doc_count, ingest_doc):
    """Ingest test documents to source cluster using dynamic batching for scalability."""
    total_docs = ingested_doc_count
    batch_size = calculate_optimal_batch_size(total_docs)
    num_batches = (total_docs + batch_size - 1) // batch_size
    
    logger.info(f"Ingesting {total_docs} documents in {num_batches} batches of ~{batch_size} documents each")
    
    total_ingested = 0
    
    for batch_num in range(num_batches):
        start_doc = batch_num * batch_size + 1
        end_doc = min((batch_num + 1) * batch_size, total_docs)
        batch_doc_count = end_doc - start_doc + 1
        
        bulk_body_lines = []
        for doc_index in range(start_doc, end_doc + 1):
            doc_id = str(doc_index)
            
            document = ingest_doc.copy()
            document["doc_number"] = str(doc_index)
            
            index_action = {"index": {"_index": index_name, "_id": doc_id}}
            bulk_body_lines.append(json.dumps(index_action))
            bulk_body_lines.append(json.dumps(document))
        
        bulk_body = "\n".join(bulk_body_lines) + "\n"
        
        logger.info(f"Batch {batch_num + 1}/{num_batches}: Ingesting documents "
                    f"{start_doc}-{end_doc} ({batch_doc_count} docs)")
        
        run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XPOST", "/_bulk",
            "-d", bulk_body,
            "-H", "Content-Type: application/x-ndjson"
        ])
        
        total_ingested += batch_doc_count
    
    run_console_command([
        "console", "clusters", "curl", "source_cluster",
        "-XPOST", "/_refresh"
    ])
    result = run_console_command([
        "console", "clusters", "curl", "source_cluster",
        "-XGET", f"/{index_name}/_count"
    ])
    
    response_data = json.loads(result.stdout)
    actual_count = response_data.get("count", 0)
    if actual_count != ingested_doc_count:
        raise RuntimeError(f"Document count mismatch: Expected {ingested_doc_count}, found {actual_count}")
    
    logger.info(f"Successfully ingested {actual_count} documents to source cluster using {num_batches} batches")


def create_transformation_config(multiplication_factor):
    """Create transformation file with document multiplication configuration."""
    try:
        shutil.rmtree(TRANSFORMATION_DIRECTORY)
        logger.info("Removed existing transformation directory")
    except FileNotFoundError:
        logger.info("No existing transformation files to cleanup")
    
    # Create multiplication transformation JavaScript
    initialization_script = (
        f"const MULTIPLICATION_FACTOR_WITH_ORIGINAL = {multiplication_factor}; "
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
    
    multiplication_transform = {
        "JsonJSTransformerProvider": {
            "initializationScript": initialization_script,
            "bindingsObject": "{}"
        }
    }
    
    # Create transformation config
    combined_config = [multiplication_transform]
    
    # Get transformation file path
    transformation_file_path = get_transformation_file_path()
    
    # Save to file using default operations library
    ops = DefaultOperationsLibrary()
    ops.create_transformation_json_file(combined_config, transformation_file_path)
    logger.info(f"Created transformation config at {transformation_file_path}")


# =============================================================================
# GENERATE LARGE SNAPSHOT UTILITIES
# =============================================================================

def get_target_document_count_for_index(INDEX_NAME):
    """Get document count from target cluster for specified index."""
    try:
        # Add explicit refresh before counting to ensure accurate results
        run_console_command([
            "console", "clusters", "curl", "target_cluster",
            "-XPOST", "/_refresh"
        ])
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
    """Check and prepare S3 bucket directory for large snapshot storage (assumes bucket exists)."""
    logger.info("Checking and preparing S3 bucket directory")
    
    S3_DIRECTORY_URI = f"s3://{JENKINS_BUCKET_NAME}/{DIRECTORY_PATH}/"
    
    logger.info(f"Target bucket: {JENKINS_BUCKET_NAME}")
    logger.info(f"Target directory: {DIRECTORY_PATH}")
    logger.info("Assuming S3 bucket already exists as per requirements")
    
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
    
    logger.info("S3 bucket directory preparation completed")


def modify_temp_config_file(action, config_file_path, temp_config_file_path, **kwargs):
    """Create or delete temporary config file for console commands."""
    if action == "create":
        logger.info("Creating temporary config file")
        
        # Get account ID and environment values
        account_id = extract_account_id_from_config(config_file_path)
        env_values = get_environment_values()
        config_values = get_config_values(config_file_path)
        
        # Build bucket names and paths
        bucket_info = build_bucket_names_and_paths(account_id, env_values, config_values)
        
        # Read and modify config
        with open(config_file_path, 'r') as f:
            config = yaml.safe_load(f)
        
        # Update snapshot configuration with all required fields
        config['snapshot']['snapshot_name'] = 'large-snapshot'
        config['snapshot']['snapshot_repo_name'] = 'migrations_jenkins_repo'
        config['snapshot']['s3']['repo_uri'] = bucket_info['large_snapshot_uri']
        config['snapshot']['s3']['aws_region'] = env_values['snapshot_region']
        config['snapshot']['s3']['role'] = bucket_info['large_snapshot_role_arn']
        
        # Write temporary config
        with open(temp_config_file_path, 'w') as f:
            yaml.dump(config, f, default_flow_style=False)
        
        logger.info(f"Created temporary config file: {temp_config_file_path}")
        logger.info(f"Large snapshot URI: {bucket_info['large_snapshot_uri']}")
        logger.info("Large snapshot repository: migrations_jenkins_repo")
        logger.info(f"Large snapshot role ARN: {bucket_info['large_snapshot_role_arn']}")
        
    elif action == "delete":
        logger.info("Deleting temporary config file")
        try:
            os.remove(temp_config_file_path)
            logger.info(f"Deleted temporary config file: {temp_config_file_path}")
        except FileNotFoundError:
            logger.info(f"Temporary config file not found: {temp_config_file_path}")
    else:
        raise ValueError(f"Invalid action: {action}. Must be 'create' or 'delete'")


def display_final_results(large_snapshot_uri, final_count, ingested_doc_count,
                          index_shard_count, multiplication_factor):
    """Display comprehensive migration test results."""
    logger.info("=== MIGRATION TEST RESULTS ===")
    logger.info(f"Original Documents Ingested: {ingested_doc_count}")
    logger.info(f"Index Configuration: {index_shard_count} shards, 0 replicas")
    logger.info(f"Transformation Applied: {multiplication_factor}x multiplication")
    
    # Final results
    logger.info(f"Final Document Count: {final_count}")
    multiplication_success = final_count == (ingested_doc_count * multiplication_factor)
    logger.info(f"Multiplication Success: {multiplication_success}")
    
    # Snapshot information
    logger.info(f"Large Snapshot Location: {large_snapshot_uri}")
    logger.info("Large Snapshot Name: 'large-snapshot'")
    logger.info("Large Snapshot Repository: 'migration_assistant_repo'")
    
    # Summary
    logger.info("=== SUMMARY ===")
    logger.info(f"Successfully migrated {ingested_doc_count} → {final_count} documents")
    logger.info(f"Large snapshot available at: {large_snapshot_uri}")
    logger.info("Migration test completed successfully!")
