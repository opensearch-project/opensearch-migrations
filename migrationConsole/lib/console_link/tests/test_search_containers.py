import os
import tempfile

import pytest
import yaml
from console_link.environment import Environment
from console_link.models.cluster import Cluster
from tests.search_containers import SearchContainer, Version


@pytest.fixture
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
        }
    }

    with tempfile.NamedTemporaryFile(mode='w', delete=False) as temp_config:
        yaml.dump(services_config, temp_config)
        temp_config_path = temp_config.name

    yield Environment(config_file=temp_config_path)

    # Stop the container and clean up the temporary services.yaml file after tests complete
    container.stop()
    os.remove(temp_config_path)


# This is effectively a smoke test to ensure that the SearchContainer creation code is working as intended and
# that containers can spin up, be described as a Cluster and respond to requests.
@pytest.mark.slow
@pytest.mark.parametrize("env_with_source_container,version_string",
                         [(Version("ELASTICSEARCH", 5, 6, 16), "5.6.16"),
                          (Version("ELASTICSEARCH", 6, 8, 23), "6.8.23"),
                          (Version("ELASTICSEARCH", 7, 10, 2), "7.10.2"),
                          (Version("OPENSEARCH", 1, 3, 16), "1.3.16"),
                          (Version("OPENSEARCH", 2, 19, 1), "2.19.1")],
                         indirect=["env_with_source_container"])
def test_get_version_searchcontainer(env_with_source_container, version_string):
    assert env_with_source_container.source_cluster is not None
    assert type(env_with_source_container.source_cluster) is Cluster
    resp = env_with_source_container.source_cluster.call_api("/")
    assert resp.json()["version"]["number"] == version_string
