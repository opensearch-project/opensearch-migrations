import copy
import pickle
import unittest
from unittest.mock import patch, MagicMock, ANY

import metadata_migration
from index_doc_count import IndexDocCount
from metadata_migration_params import MetadataMigrationParams
from tests import test_constants


class TestMetadataMigration(unittest.TestCase):
    # Run before each test
    def setUp(self) -> None:
        with open(test_constants.PIPELINE_CONFIG_PICKLE_FILE_PATH, "rb") as f:
            self.loaded_pipeline_config = pickle.load(f)

    def test_get_index_differences_empty(self):
        # Base case should return an empty list
        result_tuple = metadata_migration.get_index_differences(dict(), dict())
        # Invariant
        self.assertEqual(3, len(result_tuple))
        # All diffs should be empty
        self.assertEqual(set(), result_tuple[0])
        self.assertEqual(set(), result_tuple[1])
        self.assertEqual(set(), result_tuple[2])

    def test_get_index_differences_empty_target(self):
        result_tuple = metadata_migration.get_index_differences(test_constants.BASE_INDICES_DATA, dict())
        # Invariant
        self.assertEqual(3, len(result_tuple))
        # No conflicts or identical indices
        self.assertEqual(set(), result_tuple[1])
        self.assertEqual(set(), result_tuple[2])
        # Indices-to-create
        self.assertEqual(3, len(result_tuple[0]))
        self.assertTrue(test_constants.INDEX1_NAME in result_tuple[0])
        self.assertTrue(test_constants.INDEX2_NAME in result_tuple[0])
        self.assertTrue(test_constants.INDEX3_NAME in result_tuple[0])

    def test_get_index_differences_identical_index(self):
        test_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        del test_data[test_constants.INDEX2_NAME]
        del test_data[test_constants.INDEX3_NAME]
        result_tuple = metadata_migration.get_index_differences(test_data, test_data)
        # Invariant
        self.assertEqual(3, len(result_tuple))
        # No indices to move, or conflicts
        self.assertEqual(set(), result_tuple[0])
        self.assertEqual(set(), result_tuple[2])
        # Identical indices
        self.assertEqual(1, len(result_tuple[1]))
        self.assertTrue(test_constants.INDEX1_NAME in result_tuple[1])

    def test_get_index_differences_settings_conflict(self):
        test_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        # Set up conflict in settings
        index_settings = test_data[test_constants.INDEX2_NAME][test_constants.SETTINGS_KEY]
        index_settings[test_constants.INDEX_KEY][test_constants.NUM_REPLICAS_SETTING] += 1
        result_tuple = metadata_migration.get_index_differences(test_constants.BASE_INDICES_DATA, test_data)
        # Invariant
        self.assertEqual(3, len(result_tuple))
        # No indices to move
        self.assertEqual(set(), result_tuple[0])
        # Identical indices
        self.assertEqual(2, len(result_tuple[1]))
        self.assertTrue(test_constants.INDEX1_NAME in result_tuple[1])
        self.assertTrue(test_constants.INDEX3_NAME in result_tuple[1])
        # Conflicting indices
        self.assertEqual(1, len(result_tuple[2]))
        self.assertTrue(test_constants.INDEX2_NAME in result_tuple[2])

    def test_get_index_differences_mappings_conflict(self):
        test_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        # Set up conflict in mappings
        test_data[test_constants.INDEX3_NAME][test_constants.MAPPINGS_KEY] = {}
        result_tuple = metadata_migration.get_index_differences(test_constants.BASE_INDICES_DATA, test_data)
        # Invariant
        self.assertEqual(3, len(result_tuple))
        # No indices to move
        self.assertEqual(set(), result_tuple[0])
        # Identical indices
        self.assertEqual(2, len(result_tuple[1]))
        self.assertTrue(test_constants.INDEX1_NAME in result_tuple[1])
        self.assertTrue(test_constants.INDEX2_NAME in result_tuple[1])
        # Conflicting indices
        self.assertEqual(1, len(result_tuple[2]))
        self.assertTrue(test_constants.INDEX3_NAME in result_tuple[2])

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
        index_exact_match = test_constants.INDEX1_NAME
        # Set up expected arguments to mocks so we can verify
        expected_create_payload = {index_to_create: test_constants.BASE_INDICES_DATA[index_to_create]}
        # Print report accepts a tuple. The elements of the tuple
        # are in the order: to-create, exact-match, conflicts
        expected_diff = {index_to_create}, {index_exact_match}, {index_with_conflict}
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
        mock_print_report.assert_called_once_with(expected_diff, 1)
        mock_write_output.assert_not_called()

    @patch('index_operations.doc_count')
    @patch('metadata_migration.print_report')
    @patch('metadata_migration.write_output')
    @patch('index_operations.fetch_all_indices')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_run_dryrun(self, mock_fetch_indices: MagicMock, mock_write_output: MagicMock,
                        mock_print_report: MagicMock, mock_doc_count: MagicMock):
        index_to_create = test_constants.INDEX1_NAME
        mock_doc_count.return_value = IndexDocCount(1, dict())
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
        self.assertEqual({index_to_create}, test_result.created_indices)
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
        self.assertEqual(0, len(test_result.created_indices))


if __name__ == '__main__':
    unittest.main()
