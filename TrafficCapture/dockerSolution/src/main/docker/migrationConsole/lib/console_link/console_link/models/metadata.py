from typing import Optional
import tempfile
import logging

from cerberus import Validator

from console_link.models.command_result import CommandResult
from console_link.models.command_runner import CommandRunner, CommandRunnerError, FlagOnlyArgument
from console_link.models.schema_tools import list_schema
from console_link.models.cluster import AuthMethod, Cluster
from console_link.models.snapshot import S3Snapshot, Snapshot, FileSystemSnapshot
from typing import Any, Dict, List

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
        "otel_endpoint": {"type": "string", "required": False},
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
    "otel_endpoint": {"type": "string", "required": False},
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
        self._otel_endpoint = config.get("otel_endpoint", None)

        logger.debug(f"Min replicas: {self._min_replicas}")
        logger.debug(f"Index allowlist: {self._index_allowlist}")
        logger.debug(f"Index template allowlist: {self._index_template_allowlist}")
        logger.debug(f"Component template allowlist: {self._component_template_allowlist}")
        logger.debug(f"Otel endpoint: {self._otel_endpoint}")

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

    def _appendArgs(self, commands: Dict[str, Any], args_to_add: List[str]) -> None:
        if args_to_add == None:
            return

        def isCommand(arg: str) -> bool:
            if arg == None:
                return False
            return arg.startswith('--') or arg.startswith('-')

        def isValue(arg: str) -> bool:
            if arg == None:
                return False
            return not isCommand(arg)

        i = 0
        while i < len(args_to_add):
            arg = args_to_add[i]
            nextArg = args_to_add[i + 1] if (i + 1 < len(args_to_add)) else None

            if isCommand(arg) and isValue(nextArg):
                commands[arg] = nextArg
                i += 2  # Move past the command and value
            elif isCommand(arg):
                commands[arg] = None
                i += 1  # Move past the command, its a flag
            else:
                logger.warning(f"Ignoring extra value {arg}, there was no command name before it")
                i += 1

    def migrate(self, detached_log=None, extra_args=None) -> CommandResult:
        logger.info("Starting metadata migration")
        command_base = "/root/metadataMigration/bin/MetadataMigration"
        command_args = {
            "--snapshot-name": self._snapshot_name,
            "--target-host": self._target_cluster.endpoint,
            "--min-replicas": self._min_replicas
        }
        if self._snapshot_location == 's3':
            command_args.update({
                "--s3-local-dir": self._local_dir,
                "--s3-repo-uri": self._s3_uri,
                "--s3-region": self._aws_region,
            })
        elif self._snapshot_location == 'fs':
            command_args.update({
                "--file-system-repo-path": self._repo_path,
            })

        if self._target_cluster.auth_type == AuthMethod.BASIC_AUTH:
            try:
                command_args.update({
                    "--target-username": self._target_cluster.auth_details.get("username"),
                    "--target-password": self._target_cluster.get_basic_auth_password()
                })
                logger.info("Using basic auth for target cluster")
            except KeyError as e:
                raise ValueError(f"Missing required auth details for target cluster: {e}")
        elif self._target_cluster.auth_type == AuthMethod.SIGV4:
            signing_name, region = self._target_cluster._get_sigv4_details(force_region=True)
            logger.info(f"Using sigv4 auth for target cluster with signing_name {signing_name} and region {region}")
            command_args.update({
                "--target-aws-service-signing-name": signing_name,
                "--target-aws-region": region
            })

        if self._target_cluster.allow_insecure:
            command_args.update({"--target-insecure": FlagOnlyArgument})

        if self._index_allowlist:
            command_args.update({"--index-allowlist": ",".join(self._index_allowlist)})

        if self._index_template_allowlist:
            command_args.update({"--index-template-allowlist": ",".join(self._index_template_allowlist)})

        if self._component_template_allowlist:
            command_args.update({"--component-template-allowlist": ",".join(self._component_template_allowlist)})

        if self._otel_endpoint:
            command_args.update({"--otel-collector-endpoint": self._otel_endpoint})

        # Extra args might not be represented with dictionary, so convert args to list and append commands
        self._appendArgs(command_args, extra_args)

        command_runner = CommandRunner(command_base, command_args,
                                       sensitive_fields=["--target-password"],
                                       run_as_detatched=detached_log is not None,
                                       log_file=detached_log)
        logger.info(f"Migrating metadata with command: {' '.join(command_runner.sanitized_command())}")
        try:
            return command_runner.run()
        except CommandRunnerError as e:
            logger.error(f"Metadata migration failed: {e}")
            return CommandResult(success=False, value=f"Metadata migration failed: {e}")
