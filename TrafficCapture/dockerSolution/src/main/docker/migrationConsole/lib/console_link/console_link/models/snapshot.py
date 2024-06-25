import argparse
import datetime
import logging
import subprocess
from abc import ABC, abstractmethod
from enum import Enum
from typing import Dict, Optional, Tuple

from cerberus import Validator
from console_link.models.cluster import AuthMethod, Cluster, HttpMethod
from console_link.models.command_result import CommandResult

from console_link.models.schema_tools import contains_one_of

logger = logging.getLogger(__name__)


class SnapshotStatus(Enum):
    NOT_STARTED = "NOT_STARTED"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


SNAPSHOT_SCHEMA = {
    'snapshot': {
        'type': 'dict',
        'schema': {
            'snapshot_name': {'type': 'string', 'required': True},
            's3': {
                'type': 'dict',
                'schema': {
                    'repo_uri': {'type': 'string', 'required': True},
                    'aws_region': {'type': 'string', 'required': True},
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
    def __init__(self, config: Dict, source_cluster: Cluster, target_cluster: Optional[Cluster] = None) -> None:
        self.config = config
        self.source_cluster = source_cluster
        self.target_cluster = target_cluster
        v = Validator(SNAPSHOT_SCHEMA)
        if not v.validate({'snapshot': config}):
            raise ValueError("Invalid config file for snapshot", v.errors)

    @abstractmethod
    def create(self, *args, **kwargs) -> CommandResult:
        """Create a snapshot."""
        pass

    @abstractmethod
    def status(self, *args, **kwargs) -> CommandResult:
        """Get the status of the snapshot."""
        pass


S3_SNAPSHOT_SCHEMA = {
    'snapshot_name': {'type': 'string', 'required': True},
    's3_repo_uri': {'type': 'string', 'required': True},
    's3_region': {'type': 'string', 'required': True}
}


class S3Snapshot(Snapshot):
    def __init__(self, config: Dict, source_cluster: Cluster, target_cluster: Cluster) -> None:
        super().__init__(config, source_cluster, target_cluster)
        self.snapshot_name = config['snapshot_name']
        self.s3_repo_uri = config['s3']['repo_uri']
        self.s3_region = config['s3']['aws_region']

    def create(self, *args, **kwargs) -> CommandResult:
        assert isinstance(self.target_cluster, Cluster)
        if self.source_cluster.auth_type != AuthMethod.NO_AUTH:
            raise NotImplementedError("Source cluster authentication is not supported for creating snapshots")

        if self.target_cluster.auth_type != AuthMethod.NO_AUTH:
            raise NotImplementedError("Target cluster authentication is not supported for creating snapshots")
        wait = kwargs.get('wait', False)
        max_snapshot_rate_mb_per_node = kwargs.get('max_snapshot_rate_mb_per_node')
        command = [
            "/root/createSnapshot/bin/CreateSnapshot",
            "--snapshot-name", self.snapshot_name,
            "--s3-repo-uri", self.s3_repo_uri,
            "--s3-region", self.s3_region,
            "--source-host", self.source_cluster.endpoint
        ]

        if self.source_cluster.allow_insecure:
            command.append("--source-insecure")
        if self.target_cluster.allow_insecure:
            command.append("--target-insecure")
        if not wait:
            command.append("--no-wait")
        if max_snapshot_rate_mb_per_node is not None:
            command.extend(["--max-snapshot-rate-mb-per-node",
                            str(max_snapshot_rate_mb_per_node)])

        logger.info(f"Creating snapshot with command: {' '.join(command)}")
        try:
            # Pass None to stdout and stderr to not capture output and show in terminal
            subprocess.run(command, stdout=None, stderr=None, text=True, check=True)
            logger.info(f"Snapshot {self.config['snapshot_name']} created successfully")
            return CommandResult(success=True, value=f"Snapshot {self.config['snapshot_name']} created successfully")
        except subprocess.CalledProcessError as e:
            logger.error(f"Failed to create snapshot: {str(e)}")
            return CommandResult(success=False, value=f"Failed to create snapshot: {str(e)}")

    def status(self, *args, **kwargs) -> CommandResult:
        return CommandResult(success=False, value="Command not implemented")


class FileSystemSnapshot(Snapshot):
    def __init__(self, config: Dict, source_cluster: Cluster, target_cluster: Cluster) -> None:
        super().__init__(config, source_cluster, target_cluster)
        self.snapshot_name = config['snapshot_name']
        self.repo_path = config['fs']['repo_path']

    def create(self, *args, **kwargs) -> CommandResult:
        assert isinstance(self.target_cluster, Cluster)

        if self.source_cluster.auth_type != AuthMethod.NO_AUTH:
            raise NotImplementedError("Source cluster authentication is not supported for creating snapshots")

        if self.target_cluster.auth_type != AuthMethod.NO_AUTH:
            raise NotImplementedError("Target cluster authentication is not supported for creating snapshots")

        command = [
            "/root/createSnapshot/bin/CreateSnapshot",
            "--snapshot-name", self.snapshot_name,
            "--file-system-repo-path", self.repo_path,
            "--source-host", self.source_cluster.endpoint,
        ]

        if self.source_cluster.allow_insecure:
            command.append("--source-insecure")

        logger.info(f"Creating snapshot with command: {' '.join(command)}")
        try:
            subprocess.run(command, stdout=None, stderr=None, text=True, check=True)
            message = f"Snapshot {self.snapshot_name} created successfully"
            logger.info(message)
            return CommandResult(success=True, value=message)
        except subprocess.CalledProcessError as e:
            message = f"Failed to create snapshot: {str(e)}"
            logger.error(message)
            return CommandResult(success=False, value=message)

    def status(self, *args, **kwargs) -> Tuple[SnapshotStatus, str]:
        deep_check = kwargs.get('deep_check', False)
        if deep_check:
            return get_snapshot_status_full(self.source_cluster, self.snapshot_name)
        return get_snapshot_status(self.source_cluster, self.snapshot_name)


def parse_args():
    parser = argparse.ArgumentParser(description="Elasticsearch snapshot status checker.")
    parser.add_argument("--endpoint", help="Elasticsearch endpoint.", required=True)
    parser.add_argument("--username", help="Cluster username.", default=None)
    parser.add_argument("--password", help="Cluster password.", default=None)
    parser.add_argument("--no-auth", action='store_true', help="Flag to provide no auth in requests.")
    parser.add_argument("--debug", action='store_true', help="Enable debug logging.")
    parser.add_argument("--detailed", action='store_true', help="Always get detailed status for completed snapshots.")
    return parser.parse_args()


class ClusterSnapshotState(Enum):
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    PARTIAL = "PARTIAL"
    STARTED = "STARTED"
    IN_PROGRESS = "IN_PROGRESS"


def convert_snapshot_state_to_status(snapshot_state: str) -> Tuple[SnapshotStatus, str]:
    state_mapping = {
        ClusterSnapshotState.SUCCESS.value: (SnapshotStatus.COMPLETED, "Snapshot completed successfully"),
        ClusterSnapshotState.FAILED.value: (SnapshotStatus.FAILED, "Snapshot failed"),
        ClusterSnapshotState.PARTIAL.value: (SnapshotStatus.FAILED, "Snapshot is partially completed"),
        ClusterSnapshotState.STARTED.value: (SnapshotStatus.RUNNING, "Snapshot is running"),
        ClusterSnapshotState.IN_PROGRESS.value: (SnapshotStatus.RUNNING, "Snapshot is running")
    }
    return state_mapping.get(snapshot_state, (SnapshotStatus.FAILED, f"Unknown snapshot state: {snapshot_state}"))


def get_snapshot_status(cluster: Cluster, snapshot: str, repository: str = 'migration_assistant_repo')\
        -> Tuple[SnapshotStatus, str]:
    path = f"/_snapshot/{repository}/{snapshot}"
    response = cluster.call_api(path, HttpMethod.GET)
    logging.debug(f"Raw get snapshot status response: {response.text}")
    response.raise_for_status()

    snapshot_data = response.json()
    snapshots = snapshot_data.get('snapshots', [])
    if not snapshots:
        return SnapshotStatus.NOT_STARTED, "No snapshots found in the response"

    snapshot_state = snapshots[0].get("state")
    return convert_snapshot_state_to_status(snapshot_state)


def get_repository_for_snapshot(cluster: Cluster, snapshot: str) -> Optional[str]:
    url = f"/_snapshot/*/{snapshot}"
    response = cluster.call_api(url, HttpMethod.GET)
    logging.debug(f"Raw response: {response.text}")
    response.raise_for_status()

    snapshot_data = response.json()
    snapshots = snapshot_data.get('snapshots', [])
    if not snapshots:
        logging.debug(f"Snapshot {snapshot} not found in any repository")
        return None

    return snapshots[0].get("repository")


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
    duration_formatted = format_duration(duration_in_millis)

    anticipated_duration_remaining_formatted = (
        format_duration((duration_in_millis / percent_completed) * (100 - percent_completed))
        if percent_completed > 0 else "N/A (not enough data to compute)"
    )

    throughput_mib_per_sec = (
        (processed_size_in_bytes / (1024 ** 2)) / (duration_in_millis / 1000)
        if duration_in_millis > 0 else 0
    )

    return (
        f"Snapshot is {snapshot_state}.\n"
        f"Percent completed: {percent_completed:.2f}%\n"
        f"Data GiB done: {processed_size_gibibytes:.3f}/{total_size_gibibytes:.3f}\n"
        f"Total shards: {total_shards}\n"
        f"Successful shards: {successful_shards}\n"
        f"Failed shards: {failed_shards}\n"
        f"Start time: {start_time_formatted}\n"
        f"Duration: {duration_formatted}\n"
        f"Anticipated duration remaining: {anticipated_duration_remaining_formatted}\n"
        f"Throughput: {throughput_mib_per_sec:.2f} MiB/sec"
    )


def get_snapshot_status_full(cluster: Cluster, snapshot: str, repository: str = 'migration_assistant_repo')\
        -> Tuple[SnapshotStatus, str]:
    repository = repository if repository != '*' else get_repository_for_snapshot(cluster, snapshot)

    path = f"/_snapshot/{repository}/{snapshot}"
    response = cluster.call_api(path, HttpMethod.GET)
    logging.debug(f"Raw get snapshot status response: {response.text}")
    response.raise_for_status()

    snapshot_data = response.json()
    snapshots = snapshot_data.get('snapshots', [])
    if not snapshots:
        return SnapshotStatus.NOT_STARTED, "No snapshots found in the response"

    snapshot_info = snapshots[0]
    snapshot_state = snapshot_info.get("state")
    state, _ = convert_snapshot_state_to_status(snapshot_state)

    path = f"/_snapshot/{repository}/{snapshot}/_status"
    response = cluster.call_api(path, HttpMethod.GET)
    logging.debug(f"Raw get snapshot status full response: {response.text}")
    response.raise_for_status()

    snapshot_data = response.json()
    snapshots = snapshot_data.get('snapshots', [])
    if not snapshots:
        return SnapshotStatus.NOT_STARTED, "No snapshots found in the response"

    snapshot_info = snapshots[0]
    message = get_snapshot_status_message(snapshot_info)
    return state, message
