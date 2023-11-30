#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

from typing import Optional

import jsonpath_ng
import requests

from component_template_info import ComponentTemplateInfo
from endpoint_info import EndpointInfo
from index_doc_count import IndexDocCount
from index_template_info import IndexTemplateInfo

# Constants
SETTINGS_KEY = "settings"
MAPPINGS_KEY = "mappings"
ALIASES_KEY = "aliases"
COUNT_KEY = "count"
__INDEX_KEY = "index"
__COMPONENT_TEMPLATE_LIST_KEY = "component_templates"
__INDEX_TEMPLATE_LIST_KEY = "index_templates"
__INDEX_TEMPLATES_PATH = "/_index_template"
__COMPONENT_TEMPLATES_PATH = "/_component_template"
__ALL_INDICES_ENDPOINT = "*"
# (ES 7+) size=0 avoids the "hits" payload to reduce the response size since we're only interested in the aggregation,
# and track_total_hits forces an accurate doc-count
__SEARCH_COUNT_PATH = "/_search"
__SEARCH_COUNT_PAYLOAD = {"size": 0, "track_total_hits": True, "aggs": {"count": {"terms": {"field": "_index"}}}}
__TOTAL_COUNT_JSONPATH = jsonpath_ng.parse("$.hits.total.value")
__INDEX_COUNT_JSONPATH = jsonpath_ng.parse("$.aggregations.count.buckets")
__BUCKET_INDEX_NAME_KEY = "key"
__BUCKET_DOC_COUNT_KEY = "doc_count"
__INTERNAL_SETTINGS_KEYS = ["creation_date", "uuid", "provided_name", "version", "store"]
__TIMEOUT_SECONDS = 10


def __send_get_request(url: str, endpoint: EndpointInfo, payload: Optional[dict] = None) -> requests.Response:
    try:
        resp = requests.get(url, auth=endpoint.get_auth(), verify=endpoint.is_verify_ssl(), json=payload,
                            timeout=__TIMEOUT_SECONDS)
        resp.raise_for_status()
        return resp
    except requests.ConnectionError:
        raise RuntimeError(f"ConnectionError on GET request to cluster endpoint: {endpoint.get_url()}")
    except requests.HTTPError as e:
        raise RuntimeError(f"HTTPError on GET request to cluster endpoint: {endpoint.get_url()} - {e!s}")
    except requests.Timeout:
        # TODO retry mechanism
        raise RuntimeError(f"Timed out on GET request to cluster endpoint: {endpoint.get_url()}")
    except requests.exceptions.RequestException as e:
        raise RuntimeError(f"GET request failure to cluster endpoint: {endpoint.get_url()} - {e!s}")


def fetch_all_indices(endpoint: EndpointInfo) -> dict:
    all_indices_url: str = endpoint.add_path(__ALL_INDICES_ENDPOINT)
    try:
        # raises RuntimeError in case of any request errors
        resp = __send_get_request(all_indices_url, endpoint)
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
    except RuntimeError as e:
        raise RuntimeError(f"Failed to fetch metadata from cluster endpoint: {e!s}")


def create_indices(indices_data: dict, endpoint: EndpointInfo) -> dict:
    failed_indices = dict()
    for index in indices_data:
        index_endpoint = endpoint.add_path(index)
        data_dict = dict()
        data_dict[SETTINGS_KEY] = indices_data[index][SETTINGS_KEY]
        data_dict[MAPPINGS_KEY] = indices_data[index][MAPPINGS_KEY]
        if ALIASES_KEY in indices_data[index] and len(indices_data[index][ALIASES_KEY]) > 0:
            data_dict[ALIASES_KEY] = indices_data[index][ALIASES_KEY]
        try:
            resp = requests.put(index_endpoint, auth=endpoint.get_auth(), verify=endpoint.is_verify_ssl(),
                                json=data_dict, timeout=__TIMEOUT_SECONDS)
            resp.raise_for_status()
        except requests.exceptions.RequestException as e:
            failed_indices[index] = e
    # Loop completed, return failures if any
    return failed_indices


def doc_count(indices: set, endpoint: EndpointInfo) -> IndexDocCount:
    count_endpoint_suffix: str = ','.join(indices) + __SEARCH_COUNT_PATH
    doc_count_endpoint: str = endpoint.add_path(count_endpoint_suffix)
    try:
        # raises RuntimeError in case of any request errors
        resp = __send_get_request(doc_count_endpoint, endpoint, __SEARCH_COUNT_PAYLOAD)
        result = dict(resp.json())
        total: int = __TOTAL_COUNT_JSONPATH.find(result)[0].value
        counts_list: list = __INDEX_COUNT_JSONPATH.find(result)[0].value
        count_map = dict()
        for entry in counts_list:
            count_map[entry[__BUCKET_INDEX_NAME_KEY]] = entry[__BUCKET_DOC_COUNT_KEY]
        return IndexDocCount(total, count_map)
    except RuntimeError as e:
        raise RuntimeError(f"Failed to fetch doc_count: {e!s}")


def __fetch_templates(endpoint: EndpointInfo, path: str, root_key: str, factory) -> set:
    url: str = endpoint.add_path(path)
    # raises RuntimeError in case of any request errors
    resp = __send_get_request(url, endpoint)
    result = set()
    if root_key in resp.json():
        for template in resp.json()[root_key]:
            result.add(factory(template))
    return result


def fetch_all_component_templates(endpoint: EndpointInfo) -> set[ComponentTemplateInfo]:
    try:
        # raises RuntimeError in case of any request errors
        return __fetch_templates(endpoint, __COMPONENT_TEMPLATES_PATH, __COMPONENT_TEMPLATE_LIST_KEY,
                                 lambda t: ComponentTemplateInfo(t))
    except RuntimeError as e:
        raise RuntimeError(f"Failed to fetch component template metadata from cluster endpoint: {e!s}")


def fetch_all_index_templates(endpoint: EndpointInfo) -> set[IndexTemplateInfo]:
    try:
        # raises RuntimeError in case of any request errors
        return __fetch_templates(endpoint, __INDEX_TEMPLATES_PATH, __INDEX_TEMPLATE_LIST_KEY,
                                 lambda t: IndexTemplateInfo(t))
    except RuntimeError as e:
        raise RuntimeError(f"Failed to fetch index template metadata from cluster endpoint: {e!s}")


def __create_templates(templates: set[ComponentTemplateInfo], endpoint: EndpointInfo, template_path: str) -> dict:
    failures = dict()
    for template in templates:
        template_endpoint = endpoint.add_path(template_path + "/" + template.get_name())
        try:
            resp = requests.put(template_endpoint, auth=endpoint.get_auth(), verify=endpoint.is_verify_ssl(),
                                json=template.get_template_definition(), timeout=__TIMEOUT_SECONDS)
            resp.raise_for_status()
        except requests.exceptions.RequestException as e:
            failures[template.get_name()] = e
    # Loop completed, return failures if any
    return failures


def create_component_templates(templates: set[ComponentTemplateInfo], endpoint: EndpointInfo) -> dict:
    return __create_templates(templates, endpoint, __COMPONENT_TEMPLATES_PATH)


def create_index_templates(templates: set[IndexTemplateInfo], endpoint: EndpointInfo) -> dict:
    return __create_templates(templates, endpoint, __INDEX_TEMPLATES_PATH)
