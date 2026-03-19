import requests.exceptions

from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.snapshot import Snapshot
from dataclasses import dataclass
import logging

logger = logging.getLogger(__name__)


def _is_solr(cluster: Cluster) -> bool:
    """Check if the cluster is a Solr instance based on its configured version."""
    return isinstance(cluster.version, str) and cluster.version.upper().startswith("SOLR")


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
        if _is_solr(cluster):
            return _solr_cat_indices(cluster, as_json)
        if refresh:
            cluster.call_api('/_refresh')
        as_json_suffix = "?format=json" if as_json else "?v=true"
        cat_indices_path = f"/_cat/indices/_all{as_json_suffix}"
        r = cluster.call_api(cat_indices_path)
        return r.json() if as_json else r.content
    except Exception as e:
        logger.debug("Exception occurred when using call_api on cluster: ", exc_info=True)
        return f"Error: Unable to perform cat-indices command with message: {e}"


def _solr_cat_indices(cluster: Cluster, as_json=False):
    """List Solr collections with doc counts via Collections API (SolrCloud)."""
    # Get collection list
    r = cluster.call_api("/solr/admin/collections?action=LIST&wt=json")
    collections = r.json().get("collections", [])

    if as_json:
        result = []
        for coll in collections:
            doc_count = _solr_collection_doc_count(cluster, coll)
            result.append({
                "index": coll,
                "docs.count": str(doc_count),
            })
        return result
    else:
        lines = ["collection           docs.count"]
        for coll in collections:
            doc_count = _solr_collection_doc_count(cluster, coll)
            lines.append(f"{coll:<20} {doc_count:>10}")
        return "\n".join(lines).encode()


def _solr_collection_doc_count(cluster: Cluster, collection: str) -> int:
    """Get doc count for a Solr collection via select query."""
    try:
        r = cluster.call_api(f"/solr/{collection}/select?q=*:*&rows=0&wt=json")
        return r.json().get("response", {}).get("numFound", 0)
    except Exception:
        return 0


def connection_check(cluster: Cluster) -> ConnectionResult:
    caught_exception = None
    r = None
    try:
        if _is_solr(cluster):
            r = cluster.call_api("/solr/admin/info/system", timeout=3)
        else:
            r = cluster.call_api("/", timeout=3)
    except Exception as e:
        caught_exception = e
        logging.debug(f"Unable to access cluster: {cluster} with exception: {e}")
    if caught_exception is None:
        response_json = r.json()
        if _is_solr(cluster):
            version = response_json.get("lucene", {}).get("solr-spec-version", "unknown")
        else:
            version = response_json['version']['number']
        return ConnectionResult(connection_message="Successfully connected!",
                                connection_established=True,
                                cluster_version=version)
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
