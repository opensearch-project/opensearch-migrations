from django.http import HttpResponse
from rest_framework.decorators import api_view
import logging

logger = logging.getLogger(__name__)


@api_view(['GET'])
def healthcheck(request):
    return HttpResponse("OK")
