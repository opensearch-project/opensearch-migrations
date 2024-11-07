import argparse
from .disable_compatibility_mode import modify_compatibility_mode
from console_link.environment import Environment
from cluster_tools.utils import console_curl

def define_arguments(parser: argparse.ArgumentParser) -> None:
    """Defines arguments for enabling compatibility mode."""
    parser.description = "Enables compatibility mode on the OpenSearch cluster."

def main(env: Environment, _: argparse.Namespace) -> None:
    """Main function that enables compatibility mode."""
    print("Enabling compatibility mode on the OpenSearch cluster.")
    try:
        response = modify_compatibility_mode(env, True)
        print(f"Response: {response}")
    except Exception as e:
        print(f"An error occurred: {e}")
