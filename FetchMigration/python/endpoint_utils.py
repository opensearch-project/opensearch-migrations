from typing import Optional

from endpoint_info import EndpointInfo

# Constants
SOURCE_KEY = "source"
SINK_KEY = "sink"
SUPPORTED_PLUGINS = ["opensearch", "elasticsearch"]
HOSTS_KEY = "hosts"
INSECURE_KEY = "insecure"
CONNECTION_KEY = "connection"
DISABLE_AUTH_KEY = "disable_authentication"
USER_KEY = "username"
PWD_KEY = "password"


def __check_supported_endpoint(config: dict) -> Optional[tuple]:
    for supported_type in SUPPORTED_PLUGINS:
        if supported_type in config:
            return supported_type, config[supported_type]


# This config key may be either directly in the main dict (for sink)
# or inside a nested dict (for source). The default value is False.
def is_insecure(config: dict) -> bool:
    if INSECURE_KEY in config:
        return config[INSECURE_KEY]
    elif CONNECTION_KEY in config and INSECURE_KEY in config[CONNECTION_KEY]:
        return config[CONNECTION_KEY][INSECURE_KEY]
    return False


def validate_pipeline(pipeline: dict):
    if SOURCE_KEY not in pipeline:
        raise ValueError("Missing source configuration in Data Prepper pipeline YAML")
    if SINK_KEY not in pipeline:
        raise ValueError("Missing sink configuration in Data Prepper pipeline YAML")


def validate_auth(plugin_name: str, config: dict):
    # Check if auth is disabled. If so, no further validation is required
    if config.get(DISABLE_AUTH_KEY, False):
        return
    # TODO AWS / SigV4
    elif USER_KEY not in config:
        raise ValueError("Invalid auth configuration (no username) for plugin: " + plugin_name)
    elif PWD_KEY not in config:
        raise ValueError("Invalid auth configuration (no password for username) for plugin: " + plugin_name)


def get_supported_endpoint_config(pipeline_config: dict, section_key: str) -> tuple:
    # The value of each key may be a single plugin (as a dict) or a list of plugin configs
    supported_tuple = tuple()
    if type(pipeline_config[section_key]) is dict:
        supported_tuple = __check_supported_endpoint(pipeline_config[section_key])
    elif type(pipeline_config[section_key]) is list:
        for entry in pipeline_config[section_key]:
            supported_tuple = __check_supported_endpoint(entry)
            # Break out of the loop at the first supported type
            if supported_tuple:
                break
    if not supported_tuple:
        raise ValueError("Could not find any supported endpoints in section: " + section_key)
    # First tuple value is the plugin name, second value is the plugin config dict
    return supported_tuple[0], supported_tuple[1]


# TODO Only supports basic auth for now
def get_auth(input_data: dict) -> Optional[tuple]:
    if not input_data.get(DISABLE_AUTH_KEY, False) and USER_KEY in input_data and PWD_KEY in input_data:
        return input_data[USER_KEY], input_data[PWD_KEY]


def get_endpoint_info_from_plugin_config(plugin_config: dict) -> EndpointInfo:
    # "hosts" can be a simple string, or an array of hosts for Logstash to hit.
    # This tool needs one accessible host, so we pick the first entry in the latter case.
    url = plugin_config[HOSTS_KEY][0] if type(plugin_config[HOSTS_KEY]) is list else plugin_config[HOSTS_KEY]
    # verify boolean will be the inverse of the insecure SSL key, if present
    should_verify = not is_insecure(plugin_config)
    return EndpointInfo(url, get_auth(plugin_config), should_verify)


def get_endpoint_info_from_pipeline_config(pipeline_config: dict, section_key: str) -> EndpointInfo:

    # Raises a ValueError if no supported endpoints are found
    plugin_name, plugin_config = get_supported_endpoint_config(pipeline_config, section_key)
    if HOSTS_KEY not in plugin_config:
        raise ValueError("No hosts defined for plugin: " + plugin_name)
    # Raises a ValueError if there an error in the auth configuration
    validate_auth(plugin_name, plugin_config)
    return get_endpoint_info_from_plugin_config(plugin_config)
