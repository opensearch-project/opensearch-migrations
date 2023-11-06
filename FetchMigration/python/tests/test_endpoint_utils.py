import copy
import pickle
import random
import unittest
from typing import Optional

import endpoint_utils

# Constants
from tests import test_constants

SUPPORTED_ENDPOINTS = ["opensearch", "elasticsearch"]
INSECURE_KEY = "insecure"
CONNECTION_KEY = "connection"
TEST_KEY = "test_key"
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
    valid_plugin[random.choice(SUPPORTED_ENDPOINTS)] = plugin_config
    config_section = copy.deepcopy(BASE_CONFIG_SECTION)
    config_section[TEST_KEY].append(valid_plugin)
    return config_section


class TestEndpointUtils(unittest.TestCase):
    # Run before each test
    def setUp(self) -> None:
        with open(test_constants.PIPELINE_CONFIG_PICKLE_FILE_PATH, "rb") as f:
            self.loaded_pipeline_config = pickle.load(f)

    def test_is_insecure_default_value(self):
        self.assertFalse(endpoint_utils.is_insecure({}))

    def test_is_insecure_top_level_key(self):
        test_input = {"key": 123, INSECURE_KEY: True}
        self.assertTrue(endpoint_utils.is_insecure(test_input))

    def test_is_insecure_nested_key(self):
        test_input = {"key1": 123, CONNECTION_KEY: {"key2": "val", INSECURE_KEY: True}}
        self.assertTrue(endpoint_utils.is_insecure(test_input))

    def test_is_insecure_missing_nested(self):
        test_input = {"key1": 123, CONNECTION_KEY: {"key2": "val"}}
        self.assertFalse(endpoint_utils.is_insecure(test_input))

    def test_get_auth_returns_none(self):
        # The following inputs should not return an auth tuple:
        # - Empty input
        # - user without password
        # - password without user
        input_list = [{}, {"username": "test"}, {"password": "test"}]
        for test_input in input_list:
            self.assertIsNone(endpoint_utils.get_auth(test_input))

    def test_get_auth_basic(self):
        # Test valid input
        result = endpoint_utils.get_auth({"username": "user", "password": "pass"})
        self.assertEqual(tuple, type(result))
        self.assertEqual("user", result[0])
        self.assertEqual("pass", result[1])

    def get_endpoint_info_from_plugin_config(self):
        host_input = "test"
        expected_endpoint = "test/"
        test_user = "user"
        test_password = "password"
        # Simple base case
        test_config = create_plugin_config([host_input])

        result = endpoint_utils.get_endpoint_info_from_plugin_config(test_config)
        self.assertEqual(expected_endpoint, result.get_url())
        self.assertIsNone(result.get_auth())
        self.assertTrue(result.is_verify_ssl())
        # Invalid auth config
        test_config = create_plugin_config([host_input], test_user)
        result = endpoint_utils.get_endpoint_info_from_plugin_config(test_config)
        self.assertEqual(expected_endpoint, result.get_url())
        self.assertIsNone(result.get_auth())
        # Valid auth config
        test_config = create_plugin_config([host_input], user=test_user, password=test_password)
        result = endpoint_utils.get_endpoint_info_from_plugin_config(test_config)
        self.assertEqual(expected_endpoint, result.get_url())
        self.assertEqual(test_user, result.get_auth()[0])
        self.assertEqual(test_password, result.get_auth()[1])
        # Array of hosts uses the first entry
        test_config = create_plugin_config([host_input, "other_host"], test_user, test_password)
        result = endpoint_utils.get_endpoint_info_from_plugin_config(test_config)
        self.assertEqual(expected_endpoint, result.get_url())
        self.assertEqual(test_user, result.get_auth()[0])
        self.assertEqual(test_password, result.get_auth()[1])

    def test_validate_plugin_config_unsupported_endpoints(self):
        # No supported endpoints
        self.assertRaises(ValueError, endpoint_utils.get_endpoint_info_from_pipeline_config,
                          BASE_CONFIG_SECTION, TEST_KEY)

    def test_validate_plugin_config_missing_host(self):
        test_data = create_config_section({})
        self.assertRaises(ValueError, endpoint_utils.get_endpoint_info_from_pipeline_config, test_data, TEST_KEY)

    def test_validate_plugin_config_missing_auth(self):
        test_data = create_config_section(create_plugin_config(["host"]))
        self.assertRaises(ValueError, endpoint_utils.get_endpoint_info_from_pipeline_config, test_data, TEST_KEY)

    def test_validate_plugin_config_missing_password(self):
        test_data = create_config_section(create_plugin_config(["host"], user="test", disable_auth=False))
        self.assertRaises(ValueError, endpoint_utils.get_endpoint_info_from_pipeline_config, test_data, TEST_KEY)

    def test_validate_plugin_config_missing_user(self):
        test_data = create_config_section(create_plugin_config(["host"], password="test"))
        self.assertRaises(ValueError, endpoint_utils.get_endpoint_info_from_pipeline_config, test_data, TEST_KEY)

    def test_validate_plugin_config_auth_disabled(self):
        test_data = create_config_section(create_plugin_config(["host"], user="test", disable_auth=True))
        # Should complete without errors
        endpoint_utils.get_endpoint_info_from_pipeline_config(test_data, TEST_KEY)

    def test_validate_plugin_config_happy_case(self):
        plugin_config = create_plugin_config(["host"], "user", "password")
        test_data = create_config_section(plugin_config)
        # Should complete without errors
        endpoint_utils.get_endpoint_info_from_pipeline_config(test_data, TEST_KEY)

    def test_validate_pipeline_missing_required_keys(self):
        # Test cases:
        # - Empty input
        # - missing output
        # - missing input
        bad_configs = [{}, {"source": {}}, {"sink": {}}]
        for config in bad_configs:
            self.assertRaises(ValueError, endpoint_utils.validate_pipeline, config)

    def test_validate_pipeline_config_happy_case(self):
        # Get top level value
        test_config = next(iter(self.loaded_pipeline_config.values()))
        result = endpoint_utils.get_endpoint_info_from_pipeline_config(test_config, "source")
        self.assertIsNotNone(result)
        endpoint_utils.get_endpoint_info_from_pipeline_config(test_config, "sink")
        self.assertIsNotNone(result)


if __name__ == '__main__':
    unittest.main()
