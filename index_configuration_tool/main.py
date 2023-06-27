import argparse
import yaml
from typing import Optional

import index_operations
import utils

# Constants
SUPPORTED_ENDPOINTS = ["opensearch", "elasticsearch"]
SOURCE_KEY = "source"
SINK_KEY = "sink"
HOSTS_KEY = "hosts"
USER_KEY = "username"
PWD_KEY = "password"


# TODO Only supports basic auth for now
def get_auth(input_data: dict) -> Optional[tuple]:
    if USER_KEY in input_data and PWD_KEY in input_data:
        return input_data[USER_KEY], input_data[PWD_KEY]


def get_endpoint_info(plugin_config: dict) -> tuple:
    # "hosts" can be a simple string, or an array of hosts for Logstash to hit.
    # This tool needs one accessible host, so we pick the first entry in the latter case.
    endpoint = plugin_config[HOSTS_KEY][0] if type(plugin_config[HOSTS_KEY]) is list else plugin_config[HOSTS_KEY]
    endpoint += "/"
    return endpoint, get_auth(plugin_config)


def fetch_all_indices_by_plugin(plugin_config: dict) -> dict:
    endpoint, auth_tuple = get_endpoint_info(plugin_config)
    return index_operations.fetch_all_indices(endpoint, auth_tuple)


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
    if USER_KEY in plugin_config and PWD_KEY not in plugin_config:
        raise ValueError("Invalid auth configuration (no password for user) for endpoint: " + supported_endpoint[0])
    elif PWD_KEY in plugin_config and USER_KEY not in plugin_config:
        raise ValueError("Invalid auth configuration (Password without user) for endpoint: " + supported_endpoint[0])


def validate_pipeline_config(config: dict):
    if SOURCE_KEY not in config or SINK_KEY not in config:
        raise ValueError("Missing source or sink configuration in Data Prepper pipeline YAML")
    validate_plugin_config(config, SOURCE_KEY)
    validate_plugin_config(config, SINK_KEY)


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
def print_report(index_differences: tuple[set, set, set]):  # pragma no cover
    print("Identical indices in the target cluster (no changes will be made): " +
          utils.string_from_set(index_differences[1]))
    print("Indices in target cluster with conflicting settings/mappings: " +
          utils.string_from_set(index_differences[2]))
    print("Indices to create: " + utils.string_from_set(index_differences[0]))


def run(config_file_path: str) -> None:
    # Parse and validate pipelines YAML file
    with open(config_file_path, 'r') as pipeline_file:
        dp_config = yaml.safe_load(pipeline_file)
    # We expect the Data Prepper pipeline to only have a single top-level value
    pipeline_config = next(iter(dp_config.values()))
    validate_pipeline_config(pipeline_config)
    # Endpoint is a tuple of (type, config)
    endpoint = get_supported_endpoint(pipeline_config, SOURCE_KEY)
    # Fetch all indices from source cluster
    source_indices = fetch_all_indices_by_plugin(endpoint[1])
    # Fetch all indices from target cluster
    endpoint = get_supported_endpoint(pipeline_config, SINK_KEY)
    target_endpoint, target_auth = get_endpoint_info(endpoint[1])
    target_indices = index_operations.fetch_all_indices(target_endpoint, target_auth)
    # Compute index differences and print report
    diff = get_index_differences(source_indices, target_indices)
    print_report(diff)
    # The first element in the tuple is the set of indices to create
    if diff[0]:
        index_data = dict()
        for index_name in diff[0]:
            index_data[index_name] = source_indices[index_name]
        index_operations.create_indices(index_data, target_endpoint, target_auth)


if __name__ == '__main__':  # pragma no cover
    # Set up parsing for command line arguments
    arg_parser = argparse.ArgumentParser(
        prog="python main.py",
        description="This tool creates indices on a target cluster based on the contents of a source cluster.\n" +
        "The source and target endpoints are obtained by parsing a Data Prepper pipelines YAML file, which " +
        "is the sole expected argument for this module.\nAlso prints a report of the indices to be created, " +
        "along with indices that are identical or have conflicting settings/mappings.\nIn case of the " +
        "latter, no action will be taken on the target cluster.",
        formatter_class=argparse.RawTextHelpFormatter
    )
    # This tool only takes one argument
    arg_parser.add_argument(
        "config_file_path",
        help="Path to the Data Prepper pipeline YAML file to parse for source and target endpoint information"
    )
    args = arg_parser.parse_args()
    print("\n##### Starting index configuration tool... #####\n")
    run(args.config_file_path)
    print("\n##### Index configuration tool has completed! #####\n")
