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
BASE_DATA = {
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
    config_section = copy.deepcopy(BASE_DATA)
    config_section[TEST_KEY].append(valid_plugin)
    return config_section


class TestMain(unittest.TestCase):
    # Run before each test
    def setUp(self) -> None:
        with open(LOGSTASH_EXPECTED_OUTPUT_FILE, "rb") as f:
            self.loaded_logstash_config = pickle.load(f)

    def test_get_auth(self):
        # Empty input should produce empty auth tuple
        self.assertFalse(main.get_auth(dict()))
        base_data = {"user": "user", "password": "password"}
        # Password without user should return empty auth tuple
        test_data = dict(base_data)
        del test_data["user"]
        self.assertFalse(main.get_auth(test_data))
        # User without password should return empty auth tuple
        test_data = dict(base_data)
        del test_data["password"]
        self.assertFalse(main.get_auth(test_data))
        # Test valid input
        result = main.get_auth(base_data)
        assert type(result) is tuple
        self.assertEqual("user", result[0])
        self.assertEqual("password", result[1])

    def test_get_endpoint_info(self):
        # Simple base case
        test_config = create_plugin_config(["test"])
        result = main.get_endpoint_info(test_config)
        self.assertEqual("http://test/", result[0])
        self.assertFalse(result[1])
        # SSL enabled
        test_config = create_plugin_config(["test"], True)
        result = main.get_endpoint_info(test_config)
        self.assertEqual("https://test/", result[0])
        self.assertFalse(result[1])
        # SSL disabled, invalid auth config
        test_config = create_plugin_config(["test"], False, "test")
        result = main.get_endpoint_info(test_config)
        self.assertEqual("http://test/", result[0])
        self.assertFalse(result[1])
        # SSL disabled, valid auth config
        test_config = create_plugin_config(["test"], user="user", password="password")
        result = main.get_endpoint_info(test_config)
        self.assertEqual("http://test/", result[0])
        self.assertTrue(result[1])
        # Array of hosts uses the first entry
        test_config = create_plugin_config(["test1", "test2"], True, "user", "password")
        result = main.get_endpoint_info(test_config)
        self.assertEqual("https://test1/", result[0])
        self.assertTrue(result[1])

    def test_print_report(self):
        # Base case should return an empty list
        self.assertFalse(main.print_report(dict(), dict()))
        base_data = {
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
        result = main.print_report(base_data, dict())
        self.assertEqual(2, len(result))
        self.assertTrue("index1" in result)
        self.assertTrue("index2" in result)
        # Index present with exact match
        test_data = copy.deepcopy(base_data)
        del test_data["index2"]
        self.assertFalse(main.print_report(test_data, test_data))
        # Index has conflict in settings
        test_data["index1"]["settings"]["num_key"] = -1
        result = main.print_report(base_data, test_data)
        self.assertEqual(1, len(result))
        self.assertTrue("index2" in result)
        # Index has conflict in mappings
        test_data = copy.deepcopy(base_data)
        del test_data["index1"]["mappings"]["str_key"]
        self.assertFalse(main.print_report(base_data, test_data))

    def test_validate_plugin_config_unsupported_endpoints(self):
        with self.assertRaises(ValueError):
            # No supported endpoints
            main.validate_plugin_config(BASE_DATA, TEST_KEY)

    def test_validate_plugin_config_missing_host(self):
        with self.assertRaises(ValueError):
            # Missing hosts value
            test_data = create_config_section({})
            main.validate_plugin_config(test_data, TEST_KEY)

    def test_validate_plugin_config_bad_auth_password(self):
        with self.assertRaises(ValueError):
            # User without password
            test_data = create_config_section(create_plugin_config(["host"], user="test"))
            main.validate_plugin_config(test_data, TEST_KEY)

    def test_validate_plugin_config_bad_auth_user(self):
        with self.assertRaises(ValueError):
            # Password without user
            test_data = create_config_section(create_plugin_config(["host"], password="test"))
            main.validate_plugin_config(test_data, TEST_KEY)

    def test_validate_plugin_config_happy_case(self):
        plugin_config = create_plugin_config(["host"], True, "user", "password")
        test_data = create_config_section(plugin_config)
        # Should complete without errors
        main.validate_plugin_config(test_data, TEST_KEY)

    def test_validate_logstash_config_missing_required_keys(self):
        with self.assertRaises(ValueError):
            # Empty input
            main.validate_logstash_config(dict())
            # input missing
            main.validate_logstash_config({"output": ()})
            # output missing
            main.validate_logstash_config({"input": ()})

    def test_validate_logstash_config_happy_case(self):
        main.validate_logstash_config(self.loaded_logstash_config)


if __name__ == '__main__':
    unittest.main()
