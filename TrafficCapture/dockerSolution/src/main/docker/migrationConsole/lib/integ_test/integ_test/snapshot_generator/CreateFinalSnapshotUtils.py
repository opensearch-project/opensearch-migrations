"""
Utilities specifically for CreateFinalSnapshot operations.
Handles S3 bucket management, snapshot creation, and catalog operations.
"""

import csv
import json
import logging
import os
import re
import tempfile
import yaml
from datetime import datetime
from console_link.cli import Context
from console_link.models.snapshot import Snapshot
from console_link.models.command_result import CommandResult
from integ_test.snapshot_generator.MultiplicationTestUtils import (
    read_migration_config,
    get_environment_values,
    run_console_command,
    DEFAULT_MIGRATION_CONFIG_PATH
)
from integ_test.snapshot_generator.MultiplicationTestDataIngestion import (
    get_version_info_from_config
)

logger = logging.getLogger(__name__)


def build_bucket_names_and_paths(env_values, cluster_version):
    """Build S3 bucket names and paths using configuration values."""

    config = read_migration_config(DEFAULT_MIGRATION_CONFIG_PATH)
    account_id = config['account_id']
    stage = env_values['stage']
    region = env_values['snapshot_region']
    
    # Default RFS bucket pattern: migration-artifacts-{account_id}-{stage}-{region}
    default_snapshot_bucket_uri = f"s3://migration-artifacts-{account_id}-{stage}-{region}/rfs-snapshot-repo/"
    
    # Final snapshot bucket pattern: migrations-jenkins-snapshot-{account_id}-{region}
    final_snapshot_bucket_name = f"{env_values['final_snapshot_bucket_prefix']}{account_id}-{region}"
    
    # Final snapshot folder path using cluster version from config
    final_snapshot_folder = f"{env_values['final_snapshot_folder_prefix']}{cluster_version}"
    
    # Final snapshot S3 URI
    final_snapshot_uri = f"s3://{final_snapshot_bucket_name}/{final_snapshot_folder}/"
    
    # Role ARN for large snapshot
    final_snapshot_role_arn = f"arn:aws:iam::{account_id}:role/largesnapshotfinal"
    
    return {
        'default_snapshot_bucket_uri': default_snapshot_bucket_uri,
        'final_snapshot_bucket_name': final_snapshot_bucket_name,
        'final_snapshot_folder': final_snapshot_folder,
        'final_snapshot_uri': final_snapshot_uri,
        'final_snapshot_role_arn': final_snapshot_role_arn,
    }


def check_and_prepare_s3_bucket(final_snapshot_uri, region):
    """Check and prepare S3 bucket directory for large snapshot storage (assumes bucket exists)."""
    logger.info("Checking and preparing S3 bucket directory")
    logger.info(f"Final Snapshot to be saved in directory: {final_snapshot_uri}")
    logger.info("Assuming S3 bucket already exists as per requirements")
    
    # Check if directory exists with content
    directory_exists = False
    try:
        result = run_console_command(["aws", "s3", "ls", final_snapshot_uri, "--region", region])
        if result.stdout.strip():  # Directory has content
            directory_exists = True
            logger.info(f"S3 directory {final_snapshot_uri} exists with content")
        else:
            logger.info("S3 directory path exists but is empty")
    except Exception:
        logger.info(f"S3 directory {final_snapshot_uri} does not exist (will be created automatically)")
    
    # Clear directory if it has content
    if directory_exists:
        logger.info("Clearing existing S3 directory contents")
        try:
            run_console_command(["aws", "s3", "rm", final_snapshot_uri, "--recursive", "--region", region])
            logger.info(f"Cleared S3 directory: {final_snapshot_uri}")
        except Exception as e:
            logger.warning(f"Failed to clear S3 directory (may already be empty): {e}")
    
    logger.info("S3 bucket directory preparation completed")


def modify_temp_config_file(action, config_file_path, temp_config_file_path, bucket_info=None, **kwargs):
    """Create or delete temporary config file for console commands."""
    if action == "create":
        logger.info("Creating temporary config file")
        
        if bucket_info is None:
            raise ValueError("bucket_info is required for create action")
        
        # Get environment values for region
        env_values = get_environment_values()
        
        # Read and modify config
        with open(config_file_path, 'r') as f:
            config = yaml.safe_load(f)
        
        # Update snapshot configuration with all required fields
        config['snapshot']['snapshot_name'] = 'large-snapshot'
        config['snapshot']['snapshot_repo_name'] = 'migrations_jenkins_repo'
        config['snapshot']['s3']['repo_uri'] = bucket_info['final_snapshot_uri']
        config['snapshot']['s3']['aws_region'] = env_values['snapshot_region']
        config['snapshot']['s3']['role'] = bucket_info['final_snapshot_role_arn']
        
        # Write temporary config
        with open(temp_config_file_path, 'w') as f:
            yaml.dump(config, f, default_flow_style=False)
        
        logger.info(f"Created temporary config file: {temp_config_file_path}")
        logger.info(f"Final snapshot URI: {bucket_info['final_snapshot_uri']}")
        logger.info("Final snapshot repository: migrations_jenkins_repo")
        logger.info(f"Final snapshot role ARN: {bucket_info['final_snapshot_role_arn']}")
        
    elif action == "delete":
        logger.info("Deleting temporary config file")
        try:
            os.remove(temp_config_file_path)
            logger.info(f"Deleted temporary config file: {temp_config_file_path}")
        except FileNotFoundError:
            logger.info(f"Temporary config file not found: {temp_config_file_path}")
    else:
        raise ValueError(f"Invalid action: {action}. Must be 'create' or 'delete'")


def take_large_snapshot_with_console(console_env, config_file_path, temp_config_file_path, bucket_info):
    """Take large snapshot using console commands with temp config"""
    
    # Get environment values for region
    env_values = get_environment_values()
    
    logger.info("Unregistering existing repository (if exists) using console_link")
    try:
        console_env.snapshot.delete_snapshot_repo()
        logger.info("Successfully unregistered existing repository")
    except Exception:
        logger.info("No existing repo to unregister, proceeding with snapshot creation")
    
    logger.info("Registering new snapshot repository with S3 bucket")
    repo_settings = {
        "type": "s3",
        "settings": {
            "bucket": bucket_info['final_snapshot_bucket_name'],
            "base_path": bucket_info['final_snapshot_folder'],
            "region": env_values['snapshot_region'],
            "role_arn": bucket_info['final_snapshot_role_arn']
        }
    }
    
    try:
        run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XPUT", "/_snapshot/migrations_jenkins_repo",
            "-H", "Content-Type: application/json",
            "-d", json.dumps(repo_settings)
        ])
        logger.info("Successfully registered new snapshot repository")
        logger.info("Repository: migrations_jenkins_repo")
        logger.info(f"S3 Bucket: {bucket_info['final_snapshot_bucket_name']}")
        logger.info(f"Base Path: {bucket_info['final_snapshot_folder']}")
        logger.info(f"Role ARN: {bucket_info['final_snapshot_role_arn']}")
    except Exception as e:
        logger.error(f"Failed to register snapshot repository: {e}")
        raise RuntimeError(f"Repository registration failed: {e}")
    
    logger.info("Verifying repository registration")
    try:
        result = run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XGET", "/_snapshot/migrations_jenkins_repo"
        ])
        logger.info("Repository registration verified successfully")
        logger.debug(f"Repository details: {result.stdout}")
    except Exception as e:
        logger.warning(f"Repository verification failed: {e}")
    
    logger.info("Creating large snapshot using console_link with temp config")
    try:
        temp_context = Context(temp_config_file_path).env
        temp_snapshot: Snapshot = temp_context.snapshot
        snapshot_result: CommandResult = temp_snapshot.create(wait=False)
        if not snapshot_result.success:
            logger.error(f"Large snapshot creation failed: {snapshot_result.value}")
            raise RuntimeError(f"Snapshot creation failed: {snapshot_result.value}")
        
        logger.info("Large snapshot creation initiated successfully")
    except Exception as e:
        logger.error(f"Failed to create snapshot: {e}")
        raise RuntimeError(f"Snapshot creation failed: {e}")
    
    logger.info("Verifying large snapshot completion")
    result = run_console_command([
        "console", "--config-file", temp_config_file_path,
        "snapshot", "status", "--deep-check"
    ])
    
    if "SUCCESS" in result.stdout and "Percent completed: 100.00%" in result.stdout:
        logger.info("Large snapshot completed successfully!")
        logger.info(f"Snapshot details:\n{result.stdout}")
    else:
        logger.warning(f"Large snapshot may not be complete: {result.stdout}")
    
    return bucket_info['final_snapshot_uri']


def cleanup_temp_csv_file(*file_paths):
    """Clean up temporary files with proper logging."""
    for file_path in file_paths:
        if file_path and os.path.exists(file_path):
            try:
                os.unlink(file_path)
                logger.debug(f"Cleaned up temp csv file: {file_path}")
            except Exception as e:
                logger.warning(f"Failed to clean up temp csv file {file_path}: {e}")


def update_snapshot_catalog(config_file_path, bucket_info, temp_config_file_path):
    """Update the snapshot catalog CSV with new snapshot information.

    Logic:
    - If a row for the base_path (cluster version) exists: UPDATE that row
    - If no row exists but S3 folder exists: CREATE new row (first snapshot of this type)
    """

    # Get environment values and version info
    env_values = get_environment_values()
    version_info = get_version_info_from_config(config_file_path)

    # Calculate snapshot metadata
    original_count = env_values['batch_count'] * env_values['docs_per_batch']
    final_count = original_count * env_values['multiplication_factor']

    # Catalog file details
    catalog_bucket = bucket_info['final_snapshot_bucket_name']
    catalog_key = "large-snapshot-catalog.csv"
    catalog_s3_uri = f"s3://{catalog_bucket}/{catalog_key}"
    base_path = bucket_info['final_snapshot_folder']  # e.g., "large-snapshot-es6x"
    index_name = env_values['index_name']

    logger.info(f"Updating snapshot catalog: {catalog_s3_uri}")
    logger.info(f"Target base_path: {base_path}")

    # Query additional snapshot metadata
    logger.info("Querying snapshot UUID...")
    try:
        result = run_console_command([
            "console", "--config-file", temp_config_file_path,
            "clusters", "curl", "source_cluster",
            "-XGET", "/_snapshot/migrations_jenkins_repo/large-snapshot?pretty"
        ])
        snapshot_data = json.loads(result.stdout)
        snapshot_uuid = snapshot_data['snapshots'][0]['uuid']
        logger.info(f"Snapshot UUID: {snapshot_uuid}")
    except Exception as e:
        logger.warning(f"Failed to get snapshot UUID: {e}")
        snapshot_uuid = "unknown"

    # Query index primary size
    logger.info("Querying index primary size...")
    try:
        result = run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XGET", f"/{index_name}/_stats/store?filter_path=indices.{index_name}.primaries.store.size_in_bytes"
        ])
        stats_data = json.loads(result.stdout)
        index_primary_size = stats_data['indices'][index_name]['primaries']['store']['size_in_bytes']
        logger.info(f"Index primary size: {index_primary_size} bytes")
    except Exception as e:
        logger.warning(f"Failed to get index primary size: {e}")
        index_primary_size = 0

    # Query index total size
    logger.info("Querying index total size...")
    try:
        result = run_console_command([
            "console", "clusters", "curl", "source_cluster",
            "-XGET", f"/{index_name}/_stats/store?filter_path=indices.{index_name}.total.store.size_in_bytes"
        ])
        stats_data = json.loads(result.stdout)
        index_total_size = stats_data['indices'][index_name]['total']['store']['size_in_bytes']
        logger.info(f"Index total size: {index_total_size} bytes")
    except Exception as e:
        logger.warning(f"Failed to get index total size: {e}")
        index_total_size = 0

    # Query S3 snapshot size
    logger.info("Querying S3 snapshot size...")
    try:
        s3_path = f"{catalog_bucket}/{base_path}/"
        result = run_console_command([
            "aws", "s3", "ls", f"s3://{s3_path}",
            "--recursive", "--summarize", "--region", env_values['snapshot_region']
        ])
        # Extract total size from output like "Total Size: 40804753"
        match = re.search(r'Total Size:\s+(\d+)', result.stdout)
        if match:
            snapshot_s3_size = int(match.group(1))
            logger.info(f"S3 snapshot size: {snapshot_s3_size} bytes")
        else:
            logger.warning("Could not parse S3 total size from output")
            snapshot_s3_size = 0
    except Exception as e:
        logger.warning(f"Failed to get S3 snapshot size: {e}")
        snapshot_s3_size = 0

    # Create new/updated catalog entry
    catalog_entry = {
        'timestamp': datetime.now().isoformat(),
        'snapshot_name': 'large-snapshot',
        'cluster_version': version_info['cluster_version'],
        'engine_version': version_info['engine_version'],
        'document_count': final_count,
        'original_count': original_count,
        'multiplication_factor': env_values['multiplication_factor'],
        'batch_count': env_values['batch_count'],
        'docs_per_batch': env_values['docs_per_batch'],
        'num_shards': env_values['num_shards'],
        'index_name': env_values['index_name'],
        's3_location': bucket_info['final_snapshot_uri'],
        'bucket_name': catalog_bucket,
        'base_path': base_path,
        'snapshot_uuid': snapshot_uuid,
        'index_primary_size_in_bytes': index_primary_size,
        'index_total_size_in_bytes': index_total_size,
        'snapshot_size_in_s3_dir': snapshot_s3_size
    }

    # Define CSV fieldnames (cluster_version first for better indexability)
    fieldnames = [
        'cluster_version', 'timestamp', 'snapshot_name', 'engine_version',
        'document_count', 'original_count', 'multiplication_factor',
        'batch_count', 'docs_per_batch', 'num_shards', 'index_name',
        's3_location', 'bucket_name', 'base_path', 'snapshot_uuid',
        'index_primary_size_in_bytes', 'index_total_size_in_bytes', 'snapshot_size_in_s3_dir'
    ]

    # Read existing catalog with improved error handling and debugging
    existing_entries = []
    temp_catalog_path = None
    temp_updated_path = None

    try:
        # Create temp file for catalog download
        with tempfile.NamedTemporaryFile(mode='w+', suffix='.csv', delete=False) as temp_file:
            temp_catalog_path = temp_file.name

        # First, check if catalog file exists in S3
        catalog_exists = False
        try:
            logger.info("Checking if catalog file exists in S3...")
            result = run_console_command([
                "aws", "s3", "ls", catalog_s3_uri,
                "--region", env_values['snapshot_region']
            ])
            if result.stdout.strip():  # File exists if ls returns content
                catalog_exists = True
                logger.info("Catalog file exists in S3")
            else:
                logger.info("No existing catalog file found in S3")
        except Exception as e:
            logger.info(f"Error checking catalog existence: {e}")
            logger.info("Assuming no existing catalog")

        # Try to download existing catalog only if it exists
        download_success = False
        if catalog_exists:
            try:
                logger.info("Attempting to download existing catalog using s3api get-object...")
                run_console_command([
                    "aws", "s3api", "get-object",
                    "--bucket", catalog_bucket,
                    "--key", catalog_key,
                    temp_catalog_path,
                    "--region", env_values['snapshot_region']
                ])
                
                # Verify the download actually got content
                with open(temp_catalog_path, 'r') as debug_file:
                    file_content = debug_file.read()
                    logger.info(f"Downloaded CSV file size: {len(file_content)} characters")
                    logger.info(f"First 300 characters: {repr(file_content[:300])}")

                    # Check if file is empty - this should not happen with s3api get-object
                    if len(file_content.strip()) == 0:
                        logger.warning("Downloaded CSV file is empty - this is unexpected with s3api get-object")
                        download_success = False
                    else:
                        download_success = True
                        logger.info("Catalog download completed successfully with content")
                        
            except Exception as e:
                logger.error(f"Failed to download existing catalog using s3api get-object: {e}")
                logger.info("Will proceed with empty catalog (may lose existing data)")
                download_success = False
        else:
            logger.info("No existing catalog to download - will create new one")

        if download_success:
            # Read existing entries with detailed logging
            try:
                with open(temp_catalog_path, 'r', newline='') as csvfile:
                    reader = csv.DictReader(csvfile)
                    logger.info(f"CSV headers found: {reader.fieldnames}")
                    logger.info(f"Expected headers: {fieldnames}")

                    existing_entries = list(reader)
                    logger.info(f"Successfully parsed {len(existing_entries)} existing catalog entries")

                    # Debug each entry
                    for i, entry in enumerate(existing_entries):
                        cluster_ver = entry.get('cluster_version', 'unknown')
                        base_path_val = entry.get('base_path', 'unknown')
                        logger.info(f"Entry {i}: cluster_version='{cluster_ver}', base_path='{base_path_val}'")

                    # Check for header compatibility
                    if reader.fieldnames and set(reader.fieldnames) != set(fieldnames):
                        logger.warning("CSV header mismatch detected")
                        logger.info(f"Missing fields: {set(fieldnames) - set(reader.fieldnames)}")
                        logger.info(f"Extra fields: {set(reader.fieldnames) - set(fieldnames)}")

                        # Migrate entries to new format
                        migrated_entries = []
                        for entry in existing_entries:
                            new_entry = {}
                            # Copy existing fields that match
                            for field in fieldnames:
                                if field in entry:
                                    new_entry[field] = entry[field]
                                else:
                                    # Set defaults for missing fields
                                    if field == 'snapshot_uuid':
                                        new_entry[field] = 'unknown'
                                    elif field in [
                                        "index_primary_size_in_bytes",
                                        "index_total_size_in_bytes",
                                        "snapshot_size_in_s3_dir"
                                    ]:
                                        new_entry[field] = 0
                                    else:
                                        new_entry[field] = ''
                            migrated_entries.append(new_entry)

                        existing_entries = migrated_entries
                        logger.info(f"Migrated {len(existing_entries)} entries to new format")

            except Exception as e:
                logger.error(f"Error parsing CSV file: {e}")
                existing_entries = []
                logger.info("Proceeding with empty catalog due to parsing error")

    except Exception as e:
        logger.error(f"Critical error in catalog handling: {e}")
        logger.info("Proceeding with empty catalog")

    # Find if entry for this base_path already exists
    entry_found = False
    updated_entries = []

    for entry in existing_entries:
        if entry.get('base_path') == base_path:
            # UPDATE existing entry for this cluster version
            logger.info(f"Found existing entry for {base_path}, updating...")
            updated_entries.append(catalog_entry)
            entry_found = True
        else:
            # Keep other entries unchanged
            updated_entries.append(entry)

    # If no entry found, ADD new entry (first snapshot of this cluster version)
    if not entry_found:
        logger.info(f"No existing entry for {base_path}, creating new entry...")
        updated_entries.append(catalog_entry)

    # Display final CSV row before uploading
    logger.info("=== FINAL CSV ROW ===")
    for key, value in catalog_entry.items():
        logger.info(f"{key}: {value}")

    # Create CSV row string
    csv_row_values = [str(catalog_entry[field]) for field in fieldnames]
    csv_row = ",".join(csv_row_values)
    logger.info(f"CSV Row: {csv_row}")
    logger.info("=== UPLOADING TO S3 ===")

    # Write updated catalog
    try:
        with tempfile.NamedTemporaryFile(mode='w', suffix='.csv', delete=False, newline='') as temp_file:
            temp_updated_path = temp_file.name

            writer = csv.DictWriter(temp_file, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(updated_entries)

        # Upload updated catalog to S3
        run_console_command([
            "aws", "s3", "cp", temp_updated_path, catalog_s3_uri,
            "--region", env_values['snapshot_region']
        ])

        action = "Updated" if entry_found else "Added"
        logger.info(f"Successfully {action.lower()} catalog entry for {base_path}")
        logger.info(f"Total catalog entries: {len(updated_entries)}")
        logger.info(f"{action} entry: {catalog_entry['timestamp']} - {catalog_entry['document_count']} documents")

    except Exception as e:
        logger.error(f"Failed to update catalog: {e}")
        raise RuntimeError(f"Catalog update failed: {e}")
    
    finally:
        # Clean up temp files with proper logging
        cleanup_temp_csv_file(temp_catalog_path, temp_updated_path)


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
