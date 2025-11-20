"""Tests for script runner service."""

import pytest
import tempfile
from pathlib import Path
from unittest.mock import Mock, patch

from console_link.workflow.services.script_runner import ScriptRunner
from console_link.workflow.models.config import WorkflowConfig


class TestScriptRunner:
    """Test script runner service."""

    def test_script_runner_initialization(self):
        """Test script runner finds test scripts."""
        runner = ScriptRunner()
        assert runner.script_dir.exists()

    def test_get_sample_config(self):
        """Test getting sample configuration."""
        runner = ScriptRunner()
        sample = runner.get_sample_config()

        assert sample
        assert "parameters" in sample
        # Verify hello-world specific fields
        assert "message" in sample
        assert "requiresApproval" in sample
        assert "approver" in sample

    @patch('console_link.workflow.services.script_runner.subprocess.run')
    def test_submit_workflow(self, mock_run):
        """Test workflow submission."""
        # Mock the subprocess call to avoid actual Kubernetes submission
        mock_run.return_value = Mock(
            returncode=0,
            stdout='{"workflow_name": "test-workflow-abc", "workflow_uid": "uid-123", "namespace": "ma"}'
        )

        runner = ScriptRunner()

        test_config = """parameters:
  message: "test message"
  requiresApproval: false
  approver: ""
"""
        args = ["--prefix ma", "--etcd-endpoints http://etcd.ma.svc.cluster.local:2379"]
        result = runner.submit_workflow(test_config, args)

        # Real script returns workflow info dict
        assert "workflow_name" in result
        assert result["workflow_name"].startswith("test-workflow-")
        assert "workflow_uid" in result

    @patch('console_link.workflow.services.script_runner.subprocess.run')
    def test_submit_workflow_custom_namespace(self, mock_run):
        """Test workflow submission with custom namespace."""
        # Mock the subprocess call to avoid actual Kubernetes submission
        mock_run.return_value = Mock(
            returncode=0,
            stdout='{"workflow_name": "test-workflow-xyz", "workflow_uid": "uid-456", "namespace": "custom-ns"}'
        )

        runner = ScriptRunner()

        test_config = """parameters:
  message: "test message"
  requiresApproval: false
  approver: ""
"""
        namespace = "custom-ns"
        args = [f"--prefix {namespace}", f"--etcd-endpoints http://etcd.{namespace}.svc.cluster.local:2379"]
        result = runner.submit_workflow(test_config, args)

        assert result["namespace"] == namespace
        assert "workflow_name" in result

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

            # Write a hello-world style sample config
            custom_content = """# Sample workflow configuration
parameters:
  message: "Test message"
  requiresApproval: true
  approver: "test-user"
"""
            sample_file.write_text(custom_content)

            runner = ScriptRunner(script_dir=temp_path)

            # Should return the custom sample, not the blank starter
            sample = runner.get_sample_config()

            assert sample == custom_content
            assert "parameters" in sample
            assert "message" in sample
            assert "requiresApproval" in sample
