"""Tests for workflow reset command."""
from unittest.mock import patch, Mock

from click.testing import CliRunner

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands.reset import _fetch_all_reset_steps


class TestFetchAllResetSteps:
    @patch('console_link.workflow.commands.reset.requests.get')
    def test_returns_reset_steps(self, mock_get):
        mock_get.return_value = Mock(status_code=200, json=lambda: {
            'status': {'nodes': {
                'n1': {
                    'type': 'Suspend', 'phase': 'Running', 'displayName': 'Teardown Capture',
                    'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-capture'}]}
                },
                'n2': {
                    'type': 'Suspend', 'phase': 'Succeeded', 'displayName': 'Teardown Replay',
                    'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-replay'}]}
                },
                'n3': {
                    'type': 'Suspend', 'phase': 'Running', 'displayName': 'Approve Backfill',
                    'inputs': {'parameters': [{'name': 'name', 'value': 'source.target.backfill'}]}
                },
            }}
        })
        steps = _fetch_all_reset_steps('wf', 'ma', 'http://localhost:2746', None, False)
        assert len(steps) == 2
        assert steps[0] == ('n1', 'teardown-capture', 'Teardown Capture', 'Running')
        assert steps[1] == ('n2', 'teardown-replay', 'Teardown Replay', 'Succeeded')

    @patch('console_link.workflow.commands.reset.requests.get')
    def test_returns_none_when_not_found(self, mock_get):
        mock_get.return_value = Mock(status_code=404)
        assert _fetch_all_reset_steps('wf', 'ma', 'http://localhost:2746', None, False) is None

    @patch('console_link.workflow.commands.reset.requests.get')
    def test_returns_empty_when_no_reset_steps(self, mock_get):
        mock_get.return_value = Mock(status_code=200, json=lambda: {
            'status': {'nodes': {
                'n1': {
                    'type': 'Suspend', 'phase': 'Running', 'displayName': 'Approve',
                    'inputs': {'parameters': [{'name': 'name', 'value': 'source.target.backfill'}]}
                },
            }}
        })
        steps = _fetch_all_reset_steps('wf', 'ma', 'http://localhost:2746', None, False)
        assert steps == []


class TestApproveExcludesResetSteps:
    @patch('console_link.workflow.commands.approve.requests.get')
    def test_approve_excludes_reset_prefix(self, mock_get):
        from console_link.workflow.commands.approve import _fetch_suspended_step_names
        mock_get.return_value = Mock(status_code=200, json=lambda: {
            'status': {'nodes': {
                'n1': {
                    'type': 'Suspend', 'phase': 'Running', 'displayName': 'Approve Backfill',
                    'inputs': {'parameters': [{'name': 'name', 'value': 'source.target.backfill'}]}
                },
                'n2': {
                    'type': 'Suspend', 'phase': 'Running', 'displayName': 'Teardown Capture',
                    'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-capture'}]}
                },
            }}
        })
        result = _fetch_suspended_step_names('wf', 'ma', 'http://localhost:2746', None, False)
        assert len(result) == 1
        assert result[0][1] == 'source.target.backfill'


class TestResetCommandList:
    @patch('console_link.workflow.commands.reset._fetch_all_reset_steps')
    def test_list_mode_no_path(self, mock_fetch):
        mock_fetch.return_value = [
            ('n1', 'teardown-capture', 'Teardown Capture', 'Running'),
            ('n2', 'teardown-replay', 'Teardown Replay', 'Succeeded'),
        ]
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'])
        assert result.exit_code == 0
        assert 'teardown-capture' in result.output
        assert 'waiting' in result.output
        assert 'teardown-replay' in result.output
        assert 'succeeded' in result.output

    @patch('console_link.workflow.commands.reset._fetch_all_reset_steps')
    def test_workflow_not_found(self, mock_fetch):
        mock_fetch.return_value = None
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'])
        assert 'not found' in result.output


class TestResetCommandApprove:
    @patch('console_link.workflow.commands.reset.WorkflowService')
    @patch('console_link.workflow.commands.reset._fetch_all_reset_steps')
    def test_approve_specific_step(self, mock_fetch, mock_svc_cls):
        mock_fetch.return_value = [
            ('n1', 'teardown-capture', 'Teardown Capture', 'Running'),
            ('n2', 'teardown-replay', 'Teardown Replay', 'Running'),
        ]
        mock_svc = Mock()
        mock_svc_cls.return_value = mock_svc
        mock_svc.approve_workflow.return_value = {'success': True, 'message': 'ok'}

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'teardown-capture'])
        assert result.exit_code == 0
        assert '✓ Approved teardown-capture' in result.output
        mock_svc.approve_workflow.assert_called_once()

    @patch('console_link.workflow.commands.reset._delete_workflow')
    @patch('console_link.workflow.commands.reset._wait_for_workflow_completion')
    @patch('console_link.workflow.commands.reset.WorkflowService')
    @patch('console_link.workflow.commands.reset._fetch_all_reset_steps')
    def test_reset_all(self, mock_fetch, mock_svc_cls, mock_wait, mock_delete):
        mock_fetch.return_value = [
            ('n1', 'teardown-capture', 'Teardown Capture', 'Running'),
            ('n2', 'teardown-replay', 'Teardown Replay', 'Running'),
        ]
        mock_svc = Mock()
        mock_svc_cls.return_value = mock_svc
        mock_svc.approve_workflow.return_value = {'success': True, 'message': 'ok'}
        mock_wait.return_value = 'Succeeded'
        mock_delete.return_value = True

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', '*'])
        assert result.exit_code == 0
        assert mock_svc.approve_workflow.call_count == 2
        assert 'Deleted workflow' in result.output

    @patch('console_link.workflow.commands.reset._fetch_all_reset_steps')
    def test_no_matching_steps(self, mock_fetch):
        mock_fetch.return_value = [
            ('n1', 'teardown-capture', 'Teardown Capture', 'Succeeded'),
        ]
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'teardown-capture'])
        assert 'No suspended reset steps' in result.output
