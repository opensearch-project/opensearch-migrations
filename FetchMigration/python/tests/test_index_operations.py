import copy
import unittest

import requests
import responses
from responses import matchers

import index_operations
from endpoint_info import EndpointInfo
from tests import test_constants


class TestIndexOperations(unittest.TestCase):
    @responses.activate
    def test_fetch_all_indices(self):
        # Set up GET response
        test_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        # Add system index
        test_data[".system-index"] = {
            test_constants.SETTINGS_KEY: {
                test_constants.INDEX_KEY: {
                    test_constants.NUM_SHARDS_SETTING: 1,
                    test_constants.NUM_REPLICAS_SETTING: 1
                }
            }
        }
        responses.get(test_constants.SOURCE_ENDPOINT + "*", json=test_data)
        # Now send request
        index_data = index_operations.fetch_all_indices(EndpointInfo(test_constants.SOURCE_ENDPOINT))
        self.assertEqual(3, len(index_data.keys()))
        # Test that internal data has been filtered, but non-internal data is retained
        index_settings = index_data[test_constants.INDEX1_NAME][test_constants.SETTINGS_KEY]
        self.assertTrue(test_constants.INDEX_KEY in index_settings)
        self.assertEqual({"is_filtered": False}, index_settings[test_constants.INDEX_KEY])
        index_mappings = index_data[test_constants.INDEX2_NAME][test_constants.MAPPINGS_KEY]
        self.assertEqual("strict", index_mappings["dynamic"])

    @responses.activate
    def test_create_indices(self):
        # Set up expected PUT calls with a mock response status
        test_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        del test_data[test_constants.INDEX1_NAME]
        responses.put(test_constants.TARGET_ENDPOINT + test_constants.INDEX2_NAME,
                      match=[matchers.json_params_matcher(test_data[test_constants.INDEX2_NAME])])
        responses.put(test_constants.TARGET_ENDPOINT + test_constants.INDEX3_NAME,
                      match=[matchers.json_params_matcher(test_data[test_constants.INDEX3_NAME])])
        index_operations.create_indices(test_data, EndpointInfo(test_constants.TARGET_ENDPOINT))

    @responses.activate
    def test_create_indices_exception(self):
        # Set up expected PUT calls with a mock response status
        test_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        del test_data[test_constants.INDEX2_NAME]
        del test_data[test_constants.INDEX3_NAME]
        responses.put(test_constants.TARGET_ENDPOINT + test_constants.INDEX1_NAME,
                      body=requests.Timeout())
        self.assertRaises(RuntimeError, index_operations.create_indices, test_data,
                          EndpointInfo(test_constants.TARGET_ENDPOINT))

    @responses.activate
    def test_doc_count(self):
        test_indices = {test_constants.INDEX1_NAME, test_constants.INDEX2_NAME}
        expected_count_endpoint = test_constants.SOURCE_ENDPOINT + ",".join(test_indices) + "/_count"
        mock_count_response = {"count": "10"}
        responses.get(expected_count_endpoint, json=mock_count_response)
        # Now send request
        count_value = index_operations.doc_count(test_indices, EndpointInfo(test_constants.SOURCE_ENDPOINT))
        self.assertEqual(10, count_value)


if __name__ == '__main__':
    unittest.main()
