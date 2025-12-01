from enum import Enum
import logging
import time
from abc import ABC, abstractmethod
from cerberus import Validator
from datetime import datetime
from pydantic import BaseModel, Field, field_serializer
from requests.exceptions import HTTPError
from typing import Any, Dict, List, Optional, TypeAlias

from console_link.models.cluster import AuthMethod, Cluster, HttpMethod, NoSourceClusterDefinedError
from console_link.models.command_result import CommandResult
from console_link.models.command_runner import CommandRunner, CommandRunnerError, FlagOnlyArgument
from console_link.models.schema_tools import contains_one_of
from console_link.models.step_state import StepState
from console_link.models.utils import DEFAULT_SNAPSHOT_REPO_NAME

logger = logging.getLogger(__name__)


# Define the models first to avoid forward reference issues
class SnapshotIndex(BaseModel):
    name: str
    document_count: Optional[int]
    size_bytes: int
    shard_count: int = 0


class SnapshotIndexState(str, Enum):
    not_started = "not_started"
    in_progress = "in_progress"
    completed = "completed"


class SnapshotIndexStatus(SnapshotIndex):
    status: SnapshotIndexState


class SnapshotIndexes(BaseModel):
    indexes: List[SnapshotIndex]


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
    def create(self, *args, **kwargs) -> str:
        """Create a snapshot."""
        pass

    @abstractmethod
    def status(self, *args, **kwargs) -> CommandResult:
        """Get the status of the snapshot."""
        pass

    @abstractmethod
    def delete(self, *args, **kwargs) -> str:
        """Delete a snapshot."""
        pass

    @abstractmethod
    def delete_all_snapshots(self, *args, **kwargs) -> str:
        """Delete all snapshots in the snapshot repository."""
        pass

    @abstractmethod
    def delete_snapshot_repo(self, *args, **kwargs) -> str:
        """Delete a snapshot repository."""
        pass

    def get_snapshot_indexes(self, index_patterns: Optional[List[str]] = None) -> SnapshotIndexes:
        """
        Fetch all indexes that will be included in the snapshot with accurate document count and size information.
        
        Args:
            index_patterns: Optional list of index patterns to filter the indexes. If None,
                          all indexes in the cluster will be considered.
        
        Returns:
            SnapshotIndexes containing information about all indexes that will be included in the snapshot.
        
        Raises:
            NoSourceClusterDefinedError: If no source cluster is defined.
        """
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()
        
        try:
            return get_cluster_indexes(self.source_cluster, index_patterns)
        except Exception as e:
            logger.error(f"Failed to get snapshot indexes: {str(e)}")
            raise

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

    def create(self, *args, **kwargs) -> str:
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
            return f"Snapshot {self.config['snapshot_name']} creation initiated successfully"
        except CommandRunnerError as e:
            logger.debug(f"Failed to create snapshot: {str(e)}")
            ex = FailedToCreateSnapshot()
            ex.add_note(f"Failure from {str(e)}")
            raise ex

    def status(self, *args, deep_check=False, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return get_snapshot_status(self.source_cluster, self.snapshot_name, self.snapshot_repo_name, deep_check)

    def delete(self, *args, **kwargs) -> str:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return delete_snapshot(self.source_cluster, self.snapshot_name, self.snapshot_repo_name)

    def delete_all_snapshots(self, *args, **kwargs) -> str:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return delete_all_snapshots(self.source_cluster, self.snapshot_repo_name)

    def delete_snapshot_repo(self, *args, **kwargs) -> str:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return delete_snapshot_repo(self.source_cluster, self.snapshot_repo_name)


class FileSystemSnapshot(Snapshot):
    def __init__(self, config: Dict, source_cluster: Optional[Cluster]) -> None:
        super().__init__(config, source_cluster)
        self.repo_path = config['fs']['repo_path']

    def create(self, *args, **kwargs) -> str:
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
            return f"Snapshot {self.config['snapshot_name']} creation initiated successfully"
        except CommandRunnerError as e:
            logger.debug(f"Failed to create snapshot: {str(e)}")
            ex = FailedToCreateSnapshot()
            ex.add_note(f"Failure from {str(e)}")
            raise ex

    def status(self, *args, deep_check=False, **kwargs) -> CommandResult:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return get_snapshot_status(self.source_cluster, self.snapshot_name, self.snapshot_repo_name, deep_check)

    def delete(self, *args, **kwargs) -> str:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return delete_snapshot(self.source_cluster, self.snapshot_name, self.snapshot_repo_name)

    def delete_all_snapshots(self, *args, **kwargs) -> str:
        if not self.source_cluster:
            raise NoSourceClusterDefinedError()

        return delete_all_snapshots(self.source_cluster, self.snapshot_repo_name)

    def delete_snapshot_repo(self, *args, **kwargs) -> str:
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
    indexes: Optional[List[SnapshotIndexStatus]] = None
    model_config = {
        'from_attributes': True,
    }

    @field_serializer("started", "finished")
    def serialize_completed(self, dt: datetime | None) -> str | None:
        return dt.isoformat() if dt else None

    @classmethod
    def from_snapshot_info(cls, snapshot_info: dict) -> "SnapshotStatus":
        """
        Create a SnapshotStatus object from snapshot information.
        """
        progress_metrics = cls._extract_progress_metrics(snapshot_info)
        
        percentage, eta_ms = cls._calculate_progress_metrics(
            progress_metrics["processed_units"],
            progress_metrics["total_units"],
            progress_metrics["elapsed_ms"],
            progress_metrics["state"]
        )
        
        indexes = cls._extract_index_statuses(snapshot_info, progress_metrics["state"])
        
        return cls(
            status=progress_metrics["state"],
            percentage_completed=percentage,
            eta_ms=eta_ms,
            started=datetime.fromtimestamp(progress_metrics["start_ms"] / 1000)
            if progress_metrics["start_ms"] else None,
            finished=datetime.fromtimestamp(progress_metrics["finished_ms"] / 1000)
            if progress_metrics["finished_ms"] else None,
            data_total_bytes=progress_metrics["total_bytes"],
            data_processed_bytes=progress_metrics["processed_bytes"],
            data_throughput_bytes_avg_sec=progress_metrics["throughput_bytes"],
            shard_total=progress_metrics["total_shards"],
            shard_complete=progress_metrics["completed_shards"],
            indexes=indexes
        )
        
    @classmethod
    def _extract_progress_metrics(cls, snapshot_info: dict) -> dict:
        """
        Extract progress metrics from the snapshot information.
        """
        total_bytes = processed_bytes = throughput_bytes = None
        total_shards = completed_shards = failed_shards = None
        total_units = processed_units = 0
        start_ms = elapsed_ms = 0
        
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
        
        raw_state = snapshot_info.get("state", "")
        state = convert_snapshot_state_to_step_state(raw_state)
        
        return {
            "total_bytes": total_bytes,
            "processed_bytes": processed_bytes,
            "throughput_bytes": throughput_bytes,
            "total_shards": total_shards,
            "completed_shards": completed_shards,
            "failed_shards": failed_shards,
            "total_units": total_units,
            "processed_units": processed_units,
            "start_ms": start_ms,
            "elapsed_ms": elapsed_ms,
            "finished_ms": start_ms + elapsed_ms,
            "state": state
        }
    
    @classmethod
    def _calculate_progress_metrics(
        cls,
        processed_units: int,
        total_units: int,
        elapsed_ms: int,
        state: StepState
    ) -> tuple:
        """
        Calculate percentage complete and estimated time to completion.
        """
        # Compute percentage complete
        percentage = (processed_units / total_units * 100) if total_units else 0.0
        
        # Compute ETA in ms (only once we've made some progress)
        eta_ms: Optional[float] = None
        if 0 < percentage < 100:
            eta_ms = (elapsed_ms / percentage) * (100 - percentage)
        
        # If it's already done, clamp to 100%
        if state == StepState.COMPLETED:
            percentage = 100.0
            eta_ms = 0.0
            
        return percentage, eta_ms
    
    @classmethod
    def _extract_index_statuses(cls, snapshot_info: dict, overall_state: StepState) -> List[SnapshotIndexStatus]:
        """
        Extract status information for individual indexes in the snapshot.
        """
        indexes = []
        indices = snapshot_info.get("indices", {})
        
        if not indices:
            return indexes
            
        for index_name, index_info in indices.items():
            # Extract basic index info
            shards_info = index_info.get("shards", 0)

            # Ensure shard_count is an integer, not a dictionary
            if isinstance(shards_info, dict):
                # If it's a dictionary of shard data, just use the count of keys
                shard_count = len(shards_info)
            else:
                shard_count = shards_info

            doc_count = index_info.get("docs", 0)
            size_bytes = index_info.get("size_in_bytes", 0)
            
            # Determine index status
            index_status = cls._determine_index_status(index_info.get("state", ""), overall_state)
            
            # Create and add SnapshotIndexStatus
            indexes.append(
                SnapshotIndexStatus(
                    name=index_name,
                    document_count=doc_count,
                    size_bytes=size_bytes,
                    shard_count=shard_count,
                    status=index_status
                )
            )

        return indexes
    
    @staticmethod
    def _determine_index_status(index_state: str, overall_state: StepState) -> SnapshotIndexState:
        """
        Determine the status of an individual index based on its state and the overall snapshot state.
        """
        if index_state == "SUCCESS" or overall_state == StepState.COMPLETED:
            return SnapshotIndexState.completed
        elif index_state in ["IN_PROGRESS", "STARTED"]:
            return SnapshotIndexState.in_progress
        else:
            return SnapshotIndexState.not_started


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
                    timeout_seconds: int = 120) -> str:
    try:
        path = f"/_snapshot/{repository}/{snapshot_name}"
        response = cluster.call_api(path, HttpMethod.DELETE)
        logging.debug(f"Raw delete snapshot status response: {response.text}")
        logger.info(f"Initiated deletion of snapshot: {snapshot_name} from repository '{repository}'.")
    except Exception as e:
        logger.debug(f"Error deleting snapshot '{snapshot_name}' from repository '{repository}': {e}")
        # For HTTP errors, check if it's a 404 (snapshot not found), which means it's already deleted
        if isinstance(e, HTTPError) and e.response.status_code == 404:
            logger.info(f"Snapshot '{snapshot_name}' not found in repository '{repository}', "
                        "considering it already deleted.")
            return f"Deleted snapshot: {snapshot_name} from repository '{repository}'"
        
        ex = FailedToDeleteSnapshot()
        ex.add_note(f"Unable to delete snapshot {snapshot_name} from repo {repository}, cause {str(e)}")
        raise ex

    if wait_for_completion:
        logger.info(f"Waiting up to {timeout_seconds} seconds for deletion to complete...")
        end_time = time.time() + timeout_seconds
        while time.time() < end_time:
            try:
                check_response = cluster.call_api(path, raise_error=False)
                if check_response.status_code == 404:
                    return f"Snapshot {snapshot_name} successfully deleted."
                logger.debug(f"Waiting for snapshot {snapshot_name} to be deleted...")
                time.sleep(2)
            except Exception as e:
                logger.debug(f"Error checking snapshot deletion status: {e}")
                time.sleep(2)
        raise TimeoutError(f"Snapshot '{snapshot_name}' in repository '{repository}' was not deleted "
                           f"after {timeout_seconds} seconds.")


def delete_all_snapshots(cluster: Cluster, repository: str) -> str:
    """
    Clears all snapshots from the specified repository.

    :param cluster: Cluster object to interact with the Elasticsearch cluster.
    :param repository: Name of the snapshot repository to clear snapshots from.
    :raises Exception: For general errors during snapshot clearing, except when the repository is missing.
    """
    logger.info(f"Clearing snapshots from repository '{repository}'")
    try:
        # List all snapshots in the repository
        snapshots_path = f"/_snapshot/{repository}/_all"
        response = cluster.call_api(snapshots_path, raise_error=True)
        logger.debug(f"Raw response: {response.json()}")
        snapshots = response.json().get("snapshots", [])
        logger.info(f"Found {len(snapshots)} snapshots in repository '{repository}'.")

        if not snapshots:
            logger.info(f"No snapshots found in repository '{repository}'.")
            return f"No snapshots found in repository '{repository}'."

        # Delete each snapshot - continue even if individual deletions fail
        for snapshot in snapshots:
            try:
                snapshot_name = snapshot["snapshot"]
                delete_snapshot(cluster, snapshot_name, repository)
            except FailedToDeleteSnapshot:
                # Ignore expected exceptions, the inner message will log
                pass
            except Exception as e:
                logger.warning(f"Error deleting snapshot '{snapshot_name}': {str(e)}")

    except Exception as e:
        # Handle 404 errors specifically for missing repository
        if isinstance(e, HTTPError) and e.response.status_code == 404:
            error_details = e.response.json().get('error', {})
            if error_details.get('type') == 'repository_missing_exception':
                logger.info(f"Repository '{repository}' is missing. Skipping snapshot clearing.")
                return f"Repository '{repository}' does not exist, all snapshots are deleted."

        logger.debug(f"Error clearing snapshots from repository '{repository}': {e}")
        ex = FailedToDeleteSnapshot()
        ex.add_note(f"Cause {str(e)}")
        raise ex
    
    return f"All snapshots cleared from repository '{repository}'"


def delete_snapshot_repo(cluster: Cluster, repository: str) -> str:
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
                return f"Repository '{repository}' does not exist"
        logger.debug(f"Error deleting repository '{repository}': {e}")
        ex = FailedToDeleteSnapshotRepo()
        ex.add_note(f"Cause {str(e)}")
        raise ex
    
    return f"Repository '{repository}' deleted"


class SnapshotSourceType(str, Enum):
    filesystem = "filesytem"
    s3 = "s3"


class SnapshotSource(BaseModel):
    type: SnapshotSourceType


class FileSystemSnapshotSource(SnapshotSource):
    type: SnapshotSourceType = SnapshotSourceType.filesystem
    path: str


class S3SnapshotSource(SnapshotSource):
    type: SnapshotSourceType = SnapshotSourceType.s3
    uri: str
    region: str


SnapshotType: TypeAlias = FileSystemSnapshotSource | S3SnapshotSource


class SnapshotConfig(BaseModel):
    snapshot_name: str
    repository_name: str
    index_allow: List[str]
    source: SnapshotType


def _resolve_index_patterns(cluster: Cluster, index_patterns: Optional[List[str]]) -> Optional[str]:
    """
    Resolve index patterns to concrete indices including hidden/closed indices.
    """
    if not index_patterns:
        return None

    # Resolve via _resolve/index to capture indices + backing indices of data streams.
    resolve = cluster.call_api(
        "/_resolve/index",
        params={
            "name": ",".join(index_patterns),
            "expand_wildcards": "all",
        },
    ).json()

    concrete = [i["name"] for i in resolve.get("indices", [])]

    # Include backing indices for any matched data streams
    for ds in resolve.get("data_streams", []):
        backing_indices = ds.get("backing_indices", [])
        backing_index_names = [bi["name"] for bi in backing_indices]
        concrete.extend(backing_index_names)

    # Deduplicate while preserving order
    seen = set()
    unique_indices = []
    for x in concrete:
        if x not in seen:
            seen.add(x)
            unique_indices.append(x)

    return ",".join(unique_indices) if unique_indices else None


def _get_index_stats(cluster: Cluster, targets: Optional[str]) -> Dict:
    """
    Fetch document count and size statistics for indices.
    """
    path = f"/{targets}/_stats" if targets else "/_stats"
    params = {
        "level": "indices",
        "filter_path": (
            "indices.*.primaries.docs.count,"
            "indices.*.primaries.docs.deleted,"
            "indices.*.primaries.store.size_in_bytes"
        ),
        "expand_wildcards": "all",
        "metric": "docs,store",
    }
    
    stats = cluster.call_api(path, params=params).json()
    return stats.get("indices", {}) or {}


def _get_shard_counts(cluster: Cluster, targets: Optional[str]) -> Dict[str, int]:
    """
    Retrieve shard counts for indices from the _settings endpoint.
    """
    settings_path = f"/{targets}/_settings" if targets else "/_settings"
    settings_params = {
        "filter_path": "*.settings.index.number_of_shards",
        "expand_wildcards": "all",
    }
    
    settings_response = cluster.call_api(settings_path, params=settings_params).json()

    # Create a mapping of index name to shard count
    shard_count_map = {}
    for index_name, index_data in settings_response.items():
        # The settings path might include the index name as a prefix
        clean_name = index_name.split(".")[-1]
        try:
            num_shards = int(index_data.get("settings", {}).get("index", {}).get("number_of_shards", 0))
            shard_count_map[clean_name] = num_shards
        except (ValueError, AttributeError):
            # In case of parsing issues, default to 0
            shard_count_map[clean_name] = 0
            
    return shard_count_map


def _build_index_list(indices: Dict, shard_count_map: Dict[str, int]) -> List[SnapshotIndex]:
    """
    Build and sort a list of SnapshotIndex objects.
    """
    index_list: List[SnapshotIndex] = []

    for name, body in indices.items():
        prim = body.get("primaries", {})
        docs = prim.get("docs", {})
        store = prim.get("store", {})

        # Live docs (excludes deletions)
        doc_count = int(docs.get("count", 0) or 0)
        size_bytes = int(store.get("size_in_bytes", 0) or 0)
        
        shard_count = shard_count_map.get(name, 0)

        index_list.append(SnapshotIndex(
            name=name,
            document_count=doc_count,
            size_bytes=size_bytes,
            shard_count=shard_count,
        ))

    # Stable ordering
    index_list.sort(key=lambda x: x.name)
    return index_list


def get_cluster_indexes(cluster: Cluster, index_patterns: Optional[List[str]] = None) -> SnapshotIndexes:
    """
    Programmatic, more reliable index sizing:
    - Uses /_stats/docs,store (primary bytes & doc counts)
    - Includes hidden/closed indices if patterns match
    - Resolves data streams to backing indices
    """
    if not cluster:
        raise NoSourceClusterDefinedError()

    try:
        targets = _resolve_index_patterns(cluster, index_patterns)
        indices = _get_index_stats(cluster, targets)
        shard_count_map = _get_shard_counts(cluster, targets)
        index_list = _build_index_list(indices, shard_count_map)
        
        return SnapshotIndexes(indexes=index_list)

    except Exception as e:
        logger.error(f"Failed to fetch index information via _stats: {e}")
        raise


class FailedToCreateSnapshot(Exception):
    pass


class FailedToDeleteSnapshot(Exception):
    pass


class FailedToDeleteSnapshotRepo(Exception):
    pass
