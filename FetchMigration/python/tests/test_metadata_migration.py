#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

import copy
import logging
import pickle
import unittest
from unittest.mock import patch, MagicMock, ANY

import requests

import metadata_migration
from index_doc_count import IndexDocCount
from metadata_migration_params import MetadataMigrationParams
from tests import test_constants


class TestMetadataMigration(unittest.TestCase):
    # Run before each test
    def setUp(self) -> None:
        logging.disable(logging.CRITICAL)
        with open(test_constants.PIPELINE_CONFIG_PICKLE_FILE_PATH, "rb") as f:
            self.loaded_pipeline_config = pickle.load(f)

    def tearDown(self) -> None:
        logging.disable(logging.NOTSET)

    @patch('index_operations.doc_count')
    @patch('metadata_migration.write_output')
    @patch('metadata_migration.print_report')
    @patch('index_operations.create_indices')
    @patch('index_operations.fetch_all_indices')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_run_report(self, mock_fetch_indices: MagicMock, mock_create_indices: MagicMock,
                        mock_print_report: MagicMock, mock_write_output: MagicMock, mock_doc_count: MagicMock):
        mock_doc_count.return_value = IndexDocCount(1, dict())
        index_to_create = test_constants.INDEX3_NAME
        index_with_conflict = test_constants.INDEX2_NAME
        # Set up expected arguments to mocks so we can verify
        expected_create_payload = {index_to_create: test_constants.BASE_INDICES_DATA[index_to_create]}
        # Create mock data for indices on target
        target_indices_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        del target_indices_data[index_to_create]
        # Index with conflict
        index_settings = target_indices_data[index_with_conflict][test_constants.SETTINGS_KEY]
        index_settings[test_constants.INDEX_KEY][test_constants.NUM_REPLICAS_SETTING] += 1
        # Fetch indices is called first for source, then for target
        mock_fetch_indices.side_effect = [test_constants.BASE_INDICES_DATA, target_indices_data]
        test_input = MetadataMigrationParams(test_constants.PIPELINE_CONFIG_RAW_FILE_PATH, report=True)
        metadata_migration.run(test_input)
        mock_create_indices.assert_called_once_with(expected_create_payload, ANY)
        mock_doc_count.assert_called()
        mock_print_report.assert_called_once_with(ANY, 1)
        mock_write_output.assert_not_called()

    @patch('index_operations.doc_count')
    @patch('metadata_migration.print_report')
    @patch('metadata_migration.write_output')
    @patch('index_operations.fetch_all_indices')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_run_dryrun(self, mock_fetch_indices: MagicMock, mock_write_output: MagicMock,
                        mock_print_report: MagicMock, mock_doc_count: MagicMock):
        index_to_migrate = test_constants.INDEX1_NAME
        expected_output_path = "dummy"
        test_doc_counts = {test_constants.INDEX2_NAME: 2, test_constants.INDEX3_NAME: 3}
        # Doc counts are first fetched for the target cluster,
        # then then source cluster is queried only for identical, empty indices
        mock_doc_count.side_effect = [IndexDocCount(5, test_doc_counts),
                                      IndexDocCount(1, {index_to_migrate: 1})]
        mock_fetch_indices.return_value = test_constants.BASE_INDICES_DATA
        test_input = MetadataMigrationParams(test_constants.PIPELINE_CONFIG_RAW_FILE_PATH, expected_output_path,
                                             dryrun=True)
        test_result = metadata_migration.run(test_input)
        self.assertEqual(1, test_result.target_doc_count)
        self.assertEqual({index_to_migrate}, test_result.migration_indices)
        mock_write_output.assert_called_once_with(self.loaded_pipeline_config, {index_to_migrate}, expected_output_path)
        mock_doc_count.assert_called()
        # Report should not be printed
        mock_print_report.assert_not_called()

    @patch('index_operations.doc_count')
    @patch('metadata_migration.print_report')
    @patch('metadata_migration.write_output')
    @patch('index_operations.fetch_all_indices')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_identical_empty_index(self, mock_fetch_indices: MagicMock, mock_write_output: MagicMock,
                                   mock_print_report: MagicMock, mock_doc_count: MagicMock):
        test_index_doc_counts = {test_constants.INDEX2_NAME: 2,
                                 test_constants.INDEX3_NAME: 3}
        mock_doc_count.return_value = IndexDocCount(1, test_index_doc_counts)
        index_to_create = test_constants.INDEX1_NAME
        expected_output_path = "dummy"
        # Create mock data for indices on target
        target_indices_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        del target_indices_data[index_to_create]
        # Fetch indices is called first for source, then for target
        mock_fetch_indices.side_effect = [test_constants.BASE_INDICES_DATA, target_indices_data]
        test_input = MetadataMigrationParams(test_constants.PIPELINE_CONFIG_RAW_FILE_PATH, expected_output_path,
                                             dryrun=True)
        test_result = metadata_migration.run(test_input)
        self.assertEqual(mock_doc_count.return_value.total, test_result.target_doc_count)
        self.assertEqual({index_to_create}, test_result.migration_indices)
        mock_write_output.assert_called_once_with(self.loaded_pipeline_config, {index_to_create}, expected_output_path)
        mock_doc_count.assert_called()
        # Report should not be printed
        mock_print_report.assert_not_called()

    @patch('yaml.dump')
    def test_write_output(self, mock_dump: MagicMock):
        expected_output_path = "dummy"
        index_to_create = "good_index"
        # Set up expected data that will be dumped
        expected_output_data = copy.deepcopy(self.loaded_pipeline_config)
        expected_output_data['test-pipeline-input']['source'] = {
            'opensearch': {
                'indices': {
                    'exclude': [
                        {'index_name_regex': 'bad_index'}
                    ],
                    'include': [
                        {'index_name_regex': index_to_create}
                    ]
                }
            }
        }
        # Set up test input
        test_input = copy.deepcopy(expected_output_data)
        del test_input['test-pipeline-input']['source']['opensearch']['indices']['include']
        # Call method under test
        with patch('builtins.open') as mock_open:
            metadata_migration.write_output(test_input, {index_to_create}, expected_output_path)
            mock_open.assert_called_once_with(expected_output_path, 'w')
            mock_dump.assert_called_once_with(expected_output_data, ANY)

    def test_missing_output_file_non_report(self):
        test_input = MetadataMigrationParams(test_constants.PIPELINE_CONFIG_RAW_FILE_PATH)
        self.assertRaises(ValueError, metadata_migration.run, test_input)

    @patch('index_operations.fetch_all_indices')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_no_indices_in_source(self, mock_fetch_indices: MagicMock):
        mock_fetch_indices.return_value = {}
        test_input = MetadataMigrationParams(test_constants.PIPELINE_CONFIG_RAW_FILE_PATH, "dummy")
        test_result = metadata_migration.run(test_input)
        mock_fetch_indices.assert_called_once()
        self.assertEqual(0, test_result.target_doc_count)
        self.assertEqual(0, len(test_result.migration_indices))

    @patch('metadata_migration.write_output')
    @patch('index_operations.doc_count')
    @patch('index_operations.create_indices')
    @patch('index_operations.fetch_all_indices')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_failed_indices(self, mock_fetch_indices: MagicMock, mock_create_indices: MagicMock,
                            mock_doc_count: MagicMock, mock_write_output: MagicMock):
        mock_doc_count.return_value = IndexDocCount(1, dict())
        # Setup failed indices
        test_failed_indices_result = {
            test_constants.INDEX1_NAME: requests.Timeout(),
            test_constants.INDEX2_NAME: requests.ConnectionError(),
            test_constants.INDEX3_NAME: requests.Timeout()
        }
        mock_create_indices.return_value = test_failed_indices_result
        # Fetch indices is called first for source, then for target
        mock_fetch_indices.side_effect = [test_constants.BASE_INDICES_DATA, {}]
        test_input = MetadataMigrationParams(test_constants.PIPELINE_CONFIG_RAW_FILE_PATH, "dummy")
        self.assertRaises(RuntimeError, metadata_migration.run, test_input)
        mock_create_indices.assert_called_once_with(test_constants.BASE_INDICES_DATA, ANY)


if __name__ == '__main__':
    unittest.main()
