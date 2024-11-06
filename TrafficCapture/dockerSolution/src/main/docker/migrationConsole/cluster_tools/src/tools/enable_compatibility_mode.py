import argparse
from cluster_tools.utils import console_curl

def define_arguments(_: argparse.ArgumentParser) -> None:
    """Defines arguments for disabling compatibility mode."""
    pass

def enable_compatibility_mode() -> str:
    """Enables compatibility mode on the OpenSearch cluster."""
    settings = {
        "persistent": {
            "compatibility.override_main_response_version": True
        }
    }
    response = console_curl(
        path="/_cluster/settings",
        method="PUT",
        json_data=settings
    )
    return response

def main(_: argparse.Namespace) -> None:
    """Main function that enables compatibility mode."""
    print("Enabling compatibility mode on the OpenSearch cluster.")
    try:
        response = enable_compatibility_mode()
        print(f"Response: {response}")
    except Exception as e:
        print(f"An error occurred: {e}")
