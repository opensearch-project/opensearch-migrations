import itertools
import logging
import os
import tempfile
import json

import yaml
import pytest

from console_link.environment import Environment
from console_link.models.cluster import Cluster, HttpMethod
from console_link.models.utils import DEFAULT_SNAPSHOT_REPO_NAME
from tests.search_containers import SearchContainer, Version, CLUSTER_SNAPSHOT_DIR
import console_link.middleware.clusters as clusters_
import console_link.middleware.snapshot as snapshot_

SUPPORTED_SOURCE_CLUSTERS = [Version("ELASTICSEARCH", 5, 6, 16),
                             Version("ELASTICSEARCH", 6, 8, 23),
                             Version("ELASTICSEARCH", 7, 10, 2)]

TEST_INDEX_NAME = "test_index"
TEST_DOC = json.dumps({"test_field": "test_value"})
DOC_COUNT = 10
SNAPSHOT_NAME = "test_snapshot"


def seed_data_without_types(cluster: Cluster, doc_count):
    cluster.call_api(f"/{TEST_INDEX_NAME}", HttpMethod.PUT,
                     data=json.dumps({"settings": {"index": {"number_of_shards": 1, "number_of_replicas": 0}}}),
                     headers={"Content-Type": "application/json"})
    for i, _ in enumerate(range(doc_count)):
        cluster.call_api(f"/{TEST_INDEX_NAME}/_doc/{i + 1}", HttpMethod.PUT,
                         data=TEST_DOC, headers={"Content-Type": "application/json"})


def seed_data_with_types(cluster: Cluster, doc_count):
    # Create index with settings
    cluster.call_api(f"/{TEST_INDEX_NAME}", HttpMethod.PUT,
                     data=json.dumps({"settings": {"index": {"number_of_shards": 1, "number_of_replicas": 0}}}),
                     headers={"Content-Type": "application/json"})

    doc_type = "document"
    for i, _ in enumerate(range(doc_count)):
        cluster.call_api(f"/{TEST_INDEX_NAME}/{doc_type}/{i + 1}", HttpMethod.PUT,
                         data=TEST_DOC, headers={"Content-Type": "application/json"})


def setup_snapshot_repo_and_snapshot(cluster: Cluster):
    # Register repo
    cluster.call_api(f"/_snapshot/{DEFAULT_SNAPSHOT_REPO_NAME}", HttpMethod.PUT,
                     data=json.dumps({"type": "fs", "settings": {"location": CLUSTER_SNAPSHOT_DIR}}),
                     headers={"Content-Type": "application/json"})
    cluster.call_api(f"/_snapshot/{DEFAULT_SNAPSHOT_REPO_NAME}/{SNAPSHOT_NAME}", HttpMethod.POST)


@pytest.fixture()
def env_with_source_container(request):
    version = request.param
    # Spin up the Elasticsearch container and wait until it's healthy
    container = SearchContainer(version, mem_limit="3G")
    container.start()

    base_url = f"http://{container.get_container_host_ip()}:{container.get_exposed_port(9200)}"
    # Create a temporary services.yaml file based on the services.yaml spec
    services_config = {
        'source_cluster': {
            'endpoint': base_url,
            'allow_insecure': True,
            'no_auth': {}
        },
        'target_cluster': {
            'endpoint': "http://this_endpoint_does_not_exist:9200",
            'allow_insecure': True,
            'no_auth': {}
        },
        'snapshot': {
            'snapshot_name': SNAPSHOT_NAME,
            'fs': {
                'repo_path': CLUSTER_SNAPSHOT_DIR
            }
        }
    }

    with tempfile.NamedTemporaryFile(mode='w', delete=False) as temp_config:
        yaml.dump(services_config, temp_config)
        temp_config_path = temp_config.name

    logging.basicConfig(level=logging.INFO)

    env = Environment(config_file=temp_config_path)
    if version.flavor == "ELASTICSEARCH" and version.major_version == 5:
        seed_data_with_types(env.source_cluster, DOC_COUNT)
    else:
        seed_data_without_types(env.source_cluster, DOC_COUNT)

    setup_snapshot_repo_and_snapshot(env.source_cluster)

    yield env
    # Stop the container and clean up the temporary services.yaml file after tests complete
    container.stop()
    os.remove(temp_config_path)


@pytest.mark.slow
@pytest.mark.parametrize("env_with_source_container,json",
                         itertools.product(SUPPORTED_SOURCE_CLUSTERS, [True, False]),
                         indirect=["env_with_source_container"])
def test_cluster_cat_indices(env_with_source_container: Environment, json: bool):
    env = env_with_source_container
    assert type(env.source_cluster) is Cluster
    # First, test with refresh enabled.
    result = clusters_.cat_indices(env.source_cluster, refresh=True, as_json=json)
    if not json:
        result_lines = result.decode('utf-8').split('\n')
        assert any(TEST_INDEX_NAME in line and str(DOC_COUNT) in line for line in result_lines)
    else:
        assert any(item['index'] == TEST_INDEX_NAME and int(item['docs.count']) == DOC_COUNT for item in result)
    # And then test again with refresh disabled. Doing both checks in one test halves the number of container starts
    # and eliminates the need to `sleep` before testing.
    result = clusters_.cat_indices(env.source_cluster, refresh=False, as_json=False)
    result_lines = result.decode('utf-8').split('\n')
    assert any(TEST_INDEX_NAME in line and str(DOC_COUNT) in line for line in result_lines)


@pytest.mark.slow
@pytest.mark.parametrize("env_with_source_container", SUPPORTED_SOURCE_CLUSTERS, indirect=True)
def test_connection_check(env_with_source_container: Environment):
    env = env_with_source_container
    assert type(env.source_cluster) is Cluster
    result = clusters_.connection_check(env.source_cluster)
    assert result.connection_established


@pytest.mark.slow
@pytest.mark.parametrize("env_with_source_container,deep_status_check",
                         itertools.product(SUPPORTED_SOURCE_CLUSTERS, [False, True]),
                         indirect=["env_with_source_container"])
def test_snapshot_status(env_with_source_container: Environment, deep_status_check: bool):
    env = env_with_source_container
    assert type(env.source_cluster) is Cluster
    result = snapshot_.status(env.snapshot, deep_status_check=True)
    print(result)
    assert result.success
