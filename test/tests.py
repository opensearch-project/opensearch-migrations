from operations import create_index, check_index, create_document, delete_document, get_document, delete_index
from operations import check_document
from http import HTTPStatus
import unittest
import os
import logging
import time

logger = logging.getLogger(__name__)

# Tests will say which line the test failed at and what was the result of the execution, but better logging can be done.


def retry_request(request, args=(), max_attempts=10, delay=0.5, expectedStatusCode=None):
    for attempt in range(1, max_attempts + 1):
        try:
            result = request(*args)
            if result.status_code == expectedStatusCode:
                return result
            else:
                logger.error(f"Status code returned: {result.status_code} did not"
                             f" match the expected status code: {expectedStatusCode}")
        except Exception:
            logger.error(f"Trying again in {delay} seconds")
            time.sleep(delay)

    raise Exception(f"All {max_attempts} attempts failed.")


class E2ETests(unittest.TestCase):
    def setUp(self):
        proxy_endpoint = os.getenv('SOURCE_ENDPOINT', 'https://localhost:9200')
        username = os.getenv('username', 'admin')
        password = os.getenv('password', 'admin')
        auth = (username, password)
        index = "my_index"
        doc_id = '7'
        delete_index(proxy_endpoint, index, auth)
        delete_document(proxy_endpoint, index, doc_id, auth)

    def tearDown(self):
        proxy_endpoint = os.getenv('SOURCE_ENDPOINT', 'https://localhost:9200')
        username = os.getenv('username', 'admin')
        password = os.getenv('password', 'admin')
        auth = (username, password)
        index = "my_index"
        doc_id = '7'
        delete_index(proxy_endpoint, index, auth)
        delete_document(proxy_endpoint, index, doc_id, auth)

    def test_001_index(self):
        proxy_endpoint = os.getenv('PROXY_ENDPOINT', 'https://localhost:9200')
        source_endpoint = os.getenv('SOURCE_ENDPOINT', 'http://localhost:19200')
        target_endpoint = os.getenv('TARGET_ENDPOINT', 'https://localhost:29200')
        username = os.getenv('username', 'admin')
        password = os.getenv('password', 'admin')
        auth = (username, password)
        index = "my_index"

        proxy_response = create_index(proxy_endpoint, index, auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = retry_request(check_index, args=(target_endpoint, index, auth),
                                        expectedStatusCode=HTTPStatus.OK)
        self.assertEqual(target_response.status_code, HTTPStatus.OK)
        source_response = retry_request(check_index, args=(source_endpoint, index, auth),
                                        expectedStatusCode=HTTPStatus.OK)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        # TODO: check comparator's results here.

        proxy_response = retry_request(delete_index, args=(proxy_endpoint, index, auth),
                                       expectedStatusCode=HTTPStatus.OK)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)
        # Add a stall here maybe? Sometimes the check index function is performed before the delete request is replayed
        # on the target cluster, so the check will find the index and return a code 200 instead of 404.

        # TODO: check comparator's results here.

        target_response = retry_request(check_index, args=(target_endpoint, index, auth),
                                        expectedStatusCode=HTTPStatus.NOT_FOUND)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = retry_request(check_index, args=(source_endpoint, index, auth),
                                        expectedStatusCode=HTTPStatus.NOT_FOUND)
        self.assertEqual(source_response.status_code, HTTPStatus.NOT_FOUND)

    def test_002_document(self):
        proxy_endpoint = os.getenv('PROXY_ENDPOINT', 'https://localhost:9200')
        source_endpoint = os.getenv('SOURCE_ENDPOINT', 'http://localhost:19200')
        target_endpoint = os.getenv('TARGET_ENDPOINT', 'https://localhost:29200')
        username = os.getenv('username', 'admin')
        password = os.getenv('password', 'admin')
        auth = (username, password)
        index = "my_index"
        doc_id = '7'

        proxy_response = create_index(proxy_endpoint, index, auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = check_index(target_endpoint, index, auth)
        self.assertEqual(target_response.status_code, HTTPStatus.OK)
        source_response = check_index(source_endpoint, index, auth)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        # TODO: check comparator's results here.

        proxy_response = create_document(proxy_endpoint, index, doc_id, auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.CREATED)
        # TODO: check comparator's results here.
        # TODO: compare two documents below instead of just confirming they exist

        source_response = check_document(source_endpoint, index, doc_id, auth)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        target_response = check_document(target_endpoint, index, doc_id, auth)
        self.assertEqual(target_response.status_code, HTTPStatus.OK)

        source_content = get_document(source_endpoint, index, doc_id, auth)
        target_content = get_document(target_endpoint, index, doc_id, auth)
        self.assertEqual(source_content, target_content)

        proxy_response = delete_document(proxy_endpoint, index, doc_id, auth)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        target_response = retry_request(check_document, args=(target_endpoint, index, doc_id, auth),
                                        expectedStatusCode=HTTPStatus.NOT_FOUND)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = retry_request(check_document, args=(source_endpoint, index, doc_id, auth),
                                        expectedStatusCode=HTTPStatus.NOT_FOUND)
        self.assertEqual(source_response.status_code, HTTPStatus.NOT_FOUND)

        proxy_response = delete_index(proxy_endpoint, index, auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        # TODO: check comparator's results here.

        target_response = retry_request(check_index, args=(target_endpoint, index, auth),
                                        expectedStatusCode=HTTPStatus.NOT_FOUND)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = retry_request(check_index, args=(source_endpoint, index, auth),
                                        expectedStatusCode=HTTPStatus.NOT_FOUND)
        self.assertEqual(source_response.status_code, HTTPStatus.NOT_FOUND)

    def test_003_unsupported_transformation(self):
        self.assertTrue(True)

    def test_004_supported_transformation(self):
        self.assertTrue(True)


if __name__ == '__main__':
    unittest.main()
