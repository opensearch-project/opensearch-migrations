import argparse
from console_link.environment import Environment
from cluster_tools.base.utils import console_curl
import logging

logger = logging.getLogger(__name__)


def define_arguments(parser: argparse.ArgumentParser) -> None:
    """Defines arguments for disabling compatibility mode."""
    parser.description = "Disables compatibility mode on the OpenSearch cluster."


def modify_compatibility_mode(env: Environment, enable: bool) -> dict:
    """Modifies compatibility mode on the OpenSearch cluster.

    Args:
        env (Environment): The environment configuration.
        enable (bool): True to enable compatibility mode, False to disable.

    Returns:
        dict: The response from the OpenSearch cluster.
    """
    settings = {
        "persistent": {
            "compatibility.override_main_response_version": enable
        }
    }
    response = console_curl(
        env,
        path="/_cluster/settings",
        method="PUT",
        json_data=settings
    )
    return response


def main(env: Environment, _: argparse.Namespace) -> None:
    """Main function that disables compatibility mode."""
    logger.info("Disabling compatibility mode on the OpenSearch cluster.")
    response = modify_compatibility_mode(env, False)
    logger.info(f"Response: {response}")
