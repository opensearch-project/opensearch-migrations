from cluster_tools.tools.change_shards import main as change_shards
from tests.utils import target_cluster_refresh, get_target_index_info
from cluster_tools.tools.create_index import main as create_index
from src.cluster_tools.base.utils import console_curl
import argparse
import logging
import pytest

logger = logging.getLogger(__name__)


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

    # Verify that no other non-system index exists
    all_index_info = {index['index']: index for index in console_curl(
        env=env, path="/_cat/indices?format=json", method="GET")}
    non_system_indexes = [idx for idx in all_index_info.keys() if not idx.startswith('.')]
    assert len(non_system_indexes) == 1, f"Expected only one non-system index, found {len(non_system_indexes)}"
    assert index_name in non_system_indexes, f"Unexpected indexes found: {non_system_indexes}"


def test_exception_change_shards_with_too_high_shards(env):
    """Test the change_shards function to ensure it raises an error when the number of shards is too high."""
    index_name = "test-index"
    original_shards = 10

    args = argparse.Namespace(index_name=index_name, primary_shards=original_shards)
    create_index(env, args)

    target_cluster_refresh(env)

    invalid_target_shards = 1001
    change_shards_args = argparse.Namespace(index_name=index_name, target_shards=invalid_target_shards)
    with pytest.raises(Exception):
        change_shards(env, change_shards_args)

    # Verify that the index exists with original settings
    index_info = get_target_index_info(env, index_name)

    assert isinstance(index_info, dict), "Failed to retrieve index information."
    actual_shards = int(index_info[index_name]["settings"]["index"]["number_of_shards"])
    assert actual_shards == original_shards, f"Expected shards: {original_shards}, Actual shards: {actual_shards}"
