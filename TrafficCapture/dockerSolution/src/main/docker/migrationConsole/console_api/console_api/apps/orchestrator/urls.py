from django.urls import path
from . import views

urlpatterns = [
    path('start-historical-migration', views.start_historical_migration, name='start-historical-migration'),
    path('stop-historical-migration', views.stop_historical_migration, name='stop-historical-migration'),
]
