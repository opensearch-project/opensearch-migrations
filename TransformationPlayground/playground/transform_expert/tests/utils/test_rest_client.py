import requests

from django.test import TestCase
from unittest.mock import patch, MagicMock

from transform_expert.utils.rest_client import RESTClient, ConnectionDetails


class RESTClientTestCase(TestCase):
    def setUp(self):
        self.connection_details = ConnectionDetails(base_url="http://api.example.com")
        self.client = RESTClient(connection_details=self.connection_details)

    @patch("requests.get")
    def test_get_happy_path(self, mock_get):
        # Mock the GET response
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"key": "value"}
        mock_get.return_value = mock_response

        response = self.client.get("test-endpoint")

        # Assertions
        mock_get.assert_called_once_with("http://api.example.com/test-endpoint")
        self.assertEqual(response, {"key": "value"})

    @patch("requests.get")
    def test_get_error_path(self, mock_get):
        # Mock a GET response with an error
        mock_response = MagicMock()
        mock_response.raise_for_status.side_effect = requests.HTTPError("Not Found")
        mock_get.return_value = mock_response

        with self.assertRaises(requests.HTTPError):
            self.client.get("test-endpoint")

        # Assertions
        mock_get.assert_called_once_with("http://api.example.com/test-endpoint")

    @patch("requests.put")
    def test_put_happy_path(self, mock_put):
        # Mock the PUT response
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"key": "value"}
        mock_put.return_value = mock_response

        response = self.client.put("test-endpoint", data={"data_key": "data_value"})

        # Assertions
        mock_put.assert_called_once_with(
            "http://api.example.com/test-endpoint", json={"data_key": "data_value"}
        )
        self.assertEqual(response, {"key": "value"})

    @patch("requests.put")
    def test_put_error_path(self, mock_put):
        # Mock a PUT response with an error
        mock_response = MagicMock()
        mock_response.raise_for_status.side_effect = requests.HTTPError("Server Error")
        mock_put.return_value = mock_response

        with self.assertRaises(requests.HTTPError):
            self.client.put("test-endpoint", data={"data_key": "data_value"})

        # Assertions
        mock_put.assert_called_once_with(
            "http://api.example.com/test-endpoint", json={"data_key": "data_value"}
        )

    @patch("requests.post")
    def test_post_happy_path(self, mock_post):
        # Mock the POST response
        mock_response = MagicMock()
        mock_response.status_code = 201
        mock_response.json.return_value = {"key": "value"}
        mock_post.return_value = mock_response

        response = self.client.post("test-endpoint", data={"data_key": "data_value"})

        # Assertions
        mock_post.assert_called_once_with(
            "http://api.example.com/test-endpoint", json={"data_key": "data_value"}
        )
        self.assertEqual(response, {"key": "value"})

    @patch("requests.post")
    def test_post_error_path(self, mock_post):
        # Mock a POST response with an error
        mock_response = MagicMock()
        mock_response.raise_for_status.side_effect = requests.HTTPError("Bad Request")
        mock_post.return_value = mock_response

        with self.assertRaises(requests.HTTPError):
            self.client.post("test-endpoint", data={"data_key": "data_value"})

        # Assertions
        mock_post.assert_called_once_with(
            "http://api.example.com/test-endpoint", json={"data_key": "data_value"}
        )

    @patch("requests.delete")
    def test_delete_happy_path(self, mock_delete):
        # Mock the DELETE response
        mock_response = MagicMock()
        mock_response.status_code = 204
        mock_response.json.return_value = {}
        mock_delete.return_value = mock_response

        response = self.client.delete("test-endpoint")

        # Assertions
        mock_delete.assert_called_once_with("http://api.example.com/test-endpoint")
        self.assertEqual(response, {})

    @patch("requests.delete")
    def test_delete_error_path(self, mock_delete):
        # Mock a DELETE response with an error
        mock_response = MagicMock()
        mock_response.raise_for_status.side_effect = requests.HTTPError("Unauthorized")
        mock_delete.return_value = mock_response

        with self.assertRaises(requests.HTTPError):
            self.client.delete("test-endpoint")

        # Assertions
        mock_delete.assert_called_once_with("http://api.example.com/test-endpoint")
