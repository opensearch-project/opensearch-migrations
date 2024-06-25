import os
import subprocess
from typing import Optional
from cerberus import Validator
import tempfile
import logging

from console_link.models.command_result import CommandResult
from console_link.models.schema_tools import list_schema
from console_link.models.cluster import AuthMethod, Cluster
from console_link.models.snapshot import S3Snapshot, Snapshot, FileSystemSnapshot

logger = logging.getLogger(__name__)

FROM_SNAPSHOT_SCHEMA = {
    "type": "dict",
    # In the future, there should be a "from_snapshot" and a "from_live_cluster" option, but for now only snapshot is
    # supported, so this is required. It _can_ be null, but that requires a snapshot to defined on its own in
    # the services.yaml file.
    "required": True,
    "nullable": True,
    "schema": {
        "snapshot_name": {"type": "string", "required": True},
        "local_dir": {"type": "string", "required": False},
        "s3": {
            'type': 'dict',
            "required": False,
            'schema': {
                'repo_uri': {'type': 'string', 'required': True},
                'aws_region': {'type': 'string', 'required': True},
            }
        },
        "fs": {
            'type': 'dict',
            "required": False,
            'schema': {
                'repo_path': {'type': 'string', 'required': True},
            }
        }
    },
    # We _should_ have the check below, but I need to figure out how to combine it with a potentially
    # nullable block (like this one)
    # 'check_with': contains_one_of({'s3', 'fs'})

}

SCHEMA = {
    "from_snapshot": FROM_SNAPSHOT_SCHEMA,
    "min_replicas": {"type": "integer", "min": 0, "required": False},
    "index_allowlist": list_schema(required=False),
    "index_template_allowlist": list_schema(required=False),
    "component_template_allowlist": list_schema(required=False)
}


def generate_tmp_dir(name: str) -> str:
    return tempfile.mkdtemp(prefix=f"migration-{name}-")


class Metadata:
    def __init__(self, config, target_cluster: Cluster, snapshot: Optional[Snapshot] = None):
        logger.debug(f"Initializing Metadata with config: {config}")
        v = Validator(SCHEMA)
        if not v.validate(config):
            logger.error(f"Invalid config: {v.errors}")
            raise ValueError(v.errors)
        self._config = config
        self._target_cluster = target_cluster
        self._snapshot = snapshot

        if (not snapshot) and (config["from_snapshot"] is None):
            raise ValueError("No snapshot is specified or can be assumed "
                             "for the metadata migration to use.")

        self._min_replicas = config.get("min_replicas", 0)
        self._index_allowlist = config.get("index_allowlist", None)
        self._index_template_allowlist = config.get("index_template_allowlist", None)
        self._component_template_allowlist = config.get("component_template_allowlist", None)
        logger.debug(f"Min replicas: {self._min_replicas}")
        logger.debug(f"Index allowlist: {self._index_allowlist}")
        logger.debug(f"Index template allowlist: {self._index_template_allowlist}")
        logger.debug(f"Component template allowlist: {self._component_template_allowlist}")

        # If `from_snapshot` is fully specified, use those values to define snapshot params
        if config["from_snapshot"] is not None:
            logger.debug("Using fully specified snapshot config")
            self._init_from_config()
        else:
            logger.debug("Using independently specified snapshot")
            if isinstance(snapshot, S3Snapshot):
                self._init_from_s3_snapshot(snapshot)
            elif isinstance(snapshot, FileSystemSnapshot):
                self._init_from_fs_snapshot(snapshot)

        if config["from_snapshot"] is not None and "local_dir" in config["from_snapshot"]:
            self._local_dir = config["from_snapshot"]["local_dir"]
        else:
            self._local_dir = generate_tmp_dir(self._snapshot_name)

        logger.debug(f"Snapshot name: {self._snapshot_name}")
        if self._snapshot_location == 's3':
            logger.debug(f"S3 URI: {self._s3_uri}")
            logger.debug(f"AWS region: {self._aws_region}")
        else:
            logger.debug(f"Local dir: {self._local_dir}")

        logger.info("Metadata migration configuration defined")

    def _init_from_config(self) -> None:
        config = self._config
        self._snapshot_location = 's3' if 's3' in config["from_snapshot"] else 'fs'
        self._snapshot_name = config["from_snapshot"]["snapshot_name"]

        if self._snapshot_location == 'fs':
            self._repo_path = config["from_snapshot"]["fs"]["repo_path"]
        else:
            self._s3_uri = config["from_snapshot"]["s3"]["repo_uri"]
            self._aws_region = config["from_snapshot"]["s3"]["aws_region"]

    def _init_from_s3_snapshot(self, snapshot: S3Snapshot) -> None:
        self._snapshot_name = snapshot.snapshot_name
        self._snapshot_location = "s3"
        self._s3_uri = snapshot.s3_repo_uri
        self._aws_region = snapshot.s3_region

    def _init_from_fs_snapshot(self, snapshot: FileSystemSnapshot) -> None:
        self._snapshot_name = snapshot.snapshot_name
        self._snapshot_location = "fs"
        self._repo_path = snapshot.repo_path

    def migrate(self, detached_log=None) -> CommandResult:
        password_field_index = None
        command = [
            "/root/metadataMigration/bin/MetadataMigration",
            # Initially populate only the required params
            "--snapshot-name", self._snapshot_name,
            "--target-host", self._target_cluster.endpoint,
            "--min-replicas", str(self._min_replicas)
        ]
        if self._snapshot_location == 's3':
            command.extend([
                "--s3-local-dir", self._local_dir,
                "--s3-repo-uri", self._s3_uri,
                "--s3-region", self._aws_region,
            ])
        elif self._snapshot_location == 'fs':
            command.extend([
                "--file-system-repo-path", self._repo_path,
            ])

        if self._target_cluster.auth_details == AuthMethod.BASIC_AUTH:
            try:
                command.extend([
                    "--target-username", self._target_cluster.auth_details.get("username"),
                    "--target-password", self._target_cluster.auth_details.get("password")
                ])
                password_field_index = len(command) - 1
                logger.info("Using basic auth for target cluster")
            except KeyError as e:
                raise ValueError(f"Missing required auth details for target cluster: {e}")

        if self._target_cluster.allow_insecure:
            command.append("--target-insecure")

        if self._index_allowlist:
            command.extend(["--index-allowlist", ",".join(self._index_allowlist)])

        if self._index_template_allowlist:
            command.extend(["--index-template-allowlist", ",".join(self._index_template_allowlist)])

        if self._component_template_allowlist:
            command.extend(["--component-template-allowlist", ",".join(self._component_template_allowlist)])

        if password_field_index:
            display_command = command[:password_field_index] + ["********"] + command[password_field_index:]
        else:
            display_command = command
        logger.info(f"Migrating metadata with command: {' '.join(display_command)}")

        if detached_log:
            return self._run_as_detached_process(command, detached_log)
        return self._run_as_synchronous_process(command)

    def _run_as_synchronous_process(self, command) -> CommandResult:
        try:
            # Pass None to stdout and stderr to not capture output and show in terminal
            subprocess.run(command, stdout=None, stderr=None, text=True, check=True)
            logger.info(f"Metadata migration for snapshot {self._snapshot_name} completed")
            return CommandResult(success=True, value="Metadata migration completed")
        except subprocess.CalledProcessError as e:
            logger.error(f"Failed to migrate metadata: {str(e)}")
            return CommandResult(success=False, value=f"Failed to migrate metadata: {str(e)}")

    def _run_as_detached_process(self, command, log_file) -> CommandResult:
        try:
            with open(log_file, "w") as f:
                # Start the process in detached mode
                process = subprocess.Popen(command, stdout=f, stderr=subprocess.STDOUT, preexec_fn=os.setpgrp)
                logger.info(f"Metadata migration process started with PID {process.pid}")
                logger.info(f"Metadata migration logs available at {log_file}")
                return CommandResult(success=True, value=f"Metadata migration started with PID {process.pid}\n"
                                     f"Logs are being written to {log_file}")
        except subprocess.CalledProcessError as e:
            logger.error(f"Failed to create snapshot: {str(e)}")
            return CommandResult(success=False, value=f"Failed to migrate metadata: {str(e)}")
