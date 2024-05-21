from django.urls import path
from . import views

urlpatterns = [
    path('create-migration', views.create_migration, name='create-migration'),
    path('start-migration', views.start_migration, name='start-migration'),
    path('stop-migration', views.stop_migration, name='stop-migration'),
]
