import logging
import pytest
import unittest
from http import HTTPStatus
from console_link.middleware.clusters import connection_check, clear_indices, ConnectionResult
from console_link.models.cluster import Cluster
from console_link.models.backfill_base import Backfill
from console_link.models.command_result import CommandResult
from console_link.models.snapshot import Snapshot
from console_link.models.metadata import Metadata
from console_link.cli import Context
from common_operations import (get_document, create_document, create_index)

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
    snapshot_result: CommandResult = snapshot.create(wait=True)
    assert snapshot_result.success
    metadata_result: CommandResult = metadata.migrate()
    assert metadata_result.success
    backfill_start_result: CommandResult = backfill.start()
    assert backfill_start_result.success
    # small enough to allow containers to be reused, big enough to test scaling out
    backfill_scale_result: CommandResult = backfill.scale(units=2)
    assert backfill_scale_result.success


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

        get_document(cluster=target_cluster, index_name=index_name, doc_id=doc_id, max_attempts=30, delay=30.0,
                     test_case=self)
