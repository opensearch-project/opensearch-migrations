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

SCHEMA = {
    "otel_endpoint": {"type": "string", "required": False},
    "min_replicas": {"type": "integer", "min": 0, "required": False},
    "index_allowlist": list_schema(required=False),
    "index_template_allowlist": list_schema(required=False),
    "component_template_allowlist": list_schema(required=False)
}


def generate_tmp_dir(name: str) -> str:
    return tempfile.mkdtemp(prefix=f"migration-{name}-")


class Metadata:
    def __init__(self, config, config_file: str):
        logger.debug(f"Initializing Metadata with config: {config}")
        v = Validator(SCHEMA)
        if not v.validate(config):
            logger.error(f"Invalid config: {v.errors}")
            raise ValueError(v.errors)
        self._config = config
        self._config_file = config_file

        logger.info("Metadata migration configuration defined")

    # Discussion Needed: Verification of the config should be done by
    # console_link or by running metadata tool?
    #
    # Tools need to handle errors such as access denied or malformed
    # parameters, the validation being done here could be pushed down
    # to those tools that already need to handle those scenarios.

    def evaluate(self, extra_args=None) -> CommandResult:
        logger.info("Starting metadata migration")
        return self.migrate_or_evaluate("evaluate", extra_args)

    def migrate(self, extra_args=None) -> CommandResult:
        logger.info("Starting metadata migration")
        return self.migrate_or_evaluate("migrate", extra_args)

    def migrate_or_evaluate(self, command: str, extra_args=None) -> CommandResult:
        command_base = "/root/metadataMigration/bin/MetadataMigration"
        command_args = {}

        command_args.update({
            command: None,
            "--config-file": self._config_file
        })

        logger.info(f"Migrating metadata with command: {' '.join(command_runner.sanitized_command())}")
        try:
            return command_runner.run()
        except CommandRunnerError as e:
            logger.error(f"Metadata migration failed: {e}")
            return CommandResult(success=False, value=f"Metadata migration failed: {e}")
