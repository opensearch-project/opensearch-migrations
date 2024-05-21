from console_api.apps.orchestrator.serializers import GenericMigrationSerializer, OpenSearchIngestionCreateRequestSerializer
from console_link.models.migration import MigrationType, Migration, OpenSearchIngestionMigration
from rest_framework.decorators import api_view, parser_classes
from rest_framework.parsers import JSONParser
from rest_framework.response import Response
from rest_framework import status
import datetime
import logging

logger = logging.getLogger(__name__)

MIGRATION_TYPE_FIELD = "MigrationType"


def pretty_request(request, data):
    headers = ''
    for header, value in request.META.items():
        if not header.startswith('HTTP'):
            continue
        header = '-'.join([h.capitalize() for h in header[5:].lower().split('_')])
        headers += '{}: {}\n'.format(header, value)

    return (
        '\n{method} HTTP/1.1\n'
        'Headers---\n{headers}\n'
        'Body---\n{body}\n'
    ).format(
        method=request.method,
        headers=headers,
        body=data,
    )


@api_view(['POST'])
@parser_classes([JSONParser])
def create_migration(request):
    request_data = request.data
    logger.info(pretty_request(request, request_data))
    migration_serializer = GenericMigrationSerializer(data=request_data)
    if not migration_serializer.is_valid():
        return Response(migration_serializer.errors, status=status.HTTP_400_BAD_REQUEST)
    migration_type = request_data[MIGRATION_TYPE_FIELD]
    if migration_type == MigrationType.OSI_HISTORICAL_MIGRATION:
        osi_serializer = OpenSearchIngestionCreateRequestSerializer(data=request_data)
        #if not osi_serializer.is_valid():
        #    return Response(osi_serializer.errors, status=status.HTTP_400_BAD_REQUEST)
        migration = OpenSearchIngestionMigration()
    else:
        return Response(f"Unknown migration type accepted: {migration_type}",
                        status=status.HTTP_500_INTERNAL_SERVER_ERROR)
    migration.create()

    response_data = {
        'Timestamp': datetime.datetime.now(datetime.timezone.utc),
        'MigrationType': migration_type
    }
    return Response(response_data, status=status.HTTP_200_OK)


# TODO implement stub with backend
@api_view(['POST'])
@parser_classes([JSONParser])
def start_migration(request):
    request_data = request.data
    status_code = 200
    logger.info(pretty_request(request, request_data))
    response_data = {
        'Timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    return Response(response_data, status=status_code)


# TODO implement stub with backend
@api_view(['POST'])
@parser_classes([JSONParser])
def stop_migration(request):
    request_data = request.data
    status_code = 200
    logger.info(pretty_request(request, request_data))
    response_data = {
        'Timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    return Response(response_data, status=status_code)
