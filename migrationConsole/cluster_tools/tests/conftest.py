import os
import time
import tempfile
import yaml
import pytest
import requests
from testcontainers.opensearch import OpenSearchContainer
from console_link.environment import Environment


def wait_for_opensearch(url, max_retries=30, retry_interval=2):
    """Wait for OpenSearch to be ready by checking the health endpoint."""
    for i in range(max_retries):
        try:
            response = requests.get(f"{url}/_cluster/health", timeout=5)
            if response.status_code == 200:
                health_data = response.json()
                if health_data.get("status") in ["green", "yellow"]:
                    return True
        except (requests.RequestException, ValueError):
            pass

        time.sleep(retry_interval)

    raise TimeoutError(f"OpenSearch at {url} did not become ready within {max_retries * retry_interval} seconds")


def create_opensearch_container():
    """Create and start an OpenSearch container."""
    container = OpenSearchContainer("opensearchproject/opensearch:2.19.1")
    container.with_env("discovery.type", "single-node")
    container.with_env("OPENSEARCH_JAVA_OPTS", "-Xms2g -Xmx2g")
    container.start()

    url = f"http://{container.get_container_host_ip()}:{container.get_exposed_port(9200)}"
    wait_for_opensearch(url)

    return container, url


def create_environment_config(clusters):
    """Create a temporary services.yaml file with the specified clusters."""
    services_config = {}

    for cluster_name, url in clusters.items():
        services_config[cluster_name] = {
            'endpoint': url,
            'allow_insecure': True,
            'no_auth': {}
        }

    with tempfile.NamedTemporaryFile(mode='w', delete=False) as temp_config:
        yaml.dump(services_config, temp_config)
        temp_config_path = temp_config.name

    return temp_config_path


@pytest.fixture(scope="function")
def env(request):
    """Create an environment with configurable clusters.

    By default, only creates a target_cluster.
    To create both source and target clusters, mark the test with:
    @pytest.mark.dual_cluster
    """
    containers = []
    clusters = {}

    # Always create target cluster
    target_container, target_url = create_opensearch_container()
    containers.append(target_container)
    clusters['target_cluster'] = target_url

    # Create source cluster if test is marked with dual_cluster
    if request.node.get_closest_marker('dual_cluster') is not None:
        source_container, source_url = create_opensearch_container()
        containers.append(source_container)
        clusters['source_cluster'] = source_url

    # Create environment config
    temp_config_path = create_environment_config(clusters)

    # Create the environment
    env = Environment(config_file=temp_config_path)

    yield env

    # Stop containers and clean up
    for container in containers:
        container.stop()

    os.remove(temp_config_path)


# Mark tests that need both source and target clusters
def pytest_configure(config):
    """Add custom markers."""
    config.addinivalue_line("markers", "dual_cluster: mark test as requiring both source and target clusters")
