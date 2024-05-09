import boto3
import json
import logging
import pytest
import requests
import secrets
import string
import subprocess
import time
import unittest
from http import HTTPStatus
from requests import Session
from requests.adapters import HTTPAdapter
from requests.exceptions import ConnectionError, SSLError
from requests_aws4auth import AWS4Auth
from typing import Tuple, Callable

from operations import create_index, check_index, create_document, \
    delete_document, delete_index, get_document

logger = logging.getLogger(__name__)


def get_indices(endpoint, auth, verify):
    response = requests.get(f'{endpoint}/_cat/indices', auth=auth, verify=verify)
    indices = []
    response_lines = response.text.strip().split('\n')
    for line in response_lines:
        parts = line.split()
        index_name = parts[2]
        indices.append(index_name)
    return indices


def get_doc_count(endpoint, index, auth, verify):
    response = requests.get(f'{endpoint}/{index}/_count', auth=auth, verify=verify)
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

    @pytest.fixture(autouse=True)
    def init_fixtures(self, proxy_endpoint, source_endpoint, target_endpoint, source_auth_type, source_username,
                      source_password, target_auth_type, target_username, target_password, target_verify_ssl,
                      source_verify_ssl, deployment_type, unique_id):
        self.proxy_endpoint = proxy_endpoint
        self.source_endpoint = source_endpoint
        self.target_endpoint = target_endpoint
        self.source_auth_type = source_auth_type
        self.source_auth = self.setup_authentication(source_auth_type, source_username, source_password)
        self.source_username = source_username
        self.source_password = source_password
        self.target_auth_type = target_auth_type
        self.target_auth = self.setup_authentication(target_auth_type, target_username, target_password)
        self.target_username = target_username
        self.target_password = target_password
        self.source_verify_ssl = source_verify_ssl.lower() == 'true'
        self.target_verify_ssl = target_verify_ssl.lower() == 'true'
        self.deployment_type = deployment_type
        self.unique_id = unique_id

    def setup_authentication(self, auth_type, username, password):
        if auth_type == "basic":
            return (username, password)
        elif auth_type == "sigv4":
            session = boto3.Session()
            credentials = session.get_credentials()
            aws_auth = AWS4Auth(credentials.access_key, credentials.secret_key, session.region_name, 'es',
                                session_token=credentials.token)
            return aws_auth
        return None

    def does_index_match_ignored_index(self, index_name: str):
        for prefix in self.index_prefix_ignore_list:
            if index_name.startswith(prefix):
                return True
        return False

    def assert_source_target_doc_match(self, index_name, doc_id):
        source_response = get_document(self.source_endpoint, index_name, doc_id, self.source_auth,
                                       self.source_verify_ssl)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        target_response = retry_request(get_document, args=(self.target_endpoint, index_name, doc_id,
                                                            self.target_auth, self.target_verify_ssl),
                                        expected_status_code=HTTPStatus.OK)
        self.assertEqual(target_response.status_code, HTTPStatus.OK)

        # Comparing the document's content on both endpoints, asserting
        # that they match.
        source_document = source_response.json()
        source_content = source_document['_source']
        target_document = target_response.json()
        target_content = target_document['_source']
        self.assertEqual(source_content, target_content)

    def set_common_values(self):
        self.index_prefix_ignore_list = ["test_", ".", "searchguard", "sg7", "security-auditlog"]

    def setUp(self):
        self.set_common_values()

    def test_0001_index(self):
        # This test will verify that an index will be created (then deleted) on the target cluster when one is created
        # on the source cluster by going through the proxy first. It will verify that the traffic is captured by the
        # proxy and that the traffic reaches the source cluster, replays said traffic to the target cluster by the
        # replayer.

        index_name = f"test_0001_{self.unique_id}"
        # Creating an index, then asserting that the index was created on both targets.
        proxy_response = retry_request(create_index, args=(self.proxy_endpoint, index_name, self.source_auth,
                                                           self.source_verify_ssl),
                                       expected_status_code=HTTPStatus.OK)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = retry_request(check_index, args=(self.target_endpoint, index_name, self.target_auth,
                                                           self.target_verify_ssl),
                                        expected_status_code=HTTPStatus.OK)
        self.assertEqual(target_response.status_code, HTTPStatus.OK)
        source_response = retry_request(check_index, args=(self.source_endpoint, index_name, self.source_auth,
                                                           self.source_verify_ssl),
                                        expected_status_code=HTTPStatus.OK)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        proxy_response = retry_request(delete_index, args=(self.proxy_endpoint, index_name, self.source_auth,
                                                           self.source_verify_ssl),
                                       expected_status_code=HTTPStatus.OK)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = retry_request(check_index, args=(self.target_endpoint, index_name, self.target_auth,
                                                           self.target_verify_ssl),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = retry_request(check_index, args=(self.source_endpoint, index_name, self.source_auth,
                                                           self.source_verify_ssl),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(source_response.status_code, HTTPStatus.NOT_FOUND)

    def test_0002_document(self):
        # This test will verify that a document will be created (then deleted) on the target cluster when one is created
        # on the source cluster by going through the proxy first. It will verify that the traffic is captured by the
        # proxy and that the traffic reaches the source cluster, replays said traffic to the target cluster by the
        # replayer.

        index_name = f"test_0002_{self.unique_id}"
        doc_id = "7"
        # Creating an index, then asserting that the index was created on both targets.
        proxy_response = retry_request(create_index, args=(self.proxy_endpoint, index_name, self.source_auth,
                                                           self.source_verify_ssl),
                                       expected_status_code=HTTPStatus.OK)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = retry_request(check_index, args=(self.target_endpoint, index_name, self.target_auth,
                                                           self.target_verify_ssl),
                                        expected_status_code=HTTPStatus.OK)
        self.assertEqual(target_response.status_code, HTTPStatus.OK)
        source_response = retry_request(check_index, args=(self.source_endpoint, index_name, self.source_auth,
                                                           self.source_verify_ssl),
                                        expected_status_code=HTTPStatus.OK)
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        # Creating a document, then asserting that the document was created on both targets.
        proxy_response = create_document(self.proxy_endpoint, index_name, doc_id, self.source_auth,
                                         self.source_verify_ssl)
        self.assertEqual(proxy_response.status_code, HTTPStatus.CREATED)

        self.assert_source_target_doc_match(index_name, doc_id)

        # Deleting the document that was created then asserting that it was deleted on both targets.
        proxy_response = delete_document(self.proxy_endpoint, index_name, doc_id, self.source_auth,
                                         self.source_verify_ssl)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = retry_request(get_document, args=(self.target_endpoint, index_name, doc_id,
                                                            self.target_auth, self.target_verify_ssl),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = retry_request(get_document, args=(self.source_endpoint, index_name, doc_id,
                                                            self.source_auth, self.source_verify_ssl),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(source_response.status_code, HTTPStatus.NOT_FOUND)

        # Deleting the index that was created then asserting that it was deleted on both targets.
        proxy_response = delete_index(self.proxy_endpoint, index_name, self.source_auth, self.source_verify_ssl)
        self.assertEqual(proxy_response.status_code, HTTPStatus.OK)

        target_response = retry_request(check_index, args=(self.target_endpoint, index_name, self.target_auth,
                                                           self.target_verify_ssl),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(target_response.status_code, HTTPStatus.NOT_FOUND)
        source_response = retry_request(check_index, args=(self.source_endpoint, index_name, self.source_auth,
                                                           self.source_verify_ssl),
                                        expected_status_code=HTTPStatus.NOT_FOUND)
        self.assertEqual(source_response.status_code, HTTPStatus.NOT_FOUND)

    def test_0003_negativeAuth_invalidCreds(self):
        # This test sends negative credentials to the clusters to validate that unauthorized access is prevented.
        alphabet = string.ascii_letters + string.digits
        for _ in range(10):
            username = ''.join(secrets.choice(alphabet) for _ in range(8))
            password = ''.join(secrets.choice(alphabet) for _ in range(8))

            credentials = [
                (username, password),
                (self.source_username, password),
                (username, self.source_password)
            ]

            for user, pw in credentials:
                response = requests.get(self.proxy_endpoint, auth=(user, pw), verify=self.source_verify_ssl)
                self.assertEqual(response.status_code, HTTPStatus.UNAUTHORIZED)

    def test_0004_negativeAuth_missingCreds(self):
        # This test will use no credentials at all
        # With an empty authorization header
        response = requests.get(self.proxy_endpoint, auth=('', ''), verify=self.source_verify_ssl)
        self.assertEqual(response.status_code, HTTPStatus.UNAUTHORIZED)

        # Without an authorization header.
        response = requests.get(self.proxy_endpoint, verify=self.source_verify_ssl)
        self.assertEqual(response.status_code, HTTPStatus.UNAUTHORIZED)

    def test_0005_invalidIncorrectUri(self):
        # This test will send an invalid URI
        invalidUri = "/invalidURI"
        response = requests.get(f'{self.proxy_endpoint}{invalidUri}', auth=self.source_auth,
                                verify=self.source_verify_ssl)
        self.assertEqual(response.status_code, HTTPStatus.NOT_FOUND)

        # This test will send an incorrect URI
        incorrectUri = "/_cluster/incorrectUri"
        response = requests.get(f'{self.proxy_endpoint}{incorrectUri}', auth=self.source_auth,
                                verify=self.source_verify_ssl)
        self.assertEqual(response.status_code, HTTPStatus.METHOD_NOT_ALLOWED)

    def test_0006_OSB(self):
        if self.deployment_type == "cloud":
            cmd_exec = f"/root/runTestBenchmarks.sh --endpoint {self.proxy_endpoint}"
            if self.source_auth_type == "none":
                cmd_exec = cmd_exec + " --no-auth"
            elif self.source_auth_type == "basic":
                cmd_exec = cmd_exec + f" --auth-user {self.source_username} --auth-pass {self.source_password}"
            logger.warning(f"Running local command: {cmd_exec}")
            subprocess.run(cmd_exec, shell=True)
            # TODO: Enhance our waiting logic for determining when all OSB records have been processed by Replayer
            time.sleep(360)
        else:
            cmd = ['docker', 'ps', '--format="{{.ID}}"', '--filter', 'name=migration']
            container_id = subprocess.run(cmd, stdout=subprocess.PIPE, text=True).stdout.strip().replace('"', '')

            if container_id:
                cmd_exec = f"docker exec {container_id} ./runTestBenchmarks.sh"
                logger.warning(f"Running command: {cmd_exec}")
                subprocess.run(cmd_exec, shell=True)
                time.sleep(5)
            else:
                logger.error("Migration-console container was not found,"
                             " please double check that deployment was a success")
                self.assert_(False)

        source_indices = get_indices(self.source_endpoint, self.source_auth, self.source_verify_ssl)
        valid_source_indices = set([index for index in source_indices
                                    if not self.does_index_match_ignored_index(index)])
        target_indices = get_indices(self.target_endpoint, self.target_auth, self.target_verify_ssl)
        valid_target_indices = set([index for index in target_indices
                                    if not self.does_index_match_ignored_index(index)])

        self.assertTrue(valid_source_indices, "No valid indices found on source after running OpenSearch Benchmark")
        self.assertEqual(valid_source_indices, valid_target_indices,
                         f"Valid indices for source and target are not equal - Source = {valid_source_indices}, "
                         f"Target = {valid_target_indices}")

        for index in valid_source_indices:
            source_count = get_doc_count(self.source_endpoint, index, self.source_auth, self.source_verify_ssl)
            target_count = get_doc_count(self.target_endpoint, index, self.target_auth, self.target_verify_ssl)
            if source_count != target_count:
                self.assertEqual(source_count, target_count, f'{index}: doc counts do not match - '
                                                             f'Source = {source_count}, Target = {target_count}')

    def test_0007_timeBetweenRequestsOnSameConnection(self):
        # This test will verify that the replayer functions correctly when
        # requests on the same connection on the proxy that has a minute gap
        seconds_between_requests = 60  # 1 minute

        proxy_single_connection_session = Session()
        adapter = HTTPAdapter(pool_connections=1, pool_maxsize=1, max_retries=1)
        proxy_single_connection_session.mount(self.proxy_endpoint, adapter)

        index_name = f"test_0007_{self.unique_id}"

        number_of_docs = 3

        for doc_id_int in range(number_of_docs):
            doc_id = str(doc_id_int)
            proxy_response = create_document(self.proxy_endpoint, index_name, doc_id, self.source_auth,
                                             self.source_verify_ssl, proxy_single_connection_session)
            self.assertEqual(proxy_response.status_code, HTTPStatus.CREATED)

            if doc_id_int + 1 < number_of_docs:
                time.sleep(seconds_between_requests)

        try:
            for doc_id_int in range(number_of_docs):
                doc_id = str(doc_id_int)
                self.assert_source_target_doc_match(index_name, doc_id)
        finally:
            proxy_single_connection_session.close()
