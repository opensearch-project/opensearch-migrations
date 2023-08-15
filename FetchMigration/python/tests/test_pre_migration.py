import copy
import pickle
import random
import unittest
from typing import Optional
from unittest.mock import patch, MagicMock, ANY

import pre_migration
from pre_migration_params import PreMigrationParams
from tests import test_constants

# Constants
TEST_KEY = "test_key"
INSECURE_KEY = "insecure"
CONNECTION_KEY = "connection"
BASE_CONFIG_SECTION = {
    TEST_KEY: [{"invalid_plugin1": {"key": "val"}}, {"invalid_plugin2": {}}]
}


# Utility method to create a test plugin config
def create_plugin_config(host_list: list[str],
                         user: Optional[str] = None,
                         password: Optional[str] = None,
                         disable_auth: Optional[bool] = None) -> dict:
    config = dict()
    config["hosts"] = host_list
    if user:
        config["username"] = user
    if password:
        config["password"] = password
    if disable_auth is not None:
        config["disable_authentication"] = disable_auth
    return config


# Utility method to creat a test config section
def create_config_section(plugin_config: dict) -> dict:
    valid_plugin = dict()
    valid_plugin[random.choice(pre_migration.SUPPORTED_ENDPOINTS)] = plugin_config
    config_section = copy.deepcopy(BASE_CONFIG_SECTION)
    config_section[TEST_KEY].append(valid_plugin)
    return config_section


class TestPreMigration(unittest.TestCase):
    # Run before each test
    def setUp(self) -> None:
        with open(test_constants.PIPELINE_CONFIG_PICKLE_FILE_PATH, "rb") as f:
            self.loaded_pipeline_config = pickle.load(f)

    def test_is_insecure_default_value(self):
        self.assertFalse(pre_migration.is_insecure({}))

    def test_is_insecure_top_level_key(self):
        test_input = {"key": 123, INSECURE_KEY: True}
        self.assertTrue(pre_migration.is_insecure(test_input))

    def test_is_insecure_nested_key(self):
        test_input = {"key1": 123, CONNECTION_KEY: {"key2": "val", INSECURE_KEY: True}}
        self.assertTrue(pre_migration.is_insecure(test_input))

    def test_is_insecure_missing_nested(self):
        test_input = {"key1": 123, CONNECTION_KEY: {"key2": "val"}}
        self.assertFalse(pre_migration.is_insecure(test_input))

    def test_get_auth_returns_none(self):
        # The following inputs should not return an auth tuple:
        # - Empty input
        # - user without password
        # - password without user
        input_list = [{}, {"username": "test"}, {"password": "test"}]
        for test_input in input_list:
            self.assertIsNone(pre_migration.get_auth(test_input))

    def test_get_auth_for_valid_input(self):
        # Test valid input
        result = pre_migration.get_auth({"username": "user", "password": "pass"})
        self.assertEqual(tuple, type(result))
        self.assertEqual("user", result[0])
        self.assertEqual("pass", result[1])

    def test_get_endpoint_info(self):
        host_input = "test"
        expected_endpoint = "test/"
        test_user = "user"
        test_password = "password"
        # Simple base case
        test_config = create_plugin_config([host_input])
        result = pre_migration.get_endpoint_info(test_config)
        self.assertEqual(expected_endpoint, result.url)
        self.assertIsNone(result.auth)
        self.assertTrue(result.verify_ssl)
        # Invalid auth config
        test_config = create_plugin_config([host_input], test_user)
        result = pre_migration.get_endpoint_info(test_config)
        self.assertEqual(expected_endpoint, result.url)
        self.assertIsNone(result.auth)
        # Valid auth config
        test_config = create_plugin_config([host_input], user=test_user, password=test_password)
        result = pre_migration.get_endpoint_info(test_config)
        self.assertEqual(expected_endpoint, result.url)
        self.assertEqual(test_user, result.auth[0])
        self.assertEqual(test_password, result.auth[1])
        # Array of hosts uses the first entry
        test_config = create_plugin_config([host_input, "other_host"], test_user, test_password)
        result = pre_migration.get_endpoint_info(test_config)
        self.assertEqual(expected_endpoint, result.url)
        self.assertEqual(test_user, result.auth[0])
        self.assertEqual(test_password, result.auth[1])

    def test_get_index_differences_empty(self):
        # Base case should return an empty list
        result_tuple = pre_migration.get_index_differences(dict(), dict())
        # Invariant
        self.assertEqual(3, len(result_tuple))
        # All diffs should be empty
        self.assertEqual(set(), result_tuple[0])
        self.assertEqual(set(), result_tuple[1])
        self.assertEqual(set(), result_tuple[2])

    def test_get_index_differences_empty_target(self):
        result_tuple = pre_migration.get_index_differences(test_constants.BASE_INDICES_DATA, dict())
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
        result_tuple = pre_migration.get_index_differences(test_data, test_data)
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
        result_tuple = pre_migration.get_index_differences(test_constants.BASE_INDICES_DATA, test_data)
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
        result_tuple = pre_migration.get_index_differences(test_constants.BASE_INDICES_DATA, test_data)
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

    def test_validate_plugin_config_unsupported_endpoints(self):
        # No supported endpoints
        self.assertRaises(ValueError, pre_migration.validate_plugin_config, BASE_CONFIG_SECTION, TEST_KEY)

    def test_validate_plugin_config_missing_host(self):
        test_data = create_config_section({})
        self.assertRaises(ValueError, pre_migration.validate_plugin_config, test_data, TEST_KEY)

    def test_validate_plugin_config_missing_auth(self):
        test_data = create_config_section(create_plugin_config(["host"]))
        self.assertRaises(ValueError, pre_migration.validate_plugin_config, test_data, TEST_KEY)

    def test_validate_plugin_config_missing_password(self):
        test_data = create_config_section(create_plugin_config(["host"], user="test", disable_auth=False))
        self.assertRaises(ValueError, pre_migration.validate_plugin_config, test_data, TEST_KEY)

    def test_validate_plugin_config_missing_user(self):
        test_data = create_config_section(create_plugin_config(["host"], password="test"))
        self.assertRaises(ValueError, pre_migration.validate_plugin_config, test_data, TEST_KEY)

    def test_validate_plugin_config_auth_disabled(self):
        test_data = create_config_section(create_plugin_config(["host"], user="test", disable_auth=True))
        # Should complete without errors
        pre_migration.validate_plugin_config(test_data, TEST_KEY)

    def test_validate_plugin_config_happy_case(self):
        plugin_config = create_plugin_config(["host"], "user", "password")
        test_data = create_config_section(plugin_config)
        # Should complete without errors
        pre_migration.validate_plugin_config(test_data, TEST_KEY)

    def test_validate_pipeline_config_missing_required_keys(self):
        # Test cases:
        # - Empty input
        # - missing output
        # - missing input
        bad_configs = [{}, {"source": {}}, {"sink": {}}]
        for config in bad_configs:
            self.assertRaises(ValueError, pre_migration.validate_pipeline_config, config)

    def test_validate_pipeline_config_happy_case(self):
        # Get top level value
        test_config = next(iter(self.loaded_pipeline_config.values()))
        pre_migration.validate_pipeline_config(test_config)

    @patch('index_operations.doc_count')
    @patch('pre_migration.write_output')
    @patch('pre_migration.print_report')
    @patch('index_operations.create_indices')
    @patch('index_operations.fetch_all_indices')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_run_report(self, mock_fetch_indices: MagicMock, mock_create_indices: MagicMock,
                        mock_print_report: MagicMock, mock_write_output: MagicMock, mock_doc_count: MagicMock):
        mock_doc_count.return_value = 1
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
        test_input = PreMigrationParams(test_constants.PIPELINE_CONFIG_RAW_FILE_PATH, report=True)
        pre_migration.run(test_input)
        mock_create_indices.assert_called_once_with(expected_create_payload, ANY)
        mock_doc_count.assert_called()
        mock_print_report.assert_called_once_with(expected_diff, 1)
        mock_write_output.assert_not_called()

    @patch('index_operations.doc_count')
    @patch('pre_migration.print_report')
    @patch('pre_migration.write_output')
    @patch('index_operations.fetch_all_indices')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_run_dryrun(self, mock_fetch_indices: MagicMock, mock_write_output: MagicMock,
                        mock_print_report: MagicMock, mock_doc_count: MagicMock):
        index_to_create = test_constants.INDEX1_NAME
        mock_doc_count.return_value = 1
        expected_output_path = "dummy"
        # Create mock data for indices on target
        target_indices_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        del target_indices_data[index_to_create]
        # Fetch indices is called first for source, then for target
        mock_fetch_indices.side_effect = [test_constants.BASE_INDICES_DATA, target_indices_data]
        test_input = PreMigrationParams(test_constants.PIPELINE_CONFIG_RAW_FILE_PATH, expected_output_path, dryrun=True)
        test_result = pre_migration.run(test_input)
        self.assertEqual(mock_doc_count.return_value, test_result.target_doc_count)
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
            pre_migration.write_output(test_input, {index_to_create}, expected_output_path)
            mock_open.assert_called_once_with(expected_output_path, 'w')
            mock_dump.assert_called_once_with(expected_output_data, ANY)

    def test_missing_output_file_non_report(self):
        test_input = PreMigrationParams(test_constants.PIPELINE_CONFIG_RAW_FILE_PATH)
        self.assertRaises(ValueError, pre_migration.run, test_input)


if __name__ == '__main__':
    unittest.main()
