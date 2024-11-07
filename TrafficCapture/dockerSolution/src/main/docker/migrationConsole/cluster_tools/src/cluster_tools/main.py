import os
import importlib
import argparse
import argcomplete
from console_link.environment import Environment
import logging
import sys


class ClusterToolsFilter(logging.Filter):
    def filter(self, record):
        return record.name.startswith('cluster_tools') or record.name.startswith('tools')


def setup_logging():
    """Sets up logging with a file handler for all logs and a stdout handler for 'cluster_tools' logs only."""
    logger = logging.getLogger()
    logger.setLevel(logging.INFO)

    from datetime import datetime
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    logs_dir = os.getenv("SHARED_LOGS_DIR_PATH", "./logs")
    log_file_path = os.path.join(logs_dir, f'cluster_tools/log_{timestamp}_{os.getpid()}.log')
    os.makedirs(os.path.dirname(log_file_path), exist_ok=True)
    file_handler = logging.FileHandler(log_file_path)
    file_handler.setFormatter(logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s'))

    stdout_handler = logging.StreamHandler(sys.stdout)
    stdout_handler.setFormatter(logging.Formatter('%(message)s'))

    # Apply the filter to the stdout handler
    stdout_handler.addFilter(ClusterToolsFilter())

    # Add both handlers to the main logger
    logger.addHandler(file_handler)
    logger.addHandler(stdout_handler)

    # Setup logger for this module with name
    module_logger = logging.getLogger(__name__)
    return module_logger


logger = setup_logging()


def list_tools():
    """Dynamically list all available tools by finding Python files in the tools directory."""
    tools_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), "../tools"))
    tools = [
        filename[:-3]
        for filename in os.listdir(tools_dir)
        if filename.endswith(".py") and filename != "__init__.py"
    ]
    return tools


def setup_parser(parser):
    parser.add_argument(
        '--config_file',
        type=str,
        default='/etc/migration_services.yaml',
        help='Path to the configuration file.'
    )
    subparsers = parser.add_subparsers(dest="tool", help="The tool to run.")
    # Dynamically add subparsers for each tool
    for tool_name in list_tools():
        try:
            tool_module = importlib.import_module(f"tools.{tool_name}")
            tool_parser = subparsers.add_parser(tool_name, help=f"{tool_name} utility")

            # Check if the tool module has a 'define_arguments' function to define its arguments
            if hasattr(tool_module, "define_arguments"):
                tool_module.define_arguments(tool_parser)
            else:
                message = f"The tool '{tool_name}' does not have a 'define_arguments' function. \
                        Please add one to specify its arguments."
                logger.error(message)
                raise Exception(message)
            tool_parser.set_defaults(func=tool_module.main)  # Set the main function as the handler
        except Exception as e:
            logger.error(f"An error occurred while importing the tool '{tool_name}': {e}")
            logger.error(f"Skipping tool '{tool_name}'")


def main(args=None):
    # Create the main parser
    parser = argparse.ArgumentParser(
        description="CLI tool for managing and running different utilities."
    )

    setup_parser(parser)
    # Enable argcomplete for bash completion
    argcomplete.autocomplete(parser)

    args = args if args is not None else parser.parse_args()

    # If no specific tool is requested, list all available tools
    if not args.tool:
        logger.info("Available tools:")
        for tool in list_tools():
            logger.info(f"  - {tool}")
        logger.info("\nRun `cluster_tools <tool>` to use a tool.")
    else:
        env = Environment(args.config_file)
        try:
            args.func(env, args)
        except Exception as e:
            logger.error(f"An error occurred while executing the tool: {e}")
    logger.info(f"Logs saved to {log_file_path}")


if __name__ == "__main__":
    main()
