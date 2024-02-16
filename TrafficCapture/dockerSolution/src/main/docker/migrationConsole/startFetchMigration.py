#!/usr/bin/env python3

import os
import json
import pexpect
import argparse
import logging
import boto3
from getFetchMigrationStatus import get_fetch_migration_status
from migrationUtils import get_deployed_stage, is_migration_active, OperationStatus

logger = logging.getLogger(__name__)
ecs_client = boto3.client('ecs')


def construct_fetch_command(dry_run: bool, create_only: bool) -> str:
    fetch_command_env = os.environ.get('FETCH_MIGRATION_COMMAND')
    if not fetch_command_env:
        raise RuntimeError('Fetch Migration is unavailable or not deployed as required FETCH_MIGRATION_COMMAND'
                           ' environment variable is missing, exiting...')

    # ECS command overrides argument with placeholder for flags
    overrides_arg = ("--overrides '{ \"containerOverrides\": [ { \"name\": \"fetch-migration\", "
                     "\"command\": <FLAGS> }]}'")
    build_flags = ''
    if dry_run:
        build_flags = build_flags + '--dryrun,'
    if create_only:
        build_flags = build_flags + '--createonly,'
    if build_flags:
        build_flags = build_flags.rstrip(',')
        build_flags = f'[\"{build_flags}\"]'

    finalized_command = fetch_command_env
    if build_flags:
        overrides_arg = overrides_arg.replace('<FLAGS>', build_flags)
        finalized_command = f'{finalized_command} {overrides_arg}'
    return finalized_command


def start_fetch_migration(stage: str, force_update: bool, fetch_command=construct_fetch_command(False, False)):
    fetch_status = get_fetch_migration_status(stage)
    message = 'Starting Fetch Migration.'
    if not force_update and is_migration_active(fetch_status):
        message = 'Fetch Migration appears to be active, no action necessary.'
    else:
        # Disable paging for AWS CLI commands
        os.environ["AWS_PAGER"] = ""
        try:
            output = pexpect.run(fetch_command)
            logger.debug(output.decode("utf-8"))
            fetch_status = OperationStatus.STARTING
        except Exception as e:
            message = str(e)
    return {'status': str(fetch_status), 'message': message}


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--create-only", action='store_true',
                        help="Skips data migration and only creates indices on the target cluster.")
    parser.add_argument("--dryrun", action='store_true',
                        help="Performs a dry-run. Only a report is printed - no indices are created or migrated.")
    parser.add_argument("--display-only", action='store_true',
                        help="Display the AWS CLI command needed, without executing it")
    parser.add_argument("--force", action='store_true', help="Bypass sanity checks and update ECS task")
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    env_stage = get_deployed_stage()
    fetch_command_created = construct_fetch_command(args.dryrun, args.create_only)
    if args.display_only:
        print(fetch_command_created)
    else:
        print(json.dumps(start_fetch_migration(env_stage, args.force, fetch_command_created)))
