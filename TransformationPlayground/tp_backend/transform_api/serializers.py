from typing import Any, Dict, List

from rest_framework import serializers

from transform_expert.parameters import SourceVersion, TargetVersion, TransformType, TransformLanguage


class EnumChoiceField(serializers.ChoiceField):
    """
    Custom ChoiceField to work seamlessly with Enum types.
    Converts values to Enum members on validation.
    """
    def __init__(self, enum, **kwargs):
        self.enum = enum
        super().__init__(choices=[(item.value, item.name) for item in enum], **kwargs)

    def to_internal_value(self, data):
        # Validate and convert the input to an Enum member
        if data not in [item.value for item in self.enum]:
            self.fail("invalid_choice", input=data)
        return self.enum(data)

    def to_representation(self, value):
        # Convert the Enum member back to its value for representation
        return value.value if isinstance(value, self.enum) else value
    
class IndexShapeField(serializers.Field):
    """
    Custom serializer field to validate index shape data with the structure:
    {"index_name": <string>, "index_json": <dict>}
    """

    def to_internal_value(self, data: Dict[str, Any]) -> Dict[str, Any]:
        if not isinstance(data, dict):
            raise serializers.ValidationError("Must be a JSON object.")

        # Ensure required keys exist
        if 'index_name' not in data or 'index_json' not in data:
            raise serializers.ValidationError(
                "Must contain 'index_name' and 'index_json' keys."
            )

        # Validate `index_name`
        if not isinstance(data['index_name'], str):
            raise serializers.ValidationError("'index_name' must be a string.")

        # Validate `index_json`
        if not isinstance(data['index_json'], dict):
            raise serializers.ValidationError("'index_json' must be a dictionary.")

        return data

    def to_representation(self, value: Dict[str, Any]) -> Dict[str, Any]:
        # Pass-through representation logic
        return value


class TransformsIndexCreateRequestSerializer(serializers.Serializer):
    transform_language = EnumChoiceField(enum=TransformLanguage)
    source_version = EnumChoiceField(enum=SourceVersion)
    target_version = EnumChoiceField(enum=TargetVersion)
    input_shape = IndexShapeField()
    test_target_url = serializers.URLField(required=False, default=None)
    
class TransformsIndexCreateResponseSerializer(serializers.Serializer):
    output_shape = serializers.ListField(child=IndexShapeField())
    transform_logic = serializers.CharField()