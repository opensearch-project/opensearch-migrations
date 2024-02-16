#!/usr/bin/env python3

import argparse
import boto3
import json
from getFetchMigrationStatus import get_fetch_migration_status
from migrationUtils import get_deployed_stage, is_migration_active, get_fetch_migration_task_arn, OperationStatus


ecs_client = boto3.client('ecs')


def stop_fetch_migration(stage: str, force_update: bool):
    fetch_status = get_fetch_migration_status(stage)
    message = 'Stopped Fetch Migration.'
    if not force_update and not is_migration_active(fetch_status):
        message = 'Fetch Migration does not appear to be active, no action necessary.'
    else:
        # Stop ECS task
        fetch_task_arn = get_fetch_migration_task_arn(stage, ecs_client)
        if not fetch_task_arn:
            message = 'Unable to identify task ARN for Fetch Migration, please retry operation'
        try:
            ecs_client.stop_task(cluster=f'migration-{stage}-ecs-cluster',
                                 task=fetch_task_arn,
                                 reason='Manual termination from the Migration Console')
            fetch_status = OperationStatus.STOPPED
        except Exception as e:
            message = str(e)
    return {'status': str(fetch_status), 'message': message}


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action='store_true', help="Bypass sanity checks and update ECS task")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    env_stage = get_deployed_stage()
    print(json.dumps(stop_fetch_migration(env_stage, args.force)))
