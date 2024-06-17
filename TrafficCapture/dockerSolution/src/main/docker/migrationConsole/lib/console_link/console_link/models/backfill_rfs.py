from typing import Dict
from console_link.models.backfill_base import Backfill
from console_link.models.cluster import Cluster
from console_link.models.schema_tools import contains_one_of


DOCKER_RFS_SCHEMA = {
    "type": "dict",
    "nullable": True
}


ECS_RFS_SCHEMA = {
    "type": "dict",
    "schema": {
        "service_name": {
            "type": "string",
            "required": True,
        }
    }
}

RFS_BACKFILL_SCHEMA = {
    "reindex_from_snapshot": {
        "type": "dict",
        "schema": {
            "docker": DOCKER_RFS_SCHEMA,
            "ecs": ECS_RFS_SCHEMA,
            "snapshot_name": {
                "type": "string",
                "required": False,
            },
            "snapshot_repo": {
                "type": "string",
                "required": False,
            }
        },
        "check_with": contains_one_of({'docker', 'ecs'}),
    }
}


class RFSBackfill(Backfill):
    def __init__(self, config: Dict, target_cluster: Cluster) -> None:
        super().__init__(config)


class DockerRFSBackfill(RFSBackfill):
    pass


class ECSRFSBackfill(RFSBackfill):
    pass
