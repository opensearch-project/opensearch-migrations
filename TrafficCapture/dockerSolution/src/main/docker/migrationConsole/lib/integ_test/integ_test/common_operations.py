import datetime
import random
import string
import json
import time
from requests.exceptions import ConnectionError, SSLError
from http import HTTPStatus
import shlex
import subprocess
import logging
from console_link.logic.clusters import call_api, connection_check, clear_indices, run_test_benchmarks, ConnectionResult
from console_link.models.cluster import HttpMethod, Cluster

logger = logging.getLogger(__name__)


class ClusterAPIRequestError(Exception):
    pass


def execute_api_call(cluster: Cluster, path: str, method=HttpMethod.GET, data=None, headers=None, timeout=None,
                     desired_status_code: int = 200, max_attempts: int = 5, delay: float = 2.5):
    api_exception = None
    last_received_status = None
    for _ in range(1, max_attempts + 1):
        try:
            response = call_api(cluster=cluster, path=path, method=method, data=data, headers=headers, timeout=timeout)
            if response.status_code == desired_status_code:
                return response
            else:
                # Ensure that our final captured exception is accurate
                api_exception = None
                last_received_status = response.status_code
                logger.debug(f"Status code returned: {response.status_code} did not"
                               f" match the expected status code: {desired_status_code}."
                               f" Trying again in {delay} seconds.")
        except (ConnectionError, SSLError) as e:
            api_exception = e
            logger.debug(f"Received exception: {e}. Unable to connect to server. Please check all containers are up"
                         f" and ports are setup properly. Trying again in {delay} seconds.")
        time.sleep(delay)
    if api_exception is None:
        error_message = (f"Failed to receive desired status code of {desired_status_code} for "
                         f"request: {response.__name__} with path: {path}. Last received status "
                         f"code: {last_received_status}")
    else:
        error_message = f"Unable to connect to server. Underlying exception: {api_exception}"
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
    #headers = {'Content-Type': 'application/json'}
    return execute_api_call(cluster=cluster, method=HttpMethod.GET, path=f"/{index_name}/_doc/{doc_id}",
                            **kwargs)


def delete_document(index_name: str, doc_id: str, cluster: Cluster, **kwargs):
    return execute_api_call(cluster=cluster, method=HttpMethod.DELETE, path=f"/{index_name}/_doc/{doc_id}",
                            **kwargs)


def check_doc_match(test_case, index_name: str, doc_id: str, source_cluster: Cluster, target_cluster: Cluster):
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
