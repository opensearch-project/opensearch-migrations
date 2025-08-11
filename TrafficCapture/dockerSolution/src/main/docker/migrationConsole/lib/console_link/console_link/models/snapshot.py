import datetime
import logging
from abc import ABC, abstractmethod
from requests.exceptions import HTTPError
from typing import Any, Dict, Optional

from cerberus import Validator
from console_link.models.cluster import AuthMethod, Cluster, HttpMethod, NoSourceClusterDefinedError
from console_link.models.command_result import CommandResult
from console_link.models.command_runner import CommandRunner, CommandRunnerError, FlagOnlyArgument
from console_link.models.schema_tools import contains_one_of
from console_link.models.utils import DEFAULT_SNAPSHOT_REPO_NAME

logger = logging.getLogger(__name__)

SNAPSHOT_SCHEMA = {
    'snapshot': {
        'type': 'dict',
        'schema': {
            'snapshot_name': {'type': 'string', 'required': True},
            'snapshot_repo_name': {'type': 'string', 'required': False},
            'otel_endpoint': {'type': 'string', 'required': False},
            's3': {
                'type': 'dict',
                'schema': {
                    'repo_uri': {'type': 'string', 'required': True},
                    'aws_region': {'type': 'string', 'required': True},
                    'role': {'type': 'string', 'required': False},
                    'endpoint': {'type': 'string', 'required': False}
                }
            },
            'fs': {
                'type': 'dict',
                'schema': {
                    'repo_path': {'type': 'string', 'required': True},
                }
            }
        },
        'check_with': contains_one_of({'s3', 'fs'})
    }
}


class Snapshot(ABC):
    """
    Interface for creating and managing snapshots.
    """
    def __init__(self, config: Dict, source_cluster: Optional[Cluster]) -> None:
        self.config = config
        self.source_cluster = source_cluster
        v = Validator(SNAPSHOT_SCHEMA)
        if not v.validate({'snapshot': config}):
            raise ValueError("Invalid config file for snapshot", v.errors)
        self.snapshot_name = config['snapshot_name']
        self.snapshot_repo_name = config.get("snapshot_repo_name", DEFAULT_SNAPSHOT_REPO_NAME)
        self.otel_endpoint = config.get("otel_endpoint", None)

    @abstractmethod
    def create(self, *args, **kwargs) -> CommandResult:
        """Create a snapshot."""
        pass

    @abstractmethod
    def status(self, *args, **kwargs) -> CommandResult:
        """Get the status of the snapshot."""
        pass

    @abstractmethod
    def delete(self, *args, **kwargs) -> CommandResult:
        """Delete a snapshot."""
        pass

    @abstractmethod
    def delete_all_snapshots(self, *args, **kwargs) -> CommandResult:
        """Delete all snapshots in the snapshot repository."""
        pass

    @abstractmethod
    def delete_snapshot_repo(self, *args, **kwargs) -> CommandResult:
        """Delete a snapshot repository."""
        pass

    def _collect_universal_command_args(self) -> Dict:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        command_args = {
            "--snapshot-name": self.snapshot_name,
            "--snapshot-repo-name": self.snapshot_repo_name,
            "--source-host": self.source_cluster.endpoint
        }

        if self.source_cluster.auth_type == AuthMethod.BASIC_AUTH:
            try:
                auth_details = self.source_cluster.get_basic_auth_details()
                command_args.update({
                    "--source-username": auth_details.username,
                    "--source-password": auth_details.password
                })
                logger.info("Using basic auth for source cluster")
            except KeyError as e:
                raise ValueError(f"Missing required auth details for source cluster: {e}")
        elif self.source_cluster.auth_type == AuthMethod.SIGV4:
            signing_name, region = self.source_cluster._get_sigv4_details(force_region=True)
            logger.info(f"Using sigv4 auth for source cluster with signing_name {signing_name} and region {region}")
            command_args.update({
                "--source-aws-service-signing-name": signing_name,
                "--source-aws-region": region
            })

        if self.source_cluster.allow_insecure:
            command_args["--source-insecure"] = None

        if self.otel_endpoint:
            command_args["--otel-collector-endpoint"] = self.otel_endpoint

        return command_args


class S3Snapshot(Snapshot):
    def __init__(self, config: Dict, source_cluster: Optional[Cluster]) -> None:
        super().__init__(config, source_cluster)
        self.s3_repo_uri = config['s3']['repo_uri']
        self.s3_role_arn = config['s3'].get('role')
        self.s3_region = config['s3']['aws_region']

    def create(self, *args, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError
        base_command = "/root/createSnapshot/bin/CreateSnapshot"

        s3_command_args = {
            "--s3-repo-uri": self.s3_repo_uri,
            "--s3-region": self.s3_region,
        }

        command_args = self._collect_universal_command_args()
        command_args.update(s3_command_args)

        wait = kwargs.get('wait', False)
        max_snapshot_rate_mb_per_node = kwargs.get('max_snapshot_rate_mb_per_node')
        extra_args = kwargs.get('extra_args')

        if not wait:
            command_args["--no-wait"] = FlagOnlyArgument
        if max_snapshot_rate_mb_per_node is not None:
            command_args["--max-snapshot-rate-mb-per-node"] = max_snapshot_rate_mb_per_node
        if self.s3_role_arn:
            command_args["--s3-role-arn"] = self.s3_role_arn
        if extra_args:
            for arg in extra_args:
                command_args[arg] = FlagOnlyArgument

        command_runner = CommandRunner(base_command, command_args, sensitive_fields=["--source-password"])
        try:
            command_runner.run()
            logger.info(f"Snapshot {self.config['snapshot_name']} creation initiated successfully")
            return CommandResult(success=True,
                                 value=f"Snapshot {self.config['snapshot_name']} creation initiated successfully")
        except CommandRunnerError as e:
            logger.debug(f"Failed to create snapshot: {str(e)}")
            return CommandResult(success=False, value=f"Failed to create snapshot: {str(e)}")

    def status(self, *args, deep_check=False, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return get_snapshot_status(self.source_cluster, self.snapshot_name, self.snapshot_repo_name, deep_check)

    def delete(self, *args, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return delete_snapshot(self.source_cluster, self.snapshot_name, self.snapshot_repo_name)

    def delete_all_snapshots(self, *args, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return delete_all_snapshots(self.source_cluster, self.snapshot_repo_name)

    def delete_snapshot_repo(self, *args, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return delete_snapshot_repo(self.source_cluster, self.snapshot_repo_name)


class FileSystemSnapshot(Snapshot):
    def __init__(self, config: Dict, source_cluster: Optional[Cluster]) -> None:
        super().__init__(config, source_cluster)
        self.repo_path = config['fs']['repo_path']

    def create(self, *args, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError
        base_command = "/root/createSnapshot/bin/CreateSnapshot"

        command_args = self._collect_universal_command_args()
        command_args["--file-system-repo-path"] = self.repo_path

        max_snapshot_rate_mb_per_node = kwargs.get('max_snapshot_rate_mb_per_node')
        extra_args = kwargs.get('extra_args')

        if max_snapshot_rate_mb_per_node is not None:
            command_args["--max-snapshot-rate-mb-per-node"] = max_snapshot_rate_mb_per_node
        if extra_args:
            for arg in extra_args:
                command_args[arg] = FlagOnlyArgument

        command_runner = CommandRunner(base_command, command_args, sensitive_fields=["--source-password"])
        try:
            command_runner.run()
            logger.info(f"Snapshot {self.config['snapshot_name']} creation initiated successfully")
            return CommandResult(success=True,
                                 value=f"Snapshot {self.config['snapshot_name']} creation initiated successfully")
        except CommandRunnerError as e:
            logger.debug(f"Failed to create snapshot: {str(e)}")
            return CommandResult(success=False, value=f"Failed to create snapshot: {str(e)}")

    def status(self, *args, deep_check=False, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return get_snapshot_status(self.source_cluster, self.snapshot_name, self.snapshot_repo_name, deep_check)

    def delete(self, *args, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return delete_snapshot(self.source_cluster, self.snapshot_name, self.snapshot_repo_name)

    def delete_all_snapshots(self, *args, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return delete_all_snapshots(self.source_cluster, self.snapshot_repo_name)

    def delete_snapshot_repo(self, *args, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return delete_snapshot_repo(self.source_cluster, self.snapshot_repo_name)


def format_date(millis: int) -> str:
    if millis == 0:
        return "N/A"
    return datetime.datetime.fromtimestamp(millis / 1000).strftime('%Y-%m-%d %H:%M:%S')


def format_duration(millis: int) -> str:
    seconds = int(millis / 1000)
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    return f"{hours}h {minutes}m {seconds}s"


def get_snapshot_status_message(snapshot_info: Dict) -> str:
    snapshot_state = snapshot_info.get("state")
    stats = snapshot_info.get("stats", {})
    total_size_in_bytes = stats.get("total", {}).get("size_in_bytes", 0)
    processed_size_in_bytes = stats.get("processed", stats.get("incremental", {})).get("size_in_bytes", 0)
    percent_completed = (processed_size_in_bytes / total_size_in_bytes) * 100 if total_size_in_bytes > 0 else 0
    total_size_gibibytes = total_size_in_bytes / (1024 ** 3)
    processed_size_gibibytes = processed_size_in_bytes / (1024 ** 3)

    total_shards = snapshot_info.get('shards_stats', {}).get('total', 0)
    successful_shards = snapshot_info.get('shards_stats', {}).get('done', 0)
    failed_shards = snapshot_info.get('shards_stats', {}).get('failed', 0)

    start_time = snapshot_info.get('stats', {}).get('start_time_in_millis', 0)
    duration_in_millis = snapshot_info.get('stats', {}).get('time_in_millis', 0)

    start_time_formatted = format_date(start_time)

    anticipated_duration_remaining_formatted = (
        format_duration((duration_in_millis / percent_completed) * (100 - percent_completed))
        if percent_completed > 0 else "N/A (not enough data to compute)"
    )

    throughput_mib_per_sec = (
        (processed_size_in_bytes / (1024 ** 2)) / (duration_in_millis / 1000)
        if duration_in_millis > 0 else 0
    )

    return (
        f"Snapshot status: {snapshot_state}.\n"
        f"Start time: {start_time_formatted}\n"
        f"Finished time: {}\n"
        f"Percent completed: {percent_completed:.2f}%\n"
        f"Estimated time to completion: {anticipated_duration_remaining_formatted}\n"
        f"Data processed: {processed_size_gibibytes:.3f}/{total_size_gibibytes:.3f} MiB\n"
        f"Throughput: {throughput_mib_per_sec:.2f} MiB/sec"
        f"Total shards count: {total_shards}\n"
        f"Successful shards count: {successful_shards}\n"
        f"Failed shards count: {failed_shards}\n"
    )


class SnapshotStateAndDetails:
    def __init__(self, state: str, details: Any):
        self.state = state
        self.details = details


class SnapshotNotStarted(Exception):
    pass


class SnapshotStatusUnavailable(Exception):
    pass


def get_latest_snapshot_status_raw(cluster: Cluster,
                                   snapshot: str,
                                   repository: str,
                                   deep: bool) -> SnapshotStateAndDetails:
    try:
        path = f"/_snapshot/{repository}/{snapshot}"
        response = cluster.call_api(path, HttpMethod.GET)
        logging.debug(f"Raw get snapshot status response: {response.text}")
        response.raise_for_status()
    except HTTPError:
        raise SnapshotNotStarted()

    snapshot_data = response.json()
    snapshots = snapshot_data.get('snapshots', [])
    if not snapshots:
        raise SnapshotNotStarted()

    snapshot_info = snapshots[0]
    state = snapshot_info.get("state")

    if not deep:
        return SnapshotStateAndDetails(state, None)

    try:
        path = f"/_snapshot/{repository}/{snapshot}/_status"
        response = cluster.call_api(path, HttpMethod.GET)
        logging.debug(f"Raw get snapshot status full response: {response.text}")
        response.raise_for_status()
    except HTTPError:
        raise SnapshotStatusUnavailable()

    snapshot_data = response.json()
    snapshots = snapshot_data.get('snapshots', [])
    if not snapshots or not snapshots[0]:
        raise SnapshotStatusUnavailable()
    
    return SnapshotStateAndDetails(state, snapshots[0])


def get_snapshot_status(cluster: Cluster, snapshot: str, repository: str, deep_check: bool) -> CommandResult:
    try:
        latest_snapshot_status = get_latest_snapshot_status_raw(cluster, snapshot, repository, deep=deep_check)
        if deep_check:
            message = get_snapshot_status_message(latest_snapshot_status.details)
            return CommandResult(success=True, value=f"{latest_snapshot_status.state}\n{message}")
        return CommandResult(success=True, value=latest_snapshot_status.state)
    except SnapshotNotStarted:
        return CommandResult(success=False, value="Snapshot not started")
    except SnapshotStatusUnavailable:
        return CommandResult(success=False, value="Snapshot status not available")
    except Exception as e:
        return CommandResult(success=False, value=f"Failed to get full snapshot status: {str(e)}")


def delete_snapshot(cluster: Cluster, snapshot_name: str, repository: str):
    path = f"/_snapshot/{repository}/{snapshot_name}"
    response = cluster.call_api(path, HttpMethod.DELETE)
    logging.debug(f"Raw delete snapshot status response: {response.text}")
    logger.info(f"Deleted snapshot: {snapshot_name} from repository '{repository}'.")


def delete_all_snapshots(cluster: Cluster, repository: str) -> None:
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
        response = cluster.call_api(snapshots_path, raise_error=True)
        logger.debug(f"Raw response: {response.json()}")
        snapshots = response.json().get("snapshots", [])
        logger.info(f"Found {len(snapshots)} snapshots in repository '{repository}'.")

        if not snapshots:
            logger.info(f"No snapshots found in repository '{repository}'.")
            return

        # Delete each snapshot
        for snapshot in snapshots:
            snapshot_name = snapshot["snapshot"]
            delete_snapshot(cluster, snapshot_name, repository)

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


def delete_snapshot_repo(cluster: Cluster, repository: str) -> None:
    logger.info(f"Deleting repository '{repository}'")
    """
    Delete repository. Should be empty before execution.

    :param cluster: Cluster object to interact with the Elasticsearch cluster.
    :param repository: Name of the snapshot repository to delete.
    :raises Exception: For general errors during repository deleting, except when the repository is missing.
    """
    try:
        delete_path = f"/_snapshot/{repository}"
        response = cluster.call_api(delete_path, method=HttpMethod.DELETE, raise_error=True)
        logging.debug(f"Raw delete snapshot repository status response: {response.text}")
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
