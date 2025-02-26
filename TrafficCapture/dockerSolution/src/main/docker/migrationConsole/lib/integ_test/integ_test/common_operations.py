import datetime
import random
import string
import json
import time
import logging
from requests.exceptions import ConnectionError, SSLError
from typing import Dict, List
from unittest import TestCase
from console_link.middleware.clusters import call_api
from console_link.models.cluster import HttpMethod, Cluster
from console_link.models.replayer_base import Replayer, ReplayStatus

logger = logging.getLogger(__name__)

DEFAULT_INDEX_IGNORE_LIST = ["test_", ".", "searchguard", "sg7", "security-auditlog", "reindexed-logs"]

EXPECTED_BENCHMARK_DOCS = {
    "geonames": {"count": 1000},
    "logs-221998": {"count": 1000},
    "logs-211998": {"count": 1000},
    "logs-231998": {"count": 1000},
    "logs-241998": {"count": 1000},
    "logs-181998": {"count": 1000},
    "logs-201998": {"count": 1000},
    "logs-191998": {"count": 1000},
    "sonested": {"count": 1000},
    "nyc_taxis": {"count": 1000}
}


class ClusterAPIRequestError(Exception):
    pass


class ReplayerNotActiveError(Exception):
    pass


def execute_api_call(cluster: Cluster, path: str, method=HttpMethod.GET, data=None, headers=None, timeout=None,
                     session=None, expected_status_code: int = 200, max_attempts: int = 10, delay: float = 2.5,
                     test_case=None):
    api_exception = None
    last_received_status = None
    last_response = None
    for _ in range(1, max_attempts + 1):
        try:
            response = call_api(cluster=cluster, path=path, method=method, data=data, headers=headers, timeout=timeout,
                                session=session, raise_error=False)
            last_response = response
            if response.status_code == expected_status_code:
                break
            else:
                # Ensure that our final captured exception is accurate
                api_exception = None
                last_received_status = response.status_code
                logger.debug(f"Status code returned: {response.status_code} did not"
                             f" match the expected status code: {expected_status_code}."
                             f" Trying again in {delay} seconds.")
        except (ConnectionError, SSLError) as e:
            last_response = None
            api_exception = e
            logger.debug(f"Received exception: {e}. Unable to connect to server. Please check all containers are up"
                         f" and ports are setup properly. Trying again in {delay} seconds.")
        time.sleep(delay)

    if api_exception:
        error_message = f"Unable to connect to server. Underlying exception: {api_exception}"
        raise ClusterAPIRequestError(error_message)
    else:
        error_message = (f"Failed to receive desired status code of {expected_status_code} and instead "
                         f"received {last_received_status} for request: {method.name} {path}")
        if test_case is not None:
            test_case.assertEqual(expected_status_code, last_response.status_code, error_message)
        elif expected_status_code != last_response.status_code:
            raise ClusterAPIRequestError(error_message)
    return last_response


def create_index(index_name: str, cluster: Cluster, **kwargs):
    headers = {'Content-Type': 'application/json'}
    return execute_api_call(cluster=cluster, method=HttpMethod.PUT, path=f"/{index_name}",
                            headers=headers, **kwargs)


def get_index(index_name: str, cluster: Cluster, **kwargs):
    return execute_api_call(cluster=cluster, method=HttpMethod.GET, path=f"/{index_name}",
                            **kwargs)


def delete_index(index_name: str, cluster: Cluster, **kwargs):
    return execute_api_call(cluster=cluster, method=HttpMethod.DELETE, path=f"/{index_name}",
                            **kwargs)


def create_document(index_name: str, doc_id: str, cluster: Cluster, data: dict = None, **kwargs):
    if data is None:
        data = {
            'title': 'Test Document',
            'content': 'This is a sample document for testing OpenSearch.'
        }
    headers = {'Content-Type': 'application/json'}
    return execute_api_call(cluster=cluster, method=HttpMethod.PUT, path=f"/{index_name}/_doc/{doc_id}",
                            data=json.dumps(data), headers=headers, **kwargs)


def get_document(index_name: str, doc_id: str, cluster: Cluster, **kwargs):
    return execute_api_call(cluster=cluster, method=HttpMethod.GET, path=f"/{index_name}/_doc/{doc_id}",
                            **kwargs)


def delete_document(index_name: str, doc_id: str, cluster: Cluster, **kwargs):
    return execute_api_call(cluster=cluster, method=HttpMethod.DELETE, path=f"/{index_name}/_doc/{doc_id}",
                            **kwargs)


def index_matches_ignored_index(index_name: str, index_prefix_ignore_list: List[str]):
    for prefix in index_prefix_ignore_list:
        if index_name.startswith(prefix):
            return True
    return False


def get_all_index_details(cluster: Cluster, index_prefix_ignore_list=None, **kwargs) -> Dict[str, Dict[str, str]]:
    all_index_details = execute_api_call(cluster=cluster, path="/_cat/indices?format=json", **kwargs).json()
    index_dict = {}
    for index_details in all_index_details:
        # While cat/indices returns a doc count metric, the underlying implementation bleeds through details, only
        # capture the index name and make a separate api call for the doc count
        index_name = index_details['index']
        valid_index = not index_matches_ignored_index(index_name,
                                                      index_prefix_ignore_list=index_prefix_ignore_list)
        if index_prefix_ignore_list is None or valid_index:
            # "To get an accurate count of Elasticsearch documents, use the cat count or count APIs."
            # See https://www.elastic.co/guide/en/elasticsearch/reference/7.10/cat-indices.html

            count_response = execute_api_call(cluster=cluster, path=f"/{index_name}/_count?format=json", **kwargs)
            index_dict[index_name] = count_response.json()
            index_dict[index_name]['index'] = index_name
    return index_dict


def check_doc_counts_match(cluster: Cluster,
                           expected_index_details: Dict[str, Dict[str, str]],
                           test_case: TestCase,
                           index_prefix_ignore_list=None,
                           max_attempts: int = 5,
                           delay: float = 2.5):
    if index_prefix_ignore_list is None:
        index_prefix_ignore_list = DEFAULT_INDEX_IGNORE_LIST

    error_message = ""
    for attempt in range(1, max_attempts + 1):
        # Refresh documents
        execute_api_call(cluster=cluster, path="/_refresh")
        actual_index_details = get_all_index_details(cluster=cluster, index_prefix_ignore_list=index_prefix_ignore_list)
        logger.debug(f"Received actual indices: {actual_index_details}")
        if actual_index_details.keys() != expected_index_details.keys():
            error_message = (f"Indices are different: \n Expected: {expected_index_details.keys()} \n "
                             f"Actual: {actual_index_details.keys()}")
            logger.debug(f"Error on attempt {attempt}: {error_message}")
        else:
            for index_details in actual_index_details.values():
                index_name = index_details['index']
                actual_doc_count = index_details['count']
                expected_doc_count = expected_index_details[index_name]['count']
                if actual_doc_count != expected_doc_count:
                    error_message = (f"Index {index_name} has {actual_doc_count} documents but {expected_doc_count} "
                                     f"were expected")
                    logger.debug(f"Error on attempt {attempt}: {error_message}")
                    break
        if not error_message:
            return True
        if attempt != max_attempts:
            error_message = ""
            time.sleep(delay)
    test_case.fail(error_message)


def check_doc_match(test_case: TestCase, index_name: str, doc_id: str, source_cluster: Cluster,
                    target_cluster: Cluster):
    source_response = get_document(index_name=index_name, doc_id=doc_id, cluster=source_cluster)
    target_response = get_document(index_name=index_name, doc_id=doc_id, cluster=target_cluster)

    source_document = source_response.json()
    source_content = source_document['_source']
    target_document = target_response.json()
    target_content = target_document['_source']
    test_case.assertEqual(source_content, target_content)


def generate_large_doc(size_mib):
    # Calculate number of characters needed (1 char = 1 byte)
    num_chars = size_mib * 1024 * 1024

    # Generate random string of the desired length
    large_string = ''.join(random.choices(string.ascii_letters + string.digits, k=num_chars))

    return {
        "timestamp": datetime.datetime.now().isoformat(),
        "large_field": large_string
    }


def wait_for_running_replayer(replayer: Replayer,
                              test_case: TestCase = None,
                              max_attempts: int = 25,
                              delay: float = 3.0):
    error_message = ""
    for attempt in range(1, max_attempts + 1):
        cmd_result = replayer.get_status()
        status = cmd_result.value[0]
        logger.debug(f"Received status {status} for Replayer on attempt {attempt}")
        if status == ReplayStatus.RUNNING:
            return
        error_message = (f"Received replayer status of {status} but expecting to receive: {ReplayStatus.RUNNING} "
                         f"after {max_attempts} attempts")
        if attempt != max_attempts:
            error_message = ""
            time.sleep(delay)
    if test_case:
        test_case.fail(error_message)
    else:
        raise ReplayerNotActiveError(error_message)


def convert_transformations_to_str(transform_list: List[Dict]) -> str:
    return json.dumps(transform_list)


def get_index_name_transformation(existing_index_name: str, target_index_name: str,
                                  source_major_version: int, source_minor_version: int) -> Dict:
    return {
        "TypeMappingSanitizationTransformerProvider": {
            "staticMappings": {
                f"{existing_index_name}": {
                    "_doc": f"{target_index_name}"
                }
            },
            "sourceProperties": {
                "version": {
                    "major": source_major_version,
                    "minor": source_minor_version
                }
            }

        }
    }
