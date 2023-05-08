import copy
import pickle
import random
import unittest
from typing import Optional
from unittest.mock import patch, MagicMock, ANY

import main
from tests import test_constants

# Constants
TEST_KEY = "test_key"
BASE_CONFIG_SECTION = {
    TEST_KEY: [("invalid_plugin1", None), ("invalid_plugin2", {})]
}


# Utility method to create a test plugin config
def create_plugin_config(host_list: list[str],
                         ssl: Optional[bool] = None,
                         user: Optional[str] = None,
                         password: Optional[str] = None) -> dict:
    config = dict()
    if len(host_list) == 1:
        config["hosts"] = host_list[0]
    else:
        config["hosts"] = host_list
    if ssl:
        config["ssl"] = ssl
    if user:
        config["user"] = user
    if password:
        config["password"] = password
    return config


# Utility method to creat a test config section
def create_config_section(plugin_config: dict) -> dict:
    valid_plugin = (random.choice(main.SUPPORTED_ENDPOINTS), plugin_config)
    config_section = copy.deepcopy(BASE_CONFIG_SECTION)
    config_section[TEST_KEY].append(valid_plugin)
    return config_section


class TestMain(unittest.TestCase):
    # Run before each test
    def setUp(self) -> None:
        with open(test_constants.LOGSTASH_PICKLE_FILE_PATH, "rb") as f:
            self.loaded_logstash_config = pickle.load(f)

    def test_get_auth_returns_none(self):
        # The following inputs should not return an auth tuple:
        # - Empty input
        # - user without password
        # - password without user
        input_list = [{}, {"user": "test"}, {"password": "test"}]
        for test_input in input_list:
            self.assertIsNone(main.get_auth(test_input))

    def test_get_auth_for_valid_input(self):
        # Test valid input
        result = main.get_auth({"user": "user", "password": "pass"})
        self.assertEqual(tuple, type(result))
        self.assertEqual("user", result[0])
        self.assertEqual("pass", result[1])

    def test_get_endpoint_info(self):
        test_user = "user"
        test_password = "password"
        # Simple base case
        test_config = create_plugin_config(["test"])
        result = main.get_endpoint_info(test_config)
        self.assertEqual("http://test/", result[0])
        self.assertIsNone(result[1])
        # SSL enabled
        test_config = create_plugin_config(["test"], True)
        result = main.get_endpoint_info(test_config)
        self.assertEqual("https://test/", result[0])
        self.assertIsNone(result[1])
        # SSL disabled, invalid auth config
        test_config = create_plugin_config(["test"], False, test_user)
        result = main.get_endpoint_info(test_config)
        self.assertEqual("http://test/", result[0])
        self.assertIsNone(result[1])
        # SSL disabled, valid auth config
        test_config = create_plugin_config(["test"], user=test_user, password=test_password)
        result = main.get_endpoint_info(test_config)
        self.assertEqual("http://test/", result[0])
        self.assertEqual(test_user, result[1][0])
        self.assertEqual(test_password, result[1][1])
        # Array of hosts uses the first entry
        test_config = create_plugin_config(["test1", "test2"], True, test_user, test_password)
        result = main.get_endpoint_info(test_config)
        self.assertEqual("https://test1/", result[0])
        self.assertEqual(test_user, result[1][0])
        self.assertEqual(test_password, result[1][1])

    def test_get_index_differences_empty(self):
        # Base case should return an empty list
        result_tuple = main.get_index_differences(dict(), dict())
        # Invariant
        self.assertEqual(3, len(result_tuple))
        # All diffs should be empty
        self.assertEqual(set(), result_tuple[0])
        self.assertEqual(set(), result_tuple[1])
        self.assertEqual(set(), result_tuple[2])

    def test_get_index_differences_empty_target(self):
        result_tuple = main.get_index_differences(test_constants.BASE_INDICES_DATA, dict())
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
        result_tuple = main.get_index_differences(test_data, test_data)
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
        result_tuple = main.get_index_differences(test_constants.BASE_INDICES_DATA, test_data)
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
        result_tuple = main.get_index_differences(test_constants.BASE_INDICES_DATA, test_data)
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
        self.assertRaises(ValueError, main.validate_plugin_config, BASE_CONFIG_SECTION, TEST_KEY)

    def test_validate_plugin_config_missing_host(self):
        test_data = create_config_section({})
        self.assertRaises(ValueError, main.validate_plugin_config, test_data, TEST_KEY)

    def test_validate_plugin_config_bad_auth_password(self):
        test_data = create_config_section(create_plugin_config(["host"], user="test"))
        self.assertRaises(ValueError, main.validate_plugin_config, test_data, TEST_KEY)

    def test_validate_plugin_config_bad_auth_user(self):
        test_data = create_config_section(create_plugin_config(["host"], password="test"))
        self.assertRaises(ValueError, main.validate_plugin_config, test_data, TEST_KEY)

    def test_validate_plugin_config_happy_case(self):
        plugin_config = create_plugin_config(["host"], True, "user", "password")
        test_data = create_config_section(plugin_config)
        # Should complete without errors
        main.validate_plugin_config(test_data, TEST_KEY)

    def test_validate_logstash_config_missing_required_keys(self):
        # Test cases:
        # - Empty input
        # - missing output
        # - missing input
        bad_configs = [{}, {"input": ()}, {"output": ()}]
        for config in bad_configs:
            self.assertRaises(ValueError, main.validate_logstash_config, config)

    def test_validate_logstash_config_happy_case(self):
        main.validate_logstash_config(self.loaded_logstash_config)

    @patch('main.print_report')
    @patch('index_operations.create_indices')
    @patch('index_operations.fetch_all_indices')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_run(self, mock_fetch_indices: MagicMock, mock_create_indices: MagicMock, mock_print_report: MagicMock):
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
        main.run(test_constants.LOGSTASH_RAW_FILE_PATH)
        mock_create_indices.assert_called_once_with(expected_create_payload, test_constants.TARGET_ENDPOINT, ANY)
        mock_print_report.assert_called_once_with(expected_diff)


if __name__ == '__main__':
    unittest.main()
