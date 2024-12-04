from django.urls import path
from api.views import TransformationView

urlpatterns = [
    path('transform/', TransformationView.as_view(), name='transform'),
]