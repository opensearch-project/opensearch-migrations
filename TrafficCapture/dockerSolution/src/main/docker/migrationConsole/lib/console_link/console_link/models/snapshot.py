import logging
import time
from abc import ABC, abstractmethod
from cerberus import Validator
from datetime import datetime
from pydantic import BaseModel, Field, field_serializer
from requests.exceptions import HTTPError
from typing import Any, Dict, Optional

from console_link.models.cluster import AuthMethod, Cluster, HttpMethod, NoSourceClusterDefinedError
from console_link.models.command_result import CommandResult
from console_link.models.command_runner import CommandRunner, CommandRunnerError, FlagOnlyArgument
from console_link.models.schema_tools import contains_one_of
from console_link.models.step_state import StepState
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
        self.s3_endpoint = config['s3'].get('endpoint')

    def create(self, *args, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError
        base_command = "/root/createSnapshot/bin/CreateSnapshot"

        s3_command_args = {
            "--s3-repo-uri": self.s3_repo_uri,
            "--s3-region": self.s3_region,
        }
        if self.s3_endpoint:
            s3_command_args["--s3-endpoint"] = self.s3_endpoint

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
    return datetime.fromtimestamp(millis / 1000).strftime('%Y-%m-%d %H:%M:%S')


def format_duration(millis: int) -> str:
    seconds = int(millis / 1000)
    minutes, seconds = divmod(seconds, 60)
    hours, minutes = divmod(minutes, 60)
    return f"{hours}h {minutes}m {seconds}s"


class SnapshotStateAndDetails:
    def __init__(self, state: str, details: Any):
        self.state = state
        self.details = details


class SnapshotNotStarted(Exception):
    pass


class SnapshotStatusUnavailable(Exception):
    pass


class SnapshotStatus(BaseModel):
    status: StepState
    percentage_completed: float
    eta_ms: Optional[float] = None
    started: Optional[datetime] = Field(
        default=None,
        description="Start time in ISO 8601 format",
        json_schema_extra={"format": "date-time"}
    )
    finished: Optional[datetime] = Field(
        default=None,
        description="Start time in ISO 8601 format",
        json_schema_extra={"format": "date-time"}
    )
    data_total_bytes: Optional[int] = None
    data_processed_bytes: Optional[int] = None
    data_throughput_bytes_avg_sec: Optional[float] = None
    shard_total: Optional[int] = None
    shard_complete: Optional[int] = None
    model_config = {
        'from_attributes': True,
    }

    @field_serializer("started", "finished")
    def serialize_completed(self, dt: datetime | None) -> str | None:
        return dt.isoformat() if dt else None

    @classmethod
    def from_snapshot_info(cls, snapshot_info: dict) -> "SnapshotStatus":
        # 1) Extract progress metrics
        total_bytes = processed_bytes = throughput_bytes = None
        total_shards = completed_shards = failed_shards = None
        total_units = processed_units = 0
        if shards_stats := snapshot_info.get("shards_stats"):
            # ES ≥7.8 / OS: shard-level stats
            total_units = total_shards = shards_stats.get("total", 0)
            completed_shards = shards_stats.get("done", 0)
            failed_shards = shards_stats.get("failed", 0)
            processed_units = completed_shards + failed_shards
            # these sometimes live at the top level
            start_ms = snapshot_info.get("start_time_in_millis", 0)
            elapsed_ms = snapshot_info.get("time_in_millis", 0)
        else:
            # ES <7.8: simple shards summary
            shards = snapshot_info.get("shards", {})
            total_units = total_shards = shards.get("total", 0)
            completed_shards = shards.get("successful", 0)
            failed_shards = shards.get("failed", 0)
            processed_units = completed_shards + failed_shards
            start_ms = snapshot_info.get("start_time_in_millis", 0)
            elapsed_ms = snapshot_info.get("time_in_millis", 0)

        if stats := snapshot_info.get("stats"):
            # OpenSearch: byte-level stats
            total_units = total_bytes = stats.get("total", {}).get("size_in_bytes", 0)
            processed_units = processed_bytes = (
                stats.get("processed", {}).get("size_in_bytes", 0) +
                stats.get("incremental", {}).get("size_in_bytes", 0)
            )
            start_ms = stats.get("start_time_in_millis", 0)
            elapsed_ms = stats.get("time_in_millis", 0)
            duration_ms = stats.get("time_in_millis", 0)
            throughput_bytes = (
                (processed_bytes / (1024 ** 2)) / (duration_ms / 1000)
                if duration_ms > 0 else 0
            )

        # 2) Compute percentage complete
        percentage = (processed_units / total_units * 100) if total_units else 0.0

        # 3) Compute ETA in ms (only once we've made some progress)
        eta_ms: Optional[float] = None
        if 0 < percentage < 100:
            eta_ms = (elapsed_ms / percentage) * (100 - percentage)

        # 4) Normalize finished time
        finished_ms = start_ms + elapsed_ms

        # 5) Map snapshot state to your status string
        raw_state = snapshot_info.get("state", "")
        state = convert_snapshot_state_to_step_state(raw_state)

        # 6) If it's already done, clamp to 100%
        if state == StepState.COMPLETED:
            percentage = 100.0
            eta_ms = 0.0

        return cls(
            status=state,
            percentage_completed=percentage,
            eta_ms=eta_ms,
            started=datetime.fromtimestamp(start_ms / 1000) if start_ms else None,
            finished=datetime.fromtimestamp(finished_ms / 1000) if finished_ms else None,
            data_total_bytes=total_bytes,
            data_processed_bytes=processed_bytes,
            data_throughput_bytes_avg_sec=throughput_bytes,
            shard_total=total_shards,
            shard_complete=completed_shards,
        )


def convert_snapshot_state_to_step_state(snapshot_state: str) -> StepState:
    state_mapping = {
        "FAILED": StepState.FAILED,
        "IN_PROGRESS": StepState.RUNNING,
        "PARTIAL": StepState.FAILED,
        "SUCCESS": StepState.COMPLETED,
    }

    if (mapped := state_mapping.get(snapshot_state)) is None:
        logging.warning("Unknown snapshot_state %r; defaulting to 'FAILED'", snapshot_state)
        return StepState.FAILED
    return mapped


def get_latest_snapshot_status_raw(cluster: Cluster,
                                   snapshot: str,
                                   repository: str,
                                   deep_check: bool) -> SnapshotStateAndDetails:
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
    if not deep_check:
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
        # Get raw snapshot status data
        latest_snapshot_status_raw = get_latest_snapshot_status_raw(cluster, snapshot, repository, deep_check)

        if not deep_check:
            return CommandResult(success=True, value=latest_snapshot_status_raw.state)

        snapshot_status = SnapshotStatus.from_snapshot_info(latest_snapshot_status_raw.details)
        
        # Format datetime values for display
        start_time = snapshot_status.started.strftime('%Y-%m-%d %H:%M:%S') if snapshot_status.started else ''
        finish_time = snapshot_status.finished.strftime('%Y-%m-%d %H:%M:%S') if snapshot_status.finished else ''
        
        # Format the ETA string
        eta_ms = snapshot_status.eta_ms or 0
        eta_str = format_duration(int(eta_ms)) if eta_ms else "0h 0m 0s"

        # Format byte data
        def mb_or_blank(n: int | float | None) -> str | float:
            bytes_to_megabytes = (1024 ** 2)
            return "" if n is None else n / bytes_to_megabytes

        total_mb = mb_or_blank(snapshot_status.data_total_bytes)
        processed_mb = mb_or_blank(snapshot_status.data_processed_bytes)
        throughput_mb = mb_or_blank(snapshot_status.data_throughput_bytes_avg_sec)
        message = (
            f"Snapshot status: {latest_snapshot_status_raw.state}\n"
            f"Start time: {start_time}\n"
            f"Finished time: {finish_time}\n"
            f"Percent completed: {snapshot_status.percentage_completed:.2f}%\n"
            f"Estimated time to completion: {eta_str}\n"
            f"Data processed: {processed_mb:.3f}/{total_mb:.3f} MiB\n"
            f"Throughput: {throughput_mb:.3f} MiB/sec\n"
            f"Total shards: {snapshot_status.shard_total}\n"
            f"Successful shards: {snapshot_status.shard_complete}\n"
        )
        
        return CommandResult(success=True, value=message)
    except SnapshotNotStarted:
        return CommandResult(success=False, value="Snapshot not started")
    except SnapshotStatusUnavailable:
        return CommandResult(success=False, value="Snapshot status not available")
    except Exception as e:
        return CommandResult(success=False, value=f"Failed to get full snapshot status: {str(e)}")


def delete_snapshot(cluster: Cluster, snapshot_name: str, repository: str, wait_for_completion: bool = True,
                    timeout_seconds: int = 120):
    path = f"/_snapshot/{repository}/{snapshot_name}"
    response = cluster.call_api(path, HttpMethod.DELETE)
    logging.debug(f"Raw delete snapshot status response: {response.text}")
    logger.info(f"Initiated deletion of snapshot: {snapshot_name} from repository '{repository}'.")

    if wait_for_completion:
        logger.info(f"Waiting up to {timeout_seconds} seconds for deletion to complete...")
        end_time = time.time() + timeout_seconds
        while time.time() < end_time:
            check_response = cluster.call_api(path, raise_error=False)
            if check_response.status_code == 404:
                logger.info(f"Snapshot {snapshot_name} successfully deleted.")
                return
            logger.debug(f"Waiting for snapshot {snapshot_name} to be deleted...")
            time.sleep(2)
        raise TimeoutError(f"Snapshot '{snapshot_name}' in repository '{repository}' was not deleted "
                           f"after {timeout_seconds} seconds.")


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
