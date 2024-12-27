from django.test import TestCase
from rest_framework.serializers import ValidationError

from transform_api.serializers import EnumChoiceField, IndexShapeField, TransformsIndexCreateRequestSerializer, TransformsIndexCreateResponseSerializer
from transform_expert.parameters import TransformLanguage


class EnumChoiceFieldTestCase(TestCase):
    def test_valid_input(self):
        """Test that valid input is accepted."""
        test_field = EnumChoiceField(enum=TransformLanguage)
        result = test_field.to_internal_value("Python")
        self.assertEqual(result, TransformLanguage.PYTHON)

    def test_invalid_input(self):
        """Test that invalid input raises a ValidationError."""
        test_field = EnumChoiceField(enum=TransformLanguage)
        with self.assertRaises(ValidationError) as context:
            test_field.to_internal_value("Not a valid language")

class IndexShapeFieldTestCase(TestCase):
    def setUp(self):
        self.field = IndexShapeField()

    def test_valid_input(self):
        """Test that valid input is accepted."""
        valid_data = {
            "index_name": "my_index",
            "index_json": {"shards": 1, "replicas": 2}
        }
        result = self.field.to_internal_value(valid_data)
        self.assertEqual(result, valid_data)

    def test_invalid_input_not_a_dict(self):
        """Test that non-dict input raises a ValidationError."""
        invalid_data = ["not", "a", "dict"]
        with self.assertRaises(ValidationError) as context:
            self.field.to_internal_value(invalid_data)
        self.assertEqual(str(context.exception.detail[0]), "Must be a JSON object.")

    def test_invalid_input_missing_keys(self):
        """Test that missing required keys raises a ValidationError."""
        invalid_data = {"index_name": "my_index"}
        with self.assertRaises(ValidationError) as context:
            self.field.to_internal_value(invalid_data)
        self.assertEqual(
            str(context.exception.detail[0]),
            "Must contain 'index_name' and 'index_json' keys."
        )

    def test_invalid_index_name_type(self):
        """Test that a non-string `index_name` raises a ValidationError."""
        invalid_data = {
            "index_name": 123,
            "index_json": {"shards": 1}
        }
        with self.assertRaises(ValidationError) as context:
            self.field.to_internal_value(invalid_data)
        self.assertEqual(str(context.exception.detail[0]), "'index_name' must be a string.")

    def test_invalid_index_json_type(self):
        """Test that a non-dict `index_json` raises a ValidationError."""
        invalid_data = {
            "index_name": "my_index",
            "index_json": "not_a_dict"
        }
        with self.assertRaises(ValidationError) as context:
            self.field.to_internal_value(invalid_data)
        self.assertEqual(str(context.exception.detail[0]), "'index_json' must be a dictionary.")

    def test_to_representation(self):
        """Test that `to_representation` outputs the value as-is."""
        valid_data = {
            "index_name": "my_index",
            "index_json": {"shards": 1, "replicas": 2}
        }
        result = self.field.to_representation(valid_data)
        self.assertEqual(result, valid_data)

class TransformationCreateRequestSerializerTestCase(TestCase):
    def test_valid_input(self):
        test_serializer = TransformsIndexCreateRequestSerializer(data={
            "transform_type": "Index",
            "transform_language": "Python",
            "source_version": "Elasticsearch 6.8",
            "target_version": "OpenSearch 2.17",
            "input_shape": {
                "index_name": "test-index",
                "index_json": {
                    "settings": {
                        "index": {
                            "number_of_shards": 1,
                            "number_of_replicas": 0
                        }
                    },
                    "mappings": {
                        "type1": {
                            "properties": {
                                "title": { "type": "text" }
                            }
                        },
                        "type2": {
                            "properties": {
                                "contents": { "type": "text" }
                            }
                        }
                    }
                }
            },
            "transform_logic": "some transform logic",
            "user_guidance": "some user guidance",
            "test_target_url": "http://localhost:29200"
        })
        self.assertTrue(test_serializer.is_valid())
    
    def test_invalid_input(self):
        test_serializer = TransformsIndexCreateRequestSerializer(data={
            "transform_type": "Index",
            "transform_language": "Python",
            "source_version": "Elasticsearch 6.8",
            "target_version": "OpenSearch 2.17",
            "input_shape": {
                "index_name": "test-index"
            },
            "test_target_url": "http://localhost:29200"
        })
        self.assertFalse(test_serializer.is_valid())

class TransformationCreateResponseSerializerTestCase(TestCase):
    def test_valid_input(self):
        test_serializer = TransformsIndexCreateResponseSerializer(data={
            "output_shape": [
                {
                    "index_name": "test-index-type1",
                    "index_json": {
                        "settings": {
                            "index": {
                                "number_of_shards": 1,
                                "number_of_replicas": 0
                            }
                        },
                        "mappings": {
                            "properties": {
                                "title": {
                                    "type": "text"
                                }
                            }
                        }
                    }
                },
                {
                    "index_name": "test-index-type2",
                    "index_json": {
                        "settings": {
                            "index": {
                                "number_of_shards": 1,
                                "number_of_replicas": 0
                            }
                        },
                        "mappings": {
                            "properties": {
                                "contents": {
                                    "type": "text"
                                }
                            }
                        }
                    }
                }
            ],
            "transform_logic": "the new transform logic",
            "validation_report": [
                "Entry 1",
                "Entry 2"
            ],
            "validation_outcome": "PASSED"
        })
        self.assertTrue(test_serializer.is_valid())

    def test_invalid_input(self):
        test_serializer = TransformsIndexCreateResponseSerializer(data={
            "output_shape": [
                {
                    "index_name": "test-index-type1"
                }
            ],
            "transform_logic": "the transform logic",
            "validation_report": [
                "Entry 1",
                "Entry 2"
            ],
            "validation_outcome": "PASSED"
        })
        self.assertFalse(test_serializer.is_valid())