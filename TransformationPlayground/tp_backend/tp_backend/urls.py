from django.urls import path
from transform_api.views import TransformationsView

urlpatterns = [
    path('transforms/', TransformationsView.as_view(), name='transforms'),
]