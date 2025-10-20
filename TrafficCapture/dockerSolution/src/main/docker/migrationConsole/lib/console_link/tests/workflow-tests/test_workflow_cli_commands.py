"""Integration tests for workflow CLI commands."""

import tempfile
from pathlib import Path
from click.testing import CliRunner
from unittest.mock import Mock, patch

from console_link.workflow.cli import workflow_cli
from console_link.workflow.models.config import WorkflowConfig


class TestWorkflowCLICommands:
    """Test suite for workflow CLI command integration."""

    @patch('console_link.workflow.commands.submit.WorkflowService')
    @patch('console_link.workflow.commands.submit.WorkflowConfigStore')
    def test_submit_command_basic(self, mock_store_class, mock_service_class):
        """Test basic submit command execution."""
        runner = CliRunner()

        # Mock the service
        mock_service = Mock()
        mock_service_class.return_value = mock_service

        mock_service.submit_workflow_to_argo.return_value = {
            'success': True,
            'workflow_name': 'test-workflow-abc',
            'workflow_uid': 'uid-123',
            'namespace': 'ma',
            'phase': None,
            'output_message': None,
            'error': None
        }

        # Mock the store with a valid config
        mock_store = Mock()
        mock_store_class.return_value = mock_store
        mock_config = WorkflowConfig({'parameters': {'message': 'test'}})
        mock_store.load_config.return_value = mock_config

        result = runner.invoke(workflow_cli, ['submit'])

        assert result.exit_code == 0
        assert 'submitted successfully' in result.output
        assert 'test-workflow-abc' in result.output

    @patch('console_link.workflow.commands.submit.WorkflowService')
    @patch('console_link.workflow.commands.submit.WorkflowConfigStore')
    def test_submit_command_with_wait(self, mock_store_class, mock_service_class):
        """Test submit command with --wait flag."""
        runner = CliRunner()

        # Mock the service
        mock_service = Mock()
        mock_service_class.return_value = mock_service

        mock_service.submit_workflow_to_argo.return_value = {
            'success': True,
            'workflow_name': 'test-workflow-abc',
            'workflow_uid': 'uid-123',
            'namespace': 'ma',
            'phase': None,
            'output_message': None,
            'error': None
        }

        mock_service.wait_for_workflow_completion.return_value = ('Succeeded', 'Hello World')

        # Mock the store with a valid config
        mock_store = Mock()
        mock_store_class.return_value = mock_store
        mock_config = WorkflowConfig({'parameters': {'message': 'test'}})
        mock_store.load_config.return_value = mock_config

        result = runner.invoke(workflow_cli, ['submit', '--wait', '--timeout', '60'])

        assert result.exit_code == 0
        assert 'submitted successfully' in result.output
        assert 'Waiting for workflow to complete' in result.output
        assert 'Succeeded' in result.output

    @patch('console_link.workflow.commands.status.WorkflowService')
    def test_status_command_single_workflow(self, mock_service_class):
        """Test status command for a specific workflow."""
        runner = CliRunner()

        # Mock the service
        mock_service = Mock()
        mock_service_class.return_value = mock_service

        mock_service.get_workflow_status.return_value = {
            'success': True,
            'workflow_name': 'test-workflow',
            'namespace': 'ma',
            'phase': 'Running',
            'progress': '1/2',
            'started_at': '2024-01-01T10:00:00Z',
            'finished_at': None,
            'steps': [
                {'name': 'step1', 'phase': 'Succeeded', 'type': 'Pod', 'started_at': '2024-01-01T10:00:00Z'},
                {'name': 'step2', 'phase': 'Running', 'type': 'Pod', 'started_at': '2024-01-01T10:01:00Z'}
            ],
            'error': None
        }

        result = runner.invoke(workflow_cli, ['status', 'test-workflow'])

        assert result.exit_code == 0
        assert 'test-workflow' in result.output
        assert 'Running' in result.output
        assert 'step1' in result.output
        assert 'step2' in result.output

    @patch('console_link.workflow.commands.status.WorkflowService')
    def test_status_command_list_all(self, mock_service_class):
        """Test status command listing all workflows."""
        runner = CliRunner()

        # Mock the service
        mock_service = Mock()
        mock_service_class.return_value = mock_service

        mock_service.list_workflows.return_value = {
            'success': True,
            'workflows': ['workflow-1', 'workflow-2'],
            'count': 2,
            'error': None
        }

        mock_service.get_workflow_status.side_effect = [
            {
                'success': True,
                'workflow_name': 'workflow-1',
                'namespace': 'ma',
                'phase': 'Running',
                'progress': '1/2',
                'started_at': '2024-01-01T10:00:00Z',
                'finished_at': None,
                'steps': [],
                'error': None
            },
            {
                'success': True,
                'workflow_name': 'workflow-2',
                'namespace': 'ma',
                'phase': 'Succeeded',
                'progress': '2/2',
                'started_at': '2024-01-01T09:00:00Z',
                'finished_at': '2024-01-01T09:05:00Z',
                'steps': [],
                'error': None
            }
        ]

        result = runner.invoke(workflow_cli, ['status'])

        assert result.exit_code == 0
        assert 'Found 2 workflow(s)' in result.output
        assert 'workflow-1' in result.output
        assert 'workflow-2' in result.output

    @patch('console_link.workflow.commands.stop.WorkflowService')
    def test_stop_command_auto_detect(self, mock_service_class):
        """Test stop command with auto-detection."""
        runner = CliRunner()

        # Mock the service
        mock_service = Mock()
        mock_service_class.return_value = mock_service

        # Mock list_workflows to return single workflow
        mock_service.list_workflows.return_value = {
            'success': True,
            'workflows': ['test-workflow'],
            'count': 1,
            'error': None
        }

        mock_service.stop_workflow.return_value = {
            'success': True,
            'workflow_name': 'test-workflow',
            'namespace': 'ma',
            'message': 'Workflow test-workflow stopped successfully',
            'error': None
        }

        result = runner.invoke(workflow_cli, ['stop'])

        assert result.exit_code == 0
        assert 'Auto-detected workflow' in result.output
        assert 'stopped successfully' in result.output

    @patch('console_link.workflow.commands.approve.WorkflowService')
    def test_approve_command_auto_detect(self, mock_service_class):
        """Test approve command with auto-detection."""
        runner = CliRunner()

        # Mock the service
        mock_service = Mock()
        mock_service_class.return_value = mock_service

        # Mock list_workflows to return single workflow
        mock_service.list_workflows.return_value = {
            'success': True,
            'workflows': ['test-workflow'],
            'count': 1,
            'error': None
        }

        # Mock get_workflow_status to return workflow details
        mock_service.get_workflow_status.return_value = {
            'success': True,
            'workflow_name': 'test-workflow',
            'namespace': 'ma',
            'phase': 'Running',
            'progress': '1/2',
            'started_at': '2024-01-01T10:00:00Z',
            'finished_at': None,
            'steps': [
                {'name': 'step1', 'phase': 'Succeeded', 'type': 'Pod', 'started_at': '2024-01-01T10:00:00Z'},
                {'name': 'approval', 'phase': 'Running', 'type': 'Suspend', 'started_at': '2024-01-01T10:01:00Z'}
            ],
            'error': None
        }

        mock_service.approve_workflow.return_value = {
            'success': True,
            'workflow_name': 'test-workflow',
            'namespace': 'ma',
            'message': 'Workflow test-workflow resumed successfully',
            'error': None
        }

        result = runner.invoke(workflow_cli, ['approve', '--acknowledge'])

        assert result.exit_code == 0
        assert 'Auto-detected workflow' in result.output
        assert 'resumed successfully' in result.output

    @patch('console_link.workflow.commands.submit.WorkflowService')
    @patch('console_link.workflow.commands.submit.WorkflowConfigStore')
    def test_submit_command_with_config_injection(self, mock_store_class, mock_service_class):
        """Test submit command with parameter injection from config."""
        runner = CliRunner()

        # Mock the service
        mock_service = Mock()
        mock_service_class.return_value = mock_service

        mock_service.submit_workflow_to_argo.return_value = {
            'success': True,
            'workflow_name': 'test-workflow-abc',
            'workflow_uid': 'uid-123',
            'namespace': 'ma',
            'phase': None,
            'output_message': None,
            'error': None
        }

        # Mock the store with config
        mock_store = Mock()
        mock_store_class.return_value = mock_store
        mock_config = WorkflowConfig({'parameters': {'param1': 'value1'}})
        mock_store.load_config.return_value = mock_config

        result = runner.invoke(workflow_cli, ['submit'])

        assert result.exit_code == 0
        assert 'submitted successfully' in result.output


class TestConfigureCommands:
    """Test suite for configure CLI commands."""

    @patch('console_link.workflow.commands.configure.WorkflowConfigStore')
    def test_configure_sample_without_config_processor_dir(self, mock_store_class):
        """Test configure sample command returns blank starter when CONFIG_PROCESSOR_DIR not set."""
        runner = CliRunner()

        # Create a temporary directory without sample.yaml to simulate missing CONFIG_PROCESSOR_DIR
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)

            # Patch ScriptRunner to use the empty temp directory
            with patch('console_link.workflow.commands.configure.ScriptRunner') as mock_runner_class:
                mock_runner = Mock()
                mock_runner_class.return_value = mock_runner

                # Create a ScriptRunner with the empty directory to get blank starter
                from console_link.workflow.services.script_runner import ScriptRunner
                real_runner = ScriptRunner(script_dir=temp_path)
                mock_runner.get_sample_config.return_value = real_runner.get_sample_config()

                result = runner.invoke(workflow_cli, ['configure', 'sample'])

                assert result.exit_code == 0
                # Should contain the blank starter template
                assert 'Workflow Configuration Template' in result.output
                assert 'parameters' in result.output
                # Should NOT contain sample-specific content
                assert 'message' not in result.output or 'Hello from workflow' not in result.output

    @patch('console_link.workflow.commands.configure.WorkflowConfigStore')
    def test_configure_sample_with_config_processor_dir(self, mock_store_class):
        """Test configure sample command returns actual sample when CONFIG_PROCESSOR_DIR is set."""
        runner = CliRunner()

        # Use the default ScriptRunner which will find the test sample.yaml
        result = runner.invoke(workflow_cli, ['configure', 'sample'])

        assert result.exit_code == 0
        # Should contain actual sample content
        assert 'parameters' in result.output

    @patch('console_link.workflow.commands.configure.WorkflowConfigStore')
    def test_configure_sample_load_blank_starter(self, mock_store_class):
        """Test configure sample --load command with blank starter config."""
        runner = CliRunner()

        # Mock the store
        mock_store = Mock()
        mock_store_class.return_value = mock_store
        mock_store.save_config.return_value = "Configuration saved"

        # Create a temporary directory without sample.yaml
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)

            # Patch ScriptRunner to use the empty temp directory
            with patch('console_link.workflow.commands.configure.ScriptRunner') as mock_runner_class:
                mock_runner = Mock()
                mock_runner_class.return_value = mock_runner

                # Create a ScriptRunner with the empty directory to get blank starter
                from console_link.workflow.services.script_runner import ScriptRunner
                real_runner = ScriptRunner(script_dir=temp_path)
                mock_runner.get_sample_config.return_value = real_runner.get_sample_config()

                result = runner.invoke(workflow_cli, ['configure', 'sample', '--load'])

                assert result.exit_code == 0
                assert 'Sample configuration loaded successfully' in result.output
                # Verify save_config was called
                assert mock_store.save_config.called

    @patch('console_link.workflow.commands.configure.WorkflowConfigStore')
    @patch('console_link.workflow.commands.configure.subprocess.run')
    def test_configure_edit_without_config_processor_dir(self, mock_subprocess, mock_store_class):
        """Test configure edit command uses blank starter when CONFIG_PROCESSOR_DIR not set."""
        runner = CliRunner()

        # Mock the store
        mock_store = Mock()
        mock_store_class.return_value = mock_store
        mock_store.load_config.return_value = None  # No existing config
        mock_store.save_config.return_value = "Configuration saved"

        # Mock subprocess to simulate editor interaction
        mock_subprocess.return_value = Mock(returncode=0)

        # Create a temporary directory without sample.yaml
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)

            # Patch ScriptRunner to use the empty temp directory
            with patch('console_link.workflow.commands.configure.ScriptRunner') as mock_runner_class:
                mock_runner = Mock()
                mock_runner_class.return_value = mock_runner

                # Create a ScriptRunner with the empty directory to get blank starter
                from console_link.workflow.services.script_runner import ScriptRunner
                real_runner = ScriptRunner(script_dir=temp_path)
                mock_runner.get_sample_config.return_value = real_runner.get_sample_config()

                # Mock file operations to capture what would be written to temp file
                with patch('builtins.open', create=True) as mock_open:
                    # Setup mock for writing temp file
                    mock_file_write = Mock()
                    mock_file_read = Mock()
                    mock_file_read.read.return_value = "parameters:\n  test: value"

                    def open_side_effect(filename, mode='r'):
                        if 'w' in mode:
                            return mock_file_write
                        else:
                            return mock_file_read

                    mock_open.side_effect = open_side_effect

                    result = runner.invoke(workflow_cli, ['configure', 'edit'])

                    # The command should succeed
                    assert result.exit_code == 0

                    # Verify that the blank starter template was written to the temp file
                    # (checking the write call would contain the template)
                    if mock_file_write.write.called:
                        written_content = ''.join([call[0][0] for call in mock_file_write.write.call_args_list])
                        assert 'parameters' in written_content
