import json
import subprocess

from operations import create_index, check_index, create_document, \
    delete_document, delete_index, get_document
from http import HTTPStatus
from typing import Tuple, Callable
import unittest
import os
import logging
import time
import requests
import uuid
import string
import secrets
from requests.exceptions import ConnectionError, SSLError

logger = logging.getLogger(__name__)


def get_indices(endpoint, auth):
    response = requests.get(f'{endpoint}/_cat/indices', auth=auth, verify=False)
    indices = []
    response_lines = response.text.strip().split('\n')
    for line in response_lines:
        parts = line.split()
        index_name = parts[2]
        indices.append(index_name)
    return indices


def get_doc_count(endpoint, index, auth):
    response = requests.get(f'{endpoint}/{index}/_count', auth=auth, verify=False)
    count = json.loads(response.text)['count']
    return count


# The following "retry_request" function's purpose is to retry a certain request for "max_attempts"
# times every "delay" seconds IF the requests returned a status code other than what's expected.
# So this "retry_request" function's arguments are a request function's name and whatever arguments that function
# expects, and the status code the request function is expecting to get.
def retry_request(request: Callable, args: Tuple = (), max_attempts: int = 15, delay: float = 1.5,
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
        self.source_endpoint = os.getenv('SOURCE_ENDPOINT', 'https://localhost:19200')
        self.target_endpoint = os.getenv('TARGET_ENDPOINT', 'https://localhost:29200')
        self.jupyter_endpoint = os.getenv('JUPYTER_NOTEBOOK', 'http://localhost:8888/api')
        self.username = os.getenv('username', 'admin')
        self.password = os.getenv('password', 'admin')
        self.auth = (self.username, self.password)
        self.index = f"my_index_{uuid.uuid4()}"
        self.doc_id = '7'
        self.ignore_list = []

    def setUp(self):
        self.set_common_values()
        retry_request(delete_index, args=(self.proxy_endpoint, self.index, self.auth),
                      expected_status_code=HTTPStatus.NOT_FOUND)
        retry_request(delete_document, args=(self.proxy_endpoint, self.index, self.doc_id, self.auth),
                      expected_status_code=HTTPStatus.NOT_FOUND)

    def tearDown(self):
        delete_index(self.proxy_endpoint, self.index, self.auth)
        delete_document(self.proxy_endpoint, self.index, self.doc_id, self.auth)

    def test_0001_index(self):
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

    def test_0002_document(self):
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

        target_response = retry_request(get_document, args=(self.target_endpoint, self.index, self.doc_id, self.auth),
                                        expected_status_code=HTTPStatus.OK)
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

    def test_0003_jupyterAwake(self):
        # Making sure that the Jupyter notebook is up and can be reached.
        response = requests.get(self.jupyter_endpoint)
        self.assertEqual(response.status_code, HTTPStatus.OK)

    def test_0004_negativeAuth_invalidCreds(self):
        # This test sends negative credentials to the clusters to validate that unauthorized access is prevented.
        alphabet = string.ascii_letters + string.digits
        for _ in range(10):
            username = ''.join(secrets.choice(alphabet) for _ in range(8))
            password = ''.join(secrets.choice(alphabet) for _ in range(8))

            credentials = [
                (username, password),
                (self.username, password),
                (username, self.password)
            ]

            for user, pw in credentials:
                response = requests.get(self.proxy_endpoint, auth=(user, pw), verify=False)
                self.assertEqual(response.status_code, HTTPStatus.UNAUTHORIZED)

    def test_0005_negativeAuth_missingCreds(self):
        # This test will use no credentials at all
        # With an empty authorization header
        response = requests.get(self.proxy_endpoint, auth=('', ''), verify=False)
        self.assertEqual(response.status_code, HTTPStatus.UNAUTHORIZED)

        # Without an authorization header.
        response = requests.get(self.proxy_endpoint, verify=False)
        self.assertEqual(response.status_code, HTTPStatus.UNAUTHORIZED)

    def test_0006_invalidIncorrectUri(self):
        # This test will send an invalid URI
        invalidUri = "/invalidURI"
        response = requests.get(f'{self.proxy_endpoint}{invalidUri}', auth=self.auth, verify=False)
        self.assertEqual(response.status_code, HTTPStatus.NOT_FOUND)

        # This test will send an incorrect URI
        incorrectUri = "/_cluster/incorrectUri"
        response = requests.get(f'{self.proxy_endpoint}{incorrectUri}', auth=self.auth, verify=False)
        self.assertEqual(response.status_code, HTTPStatus.METHOD_NOT_ALLOWED)

    def test_0007_OSB(self):
        cmd = "docker ps | grep 'migrations/migration_console:latest' | awk '{print $NF}'"
        container_name = subprocess.getoutput(cmd).strip()

        if container_name:
            cmd_exec = f"docker exec {container_name} ./runTestBenchmarks.sh"
            logger.warning(f"Running command: {cmd_exec}")
            subprocess.run(cmd_exec, shell=True)
        else:
            logger.error("Migration-console container was not found, please double check that deployment was a success")
            self.assert_(False)

        source_indices = get_indices(self.source_endpoint, self.auth)
        target_indices = get_indices(self.target_endpoint, self.auth)

        for index in set(source_indices) & set(target_indices):
            if index not in self.ignore_list:
                source_count = get_doc_count(self.source_endpoint, index, self.auth)
                target_count = get_doc_count(self.target_endpoint, index, self.auth)

                if source_count != source_count:
                    logger.error(f'{index}: doc counts do not match - Source = {source_count}, Target = {target_count}')
                    self.assertEqual(source_count, target_count)
                else:
                    self.assertEqual(source_count, target_count)
                    logger.info(f'{index}: doc counts match on both source and target endpoints - {source_count}')
