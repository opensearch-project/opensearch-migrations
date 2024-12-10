from django.test import TestCase
from unittest.mock import patch
from requests import HTTPError, ConnectionError
from transform_expert.utils.opensearch_client import OpenSearchClient
from transform_expert.utils.rest_client import RESTClient, ConnectionDetails


class OpenSearchClientTestCase(TestCase):
    def setUp(self):
        # Initialize RESTClient and OpenSearchClient
        self.connection_details = ConnectionDetails(base_url="http://opensearch.example.com")
        self.rest_client = RESTClient(connection_details=self.connection_details)
        self.os_client = OpenSearchClient(rest_client=self.rest_client)

    @patch("transform_expert.utils.rest_client.RESTClient.get")
    def test_is_accessible_happy_path(self, mock_get):
        # Mock a successful GET request
        mock_get.return_value = {}

        result = self.os_client.is_accessible()

        # Assertions
        mock_get.assert_called_once_with("")
        self.assertTrue(result)

    @patch("transform_expert.utils.rest_client.RESTClient.get")
    def test_is_accessible_error_path(self, mock_get):
        # Mock HTTPError
        mock_get.side_effect = HTTPError("Not Found")

        result = self.os_client.is_accessible()

        # Assertions
        mock_get.assert_called_once_with("")
        self.assertFalse(result)

        # Mock ConnectionError
        mock_get.reset_mock()
        mock_get.side_effect = ConnectionError("Connection failed")

        result = self.os_client.is_accessible()

        # Assertions
        mock_get.assert_called_once_with("")
        self.assertFalse(result)

    @patch("transform_expert.utils.rest_client.RESTClient.put")
    def test_create_index_happy_path(self, mock_put):
        # Mock a successful PUT request
        mock_response = {"acknowledged": True}
        mock_put.return_value = mock_response

        index_name = "test-index"
        settings = {"settings": {"number_of_shards": 1}}
        result = self.os_client.create_index(index_name, settings)

        # Assertions
        mock_put.assert_called_once_with("test-index", data=settings)
        self.assertEqual(result, mock_response)

    @patch("transform_expert.utils.rest_client.RESTClient.get")
    def test_describe_index_happy_path(self, mock_get):
        # Mock a successful GET request
        mock_response = {"index": "test-index", "settings": {}}
        mock_get.return_value = mock_response

        index_name = "test-index"
        result = self.os_client.describe_index(index_name)

        # Assertions
        mock_get.assert_called_once_with("test-index")
        self.assertEqual(result, mock_response)

    @patch("transform_expert.utils.rest_client.RESTClient.put")
    def test_update_index_happy_path(self, mock_put):
        # Mock a successful PUT request
        mock_response = {"acknowledged": True}
        mock_put.return_value = mock_response

        index_name = "test-index"
        settings = {"settings": {"number_of_replicas": 2}}
        result = self.os_client.update_index(index_name, settings)

        # Assertions
        mock_put.assert_called_once_with("test-index/_settings", data=settings)
        self.assertEqual(result, mock_response)

    @patch("transform_expert.utils.rest_client.RESTClient.delete")
    def test_delete_index_happy_path(self, mock_delete):
        # Mock a successful DELETE request
        mock_response = {"acknowledged": True}
        mock_delete.return_value = mock_response

        index_name = "test-index"
        result = self.os_client.delete_index(index_name)

        # Assertions
        mock_delete.assert_called_once_with("test-index")
        self.assertEqual(result, mock_response)
