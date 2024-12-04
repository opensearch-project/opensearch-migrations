from rest_framework import serializers
from .models import Transformation

class TransformationSerializer(serializers.ModelSerializer):
    class Meta:
        model = Transformation
        fields = ['id', 'input_shape', 'output_shape', 'transform', 'created_at']
        read_only_fields = ['id', 'output_shape', 'transform', 'created_at']

class TransformationRequestSerializer(serializers.Serializer):
    input_shape = serializers.JSONField()  # Ensure it's JSON