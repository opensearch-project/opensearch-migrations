from console_api.apps.orchestrator.serializers import OpenSearchIngestionCreateRequestSerializer
from console_link.models.osi_utils import (InvalidAuthParameters, create_pipeline_from_json, start_pipeline,
                                           stop_pipeline)
from rest_framework.decorators import api_view, parser_classes
from rest_framework.parsers import JSONParser
from rest_framework.response import Response
from rest_framework import status
from pathlib import Path
import boto3
import datetime
from enum import Enum
import logging

logger = logging.getLogger(__name__)

PIPELINE_TEMPLATE_PATH = f"{Path(__file__).parents[4]}/osiPipelineTemplate.yaml"

MigrationType = Enum('MigrationType', ['OSI_HISTORICAL_MIGRATION'])


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
def osi_create_migration(request):
    request_data = request.data
    logger.info(pretty_request(request, request_data))

    osi_serializer = OpenSearchIngestionCreateRequestSerializer(data=request_data)
    osi_serializer.is_valid(raise_exception=True)
    try:
        osi_client = boto3.client('osis')
        create_pipeline_from_json(osi_client=osi_client,
                                  input_json=request_data,
                                  pipeline_template_path=PIPELINE_TEMPLATE_PATH)
    except InvalidAuthParameters as i:
        logger.error(f"Error performing osi_create_migration API: {i}")
        return Response(str(i), status=status.HTTP_400_BAD_REQUEST)
    except Exception as e:
        logger.error(f"Error performing osi_create_migration API: {e}")
        return Response(str(e), status=status.HTTP_500_INTERNAL_SERVER_ERROR)

    response_data = {
        'Timestamp': datetime.datetime.now(datetime.timezone.utc),
        'MigrationType': MigrationType.OSI_HISTORICAL_MIGRATION
    }
    return Response(response_data, status=status.HTTP_200_OK)


@api_view(['POST'])
@parser_classes([JSONParser])
def osi_start_migration(request):
    request_data = request.data
    logger.info(pretty_request(request, request_data))

    pipeline_name = request_data.get("PipelineName")
    try:
        osi_client = boto3.client('osis')
        start_pipeline(osi_client=osi_client, pipeline_name=pipeline_name)
    except Exception as e:
        logger.error(f"Error performing osi_start_migration API: {e}")
        return Response(str(e), status=status.HTTP_500_INTERNAL_SERVER_ERROR)

    response_data = {
        'Timestamp': datetime.datetime.now(datetime.timezone.utc),
        'MigrationType': MigrationType.OSI_HISTORICAL_MIGRATION
    }
    return Response(response_data, status=status.HTTP_200_OK)


@api_view(['POST'])
@parser_classes([JSONParser])
def osi_stop_migration(request):
    request_data = request.data
    logger.info(pretty_request(request, request_data))

    pipeline_name = request_data.get("PipelineName")
    try:
        osi_client = boto3.client('osis')
        stop_pipeline(osi_client=osi_client, pipeline_name=pipeline_name)
    except Exception as e:
        logger.error(f"Error performing osi_stop_migration API: {e}")
        return Response(str(e), status=status.HTTP_500_INTERNAL_SERVER_ERROR)

    response_data = {
        'Timestamp': datetime.datetime.now(datetime.timezone.utc),
        'MigrationType': MigrationType.OSI_HISTORICAL_MIGRATION
    }
    return Response(response_data, status=status.HTTP_200_OK)
