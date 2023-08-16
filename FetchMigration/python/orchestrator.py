import argparse
import logging
import os
import subprocess

import migration_monitor
import pre_migration
from migration_monitor_params import MigrationMonitorParams
from pre_migration_params import PreMigrationParams


__DP_EXECUTABLE_SUFFIX = "/bin/data-prepper"
__PIPELINE_OUTPUT_FILE_SUFFIX = "/pipelines/pipeline.yaml"


def run(dp_base_path: str, dp_config_file: str, dp_endpoint: str):
    dp_exec_path = dp_base_path + __DP_EXECUTABLE_SUFFIX
    output_file = dp_base_path + __PIPELINE_OUTPUT_FILE_SUFFIX
    pre_migration_params = PreMigrationParams(dp_config_file, output_file, report=True)
    logging.info("Running pre-migration steps...\n")
    pre_migration_result = pre_migration.run(pre_migration_params)
    if len(pre_migration_result.created_indices) > 0:
        # Kick off a subprocess for Data Prepper
        logging.info("Running Data Prepper...\n")
        proc = subprocess.Popen(dp_exec_path)
        # Data Prepper started successfully, run the migration monitor
        migration_monitor_params = MigrationMonitorParams(pre_migration_result.target_doc_count, dp_endpoint)
        logging.info("Starting migration monitor...\n")
        migration_monitor.run(migration_monitor_params)
        # Migration ended, the following is a workaround for
        # https://github.com/opensearch-project/data-prepper/issues/3141
        if proc.returncode is None:
            proc.terminate()


if __name__ == '__main__':  # pragma no cover
    # Set log level
    logging.basicConfig(level=logging.INFO)
    # Set up parsing for command line arguments
    arg_parser = argparse.ArgumentParser(
        prog="python orchestrator.py",
        description="Orchestrator script for fetch migration",
        formatter_class=argparse.RawTextHelpFormatter
    )
    # Required positional argument
    arg_parser.add_argument(
        "dp_path",
        help="Path to the base directory where Data Prepper is installed "
    )
    arg_parser.add_argument(
        "config_file_path",
        help="Path to the Data Prepper pipeline YAML file to parse for source and target endpoint information"
    )
    arg_parser.add_argument(
        "dp_endpoint",
        help="Data Prepper endpoint for monitoring the migration"
    )
    cli_args = arg_parser.parse_args()
    base_path = os.path.expandvars(cli_args.dp_path)
    run(base_path, cli_args.config_file_path, cli_args.dp_endpoint)
