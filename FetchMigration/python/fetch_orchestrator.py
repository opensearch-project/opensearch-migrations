import argparse
import base64
import logging
import os
import subprocess
import sys
from typing import Optional

import metadata_migration
import migration_monitor
from metadata_migration_params import MetadataMigrationParams
from migration_monitor_params import MigrationMonitorParams

__DP_EXECUTABLE_SUFFIX = "/bin/data-prepper"
__PIPELINE_OUTPUT_FILE_SUFFIX = "/pipelines/pipeline.yaml"


def run(dp_base_path: str, dp_config_file: str, dp_endpoint: str) -> Optional[int]:
    dp_exec_path = dp_base_path + __DP_EXECUTABLE_SUFFIX
    output_file = dp_base_path + __PIPELINE_OUTPUT_FILE_SUFFIX
    metadata_migration_params = MetadataMigrationParams(dp_config_file, output_file, report=True)
    logging.info("Running pre-migration steps...\n")
    metadata_migration_result = metadata_migration.run(metadata_migration_params)
    if len(metadata_migration_result.created_indices) > 0:
        # Kick off a subprocess for Data Prepper
        logging.info("Running Data Prepper...\n")
        proc = subprocess.Popen(dp_exec_path)
        # Run the migration monitor next
        migration_monitor_params = MigrationMonitorParams(metadata_migration_result.target_doc_count, dp_endpoint)
        logging.info("Starting migration monitor...\n")
        return migration_monitor.monitor_local(migration_monitor_params, proc)


if __name__ == '__main__':  # pragma no cover
    # Set log level
    logging.basicConfig(level=logging.INFO)
    # Set up parsing for command line arguments
    arg_parser = argparse.ArgumentParser(
        prog="python fetch_orchestrator.py",
        description="Orchestrator script for fetch migration",
        formatter_class=argparse.RawTextHelpFormatter
    )
    # Required positional argument
    arg_parser.add_argument(
        "data_prepper_path",
        help="Path to the base directory where Data Prepper is installed "
    )
    arg_parser.add_argument(
        "config_file_path",
        help="Path to the Data Prepper pipeline YAML file to parse for source and target endpoint information"
    )
    arg_parser.add_argument(
        "data_prepper_endpoint",
        help="Data Prepper endpoint for monitoring the migration"
    )
    cli_args = arg_parser.parse_args()
    base_path = os.path.expandvars(cli_args.data_prepper_path)

    inline_pipeline = os.environ.get("INLINE_PIPELINE", None)
    if inline_pipeline is not None:
        decoded_bytes = base64.b64decode(inline_pipeline)
        with open(cli_args.config_file_path, 'wb') as config_file:
            config_file.write(decoded_bytes)
    return_code = run(base_path, cli_args.config_file_path, cli_args.data_prepper_endpoint)
    if return_code == 0:
        sys.exit(0)
    else:
        logging.error("Process exited with non-zero return code: " + str(return_code))
        if return_code is None:
            return_code = 1
        sys.exit(return_code)
