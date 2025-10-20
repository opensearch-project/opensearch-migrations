"""Tests for script runner service."""

import pytest
import tempfile
from pathlib import Path
import yaml

from console_link.workflow.services.script_runner import ScriptRunner
from console_link.workflow.models.config import WorkflowConfig


class TestScriptRunner:
    """Test script runner service."""

    def test_script_runner_initialization(self):
        """Test script runner finds test scripts."""
        runner = ScriptRunner()
        assert runner.script_dir.exists()
        assert (runner.script_dir / "getSample.sh").exists()

    def test_get_sample_config(self):
        """Test getting sample configuration."""
        runner = ScriptRunner()
        sample = runner.get_sample_config()

        assert sample
        assert "parameters" in sample
        assert "message" in sample

    def test_transform_config(self):
        """Test config transformation."""
        runner = ScriptRunner()

        test_config = "test: data"
        result = runner.transform_config(test_config)

        # Mock transformer just passes through
        assert result == test_config

    def test_init_workflow(self):
        """Test workflow initialization."""
        runner = ScriptRunner()

        test_config = "test: data"
        prefix = runner.init_workflow(test_config)

        assert prefix
        assert prefix.startswith("test-")

    def test_init_workflow_with_custom_prefix(self):
        """Test workflow initialization with custom prefix."""
        runner = ScriptRunner()

        test_config = "test: data"
        custom_prefix = "custom-12345"
        prefix = runner.init_workflow(test_config, custom_prefix)

        assert prefix == custom_prefix

    def test_submit_workflow(self):
        """Test workflow submission."""
        runner = ScriptRunner()

        test_config = "test: data"
        prefix = "test-12345"
        result = runner.submit_workflow(test_config, prefix)

        # Verify Workflow structure
        assert result["kind"] == "Workflow"
        assert result["metadata"]["namespace"] == "ma"
        assert result["spec"]["workflowTemplateRef"]["name"] == "hello-world-template"

        # Verify metadata
        assert result["_metadata"]["workflow_name"]
        assert result["_metadata"]["prefix"] == prefix
        assert result["_metadata"]["template"] == "hello-world-template"

    def test_submit_workflow_custom_namespace(self):
        """Test workflow submission with custom namespace."""
        runner = ScriptRunner()

        test_config = "test: data"
        prefix = "test-12345"
        namespace = "custom-ns"
        result = runner.submit_workflow(test_config, prefix, namespace)

        assert result["metadata"]["namespace"] == namespace

    def test_script_not_found(self):
        """Test error handling when script doesn't exist."""
        runner = ScriptRunner()

        with pytest.raises(FileNotFoundError):
            runner.run_script("nonexistent.sh")

    def test_get_blank_starter_config(self):
        """Test that _get_blank_starter_config returns empty string."""
        runner = ScriptRunner()
        blank_config = runner._get_blank_starter_config()

        # Verify it's an empty string
        assert blank_config == ""
        assert isinstance(blank_config, str)

    def test_get_blank_starter_config_parseable_by_workflow_config(self):
        """Test that blank starter config can be parsed by WorkflowConfig."""
        runner = ScriptRunner()
        blank_config = runner._get_blank_starter_config()

        # Empty string should parse to empty dict
        config = WorkflowConfig.from_yaml(blank_config)
        assert config is not None
        # Empty YAML parses to None, which becomes empty dict in WorkflowConfig
        assert config.data == {} or config.data is None

    def test_get_sample_config_with_missing_file(self):
        """Test get_sample_config returns blank starter when sample.yaml doesn't exist."""
        # Create a temporary directory without sample.yaml
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            runner = ScriptRunner(script_dir=temp_path)

            # Should return blank starter config (empty string) instead of raising FileNotFoundError
            sample = runner.get_sample_config()

            # Verify it's an empty string
            assert sample == ""
            assert isinstance(sample, str)

    def test_get_sample_config_with_existing_file(self):
        """Test get_sample_config still works when sample.yaml exists."""
        # Create a temporary directory with a sample.yaml file
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            sample_file = temp_path / "sample.yaml"

            # Write a custom sample config
            custom_content = "custom:\n  config: value\nparameters:\n  test: data"
            sample_file.write_text(custom_content)

            runner = ScriptRunner(script_dir=temp_path)

            # Should return the custom sample, not the blank starter
            sample = runner.get_sample_config()

            assert sample == custom_content
            assert "custom" in sample
            assert "Workflow Configuration Template" not in sample
