import os
import tempfile
import yaml
import pytest
from testcontainers.opensearch import OpenSearchContainer
from testcontainers.core.waiting_utils import wait_for_logs
from console_link.environment import Environment


@pytest.fixture(scope="function")
def env():
    # Spin up the OpenSearch container and wait until it's healthy
    container = OpenSearchContainer("opensearchproject/opensearch:2.14.0")
    container.with_env("OPENSEARCH_INITIAL_ADMIN_PASSWORD", "myStrongPassword123!")
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
