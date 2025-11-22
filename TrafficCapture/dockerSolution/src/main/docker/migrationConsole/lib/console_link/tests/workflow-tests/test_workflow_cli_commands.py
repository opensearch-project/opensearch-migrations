"""Integration tests for workflow CLI commands."""

from click.testing import CliRunner
from unittest.mock import Mock, patch

from console_link.workflow.cli import workflow_cli
from console_link.workflow.models.config import WorkflowConfig


class TestWorkflowCLICommands:
    """Test suite for workflow CLI command integration."""

    @patch('console_link.workflow.services.script_runner.subprocess.run')
    @patch('console_link.workflow.commands.submit.WorkflowConfigStore')
    def test_submit_command_basic(self, mock_store_class, mock_subprocess):
        """Test basic submit command execution."""
        # Mock subprocess to avoid actual Kubernetes submission
        mock_subprocess.return_value = Mock(
            returncode=0,
            stdout='{"workflow_name": "test-workflow-abc", "workflow_uid": "uid-123", "namespace": "ma"}'
        )

        runner = CliRunner()

        # Mock the store with a valid config
        mock_store = Mock()
        mock_store_class.return_value = mock_store
        mock_config = WorkflowConfig({
            'parameters': {
                'message': 'test',
                'requiresApproval': False,
                'approver': ''
            }
        })
        mock_store.load_config.return_value = mock_config

        result = runner.invoke(workflow_cli, ['submit'])

        assert result.exit_code == 0
        assert 'submitted successfully' in result.output
        # Check for workflow name pattern from test scripts (test-workflow-<timestamp>)
        assert 'test-workflow-' in result.output

    @patch('console_link.workflow.services.script_runner.subprocess.run')
    @patch('console_link.workflow.commands.submit.WorkflowService')
    @patch('console_link.workflow.commands.submit.WorkflowConfigStore')
    def test_submit_command_with_wait(self, mock_store_class, mock_service_class, mock_subprocess):
        """Test submit command with --wait flag."""
        # Mock subprocess to avoid actual Kubernetes submission
        mock_subprocess.return_value = Mock(
            returncode=0,
            stdout='{"workflow_name": "test-workflow-abc", "workflow_uid": "uid-123", "namespace": "ma"}'
        )

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
        mock_config = WorkflowConfig({
            'parameters': {
                'message': 'test',
                'requiresApproval': False,
                'approver': ''
            }
        })
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
        assert 'workflow output test-workflow' in result.output

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
        # Note: output suggestion is not shown when --acknowledge flag is used

    @patch('console_link.workflow.services.script_runner.subprocess.run')
    @patch('console_link.workflow.commands.submit.WorkflowConfigStore')
    def test_submit_command_with_config_injection(self, mock_store_class, mock_subprocess):
        """Test submit command with parameter injection from config."""
        # Mock subprocess to avoid actual Kubernetes submission
        mock_subprocess.return_value = Mock(
            returncode=0,
            stdout='{"workflow_name": "test-workflow-def", "workflow_uid": "uid-789", "namespace": "ma"}'
        )

        runner = CliRunner()

        # Mock the store with config
        mock_store = Mock()
        mock_store_class.return_value = mock_store
        mock_config = WorkflowConfig({
            'parameters': {
                'message': 'test message',
                'requiresApproval': False,
                'approver': ''
            }
        })
        mock_store.load_config.return_value = mock_config

        result = runner.invoke(workflow_cli, ['submit'])

        assert result.exit_code == 0
        assert 'submitted successfully' in result.output
        # Check for workflow name pattern from test scripts
        assert 'test-workflow-' in result.output


class TestConfigureCommands:
    """Test suite for configure CLI commands."""

    @patch('console_link.workflow.commands.configure.get_store')
    def test_configure_sample_load(self, mock_get_store):
        """Test configure sample --load command."""
        runner = CliRunner()

        # Mock the store
        mock_store = Mock()
        mock_get_store.return_value = mock_store
        mock_store.save_config.return_value = "Configuration saved"

        result = runner.invoke(workflow_cli, ['configure', 'sample', '--load'])

        assert result.exit_code == 0
        assert 'Sample configuration loaded successfully' in result.output
        # Verify save_config was called
        assert mock_store.save_config.called
