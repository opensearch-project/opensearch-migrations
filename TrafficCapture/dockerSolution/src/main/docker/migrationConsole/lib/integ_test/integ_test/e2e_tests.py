import logging
import pytest
import unittest
from http import HTTPStatus
from console_link.middleware.clusters import run_test_benchmarks, connection_check, clear_indices, ConnectionResult
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
from console_link.models.replayer_base import Replayer
from console_link.models.kafka import Kafka
from console_link.models.command_result import CommandResult
from console_link.models.snapshot import Snapshot
from console_link.middleware.kafka import delete_topic
from console_link.models.metadata import Metadata
from console_link.cli import Context
from common_operations import (create_index, create_document, check_doc_counts_match, wait_for_running_replayer,
                               EXPECTED_BENCHMARK_DOCS)
logger = logging.getLogger(__name__)


@pytest.fixture(scope="class")
def initialize(request):
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    pytest.console_env = Context(config_path).env
    pytest.unique_id = unique_id
    source_cluster = pytest.console_env.source_cluster
    target_cluster = pytest.console_env.target_cluster
    backfill: Backfill = pytest.console_env.backfill
    assert backfill is not None
    metadata: Metadata = pytest.console_env.metadata
    assert metadata is not None
    replayer: Replayer = pytest.console_env.replay
    assert replayer is not None
    kafka: Kafka = pytest.console_env.kafka

    # Confirm source and target connection
    source_con_result: ConnectionResult = connection_check(source_cluster)
    assert source_con_result.connection_established is True
    target_con_result: ConnectionResult = connection_check(target_cluster)
    assert target_con_result.connection_established is True

    # Clear any existing non-system indices
    clear_indices(source_cluster)
    clear_indices(target_cluster)

    # Delete existing Kafka topic to clear records
    delete_topic(kafka=kafka, topic_name="logging-traffic-topic")


@pytest.fixture(scope="session", autouse=True)
def cleanup_after_tests():
    # Setup code
    logger.info("Starting E2E tests...")

    yield

    # Teardown code
    logger.info("Stopping services...")
    backfill: Backfill = pytest.console_env.backfill
    backfill.stop()

    replayer: Replayer = pytest.console_env.replay
    replayer.stop()


@pytest.mark.usefixtures("initialize")
class E2ETests(unittest.TestCase):

    def test_e2e_0001_default(self):
        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster
        backfill: Backfill = pytest.console_env.backfill
        metadata: Metadata = pytest.console_env.metadata
        replayer: Replayer = pytest.console_env.replay

        # Load initial data
        run_test_benchmarks(source_cluster)
        index_name = f"test_e2e_0001_{pytest.unique_id}"
        doc_id_base = "e2e_0001_doc"
        create_index(cluster=source_cluster, index_name=index_name, test_case=self)
        create_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id_base + "_1",
                        expected_status_code=HTTPStatus.CREATED, test_case=self)

        # Perform metadata and backfill migrations
        backfill.create()
        snapshot: Snapshot = pytest.console_env.snapshot
        status_result: CommandResult = snapshot.status()
        if status_result.success:
            snapshot.delete()
        snapshot.create(wait=True)
        metadata.migrate()
        backfill.start()
        backfill.scale(units=10)
        # This document was created after snapshot and should not be included in Backfill but expected in Replay
        create_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id_base + "_2",
                        expected_status_code=HTTPStatus.CREATED, test_case=self)

        ignore_list = [".", "searchguard", "sg7", "security-auditlog", "reindexed-logs"]
        expected_docs = dict(EXPECTED_BENCHMARK_DOCS)
        # Source should have both documents
        expected_docs[index_name] = {"docs.count": "2"}
        check_doc_counts_match(cluster=source_cluster, expected_index_details=expected_docs,
                               index_prefix_ignore_list=ignore_list, test_case=self)
        # Target should have one document from snapshot
        expected_docs[index_name] = {"docs.count": "1"}
        check_doc_counts_match(cluster=target_cluster, expected_index_details=expected_docs,
                               index_prefix_ignore_list=ignore_list, max_attempts=40, delay=30.0, test_case=self)

        backfill.stop()

        create_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id_base + "_3",
                        expected_status_code=HTTPStatus.CREATED, test_case=self)

        replayer.start()
        wait_for_running_replayer(replayer=replayer)

        expected_docs[index_name] = {"docs.count": "3"}
        check_doc_counts_match(cluster=source_cluster, expected_index_details=expected_docs,
                               index_prefix_ignore_list=ignore_list, test_case=self)

        check_doc_counts_match(cluster=target_cluster, expected_index_details=expected_docs,
                               index_prefix_ignore_list=ignore_list, max_attempts=30, delay=10.0, test_case=self)
