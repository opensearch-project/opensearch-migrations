import os
import requests.exceptions
import subprocess

from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.snapshot import Snapshot
from dataclasses import dataclass
import logging

logger = logging.getLogger(__name__)


@dataclass
class ConnectionResult:
    connection_message: str
    connection_established: bool
    cluster_version: str = None

    def __repr__(self):
        parts = [f"connection_message='{self.connection_message}'",
                 f"connection_established={self.connection_established}"]
        if self.cluster_version is not None:
            parts.append(f"cluster_version='{self.cluster_version}'")
        return f"ConnectionResult({', '.join(parts)})"


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
        if refresh and not cluster.is_serverless:
            cluster.call_api('/_refresh')
        as_json_suffix = "?format=json" if as_json else "?v=true"
        if cluster.is_serverless:
            cat_indices_path = f"/_cat/indices{as_json_suffix}"
        else:
            cat_indices_path = f"/_cat/indices/_all{as_json_suffix}"
        r = cluster.call_api(cat_indices_path)
        return r.json() if as_json else r.content
    except Exception as e:
        logger.debug("Exception occurred when using call_api on cluster: ", exc_info=True)
        return f"Error: Unable to perform cat-indices command with message: {e}"


def connection_check(cluster: Cluster) -> ConnectionResult:
    if cluster.is_serverless:
        try:
            cluster.call_api("/_cat/indices", timeout=3)
            return ConnectionResult(connection_message="Successfully connected to serverless collection!",
                                    connection_established=True)
        except Exception as e:
            logger.debug(f"Unable to access AOSS cluster: {cluster} with exception: {e}")
            return ConnectionResult(connection_message=f"Unable to connect to cluster with error: {e}",
                                    connection_established=False)

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
                                connection_established=False)


def run_test_benchmarks(cluster: Cluster):
    cluster.execute_benchmark_workload(workload="geonames")
    cluster.execute_benchmark_workload(workload="http_logs")
    cluster.execute_benchmark_workload(workload="nested")
    cluster.execute_benchmark_workload(workload="nyc_taxis")


def _ensure_vectorsearch_workload():
    """Ensure the vectorsearch workload is available in the OSB workload cache."""
    workload_dir = os.path.expanduser("~/.osb/benchmarks/workloads/default/vectorsearch")
    workload_file = os.path.join(workload_dir, "workload.json")
    if os.path.exists(workload_file):
        return
    repo_dir = os.path.expanduser("~/.osb/benchmarks/workloads/default")
    logger.info("vectorsearch workload not found, updating OSB workload repo...")
    subprocess.run(["git", "fetch", "origin"], cwd=repo_dir, capture_output=True, check=True)
    subprocess.run(["git", "checkout", "origin/main", "--", "vectorsearch"], cwd=repo_dir, capture_output=True, check=True)
    if not os.path.exists(workload_file):
        raise RuntimeError("Failed to fetch vectorsearch workload from OSB workload repo")
    logger.info("vectorsearch workload fetched successfully")


def run_aoss_test_benchmarks(cluster: Cluster, collection_type: str):
    """Run OSB workloads appropriate for a specific AOSS collection type.
    
    collection_type: 'search', 'timeseries', or 'vector'
    """
    if collection_type == 'search':
        cluster.execute_benchmark_workload(workload="geonames")
        cluster.execute_benchmark_workload(workload="pmc")
        cluster.execute_benchmark_workload(workload="so")
    elif collection_type == 'timeseries':
        cluster.execute_benchmark_workload(workload="http_logs")
        cluster.execute_benchmark_workload(workload="eventdata")
    elif collection_type == 'vector':
        _ensure_vectorsearch_workload()
        cluster.execute_benchmark_workload(
            workload="vectorsearch",
            workload_params="target_index_name:vectors_faiss,"
                            "target_index_body:indices/faiss-index.json,"
                            "target_field_name:target_field,"
                            "target_index_dimension:768,"
                            "target_index_space_type:l2,"
                            "target_index_bulk_size:10,"
                            "target_index_bulk_indexing_clients:1,"
                            "target_index_bulk_index_data_set_corpus:cohere",
            test_procedure="no-train-test-index-only"
        )
        cluster.execute_benchmark_workload(
            workload="vectorsearch",
            workload_params="target_index_name:vectors_lucene_filtered,"
                            "target_index_body:indices/filters/lucene-index-attributes.json,"
                            "target_field_name:target_field,"
                            "target_index_dimension:768,"
                            "target_index_space_type:l2,"
                            "target_index_bulk_size:10,"
                            "target_index_bulk_indexing_clients:1,"
                            "target_index_bulk_index_data_set_corpus:cohere",
            test_procedure="no-train-test-index-only"
        )
    else:
        raise ValueError(f"Unknown AOSS collection type: {collection_type}. Use 'search', 'timeseries', or 'vector'.")


# As a default we exclude system indices and searchguard indices
def clear_indices(cluster: Cluster):
    if cluster.is_serverless:
        try:
            r = cluster.call_api("/_cat/indices", params={"format": "json"})
            indices = [idx["index"] for idx in r.json() if not idx["index"].startswith(".")]
            if not indices:
                return "No user indices to delete."
            for index in indices:
                cluster.call_api(f"/{index}", method=HttpMethod.DELETE)
            return f"Deleted {len(indices)} indices: {', '.join(indices)}"
        except Exception as e:
            return f"Error encountered when clearing indices: {e}"

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
