#
# Copyright OpenSearch Contributors
# SPDX-License-Identifier: Apache-2.0
#
# The OpenSearch Contributors require contributions made to
# this file be licensed under the Apache-2.0 license or a
# compatible open source license.
#

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
        failed = index_operations.create_indices(test_data, EndpointInfo(test_constants.TARGET_ENDPOINT))
        self.assertEqual(0, len(failed))

    @responses.activate
    def test_create_indices_empty_alias(self):
        aliases_key = "aliases"
        test_data = copy.deepcopy(test_constants.BASE_INDICES_DATA)
        del test_data[test_constants.INDEX2_NAME]
        del test_data[test_constants.INDEX3_NAME]
        # Setup expected create payload without "aliases"
        expected_payload = copy.deepcopy(test_data[test_constants.INDEX1_NAME])
        del expected_payload[aliases_key]
        responses.put(test_constants.TARGET_ENDPOINT + test_constants.INDEX1_NAME,
                      match=[matchers.json_params_matcher(expected_payload)])
        # Empty "aliases" should be stripped
        failed = index_operations.create_indices(test_data, EndpointInfo(test_constants.TARGET_ENDPOINT))
        self.assertEqual(0, len(failed))
        # Index without "aliases" should not fail
        del test_data[test_constants.INDEX1_NAME][aliases_key]
        failed = index_operations.create_indices(test_data, EndpointInfo(test_constants.TARGET_ENDPOINT))
        self.assertEqual(0, len(failed))

    @responses.activate
    def test_create_indices_exceptions(self):
        # Set up second index to hit an exception
        responses.put(test_constants.TARGET_ENDPOINT + test_constants.INDEX2_NAME,
                      body=requests.Timeout())
        responses.put(test_constants.TARGET_ENDPOINT + test_constants.INDEX1_NAME,
                      json={})
        responses.put(test_constants.TARGET_ENDPOINT + test_constants.INDEX3_NAME,
                      json={})
        failed_indices = index_operations.create_indices(test_constants.BASE_INDICES_DATA,
                                                         EndpointInfo(test_constants.TARGET_ENDPOINT))
        # Verify that failed indices are returned with their respective errors
        self.assertEqual(1, len(failed_indices))
        self.assertTrue(test_constants.INDEX2_NAME in failed_indices)
        self.assertTrue(isinstance(failed_indices[test_constants.INDEX2_NAME], requests.Timeout))

    @responses.activate
    def test_doc_count(self):
        test_indices = {test_constants.INDEX1_NAME, test_constants.INDEX2_NAME}
        index_doc_count: int = 5
        test_buckets = list()
        for index_name in test_indices:
            test_buckets.append({"key": index_name, "doc_count": index_doc_count})
        total_docs: int = index_doc_count * len(test_buckets)
        expected_count_endpoint = test_constants.SOURCE_ENDPOINT + ",".join(test_indices) + "/_search"
        mock_count_response = {"hits": {"total": {"value": total_docs}},
                               "aggregations": {"count": {"buckets": test_buckets}}}
        responses.get(expected_count_endpoint, json=mock_count_response)
        # Now send request
        doc_count_result = index_operations.doc_count(test_indices, EndpointInfo(test_constants.SOURCE_ENDPOINT))
        self.assertEqual(total_docs, doc_count_result.total)

    @responses.activate
    def test_doc_count_error(self):
        test_indices = {test_constants.INDEX1_NAME, test_constants.INDEX2_NAME}
        expected_count_endpoint = test_constants.SOURCE_ENDPOINT + ",".join(test_indices) + "/_search"
        responses.get(expected_count_endpoint, body=requests.Timeout())
        self.assertRaises(RuntimeError, index_operations.doc_count, test_indices,
                          EndpointInfo(test_constants.SOURCE_ENDPOINT))

    @responses.activate
    def test_get_request_errors(self):
        # Set up list of error types to test
        test_errors = [requests.ConnectionError(), requests.HTTPError(), requests.Timeout(),
                       requests.exceptions.MissingSchema()]
        # Verify that each error is wrapped in a RuntimeError
        for e in test_errors:
            responses.get(test_constants.SOURCE_ENDPOINT + "*", body=e)
            self.assertRaises(RuntimeError, index_operations.fetch_all_indices,
                              EndpointInfo(test_constants.SOURCE_ENDPOINT))


if __name__ == '__main__':
    unittest.main()
