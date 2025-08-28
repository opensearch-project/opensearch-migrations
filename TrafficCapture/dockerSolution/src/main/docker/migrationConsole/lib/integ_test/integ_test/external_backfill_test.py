import logging
import pytest
import unittest
import csv
import os
import yaml
import tempfile
from datetime import datetime
from console_link.middleware.clusters import connection_check, clear_cluster, clear_indices, ConnectionResult
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from console_link.models.metadata import Metadata
from console_link.cli import Context
import time

logger = logging.getLogger(__name__)

class Metric:
    def __init__(self, name, value, unit):
        self.name = name
        self.value = value
        self.unit = unit

BACKFILL_SCALE = 80

def generate_csv_data(start_timestamp: datetime, size_in_tib: float):
    global BACKFILL_SCALE
    # Current time as the end timestamp.
    end_timestamp = datetime.now()
    
    # Calculate elapsed duration in seconds.
    duration_seconds = (end_timestamp - start_timestamp).total_seconds()
    duration_minutes = duration_seconds / 60.0
    
    # Convert data sizes:
    # 1 TiB = 1024 GiB; 1 GiB = 1024 MiB.
    size_in_mib = size_in_tib * 1024 * 1024
    size_in_gb = size_in_tib * 1024

    # Calculate throughput (MiB/s). Avoid division by zero.
    throughput_mib_s = size_in_mib / duration_seconds if duration_seconds > 0 else 0
    throughput_mib_s_per_worker = throughput_mib_s / BACKFILL_SCALE if BACKFILL_SCALE > 0 else 0
    
    # Define the metrics.
    metrics = [
        Metric("End Timestamp", end_timestamp.isoformat(), "ISO-8601"),
        Metric("Duration", round(duration_minutes, 2), "min"),
        Metric("Size Transferred", round(size_in_gb, 2), "GB"),
        Metric("Reindexing Throughput Total", round(throughput_mib_s, 4), "MiB/s"),
        Metric("Reindexing Throughput Per Worker", round(throughput_mib_s_per_worker, 4), "MiB/s")
    ]

    # Prepare the CSV header and row.
    header = [f"{m.name} ({m.unit})" for m in metrics]
    row = [m.value for m in metrics]
    return [header, row]

def clear_cluster_with_validation(cluster: Cluster):
    # Clear all data from cluster
    logger.info(f"Clearing cluster: {cluster.endpoint}")
    clear_cluster(cluster)
    clear_output = clear_indices(cluster)
    if isinstance(clear_output, str) and "Error" in clear_output:
        raise Exception(f"Cluster Clear Indices Failed: {clear_output}")
    logger.info("Cluster cleared successfully")

def preload_data(target_cluster: Cluster):
    logger.info("=== TARGET CLUSTER PREPARATION ===")
    # Confirm target connection
    target_con_result: ConnectionResult = connection_check(target_cluster)
    assert target_con_result.connection_established is True, f"Target cluster connection failed: {target_con_result}"
    logger.info(f"Target cluster connection verified: {target_cluster.endpoint}")
    
    # Clear any existing data
    clear_cluster_with_validation(target_cluster)
    logger.info("Target cluster preparation completed")

def create_config_with_source_version(original_config_path: str) -> str:
    """
    Load the original config and add source_cluster_version to metadata_migration.
    Returns path to temporary config file.
    """
    logger.info(f"Loading original config from: {original_config_path}")
    
    # Load existing config
    with open(original_config_path, 'r') as f:
        config = yaml.safe_load(f)
    
    # Add source cluster version to metadata migration
    if 'metadata_migration' not in config:
        config['metadata_migration'] = {}
    
    config['metadata_migration']['source_cluster_version'] = 'ES_5.6'
    logger.info("Added source_cluster_version: ES_5.6 to metadata_migration config")
    
    # Write temporary config
    temp_config_fd, temp_config_path = tempfile.mkstemp(suffix='.yaml', prefix='migration_services_')
    try:
        with os.fdopen(temp_config_fd, 'w') as f:
            yaml.dump(config, f, default_flow_style=False)
        logger.info(f"Created temporary config with source version: {temp_config_path}")
        return temp_config_path
    except:
        os.close(temp_config_fd)
        raise

@pytest.fixture(scope="class")
def setup_backfill(request):
    global BACKFILL_SCALE
    
    # Get parameters from environment variables
    original_config_path = os.environ.get('CONFIG_FILE_PATH', '/config/migration_services.yaml')
    unique_id = os.environ.get('UNIQUE_ID', 'test_run')
    backfill_scale_env = os.environ.get('BACKFILL_SCALE')
    
    # Update global BACKFILL_SCALE if provided
    if backfill_scale_env:
        BACKFILL_SCALE = int(backfill_scale_env)
    
    logger.info(f"=== EXTERNAL BACKFILL TEST STARTING ===")
    logger.info(f"Original config: {original_config_path}")
    logger.info(f"Backfill scale: {BACKFILL_SCALE}")
    logger.info(f"Unique ID: {unique_id}")
    
    # Create config with source version
    temp_config_path = create_config_with_source_version(original_config_path)
    
    try:
        # Initialize console environment with modified config
        console_env = Context(temp_config_path).env
        logger.info("Console environment initialized successfully")

        # Prepare target cluster
        preload_data(target_cluster=console_env.target_cluster)

        # Record start time
        start_timestamp = datetime.now()
        logger.info(f"Test started at: {start_timestamp}")

        target: Cluster = console_env.target_cluster
        assert target is not None, "Target cluster not available"

        backfill: Backfill = console_env.backfill
        assert backfill is not None, "Backfill service not available"
        
        metadata: Metadata = console_env.metadata
        assert metadata is not None, "Metadata service not available"

        # Phase 1: Metadata Migration
        logger.info("=== PHASE 1: METADATA MIGRATION ===")
        logger.info("Starting metadata migration from external snapshot")
        metadata_result: CommandResult = metadata.migrate()
        assert metadata_result.success, f"Metadata migration failed: {metadata_result.value}"
        logger.info("Metadata migration completed successfully")
        logger.info(f"Metadata migration result: {metadata_result.value}")

        # Phase 2: RFS Backfill Setup
        logger.info("=== PHASE 2: RFS BACKFILL MIGRATION ===")
        
        # Create and start backfill service
        logger.info("Creating backfill service")
        create_result = backfill.create()
        if not create_result.success:
            logger.warning(f"Backfill create returned: {create_result.value}")

        logger.info("Starting backfill operation")
        backfill_start_result: CommandResult = backfill.start()
        assert backfill_start_result.success, f"Backfill start failed: {backfill_start_result.value}"

        # Scale to specified number of workers
        logger.info(f"Scaling backfill to {BACKFILL_SCALE} workers")
        backfill_scale_result: CommandResult = backfill.scale(units=BACKFILL_SCALE)
        assert backfill_scale_result.success, f"Backfill scaling failed: {backfill_scale_result.value}"

        # Wait for backfill to initialize
        logger.info("Waiting for backfill initialization (120 seconds)")
        time.sleep(120)

        # Monitor backfill progress
        logger.info("Monitoring backfill progress")
        progress_check_count = 0
        while True:
            time.sleep(10)
            progress_check_count += 1
            
            try:
                status_result = backfill.get_status(deep_check=True)
                logger.info(f"Backfill status check #{progress_check_count}: {status_result}")
                
                # Log current cluster size for monitoring
                current_size = getTotalClusterSize(target)
                logger.info(f"Current cluster size: {current_size:.4f} TiB")
                
                # Check if backfill is complete
                if status_result.value and not isinstance(status_result.value, Exception):
                    if isinstance(status_result.value, tuple) and len(status_result.value) > 1:
                        status_message = status_result.value[1]
                        if is_backfill_done(status_message):
                            logger.info("Backfill operation completed")
                            break
                    elif isinstance(status_result.value, str):
                        if is_backfill_done(status_result.value):
                            logger.info("Backfill operation completed")
                            break
                
                # Safety check to prevent infinite loops
                if progress_check_count > 360:  # 1 hour maximum
                    logger.warning("Backfill monitoring timeout reached (1 hour)")
                    break
                    
            except Exception as e:
                logger.warning(f"Error checking backfill status: {e}")
                # Continue monitoring despite status check errors

        # Refresh target cluster
        logger.info("Refreshing target cluster")
        target.call_api("/_refresh")
        logger.info("Target cluster refreshed")

        # Phase 3: Generate metrics
        logger.info("=== PHASE 3: METRICS CALCULATION ===")
        final_size = getTotalClusterSize(target)
        data = generate_csv_data(start_timestamp, final_size)

        # Write metrics CSV
        try:
            # Ensure the reports directory exists
            reports_dir = os.path.join(os.path.dirname(__file__), "reports", unique_id)
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

        logger.info("External Backfill Test setup completed successfully")
        
        # Store objects for cleanup
        request.cls.console_env = console_env
        request.cls.temp_config_path = temp_config_path
        
    except Exception as e:
        # Clean up temp file on error
        if os.path.exists(temp_config_path):
            os.unlink(temp_config_path)
        logger.error(f"Setup failed with error: {e}")
        raise

def is_backfill_done(message: str) -> bool:
    """Check if backfill operation is complete based on status message."""
    if not isinstance(message, str):
        return False
        
    # Check for completion indicators
    completion_indicators = [
        "incomplete: 0",
        "in progress: 0", 
        "unclaimed: 0"
    ]
    
    # All indicators must be present for completion
    return all(indicator in message.lower() for indicator in completion_indicators)

def getTotalClusterSize(cluster: Cluster) -> float:
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

@pytest.fixture(scope="session", autouse=True)
def cleanup_after_tests(request):
    # Setup code
    logger.info("Starting backfill tests...")

    yield

    # Cleanup code
    logger.info("=== CLEANUP PHASE ===")
    
    try:
        # Get console environment from test class if available
        if hasattr(request, 'cls') and hasattr(request.cls, 'console_env'):
            console_env = request.cls.console_env
            
            # Stop backfill service
            backfill: Backfill = console_env.backfill
            if backfill:
                logger.info("Stopping backfill service")
                stop_result = backfill.stop()
                if stop_result.success:
                    logger.info("Backfill service stopped successfully")
                else:
                    logger.warning(f"Backfill stop returned: {stop_result.value}")

            # Clear target cluster
            target: Cluster = console_env.target_cluster
            if target:
                try:
                    logger.info("Clearing target cluster")
                    clear_cluster_with_validation(target)
                    logger.info("Successfully cleared target cluster")
                except Exception as e:
                    logger.warning(f"Error clearing cluster after tests: {e}")
            
            # Clean up temporary config file
            if hasattr(request.cls, 'temp_config_path') and os.path.exists(request.cls.temp_config_path):
                os.unlink(request.cls.temp_config_path)
                logger.info("Cleaned up temporary config file")
                
    except Exception as e:
        logger.warning(f"Cleanup encountered error: {e}")

@pytest.mark.usefixtures("setup_backfill")
class ExternalBackfillTests(unittest.TestCase):

    @pytest.fixture(autouse=True)
    def _get_unique_id(self, request):
        self.unique_id = os.environ.get('UNIQUE_ID', 'test_run')

    def test_backfill_large_snapshot(self):
        """
        Test external snapshot backfill migration.
        
        This test validates that the complete 3-phase migration process works:
        1. Metadata migration from external snapshot
        2. RFS backfill with scaled workers
        3. Performance metrics collection
        
        The actual work is done in the setup_backfill fixture.
        This test method serves as the pytest entry point.
        """
        logger.info("=== EXTERNAL BACKFILL TEST VALIDATION ===")
        logger.info(f"Test unique ID: {self.unique_id}")
        
        # Validate that the console environment was set up correctly
        assert hasattr(self.__class__, 'console_env'), "Console environment not initialized"
        
        # Validate that target cluster is accessible
        target = self.__class__.console_env.target_cluster
        assert target is not None, "Target cluster not available"
        
        # Validate that cluster has data (indicating successful migration)
        final_size = getTotalClusterSize(target)
        logger.info(f"Final cluster size: {final_size:.6f} TiB")
        assert final_size > 0, "No data found in target cluster after migration"
        
        # Small delay to ensure all operations are complete
        time.sleep(30)
        
        logger.info("External backfill test validation completed successfully")

def write_csv(filename, data):
    """Write CSV data to file."""
    # data should be a list of rows, where each row is a list of values.
    with open(filename, 'w', newline='') as csvfile:
        writer = csv.writer(csvfile)
        writer.writerows(data)
