from console_link.models.cluster import Cluster, HttpMethod
import logging

logger = logging.getLogger(__name__)


def call_api(cluster: Cluster, path: str, method=HttpMethod.GET, data=None, headers=None, timeout=None):
    r = cluster.call_api(path=path, method=method, data=data, headers=headers, timeout=timeout)
    return r


def cat_indices(cluster: Cluster, as_json=False):
    as_json_suffix = "?format=json" if as_json else "?v"
    cat_indices_path = f"/_cat/indices{as_json_suffix}"
    r = cluster.call_api(cat_indices_path)
    return r.json() if as_json else r.content


def connection_check(cluster: Cluster):
    cluster_details_path = "/"
    caught_exception = None
    r = None
    try:
        r = cluster.call_api(cluster_details_path, timeout=3)
    except Exception as e:
        caught_exception = e
        logging.debug(f"Unable to access cluster: {cluster} with exception: {e}")
    access_result = {}
    if caught_exception is None:
        response_json = r.json()
        access_result['connection_message'] = "Successfully connected!"
        access_result['cluster_version'] = response_json['version']['number']
        access_result['connection_established'] = True
    else:
        access_result['connection_message'] = f"Unable to connect to cluster with error: {caught_exception}"
        access_result['connection_established'] = False
    return access_result


def run_test_benchmarks(cluster: Cluster):
    cluster.execute_benchmark_workload(workload="geonames")
    cluster.execute_benchmark_workload(workload="http_logs")
    cluster.execute_benchmark_workload(workload="nested")
    cluster.execute_benchmark_workload(workload="nyc_taxis")


# As a default we exclude system indices and searchguard indices
def clear_indices(cluster: Cluster):
    clear_indices_path = "/*,-.*,-searchguard*,-sg7*"
    r = cluster.call_api(clear_indices_path, method=HttpMethod.DELETE)
    return r.content
