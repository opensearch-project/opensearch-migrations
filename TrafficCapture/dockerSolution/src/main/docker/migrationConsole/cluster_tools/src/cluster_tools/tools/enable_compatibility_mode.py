import argparse
import logging
from .disable_compatibility_mode import modify_compatibility_mode
from console_link.environment import Environment


logger = logging.getLogger(__name__)


def define_arguments(parser: argparse.ArgumentParser) -> None:
    """Defines arguments for enabling compatibility mode."""
    parser.description = "Enables compatibility mode on the OpenSearch cluster."


def main(env: Environment, _: argparse.Namespace) -> None:
    """Main function that enables compatibility mode."""
    logger.info("Enabling compatibility mode on the OpenSearch cluster.")
    response = modify_compatibility_mode(env, True)
    logger.info(f"Response: {response}")
