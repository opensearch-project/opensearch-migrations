import argparse
from console_link.environment import Environment
from cluster_tools.utils import console_curl


def define_arguments(parser: argparse.ArgumentParser) -> None:
    """Defines arguments for disabling compatibility mode."""
    parser.description = "Disables compatibility mode on the OpenSearch cluster."
    pass


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
    print("Disabling compatibility mode on the OpenSearch cluster.")
    try:
        response = modify_compatibility_mode(env, False)
        print(f"Response: {response}")
    except Exception as e:
        print(f"An error occurred: {e}")
