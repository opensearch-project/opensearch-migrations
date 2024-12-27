from django.urls import path
from drf_spectacular.views import SpectacularAPIView, SpectacularSwaggerView

from transform_api.views import TransformsIndexCreateView, TransformsIndexTestView


urlpatterns = [
    path('api/schema/', SpectacularAPIView.as_view(), name='schema'),
    path('api/docs/', SpectacularSwaggerView.as_view(url_name='schema'), name='swagger-ui'),
    path('transforms/index/create/', TransformsIndexCreateView.as_view(), name='transforms_index_create'),
    path('transforms/index/test/', TransformsIndexTestView.as_view(), name='transforms_index_test'),
]