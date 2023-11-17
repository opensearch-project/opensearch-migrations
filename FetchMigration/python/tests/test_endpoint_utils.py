#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

import copy
import pickle
import random
import unittest
from typing import Optional
from unittest.mock import MagicMock, patch

from moto import mock_iam

import endpoint_utils
from endpoint_info import EndpointInfo
from tests import test_constants

# Constants
SUPPORTED_ENDPOINTS = ["opensearch", "elasticsearch"]
INSECURE_KEY = "insecure"
CONNECTION_KEY = "connection"
TEST_KEY = "test_key"
BASE_CONFIG_SECTION = {
    TEST_KEY: [{"invalid_plugin1": {"key": "val"}}, {"invalid_plugin2": {}}]
}


# Utility method to create a test plugin config
def create_plugin_config(host_list: list[str],
                         basic_auth_tuple: Optional[tuple[Optional[str], Optional[str]]] = None,
                         aws_config_snippet: Optional[dict] = None,
                         disable_auth: Optional[bool] = None
                         ) -> dict:
    config = dict()
    config["hosts"] = host_list
    if disable_auth:
        config["disable_authentication"] = disable_auth
    elif basic_auth_tuple:
        user, password = basic_auth_tuple
        if user:
            config["username"] = user
        if password:
            config["password"] = password
    elif aws_config_snippet:
        config.update(aws_config_snippet)
    return config


# Utility method to create a test config section
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

    def test_auth_normalized_url(self):
        val = EndpointInfo("test")
        self.assertEqual("test/", val.get_url())

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
        test_config = create_plugin_config([host_input])
        result = endpoint_utils.get_endpoint_info_from_plugin_config(test_config)
        self.assertEqual(expected_endpoint, result.get_url())
        self.assertIsNone(result.get_auth())
        # Valid auth config
        test_config = create_plugin_config([host_input], (test_user, test_password))
        result = endpoint_utils.get_endpoint_info_from_plugin_config(test_config)
        self.assertEqual(expected_endpoint, result.get_url())
        self.assertEqual(test_user, result.get_auth()[0])
        self.assertEqual(test_password, result.get_auth()[1])
        # Array of hosts uses the first entry
        test_config = create_plugin_config([host_input, "other_host"], (test_user, test_password))
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

    def test_validate_plugin_config_auth_disabled(self):
        test_data = create_config_section(create_plugin_config(["host"], ("test", None), disable_auth=True))
        # Should complete without errors
        endpoint_utils.get_endpoint_info_from_pipeline_config(test_data, TEST_KEY)

    def test_validate_plugin_config_basic_auth(self):
        plugin_config = create_plugin_config(["host"], ("user", "password"))
        test_data = create_config_section(plugin_config)
        # Should complete without errors
        endpoint_utils.get_endpoint_info_from_pipeline_config(test_data, TEST_KEY)

    def test_validate_auth_missing_password(self):
        test_plugin_config = create_plugin_config(["host"], ("test", None), disable_auth=False)
        self.assertRaises(ValueError, endpoint_utils.validate_auth, TEST_KEY, test_plugin_config)

    def test_validate_auth_missing_user(self):
        test_plugin_config = create_plugin_config(["host"], (None, "test"))
        self.assertRaises(ValueError, endpoint_utils.validate_auth, TEST_KEY, test_plugin_config)

    def test_validate_auth_bad_empty_config(self):
        test_plugin_config = create_plugin_config(["host"], aws_config_snippet={})
        self.assertRaises(ValueError, endpoint_utils.validate_auth, TEST_KEY, test_plugin_config)

    @patch('endpoint_utils.get_aws_region')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_validate_auth_aws_sigv4(self, mock_get_aws_region: MagicMock):
        test_plugin_config = create_plugin_config(["host"], aws_config_snippet={"aws_sigv4": False})
        self.assertRaises(ValueError, endpoint_utils.validate_auth, TEST_KEY, test_plugin_config)
        mock_get_aws_region.assert_not_called()
        test_plugin_config = create_plugin_config(["host"], aws_config_snippet={"aws_sigv4": True})
        # Should complete without errors
        endpoint_utils.validate_auth(TEST_KEY, test_plugin_config)
        mock_get_aws_region.assert_called_once()
        mock_get_aws_region.reset_mock()
        # "aws" is expected to be a section so the check is only for the presence of the key
        test_plugin_config = create_plugin_config(["host"], aws_config_snippet={"aws": False})
        endpoint_utils.validate_auth(TEST_KEY, test_plugin_config)
        mock_get_aws_region.assert_called_once()

    @patch('endpoint_utils.get_aws_region')
    @patch('endpoint_utils.get_aws_sigv4_auth')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_get_auth_aws_sigv4(self, mock_get_sigv4_auth: MagicMock, mock_get_aws_region: MagicMock):
        # AWS SigV4 key specified, but disabled
        test_plugin_config = create_plugin_config(["host"], aws_config_snippet={"aws_sigv4": False})
        result = endpoint_utils.get_auth(test_plugin_config)
        self.assertIsNone(result)
        mock_get_sigv4_auth.assert_not_called()
        # AWS SigV4 key enabled
        expected_region = "region"
        mock_get_aws_region.return_value = expected_region
        test_plugin_config = create_plugin_config(["host"], aws_config_snippet={"aws_sigv4": True})
        result = endpoint_utils.get_auth(test_plugin_config)
        self.assertIsNotNone(result)
        mock_get_sigv4_auth.assert_called_once_with(expected_region, False)

    @patch('endpoint_utils.get_aws_region')
    @patch('endpoint_utils.get_aws_sigv4_auth')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_get_auth_aws_config(self, mock_get_sigv4_auth: MagicMock, mock_get_aws_region: MagicMock):
        expected_region = "region"
        mock_get_aws_region.return_value = expected_region
        test_plugin_config = create_plugin_config(["host"], aws_config_snippet={"aws": {"key": "value"}})
        result = endpoint_utils.get_auth(test_plugin_config)
        self.assertIsNotNone(result)
        mock_get_sigv4_auth.assert_called_once_with(expected_region, False)
        mock_get_aws_region.assert_called_once()

    @patch('endpoint_utils.get_aws_region')
    @patch('endpoint_utils.get_aws_sigv4_auth')
    # Note that mock objects are passed bottom-up from the patch order above
    def test_get_auth_aws_sigv4_serverless(self, mock_get_sigv4_auth: MagicMock, mock_get_aws_region: MagicMock):
        expected_region = "region"
        mock_get_aws_region.return_value = expected_region
        test_plugin_config = create_plugin_config(["host"], aws_config_snippet={"aws": {"serverless": True}})
        result = endpoint_utils.get_auth(test_plugin_config)
        self.assertIsNotNone(result)
        mock_get_sigv4_auth.assert_called_once_with(expected_region, True)
        mock_get_aws_region.assert_called_once()

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

    @patch('endpoint_utils.__derive_aws_region_from_url')
    def test_get_aws_region_aws_sigv4(self, mock_derive_region: MagicMock):
        derived_value = "derived"
        mock_derive_region.return_value = derived_value
        aws_sigv4_config = dict()
        aws_sigv4_config["aws_sigv4"] = True
        aws_sigv4_config["aws_region"] = "test"
        self.assertEqual("test", endpoint_utils.get_aws_region(
            create_plugin_config(["host"], aws_config_snippet=aws_sigv4_config)))
        mock_derive_region.assert_not_called()
        del aws_sigv4_config["aws_region"]
        self.assertEqual(derived_value, endpoint_utils.get_aws_region(
            create_plugin_config(["host"], aws_config_snippet=aws_sigv4_config)))
        mock_derive_region.assert_called_once()

    @patch('endpoint_utils.__derive_aws_region_from_url')
    def test_get_aws_region_aws_config(self, mock_derive_region: MagicMock):
        derived_value = "derived"
        mock_derive_region.return_value = derived_value
        test_config = create_plugin_config(["host"], aws_config_snippet={"aws": {"region": "test"}})
        self.assertEqual("test", endpoint_utils.get_aws_region(test_config))
        mock_derive_region.assert_not_called()
        test_config = create_plugin_config(["host"], aws_config_snippet={"aws": {"serverless": True}})
        self.assertEqual(derived_value, endpoint_utils.get_aws_region(test_config))
        mock_derive_region.assert_called_once()
        # Invalid configuration
        test_config = create_plugin_config(["host"], aws_config_snippet={"aws": True})
        self.assertRaises(ValueError, endpoint_utils.get_aws_region, test_config)

    def test_derive_aws_region(self):
        # Custom endpoint that does not match regex
        test_config = create_plugin_config(["https://www.custom.endpoint.amazon.com"],
                                           aws_config_snippet={"aws_sigv4": True})
        self.assertRaises(ValueError, endpoint_utils.get_aws_region, test_config)
        # Non-matching service name
        test_config = create_plugin_config(["test123.test-region.s3.amazonaws.com"],
                                           aws_config_snippet={"aws_sigv4": True})
        self.assertRaises(ValueError, endpoint_utils.get_aws_region, test_config)
        test_config = create_plugin_config(["test-123.test-region.es.amazonaws.com"],
                                           aws_config_snippet={"aws": {"serverless": True}})
        # Should return region successfully
        self.assertEqual("test-region", endpoint_utils.get_aws_region(test_config))

    @mock_iam
    def test_get_aws_sigv4_auth(self):
        result = endpoint_utils.get_aws_sigv4_auth("test")
        self.assertEqual(result.service, "es")
        result = endpoint_utils.get_aws_sigv4_auth("test", True)
        self.assertEqual(result.service, "aoss")


if __name__ == '__main__':
    unittest.main()
