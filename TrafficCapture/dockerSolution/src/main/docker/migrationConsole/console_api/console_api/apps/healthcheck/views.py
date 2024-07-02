from rest_framework.response import Response
import logging

logger = logging.getLogger(__name__)


def healthcheck(request):
    return Response("OK", content_type="text/plain")
