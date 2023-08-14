import sys
from typing import Optional

import requests

# Constants
SETTINGS_KEY = "settings"
MAPPINGS_KEY = "mappings"
__INDEX_KEY = "index"
__ALL_INDICES_ENDPOINT = "*"
__INTERNAL_SETTINGS_KEYS = ["creation_date", "uuid", "provided_name", "version", "store"]


def fetch_all_indices(endpoint: str, optional_auth: Optional[tuple] = None, verify: bool = True) -> dict:
    actual_endpoint = endpoint + __ALL_INDICES_ENDPOINT
    resp = requests.get(actual_endpoint, auth=optional_auth, verify=verify)
    # Remove internal settings
    result = dict(resp.json())
    for index in result:
        for setting in __INTERNAL_SETTINGS_KEYS:
            index_settings = result[index][SETTINGS_KEY]
            if __INDEX_KEY in index_settings:
                index_settings[__INDEX_KEY].pop(setting, None)
    return result


def create_indices(indices_data: dict, endpoint: str, auth_tuple: Optional[tuple]):
    for index in indices_data:
        actual_endpoint = endpoint + index
        data_dict = dict()
        data_dict[SETTINGS_KEY] = indices_data[index][SETTINGS_KEY]
        data_dict[MAPPINGS_KEY] = indices_data[index][MAPPINGS_KEY]
        try:
            resp = requests.put(actual_endpoint, auth=auth_tuple, json=data_dict)
            resp.raise_for_status()
        except requests.exceptions.RequestException as e:
            print(f"Failed to create index [{index}] - {e!s}", file=sys.stderr)
