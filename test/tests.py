from operations import create_index, check_index, create_document, \
    delete_document, delete_index, get_document
from http import HTTPStatus
from typing import Tuple, Callable
import unittest
import os
import logging
import time
import requests
from requests.exceptions import ConnectionError, SSLError

logger = logging.getLogger(__name__)


# The following "retry_request" function's purpose is to retry a certain request for "max_attempts"
# times every "delay" seconds IF the requests returned a status code other than what's expected.
# So this "retry_request" function's arguments are a request function's name and whatever arguments that function
# expects, and the status code the request function is expecting to get.
def retry_request(request: Callable, args: Tuple = (), max_attempts: int = 10, delay: float = 0.5,
                  expected_status_code: HTTPStatus = None):
    for attempt in range(1, max_attempts + 1):
        try:
            result = request(*args)
            if result.status_code == expected_status_code:
                return result
            else:
                logger.warning(f"Status code returned: {result.status_code} did not"
                               f" match the expected status code: {expected_status_code}."
                               f" Trying again in {delay} seconds.")
                time.sleep(delay)
        except ConnectionError as e:
            logger.error(f"Received exception: {e}. Unable to connect to server. Please check all containers are up"
                         f" and ports are setup properly")
            logger.warning(f"Trying again in {delay} seconds.")
            time.sleep(delay)
            continue
        except SSLError as e:
            logger.error(f"Received exception: {e}. Unable to connect to server. Please check all containers are up"
                         f"and ports are setup properly")
            logger.warning(f"Trying again in {delay} seconds.")
            time.sleep(delay)
            continue
    logger.error(f"Couldn't get the expected status code: {expected_status_code} while making the request:"
                 f"{request.__name__} using the following arguments: {args}.")
    raise Exception(f"All {max_attempts} retry attempts failed. Please check the logs for more information.")


class E2ETests(unittest.TestCase):
    def set_common_values(self):
        self.proxy_endpoint = os.getenv('PROXY_ENDPOINT', 'https://localhost:9200')
        self.source_endpoint = os.getenv('SOURCE_ENDPOINT', 'http://localhost:19200')
        self.target_endpoint = os.getenv('TARGET_ENDPOINT', 'https://localhost:29200')
        self.jupyter_endpoint = os.getenv('JUPYTER_NOTEBOOK', 'http://localhost:8888/api')
        self.username = os.getenv('username', 'admin')
        self.password = os.getenv('password', 'admin')
        self.auth = (self.username, self.password)
        self.index = "my_index"
        self.doc_id = '7'

    def setUp(self):
        self.set_common_values()
        retry_request(delete_index, args=(self.proxy_endpoint, self.index, self.auth),
                      expected_status_code=HTTPStatus.NOT_FOUND)
        retry_request(delete_document, args=(self.proxy_endpoint, self.index, self.doc_id, self.auth),
                      expected_status_code=HTTPStatus.NOT_FOUND)

    def tearDown(self):
        delete_index(self.proxy_endpoint, self.index, self.auth)
        delete_document(self.proxy_endpoint, self.index, self.doc_id, self.auth)

    def test_001_index(self):
        # This test will verify that an index will be created (then deleted) on the target cluster when one is created
        # on the source cluster by going through the proxy first. It will verify that the traffic is captured by the
        # proxy and that the traffic reaches the source cluster, replays said traffic to the target cluster by the
        # replayer.

        # Creating an index, then asserting that the index was created on both targets.
        proxy_response = retry_request(create_index, args=(self.proxy_endpoint, self.index, self.auth),
                                       expected_status_code=HTTPStatus.OK)
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
        # This test will verify that a document will be created (then deleted) on the target cluster when one is created
        # on the source cluster by going through the proxy first. It will verify that the traffic is captured by the
        # proxy and that the traffic reaches the source cluster, replays said traffic to the target cluster by the
        # replayer.

        # Creating an index, then asserting that the index was created on both targets.
        proxy_response = retry_request(create_index, args=(self.proxy_endpoint, self.index, self.auth),
                                       expected_status_code=HTTPStatus.OK)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = retry_request(check_index, args=(self.target_endpoint, self.index, self.auth),
                                        expected_status_code=HTTPStatus.OK)
        self.assertEqual(target_response.status_code, HTTPStatus.OK)
        source_response = retry_request(check_index, args=(self.source_endpoint, self.index, self.auth),
                                        expected_status_code=HTTPStatus.OK)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        # Creating a document, then asserting that the document was created on both targets.
        proxy_response = create_document(self.proxy_endpoint, self.index, self.doc_id, self.auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.CREATED)

        source_response = get_document(self.source_endpoint, self.index, self.doc_id, self.auth)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        target_response = get_document(self.target_endpoint, self.index, self.doc_id, self.auth)
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

        target_response = retry_request(get_document, args=(self.target_endpoint, self.index, self.doc_id, self.auth),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = retry_request(get_document, args=(self.source_endpoint, self.index, self.doc_id, self.auth),
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
        response = requests.get(self.jupyter_endpoint)
        self.assertEqual(response.status_code, HTTPStatus.OK)
