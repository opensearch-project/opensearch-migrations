from console_api.apps.orchestrator.serializers import (OpenSearchIngestionCreateRequestSerializer,
                                                       OpenSearchIngestionDefaultRequestSerializer)
from console_link.models.osi_utils import (InvalidAuthParameters, create_pipeline_from_json, start_pipeline,
                                           stop_pipeline, delete_pipeline, get_status, get_assume_role_session)
from django.http import JsonResponse
from rest_framework.decorators import api_view, parser_classes
from rest_framework.parsers import JSONParser
from rest_framework import status
from pathlib import Path
from typing import Callable
import boto3
import datetime
import json
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

    osi_serializer = OpenSearchIngestionDefaultRequestSerializer(data=request_data)
    osi_serializer.is_valid(raise_exception=True)
    pipeline_name = request_data.get('PipelineName')
    response_data = {
        'timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    try:
        osi_action_func(osi_client=determine_osi_client(request_data=request_data, action_name=action_name),
                        pipeline_name=pipeline_name)
    except Exception as e:
        logger.error(f'Error performing OSI {action_name} Pipeline API: {e}')
        response_data['error'] = str(e)
        return JsonResponse(response_data, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

    return JsonResponse(response_data, status=status.HTTP_200_OK)


@api_view(['POST'])
@parser_classes([JSONParser])
def osi_create_migration(request):
    request_data = request.data
    logger.info(pretty_request(request, request_data))
    action_name = 'Create'

    osi_serializer = OpenSearchIngestionCreateRequestSerializer(data=request_data)
    osi_serializer.is_valid(raise_exception=True)
    response_data = {
        'timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    try:
        create_pipeline_from_json(osi_client=determine_osi_client(request_data=request_data, action_name=action_name),
                                  input_json=request_data,
                                  pipeline_template_path=PIPELINE_TEMPLATE_PATH)
    except InvalidAuthParameters as i:
        logger.error(f'Error performing OSI {action_name} Pipeline API: {i}')
        response_data['error'] = str(i)
        return JsonResponse(response_data, status=status.HTTP_400_BAD_REQUEST)
    except Exception as e:
        logger.error(f'Error performing OSI {action_name} Pipeline API: {e}')
        response_data['error'] = str(e)
        return JsonResponse(response_data, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

    return JsonResponse(response_data, status=status.HTTP_200_OK)


@api_view(['POST'])
@parser_classes([JSONParser])
def osi_start_migration(request):
    return osi_update_workflow(request=request, osi_action_func=start_pipeline, action_name='Start')


@api_view(['POST'])
@parser_classes([JSONParser])
def osi_stop_migration(request):
    return osi_update_workflow(request=request, osi_action_func=stop_pipeline, action_name='Stop')


@api_view(['POST'])
@parser_classes([JSONParser])
def osi_delete_migration(request):
    return osi_update_workflow(request=request, osi_action_func=delete_pipeline, action_name='Delete')


@api_view(['GET'])
@parser_classes([JSONParser])
def osi_get_status_migration(request):
    response_data = {
        'timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    action_name = 'GetStatus'
    try:
        # Read the raw body data, necessary for GET requests with a body in Django
        body_unicode = request.body.decode('utf-8')
        request_data = json.loads(body_unicode)
    except json.JSONDecodeError as jde:
        logger.error(f'Error performing OSI {action_name} Pipeline API: {jde}')
        response_data['error'] = f'Unable to parse JSON. Exception: {jde}'
        return JsonResponse(data=response_data, status=status.HTTP_400_BAD_REQUEST)

    logger.info(pretty_request(request, request_data))
    osi_serializer = OpenSearchIngestionDefaultRequestSerializer(data=request_data)
    osi_serializer.is_valid(raise_exception=True)
    pipeline_name = request_data.get('PipelineName')
    try:
        status_dict = get_status(osi_client=determine_osi_client(request_data, action_name),
                                 pipeline_name=pipeline_name)
    except Exception as e:
        logger.error(f'Error performing OSI {action_name} Pipeline API: {e}')
        response_data['error'] = str(e)
        return JsonResponse(response_data, status=status.HTTP_500_INTERNAL_SERVER_ERROR)

    response_data.update(**status_dict)
    return JsonResponse(response_data, status=status.HTTP_200_OK)
