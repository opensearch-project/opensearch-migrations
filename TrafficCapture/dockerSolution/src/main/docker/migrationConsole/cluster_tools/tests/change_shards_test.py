from src.tools.change_shards import main as change_shards
from tests.utils import target_cluster_refresh, get_target_index_info
from src.tools.create_index import main as create_index
from .utils import env as env
import argparse


def test_change_shards(env):
    """Test the change_shards function to ensure it updates the number of shards in an index."""

    index_name = "test-index"
    target_shards = 10

    create_index_args = argparse.Namespace(index_name=index_name, primary_shards=1)
    create_index(env, create_index_args)

    target_cluster_refresh(env)

    change_shards_args = argparse.Namespace(index_name=index_name, target_shards=target_shards)
    change_shards(env, change_shards_args)

    target_cluster_refresh(env)

    # Verify that the shards were changed successfully
    index_info = get_target_index_info(env, index_name)

    assert isinstance(index_info, dict), "Failed to retrieve index information."
    actual_shards = int(index_info[index_name]["settings"]["index"]["number_of_shards"])
    assert actual_shards == target_shards, f"Expected shards: {target_shards}, Actual shards: {actual_shards}"
