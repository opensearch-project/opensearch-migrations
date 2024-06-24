from abc import ABC, abstractmethod
from enum import Enum
import logging
import subprocess
from typing import Dict, Optional
from console_link.models.cluster import AuthMethod, Cluster
from console_link.models.command_result import CommandResult
from cerberus import Validator

from console_link.models.schema_tools import contains_one_of

logger = logging.getLogger(__name__)

SnapshotStatus = Enum(
    "SnapshotStatus", [
        "NOT_STARTED",
        "RUNNING",
        "COMPLETED",
        "FAILED"])


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

        command = [
            "/root/createSnapshot/bin/CreateSnapshot",
            "--snapshot-name", self.snapshot_name,
            "--s3-repo-uri", self.s3_repo_uri,
            "--s3-region", self.s3_region,
            "--source-host", self.source_cluster.endpoint,
        ]

        if self.source_cluster.allow_insecure:
            command.append("--source-insecure")
        if self.target_cluster.allow_insecure:
            command.append("--target-insecure")

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

        if source_cluster.auth_type != AuthMethod.NO_AUTH:
            raise NotImplementedError("Source cluster authentication is not supported for creating snapshots")

        if target_cluster.auth_type != AuthMethod.NO_AUTH:
            raise NotImplementedError("Target cluster authentication is not supported for creating snapshots")

    def create(self, *args, **kwargs) -> CommandResult:
        assert isinstance(self.target_cluster, Cluster)

        command = [
            "/root/createSnapshot/bin/CreateSnapshot",
            "--snapshot-name", self.snapshot_name,
            "--file-system-repo-path", self.repo_path,
            "--source-host", self.source_cluster.endpoint,
        ]

        if self.source_cluster.allow_insecure:
            command.append("--source-insecure")
        if self.target_cluster.allow_insecure:
            command.append("--target-insecure")

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
