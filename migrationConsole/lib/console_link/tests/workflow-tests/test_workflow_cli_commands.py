"""Integration tests for workflow CLI commands."""

from click.testing import CliRunner
from unittest.mock import Mock, patch
from kubernetes.client.rest import ApiException

from console_link.workflow.cli import workflow_cli
from console_link.workflow.models.config import WorkflowConfig


class TestWorkflowCLICommands:
    """Test suite for workflow CLI command integration."""

    def test_short_help_alias_works_for_workflow_commands(self):
        runner = CliRunner()

        for args in (
            ['-h'],
            ['configure', '-h'],
            ['configure', 'credentials', 'create', '-h'],
            ['approve', 'step', '-h'],
            ['log', 'filter', '-h'],
            ['show', '-h'],
            ['status', '-h'],
            ['submit', '-h'],
            ['reset', '-h'],
        ):
            result = runner.invoke(workflow_cli, args)
            assert result.exit_code == 0
            assert 'Usage:' in result.output

    def test_workflow_selection_options_are_hidden_from_help(self):
        runner = CliRunner()

        for args in (
            ['status', '--help'],
            ['submit', '--help'],
            ['manage', '--help'],
            ['approve', 'step', '--help'],
            ['log', 'filter', '--help'],
            ['show', '--help'],
        ):
            result = runner.invoke(workflow_cli, args)
            assert result.exit_code == 0
            assert '--workflow-name' not in result.output
            assert '--all-workflows' not in result.output

    @patch('console_link.workflow.commands.submit.verify_configured_secrets_exist')
    @patch('console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace')
    @patch('console_link.workflow.commands.submit.delete_workflow')
    @patch('console_link.workflow.commands.submit.stop_workflow')
    @patch('console_link.workflow.commands.submit.workflow_exists')
    @patch('console_link.workflow.commands.submit.load_k8s_config')
    @patch('console_link.workflow.services.script_runner.subprocess.run')
    @patch('console_link.workflow.commands.submit.WorkflowConfigStore')
    def test_submit_command_basic(
        self,
        mock_store_class,
        mock_subprocess,
        _mock_k8s,
        mock_exists,
        mock_stop,
        mock_delete,
        _mock_get_secret_store,
        _mock_verify_secrets,
    ):
        """Test basic submit command execution."""
        # Mock subprocess to avoid actual Kubernetes submission
        mock_subprocess.return_value = Mock(
            returncode=0,
            stdout='{"workflow_name": "test-workflow-abc", "workflow_uid": "uid-123", "namespace": "ma"}'
        )
        mock_exists.return_value = False

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
        # The stale "NOT checking" warning should no longer appear.
        assert 'NOT checking' not in result.output
        # Check for workflow name pattern from test scripts (test-workflow-<timestamp>)
        assert 'test-workflow-' in result.output
        assert "--workflow-name" in mock_subprocess.call_args[0][0]
        assert "migration-workflow" in mock_subprocess.call_args[0][0]
        mock_stop.assert_not_called()
        mock_delete.assert_not_called()
        # The secret-existence check must be invoked before workflow submission.
        _mock_verify_secrets.assert_called_once()

    @patch('console_link.workflow.commands.submit.verify_configured_secrets_exist')
    @patch('console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace')
    @patch('console_link.workflow.commands.submit.load_k8s_config')
    @patch('console_link.workflow.services.script_runner.subprocess.run')
    @patch('console_link.workflow.commands.submit.WorkflowConfigStore')
    def test_submit_command_fails_fast_when_secrets_missing(
        self,
        mock_store_class,
        mock_subprocess,
        _mock_k8s,
        _mock_get_secret_store,
        mock_verify_secrets,
    ):
        """Submit must abort before calling the workflow-submit script when a
        referenced HTTP-Basic secret is missing from the cluster."""
        import click

        # The secret check raises a click exception describing which secret is missing.
        mock_verify_secrets.side_effect = click.ClickException(
            "Found 1 missing secret that must be created to make well-formed HTTP-Basic "
            "requests to clusters:\n  source-cluster-secret\n\nRun `workflow configure edit` "
            "to create them."
        )

        runner = CliRunner()

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

        assert result.exit_code != 0
        assert 'source-cluster-secret' in result.output
        assert 'workflow configure edit' in result.output
        assert 'submitted successfully' not in result.output
        # The script_runner subprocess (which actually submits the workflow) must NOT
        # have been invoked — the check has to short-circuit before that.
        mock_subprocess.assert_not_called()

    @patch('console_link.workflow.commands.submit.verify_configured_secrets_exist')
    @patch('console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace')
    @patch('console_link.workflow.commands.submit.delete_workflow')
    @patch('console_link.workflow.commands.submit.stop_workflow')
    @patch('console_link.workflow.commands.submit.workflow_exists')
    @patch('console_link.workflow.commands.submit.load_k8s_config')
    @patch('console_link.workflow.services.script_runner.subprocess.run')
    @patch('console_link.workflow.commands.submit.WorkflowService')
    @patch('console_link.workflow.commands.submit.WorkflowConfigStore')
    def test_submit_command_with_wait(
        self,
        mock_store_class,
        mock_service_class,
        mock_subprocess,
        _mock_k8s,
        mock_exists,
        mock_stop,
        mock_delete,
        _mock_get_secret_store,
        _mock_verify_secrets,
    ):
        """Test submit command with --wait flag."""
        # Mock subprocess to avoid actual Kubernetes submission
        mock_subprocess.return_value = Mock(
            returncode=0,
            stdout='{"workflow_name": "test-workflow-abc", "workflow_uid": "uid-123", "namespace": "ma"}'
        )
        mock_exists.return_value = False

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
        mock_stop.assert_not_called()
        mock_delete.assert_not_called()

    @patch('console_link.workflow.commands.submit.verify_configured_secrets_exist')
    @patch('console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace')
    @patch('console_link.workflow.commands.submit.delete_workflow')
    @patch('console_link.workflow.commands.submit.wait_until_workflow_deleted')
    @patch('console_link.workflow.commands.submit.stop_workflow')
    @patch('console_link.workflow.commands.submit.workflow_exists')
    @patch('console_link.workflow.commands.submit.load_k8s_config')
    @patch('console_link.workflow.services.script_runner.subprocess.run')
    @patch('console_link.workflow.commands.submit.WorkflowConfigStore')
    def test_submit_command_replaces_existing_workflow(
        self,
        mock_store_class,
        mock_subprocess,
        _mock_k8s,
        mock_exists,
        mock_stop,
        mock_wait_until_deleted,
        mock_delete,
        _mock_get_secret_store,
        _mock_verify_secrets,
    ):
        """Test submit replaces an existing workflow before resubmitting."""
        mock_subprocess.return_value = Mock(
            returncode=0,
            stdout='{"workflow_name": "test-workflow-abc", "workflow_uid": "uid-123", "namespace": "ma"}'
        )
        mock_exists.return_value = True
        mock_stop.return_value = True
        mock_delete.return_value = True
        mock_wait_until_deleted.return_value = True

        runner = CliRunner()

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

        result = runner.invoke(workflow_cli, ['submit', '--workflow-name', 'migration-workflow'])

        assert result.exit_code == 0
        assert "Existing workflow 'migration-workflow' found; replacing..." in result.output
        assert 'Stopped' in result.output
        assert 'Deleted' in result.output
        mock_exists.assert_called_once_with('ma', 'migration-workflow')
        mock_stop.assert_called_once_with('ma', 'migration-workflow')
        mock_delete.assert_called_once_with('ma', 'migration-workflow')
        mock_wait_until_deleted.assert_called_once_with('ma', 'migration-workflow')

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
        assert 'workflow log all' in result.output
        assert '--workflow-name' not in result.output

    @patch('console_link.workflow.commands.status.requests.get')
    @patch('console_link.workflow.commands.status.WorkflowService')
    def test_status_command_list_all(self, mock_service_class, mock_requests_get):
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

        # Mock requests.get to return workflow data for each workflow
        def mock_get_response(*args, **kwargs):
            url = args[0]
            mock_response = Mock()
            mock_response.status_code = 200
            
            if 'workflow-1' in url:
                mock_response.json.return_value = {
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
            elif 'workflow-2' in url:
                mock_response.json.return_value = {
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
            return mock_response
        
        mock_requests_get.side_effect = mock_get_response

        result = runner.invoke(workflow_cli, ['status', '--all-workflows'])

        assert result.exit_code == 0
        assert 'Found 2 workflow(s)' in result.output
        assert 'workflow-1' in result.output
        assert 'workflow-2' in result.output

    # ─────────────────────────────────────────────
    # approve subcommand tests
    # ─────────────────────────────────────────────

    def _make_gate(self, name, category='step', status='waiting', **labels):
        from console_link.workflow.commands.approve import GateInfo
        return GateInfo(name=name, category=category, status=status, labels=labels)

    def test_approve_without_subcommand_errors(self):
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['approve'])
        assert result.exit_code != 0

    @patch('console_link.workflow.commands.approve._list_all_categories')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_list_shows_all_categories(self, mock_k8s, mock_list_all):
        runner = CliRunner()

        result = runner.invoke(workflow_cli, ['approve', '--list'])

        assert result.exit_code == 0
        mock_list_all.assert_called_once_with('ma', 'migration-workflow')

    @patch('console_link.workflow.commands.approve.approve_gate')
    @patch('console_link.workflow.commands.approve._gather_gates')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_step_with_exact_name(self, mock_k8s, mock_gather, mock_approve):
        runner = CliRunner()
        mock_gather.return_value = [
            self._make_gate('evaluatemetadata.source-target'),
            self._make_gate('migratemetadata.source-target'),
        ]
        mock_approve.return_value = True

        result = runner.invoke(
            workflow_cli,
            ['approve', 'step', 'evaluatemetadata.source-target']
        )

        assert result.exit_code == 0
        assert 'Approved 1 gate' in result.output
        mock_approve.assert_called_once_with('ma', 'evaluatemetadata.source-target')

    @patch('console_link.workflow.commands.approve.approve_gate')
    @patch('console_link.workflow.commands.approve._gather_gates')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_step_with_glob(self, mock_k8s, mock_gather, mock_approve):
        runner = CliRunner()
        mock_gather.return_value = [
            self._make_gate('metadatamigrate.a-b'),
            self._make_gate('metadatamigrate.x-y'),
            self._make_gate('backfill.a-b'),
        ]
        mock_approve.return_value = True

        result = runner.invoke(workflow_cli, ['approve', 'step', 'metadatamigrate.*'])

        assert result.exit_code == 0
        assert 'Approved 2 gate' in result.output
        assert mock_approve.call_count == 2

    @patch('console_link.workflow.commands.approve.approve_gate')
    @patch('console_link.workflow.commands.approve._gather_gates')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_step_all(self, mock_k8s, mock_gather, mock_approve):
        runner = CliRunner()
        mock_gather.return_value = [
            self._make_gate('a'), self._make_gate('b'), self._make_gate('c'),
        ]
        mock_approve.return_value = True

        result = runner.invoke(workflow_cli, ['approve', 'step', '--all'])

        assert result.exit_code == 0
        assert 'Approved 3 gate' in result.output
        assert mock_approve.call_count == 3

    @patch('console_link.workflow.commands.approve._gather_gates')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_step_list(self, mock_k8s, mock_gather):
        runner = CliRunner()
        mock_gather.return_value = [self._make_gate('gate-one')]

        result = runner.invoke(workflow_cli, ['approve', 'step', '--list'])

        assert result.exit_code == 0
        assert 'gate-one' in result.output
        args, kwargs = mock_gather.call_args
        assert args[:3] == ('ma', 'migration-workflow', 'step')
        assert kwargs == {'pre_approve': True, 'include_completed': True}

    @patch('console_link.workflow.commands.approve._waiting_gates_from_workflow')
    @patch('console_link.workflow.commands.approve._list_all_gates')
    def test_gather_gates_excludes_completed_by_default(
        self, mock_list_gates, mock_waiting_gates
    ):
        from console_link.workflow.commands.approve import _gather_gates

        mock_list_gates.return_value = [
            ('done-step', 'Approved', {}),
            ('future-step', 'Pending', {}),
        ]
        mock_waiting_gates.return_value = []

        gates = _gather_gates('ma', 'migration-workflow', 'step', pre_approve=True)

        assert [(gate.name, gate.status) for gate in gates] == [
            ('future-step', 'pending')
        ]

    @patch('console_link.workflow.commands.approve._waiting_gates_from_workflow')
    @patch('console_link.workflow.commands.approve._list_all_gates')
    def test_gather_gates_can_include_completed(
        self, mock_list_gates, mock_waiting_gates
    ):
        from console_link.workflow.commands.approve import _gather_gates

        mock_list_gates.return_value = [
            ('done-step', 'Approved', {}),
            ('future-step', 'Pending', {}),
        ]
        mock_waiting_gates.return_value = []

        gates = _gather_gates(
            'ma', 'migration-workflow', 'step',
            pre_approve=True, include_completed=True,
        )

        assert [(gate.name, gate.status) for gate in gates] == [
            ('done-step', 'approved'),
            ('future-step', 'pending'),
        ]

    @patch('console_link.workflow.commands.approve._waiting_gates_from_workflow')
    @patch('console_link.workflow.commands.approve._list_all_gates')
    def test_gather_gates_uses_approved_phase_for_stale_waiting_node(
        self, mock_list_gates, mock_waiting_gates
    ):
        from console_link.workflow.commands.approve import _gather_gates

        mock_list_gates.return_value = [
            ('done-step', 'Approved', {}),
        ]
        mock_waiting_gates.return_value = [('done-step', None)]

        list_gates = _gather_gates(
            'ma', 'migration-workflow', 'step',
            pre_approve=True, include_completed=True,
        )
        completion_gates = _gather_gates(
            'ma', 'migration-workflow', 'step',
            pre_approve=True, include_completed=False,
        )

        assert [(gate.name, gate.status) for gate in list_gates] == [
            ('done-step', 'approved')
        ]
        assert completion_gates == []

    @patch('console_link.workflow.commands.approve._list_all_gates')
    def test_find_already_approved_is_workflow_scoped(self, mock_list_gates):
        from console_link.workflow.commands.approve import _find_already_approved

        mock_list_gates.return_value = [
            ('done-step', 'Approved', {}),
        ]

        approved = _find_already_approved(
            'ma', 'migration-workflow',
            ['done-step'], 'step',
        )

        assert approved == ['done-step']
        mock_list_gates.assert_called_once_with('ma', 'migration-workflow')

    @patch('console_link.workflow.commands.approve._list_all_gates')
    def test_find_already_approved_accepts_runtime_categories(self, mock_list_gates):
        from console_link.workflow.commands.approve import _find_already_approved

        mock_list_gates.return_value = [
            ('captureproxy.capture-proxy.vapretry', 'Approved', {
                'migrations.opensearch.org/resource-kind': 'CaptureProxy',
                'migrations.opensearch.org/resource-name': 'capture-proxy',
            }),
        ]

        approved = _find_already_approved(
            'ma', 'migration-workflow',
            ['captureproxy.capture-proxy'], 'change',
        )

        assert approved == ['captureproxy.capture-proxy']

    @patch('console_link.workflow.commands.approve._gather_gates')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_completion_keeps_completed_filtered(self, mock_k8s, mock_gather):
        from console_link.workflow.commands.approve import _complete_names

        ctx = Mock()
        ctx.params = {
            'namespace': 'ma',
            'workflow_name': 'migration-workflow',
            'pre_approve': True,
        }
        mock_gather.return_value = [self._make_gate('future-step')]

        completions = _complete_names('step')(ctx, None, 'future')

        assert [item.value for item in completions] == ['future-step']
        mock_gather.assert_called_once_with('ma', 'migration-workflow', 'step', True)

    @patch('console_link.workflow.commands.approve._gather_gates')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_step_list_empty(self, mock_k8s, mock_gather):
        runner = CliRunner()
        mock_gather.return_value = []

        result = runner.invoke(workflow_cli, ['approve', 'step', '--list'])

        assert result.exit_code == 0
        assert 'No gates available' in result.output

    @patch('console_link.workflow.commands.approve._gather_gates')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_step_no_matches(self, mock_k8s, mock_gather):
        runner = CliRunner()
        mock_gather.return_value = [self._make_gate('a-b-c')]

        result = runner.invoke(workflow_cli, ['approve', 'step', 'nonexistent'])

        assert result.exit_code != 0
        assert 'No gates match' in result.output
        assert 'a-b-c' in result.output

    @patch('console_link.workflow.commands.approve._gather_gates')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_step_missing_action_errors(self, mock_k8s, mock_gather):
        runner = CliRunner()
        mock_gather.return_value = [self._make_gate('a')]
        result = runner.invoke(workflow_cli, ['approve', 'step'])

        assert result.exit_code != 0
        assert '--list' in result.output or 'specify one of' in result.output

    def test_approve_retry_rejects_pre_approve(self):
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['approve', 'retry', '--pre-approve', '--list'])
        # Click treats unknown options as a usage error (exit 2).
        assert result.exit_code != 0
        assert '--pre-approve' in result.output.lower() or 'no such option' in result.output.lower()

    @patch('console_link.workflow.commands.approve._resource_still_exists')
    @patch('console_link.workflow.commands.approve.approve_gate')
    @patch('console_link.workflow.commands.approve._gather_gates')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_retry_blocks_when_resource_exists(
        self, mock_k8s, mock_gather, mock_approve, mock_exists
    ):
        runner = CliRunner()
        mock_gather.return_value = [
            self._make_gate(
                'captureproxy.capture-proxy.vapretry',
                category='retry',
                **{
                    'migrations.opensearch.org/resource-kind': 'CaptureProxy',
                    'migrations.opensearch.org/resource-name': 'capture-proxy',
                }
            )
        ]
        mock_exists.return_value = True  # resource still present → block

        result = runner.invoke(
            workflow_cli,
            ['approve', 'retry', 'captureproxy.capture-proxy.vapretry']
        )

        assert result.exit_code != 0
        assert 'still exist' in result.output or 'still exist' in result.stderr_bytes.decode('utf-8', errors='replace')
        assert mock_approve.call_count == 0

    @patch('console_link.workflow.commands.approve._resource_still_exists')
    @patch('console_link.workflow.commands.approve.approve_gate')
    @patch('console_link.workflow.commands.approve._gather_gates')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_retry_approves_when_resource_gone(
        self, mock_k8s, mock_gather, mock_approve, mock_exists
    ):
        runner = CliRunner()
        mock_gather.return_value = [
            self._make_gate(
                'captureproxy.capture-proxy.vapretry',
                category='retry',
                **{
                    'migrations.opensearch.org/resource-kind': 'CaptureProxy',
                    'migrations.opensearch.org/resource-name': 'capture-proxy',
                }
            )
        ]
        mock_exists.return_value = False  # resource absent → allow
        mock_approve.return_value = True

        result = runner.invoke(
            workflow_cli,
            ['approve', 'retry', 'captureproxy.capture-proxy.vapretry']
        )

        assert result.exit_code == 0
        assert 'Approved 1 gate' in result.output
        mock_approve.assert_called_once_with('ma', 'captureproxy.capture-proxy.vapretry')

    @patch('console_link.workflow.commands.approve.approve_gate')
    @patch('console_link.workflow.commands.approve._gather_gates')
    @patch('console_link.workflow.commands.approve.load_k8s_config')
    def test_approve_change_uses_change_category(self, mock_k8s, mock_gather, mock_approve):
        runner = CliRunner()
        mock_gather.return_value = [self._make_gate('cp.captureproxy.vapretry', category='change')]
        mock_approve.return_value = True

        result = runner.invoke(workflow_cli, ['approve', 'change', '--all'])

        assert result.exit_code == 0
        args, kwargs = mock_gather.call_args
        assert args[2] == 'change'
        assert kwargs == {'pre_approve': False, 'include_completed': False}

    @patch('console_link.workflow.commands.log._run_history_mode')
    def test_output_all_uses_workflow_selector(self, mock_history):
        runner = CliRunner()

        result = runner.invoke(workflow_cli, ['log', 'all'])

        assert result.exit_code == 0
        args, _ = mock_history.call_args
        assert args[2] == 'workflows.argoproj.io/workflow=migration-workflow'
        assert args[4] == []

    @patch('console_link.workflow.commands.log._run_history_mode')
    def test_output_filter_combines_filter_options(self, mock_history):
        runner = CliRunner()

        result = runner.invoke(
            workflow_cli,
            ['log', 'filter', '--snapshot', 'snap1', '--target', 'target1', '--', '--since=1h']
        )

        assert result.exit_code == 0
        args, _ = mock_history.call_args
        assert args[2] == (
            'migrations.opensearch.org/target=target1,'
            'migrations.opensearch.org/snapshot=snap1,'
            'workflows.argoproj.io/workflow=migration-workflow'
        )
        assert args[4] == ['--since=1h']

    @patch('console_link.workflow.commands.log.load_k8s_config')
    @patch('console_link.workflow.commands.log.client')
    @patch('console_link.workflow.commands.log._run_history_mode')
    def test_output_resource_uses_resource_labels(self, mock_history, mock_client, _mock_k8s):
        runner = CliRunner()
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_custom.get_namespaced_custom_object.return_value = {
            'metadata': {
                'labels': {
                    'migrations.opensearch.org/source': 'source1',
                    'migrations.opensearch.org/target': 'target1',
                    'strimzi.io/cluster': 'default',
                    'app.kubernetes.io/name': 'ignored',
                }
            }
        }

        result = runner.invoke(workflow_cli, ['log', 'resource', 'captureproxy.my-proxy'])

        assert result.exit_code == 0
        mock_custom.get_namespaced_custom_object.assert_called_once_with(
            group='migrations.opensearch.org',
            version='v1alpha1',
            namespace='ma',
            plural='captureproxies',
            name='my-proxy',
        )
        args, _ = mock_history.call_args
        assert args[2] == (
            'migrations.opensearch.org/source=source1,'
            'migrations.opensearch.org/target=target1,'
            'strimzi.io/cluster=default'
        )

    @patch('console_link.workflow.commands.log.load_k8s_config')
    @patch('console_link.workflow.commands.log.client')
    @patch('console_link.workflow.commands.log._run_history_mode')
    def test_output_resource_keeps_workflow_selector_for_workflow_pods(
        self, mock_history, mock_client, _mock_k8s
    ):
        runner = CliRunner()
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_custom.get_namespaced_custom_object.return_value = {
            'metadata': {
                'labels': {
                    'migrations.opensearch.org/source': 'source1',
                    'migrations.opensearch.org/task': 'captureProxy',
                }
            }
        }

        result = runner.invoke(workflow_cli, ['log', 'resource', 'captureproxy.my-proxy'])

        assert result.exit_code == 0
        args, _ = mock_history.call_args
        assert args[2] == (
            'migrations.opensearch.org/source=source1,'
            'migrations.opensearch.org/task=captureProxy,'
            'workflows.argoproj.io/workflow=migration-workflow'
        )

    @patch('console_link.workflow.commands.log._run_history_mode')
    def test_output_filter_accepts_raw_label_option(self, mock_history):
        runner = CliRunner()

        result = runner.invoke(
            workflow_cli,
            ['log', 'filter', '--label', 'custom.example/key=value']
        )

        assert result.exit_code == 0
        args, _ = mock_history.call_args
        assert args[2] == (
            'custom.example/key=value,'
            'workflows.argoproj.io/workflow=migration-workflow'
        )

    def test_output_filter_rejects_unexpected_argument(self):
        runner = CliRunner()

        result = runner.invoke(workflow_cli, ['log', 'filter', 'oops'])

        assert result.exit_code != 0
        assert 'Use filter options such as --task or --label' in result.output

    def test_output_top_level_requires_subcommand(self):
        runner = CliRunner()

        result = runner.invoke(workflow_cli, ['log'])

        assert result.exit_code != 0
        assert 'Missing command' in result.output or 'Usage:' in result.output

    @patch('console_link.workflow.commands.log.load_k8s_config')
    @patch('console_link.workflow.commands.log.list_migration_resources')
    def test_output_resource_completion_uses_migration_resource_names(self, mock_list, _mock_k8s):
        from console_link.workflow.commands.log import _get_resource_completions

        ctx = Mock()
        ctx.params = {'namespace': 'ma'}
        mock_list.return_value = [
            ('captureproxies', 'my-proxy', 'Ready', []),
            ('snapshotmigrations', 'migration-0', 'Ready', []),
        ]

        completions = _get_resource_completions(ctx, None, 'capture')

        assert completions == ['captureproxy.my-proxy']

    @patch('console_link.workflow.commands.show.load_k8s_config')
    @patch('console_link.workflow.commands.show.client')
    @patch('console_link.workflow.commands.show.read_artifact_text')
    def test_workflow_show_task_prints_all_current_outputs(
        self, mock_read_artifact, mock_client, _mock_k8s
    ):
        runner = CliRunner()
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_custom.list_namespaced_custom_object.return_value = {
            'items': [
                {
                    'metadata': {'name': 'migration-0'},
                    'status': {
                        'outputs': {
                            'metadataEvaluate': {
                                's3Key': (
                                    'migration-outputs/snapshotmigration/migration-0/uid-0/'
                                    'metadataEvaluate/wf-0.log'
                                ),
                                'workflowCreationTimestamp': '2026-05-03T13:00:00Z',
                            },
                        },
                    },
                },
                {
                    'metadata': {'name': 'migration-1'},
                    'status': {
                        'outputs': {
                            'metadataEvaluate': {
                                's3Key': (
                                    'migration-outputs/snapshotmigration/migration-1/uid-1/'
                                    'metadataEvaluate/wf-1.log'
                                ),
                                'workflowCreationTimestamp': '2026-05-03T14:00:00Z',
                            },
                        },
                    },
                },
            ],
        }
        mock_read_artifact.side_effect = ["evaluate 0\n", "evaluate 1\n"]

        result = runner.invoke(workflow_cli, ['show', 'evaluatemetadata'])

        assert result.exit_code == 0
        assert 'snapshotmigration.migration-0 / metadataEvaluate / 2026-05-03T13:00:00Z' in result.output
        assert 'snapshotmigration.migration-1 / metadataEvaluate / 2026-05-03T14:00:00Z' in result.output
        assert 'evaluate 0' in result.output
        assert 'evaluate 1' in result.output

    @patch('console_link.workflow.commands.show.load_k8s_config')
    @patch('console_link.workflow.commands.show.client')
    @patch('console_link.workflow.commands.show.read_artifact_text')
    def test_workflow_show_task_can_filter_by_resource(
        self, mock_read_artifact, mock_client, _mock_k8s
    ):
        runner = CliRunner()
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_custom.list_namespaced_custom_object.return_value = {
            'items': [
                {
                    'metadata': {'name': 'migration-0'},
                    'status': {
                        'outputs': {
                            'metadataMigrate': {
                                's3Key': 'migration-outputs/snapshotmigration/migration-0/uid-0/metadataMigrate/wf.log',
                            },
                        },
                    },
                },
                {
                    'metadata': {'name': 'migration-1'},
                    'status': {
                        'outputs': {
                            'metadataMigrate': {
                                's3Key': 'migration-outputs/snapshotmigration/migration-1/uid-1/metadataMigrate/wf.log',
                            },
                        },
                    },
                },
            ],
        }
        mock_read_artifact.return_value = "migrate 1\n"

        result = runner.invoke(workflow_cli, ['show', 'migratemetadata', 'migration-1'])

        assert result.exit_code == 0
        assert 'snapshotmigration.migration-1' in result.output
        assert 'snapshotmigration.migration-0' not in result.output
        assert 'migrate 1' in result.output
        mock_read_artifact.assert_called_once_with(
            'migration-outputs/snapshotmigration/migration-1/uid-1/metadataMigrate/wf.log'
        )

    @patch('console_link.workflow.commands.show.load_k8s_config')
    @patch('console_link.workflow.commands.show.client')
    def test_workflow_show_task_completion_only_includes_tasks_with_outputs(
        self, mock_client, _mock_k8s
    ):
        from console_link.workflow.commands.show import _get_task_or_resource_completions

        ctx = Mock()
        ctx.params = {'namespace': 'ma'}
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom

        def list_resources(**_kwargs):
            return {
                'items': [
                    {
                        'metadata': {'name': 'migration-0'},
                        'status': {
                            'outputs': {
                                'metadataEvaluate': {
                                    's3Key': (
                                        'migration-outputs/snapshotmigration/migration-0/uid/'
                                        'metadataEvaluate/wf.log'
                                    ),
                                },
                            },
                        },
                    },
                ],
            }

        mock_custom.list_namespaced_custom_object.side_effect = list_resources

        completions = _get_task_or_resource_completions(ctx, None, '')

        assert completions == ['evaluatemetadata']

    @patch('console_link.workflow.commands.show.load_k8s_config')
    @patch('console_link.workflow.commands.show.client')
    def test_workflow_show_resource_completion_matches_task_list(
        self, mock_client, _mock_k8s
    ):
        from console_link.workflow.commands.show import _get_resource_filter_completions

        ctx = Mock()
        ctx.params = {'namespace': 'ma', 'target': 'migratemetadata'}
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_custom.list_namespaced_custom_object.return_value = {
            'items': [
                {
                    'metadata': {'name': 'migration-0'},
                    'status': {
                        'outputs': {
                            'metadataMigrate': {
                                's3Key': 'migration-outputs/snapshotmigration/migration-0/uid/metadataMigrate/wf.log',
                            },
                        },
                    },
                },
                {
                    'metadata': {'name': 'migration-1'},
                    'status': {'outputs': {}},
                },
            ],
        }

        completions = _get_resource_filter_completions(ctx, None, '')

        assert completions == ['snapshotmigration.migration-0']

    @patch('console_link.workflow.commands.show.load_k8s_config')
    @patch('console_link.workflow.commands.show.client')
    @patch('console_link.workflow.commands.show.read_artifact_text')
    def test_workflow_show_clean_suppresses_task_headers(
        self, mock_read_artifact, mock_client, _mock_k8s
    ):
        runner = CliRunner()
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_custom.list_namespaced_custom_object.return_value = {
            'items': [
                {
                    'metadata': {'name': 'migration-0'},
                    'status': {
                        'outputs': {
                            'metadataEvaluate': {
                                's3Key': 'migration-outputs/snapshotmigration/migration-0/uid/metadataEvaluate/wf.log',
                                'workflowCreationTimestamp': '2026-05-03T13:00:00Z',
                            },
                        },
                    },
                },
            ],
        }
        mock_read_artifact.return_value = "evaluate clean\n"

        result = runner.invoke(workflow_cli, ['show', 'evaluatemetadata', '--clean'])

        assert result.exit_code == 0
        assert result.output == "evaluate clean\n"

    @patch('console_link.workflow.commands.show.load_k8s_config')
    @patch('console_link.workflow.commands.show.client')
    @patch('console_link.workflow.commands.show.read_artifact_text')
    def test_workflow_show_prints_stdout_for_resource(
        self, mock_read_artifact, mock_client, _mock_k8s
    ):
        runner = CliRunner()
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_custom.get_namespaced_custom_object.return_value = {
            'status': {
                'outputs': {
                    'metadataEvaluate': {
                        'artifactName': 'metadataOutput',
                        's3Key': 'migration-outputs/snapshotmigration/mig/uid-1/metadataEvaluate/wf-uid.log',
                        'workflowName': 'migration-workflow',
                    }
                }
            },
        }
        mock_read_artifact.return_value = "archived stdout\n"

        result = runner.invoke(
            workflow_cli,
            ['show', 'snapshotmigration.mig', 'metadataEvaluate']
        )

        assert result.exit_code == 0
        assert "archived stdout" in result.output
        mock_read_artifact.assert_called_once_with(
            'migration-outputs/snapshotmigration/mig/uid-1/metadataEvaluate/wf-uid.log'
        )

    @patch('console_link.workflow.commands.show.load_k8s_config')
    @patch('console_link.workflow.commands.show.client')
    @patch('console_link.workflow.commands.show.read_artifact_text')
    def test_workflow_show_filters_to_named_output(
        self, mock_read_artifact, mock_client, _mock_k8s
    ):
        runner = CliRunner()
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_custom.get_namespaced_custom_object.return_value = {
            'status': {
                'outputs': {
                    'metadataEvaluate': {
                        's3Key': 'migration-outputs/snapshotmigration/mig/uid-1/metadataEvaluate/wf-uid.log',
                    },
                    'metadataMigrate': {
                        's3Key': 'migration-outputs/snapshotmigration/mig/uid-1/metadataMigrate/wf-uid.log',
                    },
                }
            },
        }
        mock_read_artifact.return_value = "migrate output\n"

        result = runner.invoke(
            workflow_cli,
            ['show', 'snapshotmigration.mig', 'metadataMigrate']
        )

        assert result.exit_code == 0
        assert "migrate output" in result.output
        mock_read_artifact.assert_called_once_with(
            'migration-outputs/snapshotmigration/mig/uid-1/metadataMigrate/wf-uid.log'
        )

    @patch('console_link.workflow.commands.show.load_k8s_config')
    @patch('console_link.workflow.commands.show.client')
    @patch('console_link.workflow.commands.show.read_artifact_text')
    def test_workflow_show_accepts_metadata_approval_gate_names(
        self, mock_read_artifact, mock_client, _mock_k8s
    ):
        runner = CliRunner()
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_custom.get_namespaced_custom_object.return_value = {
            'status': {
                'outputs': {
                    'metadataEvaluate': {
                        's3Key': 'migration-outputs/snapshotmigration/mig/uid-1/metadataEvaluate/wf-uid.log',
                    },
                }
            },
        }
        mock_read_artifact.return_value = "evaluate output\n"

        result = runner.invoke(
            workflow_cli,
            ['show', 'evaluatemetadata.mig']
        )

        assert result.exit_code == 0
        mock_custom.get_namespaced_custom_object.assert_called_once_with(
            group='migrations.opensearch.org',
            version='v1alpha1',
            namespace='ma',
            plural='snapshotmigrations',
            name='mig',
        )
        assert "evaluate output" in result.output

    @patch('console_link.workflow.commands.show.load_k8s_config')
    @patch('console_link.workflow.commands.show.client')
    def test_workflow_show_list_shows_tasks_with_resource_names(self, mock_client, _mock_k8s):
        runner = CliRunner()
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom

        def list_resources(**kwargs):
            return {
                'items': [
                    {
                        'metadata': {'name': 'migration-0'},
                        'status': {
                            'outputs': {
                                'metadataEvaluate': {
                                    's3Key': (
                                        'migration-outputs/snapshotmigration/migration-0/uid/'
                                        'metadataEvaluate/wf.log'
                                    ),
                                },
                                'metadataMigrate': {
                                    's3Key': (
                                        'migration-outputs/snapshotmigration/migration-0/uid/'
                                        'metadataMigrate/wf.log'
                                    ),
                                },
                            },
                        },
                    },
                    {
                        'metadata': {'name': 'migration-no-output'},
                        'status': {'outputs': {}},
                    },
                ],
            }

        mock_custom.list_namespaced_custom_object.side_effect = list_resources

        result = runner.invoke(workflow_cli, ['show', '--list'])

        assert result.exit_code == 0
        assert 'evaluatemetadata:' in result.output
        assert 'migratemetadata:' in result.output
        assert 'snapshotmigration.migration-0' in result.output
        assert 'migration-no-output' not in result.output

    @patch('console_link.workflow.commands.show.load_k8s_config')
    @patch('console_link.workflow.commands.show.client')
    def test_workflow_show_task_list_shows_only_that_task_resources(self, mock_client, _mock_k8s):
        runner = CliRunner()
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_custom.list_namespaced_custom_object.return_value = {
            'items': [
                {
                    'metadata': {'name': 'migration-0'},
                    'status': {
                        'outputs': {
                            'metadataEvaluate': {
                                's3Key': 'migration-outputs/snapshotmigration/migration-0/uid/metadataEvaluate/wf.log',
                            },
                            'metadataMigrate': {
                                's3Key': 'migration-outputs/snapshotmigration/migration-0/uid/metadataMigrate/wf.log',
                            },
                        },
                    },
                },
                {
                    'metadata': {'name': 'migration-1'},
                    'status': {
                        'outputs': {
                            'metadataEvaluate': {
                                's3Key': 'migration-outputs/snapshotmigration/migration-1/uid/metadataEvaluate/wf.log',
                            },
                        },
                    },
                },
            ],
        }

        result = runner.invoke(workflow_cli, ['show', 'migratemetadata', '--list'])

        assert result.exit_code == 0
        assert 'migratemetadata:' not in result.output
        assert 'snapshotmigration.migration-0' in result.output
        assert 'snapshotmigration.migration-1' not in result.output

    @patch('console_link.workflow.commands.show.load_k8s_config')
    @patch('console_link.workflow.commands.show.client')
    @patch('console_link.workflow.commands.show.artifact_uri', side_effect=lambda key: f"s3://bucket/{key}")
    @patch('console_link.workflow.commands.show.list_artifacts')
    def test_workflow_show_history_lists_current_resource_uid_outputs(
        self, mock_list_artifacts, _mock_uri, mock_client, _mock_k8s
    ):
        runner = CliRunner()
        mock_custom = Mock()
        mock_client.CustomObjectsApi.return_value = mock_custom
        mock_custom.get_namespaced_custom_object.return_value = {
            'metadata': {'uid': 'uid-1', 'creationTimestamp': '2020-01-01T00:00:00Z'},
            'status': {'outputs': {}},
        }
        mock_list_artifacts.return_value = [
            {
                'key': 'migration-outputs/snapshotmigration/mig/2020-01-01T00:00:00Z_uid-1/metadataMigrate/wf-1.log',
                'last_modified': 1710000000.0,
                'size': 12,
            },
            {
                'key': (
                    'migration-outputs/snapshotmigration/mig/'
                    '2020-01-01T00:00:00Z_uid-1/metadataMigrate/wf-1.log.metadata'
                ),
                'last_modified': 1710000001.0,
                'size': 2,
            },
        ]

        result = runner.invoke(
            workflow_cli,
            ['show', 'snapshotmigration.mig', 'metadataMigrate', '--history']
        )

        assert result.exit_code == 0
        mock_list_artifacts.assert_called_once_with(
            'migration-outputs/snapshotmigration/mig/2020-01-01T00:00:00Z_uid-1/metadataMigrate/'
        )
        assert 'metadataMigrate' in result.output
        expected_uri = ('s3://bucket/migration-outputs/snapshotmigration/mig/'
                        '2020-01-01T00:00:00Z_uid-1/metadataMigrate/wf-1.log')
        assert expected_uri in result.output
        assert '.metadata' not in result.output

    def test_history_prefix_includes_creation_timestamp(self):
        from console_link.workflow.commands.show import _history_prefix

        resource = {
            'metadata': {
                'uid': 'accd2c5a-0e7c-4890-a083-96b3b201e1c9',
                'creationTimestamp': '2026-05-11T21:23:08Z',
            },
        }

        prefix = _history_prefix('snapshotmigration.my-migration-0', resource, 'metadataEvaluate')

        assert prefix == (
            'migration-outputs/snapshotmigration/my-migration-0/'
            '2026-05-11T21:23:08Z_accd2c5a-0e7c-4890-a083-96b3b201e1c9/metadataEvaluate/'
        )
        # Verify the prefix matches the format used by reset.py's _artifact_output_prefix
        from console_link.workflow.commands.reset import _artifact_output_prefix
        reset_prefix = _artifact_output_prefix(
            'snapshotmigrations', 'my-migration-0',
            uid='accd2c5a-0e7c-4890-a083-96b3b201e1c9',
            created_at='2026-05-11T21:23:08Z',
        )
        assert prefix.startswith(reset_prefix)

    @patch('console_link.workflow.commands.submit.verify_configured_secrets_exist')
    @patch('console_link.workflow.commands.submit.get_credentials_secret_store_for_namespace')
    @patch('console_link.workflow.commands.submit.delete_workflow')
    @patch('console_link.workflow.commands.submit.stop_workflow')
    @patch('console_link.workflow.commands.submit.workflow_exists')
    @patch('console_link.workflow.commands.submit.load_k8s_config')
    @patch('console_link.workflow.services.script_runner.subprocess.run')
    @patch('console_link.workflow.commands.submit.WorkflowConfigStore')
    def test_submit_command_with_config_injection(
        self,
        mock_store_class,
        mock_subprocess,
        _mock_k8s,
        mock_exists,
        mock_stop,
        mock_delete,
        _mock_get_secret_store,
        _mock_verify_secrets,
    ):
        """Test submit command with parameter injection from config."""
        # Mock subprocess to avoid actual Kubernetes submission
        mock_subprocess.return_value = Mock(
            returncode=0,
            stdout='{"workflow_name": "test-workflow-def", "workflow_uid": "uid-789", "namespace": "ma"}'
        )
        mock_exists.return_value = False

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
        mock_stop.assert_not_called()
        mock_delete.assert_not_called()


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

    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    def test_configure_credentials_list_shows_managed_credentials(self, mock_get_secret_store):
        runner = CliRunner()
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.list_secrets.return_value = ['source-secret', 'target-secret']

        result = runner.invoke(workflow_cli, ['configure', 'credentials', 'list'])

        assert result.exit_code == 0
        assert 'source-secret' in result.output
        assert 'target-secret' in result.output

    @patch('console_link.workflow.commands.configure.validate_and_find_secrets')
    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    @patch('console_link.workflow.commands.configure.get_workflow_config_store')
    def test_configure_credentials_create_show_missing(
        self,
        mock_get_config_store,
        mock_get_secret_store,
        mock_validate,
    ):
        runner = CliRunner()
        mock_config_store = Mock()
        mock_get_config_store.return_value = mock_config_store
        mock_config_store.load_config.return_value = WorkflowConfig(raw_yaml='sourceClusters: {}')
        mock_validate.return_value = {
            'valid': True,
            'validSecrets': ['source-secret', 'target-secret'],
        }
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.secrets_exist.return_value = {
            'source-secret': False,
            'target-secret': True,
        }

        result = runner.invoke(workflow_cli, ['configure', 'credentials', 'create', '--show-missing'])

        assert result.exit_code == 0
        assert 'source-secret' in result.output
        assert 'target-secret' not in result.output

    @patch('console_link.workflow.commands.configure.validate_and_find_secrets')
    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    @patch('console_link.workflow.commands.configure.get_workflow_config_store')
    def test_configure_credentials_create_list_alias(self, mock_get_config_store, mock_get_secret_store, mock_validate):
        runner = CliRunner()
        mock_config_store = Mock()
        mock_get_config_store.return_value = mock_config_store
        mock_config_store.load_config.return_value = WorkflowConfig(raw_yaml='sourceClusters: {}')
        mock_validate.return_value = {
            'valid': True,
            'validSecrets': ['source-secret'],
        }
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.secrets_exist.return_value = {'source-secret': False}

        result = runner.invoke(workflow_cli, ['configure', 'credentials', 'create', '--list'])

        assert result.exit_code == 0
        assert 'source-secret' in result.output

    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    def test_configure_credentials_update_list_shows_managed_credentials(self, mock_get_secret_store):
        runner = CliRunner()
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.list_secrets.return_value = ['source-secret']

        result = runner.invoke(workflow_cli, ['configure', 'credentials', 'update', '--list'])

        assert result.exit_code == 0
        assert 'source-secret' in result.output

    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    def test_configure_credentials_delete_list_shows_managed_credentials(self, mock_get_secret_store):
        runner = CliRunner()
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.list_secrets.return_value = ['target-secret']

        result = runner.invoke(workflow_cli, ['configure', 'credentials', 'delete', '--list'])

        assert result.exit_code == 0
        assert 'target-secret' in result.output

    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    def test_configure_credentials_delete_prompts_for_confirmation(self, mock_get_secret_store):
        runner = CliRunner()
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.secret_exists.return_value = True

        result = runner.invoke(
            workflow_cli,
            ['configure', 'credentials', 'delete', 'target-secret'],
            input='n\n',
        )

        assert result.exit_code == 0
        assert "Delete managed HTTP Basic credentials 'target-secret'?" in result.output
        assert 'Cancelled' in result.output
        mock_secret_store.delete_secret.assert_not_called()

    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    def test_configure_credentials_delete_yes_skips_confirmation(self, mock_get_secret_store):
        runner = CliRunner()
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.secret_exists.return_value = True
        mock_secret_store.delete_secret.return_value = 'Secret deleted: target-secret'

        result = runner.invoke(workflow_cli, ['configure', 'credentials', 'delete', '--yes', 'target-secret'])

        assert result.exit_code == 0
        assert 'Delete managed HTTP Basic credentials' not in result.output
        assert 'Credentials deleted: target-secret' in result.output
        mock_secret_store.delete_secret.assert_called_once_with('target-secret')

    def test_configure_credentials_delete_help_documents_interactive_confirmation(self):
        runner = CliRunner()

        result = runner.invoke(workflow_cli, ['configure', 'credentials', 'delete', '--help'])

        assert result.exit_code == 0
        assert 'Interactively prompts for confirmation' in result.output
        assert 'by default' in result.output
        assert '-y, --yes' in result.output
        assert '--confirm' not in result.output
        assert 'Do not prompt for confirmation' in result.output

    @patch('console_link.workflow.commands.configure.validate_and_find_secrets')
    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    def test_configure_credentials_create_prompts_for_credentials(
        self,
        mock_get_secret_store,
        mock_validate,
    ):
        runner = CliRunner()
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.namespace = 'ma'
        mock_secret_store.default_labels = {'use-case': 'http-basic-credentials'}
        mock_secret_store.v1.read_namespaced_secret.side_effect = ApiException(status=404)
        mock_secret_store.save_secret.return_value = 'Credentials created: source-secret'

        result = runner.invoke(
            workflow_cli,
            ['configure', 'credentials', 'create', 'source-secret'],
            input='admin\nsecret-pass\nsecret-pass\n',
        )

        assert result.exit_code == 0
        mock_secret_store.save_secret.assert_called_once_with(
            'source-secret',
            {'username': 'admin', 'password': 'secret-pass'},
        )
        mock_validate.assert_not_called()
        assert 'Credentials created: source-secret' in result.output

    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    def test_configure_credentials_create_stdin_parses_credentials_quietly(self, mock_get_secret_store):
        runner = CliRunner()
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.namespace = 'ma'
        mock_secret_store.default_labels = {'use-case': 'http-basic-credentials'}
        mock_secret_store.v1.read_namespaced_secret.side_effect = ApiException(status=404)
        mock_secret_store.save_secret.return_value = 'Credentials created: source-secret'

        result = runner.invoke(
            workflow_cli,
            ['configure', 'credentials', 'create', '--stdin', 'source-secret'],
            input='admin:secret:with:colons\n\n',
        )

        assert result.exit_code == 0
        assert result.output == ''
        mock_secret_store.save_secret.assert_called_once_with(
            'source-secret',
            {'username': 'admin', 'password': 'secret:with:colons'},
        )

    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    def test_configure_credentials_update_stdin_parses_credentials_quietly(self, mock_get_secret_store):
        runner = CliRunner()
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.secret_exists.return_value = True
        mock_secret_store.save_secret.return_value = 'Credentials updated: source-secret'

        result = runner.invoke(
            workflow_cli,
            ['configure', 'credentials', 'update', '--stdin', 'source-secret'],
            input='admin:secret-pass\r\n',
        )

        assert result.exit_code == 0
        assert result.output == ''
        mock_secret_store.save_secret.assert_called_once_with(
            'source-secret',
            {'username': 'admin', 'password': 'secret-pass'},
        )

    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    def test_configure_credentials_create_silent_requires_stdin(self, mock_get_secret_store):
        runner = CliRunner()
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.namespace = 'ma'
        mock_secret_store.default_labels = {'use-case': 'http-basic-credentials'}
        mock_secret_store.v1.read_namespaced_secret.side_effect = ApiException(status=404)

        result = runner.invoke(
            workflow_cli,
            ['configure', 'credentials', 'create', '--silent', 'source-secret'],
        )

        assert result.exit_code != 0
        assert '--silent can only be used with --stdin' in result.output
        mock_secret_store.save_secret.assert_not_called()

    def test_configure_credentials_stdin_silent_disables_terminal_echo(self):
        from console_link.workflow.commands import configure as configure_module

        stdin_stream = Mock()
        stdin_stream.isatty.return_value = True
        stdin_stream.fileno.return_value = 11
        stdin_stream.read.return_value = 'admin:secret-pass\n'
        original_attrs = [0, 0, 0, configure_module.termios.ECHO]

        with patch.object(configure_module.click, 'get_text_stream', return_value=stdin_stream), \
                patch.object(configure_module.termios, 'tcgetattr', return_value=original_attrs), \
                patch.object(configure_module.termios, 'tcsetattr') as mock_setattr:
            credentials = configure_module._parse_basic_auth_credentials_from_stdin(silent=True)

        assert credentials == {'username': 'admin', 'password': 'secret-pass'}
        assert mock_setattr.call_count == 2
        silent_attrs = mock_setattr.call_args_list[0].args[2]
        restored_attrs = mock_setattr.call_args_list[1].args[2]
        assert silent_attrs[3] & configure_module.termios.ECHO == 0
        assert restored_attrs == original_attrs

    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    def test_configure_credentials_create_stdin_rejects_malformed_credentials(self, mock_get_secret_store):
        runner = CliRunner()
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.namespace = 'ma'
        mock_secret_store.default_labels = {'use-case': 'http-basic-credentials'}
        mock_secret_store.v1.read_namespaced_secret.side_effect = ApiException(status=404)

        result = runner.invoke(
            workflow_cli,
            ['configure', 'credentials', 'create', '--stdin', 'source-secret'],
            input='not-well-formed\n',
        )

        assert result.exit_code != 0
        assert 'not well-formed' in result.output
        assert 'USERNAME:PASSWORD' in result.output
        mock_secret_store.save_secret.assert_not_called()

    def test_configure_credentials_create_help_documents_interactive_and_stdin_format(self):
        runner = CliRunner()

        result = runner.invoke(workflow_cli, ['configure', 'credentials', 'create', '--help'])

        assert result.exit_code == 0
        assert 'Interactively prompts for username and password' in result.output
        assert 'by default' in result.output
        assert 'USERNAME:PASSWORD' in result.output

    @patch('console_link.workflow.commands.configure.validate_and_find_secrets')
    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    @patch('console_link.workflow.commands.configure.get_workflow_config_store')
    def test_configure_credentials_create_completion_shows_missing_credentials(
        self,
        mock_get_config_store,
        mock_get_secret_store,
        mock_validate,
    ):
        runner = CliRunner()
        mock_config_store = Mock()
        mock_get_config_store.return_value = mock_config_store
        mock_config_store.load_config.return_value = WorkflowConfig(raw_yaml='sourceClusters: {}')
        mock_validate.return_value = {
            'valid': True,
            'validSecrets': ['cluster-creds', 'existing-creds'],
        }
        mock_secret_store = Mock()
        mock_get_secret_store.return_value = mock_secret_store
        mock_secret_store.secrets_exist.return_value = {
            'cluster-creds': False,
            'existing-creds': True,
        }

        result = runner.invoke(
            workflow_cli,
            [],
            prog_name='workflow',
            env={
                '_WORKFLOW_COMPLETE': 'zsh_complete',
                'COMP_WORDS': 'workflow configure credentials create ',
                'COMP_CWORD': '4',
            },
        )

        assert result.exit_code == 0
        assert 'cluster-creds' in result.output
        assert 'existing-creds' not in result.output

    @patch('console_link.workflow.commands.configure.get_current_namespace', return_value='ma')
    @patch('console_link.workflow.commands.configure.get_credentials_secret_store')
    def test_configure_credentials_update_completion_initializes_context(
        self,
        mock_get_secret_store,
        _mock_get_namespace,
    ):
        runner = CliRunner()
        mock_secret_store = Mock()
        mock_secret_store.list_secrets.return_value = ['cluster-creds']

        def get_secret_store(ctx):
            assert ctx.obj['namespace'] == 'ma'
            return mock_secret_store

        mock_get_secret_store.side_effect = get_secret_store

        result = runner.invoke(
            workflow_cli,
            [],
            prog_name='workflow',
            env={
                '_WORKFLOW_COMPLETE': 'bash_complete',
                'COMP_WORDS': 'workflow configure credentials update ',
                'COMP_CWORD': '4',
            },
        )

        assert result.exit_code == 0
        assert 'cluster-creds' in result.output
