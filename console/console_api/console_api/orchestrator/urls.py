from django.urls import path
from . import views

urlpatterns = [
    #path("", views.index, name="index"),
    path('status', views.migration_status, name='migration-status'),
    path('custom-api/', views.CustomAPIView.as_view(), name='custom-api'),
]
