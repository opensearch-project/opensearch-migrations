import jsonpath_ng
import requests

from endpoint_info import EndpointInfo

# Constants
from index_doc_count import IndexDocCount

SETTINGS_KEY = "settings"
MAPPINGS_KEY = "mappings"
COUNT_KEY = "count"
__INDEX_KEY = "index"
__ALL_INDICES_ENDPOINT = "*"
__SEARCH_COUNT_PATH = "/_search?size=0"
__SEARCH_COUNT_PAYLOAD = {"aggs": {"count": {"terms": {"field": "_index"}}}}
__TOTAL_COUNT_JSONPATH = jsonpath_ng.parse("$.hits.total.value")
__INDEX_COUNT_JSONPATH = jsonpath_ng.parse("$.aggregations.count.buckets")
__BUCKET_INDEX_NAME_KEY = "key"
__BUCKET_DOC_COUNT_KEY = "doc_count"
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


def doc_count(indices: set, endpoint: EndpointInfo) -> IndexDocCount:
    count_endpoint_suffix: str = ','.join(indices) + __SEARCH_COUNT_PATH
    doc_count_endpoint: str = endpoint.add_path(count_endpoint_suffix)
    resp = requests.get(doc_count_endpoint, auth=endpoint.get_auth(), verify=endpoint.is_verify_ssl(),
                        json=__SEARCH_COUNT_PAYLOAD)
    # TODO Handle resp.status_code for non successful requests
    result = dict(resp.json())
    total: int = __TOTAL_COUNT_JSONPATH.find(result)[0].value
    counts_list: list = __INDEX_COUNT_JSONPATH.find(result)[0].value
    count_map = dict()
    for entry in counts_list:
        count_map[entry[__BUCKET_INDEX_NAME_KEY]] = entry[__BUCKET_DOC_COUNT_KEY]
    return IndexDocCount(total, count_map)
