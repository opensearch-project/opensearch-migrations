import copy
import pickle
import random
import unittest
from os.path import dirname
from typing import Optional

import main

# Constants
LOGSTASH_EXPECTED_OUTPUT_FILE = dirname(__file__) + "/expected_parse_output.pickle"
TEST_KEY = "test_key"
BASE_CONFIG_SECTION = {
    TEST_KEY: [("invalid_plugin1", None), ("invalid_plugin2", {})]
}
BASE_INDEX_CONFIG = {
    "index1": {
        "settings": {
            "bool_key": True,
            "num_key": 1
        },
        "mappings": {
            "str_key": "value"
        }
    },
    "index2": {}
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
        with open(LOGSTASH_EXPECTED_OUTPUT_FILE, "rb") as f:
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
        result_tuple = main.get_index_differences(BASE_INDEX_CONFIG, dict())
        # Invariant
        self.assertEqual(3, len(result_tuple))
        # No conflicts or identical indices
        self.assertEqual(set(), result_tuple[1])
        self.assertEqual(set(), result_tuple[2])
        # Indices-to-create
        self.assertEqual(2, len(result_tuple[0]))
        self.assertTrue("index1" in result_tuple[0])
        self.assertTrue("index2" in result_tuple[0])

    def test_get_index_differences_identical_index(self):
        test_data = copy.deepcopy(BASE_INDEX_CONFIG)
        del test_data["index2"]
        result_tuple = main.get_index_differences(test_data, test_data)
        # Invariant
        self.assertEqual(3, len(result_tuple))
        # No indices to move, or conflicts
        self.assertEqual(set(), result_tuple[0])
        self.assertEqual(set(), result_tuple[2])
        # Identical indices
        self.assertEqual(1, len(result_tuple[1]))
        self.assertTrue("index1" in result_tuple[1])

    def test_get_index_differences_settings_conflict(self):
        test_data = copy.deepcopy(BASE_INDEX_CONFIG)
        test_data["index1"]["settings"]["num_key"] = -1
        result_tuple = main.get_index_differences(BASE_INDEX_CONFIG, test_data)
        # Invariant
        self.assertEqual(3, len(result_tuple))
        # No indices to move
        self.assertEqual(set(), result_tuple[0])
        # Identical indices
        self.assertEqual(1, len(result_tuple[1]))
        self.assertTrue("index2" in result_tuple[1])
        # Conflicting indices
        self.assertEqual(1, len(result_tuple[2]))
        self.assertTrue("index1" in result_tuple[2])

    def test_get_index_differences_mappings_conflict(self):
        test_data = copy.deepcopy(BASE_INDEX_CONFIG)
        del test_data["index1"]["mappings"]["str_key"]
        result_tuple = main.get_index_differences(BASE_INDEX_CONFIG, test_data)
        # Invariant
        self.assertEqual(3, len(result_tuple))
        # No indices to move
        self.assertEqual(set(), result_tuple[0])
        # Identical indices
        self.assertEqual(1, len(result_tuple[1]))
        self.assertTrue("index2" in result_tuple[1])
        # Conflicting indices
        self.assertEqual(1, len(result_tuple[2]))
        self.assertTrue("index1" in result_tuple[2])

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


if __name__ == '__main__':
    unittest.main()
