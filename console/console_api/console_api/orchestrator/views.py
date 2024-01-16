from django.http import HttpResponse


def index(request):
    return HttpResponse("We got this!")
