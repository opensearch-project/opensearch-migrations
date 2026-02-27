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

    @patch('console_link.workflow.commands.status.requests.get')
    @patch('console_link.workflow.commands.status.WorkflowService')
    def test_status_command_single_workflow(self, mock_service_class, mock_requests_get):
        """Test status command for a specific workflow."""
        runner = CliRunner()

        # Mock the service
        mock_service = Mock()
        mock_service_class.return_value = mock_service

        # Mock the Argo API response with full workflow data
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'metadata': {
                'name': 'test-workflow',
                'namespace': 'ma'
            },
            'status': {
                'phase': 'Running',
                'startedAt': '2024-01-01T10:00:00Z',
                'finishedAt': None,
                'nodes': {
                    'test-workflow': {
                        'id': 'test-workflow',
                        'displayName': 'test-workflow',
                        'type': 'Steps',
                        'phase': 'Running'
                    },
                    'test-workflow-step1': {
                        'id': 'test-workflow-step1',
                        'displayName': 'step1',
                        'type': 'Pod',
                        'phase': 'Succeeded',
                        'boundaryID': 'test-workflow',
                        'startedAt': '2024-01-01T10:00:00Z'
                    },
                    'test-workflow-step2': {
                        'id': 'test-workflow-step2',
                        'displayName': 'step2',
                        'type': 'Pod',
                        'phase': 'Running',
                        'boundaryID': 'test-workflow',
                        'startedAt': '2024-01-01T10:01:00Z'
                    }
                }
            }
        }
        mock_requests_get.return_value = mock_response

        result = runner.invoke(workflow_cli, ['status', '--workflow-name', 'test-workflow'])

        assert result.exit_code == 0
        assert 'test-workflow' in result.output
        assert 'Running' in result.output
        assert 'step1' in result.output
        assert 'step2' in result.output
        assert 'workflow output test-workflow' in result.output

    @patch('console_link.workflow.commands.status.requests.get')
    @patch('console_link.workflow.commands.status.WorkflowService')
    def test_status_command_list_all(self, mock_service_class, mock_requests_get):
        """Test status command listing all workflows."""
        runner = CliRunner()

        # Mock the service
        mock_service = Mock()
        mock_service_class.return_value = mock_service

        wf1_data = {
            'metadata': {'name': 'workflow-1', 'namespace': 'ma'},
            'status': {
                'phase': 'Running',
                'startedAt': '2024-01-01T10:00:00Z',
                'finishedAt': None,
                'nodes': {
                    'workflow-1': {
                        'id': 'workflow-1',
                        'displayName': 'workflow-1',
                        'type': 'Steps',
                        'phase': 'Running'
                    }
                }
            }
        }
        wf2_data = {
            'metadata': {'name': 'workflow-2', 'namespace': 'ma'},
            'status': {
                'phase': 'Succeeded',
                'startedAt': '2024-01-01T09:00:00Z',
                'finishedAt': '2024-01-01T09:05:00Z',
                'nodes': {
                    'workflow-2': {
                        'id': 'workflow-2',
                        'displayName': 'workflow-2',
                        'type': 'Steps',
                        'phase': 'Succeeded'
                    }
                }
            }
        }

        # Mock requests.get to return list response and individual workflow data
        def mock_get_response(*args, **kwargs):
            url = args[0]
            mock_response = Mock()
            mock_response.status_code = 200

            if 'workflow-1' in url:
                mock_response.json.return_value = wf1_data
            elif 'workflow-2' in url:
                mock_response.json.return_value = wf2_data
            elif 'archived-workflows' in url:
                mock_response.json.return_value = {'items': []}
            else:
                # List endpoint: /api/v1/workflows/ma
                mock_response.json.return_value = {
                    'items': [wf1_data, wf2_data]
                }
            return mock_response

        mock_requests_get.side_effect = mock_get_response

        result = runner.invoke(workflow_cli, ['status', '--all-workflows', '--all'])

        assert result.exit_code == 0
        assert 'Found 2 workflow(s)' in result.output
        assert 'workflow-1' in result.output
        assert 'workflow-2' in result.output

    @patch('console_link.workflow.commands.stop.WorkflowService')
    def test_stop_command(self, mock_service_class):
        """Test stop command with default workflow name."""
        runner = CliRunner()

        # Mock the service
        mock_service = Mock()
        mock_service_class.return_value = mock_service

        mock_service.stop_workflow.return_value = {
            'success': True,
            'workflow_name': 'migration-workflow',
            'namespace': 'ma',
            'message': 'Workflow migration-workflow stopped successfully',
            'error': None
        }

        result = runner.invoke(workflow_cli, ['stop'])

        assert result.exit_code == 0
        assert 'stopped successfully' in result.output

    @patch('console_link.workflow.commands.approve.WorkflowService')
    @patch('console_link.workflow.commands.approve._fetch_suspended_step_names')
    def test_approve_command_with_exact_key(self, mock_fetch, mock_service_class):
        """Test approve command with exact key match."""
        runner = CliRunner()

        mock_fetch.return_value = [
            ('node-1', 'source.target.metadataMigrate', 'metadata-migrate'),
            ('node-2', 'source.target.backfill', 'backfill-step')
        ]

        mock_service = Mock()
        mock_service_class.return_value = mock_service
        mock_service.approve_workflow.return_value = {
            'success': True, 'workflow_name': 'migration-workflow',
            'namespace': 'ma', 'message': 'Approved', 'error': None
        }

        result = runner.invoke(workflow_cli, ['approve', 'source.target.metadataMigrate'])

        assert result.exit_code == 0
        assert 'Approved 1 step' in result.output
        mock_service.approve_workflow.assert_called_once()
        call_kwargs = mock_service.approve_workflow.call_args[1]
        assert call_kwargs['node_field_selector'] == 'id=node-1'

    @patch('console_link.workflow.commands.approve.WorkflowService')
    @patch('console_link.workflow.commands.approve._fetch_suspended_step_names')
    def test_approve_command_with_glob_pattern(self, mock_fetch, mock_service_class):
        """Test approve command with glob pattern matching multiple steps."""
        runner = CliRunner()

        mock_fetch.return_value = [
            ('node-1', 'a.b.metadataMigrate', 'meta-1'),
            ('node-2', 'x.y.metadataMigrate', 'meta-2'),
            ('node-3', 'a.b.backfill', 'backfill')
        ]

        mock_service = Mock()
        mock_service_class.return_value = mock_service
        mock_service.approve_workflow.return_value = {
            'success': True, 'workflow_name': 'migration-workflow',
            'namespace': 'ma', 'message': 'Approved', 'error': None
        }

        result = runner.invoke(workflow_cli, ['approve', '*.metadataMigrate'])

        assert result.exit_code == 0
        assert 'Approved 2 step' in result.output
        assert mock_service.approve_workflow.call_count == 2

    @patch('console_link.workflow.commands.approve.WorkflowService')
    @patch('console_link.workflow.commands.approve._fetch_suspended_step_names')
    def test_approve_command_with_multiple_task_names(self, mock_fetch, mock_service_class):
        """Test approve command with multiple task names."""
        runner = CliRunner()

        mock_fetch.return_value = [
            ('node-1', 'step1', 'step-1'),
            ('node-2', 'step2', 'step-2'),
            ('node-3', 'step3', 'step-3')
        ]

        mock_service = Mock()
        mock_service_class.return_value = mock_service
        mock_service.approve_workflow.return_value = {
            'success': True, 'workflow_name': 'migration-workflow',
            'namespace': 'ma', 'message': 'Approved', 'error': None
        }

        result = runner.invoke(workflow_cli, ['approve', 'step1', 'step3'])

        assert result.exit_code == 0
        assert 'Approved 2 step' in result.output
        assert mock_service.approve_workflow.call_count == 2

    @patch('console_link.workflow.commands.approve._fetch_suspended_step_names')
    def test_approve_command_no_matches(self, mock_fetch):
        """Test approve command when key matches no suspended steps."""
        runner = CliRunner()

        mock_fetch.return_value = [('node-1', 'source.target.backfill', 'backfill')]

        result = runner.invoke(workflow_cli, ['approve', 'nonexistent'])

        assert result.exit_code != 0
        assert "No suspended steps match" in result.output
        assert 'source.target.backfill' in result.output

    @patch('console_link.workflow.commands.approve._fetch_suspended_step_names')
    def test_approve_command_no_suspended_steps(self, mock_fetch):
        """Test approve command fails when no steps are suspended."""
        runner = CliRunner()

        mock_fetch.return_value = []

        result = runner.invoke(workflow_cli, ['approve', 'anykey'])

        assert result.exit_code != 0
        assert 'No suspended steps found' in result.output

    def test_approve_command_missing_task_names(self):
        """Test approve command fails without required task names."""
        runner = CliRunner()

        result = runner.invoke(workflow_cli, ['approve'])

        assert result.exit_code != 0
        assert "Missing argument 'TASK_NAMES...'" in result.output

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

    @patch('console_link.workflow.commands.configure.get_workflow_config_store')
    def test_configure_sample_load(self, mock_get_workflow_config):
        """Test configure sample --load command."""
        runner = CliRunner()

        # Mock the store
        mock_store = Mock()
        mock_get_workflow_config.return_value = mock_store
        mock_store.save_config.return_value = "Configuration saved"

        result = runner.invoke(workflow_cli, ['configure', 'sample', '--load'])

        assert result.exit_code == 0
        assert 'Sample configuration loaded successfully' in result.output
        # Verify save_config was called
        assert mock_store.save_config.called


class TestApprovalCompletions:
    """Test suite for approval key autocompletion."""

    @patch('console_link.workflow.commands.approve._fetch_suspended_step_names')
    def test_get_approval_key_completions(self, mock_fetch):
        """Test autocompletion returns suspended step names."""
        from console_link.workflow.commands.approve import get_approval_task_name_completions, _get_cache_file

        mock_fetch.return_value = [
            ('node-1', 'source.target.metadataMigrate', 'meta'),
            ('node-2', 'source.target.backfill', 'backfill')
        ]

        # Clear cache
        cache_file = _get_cache_file('migration-workflow')
        if cache_file.exists():
            cache_file.unlink()

        ctx = Mock()
        ctx.params = {'workflow_name': 'migration-workflow', 'namespace': 'ma'}

        completions = get_approval_task_name_completions(ctx, None, 'source')

        assert len(completions) == 2
        values = [c.value for c in completions]
        assert 'source.target.metadataMigrate' in values
        assert 'source.target.backfill' in values

    @patch('console_link.workflow.commands.approve._fetch_suspended_step_names')
    def test_get_approval_key_completions_caching(self, mock_fetch):
        """Test that completions are cached."""
        from console_link.workflow.commands.approve import get_approval_task_name_completions, _get_cache_file

        mock_fetch.return_value = [('node-1', 'step.name', 'step')]

        # Clear cache
        cache_file = _get_cache_file('migration-workflow')
        if cache_file.exists():
            cache_file.unlink()

        ctx = Mock()
        ctx.params = {'workflow_name': 'migration-workflow', 'namespace': 'ma'}

        # First call - should fetch
        get_approval_task_name_completions(ctx, None, '')
        assert mock_fetch.call_count == 1

        # Second call - should use cache
        get_approval_task_name_completions(ctx, None, '')
        assert mock_fetch.call_count == 1  # Still 1, used cache
