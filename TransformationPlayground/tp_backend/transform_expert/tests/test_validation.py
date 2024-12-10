from unittest.mock import MagicMock

from django.test import TestCase

from transform_expert.utils.transforms import TransformPython, TransformTask
from transform_expert.validation import test_target_connection, TestTargetInnaccessibleError, ValidationReport, IndexTransformValidator
from transform_expert.utils.opensearch_client import OpenSearchClient


class TestTargetConnectionCase(TestCase):
    def test_target_is_available(self):
        mock_connection = MagicMock(OpenSearchClient)
        mock_connection.is_accessible.return_value = True

        result = test_target_connection(mock_connection)

        self.assertIsNone(result)

    def test_target_is_unavailable(self):
        mock_connection = MagicMock(OpenSearchClient)
        mock_connection.is_accessible.return_value = False

        with self.assertRaises(TestTargetInnaccessibleError):
            test_target_connection(mock_connection)


class TestIndexTransformValidatorCase(TestCase):
    def setUp(self):
        self.test_input = {
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
        }
        self.test_output = [
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
        ]

        self.test_transform = TransformPython(
            imports = "from typing import Dict, Any, List\nimport copy",
            description = "This transformation function converts Elasticsearch 6.8 index settings to OpenSearch 2.17 compatible format.\nIt handles the removal of mapping types and creates separate indexes for each type in multi-type mappings.",
            code =  "def transform(source_json: Dict[str, Any]) -> List[Dict[str, Any]]:\n    result = []\n    index_name = source_json['index_name']\n    index_json = source_json['index_json']\n    \n    # Extract settings\n    settings = index_json.get('settings', {})\n    \n    # Extract mappings\n    mappings = index_json.get('mappings', {})\n    \n    # If there are multiple types, create separate indexes for each type\n    if len(mappings) > 1:\n        for type_name, type_mapping in mappings.items():\n            new_index_name = f\"{index_name}-{type_name}\"\n            new_index_json = {\n                'settings': copy.deepcopy(settings),\n                'mappings': type_mapping\n            }\n            result.append({\n                'index_name': new_index_name,\n                'index_json': new_index_json\n            })\n    else:\n        # If there's only one type or no types, just remove the type layer\n        new_mappings = next(iter(mappings.values())) if mappings else {}\n        new_index_json = {\n            'settings': settings,\n            'mappings': new_mappings\n        }\n        result.append({\n            'index_name': index_name,\n            'index_json': new_index_json\n        })\n    \n    return result"
        )

    def test_happy_path_w_target(self):
        # Set up our test inputs
        mock_connection = MagicMock(OpenSearchClient)
        mock_connection.create_index.side_effect = [
            {"acknowledged": True, "shards_acknowledged": True, "index": "test-index-type1"},
            {"acknowledged": True, "shards_acknowledged": True, "index": "test-index-type2"}
        ]
        mock_connection.delete_index.side_effect = [
            {"acknowledged": True},
            {"acknowledged": True}
        ]

        test_task = TransformTask(
            transform_id="test-transform",
            input=self.test_input,
            context=[],
            transform=self.test_transform,
            output=None
        )

        # Run our test
        validator = IndexTransformValidator(test_task, mock_connection)
        report = validator.validate()

        # Check the results
        self.assertTrue(report.passed)
        self.assertEqual(self.test_output, report.task.output)
        self.assertTrue(len(report.report_entries) > 0)
        self.assertEqual(2, mock_connection.create_index.call_count)
        self.assertEqual(2, mock_connection.delete_index.call_count)

    def test_happy_path_w_o_target(self):
        # Set up our test inputs
        mock_connection = None

        test_task = TransformTask(
            transform_id="test-transform",
            input=self.test_input,
            context=[],
            transform=self.test_transform,
            output=None
        )

        # Run our test
        validator = IndexTransformValidator(test_task, mock_connection)
        report = validator.validate()

        # Check the results
        self.assertTrue(report.passed)
        self.assertEqual(self.test_output, report.task.output)
        self.assertTrue(len(report.report_entries) > 0)

        

