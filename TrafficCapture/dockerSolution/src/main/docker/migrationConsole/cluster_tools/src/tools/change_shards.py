import argparse
from typing import Any, Dict
from cluster_tools.utils import console_curl
import sys

def define_arguments(parser: argparse.ArgumentParser) -> None:
    """Defines arguments for the Elasticsearch index shard settings management tool."""
    parser.add_argument("index_name", type=str, help="Name of the Elasticsearch index")
    parser.add_argument("target_shards", type=int, help="Target number of primary shards")

def check_document_count(index_name: str) -> int:
    """Checks if the index contains 0 documents."""
    response = console_curl(
        path=f"/{index_name}/_count",
        method='GET'
    )
    doc_count = response.get("count", 0)
    return doc_count

def fetch_and_filter_settings(index_name: str, target_shards: int) -> Dict[str, Any]:
    """Fetches and filters the index settings to prepare for index recreation."""
    source_settings = console_curl(
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
    filtered_settings["number_of_shards"] = target_shards
    updated_settings = {"settings": filtered_settings}
    return updated_settings

def delete_index(index_name: str) -> None:
    """Deletes the original index."""
    console_curl(
        path=f"/{index_name}",
        method='DELETE'
    )

def recreate_index(index_name: str, updated_settings: Dict[str, Any]) -> None:
    """Recreates the index with the updated settings."""
    console_curl(
        path=f"/{index_name}",
        method='PUT',
        json_data=updated_settings
    )

def main(args: argparse.Namespace) -> None:
    """Main function that executes the full workflow for managing index shard settings."""
    index_name = args.index_name
    target_shards = args.target_shards

    doc_count = check_document_count(index_name)
    if doc_count != 0:
        print(f"Index {index_name} contains documents. Aborting.")
        sys.exit(1)
    print(f"Index {index_name} contains 0 documents. Proceeding.")

    print(f"Fetching settings from the index: {index_name}")
    updated_settings = fetch_and_filter_settings(index_name, target_shards)
    print(f"Updated settings: {updated_settings}")

    print(f"Deleting index: {index_name}")
    delete_index(index_name)

    print(f"Recreating index: {index_name} with updated settings")
    recreate_index(index_name, updated_settings)
    print(f"Index {index_name} recreated successfully with updated settings.")