from dataclasses import dataclass
from datetime import datetime, timezone
import json
import os
from typing import Dict, Optional


import requests

from console_link.models.step_state import StepStateWithPause
from console_link.models.backfill_base import Backfill, BackfillOverallStatus, BackfillStatus, DeepStatusNotYetAvailable
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
            "session_name": {"type": "string", "required": False},
            "backfill_session_name": {"type": "string", "required": False},
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

    def build_backfill_status(self, *args) -> BackfillStatus:
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

    def get_status(self, *args, **kwargs) -> CommandResult:
        logger.info("Getting status of RFS backfill")
        deployment_status = self.kubectl_runner.retrieve_deployment_status()
        if not deployment_status:
            return CommandResult(False, "Failed to get deployment status for RFS backfill")
        
        # Always perform deep check for K8s
        logger.info("config=" + str(self.config))
        session_name = self.config["reindex_from_snapshot"].get("backfill_session_name", "")
        logger.info(f"Using backfill_session_name for deep check: '{session_name}'")
        try:
            shard_status = get_detailed_status(target_cluster=self.target_cluster, session_name=session_name)
        except Exception:
            logger.exception(f"Failed to get detailed status for session '{session_name}'")
            shard_status = None
        
        status_parts = [
            f"Pods - Running: {deployment_status.running}, "
            f"Pending: {deployment_status.pending}, Desired: {deployment_status.desired}"
        ]
        if shard_status:
            status_parts.append(shard_status)
        
        status_str = "\n".join(status_parts)

        if deployment_status.terminating > 0 and deployment_status.desired == 0:
            return CommandResult(True, (BackfillStatus.TERMINATING, status_str))
        if deployment_status.running > 0:
            return CommandResult(True, (BackfillStatus.RUNNING, status_str))
        if deployment_status.pending > 0:
            return CommandResult(True, (BackfillStatus.STARTING, status_str))
        return CommandResult(True, (BackfillStatus.STOPPED, status_str))

    def build_backfill_status(self) -> BackfillOverallStatus:
        deployment_status = self.kubectl_runner.retrieve_deployment_status()
        active_workers = True  # Assume there are active workers if we cannot lookup the deployment status
        if deployment_status is not None:
            active_workers = deployment_status.desired != 0
        # Get backfill_session_name from config
        session_name = self.config["reindex_from_snapshot"].get("backfill_session_name", "")
        return get_detailed_status_obj(target_cluster=self.target_cluster,
                                       session_name=session_name,
                                       active_workers=active_workers)


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

    def get_status(self, deep_check=False, *args, **kwargs) -> CommandResult:
        logger.info(f"Getting status of RFS backfill, with {deep_check=}")
        instance_statuses = self.ecs_client.get_instance_statuses()
        if not instance_statuses:
            return CommandResult(False, "Failed to get instance statuses")

        status_string = str(instance_statuses)
        if deep_check:
            try:
                session_name = self.config["reindex_from_snapshot"].get("backfill_session_name", "")
                shard_status = get_detailed_status(target_cluster=self.target_cluster, session_name=session_name)
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

    def build_backfill_status(self) -> BackfillOverallStatus:
        deployment_status = self.ecs_client.get_instance_statuses()
        active_workers = True  # Assume there are active workers if we cannot lookup the deployment status
        if deployment_status is not None:
            active_workers = deployment_status.desired != 0

        session_name = self.config["reindex_from_snapshot"].get("backfill_session_name", "")
        return get_detailed_status_obj(target_cluster=self.target_cluster,
                                       session_name=session_name,
                                       active_workers=active_workers)


def get_detailed_status(target_cluster: Cluster, session_name: str) -> Optional[str]:
    values = get_detailed_status_obj(target_cluster=target_cluster,
                                     session_name=session_name,
                                     active_workers=True)  # Assume active workers
    # Check if shards are initializing
    if (values.shard_total is None and values.shard_complete is None and
            values.shard_in_progress is None and values.shard_waiting is None):
        return "Shards are initializing"
    
    return "\n".join([f"Backfill {key}: {value}" for key, value in values.__dict__.items() if value is not None])


def _get_shard_setup_started_epoch(cluster, index_name: str) -> Optional[int]:
    """
    Try to read the special shard_setup doc and take its completedAt (epoch seconds) as 'started'.
    Returns None if not present.
    """
    try:
        resp = cluster.call_api(f"/{index_name}/_doc/shard_setup")
        body = resp.json()
        src = body.get("_source") or {}
        started_epoch = src.get("completedAt")
        if isinstance(started_epoch, (int, float)) and started_epoch > 0:
            return int(started_epoch)
    except requests.exceptions.RequestException as e:
        logger.debug(f"shard_setup doc not available: {e}")
    except Exception as e:
        logger.debug(f"Failed to parse shard_setup doc: {e}")
    return None


def _get_max_completed_epoch(cluster, index_name: str) -> Optional[int]:
    """
    Return the maximum completedAt (epoch seconds) across all docs.
    Only used once we've confirmed everything is completed.
    """
    body = {
        "size": 0,
        "aggs": {"max_completed": {"max": {"field": "completedAt"}}},
        "query": {"exists": {"field": "completedAt"}},
    }
    try:
        resp = cluster.call_api(
            f"/{index_name}/_search",
            data=json.dumps(body),
            headers={"Content-Type": "application/json"},
        )
        aggs = resp.json().get("aggregations", {})
        val = aggs.get("max_completed", {}).get("value")
        if isinstance(val, (int, float)) and val > 0:
            return int(val)
    except requests.exceptions.RequestException as e:
        logger.debug(f"max completedAt aggregation failed: {e}")
    except Exception as e:
        logger.debug(f"Failed to parse max completedAt aggregation: {e}")
    return None


def _estimate_eta_ms_from_shards(started_epoch: Optional[int], pct: float) -> Optional[float]:
    """
    Simple ETA based on shard completion rate:
      remaining_time ~= elapsed * ((100 - pct) / pct)
    """
    if not started_epoch:
        return None
    if pct <= 0.0 or pct >= 100.0:
        return None
    now = datetime.now(timezone.utc).timestamp()
    elapsed_sec = max(now - started_epoch, 0.001)
    remaining_factor = (100.0 - pct) / pct
    return elapsed_sec * remaining_factor * 1000.0


@dataclass
class ShardStatusCounts:
    total: int = 0
    completed: int = 0
    incomplete: int = 0
    in_progress: int = 0
    unclaimed: int = 0


def get_detailed_status_obj(target_cluster: Cluster,
                            session_name: str = None,
                            active_workers: bool = True) -> BackfillOverallStatus:
    # Check whether the working state index exists. If not, we can't run queries.
    index_to_check = ".migrations_working_state" + (("_" + session_name) if session_name else "")
    logger.info("Checking status for index: " + index_to_check)
    try:
        target_cluster.call_api("/" + index_to_check)
    except requests.exceptions.RequestException as e:
        logger.debug(f"Working state index does not yet exist, deep status checks can't be performed. {e}")
        raise DeepStatusNotYetAvailable

    # Check shard_setup separately
    shard_setup_query = generate_shard_setup_query()
    shard_setup_completed = parse_shard_setup_response(shard_setup_query, target_cluster, index_to_check)
    
    if not shard_setup_completed:
        # Shards are still initializing, return minimal status
        return BackfillOverallStatus(
            status=StepStateWithPause.RUNNING,
            percentage_completed=0.0,
            eta_ms=None,
            started=None,
            finished=None,
            shard_total=None,
            shard_complete=None,
            shard_in_progress=None,
            shard_waiting=None
        )

    total_key = "total"
    completed_key = "completed"
    incomplete_key = "incomplete"
    unclaimed_key = "unclaimed"
    in_progress_key = "in progress"

    # Get progress queries and run them
    progress_queries = generate_progress_queries()
    values = {key: parse_query_response(progress_queries[key], target_cluster, index_to_check, key)
              for key in progress_queries.keys()}
    if None in values.values():
        logger.warning(f"Failed to get values for some queries: {values}")

    counts = ShardStatusCounts(
        total=values.get(total_key, 0) or 0,
        completed=values.get(completed_key, 0) or 0,
        incomplete=values.get(incomplete_key, 0) or 0,
        in_progress=values.get(in_progress_key, 0) or 0,
        unclaimed=values.get(unclaimed_key, 0) or 0,
    )

    # started: read shard_setup.completedAt if available
    started_epoch = _get_shard_setup_started_epoch(target_cluster, index_to_check)
    started_iso = datetime.fromtimestamp(started_epoch, tz=timezone.utc).isoformat() if started_epoch else None

    # finished: only if everything is done, take max completedAt
    finished_iso, percentage_completed, eta_ms, status = compute_dervived_values(target_cluster,
                                                                                 index_to_check,
                                                                                 counts.total,
                                                                                 counts.completed,
                                                                                 started_epoch,
                                                                                 active_workers)

    return BackfillOverallStatus(
        status=status,
        percentage_completed=percentage_completed,
        eta_ms=eta_ms,
        started=started_iso,
        finished=finished_iso,
        shard_total=counts.total,
        shard_complete=counts.completed,
        shard_in_progress=counts.in_progress,
        shard_waiting=counts.unclaimed,
    )


def compute_dervived_values(target_cluster, index_to_check, total, completed, started_epoch, active_workers: bool):
    # Consider it completed if there's nothing to do (total = 0) or we've completed all shards
    if total == 0 or (total > 0 and completed >= total):
        max_completed_epoch = _get_max_completed_epoch(target_cluster, index_to_check)
        finished_iso = (
            datetime.fromtimestamp(max_completed_epoch, tz=timezone.utc).isoformat()
            if max_completed_epoch
            else datetime.now(timezone.utc).isoformat()
        )
        percentage_completed = 100.0
        eta_ms = None
        status = StepStateWithPause.COMPLETED
    else:
        finished_iso = None
        percentage_completed = (completed / total * 100.0) if total > 0 else 0.0
        if active_workers:
            eta_ms = _estimate_eta_ms_from_shards(started_epoch, percentage_completed)
            status = StepStateWithPause.RUNNING
        else:
            eta_ms = None
            status = StepStateWithPause.PAUSED
    return finished_iso, percentage_completed, eta_ms, status


EXTRACT_UNIQUE_INDEX_SHARD_SCRIPT = (
    "def id = doc['_id'].value;"
    "int a = id.indexOf('__');"
    "int b = id.indexOf('__', a + 2);"
    "if (a > -1 && b > -1) { return id.substring(0, a) + '__' + id.substring(a + 2, b); }"
)


def with_uniques(filter_query):
    return {
        "size": 0,
        "query": filter_query,
        "aggs": {
            "unique_pair_count": {"cardinality": {"script": {"lang": "painless", "source":
                                                             EXTRACT_UNIQUE_INDEX_SHARD_SCRIPT}}}
        },
    }


def generate_progress_queries():
    current_epoch_seconds = int(datetime.now(timezone.utc).timestamp())
    total_query = with_uniques({"bool": {"must_not": [{"match": {"_id": "shard_setup"}},
                                                      {"exists": {"field": "successor_items"}}]}})
    complete_query = with_uniques({"bool": {"must": [{"exists": {"field": "completedAt"}}],
                                            "must_not": [{"match": {"_id": "shard_setup"}},
                                                         {"exists": {"field": "successor_items"}}]}})
    incomplete_query = with_uniques({"bool": {"must_not": [{"exists": {"field": "completedAt"}},
                                                           {"match": {"_id": "shard_setup"}}]}})
    in_progress_query = with_uniques({"bool": {"must": [
        {"range": {"expiration": {"gte": current_epoch_seconds}}},
        {"bool": {"must_not": [{"exists": {"field": "completedAt"}},
                               {"match": {"_id": "shard_setup"}}]}}
    ]}})
    unclaimed_query = with_uniques({"bool": {"must": [
        {"range": {"expiration": {"lt": current_epoch_seconds}}},
        {"bool": {"must_not": [{"exists": {"field": "completedAt"}},
                               {"match": {"_id": "shard_setup"}}]}}
    ]}})
    return {
        "total": total_query,
        "completed": complete_query,
        "incomplete": incomplete_query,
        "in progress": in_progress_query,
        "unclaimed": unclaimed_query
    }


def generate_shard_setup_query():
    return {"query": {"match": {"_id": "shard_setup"}}}


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


def parse_query_response(query: dict, cluster: Cluster, index_name: str, label: str) -> Optional[int]:
    try:
        logger.debug(f"Creating request: /{index_name}/_search; {query}")
        response = cluster.call_api(f"/{index_name}/_search", method=HttpMethod.POST, data=json.dumps(query),
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


def parse_shard_setup_response(query: dict, cluster: Cluster, index_name: str) -> bool:
    """Check if shard_setup document has completedAt field set"""
    try:
        response = cluster.call_api(f"/{index_name}/_search", method=HttpMethod.POST, data=json.dumps(query),
                                    headers={'Content-Type': 'application/json'})
        body = response.json()
        hits = body.get('hits', {}).get('hits', [])
        if hits:
            source = hits[0].get('_source', {})
            return 'completedAt' in source
        return False
    except Exception as e:
        logger.error(f"Failed to check shard_setup document: {e}")
        return False
