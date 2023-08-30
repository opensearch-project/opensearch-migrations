import unittest
from unittest.mock import patch, MagicMock, PropertyMock

import requests
import responses
from prometheus_client.parser import text_string_to_metric_families

import migration_monitor
from endpoint_info import EndpointInfo

# Constants
from migration_monitor_params import MigrationMonitorParams

TEST_ENDPOINT = "test"
TEST_AUTH = ("user", "pass")
TEST_FLAG = False
TEST_METRIC_NAME = "test_metric"
TEST_METRIC_VALUE = 123.45
TEST_PROMETHEUS_METRIC_STRING = "# HELP " + TEST_METRIC_NAME + " Unit Test Metric\n"\
    + "# TYPE " + TEST_METRIC_NAME + " gauge\n" \
    + TEST_METRIC_NAME + "{serviceName=\"unittest\",} " + str(TEST_METRIC_VALUE)


class TestMigrationMonitor(unittest.TestCase):
    @patch('requests.post')
    def test_shutdown(self, mock_post: MagicMock):
        expected_shutdown_url = TEST_ENDPOINT + "/shutdown"
        test_endpoint = EndpointInfo(TEST_ENDPOINT, TEST_AUTH, TEST_FLAG)
        migration_monitor.shutdown_pipeline(test_endpoint)
        mock_post.assert_called_once_with(expected_shutdown_url, auth=TEST_AUTH, verify=TEST_FLAG)

    @patch('requests.get')
    def test_fetch_prometheus_metrics(self, mock_get: MagicMock):
        expected_url = TEST_ENDPOINT + "/metrics/prometheus"
        # Set up GET response
        mock_response = MagicMock()
        # content is a property
        mock_content = PropertyMock(return_value=bytes(TEST_PROMETHEUS_METRIC_STRING, "utf-8"))
        type(mock_response).content = mock_content
        mock_get.return_value = mock_response
        # Test fetch
        raw_metrics_list = migration_monitor.fetch_prometheus_metrics(EndpointInfo(TEST_ENDPOINT))
        mock_get.assert_called_once_with(expected_url, auth=None, verify=True)
        self.assertEqual(1, len(raw_metrics_list))
        test_metric = raw_metrics_list[0]
        self.assertEqual(TEST_METRIC_NAME, test_metric.name)
        self.assertTrue(len(test_metric.type) > 0)
        self.assertTrue(len(test_metric.documentation) > 0)
        self.assertEqual(1, len(test_metric.samples))
        test_sample = test_metric.samples[0]
        self.assertEqual(TEST_METRIC_NAME, test_sample.name)
        self.assertEqual(TEST_METRIC_VALUE, test_sample.value)
        self.assertTrue(len(test_sample.labels) > 0)

    @responses.activate
    def test_fetch_prometheus_metrics_failure(self):
        # Set up expected GET call with a mock exception
        expected_url = TEST_ENDPOINT + "/metrics/prometheus"
        responses.get(expected_url, body=requests.Timeout())
        # Test fetch
        result = migration_monitor.fetch_prometheus_metrics(EndpointInfo(TEST_ENDPOINT))
        self.assertIsNone(result)

    def test_get_metric_value(self):
        # Return value is an int
        expected_val = int(TEST_METRIC_VALUE)
        test_input = list(text_string_to_metric_families(TEST_PROMETHEUS_METRIC_STRING))
        # Should fetch by suffix
        val = migration_monitor.get_metric_value(test_input, "metric")
        self.assertEqual(expected_val, val)
        # No matching metric returns None
        val = migration_monitor.get_metric_value(test_input, "invalid")
        self.assertEqual(None, val)

    @patch('migration_monitor.shutdown_pipeline')
    @patch('time.sleep')
    @patch('migration_monitor.check_if_complete')
    @patch('migration_monitor.get_metric_value')
    @patch('migration_monitor.fetch_prometheus_metrics')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_run(self, mock_fetch: MagicMock, mock_get: MagicMock, mock_check: MagicMock, mock_sleep: MagicMock,
                 mock_shut: MagicMock):
        # The param values don't matter since we've mocked the check method
        test_input = MigrationMonitorParams(1, "test")
        mock_get.return_value = None
        # Check will first fail, then pass
        mock_check.side_effect = [False, True]
        # Run test method
        wait_time = 3
        migration_monitor.run(test_input, wait_time)
        # Test that fetch was called with the expected EndpointInfo
        expected_endpoint_info = EndpointInfo(test_input.dp_endpoint, ('admin', 'admin'), False)
        self.assertEqual(2, mock_fetch.call_count)
        mock_fetch.assert_called_with(expected_endpoint_info)
        # We expect one wait cycle
        mock_sleep.assert_called_once_with(wait_time)
        mock_shut.assert_called_once_with(expected_endpoint_info)

    @patch('migration_monitor.shutdown_pipeline')
    @patch('time.sleep')
    @patch('migration_monitor.check_if_complete')
    @patch('migration_monitor.get_metric_value')
    @patch('migration_monitor.fetch_prometheus_metrics')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_run_with_fetch_failure(self, mock_fetch: MagicMock, mock_get: MagicMock, mock_check: MagicMock,
                                    mock_sleep: MagicMock, mock_shut: MagicMock):
        # The param values don't matter since we've mocked the check method
        test_input = MigrationMonitorParams(1, "test")
        mock_get.return_value = None
        mock_check.return_value = True
        # Fetch call will first fail, then succeed
        mock_fetch.side_effect = [None, MagicMock()]
        # Run test method
        wait_time = 3
        migration_monitor.run(test_input, wait_time)
        # Test that fetch was called with the expected EndpointInfo
        expected_endpoint_info = EndpointInfo(test_input.dp_endpoint, ('admin', 'admin'), False)
        self.assertEqual(2, mock_fetch.call_count)
        mock_fetch.assert_called_with(expected_endpoint_info)
        # We expect one wait cycle
        mock_sleep.assert_called_once_with(wait_time)
        mock_shut.assert_called_once_with(expected_endpoint_info)

    def test_check_if_complete(self):
        # If any of the optional values are missing, we are not complete
        self.assertFalse(migration_monitor.check_if_complete(None, 0, 1, 0, 2))
        self.assertFalse(migration_monitor.check_if_complete(2, None, 1, 0, 2))
        self.assertFalse(migration_monitor.check_if_complete(2, 0, None, 0, 2))
        # Target count not reached
        self.assertFalse(migration_monitor.check_if_complete(1, None, None, 0, 2))
        # Target count reached, but has records in flight
        self.assertFalse(migration_monitor.check_if_complete(2, 1, None, 0, 2))
        # Target count reached, no records in flight, but no prev no_part_count
        self.assertFalse(migration_monitor.check_if_complete(2, 0, 1, 0, 2))
        # Terminal state
        self.assertTrue(migration_monitor.check_if_complete(2, 0, 2, 1, 2))


if __name__ == '__main__':
    unittest.main()
