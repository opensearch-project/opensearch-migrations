from console_api.apps.orchestrator.serializers import (OpenSearchIngestionCreateRequestSerializer,
                                                       OpenSearchIngestionUpdateRequestSerializer)
from console_link.models.osi_utils import (InvalidAuthParameters, create_pipeline_from_json, start_pipeline,
                                           stop_pipeline, delete_pipeline, get_assume_role_session)
from rest_framework.decorators import api_view, parser_classes
from rest_framework.parsers import JSONParser
from rest_framework.response import Response
from rest_framework import status
from pathlib import Path
from typing import Callable
import boto3
import datetime
import logging

logger = logging.getLogger(__name__)

PIPELINE_TEMPLATE_PATH = f"{Path(__file__).parents[4]}/osiPipelineTemplate.yaml"


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


def determine_osi_client(request_data, action_name: str):
    pipeline_manager_role_arn = request_data.get('PipelineManagerAssumeRoleArn')
    if pipeline_manager_role_arn:
        logger.debug(f'Assuming provided role: {pipeline_manager_role_arn}')
        session = get_assume_role_session(role_arn=pipeline_manager_role_arn,
                                          session_name=f'Console{action_name}PipelineAssumeRole')
        osi_client = session.client('osis')
    else:
        osi_client = boto3.client('osis')
    return osi_client


def osi_update_workflow(request, osi_action_func: Callable, action_name: str):
    request_data = request.data
    logger.info(pretty_request(request, request_data))

    osi_serializer = OpenSearchIngestionUpdateRequestSerializer(data=request_data)
    osi_serializer.is_valid(raise_exception=True)
    pipeline_name = request_data.get('PipelineName')
    try:
        osi_action_func(osi_client=determine_osi_client(request_data=request_data, action_name=action_name),
                        pipeline_name=pipeline_name)
    except Exception as e:
        logger.error(f'Error performing OSI {action_name} Pipeline API: {e}')
        return Response(str(e), status=status.HTTP_500_INTERNAL_SERVER_ERROR)

    response_data = {
        'Timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    return Response(response_data, status=status.HTTP_200_OK)


@api_view(['POST'])
@parser_classes([JSONParser])
def osi_create_migration(request):
    request_data = request.data
    logger.info(pretty_request(request, request_data))
    action_name = 'Create'

    osi_serializer = OpenSearchIngestionCreateRequestSerializer(data=request_data)
    osi_serializer.is_valid(raise_exception=True)
    try:
        create_pipeline_from_json(osi_client=determine_osi_client(request_data=request_data, action_name=action_name),
                                  input_json=request_data,
                                  pipeline_template_path=PIPELINE_TEMPLATE_PATH)
    except InvalidAuthParameters as i:
        logger.error(f'Error performing OSI {action_name} Pipeline API: {i}')
        return Response(str(i), status=status.HTTP_400_BAD_REQUEST)
    except Exception as e:
        logger.error(f'Error performing OSI {action_name} Pipeline API: {e}')
        return Response(str(e), status=status.HTTP_500_INTERNAL_SERVER_ERROR)

    response_data = {
        'Timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    return Response(response_data, status=status.HTTP_200_OK)


@api_view(['POST'])
@parser_classes([JSONParser])
def osi_start_migration(request):
    osi_update_workflow(request=request, osi_action_func=start_pipeline, action_name='Start')


@api_view(['POST'])
@parser_classes([JSONParser])
def osi_stop_migration(request):
    osi_update_workflow(request=request, osi_action_func=stop_pipeline, action_name='Stop')


@api_view(['POST'])
@parser_classes([JSONParser])
def osi_delete_migration(request):
    osi_update_workflow(request=request, osi_action_func=delete_pipeline, action_name='Delete')
