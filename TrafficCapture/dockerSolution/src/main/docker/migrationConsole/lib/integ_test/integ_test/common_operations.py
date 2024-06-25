import datetime
import random
import string
import json
import time
import shlex
import subprocess
import logging
from requests.exceptions import ConnectionError, SSLError
from typing import Dict, List
from unittest import TestCase
from console_link.logic.clusters import call_api
from console_link.models.cluster import HttpMethod, Cluster

logger = logging.getLogger(__name__)

DEFAULT_INDEX_IGNORE_LIST = ["test_", ".", "searchguard", "sg7", "security-auditlog"]


class ClusterAPIRequestError(Exception):
    pass


def execute_api_call(cluster: Cluster, path: str, method=HttpMethod.GET, data=None, headers=None, timeout=None,
                     session=None, expected_status_code: int = 200, max_attempts: int = 5, delay: float = 2.5,
                     test_case=None):
    api_exception = None
    last_received_status = None
    for _ in range(1, max_attempts + 1):
        try:
            response = call_api(cluster=cluster, path=path, method=method, data=data, headers=headers, timeout=timeout,
                                session=session, raise_error=False)
            if response.status_code == expected_status_code:
                return response
            else:
                # Ensure that our final captured exception is accurate
                api_exception = None
                last_received_status = response.status_code
                logger.debug(f"Status code returned: {response.status_code} did not"
                             f" match the expected status code: {expected_status_code}."
                             f" Trying again in {delay} seconds.")
        except (ConnectionError, SSLError) as e:
            api_exception = e
            logger.debug(f"Received exception: {e}. Unable to connect to server. Please check all containers are up"
                         f" and ports are setup properly. Trying again in {delay} seconds.")
        time.sleep(delay)
    if api_exception is None:
        error_message = (f"Failed to receive desired status code of {expected_status_code} and instead "
                         f"received {last_received_status} for request: {method.name} {path}")
    else:
        error_message = f"Unable to connect to server. Underlying exception: {api_exception}"
    if test_case is not None:
        test_case.fail(f"Cluster API request error: {error_message}")
    else:
        raise ClusterAPIRequestError(error_message)


def create_index(index_name: str, cluster: Cluster, **kwargs):
    return execute_api_call(cluster=cluster, method=HttpMethod.PUT, path=f"/{index_name}",
                            **kwargs)


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
    # headers = {'Content-Type': 'application/json'}
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


def get_all_index_details(cluster: Cluster, **kwargs) -> Dict[str, Dict[str, str]]:
    all_index_details = execute_api_call(cluster=cluster, path="/_cat/indices?format=json", **kwargs).json()
    index_dict = {}
    for index_details in all_index_details:
        index_dict[index_details['index']] = index_details
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
        actual_index_details = get_all_index_details(cluster=cluster)
        logger.debug(f"Received actual indices: {actual_index_details}")
        for index_details in actual_index_details.values():
            index_name = index_details['index']
            # Skip index if matching prefix
            if index_matches_ignored_index(index_name=index_name, index_prefix_ignore_list=index_prefix_ignore_list):
                continue
            if expected_index_details[index_name] is None:
                error_message = (f"Actual index {index_name} does not exist in expected "
                                 f"indices: {expected_index_details.keys()}")
                logger.debug(f"Error on attempt {attempt}: {error_message}")
                break
            actual_doc_count = index_details['docs.count']
            expected_doc_count = expected_index_details[index_name]['docs.count']
            if actual_doc_count != expected_doc_count:
                error_message = (f"Index {index_name} has {actual_doc_count} documents but {expected_doc_count} were "
                                 f"expected")
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


class ContainerNotFoundError(Exception):
    def __init__(self, container_filter):
        super().__init__(f"No containers matching the filter '{container_filter}' were found.")


# Not currently used, but keeping this command as potentially useful in future
def run_migration_console_command(deployment_type: str, command: str):
    if deployment_type == "local":
        filter_criteria = 'name=\"migration-console\"'
        cmd = f'docker ps --format=\"{{{{.ID}}}}\" --filter {filter_criteria}'

        get_container_process = subprocess.run(shlex.split(cmd), stdout=subprocess.PIPE, text=True)
        container_id = get_container_process.stdout.strip().replace('"', '')

        if container_id:
            cmd_exec = f"docker exec {container_id} bash -c '{command}'"
            logger.warning(f"Running command: {cmd_exec} on container {container_id}")
            process = subprocess.run(cmd_exec, shell=True, capture_output=True, text=True)
            return process.returncode, process.stdout, process.stderr
        else:
            raise ContainerNotFoundError(filter_criteria)

    else:
        # In a cloud deployment case, we run the e2e tests directly on the migration console, so it's just a local call
        logger.warning(f"Running command: {command} locally")
        process = subprocess.run(command, shell=True, capture_output=True)
        return process.returncode, process.stdout, process.stderr
