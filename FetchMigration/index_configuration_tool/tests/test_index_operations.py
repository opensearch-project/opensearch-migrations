import copy
import unittest

import responses
from responses import matchers

import index_operations
from tests import test_constants


class TestSearchEndpoint(unittest.TestCase):
    @responses.activate
    def test_fetch_all_indices(self):
        # Set up GET response
        responses.get(test_constants.SOURCE_ENDPOINT + "*", json=test_constants.BASE_INDICES_DATA)
        # Now send request
        index_data = index_operations.fetch_all_indices(test_constants.SOURCE_ENDPOINT)
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
        index_operations.create_indices(test_data, test_constants.TARGET_ENDPOINT, None)


if __name__ == '__main__':
    unittest.main()
