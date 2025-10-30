import datetime
import logging
import random
import string
import json
import time
from typing import Dict, List, Optional
from unittest import TestCase
from console_link.middleware.clusters import run_test_benchmarks
from console_link.models.cluster import HttpMethod, Cluster
from .common_utils import execute_api_call, DEFAULT_INDEX_IGNORE_LIST

logger = logging.getLogger(__name__)


class DefaultOperationsLibrary:
    """
    Provides a library of high-level common operations to perform on an elasticsearch or opensearch cluster as well as
    operations for interacting with a Migration Assistant deployment.
    **Note**: This library was implemented as a default to work with OpenSearch 2.x clusters and should be extended to
    work with older clusters, such as Elasticsearch 5.x clusters, where pattern differences such as multi-type indices
    exist.
    """

    def create_index(self, index_name: str, cluster: Cluster, **kwargs):
        headers = {'Content-Type': 'application/json'}
        return execute_api_call(cluster=cluster, method=HttpMethod.PUT, path=f"/{index_name}",
                                headers=headers, **kwargs)

    def get_index(self, index_name: str, cluster: Cluster, **kwargs):
        return execute_api_call(cluster=cluster, method=HttpMethod.GET, path=f"/{index_name}",
                                **kwargs)

    def delete_index(self, index_name: str, cluster: Cluster, **kwargs):
        return execute_api_call(cluster=cluster, method=HttpMethod.DELETE, path=f"/{index_name}",
                                **kwargs)

    def create_document(self, index_name: str, doc_id: str, cluster: Cluster, data: dict = None, doc_type="_doc",
                        **kwargs):
        if data is None:
            data = {
                'title': 'Test Document',
                'content': 'This is a sample document for testing OpenSearch.'
            }
        headers = {'Content-Type': 'application/json'}
        return execute_api_call(cluster=cluster, method=HttpMethod.PUT, path=f"/{index_name}/{doc_type}/{doc_id}",
                                data=json.dumps(data), headers=headers, **kwargs)

    def create_and_retrieve_document(self, index_name: str, doc_id: str, cluster: Cluster, data: dict = None,
                                     doc_type="_doc", **kwargs):
        self.create_document(index_name=index_name, doc_id=doc_id, cluster=cluster, data=data, doc_type=doc_type,
                             **kwargs)
        headers = {'Content-Type': 'application/json'}
        self.get_document(index_name=index_name, doc_id=doc_id, cluster=cluster, data=data, doc_type=doc_type,
                          headers=headers, **kwargs)

    def get_document(self, index_name: str, doc_id: str, cluster: Cluster, doc_type="_doc", **kwargs):
        return execute_api_call(cluster=cluster, method=HttpMethod.GET, path=f"/{index_name}/{doc_type}/{doc_id}",
                                **kwargs)

    def delete_document(self, index_name: str, doc_id: str, cluster: Cluster, doc_type="_doc", **kwargs):
        return execute_api_call(cluster=cluster, method=HttpMethod.DELETE, path=f"/{index_name}/{doc_type}/{doc_id}",
                                **kwargs)

    def clear_index_templates(self, cluster: Cluster, **kwargs):
        logger.warning(f"Clearing index templates has not been implemented for cluster version: {cluster.version}")
        return

    def get_all_composable_index_template_names(self, cluster: Cluster, **kwargs):
        response = execute_api_call(cluster=cluster, method=HttpMethod.GET, path="/_index_template", **kwargs)
        data = response.json()
        templates = data.get("index_templates", [])
        return [tpl["name"] for tpl in templates]

    def verify_index_mapping_properties(self, index_name: str, cluster: Cluster, expected_props: set, **kwargs):
        response = execute_api_call(cluster=cluster, method=HttpMethod.GET, path=f"/{index_name}", **kwargs)
        data = response.json()
        mappings = data[index_name]["mappings"]["properties"]
        if not all(prop in mappings for prop in expected_props):
            raise AssertionError(f"Expected properties: {expected_props} not found in index "
                                 f"mappings {list(mappings.keys())}")

    def index_matches_ignored_index(self, index_name: str, index_prefix_ignore_list: List[str]):
        for prefix in index_prefix_ignore_list:
            if index_name.startswith(prefix):
                return True
        return False

    def get_all_index_details(self, cluster: Cluster, index_prefix_ignore_list=None,
                              **kwargs) -> Dict[str, Dict[str, str]]:
        all_index_details = execute_api_call(cluster=cluster, path="/_cat/indices?format=json", **kwargs).json()
        index_dict = {}
        for index_details in all_index_details:
            # While cat/indices returns a doc count metric, the underlying implementation bleeds through details, only
            # capture the index name and make a separate api call for the doc count
            index_name = index_details['index']
            valid_index = not self.index_matches_ignored_index(index_name,
                                                               index_prefix_ignore_list=index_prefix_ignore_list)
            if index_prefix_ignore_list is None or valid_index:
                # "To get an accurate count of Elasticsearch documents, use the cat count or count APIs."
                # See https://www.elastic.co/guide/en/elasticsearch/reference/7.10/cat-indices.html

                count_response = execute_api_call(cluster=cluster, path=f"/{index_name}/_count?format=json", **kwargs)
                index_dict[index_name] = count_response.json()
                index_dict[index_name]['index'] = index_name
        return index_dict

    def check_doc_counts_match(self, cluster: Cluster,
                               expected_index_details: Dict[str, Dict[str, str]],
                               test_case: Optional[TestCase] = None,
                               index_prefix_ignore_list=None,
                               max_attempts: int = 5,
                               delay: float = 2.5):
        if index_prefix_ignore_list is None:
            index_prefix_ignore_list = DEFAULT_INDEX_IGNORE_LIST

        error_message = ""
        for attempt in range(1, max_attempts + 1):
            # Refresh documents
            execute_api_call(cluster=cluster, path="/_refresh")
            actual_index_details = self.get_all_index_details(cluster=cluster,
                                                              index_prefix_ignore_list=index_prefix_ignore_list)
            logger.debug(f"Received actual indices: {actual_index_details}")
            if not expected_index_details.keys() <= actual_index_details.keys():
                error_message = (f"Indices are different: \n Expected: {expected_index_details.keys()} \n "
                                 f"Actual: {actual_index_details.keys()}")
            else:
                errors = []
                for index_name in expected_index_details.keys():
                    expected_doc_count = int(expected_index_details[index_name]['count'])
                    actual_doc_count = int(actual_index_details[index_name]['count'])
                    if actual_doc_count != expected_doc_count:
                        errors.append(f"Index {index_name} has {actual_doc_count} documents "
                                      f"but {expected_doc_count} were expected")
                error_message = ",\n".join(errors)
            if not error_message:
                return True
            if attempt != max_attempts:
                logger.debug(f"Error on attempt {attempt}: {error_message}")
                error_message = ""
                time.sleep(delay)
        if test_case is not None:
            test_case.fail(error_message)
        else:
            raise AssertionError(error_message)

    def check_doc_match(self, test_case: TestCase, index_name: str, doc_id: str, source_cluster: Cluster,
                        target_cluster: Cluster):
        source_response = self.get_document(index_name=index_name, doc_id=doc_id, cluster=source_cluster)
        target_response = self.get_document(index_name=index_name, doc_id=doc_id, cluster=target_cluster)

        source_document = source_response.json()
        source_content = source_document['_source']
        target_document = target_response.json()
        target_content = target_document['_source']
        test_case.assertEqual(source_content, target_content)

    def generate_large_doc(self, size_mib):
        # Calculate number of characters needed (1 char = 1 byte)
        num_chars = size_mib * 1024 * 1024

        # Generate random string of the desired length
        large_string = ''.join(random.choices(string.ascii_letters + string.digits, k=num_chars))

        return {
            "timestamp": datetime.datetime.now().isoformat(),
            "large_field": large_string
        }

    def convert_transformations_to_str(self, transform_list: List[Dict]) -> str:
        return json.dumps(transform_list)

    def get_index_name_transformation(self, existing_index_name: str, target_index_name: str,
                                      source_major_version: int, source_minor_version: int) -> Dict:
        return {
            "TypeMappingSanitizationTransformerProvider": {
                "staticMappings": {
                    f"{existing_index_name}": {
                        "_doc": f"{target_index_name}"
                    }
                },
                "sourceProperties": {
                    "version": {
                        "major": source_major_version,
                        "minor": source_minor_version
                    }
                }

            }
        }

    def get_noop_transformation(self):
        return {
            "NoopTransformerProvider": ""
        }
    
    def run_test_benchmarks(self, cluster: Cluster):
        run_test_benchmarks(cluster=cluster)
