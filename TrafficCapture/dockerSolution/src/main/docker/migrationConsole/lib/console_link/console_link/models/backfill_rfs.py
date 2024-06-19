from typing import Dict
from console_link.models.backfill_base import Backfill, BackfillStatus
from console_link.models.cluster import Cluster
from console_link.models.schema_tools import contains_one_of
from console_link.models.command_result import CommandResult
from console_link.models.ecs_service import ECSService

from cerberus import Validator

import logging

logger = logging.getLogger(__name__)

DOCKER_RFS_SCHEMA = {
    "type": "dict",
    "nullable": True,
    "schema": {
        "socket": {"type": "string", "required": False}
    }
}


ECS_RFS_SCHEMA = {
    "type": "dict",
    "schema": {
        "cluster_name": {"type": "string", "required": True},
        "service_name": {"type": "string", "required": True},
        "aws_region": {"type": "string", "required": False}
    }
}

RFS_BACKFILL_SCHEMA = {
    "reindex_from_snapshot": {
        "type": "dict",
        "schema": {
            "docker": DOCKER_RFS_SCHEMA,
            "ecs": ECS_RFS_SCHEMA,
            "snapshot_name": {"type": "string", "required": False},
            "snapshot_repo": {"type": "string", "required": False},
            "scale": {"type": "integer", "required": False, "min": 1}
        },
        "check_with": contains_one_of({'docker', 'ecs'}),
    }
}


class RFSBackfill(Backfill):
    def __init__(self, config: Dict) -> None:
        super().__init__(config)

        v = Validator(RFS_BACKFILL_SCHEMA)
        if not v.validate(self.config):
            raise ValueError("Invalid config file for RFS backfill", v.errors)

    def create(self, *args, **kwargs) -> CommandResult:
        return CommandResult(1, "no-op")

    def start(self, *args, **kwargs) -> CommandResult:
        raise NotImplementedError()

    def stop(self, *args, **kwargs) -> CommandResult:
        raise NotImplementedError()

    def get_status(self, *args, **kwargs) -> BackfillStatus:
        raise NotImplementedError()

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        raise NotImplementedError()


class DockerRFSBackfill(RFSBackfill):
    def __init__(self, config: Dict, target_cluster: Cluster) -> None:
        super().__init__(config)
        self.target_cluster = target_cluster
        self.docker_config = self.config["reindex_from_snapshot"]["docker"]


class ECSRFSBackfill(RFSBackfill):
    def __init__(self, config: Dict, target_cluster: Cluster) -> None:
        super().__init__(config)
        self.target_cluster = target_cluster
        self.default_scale = self.config["reindex_from_snapshot"].get("scale", 1)

        self.ecs_config = self.config["reindex_from_snapshot"]["ecs"]
        self.ecs_client = ECSService(self.ecs_config["cluster_name"], self.ecs_config["service_name"],
                                     self.ecs_config.get("aws_region", None))

    def start(self, *args, **kwargs) -> CommandResult:
        logger.info(f"Starting RFS backfill by setting desired count to {self.default_scale} instances")
        return self.ecs_client.set_desired_count(self.default_scale)

    def stop(self, *args, **kwargs) -> CommandResult:
        logger.info("Stopping RFS backfill by setting desired count to 0 instances")
        return self.ecs_client.set_desired_count(0)
