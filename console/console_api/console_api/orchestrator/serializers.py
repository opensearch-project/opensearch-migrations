from rest_framework import serializers

class CustomSerializer(serializers.Serializer):
    field1 = serializers.CharField(max_length=100)
    field2 = serializers.IntegerField()