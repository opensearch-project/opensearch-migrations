from rest_framework.response import Response
from rest_framework.decorators import api_view
from strenum import StrEnum
from .serializers import MigrationStatusSerializer
import datetime
import sys
import json
import logging

sys.path.append("/root")
from stopFetchMigration import stop_fetch_migration
from stopLiveCaptureMigration import stop_live_capture_migration
from startFetchMigration import start_fetch_migration
from startLiveCaptureMigration import start_live_capture_migration
from getFetchMigrationStatus import get_fetch_migration_status
from getLiveCaptureMigrationStatus import get_live_capture_migration_status
from migrationUtils import get_deployed_stage, is_migration_active, OperationStatus

root_logger = logging.getLogger()
root_logger.handlers = []
root_logger.setLevel(logging.INFO)
console_handler = logging.StreamHandler()
console_handler.setLevel(logging.INFO)
root_logger.addHandler(console_handler)
logger = logging.getLogger(__name__)

env_stage = get_deployed_stage()

class MigrationType(StrEnum):
    FULL_LOAD = 'full-load'
    CDC = 'cdc'


def pretty_request(request, decoded_body):
    headers = ''
    for header, value in request.META.items():
        if not header.startswith('HTTP'):
            continue
        header = '-'.join([h.capitalize() for h in header[5:].lower().split('_')])
        headers += '{}: {}\n'.format(header, value)

    return (
        '{method} HTTP/1.1\n'
        '{headers}\n'
        '{body}\n'
    ).format(
        method=request.method,
        headers=headers,
        body=decoded_body,
    )


def perform_action(request, full_load_func, cdc_func):
    body = json.loads(request.body.decode("utf-8"))
    logger.info(pretty_request(request, body))
    migration_type = body['MigrationType']
    action_output = ''
    message = ''
    status = ''
    status_code = 200
    if migration_type == MigrationType.FULL_LOAD:
        action_output = full_load_func()
    elif migration_type == MigrationType.CDC:
        action_output = cdc_func()
    else:
        #TODO add validation for incoming model
        raise RuntimeError(f'Unknown MigrationType provided: {migration_type}')

    if action_output:
        logger.info(f'Action output: {action_output}')
        message = action_output['message']
        status = action_output['status']

    data = {
        'MigrationStatus': status,
        'Details': message,
        'Timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    return Response(data, status=status_code)


@api_view(['GET'])
def migration_status(request):

    logger.info(pretty_request(request, request.body))

    # This is a bit limited for now, more or less we are detecting "Am I starting?", "Am I running?", or
    # "Am I doing nothing?" based on the presence (or absence) of containers and their container status
    fetch_status = get_fetch_migration_status(env_stage)
    live_capture_status = get_live_capture_migration_status(env_stage)
    logger.info(f'Fetch status: {fetch_status}, Live Capture status: {live_capture_status}')

    data = {
        'FetchMigrationStatus': fetch_status,
        'LiveCaptureMigrationStatus': live_capture_status,
        'Timestamp': datetime.datetime.now(datetime.timezone.utc)
    }

    #serializer = MigrationStatusSerializer(data=data)
    #serializer.is_valid(raise_exception=True)
    return Response(data, status=200)


@api_view(['POST'])
def start_migration(request):
    return perform_action(request,
                          lambda: start_fetch_migration(env_stage, False),
                          lambda: start_live_capture_migration(env_stage, False))


@api_view(['POST'])
def stop_migration(request):
    return perform_action(request,
                          lambda: stop_fetch_migration(env_stage, False),
                          lambda: stop_live_capture_migration(env_stage, False))
