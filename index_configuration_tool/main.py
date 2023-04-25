import sys
from typing import Optional

import logstash_conf_parser as parser
import index_operations
import utils

# Constants
SUPPORTED_ENDPOINTS = ["opensearch", "elasticsearch"]
HOSTS_KEY = "hosts"
USER_KEY = "user"
PWD_KEY = "password"


# TODO Only supports basic auth for now
def get_auth(input_data: dict) -> Optional[tuple]:
    if USER_KEY in input_data and PWD_KEY in input_data:
        return input_data[USER_KEY], input_data[PWD_KEY]


def get_endpoint_info(plugin_config: dict) -> tuple:
    endpoint = "https://" if ("ssl" in plugin_config and plugin_config["ssl"]) else "http://"
    # "hosts" can be a simple string, or an array of hosts for Logstash to hit.
    # This tool needs one accessible host, so we pick the first entry in the latter case.
    endpoint += plugin_config[HOSTS_KEY][0] if type(plugin_config[HOSTS_KEY]) is list else plugin_config[HOSTS_KEY]
    endpoint += "/"
    return endpoint, get_auth(plugin_config)


def fetch_all_indices_by_plugin(plugin_config: dict) -> dict:
    endpoint, auth_tuple = get_endpoint_info(plugin_config)
    return index_operations.fetch_all_indices(endpoint, auth_tuple)


def get_supported_endpoint(config: dict, key: str) -> tuple:
    # The value of each key is a list of plugin configs.
    # Each config is a tuple, where the first value is the endpoint type.
    supported_endpoint = next((p for p in config[key] if p[0] in SUPPORTED_ENDPOINTS), None)
    if not supported_endpoint:
        raise ValueError("Could not find any supported endpoints in section: " + key)
    return supported_endpoint


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


def validate_logstash_config(config: dict):
    if "input" not in config or "output" not in config:
        raise ValueError("Missing input or output data from Logstash config")
    validate_plugin_config(config, "input")
    validate_plugin_config(config, "output")


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


def print_report(index_differences: tuple[set, set, set]):
    print("Indices in target cluster with conflicting settings/mappings: " +
          utils.string_from_set(index_differences[2]))
    print("Indices already created in target cluster (data will NOT be moved): " +
          utils.string_from_set(index_differences[1]))
    print("Indices to create: " + utils.string_from_set(index_differences[0]))


if __name__ == '__main__':
    # Parse logstash config file
    print("\n##### Starting index configuration tool... #####\n")
    logstash_config = parser.parse(sys.argv[1])
    validate_logstash_config(logstash_config)
    # Endpoint is a tuple of (type, config)
    endpoint = get_supported_endpoint(logstash_config, "input")
    # Fetch all indices from source cluster
    source_indices = fetch_all_indices_by_plugin(endpoint[1])
    # Fetch all indices from target cluster
    endpoint = get_supported_endpoint(logstash_config, "output")
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
    print("\n##### Index configuration tool has completed! #####\n")
