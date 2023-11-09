import copy
import os
import unittest
from unittest import mock
from unittest.mock import patch, MagicMock, ANY, mock_open

import fetch_orchestrator as orchestrator
from metadata_migration_params import MetadataMigrationParams
from metadata_migration_result import MetadataMigrationResult
from migration_monitor_params import MigrationMonitorParams


class TestFetchOrchestrator(unittest.TestCase):

    @patch('migration_monitor.run')
    @patch('subprocess.Popen')
    @patch('metadata_migration.run')
    # Note that mock objects are passed bottom-up from the patch order above
    @mock.patch.dict(os.environ, {}, clear=True)
    def test_orchestrator_run(self, mock_metadata_migration: MagicMock, mock_subprocess: MagicMock,
                              mock_monitor: MagicMock):
        test_path = "test_path"
        test_file = "test_file"
        test_host = "test_host"
        # Setup mock pre-migration
        expected_metadata_migration_input = MetadataMigrationParams(test_file, test_path + "/pipelines/pipeline.yaml",
                                                                    report=True)
        test_result = MetadataMigrationResult(10, {"index1", "index2"})
        expected_monitor_input = MigrationMonitorParams(test_result.target_doc_count, test_host)
        mock_metadata_migration.return_value = test_result
        # setup subprocess return value
        mock_subprocess.return_value.returncode = 0
        # Run test
        orchestrator.run(test_path, test_file, test_host)
        mock_metadata_migration.assert_called_once_with(expected_metadata_migration_input)
        expected_dp_runnable = test_path + "/bin/data-prepper"
        mock_subprocess.assert_called_once_with(expected_dp_runnable)
        mock_monitor.assert_called_once_with(expected_monitor_input, mock_subprocess.return_value)

    @patch('migration_monitor.run')
    @patch('subprocess.Popen')
    @patch('metadata_migration.run')
    # Note that mock objects are passed bottom-up from the patch order above
    @mock.patch.dict(os.environ, {}, clear=True)
    def test_orchestrator_no_migration(self, mock_metadata_migration: MagicMock, mock_subprocess: MagicMock,
                                       mock_monitor: MagicMock):
        # Setup empty result from pre-migration
        mock_metadata_migration.return_value = MetadataMigrationResult()
        orchestrator.run("test", "test", "test")
        mock_metadata_migration.assert_called_once_with(ANY)
        # Subsequent steps should not be called
        mock_subprocess.assert_not_called()
        mock_monitor.assert_not_called()

    @patch('fetch_orchestrator.write_inline_target_host')
    @patch('metadata_migration.run')
    # Note that mock objects are passed bottom-up from the patch order above
    @mock.patch.dict(os.environ, {"INLINE_TARGET_HOST": "host"}, clear=True)
    def test_orchestrator_inline_target_host(self, mock_metadata_migration: MagicMock, mock_write_host: MagicMock):
        # For testing, return no indices to migrate
        mock_metadata_migration.return_value = MetadataMigrationResult()
        orchestrator.run("test", "test", "test")
        mock_metadata_migration.assert_called_once_with(ANY)
        mock_write_host.assert_called_once_with(ANY, "host")

    @patch('fetch_orchestrator.update_target_host')
    @patch('fetch_orchestrator.write_inline_pipeline')
    @patch('metadata_migration.run')
    # Note that mock objects are passed bottom-up from the patch order above
    @mock.patch.dict(os.environ, {"INLINE_PIPELINE": "test"}, clear=True)
    def test_orchestrator_inline_pipeline(self, mock_metadata_migration: MagicMock, mock_write_pipeline: MagicMock,
                                          mock_update_host: MagicMock):
        # For testing, return no indices to migrate
        mock_metadata_migration.return_value = MetadataMigrationResult()
        orchestrator.run("test", "test", "test")
        mock_metadata_migration.assert_called_once_with(ANY)
        mock_write_pipeline.assert_called_once_with("test")
        mock_update_host.assert_not_called()

    @patch('builtins.open', new_callable=mock_open())
    @patch('fetch_orchestrator.update_target_host')
    @patch('metadata_migration.run')
    # INLINE_PIPELINE value is base64 encoded version of {}
    @mock.patch.dict(os.environ, {"INLINE_PIPELINE": "e30K", "INLINE_TARGET_HOST": "host"}, clear=True)
    def test_orchestrator_inline_pipeline_and_host(self, mock_metadata_migration: MagicMock,
                                                   mock_update_host: MagicMock, mock_file_open: MagicMock):
        # For testing, return no indices to migrate
        mock_metadata_migration.return_value = MetadataMigrationResult()
        test_config_file = "config_file"
        orchestrator.run("test", test_config_file, "test")
        mock_metadata_migration.assert_called_once_with(ANY)
        mock_update_host.assert_called_once_with(ANY, "host")
        mock_file_open.assert_called_once_with(test_config_file, 'w')

    @patch('yaml.safe_dump')
    @patch('yaml.safe_load')
    @patch('builtins.open', new_callable=mock_open())
    # Note that mock objects are passed bottom-up from the patch order above
    def test_write_inline_target_host(self, mock_file_open: MagicMock, mock_yaml_load: MagicMock,
                                      mock_yaml_dump: MagicMock):
        mock_yaml_load.return_value = {"root": {"sink": {"opensearch": {"hosts": ["val"]}}}}
        inline_target = "host"
        expected_target_url = "https://" + inline_target
        test_values = [
            inline_target,  # base case test
            "http://" + inline_target  # tests forcing of HTTPS
        ]
        expected_pipeline = copy.deepcopy(mock_yaml_load.return_value)
        # TODO - replace with jsonpath
        expected_pipeline["root"]["sink"]["opensearch"]["hosts"] = [expected_target_url]
        for val in test_values:
            mock_file_open.reset_mock()
            mock_yaml_dump.reset_mock()
            orchestrator.write_inline_target_host("test", val)
            mock_file_open.assert_called_once_with("test", "rw")
            mock_yaml_dump.assert_called_once_with(expected_pipeline, ANY)

    def test_update_target_host_bad_config(self):
        bad_configs = [
            {},  # empty config
            {"root": {}},  # only root element
            {"root": {"source": True}},  # no sink
        ]
        for config in bad_configs:
            original = copy.deepcopy(config)
            orchestrator.update_target_host(config, "host")
            # Bad configs should result in no change
            self.assertEqual(config, original)

    def test_get_local_endpoint(self):
        # Default value for insecure flag (secure endpoint)
        self.assertEqual("https://localhost:123", orchestrator.get_local_endpoint(123))
        # insecure endpoint
        self.assertEqual("http://localhost:123", orchestrator.get_local_endpoint(123, True))


if __name__ == '__main__':
    unittest.main()
