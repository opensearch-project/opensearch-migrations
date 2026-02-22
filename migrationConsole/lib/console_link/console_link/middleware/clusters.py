import requests.exceptions

from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.snapshot import Snapshot
from dataclasses import dataclass
import logging

logger = logging.getLogger(__name__)


@dataclass
class ConnectionResult:
    connection_message: str
    connection_established: bool
    cluster_version: str


@dataclass
class CallAPIResult:
    http_response: str
    error_message: str


def call_api(cluster: Cluster, path: str, method=HttpMethod.GET, data=None, headers=None, timeout=None,
             session=None, raise_error=False):
    try:
        r = cluster.call_api(path=path, method=method, data=data, headers=headers, timeout=timeout, session=session,
                             raise_error=raise_error)
        return CallAPIResult(http_response=r, error_message=None)
    except requests.exceptions.Timeout:
        timeout_msg = f" after {timeout}s" if timeout else ""
        return CallAPIResult(http_response=None,
                             error_message=f"Error: Request timed out{timeout_msg}. "
                                           f"Use --timeout to specify a longer timeout.")
    except Exception as e:
        logger.debug("Exception occurred when using call_api on cluster: ", exc_info=True)
        return CallAPIResult(http_response=None, error_message=f"Error: Unable to perform cluster command "
                                                               f"with message: {e}")


def cat_indices(cluster: Cluster, refresh=False, as_json=False):
    try:
        if refresh:
            cluster.call_api('/_refresh')
        as_json_suffix = "?format=json" if as_json else "?v=true"
        cat_indices_path = f"/_cat/indices/_all{as_json_suffix}"
        r = cluster.call_api(cat_indices_path)
        return r.json() if as_json else r.content
    except Exception as e:
        logger.debug("Exception occurred when using call_api on cluster: ", exc_info=True)
        return f"Error: Unable to perform cat-indices command with message: {e}"


def connection_check(cluster: Cluster) -> ConnectionResult:
    cluster_details_path = "/"
    caught_exception = None
    r = None
    try:
        r = cluster.call_api(cluster_details_path, timeout=3)
    except Exception as e:
        caught_exception = e
        logging.debug(f"Unable to access cluster: {cluster} with exception: {e}")
    if caught_exception is None:
        response_json = r.json()
        return ConnectionResult(connection_message="Successfully connected!",
                                connection_established=True,
                                cluster_version=response_json['version']['number'])
    else:
        return ConnectionResult(connection_message=f"Unable to connect to cluster with error: {caught_exception}",
                                connection_established=False,
                                cluster_version=None)


def run_test_benchmarks(cluster: Cluster):
    cluster.execute_benchmark_workload(workload="geonames")
    cluster.execute_benchmark_workload(workload="http_logs")
    cluster.execute_benchmark_workload(workload="nested")
    cluster.execute_benchmark_workload(workload="nyc_taxis")


# As a default we exclude system indices and searchguard indices
def clear_indices(cluster: Cluster):
    clear_indices_path = "/*,-.*,-searchguard*,-sg7*,.migrations_working_state*"
    try:
        r = cluster.call_api(clear_indices_path, method=HttpMethod.DELETE, params={"ignore_unavailable": "true"})
        return r.content
    except Exception as e:
        return f"Error encountered when clearing indices: {e}"


def clear_cluster(cluster: Cluster, snapshot: Snapshot = None):
    clear_indices(cluster)
    if snapshot:
        snapshot.delete_all_snapshots()
        snapshot.delete_snapshot_repo()
