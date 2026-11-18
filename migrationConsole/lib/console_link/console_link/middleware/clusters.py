import os
import requests.exceptions
import subprocess
from typing import Any, Optional

from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.snapshot import Snapshot
from dataclasses import dataclass
from enum import Enum
import logging

logger = logging.getLogger(__name__)


def _is_solr(cluster: Cluster) -> bool:
    """Check if the cluster is a Solr instance based on its configured version."""
    return isinstance(cluster.version, str) and cluster.version.upper().startswith("SOLR")


class ConnectionCheckStatus(str, Enum):
    VALID = "valid"
    PARTIALLY_VERIFIED = "partially_verified"
    FAILED = "failed"


class ConnectionCheckStageStatus(str, Enum):
    PASSED = "passed"
    PARTIALLY_VERIFIED = "partially_verified"
    FAILED = "failed"
    SKIPPED = "skipped"


@dataclass(frozen=True)
class ConnectionCheckStage:
    name: str
    status: ConnectionCheckStageStatus
    message: str
    code: Optional[str] = None
    http_status: Optional[int] = None

    def to_dict(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "name": self.name,
            "status": self.status.value,
            "message": self.message,
        }
        if self.code is not None:
            result["code"] = self.code
        if self.http_status is not None:
            result["http_status"] = self.http_status
        return result


@dataclass(frozen=True)
class ConnectionResult:
    connection_message: str
    connection_established: bool
    cluster_version: Optional[str] = None
    status: ConnectionCheckStatus = ConnectionCheckStatus.FAILED
    stages: tuple[ConnectionCheckStage, ...] = ()
    diagnostic_log: Optional[str] = None

    def __repr__(self):
        parts = [f"connection_message='{self.connection_message}'",
                 f"connection_established={self.connection_established}"]
        if self.cluster_version is not None:
            parts.append(f"cluster_version='{self.cluster_version}'")
        return f"ConnectionResult({', '.join(parts)})"

    @classmethod
    def success(
        cls,
        *,
        cluster_version: Optional[str] = None,
        message: str = "Successfully connected!",
        stage_message: str = "Connected and authenticated.",
        http_status: Optional[int] = None,
    ) -> "ConnectionResult":
        return cls(
            connection_message=message,
            connection_established=True,
            cluster_version=cluster_version,
            status=ConnectionCheckStatus.VALID,
            stages=(
                ConnectionCheckStage(
                    name="cluster-api",
                    status=ConnectionCheckStageStatus.PASSED,
                    message=stage_message,
                    http_status=http_status,
                ),
            ),
        )

    @classmethod
    def failure(
        cls,
        *,
        message: str,
        code: str,
        stage_message: Optional[str] = None,
        http_status: Optional[int] = None,
        diagnostic_log: Optional[str] = None,
    ) -> "ConnectionResult":
        return cls(
            connection_message=message,
            connection_established=False,
            status=ConnectionCheckStatus.FAILED,
            stages=(
                ConnectionCheckStage(
                    name="cluster-api",
                    status=ConnectionCheckStageStatus.FAILED,
                    message=stage_message or message,
                    code=code,
                    http_status=http_status,
                ),
            ),
            diagnostic_log=diagnostic_log,
        )

    def to_dict(self) -> dict[str, Any]:
        result: dict[str, Any] = {
            "status": self.status.value,
            "connection_established": self.connection_established,
            "connection_message": self.connection_message,
        }
        if self.cluster_version is not None:
            result["cluster_version"] = self.cluster_version
        result["stages"] = [stage.to_dict() for stage in self.stages]
        if self.diagnostic_log is not None:
            result["diagnostic_log"] = self.diagnostic_log
        return result


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
        if refresh and not cluster.is_serverless:
            cluster.call_api('/_refresh')
        elif refresh and cluster.is_serverless:
            logger.debug("Skipping index refresh — not supported for serverless collections")
        as_json_suffix = "?format=json" if as_json else "?v=true"
        if cluster.is_serverless:
            cat_indices_path = f"/_cat/indices{as_json_suffix}"
        else:
            cat_indices_path = f"/_cat/indices/_all{as_json_suffix}"
        r = cluster.call_api(cat_indices_path)
        return r.json() if as_json else r.content.decode("utf-8")
    except Exception as e:
        logger.debug("Exception occurred when using call_api on cluster: ", exc_info=True)
        return f"Error: Unable to perform cat-indices command with message: {e}"


def _solr_cat_indices(cluster: Cluster, as_json=False):
    """List Solr collections/cores with doc counts. Tries SolrCloud first, falls back to standalone."""
    collections = _solr_list_collections_or_cores(cluster)

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
        return ("\n".join(lines) + "\n").encode()


def _solr_list_collections_or_cores(cluster: Cluster) -> list:
    """List collections (SolrCloud) or cores (standalone). Tries both APIs."""
    context_path = cluster.solr_context_path
    try:
        r = cluster.call_api(f"{context_path}/admin/collections?action=LIST&wt=json")
        if r.status_code == 200:
            collections = r.json().get("collections", [])
            if collections:
                return collections
    except Exception:
        pass
    try:
        r = cluster.call_api(f"{context_path}/admin/cores?action=STATUS&wt=json")
        return list(r.json().get("status", {}).keys())
    except Exception:
        return []


def _solr_collection_doc_count(cluster: Cluster, collection: str) -> int:
    """Get doc count for a Solr collection via select query."""
    try:
        r = cluster.call_api(f"{cluster.solr_context_path}/{collection}/select?q=*:*&rows=0&wt=json")
        return r.json().get("response", {}).get("numFound", 0)
    except Exception:
        return 0


def _response_failure(response) -> ConnectionResult:
    status_code = response.status_code
    diagnostic_log = _response_diagnostic_log(response)
    if status_code == 401:
        return ConnectionResult.failure(
            message="Unable to connect to cluster: authentication failed with HTTP 401.",
            stage_message="The cluster rejected the configured authentication.",
            code="authentication-failed",
            http_status=status_code,
            diagnostic_log=diagnostic_log,
        )
    if status_code == 403:
        return ConnectionResult.failure(
            message="Unable to connect to cluster: authorization failed with HTTP 403.",
            stage_message="The authenticated principal is not authorized to read cluster information.",
            code="authorization-failed",
            http_status=status_code,
            diagnostic_log=diagnostic_log,
        )
    return ConnectionResult.failure(
        message=f"Unable to connect to cluster: the cluster API returned HTTP {status_code}.",
        stage_message=f"The cluster API returned HTTP {status_code}.",
        code="unexpected-http-status",
        http_status=status_code,
        diagnostic_log=diagnostic_log,
    )


def _response_diagnostic_log(response) -> str:
    status = f"HTTP {response.status_code}"
    reason = str(getattr(response, "reason", "") or "").strip()
    if reason:
        status += f" {reason}"
    lines = [status]
    for header in ("audit-id", "x-amz-request-id", "x-amzn-requestid"):
        value = response.headers.get(header)
        if value:
            lines.append(f"{header}: {value}")
    body = str(getattr(response, "text", "") or "").strip()
    if body:
        lines.extend(["", body[:8192]])
    return "\n".join(lines)


def _exception_failure(error: Exception) -> ConnectionResult:
    if isinstance(error, requests.exceptions.SSLError):
        code = "tls-failed"
    elif isinstance(error, requests.exceptions.Timeout):
        code = "timeout"
    elif isinstance(error, requests.exceptions.ConnectionError):
        code = "connection-failed"
    else:
        code = "unexpected-error"
    return ConnectionResult.failure(
        message=f"Unable to connect to cluster with error: {error}",
        stage_message=str(error),
        code=code,
        diagnostic_log=f"{type(error).__name__}: {error}",
    )


def _solr_connection_check(response) -> ConnectionResult:
    if response.status_code != 200:
        return _response_failure(response)
    try:
        response_json = response.json()
        version = response_json.get("lucene", {}).get("solr-spec-version", "unknown")
    except Exception as error:
        return ConnectionResult.failure(
            message=f"Unable to read the Solr cluster response: {error}",
            stage_message=str(error),
            code="invalid-response",
            http_status=response.status_code,
        )
    return ConnectionResult.success(
        cluster_version=version,
        http_status=response.status_code,
    )


def _serverless_connection_check(cluster: Cluster) -> ConnectionResult:
    """Verify AOSS access with a read-only API request."""
    try:
        response = cluster.call_api("/_cat/indices", timeout=3, raise_error=False)
    except Exception as error:
        logger.debug("Unable to access AOSS cluster", exc_info=True)
        return _exception_failure(error)
    if response.status_code != 200:
        return _response_failure(response)
    return ConnectionResult.success(
        message="Successfully connected to serverless collection!",
        http_status=response.status_code,
    )


def connection_check(cluster: Cluster) -> ConnectionResult:
    try:
        if _is_solr(cluster):
            response = cluster.call_api(
                f"{cluster.solr_context_path}/admin/info/system",
                timeout=3,
                raise_error=False,
            )
        else:
            response = cluster.call_api("/", raise_error=False, timeout=5)
    except Exception as error:
        return _exception_failure(error)

    if _is_solr(cluster):
        return _solr_connection_check(response)

    if response.status_code == 404 and cluster.is_serverless:
        return _serverless_connection_check(cluster)

    if response.status_code != 200:
        return _response_failure(response)
    try:
        response_json = response.json()
        version = response_json["version"]["number"]
    except Exception as error:
        return ConnectionResult.failure(
            message=f"Unable to read the cluster version response: {error}",
            stage_message=str(error),
            code="invalid-response",
            http_status=response.status_code,
        )
    return ConnectionResult.success(
        cluster_version=version,
        http_status=response.status_code,
    )


def run_test_benchmarks(cluster: Cluster):
    cluster.execute_benchmark_workload(workload="geonames")
    cluster.execute_benchmark_workload(workload="http_logs")
    cluster.execute_benchmark_workload(workload="nested")
    cluster.execute_benchmark_workload(workload="nyc_taxis")


def _ensure_vectorsearch_workload():
    """Ensure the vectorsearch workload is available in the OSB workload cache.
    Used by run_aoss_test_benchmarks to pre-stage snapshot data on a source cluster
    before creating a BYOS snapshot for AOSS vector collection tests."""
    workload_dir = os.path.expanduser("~/.osb/benchmarks/workloads/default/vectorsearch")
    workload_file = os.path.join(workload_dir, "workload.json")
    if os.path.exists(workload_file):
        return
    repo_dir = os.path.expanduser("~/.osb/benchmarks/workloads/default")
    if not os.path.isdir(repo_dir):
        logger.info("OSB workload cache not found, bootstrapping via 'opensearch-benchmark list workloads'...")
        subprocess.run(["opensearch-benchmark", "list", "workloads"], capture_output=True, check=True)
    else:
        logger.info("vectorsearch workload not found, updating OSB workload repo...")
        subprocess.run(["git", "fetch", "origin"], cwd=repo_dir, capture_output=True, check=True)
        subprocess.run(["git", "checkout", "origin/main", "--", "vectorsearch"],
                       cwd=repo_dir, capture_output=True, check=True)
    if not os.path.exists(workload_file):
        raise RuntimeError("Failed to fetch vectorsearch workload from OSB workload repo")
    logger.info("vectorsearch workload fetched successfully")


def run_aoss_test_benchmarks(cluster: Cluster):
    """Run all OSB workloads for AOSS integration tests (search, timeseries, and vector).

    Intended for manually pre-staging snapshot data on a source cluster before
    creating a BYOS snapshot for AOSS integration tests.
    """
    # Search workloads
    cluster.execute_benchmark_workload(workload="geonames")
    cluster.execute_benchmark_workload(workload="pmc")
    cluster.execute_benchmark_workload(workload="so")
    # Timeseries workloads
    cluster.execute_benchmark_workload(workload="http_logs")
    cluster.execute_benchmark_workload(workload="eventdata")
    # Vector workloads
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
        # raise_error is the Cluster.call_api default, but stated explicitly here: the
        # module-level call_api in this file defaults the other way, and the hint below
        # only runs if a non-2xx raises.
        r = cluster.call_api(clear_indices_path, method=HttpMethod.DELETE,
                             params={"ignore_unavailable": "true"}, raise_error=True)
        return r.content
    except Exception as e:
        hint = _destructive_guard_hint(e)
        if hint:
            return hint
        return f"Error encountered when clearing indices: {e}"


# Clusters with action.destructive_requires_name enabled reject wildcard and _all deletes
# with this reason, whatever the caller passed for --acknowledge-risk.
DESTRUCTIVE_GUARD_REASON = "wildcard expressions or all indices are not allowed"


def _destructive_guard_hint(error: Exception) -> Optional[str]:
    """Turns the destructive-guard rejection into an actionable message; None for any other error."""
    response = getattr(error, "response", None)
    if response is None or response.status_code != 400:
        return None
    try:
        reason = response.json().get("error", {}).get("reason", "")
    except ValueError:
        reason = response.text or ""
    if DESTRUCTIVE_GUARD_REASON not in reason.lower():
        return None
    return (
        "Error encountered when clearing indices: the cluster has "
        "action.destructive_requires_name enabled, which forbids deleting by wildcard "
        "regardless of --acknowledge-risk. Delete the indices by name instead — list them with "
        "'console clusters cat-indices', then "
        "'console clusters curl <cluster> -X DELETE \"/<index1>,<index2>\"'."
    )


def clear_cluster(cluster: Cluster, snapshot: Snapshot = None):
    clear_indices(cluster)
    if snapshot:
        snapshot.delete_all_snapshots()
        snapshot.delete_snapshot_repo()
