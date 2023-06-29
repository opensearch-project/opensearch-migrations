import sys
from typing import Optional

import requests

# Constants
SETTINGS_KEY = "settings"
MAPPINGS_KEY = "mappings"
__INDEX_KEY = "index"
__ALL_INDICES_ENDPOINT = "*"
__INTERNAL_SETTINGS_KEYS = ["creation_date", "uuid", "provided_name", "version", "store"]
__DYNAMIC_KEY = "dynamic"
__STRICT_VALUE = "strict"


def fetch_all_indices(endpoint: str, optional_auth: Optional[tuple] = None, verify: bool = True) -> dict:
    actual_endpoint = endpoint + __ALL_INDICES_ENDPOINT
    resp = requests.get(actual_endpoint, auth=optional_auth, verify=verify)
    # Remove internal settings
    result = dict(resp.json())
    for index in result:
        # TODO Remove after https://github.com/opensearch-project/data-prepper/issues/2864 is resolved
        try:
            dynamic_mapping_value = result[index][MAPPINGS_KEY][__DYNAMIC_KEY]
            if dynamic_mapping_value == __STRICT_VALUE:
                del result[index][MAPPINGS_KEY][__DYNAMIC_KEY]
        except KeyError:
            # One of the keys in the path above is not present, so we can
            # ignore the deletion logic and execute the rest
            pass
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
