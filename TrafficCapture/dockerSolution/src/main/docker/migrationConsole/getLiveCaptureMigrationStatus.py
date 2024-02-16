#!/usr/bin/env python3

import argparse
import logging
import boto3
from migrationUtils import OperationStatus, get_deployed_stage

logger = logging.getLogger(__name__)
ecs_client = boto3.client('ecs')


def get_live_capture_migration_status(stage: str, original_migration_status: str = OperationStatus.NO_MIGRATION):
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
    print(get_live_capture_migration_status(env_stage, last_status))
