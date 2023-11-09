import logging
import subprocess
import unittest
from unittest.mock import patch, MagicMock, PropertyMock, ANY

import requests
import responses
from prometheus_client.parser import text_string_to_metric_families

import migration_monitor
from endpoint_info import EndpointInfo
from migration_monitor_params import MigrationMonitorParams

# Constants
TEST_ENDPOINT = "test"
TEST_AUTH = ("user", "pass")
TEST_FLAG = False
TEST_METRIC_NAME = "test_metric"
TEST_METRIC_VALUE = 123.45
TEST_PROMETHEUS_METRIC_STRING = "# HELP " + TEST_METRIC_NAME + " Unit Test Metric\n"\
    + "# TYPE " + TEST_METRIC_NAME + " gauge\n" \
    + TEST_METRIC_NAME + "{serviceName=\"unittest\",} " + str(TEST_METRIC_VALUE)


class TestMigrationMonitor(unittest.TestCase):

    def setUp(self) -> None:
        logging.disable(logging.CRITICAL)

    def tearDown(self) -> None:
        logging.disable(logging.NOTSET)

    @patch('requests.post')
    def test_shutdown_pipeline(self, mock_post: MagicMock):
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
        self.assertIsNone(val)

    @patch('migration_monitor.fetch_prometheus_metrics')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_check_progress_metrics_failure(self, mock_fetch: MagicMock):
        mock_progress = MagicMock()
        # On API failure, check returns None
        mock_fetch.return_value = None
        # Endpoint info doesn't matter
        return_value = migration_monitor.check_and_log_progress(MagicMock(), mock_progress)
        # Same progress object is returned
        self.assertEqual(mock_progress, return_value)
        # API metric failure is recorded
        mock_progress.record_metric_api_failure.assert_called_once()

    @patch('migration_monitor.get_metric_value')
    @patch('migration_monitor.fetch_prometheus_metrics')
    def test_check_progress_missing_success_docs_metric(self, mock_fetch: MagicMock, mock_get_metric: MagicMock):
        # Fetch return value is not None, but get-metric returns None
        mock_fetch.return_value = MagicMock()
        mock_get_metric.return_value = None
        mock_progress = MagicMock()
        # Endpoint info doesn't matter
        return_value = migration_monitor.check_and_log_progress(MagicMock(), mock_progress)
        # Same progress object is returned
        self.assertEqual(mock_progress, return_value)
        # API failure metric is reset is recorded
        mock_progress.reset_metric_api_failure.assert_called_once()
        # 3 metric values are read
        self.assertEqual(3, mock_get_metric.call_count)
        # Success doc failure metric is recorded
        mock_progress.record_success_doc_value_failure.assert_called_once()

    @patch('migration_monitor.get_metric_value')
    @patch('migration_monitor.fetch_prometheus_metrics')
    def test_check_and_log_progress(self, mock_fetch: MagicMock, mock_get_metric: MagicMock):
        # Fetch return value is not None
        mock_fetch.return_value = MagicMock()
        # Get metric return value is not None
        expected_value: int = 10
        mock_get_metric.return_value = expected_value
        mock_progress = MagicMock()
        # Set up target-doc-count
        mock_progress.target_doc_count.return_value = expected_value
        # Endpoint info doesn't matter
        return_value = migration_monitor.check_and_log_progress(MagicMock(), mock_progress)
        # Same progress object is returned
        self.assertEqual(mock_progress, return_value)
        # 3 metric values are read
        self.assertEqual(3, mock_get_metric.call_count)
        # Success doc count is updated as expected
        mock_progress.update_success_doc_count.assert_called_once_with(expected_value)
        # All-docs-migrated check is invoked
        mock_progress.all_docs_migrated.assert_called_once()

    @patch('migration_monitor.shutdown_process')
    @patch('migration_monitor.shutdown_pipeline')
    @patch('time.sleep')
    @patch('migration_monitor.check_and_log_progress')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_monitor_non_local(self, mock_check: MagicMock, mock_sleep: MagicMock, mock_shut_dp: MagicMock,
                               mock_shut_proc: MagicMock):
        # The param values don't matter since we've mocked the check method
        test_input = MigrationMonitorParams(1, "test")
        mock_progress = MagicMock()
        mock_progress.is_in_terminal_state.return_value = True
        mock_check.return_value = mock_progress
        # Run test method
        wait_time = 3
        migration_monitor.run(test_input, None, wait_time)
        # Test that fetch was called with the expected EndpointInfo
        expected_endpoint_info = EndpointInfo(test_input.data_prepper_endpoint)
        mock_sleep.assert_called_with(wait_time)
        mock_shut_dp.assert_called_once_with(expected_endpoint_info)
        mock_shut_proc.assert_not_called()

    @patch('migration_monitor.shutdown_process')
    @patch('migration_monitor.shutdown_pipeline')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_monitor_local_process_exit(self, mock_shut_dp: MagicMock, mock_shut_proc: MagicMock):
        # The param values don't matter since we've mocked the check method
        test_input = MigrationMonitorParams(1, "test")
        mock_subprocess = MagicMock()
        # Simulate an exited subprocess
        expected_return_code: int = 1
        mock_subprocess.returncode = expected_return_code
        # Run test method
        return_code = migration_monitor.run(test_input, mock_subprocess)
        self.assertEqual(expected_return_code, return_code)
        mock_shut_dp.assert_not_called()
        mock_shut_proc.assert_not_called()

    @patch('migration_monitor.shutdown_process')
    @patch('migration_monitor.shutdown_pipeline')
    @patch('migration_monitor.is_process_alive')
    @patch('migration_monitor.check_and_log_progress')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_monitor_local_migration_complete(self, mock_check: MagicMock, mock_is_alive: MagicMock,
                                              mock_shut_dp: MagicMock, mock_shut_proc: MagicMock):
        # The param values don't matter since we've mocked the check method
        test_input = MigrationMonitorParams(1, "test")
        # Simulate a successful migration
        mock_progress = MagicMock()
        mock_progress.is_in_terminal_state.side_effect = [False, True]
        mock_progress.is_migration_complete_success.return_value = True
        mock_check.return_value = mock_progress
        # Sequence of expected return values for a process that terminates successfully
        mock_is_alive.side_effect = [True, True, False, False]
        mock_subprocess = MagicMock()
        expected_return_code: int = 0
        mock_subprocess.returncode = expected_return_code
        # Simulate timeout on wait
        mock_subprocess.wait.side_effect = [subprocess.TimeoutExpired("test", 1)]
        # Run test method
        actual_return_code = migration_monitor.run(test_input, mock_subprocess)
        self.assertEqual(expected_return_code, actual_return_code)
        expected_endpoint_info = EndpointInfo(test_input.data_prepper_endpoint)
        mock_check.assert_called_once_with(expected_endpoint_info, ANY)
        mock_shut_dp.assert_called_once_with(expected_endpoint_info)
        mock_shut_proc.assert_not_called()

    @patch('migration_monitor.shutdown_process')
    @patch('migration_monitor.shutdown_pipeline')
    @patch('migration_monitor.check_and_log_progress')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_monitor_local_shutdown_process(self, mock_check: MagicMock, mock_shut_dp: MagicMock,
                                            mock_shut_proc: MagicMock):
        # The param values don't matter since we've mocked the check method
        test_input = MigrationMonitorParams(1, "test")
        # Simulate a progressing, successful migration
        mock_progress = MagicMock()
        mock_progress.is_in_terminal_state.side_effect = [False, True, True]
        mock_check.return_value = mock_progress
        mock_subprocess = MagicMock()
        # set subprocess returncode to None to simulate a zombie Data Prepper process
        mock_subprocess.returncode = None
        # Shtudown-process call return code
        expected_return_code: int = 137
        mock_shut_proc.return_value = 137
        # Run test method
        actual_return_code = migration_monitor.run(test_input, mock_subprocess)
        self.assertEqual(expected_return_code, actual_return_code)
        expected_endpoint_info = EndpointInfo(test_input.data_prepper_endpoint)
        mock_shut_dp.assert_called_once_with(expected_endpoint_info)
        mock_shut_proc.assert_called_once_with(mock_subprocess)

    def test_shutdown_process_terminate_success(self):
        proc = MagicMock()
        proc.returncode = 1
        result = migration_monitor.shutdown_process(proc)
        proc.terminate.assert_called_once()
        proc.wait.assert_called_once()
        proc.kill.assert_not_called()
        self.assertEqual(1, result)

    def test_shutdown_process_terminate_fail(self):
        proc = MagicMock()
        proc.returncode = None
        proc.wait.side_effect = [subprocess.TimeoutExpired("test", 1), None]
        result = migration_monitor.shutdown_process(proc)
        proc.terminate.assert_called_once()
        proc.wait.assert_called_once()
        proc.kill.assert_called_once()
        self.assertIsNone(result)


if __name__ == '__main__':
    unittest.main()
