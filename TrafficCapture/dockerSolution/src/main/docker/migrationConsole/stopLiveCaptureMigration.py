#!/usr/bin/env python3

import argparse
import json
import boto3
from getLiveCaptureMigrationStatus import get_live_capture_migration_status
from migrationUtils import get_deployed_stage, is_migration_active, OperationStatus

ecs_client = boto3.client('ecs')


def stop_live_capture_migration(stage: str, force_update: bool):
    replayer_status = get_live_capture_migration_status(stage)
    message = 'Stopped Live Capture Migration.'
    if not force_update and not is_migration_active(replayer_status):
        message = 'Live Capture Migration does not appear to be active, no action necessary.'
    else:
        try:
            ecs_client.update_service(cluster=f'migration-{stage}-ecs-cluster',
                                      service=f'migration-{stage}-traffic-replayer-default',
                                      desiredCount=0)
            replayer_status = OperationStatus.STOPPED
        except Exception as e:
            message = str(e)
    return {'status': str(replayer_status), 'message': message}


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action='store_true', help="Bypass sanity checks and update ECS service")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    env_stage = get_deployed_stage()
    print(json.dumps(stop_live_capture_migration(env_stage, args.force)))
