from rest_framework.decorators import api_view, parser_classes
from rest_framework.parsers import JSONParser
from rest_framework.response import Response
import datetime
import logging

logger = logging.getLogger(__name__)


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


# TODO implement stub with backend
@api_view(['POST'])
@parser_classes([JSONParser])
def start_historical_migration(request):
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
def stop_historical_migration(request):
    request_data = request.data
    status_code = 200
    logger.info(pretty_request(request, request_data))
    response_data = {
        'Timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    return Response(response_data, status=status_code)
