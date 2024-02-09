from django.urls import path
from . import views

urlpatterns = [
    path('status', views.migration_status, name='migration-status'),
    path('start', views.start_migration, name='migration-start'),
    path('stop', views.stop_migration, name='migration-stop'),
]
