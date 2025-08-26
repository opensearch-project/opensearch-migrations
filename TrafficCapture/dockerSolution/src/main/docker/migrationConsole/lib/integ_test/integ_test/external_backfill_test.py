#!/usr/bin/env python3
"""
External backfill test for snapshot-based migration with performance monitoring.
Tests migration from external S3 snapshot to target OpenSearch cluster.

This test implements a 3-phase approach:
1. Metadata Migration: Migrate index mappings, templates, and settings from snapshot
2. RFS Backfill: Reindex documents from snapshot with configurable worker scaling
3. Metrics Collection: Calculate and export performance metrics for Jenkins plotting

The test is designed to work with external snapshots (no source cluster required)
and generates CSV metrics compatible with Jenkins Plot plugin.
"""

import logging
import csv
import os
import time
import argparse
from datetime import datetime
from console_link.middleware.clusters import connection_check, clear_cluster, clear_indices, ConnectionResult
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from console_link.models.metadata import Metadata
from console_link.cli import Context

logger = logging.getLogger(__name__)


class Metric:
    """Represents a performance metric with name, value, and unit."""
    def __init__(self, name, value, unit):
        self.name = name
        self.value = value
        self.unit = unit


class ExternalBackfillTest:
    """
    Main test class for external snapshot-based migration with performance monitoring.

    This class orchestrates the complete migration workflow:
    - Target cluster preparation and validation
    - Metadata migration from external snapshot
    - Document backfill with configurable RFS workers
    - Performance metrics calculation and CSV generation
    """

    def __init__(self, config_path, backfill_scale, unique_id):
        """
        Initialize the test with configuration and parameters.

        Args:
            config_path (str): Path to migration_services.yaml configuration file
            backfill_scale (int): Number of RFS workers to scale to
            unique_id (str): Unique identifier for this test run
        """
        self.config_path = config_path
        self.backfill_scale = backfill_scale
        self.unique_id = unique_id
        self.console_env = Context(config_path).env
        self.start_timestamp = None

        logger.info(f"Initialized ExternalBackfillTest with config: {config_path}")
        logger.info(f"Backfill scale: {backfill_scale} workers")
        logger.info(f"Unique ID: {unique_id}")
        
    def clear_cluster_with_validation(self, cluster: Cluster):
        """
        Clear all data from cluster with validation.
        
        Args:
            cluster (Cluster): The cluster to clear
            
        Raises:
            Exception: If cluster clearing fails
        """
        logger.info(f"Clearing cluster: {cluster.endpoint}")
        clear_cluster(cluster)
        clear_output = clear_indices(cluster)
        if isinstance(clear_output, str) and "Error" in clear_output:
            raise Exception(f"Cluster Clear Indices Failed: {clear_output}")
        logger.info("Cluster cleared successfully")

    def preload_data(self, target_cluster: Cluster):
        """
        Confirm target connection and clear existing data.
        
        Args:
            target_cluster (Cluster): The target cluster to prepare
            
        Raises:
            AssertionError: If target cluster connection fails
        """
        logger.info("=== TARGET CLUSTER PREPARATION ===")
        
        # Verify target cluster connectivity
        target_con_result: ConnectionResult = connection_check(target_cluster)
        assert target_con_result.connection_established is True, f"Target cluster connection failed: {target_con_result}"
        
        logger.info(f"Target cluster connection verified: {target_cluster.endpoint}")
        
        # Clear any existing data
        self.clear_cluster_with_validation(target_cluster)
        logger.info("Target cluster preparation completed")

    def run_metadata_migration(self):
        """
        Phase 1: Perform metadata migration from external snapshot.
        
        Returns:
            CommandResult: Result of the metadata migration operation
            
        Raises:
            AssertionError: If metadata migration fails
        """
        logger.info("=== PHASE 1: METADATA MIGRATION ===")
        
        metadata: Metadata = self.console_env.metadata
        assert metadata is not None, "Metadata service not available in console environment"
        
        logger.info("Starting metadata migration from external snapshot")
        metadata_result: CommandResult = metadata.migrate()
        assert metadata_result.success, f"Metadata migration failed: {metadata_result.value}"
        
        logger.info("Metadata migration completed successfully")
        logger.info(f"Metadata migration result: {metadata_result.value}")
        
        return metadata_result

    def run_backfill_migration(self):
        """
        Phase 2: Perform RFS backfill with configurable worker scaling.
        
        Returns:
            Backfill: The backfill service instance
            
        Raises:
            AssertionError: If backfill operations fail
        """
        logger.info("=== PHASE 2: RFS BACKFILL MIGRATION ===")
        
        backfill: Backfill = self.console_env.backfill
        assert backfill is not None, "Backfill service not available in console environment"
        
        # Create and start backfill service
        logger.info("Creating backfill service")
        create_result = backfill.create()
        if not create_result.success:
            logger.warning(f"Backfill create returned: {create_result.value}")
        
        logger.info("Starting backfill operation")
        backfill_start_result: CommandResult = backfill.start()
        assert backfill_start_result.success, f"Backfill start failed: {backfill_start_result.value}"
        
        # Scale to specified number of workers
        logger.info(f"Scaling backfill to {self.backfill_scale} workers")
        backfill_scale_result: CommandResult = backfill.scale(units=self.backfill_scale)
        assert backfill_scale_result.success, f"Backfill scaling failed: {backfill_scale_result.value}"
        
        # Wait for backfill to initialize
        logger.info("Waiting for backfill initialization (120 seconds)")
        time.sleep(120)
        
        # Monitor backfill progress
        logger.info("Monitoring backfill progress")
        target: Cluster = self.console_env.target_cluster
        
        progress_check_count = 0
        while True:
            time.sleep(10)
            progress_check_count += 1
            
            try:
                status_result = backfill.get_status(deep_check=True)
                logger.info(f"Backfill status check #{progress_check_count}: {status_result}")
                
                # Log current cluster size for monitoring
                current_size = self.get_total_cluster_size(target)
                logger.info(f"Current cluster size: {current_size:.4f} TiB")
                
                # Check if backfill is complete
                if status_result.value and not isinstance(status_result.value, Exception):
                    if isinstance(status_result.value, tuple) and len(status_result.value) > 1:
                        status_message = status_result.value[1]
                        if self.is_backfill_done(status_message):
                            logger.info("Backfill operation completed")
                            break
                    elif isinstance(status_result.value, str):
                        if self.is_backfill_done(status_result.value):
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
        
        return backfill

    def calculate_and_generate_metrics(self):
        """
        Phase 3: Calculate performance metrics and generate CSV.
        
        Returns:
            list: CSV data with headers and metrics row
        """
        logger.info("=== PHASE 3: METRICS CALCULATION ===")
        
        # Current time as the end timestamp
        end_timestamp = datetime.now()
        
        # Calculate elapsed duration
        duration_seconds = (end_timestamp - self.start_timestamp).total_seconds()
        duration_minutes = duration_seconds / 60.0
        
        # Get final cluster size
        target: Cluster = self.console_env.target_cluster
        size_in_tib = self.get_total_cluster_size(target)
        
        # Convert data sizes
        size_in_mib = size_in_tib * 1024 * 1024  # TiB to MiB
        size_in_gb = size_in_tib * 1024  # TiB to GB
        
        # Calculate throughput (avoid division by zero)
        throughput_mib_s = size_in_mib / duration_seconds if duration_seconds > 0 else 0
        throughput_mib_s_per_worker = throughput_mib_s / self.backfill_scale if self.backfill_scale > 0 else 0
        
        # Define the metrics
        metrics = [
            Metric("End Timestamp", end_timestamp.isoformat(), "ISO-8601"),
            Metric("Duration", round(duration_minutes, 2), "min"),
            Metric("Size Transferred", round(size_in_gb, 2), "GB"),
            Metric("Reindexing Throughput Total", round(throughput_mib_s, 4), "MiB/s"),
            Metric("Reindexing Throughput Per Worker", round(throughput_mib_s_per_worker, 4), "MiB/s")
        ]
        
        # Prepare CSV data
        header = [f"{m.name} ({m.unit})" for m in metrics]
        row = [m.value for m in metrics]
        csv_data = [header, row]
        
        # Write metrics to CSV
        self.write_metrics_csv(csv_data)
        
        logger.info("=== METRICS SUMMARY ===")
        for metric in metrics:
            logger.info(f"{metric.name}: {metric.value} {metric.unit}")
        
        return csv_data

    def write_metrics_csv(self, data):
        """
        Write metrics data to CSV file for Jenkins Plot plugin.
        
        Args:
            data (list): CSV data with headers and values
            
        Raises:
            Exception: If CSV writing fails
        """
        try:
            # Ensure the reports directory exists
            reports_dir = os.path.join(os.path.dirname(__file__), "reports", self.unique_id)
            os.makedirs(reports_dir, exist_ok=True)
            
            # Write metrics CSV
            metrics_file = os.path.join(reports_dir, "backfill_metrics.csv")
            logger.info(f"Writing metrics to: {metrics_file}")
            
            with open(metrics_file, 'w', newline='') as csvfile:
                writer = csv.writer(csvfile)
                writer.writerows(data)
            
            logger.info(f"Successfully wrote metrics to: {metrics_file}")
            
        except Exception as e:
            logger.error(f"Error writing metrics file: {str(e)}")
            raise

    def is_backfill_done(self, message: str) -> bool:
        """
        Check if backfill operation is complete based on status message.
        
        Args:
            message (str): Status message from backfill service
            
        Returns:
            bool: True if backfill is complete, False otherwise
        """
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

    def get_total_cluster_size(self, cluster: Cluster) -> float:
        """
        Get total cluster size in TiB.
        
        Args:
            cluster (Cluster): The cluster to measure
            
        Returns:
            float: Cluster size in TiB
        """
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

    def cleanup_after_test(self):
        """
        Cleanup resources after test completion.
        
        This method attempts to stop the backfill service and clear the target cluster.
        Errors during cleanup are logged as warnings but don't fail the test.
        """
        logger.info("=== CLEANUP PHASE ===")
        
        try:
            # Stop backfill service
            backfill: Backfill = self.console_env.backfill
            if backfill:
                logger.info("Stopping backfill service")
                stop_result = backfill.stop()
                if stop_result.success:
                    logger.info("Backfill service stopped successfully")
                else:
                    logger.warning(f"Backfill stop returned: {stop_result.value}")
            
            # Clear target cluster
            target: Cluster = self.console_env.target_cluster
            if target:
                logger.info("Clearing target cluster")
                self.clear_cluster_with_validation(target)
                logger.info("Successfully cleared target cluster")
                
        except Exception as e:
            logger.warning(f"Cleanup encountered error: {e}")

    def run_complete_test(self):
        """
        Run the complete 3-phase external backfill test.
        
        This is the main entry point that orchestrates:
        1. Target cluster preparation
        2. Metadata migration
        3. RFS backfill
        4. Metrics calculation
        5. Cleanup
        
        Raises:
            Exception: If any phase of the test fails
        """
        logger.info("Starting External Backfill Test")
        logger.info(f"Backfill Scale: {self.backfill_scale} workers")
        logger.info(f"Unique ID: {self.unique_id}")
        
        try:
            # Record start time
            self.start_timestamp = datetime.now()
            logger.info(f"Test started at: {self.start_timestamp}")
            
            # Prepare target cluster
            target: Cluster = self.console_env.target_cluster
            assert target is not None, "Target cluster not available in console environment"
            self.preload_data(target_cluster=target)
            
            # Phase 1: Metadata Migration
            self.run_metadata_migration()
            
            # Phase 2: RFS Backfill
            self.run_backfill_migration()
            
            # Phase 3: Metrics Calculation
            self.calculate_and_generate_metrics()
            
            logger.info("External Backfill Test completed successfully")
            
        except Exception as e:
            logger.error(f"Test failed with error: {e}")
            raise
        finally:
            # Always attempt cleanup
            self.cleanup_after_test()


def main():
    """
    Main entry point for the external backfill test.
    
    Parses command line arguments and executes the test.
    """
    parser = argparse.ArgumentParser(description='External backfill test with performance monitoring')
    parser.add_argument('--config-file-path', required=True, help='Path to migration configuration file')
    parser.add_argument('--backfill-scale', type=int, default=80, help='Number of RFS workers')
    parser.add_argument('--unique-id', required=True, help='Unique identifier for test run')
    parser.add_argument('--stage', required=True, help='Deployment stage')
    parser.add_argument('--snapshot-name', required=True, help='Snapshot name')
    parser.add_argument('--snapshot-repo', required=True, help='Snapshot repository')
    
    args = parser.parse_args()
    
    # Configure logging
    logging.basicConfig(
        level=logging.INFO,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    logger.info("=== EXTERNAL BACKFILL TEST STARTING ===")
    logger.info(f"Configuration file: {args.config_file_path}")
    logger.info(f"Backfill scale: {args.backfill_scale}")
    logger.info(f"Unique ID: {args.unique_id}")
    logger.info(f"Stage: {args.stage}")
    logger.info(f"Snapshot: {args.snapshot_name}")
    logger.info(f"Repository: {args.snapshot_repo}")
    
    # Run test
    test = ExternalBackfillTest(
        config_path=args.config_file_path,
        backfill_scale=args.backfill_scale,
        unique_id=args.unique_id
    )
    
    test.run_complete_test()
    
    logger.info("=== EXTERNAL BACKFILL TEST COMPLETED ===")

if __name__ == '__main__':
    main()
