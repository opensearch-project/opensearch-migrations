"""Unit tests for workflow models."""

from console_link.workflow.models.config import WorkflowConfig
from console_link.workflow.models.utils import ExitCode
from console_link.models.command_result import CommandResult
import yaml


class TestWorkflowModels:
    """Test workflow configuration models."""

    def test_workflow_config_creation(self):
        """Test WorkflowConfig creation."""
        # Empty config
        config = WorkflowConfig()
        assert config.data == {}
        assert not config

        # Config with data
        data = {"key": "value", "nested": {"item": "test"}}
        config = WorkflowConfig(data)
        assert config.data == data
        assert bool(config) is True

    def test_workflow_config_get_set(self):
        """Test get and set methods."""
        config = WorkflowConfig()

        # Set values
        config.set("endpoint", "https://test.com:9200")
        config.set("nested", {"key": "value"})

        # Get values
        assert config.get("endpoint") == "https://test.com:9200"
        assert config.get("nested") == {"key": "value"}
        assert config.get("missing", "default") == "default"

    def test_workflow_config_yaml_serialization(self):
        """Test YAML serialization and deserialization."""
        # Create a config with data
        data = {
            "targets": {
                "test": {
                    "endpoint": "https://test.com:9200",
                    "auth": {
                        "username": "admin",
                        "password": "password"
                    }
                }
            }
        }
        config = WorkflowConfig(data, raw_yaml="targets:\n  test:\n    endpoint: https://test.com:9200\n    auth:\n      username: admin\n      password: password\n")

        # Test raw_yaml content
        assert "targets:" in config.raw_yaml
        assert "test:" in config.raw_yaml
        assert "username: admin" in config.raw_yaml

        # Test YAML deserialization
        config_from_yaml = WorkflowConfig.from_yaml(config.raw_yaml)
        assert config_from_yaml.data == data
        assert config_from_yaml.get("targets")["test"]["endpoint"] == "https://test.com:9200"

    def test_workflow_config_with_arbitrary_structure(self):
        """Test WorkflowConfig with arbitrary data structures."""
        # Test with various data types
        data = {
            "string_value": "test",
            "number_value": 42,
            "boolean_value": True,
            "list_value": [1, 2, 3],
            "nested": {
                "deeply": {
                    "nested": {
                        "value": "deep"
                    }
                }
            }
        }
        yaml_str = yaml.dump(data, default_flow_style=False)
        config = WorkflowConfig.from_yaml(yaml_str)

        # Verify all data is preserved through raw_yaml round-trip
        config_from_yaml = WorkflowConfig.from_yaml(config.raw_yaml)
        assert config_from_yaml.data == data

    def test_workflow_config_empty_yaml(self):
        """Test WorkflowConfig with empty YAML."""
        config = WorkflowConfig.from_yaml("")
        assert config.data == {}
        assert not config

    def test_workflow_config_json_as_yaml(self):
        """Test that JSON can be parsed as YAML (JSON is a subset of YAML 1.2)."""
        # Valid JSON should be parseable as YAML
        json_str = '{"targets": {"test": {"endpoint": "https://test.com:9200"}}}'
        config = WorkflowConfig.from_yaml(json_str)
        assert config.data["targets"]["test"]["endpoint"] == "https://test.com:9200"

    def test_workflow_config_comment_preservation(self):
        """Test that YAML comments are preserved through load/save cycles."""
        yaml_with_comments = """# Main configuration
targets:
  # Test target configuration
  test:
    endpoint: https://test.com:9200  # Production endpoint
    auth:
      username: admin  # Admin user
"""
        # Load config with comments
        config = WorkflowConfig.from_yaml(yaml_with_comments)

        # Verify data is loaded correctly
        assert config.data["targets"]["test"]["endpoint"] == "https://test.com:9200"
        assert config.data["targets"]["test"]["auth"]["username"] == "admin"

        # Verify comments are preserved exactly in raw_yaml
        assert "# Main configuration" in config.raw_yaml
        assert "# Production endpoint" in config.raw_yaml
        assert config.raw_yaml == yaml_with_comments

    def test_command_result(self):
        """Test CommandResult wrapper."""
        # Success result
        result = CommandResult(success=True, value="Operation completed")
        assert result.success is True
        assert result.value == "Operation completed"

        # Failure result
        error_result = CommandResult(success=False, value="Error occurred")
        assert error_result.success is False

    def test_exit_code_enum(self):
        """Test ExitCode enum values."""
        assert ExitCode.SUCCESS.value == 0
        assert ExitCode.FAILURE.value == 1
        assert ExitCode.INVALID_INPUT.value == 2
        assert ExitCode.NOT_FOUND.value == 3
        assert ExitCode.ALREADY_EXISTS.value == 4
        assert ExitCode.PERMISSION_DENIED.value == 5
