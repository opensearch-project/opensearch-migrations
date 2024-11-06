import pytest
from testcontainers.opensearch import OpenSearchContainer
from testcontainers.core.waiting_utils import wait_for_logs
import tempfile
import os
import yaml

@pytest.fixture(scope="module")
def opensearch_container():
    # Spin up the OpenSearch container and wait until it's healthy
    container = OpenSearchContainer()
    container.start()
    wait_for_logs(container, ".*recovered .* indices into cluster_state.*")
    
    base_url = f"http://{container.get_container_host_ip()}:{container.get_exposed_port(9200)}"
    # Create a temporary services.yaml file based on the services.yaml spec
    services_config = {
        'source_cluster': {
            'endpoint': base_url,
            'allow_insecure': True,
            'no_auth': {}
        },
        'target_cluster': {
            'endpoint': base_url,
            'allow_insecure': True,
            'no_auth': {}
        }
    }
    
    with tempfile.NamedTemporaryFile(mode='w', delete=False) as temp_config:
        yaml.dump(services_config, temp_config)
        temp_config_path = temp_config.name
    
    # Set the CONFIG_FILE environment variable scoped to the pytest test
    os.environ['CONFIG_FILE'] = temp_config_path
    
    yield {"base_url": base_url, "config_file": temp_config_path}  # Provide the connection details to tests
    
    # Stop the container and clean up the temporary services.yaml file after tests complete
    container.stop()
    os.remove(temp_config_path)
