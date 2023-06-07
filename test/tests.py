from operations import create_index, check_index, create_document, delete_document, get_document, delete_index
from operations import check_document
from http import HTTPStatus
import unittest
import os
import logging
from time import sleep

logger = logging.getLogger(__name__)

# Tests will say which line the test failed at and what was the result of the execution, but better logging can be done.


class E2ETests(unittest.TestCase):
    def setUp(self):
        # This is not necessarily a test, but if a test fails for whatever reason, it can cause next tests to fail even
        # though they could've passed, due to a previous test e.g failing at a point where it creates the index, but it
        # never gets to the point where it gets deleted.
        # The final state of the script should be cleaning up after each test run.
        proxy_endpoint = os.getenv('SOURCE_ENDPOINT', 'https://localhost:9200')
        username = os.getenv('username', 'admin')
        password = os.getenv('password', 'admin')
        auth = (username, password)
        index = "my_index"
        doc_id = '7'
        delete_index(proxy_endpoint, index, auth)
        delete_document(proxy_endpoint, index, doc_id, auth)

    def tearDown(self):
        # This is not necessarily a test, but if a test fails for whatever reason, it can cause next tests to fail even
        # though they could've passed, due to a previous test e.g failing at a point where it creates the index, but it
        # never gets to the point where it gets deleted.
        # The final state of the script should be cleaning up after each test run.
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

        sleep(3)

        target_response = check_index(target_endpoint, index, auth)
        self.assertEqual(target_response.status_code, HTTPStatus.OK)
        source_response = check_index(source_endpoint, index, auth)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        # TODO: check comparator's results here.

        sleep(3)

        proxy_response = delete_index(proxy_endpoint, index, auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)
        # Add a stall here maybe? Sometimes the check index function is performed before the delete request is replayed
        # on the target cluster, so the check will find the index and return a code 200 instead of 404.

        # TODO: check comparator's results here.

        sleep(3)
        target_response = check_index(target_endpoint, index, auth)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = check_index(source_endpoint, index, auth)
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

        target_response = check_document(target_endpoint, index, doc_id, auth)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = check_document(source_endpoint, index, doc_id, auth)
        self.assertEqual(source_response.status_code, HTTPStatus.NOT_FOUND)

        proxy_response = delete_index(proxy_endpoint, index, auth)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        # TODO: check comparator's results here.

        target_response = check_index(target_endpoint, index, auth)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = check_index(source_endpoint, index, auth)
        self.assertEqual(source_response.status_code, HTTPStatus.NOT_FOUND)

    def test_003_unsupported_transformation(self):
        self.assertTrue(True)

    def test_004_supported_transformation(self):
        self.assertTrue(True)


if __name__ == '__main__':
    unittest.main()
