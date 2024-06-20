from typing import Optional
from cerberus import Validator

from console_link.models.command_result import CommandResult
from console_link.models.schema_tools import list_schema
from console_link.models.cluster import Cluster
from console_link.models.snapshot import Snapshot


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


class Metadata:
    def __init__(self, config, target_cluster: Cluster, snapshot: Optional[Snapshot] = None):
        v = Validator(SCHEMA)
        if not v.validate(config):
            raise ValueError(v.errors)
        self._config = config
        self._target_cluster = target_cluster
        self._snapshot = snapshot

        if (not self._snapshot) and (config["from_snapshot"] is None):
            raise ValueError("No snapshot is specified or can be assumed "
                             "for the metadata migration to use.")

    def migrate(self) -> CommandResult:
        pass
