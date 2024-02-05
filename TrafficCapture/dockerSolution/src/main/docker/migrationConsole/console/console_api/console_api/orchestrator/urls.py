from django.urls import path
from . import views

urlpatterns = [
    path('status', views.migration_status, name='migration-status'),
]
