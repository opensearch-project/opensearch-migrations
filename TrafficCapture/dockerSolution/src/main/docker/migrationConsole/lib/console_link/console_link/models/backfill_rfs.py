from datetime import datetime
import json
import os
from typing import Dict, Optional


import requests

from console_link.models.backfill_base import Backfill, BackfillStatus
from console_link.models.client_options import ClientOptions
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.schema_tools import contains_one_of
from console_link.models.command_result import CommandResult
from console_link.models.kubectl_runner import DeploymentStatus, KubectlRunner
from console_link.models.ecs_service import ECSService

from cerberus import Validator

import logging

logger = logging.getLogger(__name__)

WORKING_STATE_INDEX = ".migrations_working_state"

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

K8S_RFS_SCHEMA = {
    "type": "dict",
    "schema": {
        "namespace": {"type": "string", "required": True},
        "deployment_name": {"type": "string", "required": True}
    }
}

RFS_BACKFILL_SCHEMA = {
    "reindex_from_snapshot": {
        "type": "dict",
        "schema": {
            "docker": DOCKER_RFS_SCHEMA,
            "ecs": ECS_RFS_SCHEMA,
            "k8s": K8S_RFS_SCHEMA,
            "snapshot_name": {"type": "string", "required": False},
            "snapshot_repo": {"type": "string", "required": False},
            "scale": {"type": "integer", "required": False, "min": 1}
        },
        "check_with": contains_one_of({'docker', 'ecs', 'k8s'}),
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

    def pause(self, pipeline_name=None) -> CommandResult:
        raise NotImplementedError()

    def get_status(self, *args, **kwargs) -> CommandResult:
        return CommandResult(True, (BackfillStatus.RUNNING, "This is my running state message"))

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        raise NotImplementedError()
    
    def archive(self, *args, **kwargs) -> CommandResult:
        raise NotImplementedError()
    

class RfsWorkersInProgress(Exception):
    def __init__(self):
        super().__init__("RFS Workers are still in progress")


class WorkingIndexDoesntExist(Exception):
    def __init__(self, index_name: str):
        super().__init__(f"The working state index '{index_name}' does not exist")


class K8sRFSBackfill(RFSBackfill):
    def __init__(self, config: Dict, target_cluster: Cluster, client_options: Optional[ClientOptions] = None) -> None:
        super().__init__(config)
        self.client_options = client_options
        self.target_cluster = target_cluster
        self.default_scale = self.config["reindex_from_snapshot"].get("scale", 5)

        self.k8s_config = self.config["reindex_from_snapshot"]["k8s"]
        self.namespace = self.k8s_config["namespace"]
        self.deployment_name = self.k8s_config["deployment_name"]
        self.kubectl_runner = KubectlRunner(namespace=self.namespace, deployment_name=self.deployment_name)

    def start(self, *args, **kwargs) -> CommandResult:
        logger.info(f"Starting RFS backfill by setting desired count to {self.default_scale} instances")
        return self.kubectl_runner.perform_scale_command(replicas=self.default_scale)

    def pause(self, *args, **kwargs) -> CommandResult:
        logger.info("Pausing RFS backfill by setting desired count to 0 instances")
        return self.kubectl_runner.perform_scale_command(replicas=0)

    def stop(self, *args, **kwargs) -> CommandResult:
        logger.info("Stopping RFS backfill by setting desired count to 0 instances")
        return self.kubectl_runner.perform_scale_command(replicas=0)

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        logger.info(f"Scaling RFS backfill by setting desired count to {units} instances")
        return self.kubectl_runner.perform_scale_command(replicas=units)

    def archive(self, *args, archive_dir_path: str = None, archive_file_name: str = None, **kwargs) -> CommandResult:
        deployment_status = self.kubectl_runner.retrieve_deployment_status()
        return perform_archive(target_cluster=self.target_cluster,
                               deployment_status=deployment_status,
                               archive_dir_path=archive_dir_path,
                               archive_file_name=archive_file_name)

    def get_status(self, deep_check: bool, *args, **kwargs) -> CommandResult:
        logger.info("Getting status of RFS backfill")
        deployment_status = self.kubectl_runner.retrieve_deployment_status()
        if not deployment_status:
            return CommandResult(False, "Failed to get deployment status for RFS backfill")
        status_str = str(deployment_status)
        if deep_check:
            try:
                shard_status = get_detailed_status(target_cluster=self.target_cluster)
            except Exception as e:
                logger.error(f"Failed to get detailed status: {e}")
                shard_status = None
            if shard_status:
                status_str += f"\n{shard_status}"
        if deployment_status.running > 0:
            return CommandResult(True, (BackfillStatus.RUNNING, status_str))
        if deployment_status.pending > 0:
            return CommandResult(True, (BackfillStatus.STARTING, status_str))
        return CommandResult(True, (BackfillStatus.STOPPED, status_str))


class ECSRFSBackfill(RFSBackfill):
    def __init__(self, config: Dict, target_cluster: Cluster, client_options: Optional[ClientOptions] = None) -> None:
        super().__init__(config)
        self.client_options = client_options
        self.target_cluster = target_cluster
        self.default_scale = self.config["reindex_from_snapshot"].get("scale", 5)

        self.ecs_config = self.config["reindex_from_snapshot"]["ecs"]
        self.ecs_client = ECSService(cluster_name=self.ecs_config["cluster_name"],
                                     service_name=self.ecs_config["service_name"],
                                     aws_region=self.ecs_config.get("aws_region", None),
                                     client_options=self.client_options)

    def start(self, *args, **kwargs) -> CommandResult:
        logger.info(f"Starting RFS backfill by setting desired count to {self.default_scale} instances")
        return self.ecs_client.set_desired_count(self.default_scale)
    
    def pause(self, *args, **kwargs) -> CommandResult:
        logger.info("Pausing RFS backfill by setting desired count to 0 instances")
        return self.ecs_client.set_desired_count(0)

    def stop(self, *args, **kwargs) -> CommandResult:
        logger.info("Stopping RFS backfill by setting desired count to 0 instances")
        return self.ecs_client.set_desired_count(0)

    def scale(self, units: int, *args, **kwargs) -> CommandResult:
        logger.info(f"Scaling RFS backfill by setting desired count to {units} instances")
        return self.ecs_client.set_desired_count(units)
    
    def archive(self, *args, archive_dir_path: str = None, archive_file_name: str = None, **kwargs) -> CommandResult:
        status = self.ecs_client.get_instance_statuses()
        return perform_archive(target_cluster=self.target_cluster,
                               deployment_status=status,
                               archive_dir_path=archive_dir_path,
                               archive_file_name=archive_file_name)

    def get_status(self, deep_check: bool, *args, **kwargs) -> CommandResult:
        logger.info(f"Getting status of RFS backfill, with {deep_check=}")
        instance_statuses = self.ecs_client.get_instance_statuses()
        if not instance_statuses:
            return CommandResult(False, "Failed to get instance statuses")

        status_string = str(instance_statuses)
        if deep_check:
            try:
                shard_status = get_detailed_status(target_cluster=self.target_cluster)
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


def get_detailed_status(target_cluster: Cluster) -> Optional[str]:
    # Check whether the working state index exists. If not, we can't run queries.
    try:
        target_cluster.call_api("/.migrations_working_state")
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
    values = {key: parse_query_response(queries[key], target_cluster, key) for key in queries.keys()}
    logger.info(f"Values: {values}")
    if None in values.values():
        logger.warning(f"Failed to get values for some queries: {values}")
    disclaimer = "This may be transient because of timing of executing the queries or indicate an issue" + \
                 " with the queries or the working state index"
    # Check the various sums to make sure things add up correctly.
    if values["incomplete"] + values["completed"] != values["total"]:
        logger.warning(f"Incomplete ({values['incomplete']}) and completed ({values['completed']}) shards do not "
                       f"sum to the total ({values['total']}) shards." + disclaimer)
    if values["unclaimed"] + values["in progress"] != values["incomplete"]:
        logger.warning(f"Unclaimed ({values['unclaimed']}) and in progress ({values['in progress']}) shards do not"
                       f" sum to the incomplete ({values['incomplete']}) shards." + disclaimer)

    return "\n".join([f"Work items {key}: {value}" for key, value in values.items() if value is not None])


def perform_archive(target_cluster: Cluster,
                    deployment_status: DeploymentStatus,
                    archive_dir_path: str = None,
                    archive_file_name: str = None) -> CommandResult:
    logger.info("Confirming there are no currently in-progress workers")
    if deployment_status.running > 0 or deployment_status.pending > 0 or deployment_status.desired > 0:
        return CommandResult(False, RfsWorkersInProgress())

    try:
        backup_path = get_working_state_index_backup_path(archive_dir_path, archive_file_name)
        logger.info(f"Backing up working state index to {backup_path}")
        backup_working_state_index(target_cluster, WORKING_STATE_INDEX, backup_path)
        logger.info("Working state index backed up successful")

        logger.info("Cleaning up working state index on target cluster")
        target_cluster.call_api(
            f"/{WORKING_STATE_INDEX}",
            method=HttpMethod.DELETE,
            params={"ignore_unavailable": "true"}
        )
        logger.info("Working state index cleaned up successful")
        return CommandResult(True, backup_path)
    except requests.HTTPError as e:
        if e.response.status_code == 404:
            return CommandResult(False, WorkingIndexDoesntExist(WORKING_STATE_INDEX))
        return CommandResult(False, e)


def get_working_state_index_backup_path(archive_dir_path: str = None, archive_file_name: str = None) -> str:
    shared_logs_dir = os.getenv("SHARED_LOGS_DIR_PATH", None)
    if archive_dir_path:
        backup_dir = archive_dir_path
    elif shared_logs_dir is None:
        backup_dir = "./backfill_working_state"
    else:
        backup_dir = os.path.join(shared_logs_dir, "backfill_working_state")

    if archive_file_name:
        file_name = archive_file_name
    else:
        file_name = f"working_state_backup_{datetime.now().strftime('%Y%m%d%H%M%S')}.json"
    return os.path.join(backup_dir, file_name)


def backup_working_state_index(cluster: Cluster, index_name: str, backup_path: str):
    # Ensure the backup directory exists
    backup_dir = os.path.dirname(backup_path)
    os.makedirs(backup_dir, exist_ok=True)

    # Backup the docs in the working state index as a JSON array containing batches of documents
    with open(backup_path, 'w') as outfile:
        outfile.write("[\n")  # Start the JSON array
        first_batch = True

        for batch in cluster.fetch_all_documents(index_name=index_name):
            if not first_batch:
                outfile.write(",\n")
            else:
                first_batch = False
            
            # Dump the batch of documents as an entry in the array
            batch_json = json.dumps(batch, indent=4)
            outfile.write(batch_json)

        outfile.write("\n]")  # Close the JSON array


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
