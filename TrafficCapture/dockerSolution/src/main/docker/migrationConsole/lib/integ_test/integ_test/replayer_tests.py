import pytest
import unittest
import requests
import logging
import secrets
import string
import time
from http import HTTPStatus
from requests import Session
from requests.adapters import HTTPAdapter
from console_link.models.replayer_base import Replayer, ReplayStatus
from console_link.middleware.kafka import delete_topic
from console_link.models.kafka import Kafka
from console_link.middleware.clusters import connection_check, clear_cluster, run_test_benchmarks, ConnectionResult
from console_link.models.cluster import Cluster, AuthMethod
from console_link.cli import Context

from .common_utils import execute_api_call, wait_for_service_status, EXPECTED_BENCHMARK_DOCS
from .default_operations import DefaultOperationsLibrary
from .metric_operations import assert_metrics_present

logger = logging.getLogger(__name__)
ops = DefaultOperationsLibrary()


@pytest.fixture(scope="class")
def setup_replayer(request):
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    pytest.console_env = Context(config_path).env
    pytest.unique_id = unique_id
    source_cluster: Cluster = pytest.console_env.source_cluster
    target_cluster: Cluster = pytest.console_env.target_cluster
    kafka: Kafka = pytest.console_env.kafka
    replayer: Replayer = pytest.console_env.replay
    assert replayer is not None

    # Confirm source and target connection
    source_con_result: ConnectionResult = connection_check(source_cluster)
    assert source_con_result.connection_established is True
    target_con_result: ConnectionResult = connection_check(target_cluster)
    assert target_con_result.connection_established is True

    # Clear Cluster
    clear_cluster(source_cluster)
    clear_cluster(target_cluster)

    # Delete existing Kafka topic to clear records
    delete_topic(kafka=kafka, topic_name="logging-traffic-topic")

    logger.info("Starting replayer...")
    # TODO provide support for actually starting/stopping Replayer in Docker
    replayer.start()
    wait_for_service_status(status_func=lambda: replayer.get_status(), desired_status=ReplayStatus.RUNNING)


@pytest.fixture(scope="session", autouse=True)
def cleanup_after_tests():
    # Setup code
    logger.info("Starting replayer tests...")

    yield

    # Teardown code
    logger.info("Stopping replayer...")
    replayer: Replayer = pytest.console_env.replay
    assert replayer is not None
    replayer.stop()


@pytest.mark.usefixtures("setup_replayer")
class ReplayerTests(unittest.TestCase):

    @assert_metrics_present({
        'captureProxy': ['kafkaCommitCount_total'],
        'replayer': ['kafkaCommitCount_total']
    })
    def test_replayer_0001_empty_index(self):
        # This test will verify that an index will be created (then deleted) on the target cluster when one is created
        # on the source cluster by going through the proxy first. It will verify that the traffic is captured by the
        # proxy and that the traffic reaches the source cluster, replays said traffic to the target cluster by the
        # replayer.

        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster
        index_name = f"test_replayer_0001_{pytest.unique_id}"

        ops.create_index(cluster=source_cluster, index_name=index_name, test_case=self)
        ops.get_index(cluster=source_cluster, index_name=index_name, test_case=self)
        ops.get_index(cluster=target_cluster, index_name=index_name, test_case=self)
        ops.delete_index(cluster=source_cluster, index_name=index_name, test_case=self)
        ops.get_index(cluster=source_cluster, index_name=index_name, expected_status_code=HTTPStatus.NOT_FOUND,
                      test_case=self)
        ops.get_index(cluster=target_cluster, index_name=index_name, expected_status_code=HTTPStatus.NOT_FOUND,
                      test_case=self)

    @unittest.skip("Flaky test - https://opensearch.atlassian.net/browse/MIGRATIONS-1925")
    def test_replayer_0002_single_document(self):
        # This test will verify that a document will be created (then deleted) on the target cluster when one is created
        # on the source cluster by going through the proxy first. It will verify that the traffic is captured by the
        # proxy and that the traffic reaches the source cluster, replays said traffic to the target cluster by the
        # replayer.

        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster
        index_name = f"test_replayer_0002_{pytest.unique_id}"
        doc_id = "replayer_0002_doc"

        ops.create_index(cluster=source_cluster, index_name=index_name, test_case=self)
        ops.get_index(cluster=source_cluster, index_name=index_name, test_case=self)
        ops.get_index(cluster=target_cluster, index_name=index_name, test_case=self)
        ops.create_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id,
                            expected_status_code=HTTPStatus.CREATED, test_case=self)
        ops.check_doc_match(source_cluster=source_cluster, target_cluster=target_cluster,
                            index_name=index_name, doc_id=doc_id, test_case=self)
        ops.delete_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id, test_case=self)
        ops.get_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id,
                         expected_status_code=HTTPStatus.NOT_FOUND, test_case=self)
        ops.get_document(cluster=target_cluster, index_name=index_name, doc_id=doc_id,
                         expected_status_code=HTTPStatus.NOT_FOUND, test_case=self)
        ops.delete_index(cluster=source_cluster, index_name=index_name)
        ops.get_index(cluster=source_cluster, index_name=index_name, expected_status_code=HTTPStatus.NOT_FOUND,
                      test_case=self)
        ops.get_index(cluster=target_cluster, index_name=index_name, expected_status_code=HTTPStatus.NOT_FOUND,
                      test_case=self)

    def test_replayer_0003_negativeAuth_invalidCreds(self):
        # This test sends negative credentials to the clusters to validate that unauthorized access is prevented.
        source_cluster: Cluster = pytest.console_env.source_cluster
        if source_cluster.auth_type != AuthMethod.BASIC_AUTH or source_cluster.auth_details['password'] is None:
            self.skipTest("Test case is only valid for a basic auth source cluster with username and password")

        alphabet = string.ascii_letters + string.digits
        for _ in range(10):
            username = ''.join(secrets.choice(alphabet) for _ in range(8))
            password = ''.join(secrets.choice(alphabet) for _ in range(8))

            credentials = [
                (username, password),
                (source_cluster.auth_details['username'], password),
                (username, source_cluster.auth_details['password'])
            ]

            for user, pw in credentials:
                response = requests.get(source_cluster.endpoint, auth=(user, pw),
                                        verify=not source_cluster.allow_insecure)
                self.assertEqual(response.status_code, HTTPStatus.UNAUTHORIZED)

    def test_replayer_0004_negativeAuth_missingCreds(self):
        # This test will use no credentials at all
        source_cluster: Cluster = pytest.console_env.source_cluster
        if source_cluster.auth_type != AuthMethod.BASIC_AUTH:
            self.skipTest("Test case is only valid for a basic auth source cluster")

        # With an empty authorization header
        response_with_header = requests.get(source_cluster.endpoint, auth=('', ''),
                                            verify=not source_cluster.allow_insecure)
        self.assertEqual(response_with_header.status_code, HTTPStatus.UNAUTHORIZED)

        # Without an authorization header.
        response_no_header = requests.get(source_cluster.endpoint, verify=not source_cluster.allow_insecure)
        self.assertEqual(response_no_header.status_code, HTTPStatus.UNAUTHORIZED)

    def test_replayer_0005_invalidIncorrectUri(self):
        # This test will send an invalid URI
        source_cluster: Cluster = pytest.console_env.source_cluster
        invalid_uri = "/invalidURI"
        execute_api_call(source_cluster, path=invalid_uri, expected_status_code=HTTPStatus.NOT_FOUND, test_case=self)

        # This test will send an incorrect URI
        incorrect_uri = "/_cluster/incorrectUri"
        execute_api_call(source_cluster, path=incorrect_uri, expected_status_code=HTTPStatus.METHOD_NOT_ALLOWED,
                         test_case=self)

    def test_replayer_0006_OSB(self):
        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster

        run_test_benchmarks(cluster=source_cluster)
        # Confirm documents on source
        ops.check_doc_counts_match(cluster=source_cluster, expected_index_details=EXPECTED_BENCHMARK_DOCS,
                                   test_case=self)
        # Confirm documents on target after replay
        ops.check_doc_counts_match(cluster=target_cluster, expected_index_details=EXPECTED_BENCHMARK_DOCS,
                                   test_case=self)

    def test_replayer_0007_timeBetweenRequestsOnSameConnection(self):
        # This test will verify that the replayer functions correctly when
        # requests on the same connection on the proxy that has a minute gap
        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster
        seconds_between_requests = 60  # 1 minute

        proxy_single_connection_session = Session()
        adapter = HTTPAdapter(pool_connections=1, pool_maxsize=1, max_retries=1)
        proxy_single_connection_session.mount(source_cluster.endpoint, adapter)

        index_name = f"test_replayer_0007_{pytest.unique_id}"

        number_of_docs = 3

        for doc_id_int in range(number_of_docs):
            doc_id = str(doc_id_int)
            ops.create_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id,
                                expected_status_code=HTTPStatus.CREATED, session=proxy_single_connection_session,
                                test_case=self)
            if doc_id_int + 1 < number_of_docs:
                time.sleep(seconds_between_requests)

        try:
            for doc_id_int in range(number_of_docs):
                doc_id = str(doc_id_int)
                ops.check_doc_match(source_cluster=source_cluster, target_cluster=target_cluster,
                                    index_name=index_name, doc_id=doc_id, test_case=self)
        finally:
            proxy_single_connection_session.close()

    @unittest.skip("Flaky test needs resolution")
    def test_replayer_0008_largeRequest(self):
        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster
        index_name = f"test_replayer_0008_{pytest.unique_id}"
        doc_id = "replayer_0008_doc"

        # Create large document, 99MiB
        # Default max 100MiB in ES/OS settings (http.max_content_length)
        large_doc = ops.generate_large_doc(size_mib=99)

        # Measure the time taken by the create_document call
        # Send large request to proxy and verify response
        start_time = time.time()
        ops.create_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id, data=large_doc,
                            expected_status_code=HTTPStatus.CREATED, test_case=self)
        end_time = time.time()
        duration = end_time - start_time

        # Set wait time to double the response time or 5 seconds
        wait_time_seconds = min(round(duration, 3) * 2, 5)

        # Wait for the measured duration
        logger.debug(f"Waiting {wait_time_seconds} seconds for"
                     f" replay of large doc creation")

        time.sleep(wait_time_seconds)

        # Verify document created on source and target
        ops.check_doc_match(source_cluster=source_cluster, target_cluster=target_cluster, index_name=index_name,
                            doc_id=doc_id, test_case=self)
