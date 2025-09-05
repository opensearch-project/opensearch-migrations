#!/usr/bin/env python3
"""
External Backfill Test

This pytest performs a complete 4-phase migration test:
1. Read Catalog File for Expected Metrics
2. Metadata Migration (using correct engine version from catalog)
3. Backfill with Document Count Monitoring
4. Performance Metrics Calculation

Usage:
    cd /root/lib/integ_test && pytest integ_test/external_backfill_test.py --unique_id test_run --stage dev

Environment Variables Required:
    SNAPSHOT_S3_URI - S3 URI of the snapshot to migrate from
    BACKFILL_SCALE - Number of RFS workers (default: 80)
    UNIQUE_ID - Test run identifier (default: test_run)
    STAGE - Deployment stage (default: dev)
"""

import os
import csv
import tempfile
import subprocess
import time
import logging
import yaml
import pytest
from datetime import datetime
from console_link.middleware.clusters import connection_check, clear_cluster, clear_indices, ConnectionResult
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from console_link.models.metadata import Metadata
from console_link.cli import Context

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Configuration constants
DEFAULT_COMMAND_TIMEOUT = 300
BACKFILL_INITIALIZATION_WAIT_SECONDS = 120
PROGRESS_CHECK_INTERVAL_SECONDS = 30
NO_PROGRESS_THRESHOLD_CHECKS = 10
MIGRATION_TIMEOUT_SECONDS = 7200
DEFAULT_BACKFILL_SCALE = 80


class Metric:
    def __init__(self, name, value, unit):
        self.name = name
        self.value = value
        self.unit = unit


def run_console_command(command_args, timeout=DEFAULT_COMMAND_TIMEOUT):
    """Execute console command and return result with error handling."""
    try:
        logger.info(f"Executing: {' '.join(command_args)}")
        result = subprocess.run(
            command_args,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=True
        )
        
        # Log stdout and stderr for debugging
        if result.stdout:
            logger.info(f"Stdout: {result.stdout}")
        if result.stderr:
            logger.warning(f"Stderr: {result.stderr}")
            
        logger.info("Command completed successfully")
        return result
    except subprocess.CalledProcessError as e:
        error_msg = f"Command failed: {' '.join(command_args)}\nStdout: {e.stdout}\nStderr: {e.stderr}"
        logger.error(error_msg)
        raise RuntimeError(error_msg)
    except subprocess.TimeoutExpired:
        error_msg = f"Command timed out after {timeout}s: {' '.join(command_args)}"
        logger.error(error_msg)
        raise TimeoutError(error_msg)


def extract_account_id_from_s3_uri(s3_uri):
    """Extract AWS account ID from S3 URI."""
    # Pattern: s3://migrations-jenkins-snapshot-{ACCOUNT_ID}-{REGION}/...
    bucket_name = s3_uri.split('/')[2]  # Get bucket name
    parts = bucket_name.split('-')
    # Find the part that looks like an account ID (12 digits)
    for part in parts:
        if part.isdigit() and len(part) == 12:
            return part
    return None


def generate_snapshot_role_arn(account_id, stage, region):
    """Generate snapshot role ARN using the standard pattern."""
    return f"arn:aws:iam::{account_id}:role/OSMigrations-{stage}-{region}-default-SnapshotRole"


def parse_environment():
    """Parse environment variables and derive catalog info."""
    logger.info("=== PARSING ENVIRONMENT VARIABLES ===")
    
    snapshot_s3_uri = os.environ.get('SNAPSHOT_S3_URI')
    if not snapshot_s3_uri:
        raise ValueError("SNAPSHOT_S3_URI environment variable is required")
    
    logger.info(f"Snapshot S3 URI: {snapshot_s3_uri}")
    
    # Parse: s3://migrations-jenkins-snapshot-****-us-west-2/large-snapshot-es5x/
    # Extract: bucket, account, region, cluster_version
    uri_without_protocol = snapshot_s3_uri.replace('s3://', '')
    parts = uri_without_protocol.rstrip('/').split('/')
    
    if len(parts) < 2:
        raise ValueError(f"Invalid SNAPSHOT_S3_URI format: {snapshot_s3_uri}")
    
    bucket = parts[0]  # migrations-jenkins-snapshot-****-us-west-2
    snapshot_dir = parts[1]  # large-snapshot-es5x
    
    # Extract cluster version from snapshot directory name
    if '-' in snapshot_dir:
        cluster_version = snapshot_dir.split('-')[-1]  # es5x
    else:
        cluster_version = snapshot_dir
    
    # Extract account ID from S3 URI
    account_id = extract_account_id_from_s3_uri(snapshot_s3_uri)
    if not account_id:
        raise ValueError(f"Could not extract account ID from S3 URI: {snapshot_s3_uri}")
    
    # Extract account and region from bucket name
    # Expected format: migrations-jenkins-snapshot-{account}-{region}
    bucket_parts = bucket.split('-')
    if len(bucket_parts) >= 6:
        region = '-'.join(bucket_parts[4:])  # us-west-2
    else:
        # Fallback: try to extract from environment or use defaults
        region = os.environ.get('AWS_DEFAULT_REGION', 'us-west-2')
    
    # Get stage from environment
    stage = os.environ.get('STAGE', 'dev')
    
    # Generate snapshot role ARN
    snapshot_role_arn = generate_snapshot_role_arn(account_id, stage, region)
    
    env_values = {
        'snapshot_s3_uri': snapshot_s3_uri,
        'snapshot_bucket': bucket,
        'snapshot_region': region,
        'cluster_version': cluster_version,
        'catalog_bucket': bucket,
        'catalog_key': 'large-snapshot-catalog.csv',
        'backfill_scale': int(os.environ.get('BACKFILL_SCALE', str(DEFAULT_BACKFILL_SCALE))),
        'unique_id': os.environ.get('UNIQUE_ID', 'test_run'),
        'stage': stage,
        'account_id': account_id,
        'snapshot_role_arn': snapshot_role_arn
    }
    
    logger.info("Environment values parsed:")
    for key, value in env_values.items():
        logger.info(f"  {key}: {value}")
    
    return env_values


def update_original_config_with_source_version(original_config_path: str, engine_version: str, env_values: dict):
    """
    Update the original config file with source_cluster_version if not present.
    Also updates source_cluster.version for RFS backfill command and fixes snapshot configuration.
    """
    logger.info(f"Loading original config from: {original_config_path}")
    
    # Load existing config
    with open(original_config_path, 'r') as f:
        config = yaml.safe_load(f)
    
    # Check if source_cluster_version is already set in metadata_migration
    metadata_migration = config.get('metadata_migration', {})
    current_metadata_version = metadata_migration.get('source_cluster_version')
    
    # Check if version is already set in source_cluster
    source_cluster = config.get('source_cluster', {})
    current_source_version = source_cluster.get('version')
    
    config_updated = False
    
    # Update metadata_migration.source_cluster_version and fix from_snapshot
    if current_metadata_version != engine_version:
        logger.info(f"Updating metadata_migration.source_cluster_version from "
                    f"'{current_metadata_version}' to '{engine_version}'")
        
        if 'metadata_migration' not in config:
            config['metadata_migration'] = {}
        
        config['metadata_migration']['source_cluster_version'] = engine_version
        config_updated = True
    else:
        logger.info(f"Config already has correct metadata_migration.source_cluster_version: {engine_version}")
    
    # Fix metadata_migration.from_snapshot to be empty instead of null
    if 'metadata_migration' in config:
        if config['metadata_migration'].get('from_snapshot') is None:
            # Remove the key entirely so it appears as empty in YAML
            if 'from_snapshot' in config['metadata_migration']:
                del config['metadata_migration']['from_snapshot']
            # Add it back as empty
            config['metadata_migration']['from_snapshot'] = None
            config_updated = True
    
    # Update source_cluster.version for RFS backfill with allow_insecure
    if current_source_version != engine_version:
        logger.info(f"Updating source_cluster.version from '{current_source_version}' to '{engine_version}'")
        
        config['source_cluster'] = {
            'endpoint': 'http://localhost:9200',
            'no_auth': {},
            'version': engine_version,
            'allow_insecure': True
        }
        config_updated = True
    else:
        logger.info(f"Config already has correct source_cluster.version: {engine_version}")
    
    # Update snapshot configuration with proper ordering and role
    if 'snapshot' in config:
        snapshot_config = config['snapshot']
        
        # Create properly ordered snapshot configuration
        ordered_snapshot = {
            'snapshot_name': snapshot_config.get('snapshot_name', 'large-snapshot'),
            'otel_endpoint': snapshot_config.get('otel_endpoint', 'http://localhost:4317'),
            'snapshot_repo_name': snapshot_config.get('snapshot_repo_name', 'migration_assistant_repo'),
            's3': {
                'repo_uri': env_values['snapshot_s3_uri'],
                'aws_region': env_values['snapshot_region'],
                'role': env_values['snapshot_role_arn']
            }
        }
        
        # Check if snapshot config needs updating
        if config['snapshot'] != ordered_snapshot:
            logger.info("Updating snapshot configuration with proper ordering and role")
            config['snapshot'] = ordered_snapshot
            config_updated = True
    
    # Write back to original file if any updates were made
    if config_updated:
        # Custom YAML dumper to handle empty values correctly
        class CustomDumper(yaml.SafeDumper):
            def represent_none(self, data):
                return self.represent_scalar('tag:yaml.org,2002:null', '')
        
        CustomDumper.add_representer(type(None), CustomDumper.represent_none)
        
        with open(original_config_path, 'w') as f:
            yaml.dump(config, f, default_flow_style=False, Dumper=CustomDumper)
        
        logger.info(f"Updated original config with engine version: {engine_version}")
    else:
        logger.info(f"No config updates needed - all versions already set to: {engine_version}")


def initialize_console_environment(engine_version: str, env_values: dict):
    """Initialize console environment with updated original config."""
    logger.info("=== INITIALIZING CONSOLE ENVIRONMENT ===")
    
    original_config_path = os.environ.get('CONFIG_FILE_PATH', '/config/migration_services.yaml')
    
    # Update original config instead of creating temporary file
    update_original_config_with_source_version(original_config_path, engine_version, env_values)
    
    # Initialize console environment with original config
    console_env = Context(original_config_path).env
    logger.info("Console environment initialized successfully")
    
    # Validate required components
    assert console_env.target_cluster is not None, "Target cluster not available"
    assert console_env.backfill is not None, "Backfill service not available"
    assert console_env.metadata is not None, "Metadata service not available"
    
    return console_env, None  # No temp config path to return


def ensure_cluster_is_clean(cluster: Cluster):
    """Clear all data from cluster with validation."""
    logger.info(f"Clearing cluster: {cluster.endpoint}")
    clear_cluster(cluster)
    clear_output = clear_indices(cluster)
    if isinstance(clear_output, str) and "Error" in clear_output:
        raise Exception(f"Cluster Clear Indices Failed: {clear_output}")
    logger.info("Cleaning up target cluster with console commands")
    try:
        run_console_command(["console", "clusters", "curl", "target_cluster", "-XDELETE", "/basic_index"])
        logger.info("Successfully deleted basic_index")
    except Exception as e:
        logger.info(f"basic_index deletion result (may not exist): {e}")
    
    try:
        run_console_command(["console", "clusters", "curl", "target_cluster", "-XDELETE", "/.migrations_working_state"])
        logger.info("Successfully deleted .migrations_working_state")
    except Exception as e:
        logger.info(f".migrations_working_state deletion result (may not exist): {e}")


def prepare_target_cluster(target_cluster: Cluster):
    """Prepare target cluster for migration."""
    logger.info("=== TARGET CLUSTER PREPARATION ===")
    
    # Confirm target connection
    target_con_result: ConnectionResult = connection_check(target_cluster)
    assert target_con_result.connection_established is True, f"Target cluster connection failed: {target_con_result}"
    logger.info(f"Target cluster connection verified: {target_cluster.endpoint}")
    
    # Clear any remaining data using existing method
    ensure_cluster_is_clean(target_cluster)
    logger.info("Target cluster preparation completed")


def perform_metadata_migration(console_env):
    """Perform metadata migration from external snapshot."""
    logger.info("=== STEP 2: METADATA MIGRATION ===")
    logger.info("Starting metadata migration from external snapshot")
    
    metadata: Metadata = console_env.metadata
    metadata_result: CommandResult = metadata.migrate()
    
    if not metadata_result.success:
        logger.error("=== METADATA MIGRATION FAILED ===")
        logger.error(f"Full error details: {metadata_result.value}")
        raise RuntimeError(f"Metadata migration failed: {metadata_result.value}")
    else:
        logger.info("Metadata migration completed successfully")
        logger.info(f"Metadata migration result: {metadata_result.value}")


def fetch_snapshot_catalog_info(env_values):
    """Download and parse snapshot catalog CSV file."""
    logger.info("=== STEP 1: READING SNAPSHOT CATALOG ===")
    
    # Create temporary file for catalog
    with tempfile.NamedTemporaryFile(mode='w+', suffix='.csv', delete=False) as temp_file:
        temp_catalog_path = temp_file.name
    
    try:
        # Download catalog file from S3
        logger.info(f"Downloading catalog from s3://{env_values['catalog_bucket']}/{env_values['catalog_key']}")
        run_console_command([
            "aws", "s3api", "get-object",
            "--bucket", env_values['catalog_bucket'],
            "--key", env_values['catalog_key'],
            temp_catalog_path,
            "--region", env_values['snapshot_region']
        ])
        
        # Parse CSV file
        logger.info(f"Parsing catalog file for cluster version: {env_values['cluster_version']}")
        with open(temp_catalog_path, 'r') as csvfile:
            reader = csv.DictReader(csvfile)
            for row in reader:
                if row['cluster_version'] == env_values['cluster_version']:
                    snapshot_info = {
                        'document_count': int(row['document_count']),
                        'index_total_size_in_bytes': int(row['index_total_size_in_bytes']),
                        'snapshot_size_in_s3_dir': int(row['snapshot_size_in_s3_dir']),
                        'engine_version': row['engine_version']
                    }
                    logger.info(f"Found catalog entry for {env_values['cluster_version']}:")
                    logger.info(f"  Engine version: {snapshot_info['engine_version']}")
                    logger.info(f"  Expected documents: {snapshot_info['document_count']:,}")
                    logger.info(f"  Index size: {snapshot_info['index_total_size_in_bytes']:,} bytes")
                    logger.info(f"  S3 size: {snapshot_info['snapshot_size_in_s3_dir']:,} bytes")
                    return snapshot_info
        
        raise ValueError(f"No catalog entry found for cluster version: {env_values['cluster_version']}")
        
    finally:
        # Clean up temporary file
        if os.path.exists(temp_catalog_path):
            os.unlink(temp_catalog_path)


def get_document_count(target_cluster: Cluster):
    """Get total document count from all indices in target cluster using direct API call."""
    try:
        # Use direct API call to get document count for basic_index
        response = target_cluster.call_api("/basic_index/_count", raise_error=False)
        if response.status_code == 200:
            response_data = response.json()
            return response_data.get('count', 0)
        else:
            logger.warning(f"API call failed with status {response.status_code}: {response.text}")
            return 0
        
    except Exception as e:
        logger.warning(f"Error getting total document count: {e}")
        return 0


def perform_backfill_with_monitoring(console_env, snapshot_info, env_values):
    """Perform backfill with document count monitoring."""
    logger.info("=== STEP 3: BACKFILL WITH DOCUMENT COUNT MONITORING ===")
    
    # Stop any existing backfill processes
    logger.info("Stopping any existing backfill processes")
    try:
        run_console_command(["console", "backfill", "stop"])
        logger.info("Successfully stopped any existing backfill processes")
    except Exception as e:
        logger.warning(f"No existing backfill to stop or stop failed: {e}")
    
    # Clear working state to prevent lock contention
    logger.info("Clearing any stale working state")
    try:
        console_env.target_cluster.call_api("/.migrations_working_state", method="DELETE")
        logger.info("Successfully cleared working state index")
    except Exception as e:
        error_str = str(e).lower()
        if "404" in error_str or "not_found" in error_str or "index_not_found" in error_str:
            logger.info("Working state index didn't exist - that's fine")
        else:
            logger.warning(f"Error clearing working state: {e}")
    
    backfill: Backfill = console_env.backfill
    expected_docs = snapshot_info['document_count']
    backfill_scale = env_values['backfill_scale']
    
    # Start backfill
    logger.info("Starting backfill operation")
    backfill_start_result: CommandResult = backfill.start()
    if not backfill_start_result.success:
        raise RuntimeError(f"Backfill start failed: {backfill_start_result.value}")
    
    # Scale to specified workers
    logger.info(f"Scaling backfill to {backfill_scale} workers")
    backfill_scale_result: CommandResult = backfill.scale(units=backfill_scale)
    if not backfill_scale_result.success:
        raise RuntimeError(f"Backfill scaling failed: {backfill_scale_result.value}")
    
    # Wait for initialization
    logger.info(f"Waiting for backfill initialization ({BACKFILL_INITIALIZATION_WAIT_SECONDS} seconds)")
    time.sleep(BACKFILL_INITIALIZATION_WAIT_SECONDS)
    
    # Monitor document count until completion
    logger.info(f"Monitoring document migration progress (target: {expected_docs:,} documents)")
    start_time = time.time()
    check_count = 0
    last_doc_count = 0
    no_progress_count = 0
    
    while True:
        check_count += 1
        current_docs = get_document_count(console_env.target_cluster)
        elapsed_time = time.time() - start_time
        
        # Calculate progress percentage
        progress_pct = (current_docs / expected_docs * 100) if expected_docs > 0 else 0
        
        logger.info(f"Progress check #{check_count}: {current_docs:,}/{expected_docs:,} documents "
                    f"({progress_pct:.1f}%) - {elapsed_time / 60:.1f} minutes elapsed")
        
        # Check for completion
        if current_docs >= expected_docs:
            logger.info("Document migration completed successfully!")
            break
        
        # Check for progress (no documents added in last checks)
        if current_docs == last_doc_count:
            no_progress_count += 1
            if no_progress_count >= NO_PROGRESS_THRESHOLD_CHECKS:
                progress_timeout_seconds = no_progress_count * PROGRESS_CHECK_INTERVAL_SECONDS
                logger.warning(f"No progress detected for {NO_PROGRESS_THRESHOLD_CHECKS} checks "
                               f"({progress_timeout_seconds} seconds)")
                logger.warning("This may indicate the migration is stuck or complete")
                
                # If we have some documents but not all, this might be expected
                if current_docs > 0:
                    logger.info(f"Partial migration detected: {current_docs:,} documents migrated")
                    logger.info("Proceeding with current state...")
                    break
        else:
            no_progress_count = 0
            last_doc_count = current_docs
        
        # Safety timeout
        if elapsed_time > MIGRATION_TIMEOUT_SECONDS:
            timeout_hours = MIGRATION_TIMEOUT_SECONDS / 3600
            logger.warning(f"Migration timeout reached ({timeout_hours} hours)")
            logger.info(f"Final document count: {current_docs:,}/{expected_docs:,}")
            break
        
        # Wait before next check
        time.sleep(PROGRESS_CHECK_INTERVAL_SECONDS)
    
    # Stop backfill
    logger.info("Stopping backfill")
    
    # Step 3a: Scale down ECS workers
    backfill_stop_result: CommandResult = backfill.stop()
    if not backfill_stop_result.success:
        logger.error(f"Failed to stop backfill: {backfill_stop_result.value}")
        return current_docs
    logger.info("Successfully scaled down backfill workers")
    
    # Step 3b: Completely stop migration and clean up state
    logger.info("Completely stopping migration and cleaning up working state")
    try:
        run_console_command(["console", "backfill", "stop"])
        logger.info("Successfully stopped backfill migration completely")
    except Exception as e:
        logger.warning(f"Failed to completely stop backfill: {e}")
    
    return current_docs


def get_total_cluster_size(cluster: Cluster) -> float:
    """Get total cluster size in TiB."""
    try:
        response = cluster.call_api("/_stats/store?level=cluster", raise_error=False)
        data = response.json()
        primary_size_bytes = data['_all']['primaries']['store']['size_in_bytes']

        # Convert bytes to tebibytes (TiB)
        primary_size_tib = float(primary_size_bytes) / (1024**4)

        logger.debug(f"Cluster primary store size: {primary_size_bytes} bytes = {primary_size_tib:.6f} TiB")
        return primary_size_tib
        
    except Exception as e:
        logger.warning(f"Error getting cluster size: {e}")
        return 0.0


def generate_csv_data(start_timestamp: datetime, final_size_tib: float, actual_docs: int,
                      expected_docs: int, backfill_scale: int):
    """Generate CSV data for metrics."""
    # Current time as the end timestamp
    end_timestamp = datetime.now()
    
    # Calculate elapsed duration in seconds
    duration_seconds = (end_timestamp - start_timestamp).total_seconds()
    duration_minutes = duration_seconds / 60.0
    
    # Convert data sizes:
    # 1 TiB = 1024 GiB; 1 GiB = 1024 MiB
    size_in_mib = final_size_tib * 1024 * 1024
    size_in_gb = final_size_tib * 1024

    # Calculate throughput (MiB/s). Avoid division by zero
    throughput_mib_s = size_in_mib / duration_seconds if duration_seconds > 0 else 0
    throughput_mib_s_per_worker = throughput_mib_s / backfill_scale if backfill_scale > 0 else 0
    
    # Calculate document throughput
    doc_throughput_per_sec = actual_docs / duration_seconds if duration_seconds > 0 else 0
    doc_throughput_per_worker = doc_throughput_per_sec / backfill_scale if backfill_scale > 0 else 0
    
    # Define the metrics
    metrics = [
        Metric("End Timestamp", end_timestamp.isoformat(), "ISO-8601"),
        Metric("Duration", round(duration_minutes, 2), "min"),
        Metric("Size Transferred", round(size_in_gb, 2), "GB"),
        Metric("Documents Migrated", actual_docs, "count"),
        Metric("Expected Documents", expected_docs, "count"),
        Metric("Migration Completeness",
               round((actual_docs / expected_docs * 100) if expected_docs > 0 else 0, 1), "%"),
        Metric("Reindexing Throughput Total", round(throughput_mib_s, 4), "MiB/s"),
        Metric("Reindexing Throughput Per Worker", round(throughput_mib_s_per_worker, 4), "MiB/s"),
        Metric("Document Throughput Total", round(doc_throughput_per_sec, 2), "docs/s"),
        Metric("Document Throughput Per Worker", round(doc_throughput_per_worker, 4), "docs/s"),
        Metric("Backfill Workers", backfill_scale, "count")
    ]

    # Prepare the CSV header and row
    header = [f"{m.name} ({m.unit})" for m in metrics]
    row = [m.value for m in metrics]
    return [header, row]


def write_csv(filename, data):
    """Write CSV data to file."""
    with open(filename, 'w', newline='') as csvfile:
        writer = csv.writer(csvfile)
        writer.writerows(data)


def calculate_and_save_metrics(snapshot_info, env_values, console_env, start_timestamp, actual_docs):
    """Calculate and save performance metrics."""
    logger.info("=== STEP 4: PERFORMANCE METRICS CALCULATION ===")
    
    # Get final cluster size
    final_size = get_total_cluster_size(console_env.target_cluster)
    
    # Generate metrics data
    data = generate_csv_data(
        start_timestamp,
        final_size,
        actual_docs,
        snapshot_info['document_count'],
        env_values['backfill_scale']
    )
    
    try:
        # Ensure the reports directory exists
        reports_dir = os.path.join(os.path.dirname(__file__), "reports", env_values['unique_id'])
        os.makedirs(reports_dir, exist_ok=True)
        
        # Write metrics directly to CSV in the format needed by Jenkins Plot plugin
        metrics_file = os.path.join(reports_dir, "backfill_metrics.csv")
        logger.info(f"Writing metrics to: {metrics_file}")

        write_csv(metrics_file, data)
        logger.info(f"Successfully wrote metrics to: {metrics_file}")
        
        # Log metrics summary
        logger.info("=== METRICS SUMMARY ===")
        if len(data) >= 2:
            headers = data[0]
            values = data[1]
            for header, value in zip(headers, values):
                logger.info(f"{header}: {value}")
                
    except Exception as e:
        logger.error(f"Error writing metrics file: {str(e)}")
        raise


def cleanup_resources(console_env, temp_config_path):
    """Clean up resources after test completion."""
    logger.info("=== CLEANUP PHASE ===")
    
    try:
        # Clear target cluster
        if console_env and console_env.target_cluster:
            try:
                logger.info("Clearing target cluster")
                ensure_cluster_is_clean(console_env.target_cluster)
                logger.info("Successfully cleared target cluster")
            except Exception as e:
                logger.warning(f"Error clearing cluster after tests: {e}")
        
        # Clean up temporary config file
        if temp_config_path and os.path.exists(temp_config_path):
            os.unlink(temp_config_path)
            logger.info("Cleaned up temporary config file")
            
    except Exception as e:
        logger.warning(f"Cleanup encountered error: {e}")


# Pytest fixtures and test class
@pytest.fixture(scope="session", autouse=True)
def migration_test_setup():
    """Session-scoped fixture for test setup and teardown."""
    logger.info("=== EXTERNAL BACKFILL TEST STARTING ===")
    
    console_env = None
    temp_config_path = None
    start_timestamp = datetime.now()
    
    try:
        env_values = parse_environment()
        logger.info("=== DEBUG: CURRENT CONFIG FILE ===")
        try:
            run_console_command(["cat", "/config/migration_services.yaml"])
        except Exception as e:
            logger.warning(f"Could not read config file: {e}")
        
        # Read Catalog File for Expected Metrics (MOVED TO FIRST)
        snapshot_info = fetch_snapshot_catalog_info(env_values)
        
        # Initialize console environment with correct engine version from catalog
        console_env, temp_config_path = initialize_console_environment(snapshot_info['engine_version'], env_values)
        
        # Prepare target cluster
        prepare_target_cluster(console_env.target_cluster)
        
        logger.info(f"Test started at: {start_timestamp}")

        # Debug: Print current config file again
        logger.info("=== DEBUG: UPDATED CONFIG FILE ===")
        try:
            run_console_command(["cat", "/config/migration_services.yaml"])
        except Exception as e:
            logger.warning(f"Could not read config file: {e}")
        
        # Store test data for the test method
        test_data = {
            'console_env': console_env,
            'temp_config_path': temp_config_path,
            'start_timestamp': start_timestamp,
            'env_values': env_values,
            'snapshot_info': snapshot_info
        }
        
        yield test_data
        
    except Exception as e:
        logger.error(f"Test setup failed: {e}")
        raise
        
    finally:
        # Always cleanup resources
        cleanup_resources(console_env, temp_config_path)


class TestExternalBackfill:
    """Test class for external backfill migration."""
    
    def test_external_backfill_migration(self, migration_test_setup):
        """Test the complete external backfill migration process."""
        logger.info("=== STARTING EXTERNAL BACKFILL MIGRATION TEST ===")
        
        # Get test data from fixture
        test_data = migration_test_setup
        console_env = test_data['console_env']
        start_timestamp = test_data['start_timestamp']
        env_values = test_data['env_values']
        snapshot_info = test_data['snapshot_info']
        
        # Metadata Migration
        perform_metadata_migration(console_env)
        
        # Step 3: Backfill with Document Count Monitoring
        actual_docs = perform_backfill_with_monitoring(console_env, snapshot_info, env_values)
        
        # Refresh target cluster
        logger.info("Refreshing target cluster")
        console_env.target_cluster.call_api("/_refresh")
        logger.info("Target cluster refreshed")
        
        # Step 4: Performance Metrics Calculation
        calculate_and_save_metrics(snapshot_info, env_values, console_env, start_timestamp, actual_docs)
        
        # Assertions for test validation
        expected_docs = snapshot_info['document_count']
        migration_completeness = (actual_docs / expected_docs * 100) if expected_docs > 0 else 0
        
        # Assert that migration was successful
        assert actual_docs > 0, f"No documents were migrated. Expected: {expected_docs:,}"
        assert migration_completeness >= 95.0, f"Migration completeness too low: {migration_completeness:.1f}%. Expected >= 95%"
        
        logger.info("External backfill test completed successfully")
        logger.info(f"Final results: {actual_docs:,}/{expected_docs:,} documents ({migration_completeness:.1f}% complete)")


# For backward compatibility with standalone execution
def main():
    """Main execution function for standalone backfill test."""
    logger.info("=== EXTERNAL BACKFILL TEST STARTING (STANDALONE MODE) ===")
    
    console_env = None
    temp_config_path = None
    start_timestamp = datetime.now()
    
    try:
        env_values = parse_environment()
        logger.info("=== DEBUG: CURRENT CONFIG FILE ===")
        try:
            run_console_command(["cat", "/config/migration_services.yaml"])
        except Exception as e:
            logger.warning(f"Could not read config file: {e}")
        
        # Read Catalog File for Expected Metrics (MOVED TO FIRST)
        snapshot_info = fetch_snapshot_catalog_info(env_values)
        
        # Initialize console environment with correct engine version from catalog
        console_env, temp_config_path = initialize_console_environment(snapshot_info['engine_version'], env_values)
        
        # Prepare target cluster
        prepare_target_cluster(console_env.target_cluster)
        
        logger.info(f"Test started at: {start_timestamp}")

        # Debug: Print current config file again
        logger.info("=== DEBUG: UPDATED CONFIG FILE ===")
        try:
            run_console_command(["cat", "/config/migration_services.yaml"])
        except Exception as e:
            logger.warning(f"Could not read config file: {e}")
        
        # Metadata Migration
        perform_metadata_migration(console_env)
        
        # Step 3: Backfill with Document Count Monitoring
        actual_docs = perform_backfill_with_monitoring(console_env, snapshot_info, env_values)
        
        # Refresh target cluster
        logger.info("Refreshing target cluster")
        console_env.target_cluster.call_api("/_refresh")
        logger.info("Target cluster refreshed")
        
        # Step 4: Performance Metrics Calculation
        calculate_and_save_metrics(snapshot_info, env_values, console_env, start_timestamp, actual_docs)
        
        logger.info("External backfill test completed successfully")
        return 0
        
    except Exception as e:
        logger.error(f"Test failed: {e}")
        return 1
        
    finally:
        # Always cleanup resources
        cleanup_resources(console_env, temp_config_path)


if __name__ == "__main__":
    exit(main())
