import copy
import unittest

import responses
from responses import matchers

import search_endpoint

# Constants
TEST_ENDPOINT = "http://test/"
INDEX1_NAME = "index1"
INDEX2_NAME = "index2"
SETTINGS_KEY = "settings"
INTERNAL_INDEX_KEY = "index"
TEST_DATA = {
    INDEX1_NAME: {
        SETTINGS_KEY: {
            INTERNAL_INDEX_KEY: {
                "uuid": "test",
                "version": 1
            },
            "bool_key": True,
            "str_array": ["abc", "x y z"],
        },
        "mappings": {
            "str_key": "string value",
            "num_key": -1
        }
    },
    INDEX2_NAME: {
        SETTINGS_KEY: {},
        "mappings": {}
    }
}


class TestSearchEndpoint(unittest.TestCase):
    def setUp(self) -> None:
        self.test_data_without_internal = copy.deepcopy(TEST_DATA)
        # Remove internal data
        for i in self.test_data_without_internal.keys():
            self.test_data_without_internal[i][SETTINGS_KEY].pop(INTERNAL_INDEX_KEY, None)

    @responses.activate
    def test_fetch_all_indices(self):
        # Set up GET response
        responses.get(TEST_ENDPOINT + "*", json=TEST_DATA)
        # Now send request
        index_data = search_endpoint.fetch_all_indices(TEST_ENDPOINT)
        self.assertEqual(2, len(index_data.keys()))
        # Test that internal data has been filtered
        self.assertTrue(INTERNAL_INDEX_KEY in index_data[INDEX1_NAME][SETTINGS_KEY])
        self.assertFalse(index_data[INDEX1_NAME][SETTINGS_KEY][INTERNAL_INDEX_KEY])

    @responses.activate
    def test_create_indices(self):
        # Set up expected PUT calls with a mock response status
        responses.put(TEST_ENDPOINT + INDEX1_NAME,
                      match=[matchers.json_params_matcher(self.test_data_without_internal[INDEX1_NAME])])
        responses.put(TEST_ENDPOINT + INDEX2_NAME,
                      match=[matchers.json_params_matcher(self.test_data_without_internal[INDEX2_NAME])])
        search_endpoint.create_indices(self.test_data_without_internal, TEST_ENDPOINT, None)


if __name__ == '__main__':
    unittest.main()
