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

import index_management
from component_template_info import ComponentTemplateInfo
from endpoint_info import EndpointInfo
from exceptions import IndexManagementError, RequestError
from index_template_info import IndexTemplateInfo
from tests import test_constants


# Helper method to create a template API response
def create_base_template_response(list_name: str, body: dict) -> dict:
    return {list_name: [{"name": "test", list_name[:-1]: {"template": {
        test_constants.SETTINGS_KEY: body.get(test_constants.SETTINGS_KEY, {}),
        test_constants.MAPPINGS_KEY: body.get(test_constants.MAPPINGS_KEY, {})
    }}}]}


class TestIndexManagement(unittest.TestCase):
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
        index_data = index_management.fetch_all_indices(EndpointInfo(test_constants.SOURCE_ENDPOINT))
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
        failed = index_management.create_indices(test_data, EndpointInfo(test_constants.TARGET_ENDPOINT))
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
        failed = index_management.create_indices(test_data, EndpointInfo(test_constants.TARGET_ENDPOINT))
        self.assertEqual(0, len(failed))
        # Index without "aliases" should not fail
        del test_data[test_constants.INDEX1_NAME][aliases_key]
        failed = index_management.create_indices(test_data, EndpointInfo(test_constants.TARGET_ENDPOINT))
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
        failed_indices = index_management.create_indices(test_constants.BASE_INDICES_DATA,
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
        doc_count_result = index_management.doc_count(test_indices, EndpointInfo(test_constants.SOURCE_ENDPOINT))
        self.assertEqual(total_docs, doc_count_result.total)

    @responses.activate
    def test_doc_count_error(self):
        test_indices = {test_constants.INDEX1_NAME, test_constants.INDEX2_NAME}
        expected_count_endpoint = test_constants.SOURCE_ENDPOINT + ",".join(test_indices) + "/_search"
        responses.get(expected_count_endpoint, body=requests.Timeout())
        try:
            index_management.doc_count(test_indices, EndpointInfo(test_constants.SOURCE_ENDPOINT))
            # test should not reach this line
            self.fail("Expected exception not thrown")
        except IndexManagementError as e:
            self.assertIsNotNone(e.__cause__)
            self.assertTrue(isinstance(e.__cause__, RequestError))

    @responses.activate
    def test_get_request_errors(self):
        # Set up list of error types to test
        test_errors = [requests.ConnectionError(), requests.HTTPError(), requests.Timeout(),
                       requests.exceptions.MissingSchema()]
        # Verify that each error is wrapped in a IndexManagementError, then a RequestError
        for e in test_errors:
            responses.get(test_constants.SOURCE_ENDPOINT + "*", body=e)
            try:
                index_management.fetch_all_indices(EndpointInfo(test_constants.SOURCE_ENDPOINT))
            except IndexManagementError as ime:
                self.assertIsNotNone(ime.__cause__)
                self.assertTrue(isinstance(ime.__cause__, RequestError))
                self.assertIsNotNone(ime.__cause__.__cause__)

    @responses.activate
    def test_fetch_all_component_templates_empty(self):
        # 1 - Empty response
        responses.get(test_constants.SOURCE_ENDPOINT + "_component_template", json={})
        result = index_management.fetch_all_component_templates(EndpointInfo(test_constants.SOURCE_ENDPOINT))
        # Missing key returns empty result
        self.assertEqual(0, len(result))
        # 2 - Valid response structure but no templates
        responses.get(test_constants.SOURCE_ENDPOINT + "_component_template", json={"component_templates": []})
        result = index_management.fetch_all_component_templates(EndpointInfo(test_constants.SOURCE_ENDPOINT))
        self.assertEqual(0, len(result))
        # 2 - Invalid response structure
        responses.get(test_constants.SOURCE_ENDPOINT + "_component_template", json={"templates": []})
        result = index_management.fetch_all_component_templates(EndpointInfo(test_constants.SOURCE_ENDPOINT))
        self.assertEqual(0, len(result))

    @responses.activate
    def test_fetch_all_component_templates(self):
        # Set up response
        test_index = test_constants.BASE_INDICES_DATA[test_constants.INDEX3_NAME]
        test_resp = create_base_template_response("component_templates", test_index)
        responses.get(test_constants.SOURCE_ENDPOINT + "_component_template", json=test_resp)
        result = index_management.fetch_all_component_templates(EndpointInfo(test_constants.SOURCE_ENDPOINT))
        # Result should contain one template
        self.assertEqual(1, len(result))
        template = result.pop()
        self.assertTrue(isinstance(template, ComponentTemplateInfo))
        self.assertEqual("test", template.get_name())
        template_def = template.get_template_definition()["template"]
        self.assertEqual(test_index[test_constants.SETTINGS_KEY], template_def[test_constants.SETTINGS_KEY])
        self.assertEqual(test_index[test_constants.MAPPINGS_KEY], template_def[test_constants.MAPPINGS_KEY])

    @responses.activate
    def test_fetch_all_index_templates_empty(self):
        # 1 - Empty response
        responses.get(test_constants.SOURCE_ENDPOINT + "_index_template", json={})
        result = index_management.fetch_all_index_templates(EndpointInfo(test_constants.SOURCE_ENDPOINT))
        # Missing key returns empty result
        self.assertEqual(0, len(result))
        # 2 - Valid response structure but no templates
        responses.get(test_constants.SOURCE_ENDPOINT + "_index_template", json={"index_templates": []})
        result = index_management.fetch_all_index_templates(EndpointInfo(test_constants.SOURCE_ENDPOINT))
        self.assertEqual(0, len(result))
        # 2 - Invalid response structure
        responses.get(test_constants.SOURCE_ENDPOINT + "_index_template", json={"templates": []})
        result = index_management.fetch_all_index_templates(EndpointInfo(test_constants.SOURCE_ENDPOINT))
        self.assertEqual(0, len(result))

    @responses.activate
    def test_fetch_all_index_templates(self):
        # Set up base response
        key = "index_templates"
        test_index_pattern = "test-*"
        test_component_template_name = "test_component_template"
        test_index = test_constants.BASE_INDICES_DATA[test_constants.INDEX2_NAME]
        test_resp = create_base_template_response(key, test_index)
        # Add fields specific to index templates
        template_body = test_resp[key][0][key[:-1]]
        template_body["index_patterns"] = [test_index_pattern]
        template_body["composed_of"] = [test_component_template_name]
        responses.get(test_constants.SOURCE_ENDPOINT + "_index_template", json=test_resp)
        result = index_management.fetch_all_index_templates(EndpointInfo(test_constants.SOURCE_ENDPOINT))
        # Result should contain one template
        self.assertEqual(1, len(result))
        template = result.pop()
        self.assertTrue(isinstance(template, IndexTemplateInfo))
        self.assertEqual("test", template.get_name())
        template_def = template.get_template_definition()["template"]
        self.assertEqual(test_index[test_constants.SETTINGS_KEY], template_def[test_constants.SETTINGS_KEY])
        self.assertEqual(test_index[test_constants.MAPPINGS_KEY], template_def[test_constants.MAPPINGS_KEY])

    @responses.activate
    def test_fetch_all_templates_errors(self):
        # Set up error responses
        responses.get(test_constants.SOURCE_ENDPOINT + "_component_template", body=requests.Timeout())
        responses.get(test_constants.SOURCE_ENDPOINT + "_index_template", body=requests.HTTPError())
        try:
            index_management.fetch_all_component_templates(EndpointInfo(test_constants.SOURCE_ENDPOINT))
        except IndexManagementError as e:
            self.assertIsNotNone(e.__cause__)
        try:
            index_management.fetch_all_index_templates(EndpointInfo(test_constants.SOURCE_ENDPOINT))
        except IndexManagementError as e:
            self.assertIsNotNone(e.__cause__)

    @responses.activate
    def test_create_templates(self):
        # Set up test input
        test1_template_def = copy.deepcopy(test_constants.BASE_INDICES_DATA[test_constants.INDEX2_NAME])
        test2_template_def = copy.deepcopy(test_constants.BASE_INDICES_DATA[test_constants.INDEX3_NAME])
        # Remove "aliases" since that's not a valid component template entry
        del test1_template_def[test_constants.ALIASES_KEY]
        del test2_template_def[test_constants.ALIASES_KEY]
        # Test component templates first
        test_templates = set()
        test_templates.add(ComponentTemplateInfo({"name": "test1", "component_template": test1_template_def}))
        test_templates.add(ComponentTemplateInfo({"name": "test2", "component_template": test2_template_def}))
        # Set up expected PUT calls with a mock response status
        responses.put(test_constants.TARGET_ENDPOINT + "_component_template/test1",
                      match=[matchers.json_params_matcher(test1_template_def)])
        responses.put(test_constants.TARGET_ENDPOINT + "_component_template/test2",
                      match=[matchers.json_params_matcher(test2_template_def)])
        failed = index_management.create_component_templates(test_templates,
                                                             EndpointInfo(test_constants.TARGET_ENDPOINT))
        self.assertEqual(0, len(failed))
        # Also test index templates
        test_templates.clear()
        test_templates.add(IndexTemplateInfo({"name": "test1", "index_template": test1_template_def}))
        test_templates.add(IndexTemplateInfo({"name": "test2", "index_template": test2_template_def}))
        # Set up expected PUT calls with a mock response status
        responses.put(test_constants.TARGET_ENDPOINT + "_index_template/test1",
                      match=[matchers.json_params_matcher(test1_template_def)])
        responses.put(test_constants.TARGET_ENDPOINT + "_index_template/test2",
                      match=[matchers.json_params_matcher(test2_template_def)])
        failed = index_management.create_index_templates(test_templates, EndpointInfo(test_constants.TARGET_ENDPOINT))
        self.assertEqual(0, len(failed))

    @responses.activate
    def test_create_templates_failure(self):
        # Set up failures
        responses.put(test_constants.TARGET_ENDPOINT + "_component_template/test1", body=requests.Timeout())
        responses.put(test_constants.TARGET_ENDPOINT + "_index_template/test2", body=requests.HTTPError())
        test_input = ComponentTemplateInfo({"name": "test1", "component_template": {}})
        failed = index_management.create_component_templates({test_input}, EndpointInfo(test_constants.TARGET_ENDPOINT))
        # Verify that failures return their respective errors
        self.assertEqual(1, len(failed))
        self.assertTrue("test1" in failed)
        self.assertTrue(isinstance(failed["test1"], requests.Timeout))
        test_input = IndexTemplateInfo({"name": "test2", "index_template": {}})
        failed = index_management.create_index_templates({test_input}, EndpointInfo(test_constants.TARGET_ENDPOINT))
        # Verify that failures return their respective errors
        self.assertEqual(1, len(failed))
        self.assertTrue("test2" in failed)
        self.assertTrue(isinstance(failed["test2"], requests.HTTPError))


if __name__ == '__main__':
    unittest.main()
