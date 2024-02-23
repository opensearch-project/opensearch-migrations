#!/usr/bin/env python3

import boto3
from migrationUtils import OperationStatus, get_deployed_stage

ecs_client = boto3.client('ecs')


def get_live_capture_migration_status(stage: str):
    response = ecs_client.describe_services(cluster=f'migration-{stage}-ecs-cluster',
                                            services=[f'migration-{stage}-traffic-replayer-default'])
    replayer_service_details = response['services'][0]
    replayer_running_count = replayer_service_details['runningCount']
    replayer_pending_count = replayer_service_details['pendingCount']
    if replayer_running_count > 0:
        return OperationStatus.RUNNING_LOAD
    elif replayer_pending_count > 0:
        return OperationStatus.STARTING
    else:
        return OperationStatus.NO_ACTIVE_MIGRATION


if __name__ == "__main__":
    env_stage = get_deployed_stage()
    print(get_live_capture_migration_status(env_stage))
