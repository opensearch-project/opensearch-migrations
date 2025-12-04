import tempfile
import logging
import json
from cerberus import Validator
from datetime import datetime, timezone
from pydantic import BaseModel, Field, field_validator, field_serializer
from typing import Optional, Any, Dict, List

from console_link.db import metadata_db
from console_link.models.command_result import CommandResult
from console_link.models.command_runner import CommandRunner, CommandRunnerError, FlagOnlyArgument
from console_link.models.cluster import AuthMethod, Cluster, NoTargetClusterDefinedError
from console_link.models.schema_tools import list_schema
from console_link.models.snapshot import S3Snapshot, Snapshot, FileSystemSnapshot
from console_link.models.step_state import StepState

logger = logging.getLogger(__name__)
MAX_FILENAME_LEN = 255

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
    "cluster_awareness_attributes": {"type": "integer", "min": 0, "required": False},
    "index_allowlist": list_schema(required=False),
    "index_template_allowlist": list_schema(required=False),
    "component_template_allowlist": list_schema(required=False),
    "source_cluster_version": {"type": "string", "required": False},
    "transformer_config_base64": {"type": "string", "required": False}
}


def generate_tmp_dir(name: str) -> str:
    prefix = "migration-"
    suffix = "-"
    # Tempfile will add random string ~6 characters, round to 10
    reserved_len = len(prefix) + len(suffix) + 10
    max_name_len = MAX_FILENAME_LEN - reserved_len

    # Truncate name if too long
    safe_name = name[:max_name_len]
    return tempfile.mkdtemp(prefix=f"{prefix}{safe_name}{suffix}")


class Metadata:
    def __init__(self, config, target_cluster: Optional[Cluster], source_cluster: Optional[Cluster] = None,
                 snapshot: Optional[Snapshot] = None):
        logger.debug(f"Initializing Metadata with config: {config}")
        v = Validator(SCHEMA)
        if not v.validate(config):
            logger.error(f"Invalid config: {v.errors}")
            raise ValueError(v.errors)
        self._config = config
        self._target_cluster = target_cluster
        self._snapshot = snapshot

        if (not snapshot) and (config["from_snapshot"] is None):
            raise ValueError("No snapshot is specified or can be assumed for the metadata migration to use.")

        self._source_cluster_version = self._get_source_cluster_version(source_cluster)

        self._awareness_attributes = config.get("cluster_awareness_attributes", 0)
        self._index_allowlist = config.get("index_allowlist", None)
        self._index_template_allowlist = config.get("index_template_allowlist", None)
        self._component_template_allowlist = config.get("component_template_allowlist", None)
        self._otel_endpoint = config.get("otel_endpoint", None)
        self._transformer_config_base64 = config.get("transformer_config_base64", None)

        logger.debug(f"Cluster awareness attributes: {self._awareness_attributes}")
        logger.debug(f"Index allowlist: {self._index_allowlist}")
        logger.debug(f"Index template allowlist: {self._index_template_allowlist}")
        logger.debug(f"Component template allowlist: {self._component_template_allowlist}")
        logger.debug(f"Otel endpoint: {self._otel_endpoint}")
        logger.debug(f"Transformation config: {self._transformer_config_base64}")

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
        self._s3_endpoint = snapshot.s3_endpoint

    def _init_from_fs_snapshot(self, snapshot: FileSystemSnapshot) -> None:
        self._snapshot_name = snapshot.snapshot_name
        self._snapshot_location = "fs"
        self._repo_path = snapshot.repo_path

    def _append_args(self, commands: Dict[str, Any], args_to_add: List[str]) -> None:
        if args_to_add is None:
            return

        def is_command(arg: Optional[str]) -> bool:
            if arg is None:
                return False
            return arg.startswith('--') or arg.startswith('-')

        def is_value(arg: Optional[str]) -> bool:
            if arg is None:
                return False
            return not is_command(arg)

        i = 0
        while i < len(args_to_add):
            arg = args_to_add[i]
            next_arg = args_to_add[i + 1] if (i + 1 < len(args_to_add)) else None

            if is_command(arg) and is_value(next_arg):
                commands[arg] = next_arg
                i += 2  # Move past the command and value
            elif is_command(arg):
                commands[arg] = None
                i += 1  # Move past the command, its a flag
            else:
                logger.warning(f"Ignoring extra value {arg}, there was no command name before it")
                i += 1

    def evaluate(self, extra_args=None) -> CommandResult:
        logger.info("Starting metadata migration")
        return self.migrate_or_evaluate("evaluate", extra_args)

    def migrate(self, extra_args=None) -> CommandResult:
        logger.info("Starting metadata migration")
        return self.migrate_or_evaluate("migrate", extra_args)

    def migrate_or_evaluate(self, command: str, extra_args=None) -> CommandResult:
        if not self._target_cluster:
            raise NoTargetClusterDefinedError()

        command_base = "/root/metadataMigration/bin/MetadataMigration"
        command_args = {}

        # Add any common metadata parameter before the command
        if self._otel_endpoint:
            command_args.update({"--otel-collector-endpoint": self._otel_endpoint})

        command_args.update({
            command: None,
            "--snapshot-name": self._snapshot_name,
            "--target-host": self._target_cluster.endpoint,
            "--cluster-awareness-attributes": self._awareness_attributes
        })

        if self._snapshot_location == 's3':
            self._add_s3_args(command_args=command_args)
        elif self._snapshot_location == 'fs':
            command_args.update({
                "--file-system-repo-path": self._repo_path,
            })

        if self._target_cluster.auth_type == AuthMethod.BASIC_AUTH:
            try:
                auth_details = self._target_cluster.get_basic_auth_details()
                command_args.update({
                    "--target-username": auth_details.username,
                    "--target-password": auth_details.password
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

        if self._source_cluster_version:
            command_args.update({"--source-version": self._source_cluster_version})

        if self._transformer_config_base64:
            command_args.update({"--transformer-config-base64": self._transformer_config_base64})

        # Extra args might not be represented with dictionary, so convert args to list and append commands
        self._append_args(command_args, extra_args)

        command_runner = CommandRunner(command_base, command_args,
                                       sensitive_fields=["--target-password"])
        logger.info(f"Migrating metadata with command: {' '.join(command_runner.sanitized_command())}")
        try:
            return command_runner.run(print_on_error=True)
        except CommandRunnerError as e:
            logger.debug(f"Metadata migration failed: {e}")
            return CommandResult(success=False, value=f"{e.output}")

    def _get_source_cluster_version(self, source_cluster: Optional[Cluster] = None) -> str:
        version = self._config.get("source_cluster_version", None)
        if not version:
            if source_cluster and source_cluster.version:
                logger.info(f"Using source cluster version: {source_cluster.version} as cluster version used for "
                            f"snapshot when performing metadata migrations")
                version = source_cluster.version
            else:
                raise ValueError("A version field in the source_cluster object, or source_cluster_version in the "
                                 "metadata object is required to perform metadata migrations e.g. version: \"ES_6.8\" ")
        return version

    def _add_s3_args(self, command_args: Dict[str, Any]) -> None:
        command_args.update({
            "--s3-local-dir": self._local_dir,
            "--s3-repo-uri": self._s3_uri,
            "--s3-region": self._aws_region,
        })
        if hasattr(self, '_s3_endpoint') and self._s3_endpoint:
            command_args.update({
                "--s3-endpoint": self._s3_endpoint,
            })


class MetadataMigrateRequest(BaseModel):
    indexAllowlist: Optional[List[str]] = None
    indexTemplateAllowlist: Optional[List[str]] = None
    componentTemplateAllowlist: Optional[List[str]] = None
    dryRun: bool = True


class MetadataClusterInfo(BaseModel):
    type: Optional[str] = None
    version: Optional[str] = None
    uri: Optional[str] = None
    protocol: Optional[str] = None
    insecure: Optional[bool] = None
    awsSpecificAuthentication: Optional[bool] = None
    disableCompression: Optional[bool] = None
    localRepository: Optional[str] = None


class MetadataClustersInfo(BaseModel):
    source: MetadataClusterInfo
    target: MetadataClusterInfo


class FailureInfo(BaseModel):
    type: Optional[str] = None
    message: Optional[str] = None
    fatal: Optional[bool] = None


class ItemResult(BaseModel):
    name: str
    successful: bool
    failure: Optional[FailureInfo] = None


class ItemsInfo(BaseModel):
    dryRun: bool
    indexTemplates: List[ItemResult]
    componentTemplates: List[ItemResult]
    indexes: List[ItemResult]
    aliases: List[ItemResult]


class TransformationInfo(BaseModel):
    transformers: List[Dict[str, Any]]


class MetadataStatus(BaseModel):
    status: Optional[StepState] = StepState.PENDING
    started: Optional[datetime] = Field(
        default=None,
        description="Start time in ISO 8601 format",
        json_schema_extra={"format": "date-time"}
    )
    finished: Optional[datetime] = Field(
        default=None,
        description="Finish time in ISO 8601 format",
        json_schema_extra={"format": "date-time"}
    )
    dryRun: Optional[bool] = None
    clusters: Optional[MetadataClustersInfo] = None
    items: Optional[ItemsInfo] = None
    transformations: Optional[TransformationInfo] = None
    errors: Optional[List[str]] = None
    errorCount: Optional[int] = None
    errorCode: Optional[int] = None
    errorMessage: Optional[str] = None

    @field_serializer('started', 'finished')
    def serialize_datetime(self, dt: Optional[datetime]) -> str | None:
        if dt:
            return dt.isoformat()
        return None

    @field_validator('started', 'finished', mode='before')
    @classmethod
    def parse_datetime(cls, v):
        if isinstance(v, str):
            return datetime.fromisoformat(v)
        return v


class MetadataResponseUnparseable(Exception):
    pass


def parse_metadata_result(result: CommandResult) -> Any:
    """Parse the metadata operation result into a structured format."""
    logger.info(f"Result response: {result}")
    if result.output and result.output.stdout:
        result_str = result.output.stdout
    else:
        logger.error("Unable to read standard out from the migration command")
        raise MetadataResponseUnparseable

    try:
        if result_str.strip().startswith('{'):
            parsed_json = json.loads(result_str)
            if isinstance(parsed_json, dict):
                return parsed_json
    except Exception:
        raise MetadataResponseUnparseable

    # Fail out if we could not parse the response
    raise MetadataResponseUnparseable


def store_metadata_result(
    session_name: str,
    result: Any,
    start_time: datetime,
    end_time: datetime,
    dry_run: bool
) -> metadata_db.MetadataEntry:
    """Store metadata operation result for later retrieval."""
    metadata_result = metadata_db.MetadataEntry(
        session_name=session_name,
        timestamp=datetime.now(timezone.utc),
        started=start_time,
        finished=end_time,
        dry_run=dry_run,
        detailed_results=result
    )
    metadata_db.create_entry(metadata_result)
    return metadata_result


def extra_args_from_request(request: MetadataMigrateRequest) -> List[str]:
    """Build extra args list from the request parameters."""
    extra_args = []

    if request.indexAllowlist:
        extra_args.extend(["--index-allowlist", ",".join(request.indexAllowlist)])

    if request.indexTemplateAllowlist:
        extra_args.extend(["--index-template-allowlist", ",".join(request.indexTemplateAllowlist)])

    if request.componentTemplateAllowlist:
        extra_args.extend(["--component-template-allowlist", ",".join(request.componentTemplateAllowlist)])

    extra_args.extend(["--output", "json"])

    return extra_args


def build_status_from_entry(entry: metadata_db.MetadataEntry) -> MetadataStatus:
    """Build a structured metadata response from the command result."""
    error_message = entry.detailed_results.get("errorMessage", None)
    error_count = entry.detailed_results.get("errorCount", 0)
    errors = entry.detailed_results.get("errors", [])

    if error_message or error_count or errors:
        status = StepState.FAILED
    else:
        status = StepState.COMPLETED

    response = MetadataStatus(
        status=status,
        started=entry.started,
        finished=entry.finished,
        dryRun=entry.dry_run,
        clusters=MetadataClustersInfo(**entry.detailed_results.get("clusters", {}))
        if "clusters" in entry.detailed_results else None,
        items=ItemsInfo(**entry.detailed_results.get("items", {}))
        if "items" in entry.detailed_results else None,
        transformations=TransformationInfo(**entry.detailed_results.get("transformations", {}))
        if "transformations" in entry.detailed_results else None,
        errors=errors,
        errorCount=error_count,
        errorCode=entry.detailed_results.get("errorCode", 0),
        errorMessage=error_message,
    )

    return response
