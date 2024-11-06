from src.tools.create_index import create_index
import requests
from tests.utils import opensearch_container

def test_create_index(opensearch_container):
    """Test the create_index function to ensure it creates an index in OpenSearch."""
    base_url = opensearch_container["base_url"]

    index_name = "test-index"
    primary_shards = 1

    # Call the create_index function
    create_index(index_name, primary_shards)

    # Verify that the index was created successfully
    verify_response = requests.get(f"{base_url}/{index_name}")
    assert verify_response.status_code == 200, "Index was not created successfully."
    index_info = verify_response.json()
    assert int(index_info[index_name]["settings"]["index"]["number_of_shards"]) == primary_shards, "Primary shards count does not match."
