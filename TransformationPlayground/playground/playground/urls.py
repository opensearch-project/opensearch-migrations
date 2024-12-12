from django.urls import path
from transform_api.views import TransformsIndexView

urlpatterns = [
    path('transforms/index/', TransformsIndexView.as_view(), name='transforms_index'),
]