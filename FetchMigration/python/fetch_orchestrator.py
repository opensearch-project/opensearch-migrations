import argparse
import base64
import logging
import os
import re
import subprocess
import sys
from typing import Optional

import yaml

import metadata_migration
import migration_monitor
from fetch_orchestrator_params import FetchOrchestratorParams
from metadata_migration_params import MetadataMigrationParams
from migration_monitor_params import MigrationMonitorParams

__PROTOCOL_PREFIX_PATTERN = re.compile(r"^https?://")
__HTTPS_PREFIX = "https://"
__DP_EXECUTABLE_SUFFIX = "/bin/data-prepper"
__PIPELINE_OUTPUT_FILE_SUFFIX = "/pipelines/pipeline.yaml"


def __get_env_string(name: str) -> Optional[str]:
    val: str = os.environ.get(name, "")
    if len(val) > 0:
        return val
    else:
        return None


def update_target_host(dp_config: dict, target_host: str):
    # Inline target host only supports HTTPS, so force it
    target_with_protocol = target_host
    match = __PROTOCOL_PREFIX_PATTERN.match(target_with_protocol)
    if match:
        target_with_protocol = target_host[match.end():]
    target_with_protocol = __HTTPS_PREFIX + target_with_protocol
    if len(dp_config) > 0:
        # We expect the Data Prepper pipeline to only have a single top-level value
        pipeline_config = next(iter(dp_config.values()))
        # The entire pipeline will be validated later
        if metadata_migration.SINK_KEY in pipeline_config:
            # throws ValueError if no supported endpoints are found
            plugin_name, plugin_config = metadata_migration.get_supported_endpoint(pipeline_config,
                                                                                   metadata_migration.SINK_KEY)
            plugin_config[metadata_migration.HOSTS_KEY] = [target_with_protocol]
            pipeline_config[metadata_migration.SINK_KEY] = [{plugin_name: plugin_config}]


def write_inline_pipeline(pipeline_file_path: str, inline_pipeline: str, inline_target_host: Optional[str]):
    pipeline_yaml = yaml.safe_load(base64.b64decode(inline_pipeline))
    if inline_target_host is not None:
        update_target_host(pipeline_yaml, inline_target_host)
    with open(pipeline_file_path, 'w') as out_file:
        # Note - this does not preserve comments
        yaml.safe_dump(pipeline_yaml, out_file)


def write_inline_target_host(pipeline_file_path: str, inline_target_host: str):
    with open(pipeline_file_path, 'rw') as pipeline_file:
        pipeline_yaml = yaml.safe_load(pipeline_file)
        update_target_host(pipeline_yaml, inline_target_host)
        # Note - this does not preserve comments
        yaml.safe_dump(pipeline_yaml, pipeline_file)


def run(params: FetchOrchestratorParams) -> Optional[int]:
    # This is expected to be a base64 encoded string
    inline_pipeline = __get_env_string("INLINE_PIPELINE")
    inline_target_host = __get_env_string("INLINE_TARGET_HOST")
    if inline_pipeline is not None:
        write_inline_pipeline(params.pipeline_file_path, inline_pipeline, inline_target_host)
    elif inline_target_host is not None:
        write_inline_target_host(params.pipeline_file_path, inline_target_host)
    dp_exec_path = params.data_prepper_path + __DP_EXECUTABLE_SUFFIX
    output_file = params.data_prepper_path + __PIPELINE_OUTPUT_FILE_SUFFIX
    if params.is_dry_run:
        logging.info("Dry-run flag enabled, no actual changes will be made\n")
    elif params.is_create_only:
        logging.info("Create-only flag enabled, will only perform metadata migration\n")
    metadata_migration_params = MetadataMigrationParams(params.pipeline_file_path, output_file,
                                                        report=True, dryrun=params.is_dry_run)
    logging.info("Running metadata migration...\n")
    metadata_migration_result = metadata_migration.run(metadata_migration_params)
    if len(metadata_migration_result.created_indices) > 0 and not params.is_only_metadata_migration():
        # Kick off a subprocess for Data Prepper
        logging.info("Running Data Prepper...\n")
        proc = subprocess.Popen(dp_exec_path)
        # Run the migration monitor next
        migration_monitor_params = MigrationMonitorParams(metadata_migration_result.target_doc_count,
                                                          params.get_local_endpoint())
        logging.info("Starting migration monitor...\n")
        return migration_monitor.run(migration_monitor_params, proc)
    logging.info("Fetch Migration workflow concluded\n")


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
        "pipeline_file_path",
        help="Path to the Data Prepper pipeline YAML file to parse for source and target endpoint information"
    )
    # Optional positional argument
    arg_parser.add_argument(
        "port", type=int,
        nargs='?', default=4900,
        help="Local port at which the Data Prepper process will expose its APIs"
    )
    # Flags
    arg_parser.add_argument("--insecure", "-k", action="store_true",
                            help="Specifies that the local Data Prepper process is not using SSL")
    arg_parser.add_argument("--dryrun", action="store_true",
                            help="Performs a dry-run. Only a report is printed - no indices are created or migrated")
    arg_parser.add_argument("--createonly", "-c", action="store_true",
                            help="Skips data migration and only creates indices on the target cluster")
    cli_args = arg_parser.parse_args()
    params = FetchOrchestratorParams(os.path.expandvars(cli_args.data_prepper_path),
                                     os.path.expandvars(cli_args.pipeline_file_path),
                                     port=cli_args.port, insecure=cli_args.insecure,
                                     dryrun=cli_args.dryrun, create_only=cli_args.createonly)
    return_code = run(params)
    if return_code == 0:
        sys.exit(0)
    else:
        logging.error("Process exited with non-zero return code: " + str(return_code))
        if return_code is None:
            return_code = 1
        sys.exit(return_code)
