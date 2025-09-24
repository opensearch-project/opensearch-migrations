"""
Utility functions for multiplication test suite.
"""

import json
import logging
import os
import shutil
import subprocess
import textwrap
import time
import yaml
from integ_test.default_operations import DefaultOperationsLibrary as ops
from console_link.middleware.clusters import clear_indices
from console_link.models.command_result import CommandResult

logger = logging.getLogger(__name__)

DEFAULT_COMMAND_TIMEOUT = 300
SNAPSHOT_POLL_INTERVAL = 3
BACKFILL_POLL_INTERVAL = 5
STABILITY_CHECK_INTERVAL = 15
STABILITY_CHECK_COUNT = 4
STUCK_COUNT_TIMEOUT_MINUTES = 10
FINAL_SNAPSHOT_TEST_ID = "large_snapshot_test"
DEFAULT_MIGRATION_CONFIG_PATH = "/config/migration_services.yaml"
FINAL_SNAPSHOT_CONFIG_PATH = "/config/migration_large_snapshot.yaml"
TRANSFORMATION_CONFIG_DIRECTORY = "/shared-logs-output/test-transformations"


def read_migration_config(config_path=None):
    """Extract all migration configuration from services YAML file."""
    if config_path is None:
        config_path = DEFAULT_MIGRATION_CONFIG_PATH
    
    try:
        with open(config_path, 'r') as f:
            config = yaml.safe_load(f)
        
        snapshot_config = config.get('snapshot', {})
        s3_config = snapshot_config.get('s3', {})
        
        # Extract role ARN from snapshot.s3.role
        role_arn = s3_config.get('role', '')
        
        if not role_arn:
            raise ValueError("No role ARN found in snapshot.s3.role")
        
        # Parse account ID from ARN format: arn:aws:iam::ACCOUNT_ID:role/ROLE_NAME
        account_id = None
        if ':' in role_arn:
            arn_parts = role_arn.split(':')
            if len(arn_parts) >= 5:
                account_id = arn_parts[4]
                if not (account_id and account_id.isdigit()):
                    account_id = None
        
        if not account_id:
            raise ValueError(f"Could not extract account ID from role ARN: {role_arn}")
        
        # Extract engine version from source_cluster.version
        engine_version = config.get('source_cluster', {}).get('version', '')
        
        if not engine_version:
            raise ValueError("No engine version found in source_cluster.version")
        
        logger.debug(f"Extracted account ID: {account_id}")
        logger.debug(f"Extracted engine version: {engine_version}")
        
        return {
            # Snapshot configuration
            'snapshot_repo_name': snapshot_config.get('snapshot_repo_name', 'migration_assistant_repo'),
            'repo_uri': s3_config.get('repo_uri', ''),
            'role_arn': role_arn,
            'aws_region': s3_config.get('aws_region', 'us-west-2'),
            
            # Account and version information
            'account_id': account_id,
            'engine_version': engine_version
        }
    except FileNotFoundError:
        raise ValueError(f"Config file not found: {config_path}")
    except yaml.YAMLError as e:
        raise ValueError(f"Error parsing YAML config file: {e}")
    except Exception as e:
        raise ValueError(f"Error extracting config info: {e}")


def get_environment_values():
    """Get values from environment variables with defaults."""
    return {
        'stage': os.getenv('STAGE', 'dev'),
        'final_snapshot_bucket_prefix': os.getenv('FINAL_SNAPSHOT_BUCKET_PREFIX', 'migrations-jenkins-snapshot-'),
        'final_snapshot_folder_prefix': os.getenv('FINAL_SNAPSHOT_FOLDER_PREFIX', 'large-snapshot-'),
        'snapshot_region': os.getenv('SNAPSHOT_REGION', 'us-west-2'),
        'multiplication_factor': int(os.getenv('MULTIPLICATION_FACTOR', '10')),
        'batch_count': int(os.getenv('BATCH_COUNT', '1')),
        'docs_per_batch': int(os.getenv('DOCS_PER_BATCH', '50')),
        'num_shards': int(os.getenv('NUM_SHARDS', '10')),
        'index_name': os.getenv('INDEX_NAME', 'basic_index'),
    }


def get_transformation_file_path():
    """Get the transformation file path."""
    return f"{TRANSFORMATION_CONFIG_DIRECTORY}/transformation.json"


# ==== SHARED UTILITIES (Used by multiple files) ====

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


def wait_for_backfill_completion_on_index(index_name, expected_count, backfill=None):
    """
    Poll specific index document count until expected count is reached.
    Includes stuck count detection and automatic backfill stopping.

    Returns:
        bool: True if completed successfully, False if failed with warnings
    """
    
    start_time = time.time()
    last_count = 0
    last_count_change_time = start_time
    stuck_timeout_seconds = STUCK_COUNT_TIMEOUT_MINUTES * 60
    
    logger.info("Starting backfill monitoring: %s (target: %s documents, no timeout)",
                index_name, expected_count)
    
    # Initialize progress tracker
    progress_tracker = ProgressTracker(expected_count)
    
    # Wait for expected document count (no timeout)
    logger.info(f"Migration Progress: 0/{expected_count} documents (0.0%) | Starting backfill monitoring...")
    
    while True:  # No timeout - wait indefinitely
        actual_count = get_target_document_count_for_index(index_name)
        current_time = time.time()
        
        # Check if count changed
        if actual_count != last_count:
            last_count = actual_count
            last_count_change_time = current_time
        # Check for stuck count (only if migration has started and count > 0)
        elif actual_count > 0 and (current_time - last_count_change_time) > stuck_timeout_seconds:
            logger.warning(f"Document count stuck at {actual_count} for more than "
                           f"{STUCK_COUNT_TIMEOUT_MINUTES} minutes")
            logger.warning("Backfill appears to be stuck - attempting to stop backfill")
            
            # Try to stop backfill
            if backfill:
                try:
                    stop_result: CommandResult = backfill.stop()
                    if stop_result.success:
                        logger.warning("Successfully stopped backfill")
                    else:
                        logger.warning(f"Failed to stop backfill: {stop_result.value}")
                except Exception as e:
                    logger.warning(f"Exception while stopping backfill: {e}")
            else:
                logger.warning("No backfill object provided - cannot stop backfill automatically")
            
            logger.warning("Backfill failed, please check the logs")
            return False  # Indicate failure
        
        # Update progress and get metrics for logs
        metrics = progress_tracker.update(actual_count)
        if metrics['current_count'] == 0:
            logger.info(
                f"Migration Progress: {metrics['current_count']}/{metrics['expected_count']} "
                f"documents (0.0%) | "
                f"Elapsed: {ProgressTracker.format_duration(metrics['elapsed_total'])} | "
                f"Waiting for migration to start..."
            )
        elif metrics['is_static_period']:
            logger.info(f"Migration Progress: {metrics['current_count']}/{metrics['expected_count']} "
                        f"documents ({metrics['progress_pct']:.1f}%) | "
                        f"Rate: NA | Elapsed: {ProgressTracker.format_duration(metrics['elapsed_total'])} | "
                        f"ETA: NA | Waiting for RFS workers to start...")
        elif metrics['migration_started'] and metrics['avg_rate'] > 0:
            eta_str = (ProgressTracker.format_duration(metrics['eta_seconds'])
                       if metrics['eta_seconds'] > 0 else "calculating...")
            elapsed_str = ProgressTracker.format_duration(metrics['elapsed_total'])
            progress_str = f"{metrics['current_count']}/{metrics['expected_count']}"
            rate_str = f"{metrics['avg_rate']:.1f} docs/sec"
            pct_str = f"({metrics['progress_pct']:.1f}%)"
            logger.info(f"Migration Progress: {progress_str} documents {pct_str} | "
                        f"Rate: {rate_str} | Elapsed: {elapsed_str} | ETA: {eta_str}")
        else:
            logger.info(f"Migration Progress: {metrics['current_count']}/{metrics['expected_count']} "
                        f"documents ({metrics['progress_pct']:.1f}%) | "
                        f"Rate: calculating... | "
                        f"Elapsed: {ProgressTracker.format_duration(metrics['elapsed_total'])} | "
                        f"ETA: calculating...")
        
        # Check completion
        if actual_count == expected_count:
            total_time = time.time() - start_time
            final_rate = expected_count / total_time if total_time > 0 else 0
            logger.info(f"Target reached: {actual_count}/{expected_count} documents (100.0%) | "
                        f"Total time: {ProgressTracker.format_duration(total_time)} | "
                        f"Avg rate: {final_rate:.1f} docs/sec")
            break
        elif actual_count > expected_count:
            raise RuntimeError(f"Document count exceeded expected: {actual_count} > {expected_count}")
        
        time.sleep(BACKFILL_POLL_INTERVAL)
    
    # Verify stability with enhanced logging
    stability_duration = STABILITY_CHECK_COUNT * STABILITY_CHECK_INTERVAL
    logger.info(f"Starting stability verification ({STABILITY_CHECK_COUNT} checks over "
                f"{ProgressTracker.format_duration(stability_duration)})...")
    
    for i in range(STABILITY_CHECK_COUNT):
        time.sleep(STABILITY_CHECK_INTERVAL)
        actual_count = get_target_document_count_for_index(index_name)
        
        if actual_count != expected_count:
            raise RuntimeError(
                f"Document count became unstable: {actual_count} != {expected_count} "
                f"(check {i + 1}/{STABILITY_CHECK_COUNT})"
            )
        
        logger.info(f"Stability Check {i + 1}/{STABILITY_CHECK_COUNT}: "
                    f"Count = {actual_count} (STABLE)")
    
    logger.info(f"Document count verified as stable: {expected_count} documents")
    return True


# ==== CLEANUP AND PREPARE UTILITIES ====

def cleanup_snapshots_and_repo(source_cluster, stage, region):
    """Multiple cleanup steps on cluster and Jenkins S3 bucket."""

    config = read_migration_config(DEFAULT_MIGRATION_CONFIG_PATH)
    account_id = config['account_id']
    default_snapshot_repo_name = config['snapshot_repo_name']
    default_s3_bucket_uri = f"s3://migration-artifacts-{account_id}-{stage}-{region}/rfs-snapshot-repo/"
    
    cleanup_commands = [
        ["console", "snapshot", "delete", "--acknowledge-risk"],  # Delete snapshot
        ["console", "clusters", "curl", "source_cluster", "-XDELETE",
         f"/_snapshot/{default_snapshot_repo_name}"],  # Delete repo
        ["aws", "s3", "rm", default_s3_bucket_uri, "--recursive"]  # Clear S3 contents
    ]

    logger.info("Delete existing snapshot and repository and clean up Jenkins S3 bucket")
    for cmd in cleanup_commands:
        try:
            run_console_command(cmd)
            logger.info(f"Cleanup succeeded: {' '.join(cmd)}")
        except Exception:
            logger.warning(f"Cleanup failed (expected if resource doesn't exist): {' '.join(cmd)}")

    logger.info("Clear cluster")
    try:
        # clear_cluster doesn't raise exceptions, so we need to check the result
        result = clear_indices(source_cluster)
        if isinstance(result, str) and ("Error" in result or "403" in result or "security_exception" in result):
            logger.warning(f"Failed to clear cluster (may be due to permissions): {result}")
            logger.info("Continuing with script execution...")
        else:
            logger.info("Successfully cleared cluster")
    except Exception as e:
        logger.warning(f"Failed to clear cluster (may be due to permissions): {e}")
        logger.info("Continuing with script execution...")


def create_transformation_config(multiplication_factor: int):
    """Create transformation file with multiplication configuration."""

    # Remove existing transformation directory
    try:
        shutil.rmtree(TRANSFORMATION_CONFIG_DIRECTORY)
        logger.info("Removed existing transformation directory")
    except FileNotFoundError:
        logger.info("No existing transformation files to cleanup")

    # One single f-string; use {{ }} to emit literal braces in JS.
    initialization_script = textwrap.dedent(f"""
        const MULTIPLICATION_FACTOR_WITH_ORIGINAL = {multiplication_factor};

        function jget(obj, key) {{
          return obj && typeof obj.get === 'function' ? obj.get(key) : (obj ? obj[key] : undefined);
        }}
        function jset(obj, key, val) {{
          if (obj && typeof obj.set === 'function') {{ obj.set(key, val); return obj; }}
          if (obj) obj[key] = val;
          return obj;
        }}
        function jcloneMapLike(src) {{
          if (src && typeof src.forEach === 'function') {{
            const m = new Map(); src.forEach((v,k) => m.set(k, v)); return m;
          }}
          return Object.assign({{}}, src || {{}});
        }}
        function pickAction(doc) {{
          for (const k of ['index','create','update','delete']) {{
            const v = jget(doc, k); if (v !== undefined) return [k, v];
          }}
          return [null, null];
        }}

        function transform(document) {{
          if (!document) throw new Error('No source_document was defined - nothing to transform!');
          const [action, meta] = pickAction(document);
          if (!action) return [];
          if (action === 'update' || action === 'delete') return []; // multiply only inserts

          const originalSource = jget(document, 'source') ?? jget(document, '_source') ?? {{}};
          const originalId = jget(meta, '_id');
          if (!originalId) return [];

          const out = [];
          for (let i=0; i<MULTIPLICATION_FACTOR_WITH_ORIGINAL; i++) {{
            const newMeta = jcloneMapLike(meta);
            jset(newMeta, '_id', i === 0 ? String(originalId) : String(originalId) + '_' + i);

            // Preserve the original action (index/create)
            const pair = (typeof Map === 'function')
              ? new Map([[action, newMeta], ['source', originalSource]])
              : (function() {{ const o = {{}}; o[action] = newMeta; o.source = originalSource; return o; }})();

            out.push(pair);
          }}
          return out;
        }}

        function main(context) {{
          console.log('Context: ', JSON.stringify((context || {{}}), null, 2));
          return (doc) => Array.isArray(doc) ? doc.flatMap(transform) : transform(doc);
        }}

        (() => main)();
    """).strip()

    multiplication_transform = {
        "JsonJSTransformerProvider": {
            "initializationScript": initialization_script,
            "bindingsObject": "{}"
        }
    }

    combined_config = [multiplication_transform]
    transformation_file_path = get_transformation_file_path()
    ops().create_transformation_json_file(combined_config, transformation_file_path)
    logger.info(f"Created transformation config at {transformation_file_path}")


# ==== MULTIPLY DOCUMENTS UTILITIES ====
class ProgressTracker:
    """Helper class to track migration progress and calculate ETAs"""
    
    def __init__(self, expected_count):
        self.expected_count = expected_count
        self.start_time = __import__('time').time()
        self.previous_count = 0
        self.previous_time = self.start_time
        self.migration_rates = []
        self.initial_count = None
        self.migration_started = False
        
    def update(self, current_count):
        """Update progress and calculate metrics"""
        current_time = time.time()
        elapsed_total = current_time - self.start_time
        elapsed_since_last = current_time - self.previous_time
        
        # Set initial count on first call
        if self.initial_count is None:
            self.initial_count = current_count
            
        # Detect if migration has actually started
        if not self.migration_started and current_count > self.initial_count:
            self.migration_started = True
        
        if elapsed_since_last > 0 and current_count > self.previous_count:
            current_rate = (current_count - self.previous_count) / elapsed_since_last
            self.migration_rates.append(current_rate)
            if len(self.migration_rates) > 5:
                self.migration_rates.pop(0)
        
        avg_rate = sum(self.migration_rates) / len(self.migration_rates) if self.migration_rates else 0
        progress_pct = (current_count / self.expected_count) * 100 if self.expected_count > 0 else 0
        remaining_docs = self.expected_count - current_count
        eta_seconds = remaining_docs / avg_rate if avg_rate > 0 and remaining_docs > 0 else 0
        
        self.previous_count = current_count
        self.previous_time = current_time
        
        return {
            'current_count': current_count,
            'expected_count': self.expected_count,
            'progress_pct': progress_pct,
            'elapsed_total': elapsed_total,
            'current_rate': self.migration_rates[-1] if self.migration_rates else 0,
            'avg_rate': avg_rate,
            'eta_seconds': eta_seconds,
            'remaining_docs': remaining_docs,
            'migration_started': self.migration_started,
            'is_static_period': not self.migration_started and current_count == self.initial_count
        }
    
    @staticmethod
    def format_duration(seconds):
        """Format duration in human-readable format"""
        if seconds < 60:
            return f"{int(seconds)}s"
        elif seconds < 3600:
            minutes = int(seconds // 60)
            secs = int(seconds % 60)
            return f"{minutes}m {secs}s"
        else:
            hours = int(seconds // 3600)
            minutes = int((seconds % 3600) // 60)
            return f"{hours}h {minutes}m"


def get_target_document_count_for_index(index_name):
    """Get document count from target cluster for specified index."""
    try:
        run_console_command([
            "console", "clusters", "curl", "target_cluster",
            "-XPOST", "/_refresh"
        ])
        result = run_console_command([
            "console", "clusters", "curl", "target_cluster",
            "-XGET", f"/{index_name}/_count"
        ])
        
        response_data = json.loads(result.stdout)
        count = response_data.get("count", 0)
        logger.debug(f"Target cluster document count for {index_name}: {count}")
        return count
    except (json.JSONDecodeError, KeyError) as e:
        logger.warning(f"Failed to parse document count response for {index_name}: {e}")
        return 0
