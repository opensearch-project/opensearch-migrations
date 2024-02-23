#!/usr/bin/env python3

import logging
import boto3
from migrationUtils import OperationStatus, ECSTaskStatus, get_deployed_stage

logger = logging.getLogger(__name__)
ecs_client = boto3.client('ecs')


def get_fetch_migration_status(stage: str):
    response = ecs_client.list_tasks(cluster=f'migration-{stage}-ecs-cluster',
                                     family=f'migration-{stage}-fetch-migration')
    recent_task_arns = response['taskArns']
    if not recent_task_arns:
        return OperationStatus.NO_ACTIVE_MIGRATION

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
        logger.error(f'Unknown ECS task status: {fetch_task_status}, '
                     f'using status: {OperationStatus.NO_ACTIVE_MIGRATION}')
        return OperationStatus.NO_ACTIVE_MIGRATION


if __name__ == "__main__":
    env_stage = get_deployed_stage()
    print(get_fetch_migration_status(env_stage))
