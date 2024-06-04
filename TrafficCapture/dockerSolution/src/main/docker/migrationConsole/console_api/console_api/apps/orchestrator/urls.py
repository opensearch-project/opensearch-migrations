from django.urls import path
from . import views

urlpatterns = [
    path('osi-create-migration', views.osi_create_migration, name='osi-create-migration'),
    path('osi-start-migration', views.osi_start_migration, name='osi-start-migration'),
    path('osi-stop-migration', views.osi_stop_migration, name='osi-stop-migration'),
]
