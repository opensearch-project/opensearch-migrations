from rest_framework import serializers

class MigrationStatusSerializer(serializers.Serializer):
    status = serializers.CharField(max_length=100)
    details = serializers.CharField(max_length=1000)
    timestamp = serializers.DateTimeField()
