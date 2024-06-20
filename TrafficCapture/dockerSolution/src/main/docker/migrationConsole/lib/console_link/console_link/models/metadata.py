import subprocess
from typing import Optional
from cerberus import Validator
import tempfile
import logging

from console_link.models.command_result import CommandResult
from console_link.models.schema_tools import list_schema
from console_link.models.cluster import AuthMethod, Cluster
from console_link.models.snapshot import S3Snapshot, Snapshot

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
            "type": "dict",
            # Again, in the future there should be a 'local' option, but for now this block is required if the
            # snapshot details are being specified.
            "required": True,
            "schema": {
                "repo_uri": {"type": "string", "required": True},
                "aws_region": {"type": "string", "required": True}
            }
        },
    }
}

SCHEMA = {
    "from_snapshot": FROM_SNAPSHOT_SCHEMA,
    "min_replicas": {"type": "integer", "min": 0, "required": False},
    "index_allowlist": list_schema(required=False),
    "index_template_allowlist": list_schema(required=False),
    "component-template-allowlist": list_schema(required=False)
}


def generate_tmp_dir(name: str) -> str:
    return tempfile.mkdtemp(prefix=f"migration-{name}-")


class Metadata:
    def __init__(self, config, target_cluster: Cluster, snapshot: Optional[Snapshot] = None):
        v = Validator(SCHEMA)
        if not v.validate(config):
            raise ValueError(v.errors)
        self._config = config
        self._target_cluster = target_cluster

        if not isinstance(snapshot, S3Snapshot):
            # We can't currently use the snapshot info unless it's in S3. We need to ignore it.
            logger.info("Ignoring externally specified snapshot info since it's not in S3.")
            snapshot = None

        if (not snapshot) and (config["from_snapshot"] is None):
            raise ValueError("No snapshot is specified or can be assumed "
                             "for the metadata migration to use.")
        
        self._min_replicas = config.get("min_replicas", 0)
        self._index_allowlist = config.get("index_allowlist", None)
        self._index_template_allowlist = config.get("index_template_allowlist", None)
        self._component_template_allowlist = config.get("component-template-allowlist", None)

        # If `from_snapshot` is fully specified, use those values to define snapshot params
        if config["from_snapshot"] is not None:
            logger.info("Defining snapshot params for metadata migration from config file")
            self._snapshot_name = config["from_snapshot"]["snapshot_name"]
            if "local_dir" in config["from_snapshot"]:
                self._local_dir = config["from_snapshot"]["local_dir"]
            else:
                self._local_dir = generate_tmp_dir(self._snapshot_name)
            self._s3_uri = config["from_snapshot"]["s3"]["repo_uri"]
            self._aws_region = config["from_snapshot"]["s3"]["aws_region"]
        else:
            assert snapshot is not None  # This follows from the logic above, but I'm asserting it to make mypy happy
            logger.info("Defining snapshot params for metadata migration from Snapshot object")
            self._snapshot_name = snapshot.snapshot_name
            self._local_dir = generate_tmp_dir(self._snapshot_name)
            self._s3_uri = snapshot.s3_repo_uri
            self._aws_region = snapshot.s3_region
        
        logger.debug(f"Snapshot name: {self._snapshot_name}")
        logger.debug(f"Local dir: {self._local_dir}")
        logger.debug(f"S3 URI: {self._s3_uri}")
        logger.debug(f"AWS region: {self._aws_region}")
        logger.debug(f"Min replicas: {self._min_replicas}")
        logger.debug(f"Index allowlist: {self._index_allowlist}")
        logger.debug(f"Index template allowlist: {self._index_template_allowlist}")
        logger.debug(f"Component template allowlist: {self._component_template_allowlist}")
        logger.info("Metadata migration configuration defined")

    def migrate(self) -> CommandResult:
        password_field_index = None
        command = [
            "/root/metadataMigration/bin/MetadataMigration",
            # Initially populate only the required params
            "--snapshot-name", self._snapshot_name,
            "--s3-local-dir", self._local_dir,
            "--s3-repo-uri", self._s3_uri,
            "--s3-region", self._aws_region,
            "--target-host", self._target_cluster.endpoint,
            "--min-replicas", str(self._min_replicas)
        ]
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

        if self._index_allowlist:
            command.extend(["--index-allowlist", ", ".join(self._index_allowlist)])

        if self._index_template_allowlist:
            command.extend(["--index-template-allowlist", ", ".join(self._index_template_allowlist)])

        if self._component_template_allowlist:
            command.extend(["--component-template-allowlist", ", ".join(self._component_template_allowlist)])

        if password_field_index:
            display_command = command[:password_field_index] + ["********"] + command[password_field_index:]
        else:
            display_command = command
        logger.info(f"Creating snapshot with command: {' '.join(display_command)}")

        try:
            # Pass None to stdout and stderr to not capture output and show in terminal
            subprocess.Popen(command, stdout=None, stderr=None, text=True, start_new_session=True)
            logger.info(f"Metadata migration for snapshot {self._snapshot_name} initiated")
            return CommandResult(success=True, value="Metadata migration initiated")
        except subprocess.CalledProcessError as e:
            logger.error(f"Failed to create snapshot: {str(e)}")
            return CommandResult(success=False, value=f"Failed to migrate metadata: {str(e)}")
