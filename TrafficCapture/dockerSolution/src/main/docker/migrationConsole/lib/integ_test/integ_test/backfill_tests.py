import logging
import pytest
import json
import unittest
from http import HTTPStatus
from console_link.logic.clusters import call_api, connection_check, clear_indices, run_test_benchmarks, ConnectionResult
from console_link.models.cluster import HttpMethod, Cluster
from console_link.models.backfill_base import Backfill
from console_link.cli import Context

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
    headers = {'Content-Type': 'application/json'}
    data = {'title': 'Test Document', 'content': 'This is a sample document for testing OpenSearch.'}
    call_api(source_cluster, method=HttpMethod.PUT, path=f"/{index_name}")
    call_api(source_cluster, method=HttpMethod.PUT, path=f"/{index_name}/_doc/{doc_id}", data=json.dumps(data),
             headers=headers)

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
    # TODO start backfill


@pytest.mark.usefixtures("setup_backfill")
class BackfillTests(unittest.TestCase):

    def test_backfill_0001_single_document(self):
        index_name = f"test_backfill_0001_{pytest.unique_id}"
        doc_id = "backfill_0001_doc"
        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster

        # Assert preloaded document exists
        source_response = call_api(source_cluster, method=HttpMethod.GET, path=f"/{index_name}/_doc/{doc_id}")
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        # TODO Determine when backfill is completed

        target_response = call_api(target_cluster, method=HttpMethod.GET, path=f"/{index_name}/_doc/{doc_id}")
        self.assertEqual(target_response.status_code, HTTPStatus.OK)

    def test_backfill_0002_sample_benchmarks(self):
        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster
        logger.info(source_cluster)
        logger.info(target_cluster)
        # Assert preloaded benchmark indices exist
        #source_response = call_api(source_cluster, method=HttpMethod.GET, path=f"/{index_name}/_doc/{doc_id}")
        #self.assertEqual(source_response.status_code, HTTPStatus.OK)

        # TODO Determine when backfill is completed

        #target_response = call_api(target_cluster, method=HttpMethod.GET, path=f"/{index_name}/_doc/{doc_id}")
        #self.assertEqual(target_response.status_code, HTTPStatus.OK)
