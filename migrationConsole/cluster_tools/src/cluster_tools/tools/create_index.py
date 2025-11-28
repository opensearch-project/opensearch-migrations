import argparse
from console_link.environment import Environment
from cluster_tools.base.utils import console_curl
import logging

logger = logging.getLogger(__name__)


def define_arguments(parser: argparse.ArgumentParser) -> None:
    """Defines arguments for creating an Elasticsearch index."""
    parser.add_argument("index_name", type=str, help="Name of the Elasticsearch index to create")
    parser.add_argument("primary_shards", type=int, help="Number of primary shards for the index")


def create_index(env: Environment, index_name: str, primary_shards: int) -> str:
    """Creates an Elasticsearch index with the given name and primary shards."""
    settings: dict = {
        "settings": {
            "index": {
                "number_of_shards": primary_shards
            }
        }
    }
    output: str = console_curl(
        env=env,
        path=f"/{index_name}",
        method='PUT',
        json_data=settings,
    )
    return output


def main(env: Environment, args: argparse.Namespace) -> None:
    """Main function that executes the index creation."""
    logger.info(f"Creating index: {args.index_name} with {args.primary_shards} primary shards")
    output = create_index(env, args.index_name, args.primary_shards)
    logger.info(f"Response: {output}")
