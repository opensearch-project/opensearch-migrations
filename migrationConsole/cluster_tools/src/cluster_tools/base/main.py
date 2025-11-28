import os
import importlib
import argparse
import argcomplete
from console_link.environment import Environment
import logging
from datetime import datetime

root_logger = logging.getLogger()
root_logger.setLevel(logging.INFO)

logger = logging.getLogger(__name__)


def setup_file_logging(tool_name: str):
    """Sets up logging with a file handler for all logs."""
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    logs_dir = os.getenv("SHARED_LOGS_DIR_PATH", "./logs")
    host_name = os.getenv("HOSTNAME", "localhost")
    log_file_path = os.path.join(logs_dir, f'{host_name}/cluster_tools/{timestamp}_{tool_name}_log_{os.getpid()}.log')
    os.makedirs(os.path.dirname(log_file_path), exist_ok=True)
    file_handler = logging.FileHandler(log_file_path)
    file_handler.setLevel(logging.INFO)
    file_handler.setFormatter(logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s'))
    root_logger.addHandler(file_handler)
    return file_handler


def setup_stdout_logging():
    """Sets up a stdout handler for 'cluster_tools' logs only."""
    stdout_handler = logging.StreamHandler()
    stdout_handler.setLevel(logging.INFO)
    stdout_handler.setFormatter(logging.Formatter('%(message)s'))
    root_module_name = __name__.split('.')[0]
    stdout_handler.addFilter(logging.Filter(root_module_name))
    root_logger.addHandler(stdout_handler)
    return stdout_handler


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
        default='/config/migration_services.yaml',
        help='Path to the configuration file.'
    )
    subparsers = parser.add_subparsers(dest="tool", help="The tool to run.")
    # Dynamically add subparsers for each tool
    for tool_name in list_tools():
        try:
            tool_module = importlib.import_module(f"cluster_tools.tools.{tool_name}")
            tool_parser = subparsers.add_parser(tool_name, help=f"{tool_name} utility")

            # Check if the tool module has a 'define_arguments' function to define its arguments
            if hasattr(tool_module, "define_arguments"):
                tool_module.define_arguments(tool_parser)
            else:
                message = f"The tool '{tool_name}' does not have a 'define_arguments' function. \
                        Please add one to specify its arguments."
                logger.error(message)
                raise ValueError(message)
            tool_parser.set_defaults(func=tool_module.main)  # Set the main function as the handler
        except Exception as e:
            logger.error(f"An error occurred while importing the tool '{tool_name}': {e}")
            logger.error(f"Skipping tool '{tool_name}'")


def main(args=None):
    setup_stdout_logging()
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
        # Setup file logging for tool execution
        file_handler = setup_file_logging(args.tool)
        # Handle case where config_file might not be provided
        config_file = getattr(args, 'config_file', '/config/migration_services.yaml')
        env = Environment(config_file=config_file)
        try:
            args.func(env, args)
        except Exception as e:
            logger.error(f"An error occurred while executing the tool: {e}")
            raise e
        finally:
            if file_handler is not None:
                logger.info(f"\nLogs saved to {file_handler.baseFilename}")
                file_handler.close()
                logger.removeHandler(file_handler)


if __name__ == "__main__":
    main()
