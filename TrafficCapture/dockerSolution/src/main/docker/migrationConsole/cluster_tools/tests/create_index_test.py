from cluster_tools.tools.create_index import main as create_index
from tests.utils import get_target_index_info
import argparse
import logging

logger = logging.getLogger(__name__)


def test_create_index(env):
    """Test the create_index function to ensure it creates an index in OpenSearch."""

    index_name = "test-index"
    primary_shards = 10

    # Call create_index
    args = argparse.Namespace(index_name=index_name, primary_shards=primary_shards)
    create_index(env, args)
    # Verify that the index was created successfully with correct shards
    index_info = get_target_index_info(env, index_name)

    assert isinstance(index_info, dict), "Index was not created successfully."
    actual_shards = int(index_info[index_name]["settings"]["index"]["number_of_shards"])
    print(f"Expected shards: {primary_shards}, Actual shards: {actual_shards}")
    assert actual_shards == primary_shards, f"Expected shards: {primary_shards}, Actual shards: {actual_shards}"
