import time
import logging
from requests.exceptions import ConnectionError, SSLError
from console_link.middleware.clusters import call_api
from console_link.models.cluster import HttpMethod, Cluster

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


class ServiceStatusError(Exception):
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


def wait_for_service_status(status_func, desired_status, max_attempts: int = 25, delay: float = 3.0):
    error_message = ""
    for attempt in range(1, max_attempts + 1):
        cmd_result = status_func()
        status = cmd_result.value[0]
        logger.debug(f"Received status {status} on attempt {attempt}")
        if status == desired_status:
            return
        error_message = (f"Received status of {status} but expecting to receive: {desired_status} "
                         f"after {max_attempts} attempts")
        if attempt != max_attempts:
            error_message = ""
            time.sleep(delay)
    raise ServiceStatusError(error_message)
