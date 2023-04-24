import sys
from typing import Optional

import logstash_conf_parser as parser
import search_endpoint
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
    return search_endpoint.fetch_all_indices(endpoint, auth_tuple)


def validate_logstash_config(config: dict):
    if "input" not in config or "output" not in config:
        raise ValueError("Missing input or output data from Logstash config")
    for plugin_tuple in config["input"], config["output"]:
        endpoint_type = plugin_tuple[0]
        if endpoint_type not in SUPPORTED_ENDPOINTS:
            raise ValueError("Unsupported endpoint type: " + endpoint_type)
        plugin_config = plugin_tuple[1]
        if HOSTS_KEY not in plugin_config:
            raise ValueError("No hosts defined for endpoint: " + endpoint_type)
        if USER_KEY in plugin_config and PWD_KEY not in plugin_config:
            raise ValueError("Invalid auth configuration (no password for user) for endpoint: " + endpoint_type)
        elif PWD_KEY in plugin_config and USER_KEY not in plugin_config:
            raise ValueError("Invalid auth configuration (Password without user) for endpoint: " + endpoint_type)


def print_report(source: dict, target: dict) -> set:
    index_conflicts = set()
    indices_in_target = set(source.keys()) & set(target.keys())
    for index in indices_in_target:
        # Check settings
        if utils.has_differences(search_endpoint.SETTINGS_KEY, source[index], target[index]):
            index_conflicts.add(index)
        # Check mappings
        if utils.has_differences(search_endpoint.MAPPINGS_KEY, source[index], target[index]):
            index_conflicts.add(index)
    identical_indices = set(indices_in_target) - set(index_conflicts)
    indices_to_create = set(source.keys()) - set(indices_in_target)
    # Print report
    print("Indices in target cluster with conflicting settings/mappings: " + utils.string_from_set(index_conflicts))
    print("Indices already created in target cluster (data will NOT be moved): " +
          utils.string_from_set(identical_indices))
    print("Indices to create: " + utils.string_from_set(indices_to_create))
    return indices_to_create


if __name__ == '__main__':
    # Parse logstash config file
    print("\n##### Starting index configuration tool... #####\n")
    logstash_config = parser.parse(sys.argv[1])
    validate_logstash_config(logstash_config)
    # Get input config. The tuple contents are (type, config)
    plugin_config = logstash_config["input"][1]
    # Fetch all indices from source cluster
    source_indices = fetch_all_indices_by_plugin(plugin_config)
    # Fetch all indices from target cluster
    plugin_config = logstash_config["output"][1]
    target_endpoint, target_auth = get_endpoint_info(plugin_config)
    target_indices = search_endpoint.fetch_all_indices(target_endpoint, target_auth)
    # Print report and get index data for indices to be created
    indices_set = print_report(source_indices, target_indices)
    # Create indices
    if indices_set:
        index_data = dict()
        for index_name in indices_set:
            index_data[index_name] = source_indices[index_name]
        search_endpoint.create_indices(index_data, target_endpoint, target_auth)
    print("\n##### Index configuration tool has completed! #####\n")
