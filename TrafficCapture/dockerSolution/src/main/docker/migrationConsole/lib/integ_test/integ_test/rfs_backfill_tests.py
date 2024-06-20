import logging
import pytest
import json
import unittest
from http import HTTPStatus
from console_link.logic.clusters import call_api, connection_check, clear_indices
from console_link.models.cluster import HttpMethod, Cluster
from console_link.cli import Context

logger = logging.getLogger(__name__)


def preload_data(source_cluster: Cluster, target_cluster: Cluster):
    # Confirm and source target connection
    source_con_result = connection_check(source_cluster)
    assert source_con_result['connection_established'] is True
    target_con_result = connection_check(target_cluster)
    assert target_con_result['connection_established'] is True

    # Clear any existing non-system indices
    clear_indices(source_cluster)
    clear_indices(target_cluster)

    # Preload data that test cases will verify is migrated
    # test_rfs_0001
    index_name = f"test_rfs_0001_{pytest.unique_id}"
    doc_id = "rfs_0001_doc"
    headers = {'Content-Type': 'application/json'}
    data = {'title': 'Test Document', 'content': 'This is a sample document for testing OpenSearch.'}
    call_api(source_cluster, method=HttpMethod.PUT, path=f"/{index_name}")
    call_api(source_cluster, method=HttpMethod.PUT, path=f"/{index_name}/_doc/{doc_id}", data=json.dumps(data),
             headers=headers)

    # TODO Start backfill



@pytest.fixture(scope="class")
def setup_rfs(request):
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    pytest.console_env = Context(config_path).env
    pytest.unique_id = unique_id
    preload_data(source_cluster=pytest.console_env.source_cluster,
                 target_cluster=pytest.console_env.target_cluster)


@pytest.mark.usefixtures("setup_rfs")
class RfsBackfillTests(unittest.TestCase):

    def test_rfs_0001_single_document(self):
        index_name = f"test_rfs_0001_{pytest.unique_id}"
        doc_id = "rfs_0001_doc"
        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster

        # Assert preloaded document exists
        source_response = call_api(source_cluster, method=HttpMethod.GET, path=f"/{index_name}/_doc/{doc_id}")
        self.assertEqual(source_response.status_code, HTTPStatus.OK)

        # target_response = call_api(target_cluster, method=HttpMethod.GET, path=f"/{index_name}")
        # self.assertEqual(target_response.status_code, HTTPStatus.OK)

        # TODO Verify target contains document after backfill is completed
