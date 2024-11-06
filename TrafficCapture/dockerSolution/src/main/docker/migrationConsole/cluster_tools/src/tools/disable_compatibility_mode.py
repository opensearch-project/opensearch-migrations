import argparse
from cluster_tools.utils import console_curl

def define_arguments(parser: argparse.ArgumentParser) -> None:
    """Defines arguments for disabling compatibility mode."""
    parser.description = "Disables compatibility mode on the OpenSearch cluster."
    pass

def disable_compatibility_mode() -> str:
    """Disables compatibility mode on the OpenSearch cluster."""
    settings = {
        "persistent": {
            "compatibility.override_main_response_version": False
        }
    }
    response = console_curl(
        path="/_cluster/settings",
        method="PUT",
        json_data=settings
    )
    return response

def main(_: argparse.Namespace) -> None:
    """Main function that disables compatibility mode."""
    print("Disabling compatibility mode on the OpenSearch cluster.")
    try:
        response = disable_compatibility_mode()
        print(f"Response: {response}")
    except Exception as e:
        print(f"An error occurred: {e}")
