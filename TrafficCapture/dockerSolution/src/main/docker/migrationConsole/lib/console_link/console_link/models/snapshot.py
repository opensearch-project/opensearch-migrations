import argparse
import datetime
import logging
from abc import ABC, abstractmethod
from typing import Dict, Optional

from cerberus import Validator
from console_link.models.cluster import AuthMethod, Cluster, HttpMethod
from console_link.models.command_result import CommandResult
from console_link.models.command_runner import CommandRunner, CommandRunnerError, FlagOnlyArgument
from console_link.models.schema_tools import contains_one_of

logger = logging.getLogger(__name__)


SNAPSHOT_SCHEMA = {
    'snapshot': {
        'type': 'dict',
        'schema': {
            'snapshot_name': {'type': 'string', 'required': True},
            'otel_endpoint': {'type': 'string', 'required': False},
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
    def __init__(self, config: Dict, source_cluster: Cluster) -> None:
        self.config = config
        self.source_cluster = source_cluster
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

    @abstractmethod
    def delete(self, *args, **kwargs) -> CommandResult:
        """Delete a snapshot."""
        pass

    def _collect_universal_command_args(self) -> Dict:
        command_args = {
            "--snapshot-name": self.snapshot_name,
            "--source-host": self.source_cluster.endpoint
        }

        if self.source_cluster.auth_type == AuthMethod.BASIC_AUTH:
            try:
                command_args.update({
                    "--source-username": self.source_cluster.auth_details.get("username"),
                    "--source-password": self.source_cluster.get_basic_auth_password()
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


S3_SNAPSHOT_SCHEMA = {
    'snapshot_name': {'type': 'string', 'required': True},
    'otel_endpoint': {'type': 'string', 'required': False},
    's3_repo_uri': {'type': 'string', 'required': True},
    's3_region': {'type': 'string', 'required': True}
}


class S3Snapshot(Snapshot):
    def __init__(self, config: Dict, source_cluster: Cluster) -> None:
        super().__init__(config, source_cluster)
        self.snapshot_name = config['snapshot_name']
        self.otel_endpoint = config.get("otel_endpoint", None)
        self.s3_repo_uri = config['s3']['repo_uri']
        self.s3_region = config['s3']['aws_region']

    def create(self, *args, **kwargs) -> CommandResult:
        assert isinstance(self.source_cluster, Cluster)
        base_command = "/root/createSnapshot/bin/CreateSnapshot"

        s3_command_args = {
            "--s3-repo-uri": self.s3_repo_uri,
            "--s3-region": self.s3_region,
        }

        command_args = self._collect_universal_command_args()
        command_args.update(s3_command_args)

        wait = kwargs.get('wait', False)
        max_snapshot_rate_mb_per_node = kwargs.get('max_snapshot_rate_mb_per_node')

        if not wait:
            command_args["--no-wait"] = FlagOnlyArgument
        if max_snapshot_rate_mb_per_node is not None:
            command_args["--max-snapshot-rate-mb-per-node"] = max_snapshot_rate_mb_per_node

        command_runner = CommandRunner(base_command, command_args, sensitive_fields=["--source-password"])
        try:
            command_runner.run()
            logger.info(f"Snapshot {self.config['snapshot_name']} creation initiated successfully")
            return CommandResult(success=True,
                                 value=f"Snapshot {self.config['snapshot_name']} creation initiated successfully")
        except CommandRunnerError as e:
            logger.error(f"Failed to create snapshot: {str(e)}")
            return CommandResult(success=False, value=f"Failed to create snapshot: {str(e)}")

    def status(self, *args, deep_check=False, **kwargs) -> CommandResult:
        if deep_check:
            return get_snapshot_status_full(self.source_cluster, self.snapshot_name)
        return get_snapshot_status(self.source_cluster, self.snapshot_name)

    def delete(self, *args, **kwargs) -> CommandResult:
        return delete_snapshot(self.source_cluster, self.snapshot_name)


class FileSystemSnapshot(Snapshot):
    def __init__(self, config: Dict, source_cluster: Cluster) -> None:
        super().__init__(config, source_cluster)
        self.snapshot_name = config['snapshot_name']
        self.otel_endpoint = config.get("otel_endpoint", None)
        self.repo_path = config['fs']['repo_path']

    def create(self, *args, **kwargs) -> CommandResult:
        assert isinstance(self.source_cluster, Cluster)
        base_command = "/root/createSnapshot/bin/CreateSnapshot"

        command_args = self._collect_universal_command_args()
        command_args["--file-system-repo-path"] = self.repo_path

        max_snapshot_rate_mb_per_node = kwargs.get('max_snapshot_rate_mb_per_node')

        if max_snapshot_rate_mb_per_node is not None:
            command_args["--max-snapshot-rate-mb-per-node"] = max_snapshot_rate_mb_per_node

        command_runner = CommandRunner(base_command, command_args, sensitive_fields=["--source-password"])
        try:
            command_runner.run()
            logger.info(f"Snapshot {self.config['snapshot_name']} creation initiated successfully")
            return CommandResult(success=True,
                                 value=f"Snapshot {self.config['snapshot_name']} creation initiated successfully")
        except CommandRunnerError as e:
            logger.error(f"Failed to create snapshot: {str(e)}")
            return CommandResult(success=False, value=f"Failed to create snapshot: {str(e)}")

    def status(self, *args, **kwargs) -> CommandResult:
        raise NotImplementedError("Status check for FileSystemSnapshot is not implemented yet.")

    def delete(self, *args, **kwargs) -> CommandResult:
        return delete_snapshot(self.source_cluster, self.snapshot_name)


def parse_args():
    parser = argparse.ArgumentParser(description="Elasticsearch snapshot status checker.")
    parser.add_argument("--endpoint", help="Elasticsearch endpoint.", required=True)
    parser.add_argument("--username", help="Cluster username.", default=None)
    parser.add_argument("--password", help="Cluster password.", default=None)
    parser.add_argument("--no-auth", action='store_true', help="Flag to provide no auth in requests.")
    parser.add_argument("--debug", action='store_true', help="Enable debug logging.")
    parser.add_argument("--detailed", action='store_true', help="Always get detailed status for completed snapshots.")
    return parser.parse_args()


def get_snapshot_status(cluster: Cluster, snapshot: str,
                        repository: str = 'migration_assistant_repo') -> CommandResult:
    path = f"/_snapshot/{repository}/{snapshot}"
    try:
        response = cluster.call_api(path, HttpMethod.GET)
        logging.debug(f"Raw get snapshot status response: {response.text}")
        response.raise_for_status()

        snapshot_data = response.json()
        snapshots = snapshot_data.get('snapshots', [])
        if not snapshots:
            return CommandResult(success=False, value="Snapshot not started")

        return CommandResult(success=True, value=snapshots[0].get("state"))
    except Exception as e:
        return CommandResult(success=False, value=f"Failed to get snapshot status: {str(e)}")


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


def get_snapshot_status_full(cluster: Cluster, snapshot: str,
                             repository: str = 'migration_assistant_repo') -> CommandResult:
    try:
        repository = repository if repository != '*' else get_repository_for_snapshot(cluster, snapshot)

        path = f"/_snapshot/{repository}/{snapshot}"
        response = cluster.call_api(path, HttpMethod.GET)
        logging.debug(f"Raw get snapshot status response: {response.text}")
        response.raise_for_status()

        snapshot_data = response.json()
        snapshots = snapshot_data.get('snapshots', [])
        if not snapshots:
            return CommandResult(success=False, value="Snapshot not started")

        snapshot_info = snapshots[0]
        state = snapshot_info.get("state")

        path = f"/_snapshot/{repository}/{snapshot}/_status"
        response = cluster.call_api(path, HttpMethod.GET)
        logging.debug(f"Raw get snapshot status full response: {response.text}")
        response.raise_for_status()

        snapshot_data = response.json()
        snapshots = snapshot_data.get('snapshots', [])
        if not snapshots:
            return CommandResult(success=False, value="Snapshot status not available")

        message = get_snapshot_status_message(snapshots[0])
        return CommandResult(success=True, value=f"{state}\n{message}")
    except Exception as e:
        return CommandResult(success=False, value=f"Failed to get full snapshot status: {str(e)}")


def delete_snapshot(cluster: Cluster, snapshot_name: str, repository: str = 'migration_assistant_repo'):
    repository = repository if repository != '*' else get_repository_for_snapshot(cluster, snapshot_name)

    path = f"/_snapshot/{repository}/{snapshot_name}"
    response = cluster.call_api(path, HttpMethod.DELETE)
    logging.debug(f"Raw delete snapshot status response: {response.text}")
