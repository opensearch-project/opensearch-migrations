#!/usr/bin/env python3

import os
import logging
from strenum import StrEnum

logger = logging.getLogger(__name__)

class ECSTaskStatus(StrEnum):
    PROVISIONING = 'PROVISIONING'
    PENDING = 'PENDING'
    RUNNING = 'RUNNING'
    STOPPED = 'STOPPED'


class OperationStatus(StrEnum):
    STARTING = 'STARTING'
    RUNNING_LOAD = 'RUNNING_LOAD'
    STOPPED = 'STOPPED'
    FAILED = 'FAILED'
    LOAD_COMPLETE = 'LOAD_COMPLETE'
    NO_MIGRATION = 'NO_MIGRATION'


def get_fetch_migration_task_arn(stage: str, ecs_client) -> str:
    response = ecs_client.list_tasks(cluster=f'migration-{stage}-ecs-cluster',
                                     family=f'migration-{stage}-fetch-migration')
    recent_task_arns = response['taskArns']
    if recent_task_arns:
        return recent_task_arns[0]
    else:
        return ''


def is_migration_active(status: str) -> bool:
    if status == OperationStatus.STARTING or status == OperationStatus.RUNNING_LOAD:
        return True
    else:
        return False


def get_deployed_stage() -> str:
    env_stage = os.environ.get('MIGRATION_STAGE')
    if not env_stage:
        raise RuntimeError('Migration is not on AWS or not deployed as required MIGRATION_STAGE environment variable '
                           'is missing, exiting...')
    else:
        return env_stage
