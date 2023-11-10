import requests

from endpoint_info import EndpointInfo

# Constants
SETTINGS_KEY = "settings"
MAPPINGS_KEY = "mappings"
COUNT_KEY = "count"
__INDEX_KEY = "index"
__ALL_INDICES_ENDPOINT = "*"
__COUNT_ENDPOINT = "/_count"
__INTERNAL_SETTINGS_KEYS = ["creation_date", "uuid", "provided_name", "version", "store"]


def fetch_all_indices(endpoint: EndpointInfo) -> dict:
    all_indices_url: str = endpoint.add_path(__ALL_INDICES_ENDPOINT)
    resp = requests.get(all_indices_url, auth=endpoint.get_auth(), verify=endpoint.is_verify_ssl())
    result = dict(resp.json())
    for index in list(result.keys()):
        # Remove system indices
        if index.startswith("."):
            del result[index]
        # Remove internal settings
        else:
            for setting in __INTERNAL_SETTINGS_KEYS:
                index_settings = result[index][SETTINGS_KEY]
                if __INDEX_KEY in index_settings:
                    index_settings[__INDEX_KEY].pop(setting, None)
    return result


def create_indices(indices_data: dict, endpoint: EndpointInfo):
    for index in indices_data:
        index_endpoint = endpoint.add_path(index)
        data_dict = dict()
        data_dict[SETTINGS_KEY] = indices_data[index][SETTINGS_KEY]
        data_dict[MAPPINGS_KEY] = indices_data[index][MAPPINGS_KEY]
        try:
            resp = requests.put(index_endpoint, auth=endpoint.get_auth(), verify=endpoint.is_verify_ssl(),
                                json=data_dict)
            resp.raise_for_status()
        except requests.exceptions.RequestException as e:
            raise RuntimeError(f"Failed to create index [{index}] - {e!s}")


def doc_count(indices: set, endpoint: EndpointInfo) -> int:
    count_endpoint_suffix: str = ','.join(indices) + __COUNT_ENDPOINT
    doc_count_endpoint: str = endpoint.add_path(count_endpoint_suffix)
    resp = requests.get(doc_count_endpoint, auth=endpoint.get_auth(), verify=endpoint.is_verify_ssl())
    result = dict(resp.json())
    return int(result[COUNT_KEY])
