from rest_framework.response import Response
from rest_framework.decorators import api_view
from .serializers import MigrationStatusSerializer
import datetime
import subprocess
import os
import sys

def pretty_request(request):
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
        body=request.body,
    )

@api_view(['GET'])
def migration_status(request):

    sys.stderr.write(pretty_request(request))
    data = {
        'status': 'Completed',
        'details': 'Migration completed successfully.',
        'timestamp': datetime.datetime.now(datetime.timezone.utc)
    }

    serializer = MigrationStatusSerializer(data=data)

    serializer.is_valid(raise_exception=True)

    return Response(serializer.data)


@api_view(['POST'])
def start_migration(request):

    # TODO remove temporary mocking
    sys.stderr.write(pretty_request(request))
    data = {
        'status': 'Started',
        'details': 'Migration started.',
        'timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    return Response(data, status=200)

    # Use subprocess.Popen to run the command
    #process = subprocess.Popen(['/root/catIndices.sh'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    full_load = os.getenv('FETCH_MIGRATION_COMMAND')
    if not full_load:
        return Response({"errors": ["Cannot execute full load command"]}, status=400)

    # TODO: Create replayer command
    # command = os.getenv('START_REPLAYER')
    process = subprocess.Popen(['ls'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    output, errors = process.communicate()

    # Check for errors
    if process.returncode != 0:
        return Response({"errors": errors}, status=400)

    # Split the output into a list of files
    index_listing = output.split('\n')

    # Return the list of files in the response
    return Response({"Index Listing\n": index_listing})

@api_view(['POST'])
def stop_migration(request):
    # TODO remove temporary mocking
    sys.stderr.write(pretty_request(request))
    data = {
        'status': 'Stopped',
        'details': 'Migration stopped.',
        'timestamp': datetime.datetime.now(datetime.timezone.utc)
    }
    return Response(data, status=200)