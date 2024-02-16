#!/usr/bin/env python3

import argparse
import logging
import boto3
from migrationUtils import OperationStatus, ECSTaskStatus, get_deployed_stage

logger = logging.getLogger(__name__)
ecs_client = boto3.client('ecs')


def get_fetch_migration_status(stage: str, original_migration_status: str = OperationStatus.NO_MIGRATION):
    response = ecs_client.list_tasks(cluster=f'migration-{stage}-ecs-cluster',
                                     family=f'migration-{stage}-fetch-migration')
    recent_task_arns = response['taskArns']
    if not recent_task_arns:
        # Baseless assumption that migration was successful since it is no longer running, and the task no longer
        # exists to query
        if (original_migration_status == OperationStatus.RUNNING_LOAD or
                original_migration_status == OperationStatus.STARTING):
            return OperationStatus.LOAD_COMPLETE
        else:
            return original_migration_status

    task_details = ecs_client.describe_tasks(cluster=f'migration-{stage}-ecs-cluster',
                                             tasks=[recent_task_arns[0]])
    # TODO case of more than one task
    fetch_task = task_details['tasks'][0]
    fetch_task_status = fetch_task['lastStatus']
    if fetch_task_status == ECSTaskStatus.PENDING or fetch_task_status == ECSTaskStatus.PROVISIONING:
        return OperationStatus.STARTING
    elif fetch_task_status == ECSTaskStatus.RUNNING:
        return OperationStatus.RUNNING_LOAD
    elif fetch_task_status == ECSTaskStatus.STOPPED:
        fetch_task_stopped_reason = fetch_task['stoppedReason']
        logger.error(fetch_task_stopped_reason)
        return OperationStatus.STOPPED
    else:
        logger.error(f'Unknown ECS task status: {fetch_task_status}, using last known status: {original_migration_status}')
        return original_migration_status


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--last-migration-status", type=str, help="Last known migration status.")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    last_status = OperationStatus.NO_MIGRATION
    if args.last_migration_status:
        last_status = args.last_migration_status
    env_stage = get_deployed_stage()
    print(get_fetch_migration_status(env_stage, last_status))
