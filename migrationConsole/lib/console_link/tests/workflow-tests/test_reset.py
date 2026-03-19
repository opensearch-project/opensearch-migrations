"""Tests for workflow reset/approve shared infrastructure and reset command."""
from unittest.mock import patch, Mock

from click.testing import CliRunner

from console_link.workflow.cli import workflow_cli
from console_link.workflow.commands.suspend_steps import (
    find_suspend_steps,
    match_steps,
    RESET_PREFIX,
)


class TestFindSuspendSteps:
    NODES = {
        'n1': {
            'type': 'Suspend', 'phase': 'Running', 'displayName': 'Approve Backfill',
            'inputs': {'parameters': [{'name': 'name', 'value': 'source.target.backfill'}]}
        },
        'n2': {
            'type': 'Suspend', 'phase': 'Running', 'displayName': 'Teardown Capture',
            'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-capture'}]}
        },
        'n3': {
            'type': 'Suspend', 'phase': 'Succeeded', 'displayName': 'Teardown Replay',
            'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-replay'}]}
        },
        'n4': {
            'type': 'Steps', 'phase': 'Running', 'displayName': 'Main',
            'inputs': {'parameters': [{'name': 'name', 'value': 'main'}]}
        },
    }

    def test_no_filters(self):
        steps = find_suspend_steps(self.NODES)
        assert len(steps) == 3  # n1, n2, n3 (all Suspend nodes)

    def test_prefix_filter(self):
        steps = find_suspend_steps(self.NODES, prefix=RESET_PREFIX)
        assert len(steps) == 2
        names = [s[1] for s in steps]
        assert 'reset:teardown-capture' in names
        assert 'reset:teardown-replay' in names

    def test_exclude_prefix(self):
        steps = find_suspend_steps(self.NODES, exclude_prefix=RESET_PREFIX)
        assert len(steps) == 1
        assert steps[0][1] == 'source.target.backfill'

    def test_phase_filter(self):
        steps = find_suspend_steps(self.NODES, prefix=RESET_PREFIX, phase_filter='Running')
        assert len(steps) == 1
        assert steps[0][1] == 'reset:teardown-capture'

    def test_skips_nodes_without_name(self):
        nodes = {'n1': {'type': 'Suspend', 'phase': 'Running', 'displayName': 'x', 'inputs': {'parameters': []}}}
        assert find_suspend_steps(nodes) == []


class TestMatchSteps:
    STEPS = [
        ('n1', 'teardown-capture', 'Teardown Capture', 'Running'),
        ('n2', 'teardown-replay', 'Teardown Replay', 'Running'),
        ('n3', 'teardown-kafka', 'Teardown Kafka', 'Running'),
    ]

    def test_exact_match(self):
        matches = match_steps(self.STEPS, ['teardown-capture'])
        assert len(matches) == 1
        assert matches[0][1] == 'teardown-capture'

    def test_glob_match(self):
        matches = match_steps(self.STEPS, ['teardown-*'])
        assert len(matches) == 3

    def test_no_match(self):
        assert match_steps(self.STEPS, ['nonexistent']) == []

    def test_no_duplicates(self):
        matches = match_steps(self.STEPS, ['teardown-capture', 'teardown-*'])
        assert len(matches) == 3


class TestApproveExcludesResetSteps:
    @patch('console_link.workflow.commands.approve.fetch_workflow_nodes')
    def test_approve_excludes_reset_prefix(self, mock_fetch):
        mock_fetch.return_value = {
            'n1': {
                'type': 'Suspend', 'phase': 'Running', 'displayName': 'Approve Backfill',
                'inputs': {'parameters': [{'name': 'name', 'value': 'source.target.backfill'}]}
            },
            'n2': {
                'type': 'Suspend', 'phase': 'Running', 'displayName': 'Teardown Capture',
                'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-capture'}]}
            },
        }
        from console_link.workflow.commands.approve import _fetch_approvable_steps
        result = _fetch_approvable_steps('wf', 'ma', 'http://localhost:2746', None, False)
        assert len(result) == 1
        assert result[0][1] == 'source.target.backfill'


class TestResetCommandList:
    @patch('console_link.workflow.commands.reset.fetch_workflow_nodes')
    def test_list_mode_no_path(self, mock_fetch):
        mock_fetch.return_value = {
            'n1': {
                'type': 'Suspend', 'phase': 'Running', 'displayName': 'Teardown Capture',
                'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-capture'}]}
            },
            'n2': {
                'type': 'Suspend', 'phase': 'Succeeded', 'displayName': 'Teardown Replay',
                'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-replay'}]}
            },
        }
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'])
        assert result.exit_code == 0
        assert 'teardown-capture' in result.output
        assert 'waiting' in result.output
        assert 'teardown-replay' in result.output
        assert 'succeeded' in result.output

    @patch('console_link.workflow.commands.reset.fetch_workflow_nodes')
    def test_workflow_not_found(self, mock_fetch):
        mock_fetch.return_value = None
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset'])
        assert 'not found' in result.output


class TestResetCommandApprove:
    @patch('console_link.workflow.commands.suspend_steps.WorkflowService')
    @patch('console_link.workflow.commands.reset.fetch_workflow_nodes')
    def test_approve_specific_step(self, mock_fetch, mock_svc_cls):
        mock_fetch.return_value = {
            'n1': {
                'type': 'Suspend', 'phase': 'Running', 'displayName': 'Teardown Capture',
                'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-capture'}]}
            },
            'n2': {
                'type': 'Suspend', 'phase': 'Running', 'displayName': 'Teardown Replay',
                'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-replay'}]}
            },
        }
        mock_svc = Mock()
        mock_svc_cls.return_value = mock_svc
        mock_svc.approve_workflow.return_value = {'success': True, 'message': 'ok'}

        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'teardown-capture'])
        assert result.exit_code == 0
        assert '✓ Approved teardown-capture' in result.output
        mock_svc.approve_workflow.assert_called_once()

    @patch('console_link.workflow.commands.reset.delete_workflow')
    @patch('console_link.workflow.commands.reset.wait_for_workflow_completion')
    @patch('console_link.workflow.commands.suspend_steps.WorkflowService')
    @patch('console_link.workflow.commands.reset.fetch_workflow_nodes')
    def test_reset_all(self, mock_fetch, mock_svc_cls, mock_wait, mock_delete):
        mock_fetch.return_value = {
            'n1': {
                'type': 'Suspend', 'phase': 'Running', 'displayName': 'Teardown Capture',
                'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-capture'}]}
            },
            'n2': {
                'type': 'Suspend', 'phase': 'Running', 'displayName': 'Teardown Replay',
                'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-replay'}]}
            },
        }
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

    @patch('console_link.workflow.commands.reset.fetch_workflow_nodes')
    def test_no_matching_suspended_steps(self, mock_fetch):
        mock_fetch.return_value = {
            'n1': {
                'type': 'Suspend', 'phase': 'Succeeded', 'displayName': 'Teardown Capture',
                'inputs': {'parameters': [{'name': 'name', 'value': 'reset:teardown-capture'}]}
            },
        }
        runner = CliRunner()
        result = runner.invoke(workflow_cli, ['reset', 'teardown-capture'])
        assert 'No suspended reset steps' in result.output
