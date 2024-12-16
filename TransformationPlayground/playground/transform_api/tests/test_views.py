from unittest.mock import patch, MagicMock
from django.test import TestCase
from rest_framework.test import APIClient
from rest_framework import status

from transform_expert.validation import TestTargetInnaccessibleError


class TransformsIndexViewTestCase(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.url = "/transforms/index/"

        self.valid_request_body = {
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
                                "title": {"type": "text"}
                            }
                        },
                        "type2": {
                            "properties": {
                                "contents": {"type": "text"}
                            }
                        }
                    }
                }
            },
            "test_target_url": "http://localhost:29200"
        }

        self.valid_response_body = {
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
                                "title": {"type": "text"}
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
                                "contents": {"type": "text"}
                            }
                        }
                    }
                }
            ],
            "transform_logic": "Generated Python transformation logic",
            "validation_report": [
                "Attempting to load the transform function...",
                "Loaded the transform function without exceptions",
                "Attempting to invoke the transform function against the input...",
                "Invoked the transform function without exceptions",
                "The transformed output has 2 Index entries.",
                "Attempting to create & delete index 'test-index-type1' with transformed settings...",
                "Created index 'test-index-type1'.  Response: \n{\"acknowledged\": true, \"shards_acknowledged\": true, \"index\": \"test-index-type1\"}",
                "Deleted index 'test-index-type1'.  Response: \n{\"acknowledged\": true}",
                "Attempting to create & delete index 'test-index-type2' with transformed settings...",
                "Created index 'test-index-type2'.  Response: \n{\"acknowledged\": true, \"shards_acknowledged\": true, \"index\": \"test-index-type2\"}",
                "Deleted index 'test-index-type2'.  Response: \n{\"acknowledged\": true}"
            ],
            "validation_outcome": "PASSED"
        }

    @patch("transform_api.views.TransformsIndexView._perform_transformation")
    def test_post_happy_path(self, mock_perform_transformation):
        # Mock the transformation result
        mock_transform_report = MagicMock()
        mock_transform_report.task.output = self.valid_response_body["output_shape"]
        mock_transform_report.report_entries = self.valid_response_body["validation_report"]
        mock_transform_report.passed = True
        mock_transform_report.task.transform.to_file_format.return_value = self.valid_response_body["transform_logic"]
        mock_perform_transformation.return_value = mock_transform_report

        # Make the request
        response = self.client.post(self.url, self.valid_request_body, format="json")

        # Assertions
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.json(), self.valid_response_body)
        mock_perform_transformation.assert_called_once()

    def test_post_invalid_request_body(self):
        # Incomplete request body
        invalid_request_body = {"transform_language": "Python"}

        # Make the request
        response = self.client.post(self.url, invalid_request_body, format="json")

        # Assertions
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("input_shape", response.json())

    @patch("transform_api.views.TransformsIndexView._perform_transformation")
    def test_post_inaccessible_target_cluster(self, mock_perform_transformation):
        # Mock the `_perform_transformation` method to raise `TestTargetInnaccessibleError`
        mock_perform_transformation.side_effect = TestTargetInnaccessibleError("Cluster not accessible")

        # Make the request
        response = self.client.post(self.url, self.valid_request_body, format="json")

        # Assertions
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(response.json(), {"error": "Cluster not accessible"})
        mock_perform_transformation.assert_called_once()

    @patch("transform_api.views.TransformsIndexView._perform_transformation")
    def test_post_general_transformation_failure(self, mock_perform_transformation):
        # Mock the `_perform_transformation` method to raise a general exception
        mock_perform_transformation.side_effect = RuntimeError("General failure")

        # Make the request
        response = self.client.post(self.url, self.valid_request_body, format="json")

        # Assertions
        self.assertEqual(response.status_code, status.HTTP_500_INTERNAL_SERVER_ERROR)
        self.assertEqual(response.json(), {"error": "General failure"})
        mock_perform_transformation.assert_called_once()

    @patch("transform_api.views.TransformsIndexCreateResponseSerializer")
    @patch("transform_api.views.TransformsIndexView._perform_transformation")
    def test_post_invalid_response(self, mock_perform_transformation, mock_response_serializer):
        # Mock the transformation result
        mock_transform_report = MagicMock()
        mock_transform_report.task.output = self.valid_response_body["output_shape"]
        mock_transform_report.report_entries = self.valid_response_body["validation_report"]
        mock_transform_report.passed = True
        mock_transform_report.task.transform.to_file_format.return_value = self.valid_response_body["transform_logic"]
        mock_perform_transformation.return_value = mock_transform_report

        # Mock the serializer behavior
        mock_serializer_instance = mock_response_serializer.return_value
        mock_serializer_instance.is_valid.return_value = False
        mock_serializer_instance.errors = {"transform_logic": ["Invalid format"]}

        # Make the request
        response = self.client.post(self.url, self.valid_request_body, format="json")

        # Assertions
        self.assertEqual(response.status_code, status.HTTP_500_INTERNAL_SERVER_ERROR)
        mock_perform_transformation.assert_called_once()
        mock_serializer_instance.is_valid.assert_called_once()
        self.assertEqual(mock_serializer_instance.errors, {"transform_logic": ["Invalid format"]})

class TransformsIndexTestViewTestCase(TestCase):
    def setUp(self):
        self.client = APIClient()
        self.url = "/transforms/index/test/"

        self.valid_request_body = {
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
                                "title": {"type": "text"}
                            }
                        },
                        "type2": {
                            "properties": {
                                "contents": {"type": "text"}
                            }
                        }
                    }
                }
            },
            "transform_logic": "Generated Python transformation logic",
            "test_target_url": "http://localhost:29200"
        }

        self.valid_response_body = {
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
                                "title": {"type": "text"}
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
                                "contents": {"type": "text"}
                            }
                        }
                    }
                }
            ],
            "transform_logic": "Generated Python transformation logic",
            "validation_report": [
                "Validation successful log 1",
                "Validation successful log 2"
            ],
            "validation_outcome": "PASSED"
        }

    @patch("transform_api.views.TransformsIndexTestView._perform_test")
    def test_post_happy_path(self, mock_perform_test):
        # Mock the validation result
        mock_validation_report = MagicMock()
        mock_validation_report.task.output = self.valid_response_body["output_shape"]
        mock_validation_report.report_entries = self.valid_response_body["validation_report"]
        mock_validation_report.passed = True
        mock_validation_report.task.transform.to_file_format.return_value = self.valid_response_body["transform_logic"]
        mock_perform_test.return_value = mock_validation_report

        # Make the request
        response = self.client.post(self.url, self.valid_request_body, format="json")

        # Assertions
        self.assertEqual(response.status_code, status.HTTP_200_OK)
        self.assertEqual(response.json(), self.valid_response_body)
        mock_perform_test.assert_called_once()

    def test_post_invalid_request_body(self):
        # Incomplete request body
        invalid_request_body = {"transform_language": "Python"}

        # Make the request
        response = self.client.post(self.url, invalid_request_body, format="json")

        # Assertions
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertIn("input_shape", response.json())

    @patch("transform_api.views.TransformsIndexTestView._perform_test")
    def test_post_inaccessible_target_cluster(self, mock_perform_test):
        # Mock the `_perform_test` method to raise `TestTargetInnaccessibleError`
        mock_perform_test.side_effect = TestTargetInnaccessibleError("Cluster not accessible")

        # Make the request
        response = self.client.post(self.url, self.valid_request_body, format="json")

        # Assertions
        self.assertEqual(response.status_code, status.HTTP_400_BAD_REQUEST)
        self.assertEqual(response.json(), {"error": "Cluster not accessible"})
        mock_perform_test.assert_called_once()

    @patch("transform_api.views.TransformsIndexTestView._perform_test")
    def test_post_general_testing_failure(self, mock_perform_test):
        # Mock the `_perform_test` method to raise a general exception
        mock_perform_test.side_effect = RuntimeError("General failure")

        # Make the request
        response = self.client.post(self.url, self.valid_request_body, format="json")

        # Assertions
        self.assertEqual(response.status_code, status.HTTP_500_INTERNAL_SERVER_ERROR)
        self.assertEqual(response.json(), {"error": "General failure"})
        mock_perform_test.assert_called_once()

    @patch("transform_api.views.TransformsIndexTestResponseSerializer")
    @patch("transform_api.views.TransformsIndexTestView._perform_test")
    def test_post_invalid_response(self, mock_perform_test, mock_response_serializer):
        # Mock the validation result
        mock_validation_report = MagicMock()
        mock_validation_report.task.output = self.valid_response_body["output_shape"]
        mock_validation_report.report_entries = self.valid_response_body["validation_report"]
        mock_validation_report.passed = True
        mock_validation_report.task.transform.to_file_format.return_value = self.valid_response_body["transform_logic"]
        mock_perform_test.return_value = mock_validation_report

        # Mock the serializer behavior
        mock_serializer_instance = mock_response_serializer.return_value
        mock_serializer_instance.is_valid.return_value = False
        mock_serializer_instance.errors = {"output_shape": ["Invalid format"]}

        # Make the request
        response = self.client.post(self.url, self.valid_request_body, format="json")

        # Assertions
        self.assertEqual(response.status_code, status.HTTP_500_INTERNAL_SERVER_ERROR)
        mock_perform_test.assert_called_once()
        mock_serializer_instance.is_valid.assert_called_once()
        self.assertEqual(mock_serializer_instance.errors, {"output_shape": ["Invalid format"]})
