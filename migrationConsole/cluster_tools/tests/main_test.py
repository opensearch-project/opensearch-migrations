from src.cluster_tools.base.main import main
import argparse
from .utils import get_target_index_info
import src.cluster_tools.tools.create_index as create_index
import logging

logger = logging.getLogger(__name__)


def test_list_tools(caplog):
    """Test the list_tools function to ensure it lists available tools."""
    caplog.set_level(logging.INFO)
    logger.info(caplog.text)
    main(argparse.Namespace(tool=None))
    violating_logs = [record for record in caplog.records if record.levelno >= logging.WARNING]
    assert not violating_logs, f"Warnings or errors were logged during test_list_tools: {violating_logs}"
    assert "Available tools:" in caplog.text
    available_tools = [
        line.split("  - ", 1)[1].strip()
        for line in caplog.text.splitlines()
        if "  - " in line
    ]
    assert len(available_tools) >= 3, "Expected at least 3 tools"
    assert "create_index" in available_tools, "Expected 'create_index' tool to be listed"


def test_main_with_tool(caplog, env, monkeypatch):
    """Test the main function with a specific tool to ensure it executes correctly."""
    caplog.set_level(logging.INFO)

    # Create a mock Environment constructor that returns our fixture env
    def mock_environment_init(*args, **kwargs):
        return env

    # Patch the Environment class to return our fixture env
    monkeypatch.setattr("src.cluster_tools.base.main.Environment", mock_environment_init)

    args = argparse.Namespace(tool="create_index", index_name="test-index",
                              primary_shards=10)
    args.func = create_index.main
    main(args)

    # Verify that the index was created successfully
    index_info = get_target_index_info(env, args.index_name)
    assert isinstance(index_info, dict), "Index was not created successfully."
    actual_shards = int(index_info[args.index_name]["settings"]["index"]["number_of_shards"])
    assert actual_shards == args.primary_shards, f"Expected {args.primary_shards} shards, got {actual_shards}"
    assert "Creating index:" in caplog.text
