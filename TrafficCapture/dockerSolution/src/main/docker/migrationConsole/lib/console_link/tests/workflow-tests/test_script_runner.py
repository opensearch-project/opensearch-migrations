"""Tests for script runner service."""

import pytest

from console_link.workflow.services.script_runner import ScriptRunner


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
