from console_link.models.cluster import Cluster, HttpMethod
from dataclasses import dataclass
import logging
from requests.exceptions import HTTPError

logger = logging.getLogger(__name__)


@dataclass
class ConnectionResult:
    connection_message: str
    connection_established: bool
    cluster_version: str


def call_api(cluster: Cluster, path: str, method=HttpMethod.GET, data=None, headers=None, timeout=None,
             session=None, raise_error=False):
    r = cluster.call_api(path=path, method=method, data=data, headers=headers, timeout=timeout, session=session,
                         raise_error=raise_error)
    return r


def cat_indices(cluster: Cluster, refresh=False, as_json=False):
    if refresh:
        cluster.call_api('/_refresh')
    as_json_suffix = "?format=json" if as_json else "?v=true"
    cat_indices_path = f"/_cat/indices/_all{as_json_suffix}"
    r = cluster.call_api(cat_indices_path)
    return r.json() if as_json else r.content


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
    clear_indices_path = "/*,-.*,-searchguard*,-sg7*,.migrations_working_state"
    try:
        r = cluster.call_api(clear_indices_path, method=HttpMethod.DELETE, params={"ignore_unavailable": "true"})
        return r.content
    except Exception as e:
        return f"Error encountered when clearing indices: {e}"


def clear_cluster(cluster: Cluster):
    clear_indices(cluster)
    clear_snapshots(cluster, 'migration_assistant_repo')
    delete_repo(cluster, 'migration_assistant_repo')


def clear_snapshots(cluster: Cluster, repository: str) -> None:
    logger.info(f"Clearing snapshots from repository '{repository}'")
    """
    Clears all snapshots from the specified repository.

    :param cluster: Cluster object to interact with the Elasticsearch cluster.
    :param repository: Name of the snapshot repository to clear snapshots from.
    :raises Exception: For general errors during snapshot clearing, except when the repository is missing.
    """
    try:
        # List all snapshots in the repository
        snapshots_path = f"/_snapshot/{repository}/_all"
        response = call_api(cluster, snapshots_path, raise_error=True)
        logger.debug(f"Raw response: {response.json()}")
        snapshots = response.json().get("snapshots", [])
        logger.info(f"Found {len(snapshots)} snapshots in repository '{repository}'.")

        if not snapshots:
            logger.info(f"No snapshots found in repository '{repository}'.")
            return

        # Delete each snapshot
        for snapshot in snapshots:
            snapshot_name = snapshot["snapshot"]
            delete_path = f"/_snapshot/{repository}/{snapshot_name}"
            call_api(cluster, delete_path, method=HttpMethod.DELETE, raise_error=True)
            logger.info(f"Deleted snapshot: {snapshot_name} from repository '{repository}'.")

    except Exception as e:
        # Handle 404 errors specifically for missing repository
        if isinstance(e, HTTPError) and e.response.status_code == 404:
            error_details = e.response.json().get('error', {})
            if error_details.get('type') == 'repository_missing_exception':
                logger.info(f"Repository '{repository}' is missing. Skipping snapshot clearing.")
                return
        # Re-raise other errors
        logger.error(f"Error clearing snapshots from repository '{repository}': {e}")
        raise e


def delete_repo(cluster: Cluster, repository: str) -> None:
    logger.info(f"Deleting repository '{repository}'")
    """
    Delete repository. Should be empty before execution.

    :param cluster: Cluster object to interact with the Elasticsearch cluster.
    :param repository: Name of the snapshot repository to delete.
    :raises Exception: For general errors during repository deleting, except when the repository is missing.
    """
    try:
        delete_path = f"/_snapshot/{repository}"
        call_api(cluster, delete_path, method=HttpMethod.DELETE, raise_error=True)
        logger.info(f"Deleted repository: {repository}.")
    except Exception as e:
        # Handle 404 errors specifically for missing repository
        if isinstance(e, HTTPError) and e.response.status_code == 404:
            error_details = e.response.json().get('error', {})
            if error_details.get('type') == 'repository_missing_exception':
                logger.info(f"Repository '{repository}' is missing. Skipping delete.")
                return
        # Re-raise other errors
        logger.error(f"Error deleting repository '{repository}': {e}")
        raise e
