from rest_framework.response import Response
from rest_framework.decorators import api_view
import datetime
import json
import logging

#sys.path.append("/root")

root_logger = logging.getLogger()
root_logger.handlers = []
root_logger.setLevel(logging.INFO)
console_handler = logging.StreamHandler()
console_handler.setLevel(logging.INFO)
root_logger.addHandler(console_handler)
logger = logging.getLogger(__name__)


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


def parse_json_body(request):
    try:
        return json.loads(request.body.decode("utf-8"))
    except Exception as e:
        logger.error(f"Unable to parse request body: {e}")
        return None


# TODO implement stub with backend
@api_view(['POST'])
def start_historical_migration(request):
    body = parse_json_body(request)
    status_code = 200
    if body is None:
        status_code = 400
    logger.info(pretty_request(request, body))
    data = {
        'Timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    return Response(data, status=status_code)


# TODO implement stub with backend
@api_view(['POST'])
def stop_historical_migration(request):
    body = parse_json_body(request)
    status_code = 200
    if body is None:
        status_code = 400
    logger.info(pretty_request(request, body))
    data = {
        'Timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    return Response(data, status=status_code)
