from abc import ABC, abstractmethod
from enum import Enum
import logging
import subprocess
from typing import Dict, Optional
from console_link.models.cluster import Cluster
from console_link.models.command_result import CommandResult
from cerberus import Validator

logger = logging.getLogger(__name__)

SnapshotStatus = Enum(
    "SnapshotStatus", [
        "NOT_STARTED",
        "RUNNING",
        "COMPLETED",
        "FAILED"])


class Snapshot(ABC):
    """
    Interface for creating and managing snapshots.
    """
    def __init__(self, config: Dict, source_cluster: Cluster, target_cluster: Optional[Cluster] = None) -> None:
        self.config = config
        self.source_cluster = source_cluster
        self.target_cluster = target_cluster

    @abstractmethod
    def create(self, *args, **kwargs) -> CommandResult:
        """Create a snapshot."""
        pass

    @abstractmethod
    def status(self, *args, **kwargs) -> CommandResult:
        """Get the status of the snapshot."""
        pass


S3_SNAPSHOT_SCHEMA = {
    'snapshot_name': {
        'type': 'string',
        'required': True
    },
    's3_repo_uri': {
        'type': 'string',
        'required': True
    },
    's3_region': {
        'type': 'string',
        'required': True
    }
}


class S3Snapshot(Snapshot):
    def __init__(self, config: Dict, source_cluster: Cluster, target_cluster: Optional[Cluster] = None) -> None:
        super().__init__(config, source_cluster, target_cluster)
        v = Validator(S3_SNAPSHOT_SCHEMA)
        if not v.validate(config):
            raise ValueError("Invalid config file for snapshot", v.errors)
        self.snapshot_name = config['snapshot_name']
        self.s3_repo_uri = config['s3_repo_uri']
        self.s3_region = config['s3_region']

    def create(self, *args, **kwargs) -> CommandResult:
        command = [
            "/root/createSnapshot/bin/CreateSnapshot",
            "--snapshot-name", self.snapshot_name,
            "--s3-repo-uri", self.s3_repo_uri,
            "--s3-region", self.s3_region,
            "--source-host", self.source_cluster.endpoint,
            "--target-host", self.target_cluster.endpoint,
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
