import logging
import pytest
import unittest
from http import HTTPStatus
from console_link.middleware.clusters import run_test_benchmarks, connection_check, clear_indices, ConnectionResult
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from console_link.models.snapshot import Snapshot
from console_link.models.metadata import Metadata
from console_link.cli import Context
from common_operations import (get_document, create_document, create_index, check_doc_counts_match,
                               EXPECTED_BENCHMARK_DOCS)

logger = logging.getLogger(__name__)


def preload_data(source_cluster: Cluster, target_cluster: Cluster):
    # Confirm source and target connection
    source_con_result: ConnectionResult = connection_check(source_cluster)
    assert source_con_result.connection_established is True
    target_con_result: ConnectionResult = connection_check(target_cluster)
    assert target_con_result.connection_established is True

    # Clear any existing non-system indices
    clear_indices(source_cluster)
    clear_indices(target_cluster)

    # Preload data that test cases will verify is migrated
    # test_backfill_0001
    index_name = f"test_backfill_0001_{pytest.unique_id}"
    doc_id = "backfill_0001_doc"
    create_index(cluster=source_cluster, index_name=index_name)
    create_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id,
                    expected_status_code=HTTPStatus.CREATED)

    # test_backfill_0002
    run_test_benchmarks(source_cluster)


@pytest.fixture(scope="class")
def setup_backfill(request):
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    pytest.console_env = Context(config_path).env
    pytest.unique_id = unique_id
    preload_data(source_cluster=pytest.console_env.source_cluster,
                 target_cluster=pytest.console_env.target_cluster)
    backfill: Backfill = pytest.console_env.backfill
    assert backfill is not None
    metadata: Metadata = pytest.console_env.metadata
    assert metadata is not None

    backfill.create()
    snapshot: Snapshot = pytest.console_env.snapshot
    status_result: CommandResult = snapshot.status()
    if status_result.success:
        snapshot.delete()
    snapshot.create(wait=True)
    metadata.migrate()
    backfill.start()
    backfill.scale(units=10)


@pytest.fixture(scope="session", autouse=True)
def cleanup_after_tests():
    # Setup code
    logger.info("Starting backfill tests...")

    yield

    # Teardown code
    logger.info("Stopping backfill...")
    backfill: Backfill = pytest.console_env.backfill
    backfill.stop()


@pytest.mark.usefixtures("setup_backfill")
class BackfillTests(unittest.TestCase):

    def test_backfill_0001_single_document(self):
        index_name = f"test_backfill_0001_{pytest.unique_id}"
        doc_id = "backfill_0001_doc"
        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster

        # Assert preloaded document exists
        get_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id, test_case=self)

        # TODO Determine when backfill is completed

        get_document(cluster=target_cluster, index_name=index_name, doc_id=doc_id, max_attempts=30, delay=30.0,
                     test_case=self)

    def test_backfill_0002_sample_benchmarks(self):
        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster

        # Confirm documents on source
        check_doc_counts_match(cluster=source_cluster, expected_index_details=EXPECTED_BENCHMARK_DOCS,
                               test_case=self)

        # TODO Determine when backfill is completed

        # Confirm documents on target after backfill
        check_doc_counts_match(cluster=target_cluster, expected_index_details=EXPECTED_BENCHMARK_DOCS,
                               max_attempts=40, delay=30.0, test_case=self)
