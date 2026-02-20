import argparse
from typing import Any, Dict
from cluster_tools.base.utils import console_curl
from console_link.environment import Environment
import logging
import uuid

logger = logging.getLogger(__name__)


def define_arguments(parser: argparse.ArgumentParser) -> None:
    """Defines arguments for the Elasticsearch index shard settings management tool."""
    parser.add_argument("index_name", type=str, help="Name of the Elasticsearch index")
    parser.add_argument("target_shards", type=int, help="Target number of primary shards")


def check_document_count(env: Environment, index_name: str) -> int:
    """Checks if the index contains 0 documents."""
    response = console_curl(
        env=env,
        path=f"/{index_name}/_count",
        method='GET'
    )
    doc_count = response.get("count", 0)
    return doc_count


def fetch_and_filter_settings(env: Environment, index_name: str, target_shards: int) -> Dict[str, Any]:
    """Fetches and filters the index settings to prepare for index recreation."""
    source_settings = console_curl(
        env=env,
        path=f"/{index_name}/_settings",
        method='GET'
    )

    # Extract and filter settings
    settings_json = source_settings[index_name]["settings"]["index"]
    filtered_settings = {
        k: v for k, v in settings_json.items()
        if k not in ["number_of_shards", "provided_name", "uuid", "creation_date", "version"]
    }

    # Add target number of shards
    filtered_settings["number_of_shards"] = int(target_shards)
    source_settings[index_name]["settings"] = filtered_settings
    updated_settings = source_settings[index_name]
    return updated_settings


def delete_index(env: Environment, index_name: str) -> None:
    """Deletes the original index."""
    console_curl(
        env=env,
        path=f"/{index_name}",
        method='DELETE'
    )


def recreate_index(env: Environment, index_name: str, updated_settings: Dict[str, Any]) -> None:
    """Recreates the index with the updated settings."""
    console_curl(
        env=env,
        path=f"/{index_name}",
        method='PUT',
        json_data=updated_settings
    )


def target_cluster_refresh(env: Environment) -> None:
    """Refreshes the target cluster's indices."""
    console_curl(
        env=env,
        path="/_refresh",
        method='POST'
    )


def main(env: Environment, args: argparse.Namespace) -> None:
    """Main function that executes the full workflow for managing index shard settings."""
    index_name = args.index_name
    target_shards = args.target_shards

    doc_count = check_document_count(env, index_name)
    if doc_count != 0:
        logger.info(f"Index {index_name} contains documents. Aborting.")
        raise RuntimeError(f"Index {index_name} contains documents. Aborting.")
    logger.info(f"Index {index_name} contains 0 documents. Proceeding.")

    logger.info(f"Fetching settings from the index: {index_name}")
    updated_settings = fetch_and_filter_settings(env, index_name, target_shards)
    logger.info(f"Updated settings: {updated_settings}")

    test_index_name = f"test-{index_name}-{uuid.uuid4().hex}"
    logger.info(f"Creating test index: {test_index_name} with updated settings")
    recreate_index(env, test_index_name, updated_settings)
    logger.info(f"Test index {test_index_name} created successfully")

    logger.info(f"Deleting test index: {test_index_name}")
    delete_index(env, test_index_name)
    logger.info(f"Test index {test_index_name} deleted successfully. Proceeding with main index.")

    logger.info(f"Deleting index: {index_name}")
    delete_index(env, index_name)

    logger.info("Refreshing target cluster")
    target_cluster_refresh(env)

    logger.info(f"Recreating index: {index_name} with updated settings")
    recreate_index(env, index_name, updated_settings)

    logger.info(f"Index {index_name} recreated successfully with updated settings.")
