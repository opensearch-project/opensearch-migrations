import pytest
from testcontainers.opensearch import OpenSearchContainer
from testcontainers.core.waiting_utils import wait_for_logs
import tempfile
import os
import yaml
from console_link.environment import Environment
from src.cluster_tools.utils import console_curl


@pytest.fixture(scope="module")
def env():
    # Spin up the OpenSearch container and wait until it's healthy
    container = OpenSearchContainer()
    container.start()
    wait_for_logs(container, ".*recovered .* indices into cluster_state.*")

    base_url = f"http://{container.get_container_host_ip()}:{container.get_exposed_port(9200)}"
    # Create a temporary services.yaml file based on the services.yaml spec
    services_config = {
        'target_cluster': {
            'endpoint': base_url,
            'allow_insecure': True,
            'no_auth': {}
        }
    }

    with tempfile.NamedTemporaryFile(mode='w', delete=False) as temp_config:
        yaml.dump(services_config, temp_config)
        temp_config_path = temp_config.name

    yield Environment(temp_config_path)

    # Stop the container and clean up the temporary services.yaml file after tests complete
    container.stop()
    os.remove(temp_config_path)


def target_cluster_refresh(env: Environment) -> None:
    """Refreshes the target cluster's indices."""
    console_curl(
        env=env,
        path="/_refresh",
        cluster='target_cluster',
        method='POST'
    )


def get_target_index_info(env: Environment, index_name: str) -> dict:
    """Retrieves information about the target index."""
    response = console_curl(
        env=env,
        path=f"/{index_name}",
        cluster='target_cluster',
        method='GET'
    )
    return response
