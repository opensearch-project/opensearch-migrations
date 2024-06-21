import logging
import pytest
import json
import unittest
from http import HTTPStatus
from console_link.logic.clusters import call_api, connection_check, clear_indices, run_test_benchmarks, ConnectionResult
from console_link.models.cluster import HttpMethod, Cluster
from console_link.models.backfill_base import Backfill
from console_link.cli import Context
from common_operations import *  # NOSONAR


@pytest.fixture(scope="class")
def setup_replayer(request):
    config_path = request.config.getoption("--config_file_path")
    unique_id = request.config.getoption("--unique_id")
    pytest.console_env = Context(config_path).env
    pytest.unique_id = unique_id
    # TODO start replayer


@pytest.fixture(scope="session", autouse=True)
def cleanup(request):
    """Cleanup a testing directory once we are finished."""

    def post_cleanup():
        print("Cleanup")

    request.addfinalizer(post_cleanup())


@pytest.mark.usefixtures("setup_replayer")
class ReplayerTests(unittest.TestCase):

    def test_replayer_0001_empty_index(self):
        # This test will verify that an index will be created (then deleted) on the target cluster when one is created
        # on the source cluster by going through the proxy first. It will verify that the traffic is captured by the
        # proxy and that the traffic reaches the source cluster, replays said traffic to the target cluster by the
        # replayer.

        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster
        index_name = f"test_replayer_0001_{pytest.unique_id}"

        create_index(cluster=source_cluster, index_name=index_name)
        get_index(cluster=source_cluster, index_name=index_name)
        get_index(cluster=target_cluster, index_name=index_name)
        delete_index(cluster=source_cluster, index_name=index_name)
        get_index(cluster=source_cluster, index_name=index_name, desired_status_code=HTTPStatus.NOT_FOUND)
        get_index(cluster=target_cluster, index_name=index_name, desired_status_code=HTTPStatus.NOT_FOUND)

    def test_replayer_0002_single_document(self):
        # This test will verify that a document will be created (then deleted) on the target cluster when one is created
        # on the source cluster by going through the proxy first. It will verify that the traffic is captured by the
        # proxy and that the traffic reaches the source cluster, replays said traffic to the target cluster by the
        # replayer.

        source_cluster: Cluster = pytest.console_env.source_cluster
        target_cluster: Cluster = pytest.console_env.target_cluster
        index_name = f"test_replayer_0002_{pytest.unique_id}"
        doc_id = "replayer_0002_doc"

        create_index(cluster=source_cluster, index_name=index_name)
        get_index(cluster=source_cluster, index_name=index_name)
        get_index(cluster=target_cluster, index_name=index_name)
        create_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id)
        check_doc_match(test_case=self, source_cluster=source_cluster, target_cluster=target_cluster,
                        index_name=index_name, doc_id=doc_id)
        delete_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id)
        get_document(cluster=source_cluster, index_name=index_name, doc_id=doc_id,
                     desired_status_code=HTTPStatus.NOT_FOUND)
        get_document(cluster=target_cluster, index_name=index_name, doc_id=doc_id,
                     desired_status_code=HTTPStatus.NOT_FOUND)
        delete_index(cluster=source_cluster, index_name=index_name)
        get_index(cluster=source_cluster, index_name=index_name, desired_status_code=HTTPStatus.NOT_FOUND)
        get_index(cluster=target_cluster, index_name=index_name, desired_status_code=HTTPStatus.NOT_FOUND)
