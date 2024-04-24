#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

import re
from typing import Optional, Union

from requests_aws4auth import AWS4Auth
from botocore.session import Session

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
AWS_SIGV4_KEY = "aws_sigv4"
AWS_REGION_KEY = "aws_region"
AWS_CONFIG_KEY = "aws"
AWS_CONFIG_REGION_KEY = "region"
IS_SERVERLESS_KEY = "serverless"
ES_SERVICE_NAME = "es"
AOSS_SERVICE_NAME = "aoss"
URL_REGION_PATTERN = re.compile(r"([\w-]*)\.(es|aoss)\.amazonaws\.com")


def __get_url(plugin_config: dict) -> str:
    # "hosts" can be a simple string, or an array of hosts for Logstash to hit.
    # This tool needs one accessible host, so we pick the first entry in the latter case.
    return plugin_config[HOSTS_KEY][0] if isinstance(plugin_config[HOSTS_KEY], list) else plugin_config[HOSTS_KEY]


# Helper function that attempts to extract the AWS region from a URL,
# assuming it is of the form *.<region>.<service>.amazonaws.com
def __derive_aws_region_from_url(url: str) -> Optional[str]:
    match = URL_REGION_PATTERN.search(url)
    if match:
        # Index 0 returns the entire match, index 1 returns only the first group
        return match.group(1)
    return None


def get_aws_region(plugin_config: dict) -> str:
    if plugin_config.get(AWS_SIGV4_KEY, False) and plugin_config.get(AWS_REGION_KEY, None) is not None:
        return plugin_config[AWS_REGION_KEY]
    elif plugin_config.get(AWS_CONFIG_KEY, None) is not None:
        aws_config = plugin_config[AWS_CONFIG_KEY]
        if not isinstance(aws_config, dict):
            raise ValueError("Unexpected value for 'aws' configuration")
        elif aws_config.get(AWS_CONFIG_REGION_KEY, None) is not None:
            return aws_config[AWS_CONFIG_REGION_KEY]
    # Region not explicitly defined, attempt to derive from URL
    derived_region = __derive_aws_region_from_url(__get_url(plugin_config))
    if derived_region is None:
        raise ValueError("No region configured for AWS SigV4 auth, or derivable from host URL")
    return derived_region


def __check_supported_endpoint(section_config: dict) -> Optional[tuple]:
    for supported_type in SUPPORTED_PLUGINS:
        if supported_type in section_config:
            return supported_type, section_config[supported_type]


# This config key may be either directly in the main dict (for sink)
# or inside a nested dict (for source). The default value is False.
def is_insecure(plugin_config: dict) -> bool:
    if INSECURE_KEY in plugin_config:
        return plugin_config[INSECURE_KEY]
    elif CONNECTION_KEY in plugin_config and INSECURE_KEY in plugin_config[CONNECTION_KEY]:
        return plugin_config[CONNECTION_KEY][INSECURE_KEY]
    return False


def validate_pipeline(pipeline: dict):
    if SOURCE_KEY not in pipeline:
        raise ValueError("Missing source configuration in Data Prepper pipeline YAML")
    if SINK_KEY not in pipeline:
        raise ValueError("Missing sink configuration in Data Prepper pipeline YAML")


def validate_auth(plugin_name: str, plugin_config: dict):
    # If auth is disabled, no further validation is required
    if plugin_config.get(DISABLE_AUTH_KEY, False):
        return
    # If AWS SigV4 is configured, validate region
    if plugin_config.get(AWS_SIGV4_KEY, False) or AWS_CONFIG_KEY in plugin_config:
        # Raises a ValueError if region cannot be derived
        get_aws_region(plugin_config)
    # Validate basic auth
    elif USER_KEY not in plugin_config:
        raise ValueError("Invalid auth configuration (no username) for plugin: " + plugin_name)
    elif PWD_KEY not in plugin_config:
        raise ValueError("Invalid auth configuration (no password for username) for plugin: " + plugin_name)


def get_supported_endpoint_config(pipeline_config: dict, section_key: str) -> tuple:
    # The value of each key may be a single plugin (as a dict) or a list of plugin configs
    supported_tuple = tuple()
    if isinstance(pipeline_config[section_key], dict):
        supported_tuple = __check_supported_endpoint(pipeline_config[section_key])
    elif isinstance(pipeline_config[section_key], list):
        for entry in pipeline_config[section_key]:
            supported_tuple = __check_supported_endpoint(entry)
            # Break out of the loop at the first supported type
            if supported_tuple:
                break
    if not supported_tuple:
        raise ValueError("Could not find any supported endpoints in section: " + section_key)
    # First tuple value is the plugin name, second value is the plugin config dict
    return supported_tuple[0], supported_tuple[1]


def get_aws_sigv4_auth(region: str, is_serverless: bool = False) -> AWS4Auth:
    credentials = Session().get_credentials()
    if not credentials:
        raise ValueError("Unable to fetch AWS session credentials for SigV4 auth")
    if is_serverless:
        return AWS4Auth(region=region, service=AOSS_SERVICE_NAME, refreshable_credentials=credentials)
    else:
        return AWS4Auth(region=region, service=ES_SERVICE_NAME, refreshable_credentials=credentials)


def get_auth(plugin_config: dict) -> Union[AWS4Auth, tuple, None]:
    # Basic auth
    if USER_KEY in plugin_config and PWD_KEY in plugin_config:
        return plugin_config[USER_KEY], plugin_config[PWD_KEY]
    elif plugin_config.get(AWS_SIGV4_KEY, False) or AWS_CONFIG_KEY in plugin_config:
        is_serverless = False
        # OpenSearch Serverless uses a different service name
        if AWS_CONFIG_KEY in plugin_config:
            aws_config = plugin_config[AWS_CONFIG_KEY]
            if isinstance(aws_config, dict) and aws_config.get(IS_SERVERLESS_KEY, False):
                is_serverless = True
        region = get_aws_region(plugin_config)
        return get_aws_sigv4_auth(region, is_serverless)
    return None


def get_endpoint_info_from_plugin_config(plugin_config: dict) -> EndpointInfo:
    # verify boolean will be the inverse of the insecure SSL key, if present
    should_verify = not is_insecure(plugin_config)
    return EndpointInfo(__get_url(plugin_config), get_auth(plugin_config), should_verify)


def get_endpoint_info_from_pipeline_config(pipeline_config: dict, section_key: str) -> EndpointInfo:
    # Raises a ValueError if no supported endpoints are found
    plugin_name, plugin_config = get_supported_endpoint_config(pipeline_config, section_key)
    if HOSTS_KEY not in plugin_config:
        raise ValueError("No hosts defined for plugin: " + plugin_name)
    # Raises a ValueError if there an error in the auth configuration
    validate_auth(plugin_name, plugin_config)
    return get_endpoint_info_from_plugin_config(plugin_config)
