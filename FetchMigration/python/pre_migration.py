import argparse
import yaml
from typing import Optional

import index_operations
import utils

# Constants
from endpoint_info import EndpointInfo
from pre_migration_params import PreMigrationParams
from pre_migration_result import PreMigrationResult

SUPPORTED_ENDPOINTS = ["opensearch", "elasticsearch"]
SOURCE_KEY = "source"
SINK_KEY = "sink"
HOSTS_KEY = "hosts"
DISABLE_AUTH_KEY = "disable_authentication"
USER_KEY = "username"
PWD_KEY = "password"
INSECURE_KEY = "insecure"
CONNECTION_KEY = "connection"
INDICES_KEY = "indices"
INCLUDE_KEY = "include"
INDEX_NAME_KEY = "index_name_regex"


# This config key may be either directly in the main dict (for sink)
# or inside a nested dict (for source). The default value is False.
def is_insecure(config: dict) -> bool:
    if INSECURE_KEY in config:
        return config[INSECURE_KEY]
    elif CONNECTION_KEY in config and INSECURE_KEY in config[CONNECTION_KEY]:
        return config[CONNECTION_KEY][INSECURE_KEY]
    return False


# TODO Only supports basic auth for now
def get_auth(input_data: dict) -> Optional[tuple]:
    if not input_data.get(DISABLE_AUTH_KEY, False) and USER_KEY in input_data and PWD_KEY in input_data:
        return input_data[USER_KEY], input_data[PWD_KEY]


def get_endpoint_info(plugin_config: dict) -> EndpointInfo:
    # "hosts" can be a simple string, or an array of hosts for Logstash to hit.
    # This tool needs one accessible host, so we pick the first entry in the latter case.
    url = plugin_config[HOSTS_KEY][0] if type(plugin_config[HOSTS_KEY]) is list else plugin_config[HOSTS_KEY]
    url += "/"
    # verify boolean will be the inverse of the insecure SSL key, if present
    should_verify = not is_insecure(plugin_config)
    return EndpointInfo(url, get_auth(plugin_config), should_verify)


def check_supported_endpoint(config: dict) -> Optional[tuple]:
    for supported_type in SUPPORTED_ENDPOINTS:
        if supported_type in config:
            return supported_type, config[supported_type]


def get_supported_endpoint(config: dict, key: str) -> tuple:
    # The value of each key may be a single plugin (as a dict)
    # or a list of plugin configs
    supported_tuple = tuple()
    if type(config[key]) is dict:
        supported_tuple = check_supported_endpoint(config[key])
    elif type(config[key]) is list:
        for entry in config[key]:
            supported_tuple = check_supported_endpoint(entry)
            # Break out of the loop at the first supported type
            if supported_tuple:
                break
    if not supported_tuple:
        raise ValueError("Could not find any supported endpoints in section: " + key)
    # First tuple value is the name, second value is the config dict
    return supported_tuple[0], supported_tuple[1]


def validate_plugin_config(config: dict, key: str):
    # Raises a ValueError if no supported endpoints are found
    supported_endpoint = get_supported_endpoint(config, key)
    plugin_config = supported_endpoint[1]
    if HOSTS_KEY not in plugin_config:
        raise ValueError("No hosts defined for endpoint: " + supported_endpoint[0])
    # Check if auth is disabled. If so, no further validation is required
    if plugin_config.get(DISABLE_AUTH_KEY, False):
        return
    elif USER_KEY not in plugin_config:
        raise ValueError("Invalid auth configuration (no username) for endpoint: " + supported_endpoint[0])
    elif PWD_KEY not in plugin_config:
        raise ValueError("Invalid auth configuration (no password for username) for endpoint: " +
                         supported_endpoint[0])


def validate_pipeline_config(config: dict):
    if SOURCE_KEY not in config:
        raise ValueError("Missing source configuration in Data Prepper pipeline YAML")
    if SINK_KEY not in config:
        raise ValueError("Missing sink configuration in Data Prepper pipeline YAML")
    validate_plugin_config(config, SOURCE_KEY)
    validate_plugin_config(config, SINK_KEY)


def write_output(yaml_data: dict, new_indices: set, output_path: str):
    pipeline_config = next(iter(yaml_data.values()))
    # Endpoint is a tuple of (type, config)
    source_config = get_supported_endpoint(pipeline_config, SOURCE_KEY)[1]
    source_indices = source_config.get(INDICES_KEY, dict())
    included_indices = source_indices.get(INCLUDE_KEY, list())
    for index in new_indices:
        included_indices.append({INDEX_NAME_KEY: index})
    source_indices[INCLUDE_KEY] = included_indices
    source_config[INDICES_KEY] = source_indices
    with open(output_path, 'w') as out_file:
        yaml.dump(yaml_data, out_file)


# Computes differences in indices between source and target.
# Returns a tuple with 3 elements:
# - The 1st element is the set of indices to create on the target
# - The 2nd element is a set of indices that are identical on source and target
# - The 3rd element is a set of indices that are present on both source and target,
# but differ in their settings or mappings.
def get_index_differences(source: dict, target: dict) -> tuple[set, set, set]:
    index_conflicts = set()
    indices_in_target = set(source.keys()) & set(target.keys())
    for index in indices_in_target:
        # Check settings
        if utils.has_differences(index_operations.SETTINGS_KEY, source[index], target[index]):
            index_conflicts.add(index)
        # Check mappings
        if utils.has_differences(index_operations.MAPPINGS_KEY, source[index], target[index]):
            index_conflicts.add(index)
    identical_indices = set(indices_in_target) - set(index_conflicts)
    indices_to_create = set(source.keys()) - set(indices_in_target)
    return indices_to_create, identical_indices, index_conflicts


# The order of data in the tuple is:
# (indices to create), (identical indices), (indices with conflicts)
def print_report(index_differences: tuple[set, set, set], count: int):  # pragma no cover
    print("Identical indices in the target cluster (no changes will be made): " +
          utils.string_from_set(index_differences[1]))
    print("Indices in target cluster with conflicting settings/mappings: " +
          utils.string_from_set(index_differences[2]))
    print("Indices to create: " + utils.string_from_set(index_differences[0]))
    print("Total documents to be moved: " + str(count))


def compute_endpoint_and_fetch_indices(config: dict, key: str) -> tuple[EndpointInfo, dict]:
    endpoint = get_supported_endpoint(config, key)
    # Endpoint is a tuple of (type, config)
    endpoint_info = get_endpoint_info(endpoint[1])
    indices = index_operations.fetch_all_indices(endpoint_info)
    return endpoint_info, indices


def run(args: PreMigrationParams) -> PreMigrationResult:
    # Sanity check
    if not args.report and len(args.output_file) == 0:
        raise ValueError("No output file specified")
    # Parse and validate pipelines YAML file
    with open(args.config_file_path, 'r') as pipeline_file:
        dp_config = yaml.safe_load(pipeline_file)
    # We expect the Data Prepper pipeline to only have a single top-level value
    pipeline_config = next(iter(dp_config.values()))
    validate_pipeline_config(pipeline_config)
    # Fetch EndpointInfo and indices
    source_endpoint_info, source_indices = compute_endpoint_and_fetch_indices(pipeline_config, SOURCE_KEY)
    target_endpoint_info, target_indices = compute_endpoint_and_fetch_indices(pipeline_config, SINK_KEY)
    # Compute index differences and print report
    diff = get_index_differences(source_indices, target_indices)
    # The first element in the tuple is the set of indices to create
    indices_to_create = diff[0]
    result = PreMigrationResult()
    if indices_to_create:
        result.created_indices = indices_to_create
        result.target_doc_count = index_operations.doc_count(indices_to_create, source_endpoint_info)
    if args.report:
        print_report(diff, result.target_doc_count)
    if indices_to_create:
        # Write output YAML
        if len(args.output_file) > 0:
            write_output(dp_config, indices_to_create, args.output_file)
            if args.report:
                print("Wrote output YAML pipeline to: " + args.output_file)
        if not args.dryrun:
            index_data = dict()
            for index_name in indices_to_create:
                index_data[index_name] = source_indices[index_name]
            index_operations.create_indices(index_data, target_endpoint_info)
    return result


if __name__ == '__main__':  # pragma no cover
    # Set up parsing for command line arguments
    arg_parser = argparse.ArgumentParser(
        prog="python pre_migration.py",
        description="This tool creates indices on a target cluster based on the contents of a source cluster.\n" +
        "The first input to the tool is a path to a Data Prepper pipeline YAML file, which is parsed to obtain " +
        "the source and target cluster endpoints.\nThe second input is an output path to which a modified version " +
        "of the pipeline YAML file is written. This version of the pipeline adds an index inclusion configuration " +
        "to the sink, specifying only those indices that were created by the index configuration tool.\nThis tool " +
        "can also print a report based on the indices in the source cluster, indicating which ones will be created, " +
        "along with indices that are identical or have conflicting settings/mappings.",
        formatter_class=argparse.RawTextHelpFormatter
    )
    # Required positional argument
    arg_parser.add_argument(
        "config_file_path",
        help="Path to the Data Prepper pipeline YAML file to parse for source and target endpoint information"
    )
    # Optional positional argument
    arg_parser.add_argument(
        "output_file",
        nargs='?', default="",
        help="Output path for the Data Prepper pipeline YAML file that will be generated"
    )
    # Flags
    arg_parser.add_argument("--report", "-r", action="store_true",
                            help="Print a report of the index differences")
    arg_parser.add_argument("--dryrun", action="store_true",
                            help="Skips the actual creation of indices on the target cluster")
    namespace = arg_parser.parse_args()
    run(PreMigrationParams(namespace.config_file_path, namespace.output_file, namespace.report, namespace.dryrun))
