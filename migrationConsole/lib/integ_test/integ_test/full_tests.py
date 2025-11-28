import json
import logging
import os
import pytest
import unittest
from http import HTTPStatus

from console_link.middleware.clusters import connection_check, clear_cluster, ConnectionResult
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
from console_link.models.replayer_base import Replayer, ReplayStatus
from console_link.models.kafka import Kafka
from console_link.models.command_result import CommandResult
from console_link.models.snapshot import Snapshot
from console_link.middleware.kafka import delete_topic
from console_link.models.metadata import Metadata
from console_link.cli import Context
from .common_utils import wait_for_service_status
from .default_operations import DefaultOperationsLibrary

logger = logging.getLogger(__name__)
ops = DefaultOperationsLibrary()


@pytest.fixture(scope="class")
def initialize(request):
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    pytest.console_env = Context(config_path).env
    pytest.unique_id = unique_id
    source_cluster = pytest.console_env.source_cluster
    target_cluster = pytest.console_env.target_cluster
    # If in AWS, modify source and target objects here to route requests through the created ALB to verify its operation
    if 'AWS_EXECUTION_ENV' in os.environ:
        logger.info("Detected an AWS environment")
        source_proxy_alb_endpoint = request.config.getoption("--source_proxy_alb_endpoint")
        target_proxy_alb_endpoint = request.config.getoption("--target_proxy_alb_endpoint")
        logger.info("Checking original source and target endpoints can be reached, before using ALB endpoints for test")
        direct_source_con_result: ConnectionResult = connection_check(source_cluster)
        assert direct_source_con_result.connection_established is True
        direct_target_con_result: ConnectionResult = connection_check(target_cluster)
        assert direct_target_con_result.connection_established is True
        source_cluster.endpoint = source_proxy_alb_endpoint
        target_cluster.endpoint = target_proxy_alb_endpoint
        target_cluster.allow_insecure = True
    backfill: Backfill = pytest.console_env.backfill
    assert backfill is not None
    metadata: Metadata = pytest.console_env.metadata
    assert metadata is not None
    replayer: Replayer = pytest.console_env.replay
    assert replayer is not None
    kafka: Kafka = pytest.console_env.kafka
    snapshot: Snapshot = pytest.console_env.snapshot
    assert snapshot is not None

    # Confirm source and target connection
    source_con_result: ConnectionResult = connection_check(source_cluster)
    assert source_con_result.connection_established is True
    target_con_result: ConnectionResult = connection_check(target_cluster)
    assert target_con_result.connection_established is True

    # Clear Cluster
    clear_cluster(source_cluster, snapshot)
    clear_cluster(target_cluster)

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
        # Populate with https://opensearch.atlassian.net/browse/MIGRATIONS-2382
        source_major_version = 6
        source_minor_version = 8
        target_cluster: Cluster = pytest.console_env.target_cluster

        backfill: Backfill = pytest.console_env.backfill
        metadata: Metadata = pytest.console_env.metadata
        replayer: Replayer = pytest.console_env.replay

        # Load initial data
        index_name = f"test_e2e_0001_{pytest.unique_id}"
        transformed_index_name = f"{index_name}_transformed"
        doc_id_base = "e2e_0001_doc"
        index_body = {
            'settings': {
                'index': {
                    'number_of_shards': 3,
                    'number_of_replicas': 0
                }
            }
        }
        ops.create_index(cluster=source_cluster, index_name=index_name, data=json.dumps(index_body), test_case=self)
        ops.create_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id_base + "_1",
                            expected_status_code=HTTPStatus.CREATED, test_case=self)

        backfill.create()
        snapshot: Snapshot = pytest.console_env.snapshot
        snapshot_result = snapshot.create(wait=True)
        assert "creation initiated successfully" in snapshot_result

        # Perform metadata migration with a transform to index name
        index_name_transform = ops.get_index_name_transformation(existing_index_name=index_name,
                                                                 target_index_name=transformed_index_name,
                                                                 source_major_version=source_major_version,
                                                                 source_minor_version=source_minor_version)
        transform_arg = ops.convert_transformations_to_str(transform_list=[index_name_transform])
        metadata_result: CommandResult = metadata.migrate(extra_args=["--transformer-config", transform_arg])
        assert metadata_result.success

        backfill_start_result: CommandResult = backfill.start()
        assert backfill_start_result.success
        # small enough to allow containers to be reused, big enough to test scaling out
        backfill_scale_result: CommandResult = backfill.scale(units=2)
        assert backfill_scale_result.success
        # This document was created after snapshot and should not be included in Backfill but expected in Replay
        ops.create_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id_base + "_2",
                            expected_status_code=HTTPStatus.CREATED, test_case=self)

        ignore_list = [".", "searchguard", "sg7", "security-auditlog", "reindexed-logs"]
        expected_source_docs = {}
        expected_target_docs = {}
        # Source should have both documents
        expected_source_docs[index_name] = {"count": 2}
        ops.check_doc_counts_match(cluster=source_cluster, expected_index_details=expected_source_docs,
                                   index_prefix_ignore_list=ignore_list, test_case=self)
        # Target should have one document from snapshot
        expected_target_docs[transformed_index_name] = {"count": 1}
        ops.check_doc_counts_match(cluster=target_cluster, expected_index_details=expected_target_docs,
                                   index_prefix_ignore_list=ignore_list, max_attempts=20, delay=30.0, test_case=self)

        backfill.stop()

        ops.create_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id_base + "_3",
                            expected_status_code=HTTPStatus.CREATED, test_case=self)

        replayer.start()
        wait_for_service_status(status_func=lambda: replayer.get_status(), desired_status=ReplayStatus.RUNNING)

        expected_source_docs[index_name] = {"count": 3}
        # TODO Replayer transformation needed to only have docs in the transformed index
        expected_target_docs[index_name] = {"count": 3}
        ops.check_doc_counts_match(cluster=source_cluster, expected_index_details=expected_source_docs,
                                   index_prefix_ignore_list=ignore_list, test_case=self)
        ops.check_doc_counts_match(cluster=target_cluster, expected_index_details=expected_target_docs,
                                   index_prefix_ignore_list=ignore_list, max_attempts=30, delay=10.0, test_case=self)
