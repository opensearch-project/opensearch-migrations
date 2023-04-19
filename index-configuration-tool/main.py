import sys
import requests
import logstash_conf_parser as parser
from jsondiff import diff

# Constants
SUPPORTED_ENDPOINTS = ["opensearch", "elasticsearch"]
HOSTS_KEY = "hosts"
USER_KEY = "user"
PWD_KEY = "password"
SETTINGS_KEY = "settings"
MAPPINGS_KEY = "mappings"
INDEX_KEY = "index"
INTERNAL_SETTINGS_KEYS = ["creation_date", "uuid", "provided_name", "version", "store"]
ALL_INDICES_ENDPOINT = "*"


# Utility method to make a comma-separated string from a set
def string_from_set(s: set) -> str:
    if s:
        return ", ".join(s)
    else:
        return "[]"


# Utility method to get the plugin config from the base data
def get_plugin_config(data: dict, plugin_type: str) -> dict:
    # Tuple contents are (type, config)
    return data[plugin_type][1]


# TODO Only supports basic auth for now
def get_auth(input_data: dict) -> tuple:
    if USER_KEY in input_data and PWD_KEY in input_data:
        return input_data[USER_KEY], input_data[PWD_KEY]


def get_endpoint_info(plugin_config: dict) -> tuple:
    endpoint = "https://" if ("ssl" in plugin_config and plugin_config["ssl"]) else "http://"
    endpoint += plugin_config[HOSTS_KEY][0] if type(plugin_config[HOSTS_KEY]) is list else plugin_config[HOSTS_KEY]
    endpoint += "/"
    return endpoint, get_auth(plugin_config)


def fetch_all_indices_by_plugin(plugin_config: dict) -> dict:
    endpoint, auth_tuple = get_endpoint_info(plugin_config)
    return fetch_all_indices(endpoint, auth_tuple)


def fetch_all_indices(endpoint: str, auth_tuple: tuple) -> dict:
    actual_endpoint = endpoint + ALL_INDICES_ENDPOINT
    resp = requests.get(actual_endpoint, auth=auth_tuple)
    # Remove internal settings
    result = dict(resp.json())
    for index in result:
        for setting in INTERNAL_SETTINGS_KEYS:
            result[index][SETTINGS_KEY][INDEX_KEY].pop(setting, None)
    return result


def validate_logstash_config(config: dict):
    if "input" not in config or "output" not in config:
        raise ValueError("Missing input or output data from Logstash config")
    for plugin_tuple in config["input"], config["output"]:
        input_type = plugin_tuple[0]
        if input_type not in SUPPORTED_ENDPOINTS:
            raise ValueError("Unsupported plugin type: " + input_type)
        plugin_config = plugin_tuple[1]
        if HOSTS_KEY not in plugin_config:
            raise ValueError("No hosts defined for plugin: " + input_type)
        if USER_KEY in plugin_config and PWD_KEY not in plugin_config:
            raise ValueError("Invalid auth configuration (no password for user) for plugin: " + input_type)
        elif PWD_KEY in plugin_config and USER_KEY not in plugin_config:
            raise ValueError("Invalid auth configuration (Password without user) for plugin: " + input_type)


def has_differences(index: str, dict1: dict, dict2: dict, key: str) -> bool:
    if key in dict1[index] and key in dict2[index]:
        data_diff = diff(dict1[index][key], dict2[index][key])
        return bool(data_diff)
    else:
        return True


def print_report(source: dict, target: dict) -> set:
    index_conflicts = set()
    indices_in_target = set(source.keys()) & set(target.keys())
    for index in indices_in_target:
        # Check settings
        if has_differences(index, source, target, SETTINGS_KEY):
            index_conflicts.add(index)
        # Check mappings
        if has_differences(index, source, target, MAPPINGS_KEY):
            index_conflicts.add(index)
    identical_indices = set(indices_in_target) - set(index_conflicts)
    indices_to_create = set(source.keys()) - set(indices_in_target)
    # Print report
    print("Indices in target cluster with conflicting settings/mappings: " + string_from_set(index_conflicts))
    print("Indices already created in target cluster (data will NOT be moved): " + string_from_set(identical_indices))
    print("Indices to create: " + string_from_set(indices_to_create))
    return indices_to_create


def create_indices(indices: dict, endpoint: str, auth_tuple: tuple):
    for index in indices:
        actual_endpoint = endpoint + index
        data_dict = dict()
        data_dict[SETTINGS_KEY] = indices[index][SETTINGS_KEY]
        data_dict[MAPPINGS_KEY] = indices[index][MAPPINGS_KEY]
        try:
            resp = requests.put(actual_endpoint, auth=auth_tuple, json=data_dict)
            resp.raise_for_status()
        except requests.exceptions.RequestException as e:
            print("Failed to create index [" + index + "] - " + str(e), file=sys.stderr)


if __name__ == '__main__':
    # Parse logstash config file
    print("\n##### Starting index configuration tool... #####\n")
    logstash = parser.parse(sys.argv[1])
    validate_logstash_config(logstash)
    # Fetch all indices from source cluster
    source_indices = fetch_all_indices_by_plugin(get_plugin_config(logstash, "input"))
    # Fetch all indices from target cluster
    target_endpoint, target_auth = get_endpoint_info(get_plugin_config(logstash, "output"))
    target_indices = fetch_all_indices(target_endpoint, target_auth)
    # Print report and get index data for indices to be created
    indices_set = print_report(source_indices, target_indices)
    # Create indices
    if indices_set:
        index_data = dict()
        for index_name in indices_set:
            index_data[index_name] = source_indices[index_name]
        create_indices(index_data, target_endpoint, target_auth)
    print("\n##### Index configuration tool has completed! #####\n")
