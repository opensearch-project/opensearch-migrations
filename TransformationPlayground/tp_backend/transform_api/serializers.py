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


class TransformationCreateRequestSerializer(serializers.Serializer):
    transform_type = EnumChoiceField(enum=TransformType)
    transform_language = EnumChoiceField(enum=TransformLanguage)
    source_version = EnumChoiceField(enum=SourceVersion)
    target_version = EnumChoiceField(enum=TargetVersion)
    input_shape = serializers.JSONField()
    test_target_url = serializers.URLField(required=False, default=None)

    def validate_input_shape(self, value: Dict[str, Any]) -> Dict[str, Any]:
        """
        Custom validation to ensure `input_shape` has the required structure:
        {"index_name": <string>, "index_json": <dict>}
        """
        # Ensure required keys exist
        if not isinstance(value, dict):
            raise serializers.ValidationError("input_shape must be a JSON object.")
        if 'index_name' not in value or 'index_json' not in value:
            raise serializers.ValidationError(
                "input_shape must contain 'index_name' and 'index_json' keys."
            )

        # Validate `index_name`
        if not isinstance(value['index_name'], str):
            raise serializers.ValidationError("'index_name' must be a string.")

        # Validate `index_json`
        if not isinstance(value['index_json'], dict):
            raise serializers.ValidationError("'index_json' must be a dictionary.")

        return value
    
class TransformationCreateResponseSerializer(serializers.Serializer):
    # Define our fields
    output_shape = serializers.JSONField()
    transform_logic = serializers.CharField()

    # Define our custom validation methods
    def validate_output_shape(self, value: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """
        Custom validation to ensure `output_shape` has the required structure:
        [
            {"index_name": <string>, "index_json": <dict>},
            ...
        ]
        """
        # Ensure "output_shape" is a list
        if not isinstance(value, list):
            raise serializers.ValidationError("output_shape must be a JSON list.")
        
        # Ensure each item in the list is a dictionary
        for item in value:
            if not isinstance(item, dict):
                raise serializers.ValidationError("Each item in output_shape must be a JSON object.")
            
            # Ensure required keys exist
            if 'index_name' not in item or 'index_json' not in item:
                raise serializers.ValidationError(
                    "Each item in output_shape must contain 'index_name' and 'index_json' keys."
                )

            # Validate `index_name`
            if not isinstance(item['index_name'], str):
                raise serializers.ValidationError("'index_name' must be a string.")

            # Validate `index_json`
            if not isinstance(item['index_json'], dict):
                raise serializers.ValidationError("'index_json' must be a dictionary.")

        return value