from unittest.mock import MagicMock

from django.test import TestCase

from transform_expert.feedback import test_target_connection, TestTargetInnaccessibleError
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

        

