from src.cluster_tools.main import main
import argparse
from tests.utils import get_target_index_info
import src.tools.create_index as create_index


def test_list_tools(capsys):
    """Test the list_tools function to ensure it lists available tools."""
    main(argparse.Namespace(tool=None))
    captured = capsys.readouterr()
    assert "Available tools:" in captured.out
    available_tools = [
        line.strip().lstrip("- ")
        for line in captured.out.splitlines()
        if line.startswith("  - ")
    ]
    assert len(available_tools) >= 4, "Expected at least 4 tools"
    assert "create_index" in available_tools, "Expected 'create_index' tool to be listed"


def test_main_with_tool(capsys, env):
    args = argparse.Namespace(tool="create_index", index_name="test-index",
                              primary_shards=10, config_file=env.config_file)
    args.func = create_index.main
    main(args)

    # Verify that the index was created successfully
    index_info = get_target_index_info(env, args.index_name)
    assert isinstance(index_info, dict), "Index was not created successfully."
    actual_shards = int(index_info[args.index_name]["settings"]["index"]["number_of_shards"])
    assert actual_shards == args.primary_shards, f"Expected {args.primary_shards} shards, got {actual_shards}"
    captured = capsys.readouterr()
    assert "Creating index:" in captured.out
