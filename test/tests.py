from operations import create_index, check_index, create_document,\
    delete_document, delete_index, check_document
from http import HTTPStatus
from typing import Tuple, Callable
import unittest
import os
import logging
import time
import requests

logger = logging.getLogger(__name__)

def retry_request(request: Callable, args: Tuple = (), max_attempts: int = 10, delay: float = 0.5, expected_status_code: HTTPStatus = None):
    for attempt in range(1, max_attempts + 1):

        result = request(*args)
        if result.status_code == expected_status_code:
            return result
        else:
            logger.warning(f"Status code returned: {result.status_code} did not"
                           f" match the expected status code: {expected_status_code}."
                           f" Trying again in {delay} seconds.")
            time.sleep(delay)

    logger.error(f"All {max_attempts} attempts failed.")
    logger.error(f"Couldn't get the expected status code: {expected_status_code} while making the request:"
                     f"{request.__name__} using the following arguments: {args} ")


class E2ETests(unittest.TestCase):
    def common_functionality(self):
        self.proxy_endpoint = os.getenv('SOURCE_ENDPOINT', 'https://localhost:9200')
        self.source_endpoint = os.getenv('SOURCE_ENDPOINT', 'http://localhost:19200')
        self.target_endpoint = os.getenv('TARGET_ENDPOINT', 'https://localhost:29200')
        self.username = os.getenv('username', 'admin')
        self.password = os.getenv('password', 'admin')
        self.auth = (self.username, self.password)
        self.index = "my_index"
        self.doc_id = '7'


    def setUp(self):
        self.common_functionality()
        delete_index(self.proxy_endpoint, self.index, self.auth)
        delete_document(self.proxy_endpoint, self.index, self.doc_id, self.auth)

    def tearDown(self):
        self.common_functionality()
        delete_index(self.proxy_endpoint, self.index, self.auth)
        delete_document(self.proxy_endpoint, self.index, self.doc_id, self.auth)

    def test_001_index(self):

        proxy_response = create_index(self.proxy_endpoint, self.index, self.auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = retry_request(check_index, args=(self.target_endpoint, self.index, self.auth),
                                        expected_status_code=HTTPStatus.OK)
        self.assertEqual(target_response.status_code, HTTPStatus.OK)
        source_response = retry_request(check_index, args=(self.source_endpoint, self.index, self.auth),
                                        expected_status_code=HTTPStatus.OK)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        proxy_response = retry_request(delete_index, args=(self.proxy_endpoint, self.index, self.auth),
                                       expected_status_code=HTTPStatus.OK)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = retry_request(check_index, args=(self.target_endpoint, self.index, self.auth),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = retry_request(check_index, args=(self.source_endpoint, self.index, self.auth),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(source_response.status_code, HTTPStatus.NOT_FOUND)

    def test_002_document(self):
        # Creating an index, then asserting that the index was created on both targets.
        proxy_response = create_index(self.proxy_endpoint, self.index, self.auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = check_index(self.target_endpoint, self.index, self.auth)
        self.assertEqual(target_response.status_code, HTTPStatus.OK)
        source_response = check_index(self.source_endpoint, self.index, self.auth)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        # Creating a document, then asserting that the document was created on both targets.
        proxy_response = create_document(self.proxy_endpoint, self.index, self.doc_id, self.auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.CREATED)

        source_response = check_document(self.source_endpoint, self.index, self.doc_id, self.auth)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        target_response = check_document(self.target_endpoint, self.index, self.doc_id, self.auth)
        self.assertEqual(target_response.status_code, HTTPStatus.OK)



        # Comparing the document's content on both endpoints, asserting that they match.
        source_document = source_response.json()
        source_content = source_document['_source']
        target_document = target_response.json()
        target_content = target_document['_source']
        self.assertEqual(source_content, target_content)

        # Deleting the document that was created then asserting that it was deleted on both targets.
        proxy_response = delete_document(self.proxy_endpoint, self.index, self.doc_id, self.auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = retry_request(check_document, args=(self.target_endpoint, self.index, self.doc_id, self.auth),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = retry_request(check_document, args=(self.source_endpoint, self.index, self.doc_id, self.auth),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(source_response.status_code, HTTPStatus.NOT_FOUND)

        # Deleting the index that was created then asserting that it was deleted on both targets.
        proxy_response = delete_index(self.proxy_endpoint, self.index, self.auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)



        target_response = retry_request(check_index, args=(self.target_endpoint, self.index, self.auth),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = retry_request(check_index, args=(self.source_endpoint, self.index, self.auth),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(source_response.status_code, HTTPStatus.NOT_FOUND)

    def test_003_jupyterAwake(self):
        # Making sure that the Jupyter notebook is up and can be reached.
        jupyter_endpoint = os.getenv('JUPYTER_NOTEBOOK', 'http://localhost:8888/api')
        response = requests.get(jupyter_endpoint)
        self.assertEqual(response.status_code, HTTPStatus.OK)
