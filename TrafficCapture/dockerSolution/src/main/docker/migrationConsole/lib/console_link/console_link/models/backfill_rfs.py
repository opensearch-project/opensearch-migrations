from datetime import datetime
from typing import Dict, Optional
import json

import requests

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

    def get_status(self, *args, **kwargs) -> CommandResult:
        raise NotImplementedError()

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        raise NotImplementedError()


class DockerRFSBackfill(RFSBackfill):
    def __init__(self, config: Dict, target_cluster: Cluster) -> None:
        super().__init__(config)
        self.target_cluster = target_cluster
        self.docker_config = self.config["reindex_from_snapshot"]["docker"]

    def get_status(self, *args, **kwargs) -> CommandResult:
        return CommandResult(True, (BackfillStatus.RUNNING, "This is my running state message"))

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        raise NotImplementedError()


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

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        logger.info(f"Scaling RFS backfill by setting desired count to {units} instances")
        return self.ecs_client.set_desired_count(units)

    def get_status(self, deep_check: bool, *args, **kwargs) -> CommandResult:
        logger.info(f"Getting status of RFS backfill, with {deep_check=}")
        instance_statuses = self.ecs_client.get_instance_statuses()
        if not instance_statuses:
            return CommandResult(False, "Failed to get instance statuses")

        status_string = str(instance_statuses)
        if deep_check:
            try:
                shard_status = self._get_detailed_status()
            except Exception as e:
                logger.error(f"Failed to get detailed status: {e}")
                shard_status = None
            if shard_status:
                status_string += f"\n{shard_status}"

        if instance_statuses.running > 0:
            return CommandResult(True, (BackfillStatus.RUNNING, status_string))
        elif instance_statuses.pending > 0:
            return CommandResult(True, (BackfillStatus.STARTING, status_string))
        return CommandResult(True, (BackfillStatus.STOPPED, status_string))

    def _get_detailed_status(self) -> Optional[str]:
        # Check whether the working state index exists. If not, we can't run queries.
        try:
            self.target_cluster.call_api("/.migrations_working_state")
        except requests.exceptions.RequestException:
            logger.warning("Working state index does not yet exist, deep status checks can't be performed.")
            return None

        current_epoch_seconds = int(datetime.now().timestamp())
        incomplete_query = {"query": {
            "bool": {"must_not": [{"exists": {"field": "completedAt"}}]}
        }}
        completed_query = {"query": {
            "bool": {"must": [{"exists": {"field": "completedAt"}}]}
        }}
        total_query = {"query": {"match_all": {}}}
        in_progress_query = {"query": {
            "bool": {"must": [
                {"range": {"expiration": {"gte": current_epoch_seconds}}},
                {"bool": {"must_not": [{"exists": {"field": "completedAt"}}]}}
            ]}
        }}
        unclaimed_query = {"query": {
            "bool": {"must": [
                {"range": {"expiration": {"lt": current_epoch_seconds}}},
                {"bool": {"must_not": [{"exists": {"field": "completedAt"}}]}}
            ]}
        }}
        queries = {
            "total": total_query,
            "completed": completed_query,
            "incomplete": incomplete_query,
            "in progress": in_progress_query,
            "unclaimed": unclaimed_query
        }
        values = {key: parse_query_response(queries[key], self.target_cluster, key) for key in queries.keys()}
        logger.info(f"Values: {values}")
        if None in values.values():
            logger.warning(f"Failed to get values for some queries: {values}")
        disclaimer = "This may be transient because of timing of executing the queries or indicate an issue" +\
            " with the queries or the working state index"
        # Check the various sums to make sure things add up correctly.
        if values["incomplete"] + values["completed"] != values["total"]:
            logger.warning(f"Incomplete ({values['incomplete']}) and completed ({values['completed']}) shards do not "
                           f"sum to the total ({values['total']}) shards." + disclaimer)
        if values["unclaimed"] + values["in progress"] != values["incomplete"]:
            logger.warning(f"Unclaimed ({values['unclaimed']}) and in progress ({values['in progress']}) shards do not"
                           f" sum to the incomplete ({values['incomplete']}) shards." + disclaimer)

        return "\n".join([f"Shards {key}: {value}" for key, value in values.items() if value is not None])


def parse_query_response(query: dict, cluster: Cluster, label: str) -> Optional[int]:
    try:
        response = cluster.call_api("/.migrations_working_state/_search", data=json.dumps(query),
                                    headers={'Content-Type': 'application/json'})
    except Exception as e:
        logger.error(f"Failed to execute query: {e}")
        return None
    logger.debug(f"Query: {label}, {response.request.path_url}, {response.request.body}")
    body = response.json()
    logger.debug(f"Raw response: {body}")
    if "hits" in body:
        logger.debug(f"Hits on {label} query: {body['hits']}")
        logger.info(f"Sample of {label} shards: {[hit['_id'] for hit in body['hits']['hits']]}")
        return int(body['hits']['total']['value'])
    logger.warning(f"No hits on {label} query, migration_working_state index may not exist or be populated")
    return None
