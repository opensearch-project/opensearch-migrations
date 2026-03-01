"""Tests for workflow status command."""

import sys
from io import StringIO
from typing import Any, Dict, List, Optional
from unittest.mock import Mock, patch

import pytest

from console_link.workflow.commands.status import StatusCommandHandler, WorkflowDataFetcher
from console_link.workflow.services.workflow_service import WorkflowService, WorkflowListResult, ENDING_PHASES


class FakeWorkflowService(WorkflowService):
    """Test service that returns workflow data."""

    def __init__(self, workflows: Dict[str, Dict[str, Any]]):
        super().__init__()
        self.workflows = workflows

    def list_workflows(
        self,
        namespace: str,
        argo_server: str,
        token: Optional[str] = None,
        insecure: bool = False,
        exclude_completed: bool = False,
        phase_filter: Optional[str] = None
    ) -> WorkflowListResult:
        workflow_names = list(self.workflows.keys())
        if exclude_completed:
            workflow_names = [
                name for name in workflow_names
                if self.workflows[name].get('status', {}).get('phase') not in ENDING_PHASES
            ]
        return {
            'success': True,
            'workflows': workflow_names,
            'count': len(workflow_names),
            'error': None
        }


class FakeDataFetcher(WorkflowDataFetcher):
    """Test fetcher that returns workflow data."""

    def __init__(self, workflows: Dict[str, Dict[str, Any]], service: WorkflowService):
        super().__init__(service)
        self.workflows = workflows

    def get_workflow_data(self, workflow_name: str, argo_server: str,
                          namespace: str, insecure: bool) -> Dict[str, Any]:
        return self.workflows.get(workflow_name, {})

    def list_workflows(self, argo_server: str, namespace: str,
                       insecure: bool, exclude_completed: bool) -> List[Dict[str, Any]]:
        wf_list = list(self.workflows.values())
        if exclude_completed:
            wf_list = [wf for wf in wf_list
                       if wf.get('status', {}).get('phase') not in ('Succeeded', 'Failed')]
        return wf_list


def capture_output(func):
    """Capture click output."""
    old_stdout = sys.stdout
    sys.stdout = StringIO()
    try:
        func()
        return sys.stdout.getvalue()
    finally:
        sys.stdout = old_stdout


class TestWorkflowDataFetcherArchive:
    """Tests for WorkflowDataFetcher archive fallback methods."""

    @patch('console_link.workflow.commands.status.requests.get')
    def test_get_workflow_data_falls_back_to_archive(self, mock_get):
        """Test get_workflow_data falls back to archive when live API returns 404."""
        service = WorkflowService()
        fetcher = WorkflowDataFetcher(service, token='test-token')

        live_response = Mock()
        live_response.status_code = 404

        archive_response = Mock()
        archive_response.status_code = 200
        archive_response.json.return_value = {
            'items': [{'metadata': {'name': 'wf-1'}, 'status': {'phase': 'Succeeded'}}]
        }

        mock_get.side_effect = [live_response, archive_response]

        result = fetcher.get_workflow_data('wf-1', 'http://argo:2746', 'ma', False)

        assert result['metadata']['name'] == 'wf-1'
        assert mock_get.call_count == 2

    @patch('console_link.workflow.commands.status.requests.get')
    def test_get_workflow_data_returns_empty_when_both_fail(self, mock_get):
        """Test get_workflow_data returns empty dict when live and archive both fail."""
        service = WorkflowService()
        fetcher = WorkflowDataFetcher(service)

        mock_response = Mock()
        mock_response.status_code = 404
        mock_get.return_value = mock_response

        result = fetcher.get_workflow_data('missing', 'http://argo:2746', 'ma', False)

        assert result == {}

    @patch('console_link.workflow.commands.status.requests.get')
    def test_list_workflows_merges_archived(self, mock_get):
        """Test list_workflows merges archived workflows with live ones."""
        service = WorkflowService()
        fetcher = WorkflowDataFetcher(service, token='tok')

        live_response = Mock()
        live_response.status_code = 200
        live_response.json.return_value = {
            'items': [
                {'metadata': {'name': 'live-wf'}, 'status': {'phase': 'Running'}}
            ]
        }

        archive_response = Mock()
        archive_response.status_code = 200
        archive_response.json.return_value = {
            'items': [
                {'metadata': {'name': 'archived-wf'}, 'status': {'phase': 'Succeeded'}},
                {'metadata': {'name': 'live-wf'}, 'status': {'phase': 'Running'}},  # duplicate
            ]
        }

        mock_get.side_effect = [live_response, archive_response]

        result = fetcher.list_workflows('http://argo:2746', 'ma', False, exclude_completed=False)

        names = [wf['metadata']['name'] for wf in result]
        assert 'live-wf' in names
        assert 'archived-wf' in names
        assert len(result) == 2  # no duplicate

    @patch('console_link.workflow.commands.status.requests.get')
    def test_list_workflows_skips_archive_when_exclude_completed(self, mock_get):
        """Test list_workflows does not query archive when exclude_completed=True."""
        service = WorkflowService()
        fetcher = WorkflowDataFetcher(service)

        live_response = Mock()
        live_response.status_code = 200
        live_response.json.return_value = {
            'items': [
                {'metadata': {'name': 'running-wf'}, 'status': {'phase': 'Running'}}
            ]
        }
        mock_get.return_value = live_response

        result = fetcher.list_workflows('http://argo:2746', 'ma', False, exclude_completed=True)

        assert len(result) == 1
        # Only one call (live API), no archive call
        assert mock_get.call_count == 1

    @patch('console_link.workflow.commands.status.requests.get')
    def test_list_workflows_filters_completed_from_live(self, mock_get):
        """Test list_workflows filters completed workflows from live when exclude_completed."""
        service = WorkflowService()
        fetcher = WorkflowDataFetcher(service)

        live_response = Mock()
        live_response.status_code = 200
        live_response.json.return_value = {
            'items': [
                {'metadata': {'name': 'running-wf'}, 'status': {'phase': 'Running'}},
                {'metadata': {'name': 'done-wf'}, 'status': {'phase': 'Succeeded'}},
            ]
        }
        mock_get.return_value = live_response

        result = fetcher.list_workflows('http://argo:2746', 'ma', False, exclude_completed=True)

        assert len(result) == 1
        assert result[0]['metadata']['name'] == 'running-wf'

    @patch('console_link.workflow.commands.status.requests.get')
    def test_list_workflows_returns_empty_on_exception(self, mock_get):
        """Test list_workflows returns empty list on network error."""
        service = WorkflowService()
        fetcher = WorkflowDataFetcher(service)

        mock_get.side_effect = ConnectionError("Network down")

        result = fetcher.list_workflows('http://argo:2746', 'ma', False, exclude_completed=False)

        assert result == []

    @patch('console_link.workflow.commands.status.requests.get')
    def test_list_archived_workflows_success(self, mock_get):
        """Test _list_archived_workflows returns items on success."""
        service = WorkflowService()
        fetcher = WorkflowDataFetcher(service, token='tok')

        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'items': [
                {'metadata': {'name': 'arch-1'}},
                {'metadata': {'name': 'arch-2'}},
            ]
        }
        mock_get.return_value = mock_response

        result = fetcher._list_archived_workflows('http://argo:2746', 'ma', False)

        assert len(result) == 2
        call_args = mock_get.call_args
        assert 'archived-workflows' in call_args[0][0]

    @patch('console_link.workflow.commands.status.requests.get')
    def test_list_archived_workflows_returns_empty_on_error(self, mock_get):
        """Test _list_archived_workflows returns empty list on exception."""
        service = WorkflowService()
        fetcher = WorkflowDataFetcher(service)

        mock_get.side_effect = ConnectionError("fail")

        result = fetcher._list_archived_workflows('http://argo:2746', 'ma', False)

        assert result == []

    @patch('console_link.workflow.commands.status.requests.get')
    def test_list_archived_workflows_returns_empty_on_non_200(self, mock_get):
        """Test _list_archived_workflows returns empty list on non-200."""
        service = WorkflowService()
        fetcher = WorkflowDataFetcher(service)

        mock_response = Mock()
        mock_response.status_code = 500
        mock_get.return_value = mock_response

        result = fetcher._list_archived_workflows('http://argo:2746', 'ma', False)

        assert result == []


class TestStatusCommand:
    """Test status command through main entry points."""

    def test_single_workflow_running(self):
        workflows = {
            'test-wf': {
                'metadata': {'name': 'test-wf'},
                'status': {
                    'phase': 'Running',
                    'startedAt': '2023-01-01T10:00:00Z',
                    'nodes': {
                        'root': {'id': 'root', 'displayName': 'root', 'type': 'Steps',
                                 'phase': 'Running', 'children': ['step1']},
                        'step1': {'id': 'step1', 'displayName': 'step1', 'type': 'Pod',
                                  'phase': 'Succeeded', 'boundaryID': 'root'}
                    }
                }
            }
        }

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)

        output = capture_output(lambda: handler.handle_status_command(
            'test-wf', 'http://argo', 'ma', False, False, False))

        assert 'test-wf' in output
        assert 'Running' in output
        assert 'step1' in output

    def test_single_workflow_succeeded(self):
        workflows = {
            'test-wf': {
                'metadata': {'name': 'test-wf'},
                'status': {
                    'phase': 'Succeeded',
                    'startedAt': '2023-01-01T10:00:00Z',
                    'finishedAt': '2023-01-01T10:05:00Z',
                    'nodes': {
                        'root': {'id': 'root', 'displayName': 'root', 'type': 'Steps',
                                 'phase': 'Succeeded'}
                    }
                }
            }
        }

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)

        output = capture_output(lambda: handler.handle_status_command(
            'test-wf', 'http://argo', 'ma', False, False, False))

        assert 'test-wf' in output
        assert 'Succeeded' in output

    def test_workflow_not_found(self):
        service = FakeWorkflowService({})
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher({}, service)

        with pytest.raises(Exception):  # click.Abort
            handler.handle_status_command('nonexistent', 'http://argo', 'ma', False, False, False)

    def test_list_multiple_workflows(self):
        workflows = {
            'wf-1': {
                'metadata': {'name': 'wf-1'},
                'status': {'phase': 'Running', 'startedAt': '2023-01-01T12:00:00Z',
                           'nodes': {'root': {'id': 'root', 'displayName': 'wf-1',
                                              'type': 'Steps', 'phase': 'Running'}}}
            },
            'wf-2': {
                'metadata': {'name': 'wf-2'},
                'status': {'phase': 'Running', 'startedAt': '2023-01-01T10:00:00Z',
                           'nodes': {'root': {'id': 'root', 'displayName': 'wf-2',
                                              'type': 'Steps', 'phase': 'Running'}}}
            }
        }

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)

        output = capture_output(lambda: handler.handle_status_command(
            None, 'http://argo', 'ma', False, False, False))

        assert 'Found 2 workflow(s)' in output
        assert 'wf-1' in output
        assert 'wf-2' in output

    def test_list_exclude_completed(self):
        workflows = {
            'wf-running': {
                'metadata': {'name': 'wf-running'},
                'status': {'phase': 'Running', 'startedAt': '2023-01-01T12:00:00Z',
                           'nodes': {'root': {'id': 'root', 'displayName': 'wf-running',
                                              'type': 'Steps', 'phase': 'Running'}}}
            },
            'wf-done': {
                'metadata': {'name': 'wf-done'},
                'status': {'phase': 'Succeeded', 'startedAt': '2023-01-01T10:00:00Z',
                           'finishedAt': '2023-01-01T10:05:00Z',
                           'nodes': {'root': {'id': 'root', 'displayName': 'wf-done',
                                              'type': 'Steps', 'phase': 'Succeeded'}}}
            }
        }

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)

        output = capture_output(lambda: handler.handle_status_command(
            None, 'http://argo', 'ma', False, False, False))

        assert 'Found 1 workflow(s)' in output
        assert 'wf-running' in output
        assert 'wf-done' not in output

    def test_no_workflows_shows_message(self):
        service = FakeWorkflowService({})
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher({}, service)

        output = capture_output(lambda: handler.handle_status_command(
            None, 'http://argo', 'ma', False, False, False))

        assert 'No running workflows found' in output

    def test_workflow_with_nested_steps(self):
        workflows = {
            'test-wf': {
                'metadata': {'name': 'test-wf'},
                'status': {
                    'phase': 'Running',
                    'startedAt': '2023-01-01T10:00:00Z',
                    'nodes': {
                        'root': {'id': 'root', 'displayName': 'root', 'type': 'Steps',
                                 'phase': 'Running', 'children': ['group1']},
                        'group1': {'id': 'group1', 'displayName': 'group1', 'type': 'StepGroup',
                                   'phase': 'Running', 'boundaryID': 'root', 'children': ['step1', 'step2']},
                        'step1': {'id': 'step1', 'displayName': 'step1', 'type': 'Pod',
                                  'phase': 'Succeeded', 'boundaryID': 'group1'},
                        'step2': {'id': 'step2', 'displayName': 'step2', 'type': 'Pod',
                                  'phase': 'Running', 'boundaryID': 'group1'}
                    }
                }
            }
        }

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)

        output = capture_output(lambda: handler.handle_status_command(
            'test-wf', 'http://argo', 'ma', False, False, False))

        assert 'test-wf' in output
        assert 'step1' in output
        assert 'step2' in output

    def test_show_all_includes_completed(self):
        workflows = {
            'wf-done': {
                'metadata': {'name': 'wf-done'},
                'status': {'phase': 'Succeeded', 'startedAt': '2023-01-01T10:00:00Z',
                           'finishedAt': '2023-01-01T10:05:00Z',
                           'nodes': {'root': {'id': 'root', 'displayName': 'wf-done',
                                              'type': 'Steps', 'phase': 'Succeeded'}}}
            }
        }

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)

        output = capture_output(lambda: handler.handle_status_command(
            None, 'http://argo', 'ma', False, True, False))

        assert 'Found 1 workflow(s)' in output
        assert 'wf-done' in output

    def test_no_running_shows_most_recent_completed(self):
        workflows = {
            'wf-old': {
                'metadata': {'name': 'wf-old'},
                'status': {'phase': 'Succeeded', 'startedAt': '2023-01-01T09:00:00Z',
                           'finishedAt': '2023-01-01T09:05:00Z',
                           'nodes': {'root': {'id': 'root', 'displayName': 'wf-old',
                                              'type': 'Steps', 'phase': 'Succeeded'}}}
            },
            'wf-recent': {
                'metadata': {'name': 'wf-recent'},
                'status': {'phase': 'Succeeded', 'startedAt': '2023-01-01T10:00:00Z',
                           'finishedAt': '2023-01-01T10:05:00Z',
                           'nodes': {'root': {'id': 'root', 'displayName': 'wf-recent',
                                              'type': 'Steps', 'phase': 'Succeeded'}}}
            }
        }

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)

        output = capture_output(lambda: handler.handle_status_command(
            None, 'http://argo', 'ma', False, False, False))

        assert 'No running workflows found' in output
        assert 'last completed workflow' in output
        assert 'wf-recent' in output

    def test_exception_handling(self):
        service = FakeWorkflowService({})
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher({}, service)

        # Simulate an exception in the handler
        def bad_list(*args, **kwargs):
            raise RuntimeError("Network error")

        handler.data_fetcher.list_workflows = bad_list

        with pytest.raises(Exception):
            handler.handle_status_command(None, 'http://argo', 'ma', False, False, False)

    def test_live_check_with_snapshot_node(self):
        workflows = {
            'test-wf': {
                'metadata': {'name': 'test-wf'},
                'status': {
                    'phase': 'Running',
                    'startedAt': '2023-01-01T10:00:00Z',
                    'nodes': {
                        'root': {'id': 'root', 'displayName': 'root', 'type': 'Steps',
                                 'phase': 'Running', 'children': ['snapshot-group']},
                        'snapshot-group': {
                            'id': 'snapshot-group',
                            'displayName': 'createSnapshot(group1)',
                            'type': 'StepGroup',
                            'phase': 'Running',
                            'boundaryID': 'root',
                            'children': ['status-node'],
                            'inputs': {'parameters': [{'name': 'groupName', 'value': 'group1'}]}
                        },
                        'status-node': {
                            'id': 'status-node',
                            'displayName': 'checkStatus',
                            'type': 'Pod',
                            'phase': 'Running',
                            'boundaryID': 'snapshot-group',
                            'startedAt': '2023-01-01T10:01:00Z',
                            'inputs': {'parameters': [{'name': 'configContents', 'value': 'test-config'}]},
                            'outputs': {'parameters': [{'name': 'statusOutput', 'value': 'status-data'}]}
                        }
                    }
                }
            }
        }

        # Create a fake config converter that returns valid config
        class FakeConverter:
            def convert_with_jq(self, config):
                return '''
source_cluster:
  endpoint: "http://test"
  no_auth:
target_cluster:
  endpoint: "http://test"
  no_auth:
snapshot:
  snapshot_name: "test"
  s3:
    repo_uri: "s3://test"
    aws_region: "us-east-1"
'''

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)
        handler.live_check_processor.config_converter = FakeConverter()

        # This will attempt live checks but fail gracefully without real clusters
        output = capture_output(lambda: handler.handle_status_command(
            'test-wf', 'http://argo', 'ma', False, False, True))

        assert 'test-wf' in output

    def test_live_check_with_backfill_node(self):
        workflows = {
            'test-wf': {
                'metadata': {'name': 'test-wf'},
                'status': {
                    'phase': 'Running',
                    'startedAt': '2023-01-01T10:00:00Z',
                    'nodes': {
                        'root': {'id': 'root', 'displayName': 'root', 'type': 'Steps',
                                 'phase': 'Running', 'children': ['backfill-group']},
                        'backfill-group': {
                            'id': 'backfill-group',
                            'displayName': 'bulkLoadDocuments(group1)',
                            'type': 'StepGroup',
                            'phase': 'Running',
                            'boundaryID': 'root',
                            'children': ['status-node'],
                            'inputs': {'parameters': [{'name': 'groupName', 'value': 'group1'}]}
                        },
                        'status-node': {
                            'id': 'status-node',
                            'displayName': 'checkStatus',
                            'type': 'Pod',
                            'phase': 'Failed',
                            'boundaryID': 'backfill-group',
                            'startedAt': '2023-01-01T10:01:00Z',
                            'inputs': {'parameters': [{'name': 'configContents', 'value': 'test-config'}]},
                            'outputs': {'parameters': [{'name': 'statusOutput', 'value': 'status-data'}]}
                        }
                    }
                }
            }
        }

        class FakeConverter:
            def convert_with_jq(self, config):
                return '''
source_cluster:
  endpoint: "http://test"
  no_auth:
target_cluster:
  endpoint: "http://test"
  no_auth:
backfill:
  reindex_from_snapshot:
    docker:
'''

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)
        handler.live_check_processor.config_converter = FakeConverter()

        output = capture_output(lambda: handler.handle_status_command(
            'test-wf', 'http://argo', 'ma', False, False, True))

        assert 'test-wf' in output

    def test_live_check_with_unknown_check_type(self):
        workflows = {
            'test-wf': {
                'metadata': {'name': 'test-wf'},
                'status': {
                    'phase': 'Running',
                    'startedAt': '2023-01-01T10:00:00Z',
                    'nodes': {
                        'root': {'id': 'root', 'displayName': 'root', 'type': 'Steps',
                                 'phase': 'Running', 'children': ['unknown-group']},
                        'unknown-group': {
                            'id': 'unknown-group',
                            'displayName': 'unknownOperation(group1)',
                            'type': 'StepGroup',
                            'phase': 'Running',
                            'boundaryID': 'root',
                            'children': ['status-node'],
                            'inputs': {'parameters': [{'name': 'groupName', 'value': 'group1'}]}
                        },
                        'status-node': {
                            'id': 'status-node',
                            'displayName': 'checkStatus',
                            'type': 'Pod',
                            'phase': 'Running',
                            'boundaryID': 'unknown-group',
                            'startedAt': '2023-01-01T10:01:00Z',
                            'inputs': {'parameters': [{'name': 'configContents', 'value': 'test-config'}]},
                            'outputs': {'parameters': [{'name': 'statusOutput', 'value': 'status-data'}]}
                        }
                    }
                }
            }
        }

        class FakeConverter:
            def convert_with_jq(self, config):
                return '''
source_cluster:
  endpoint: "http://test"
  no_auth:
target_cluster:
  endpoint: "http://test"
  no_auth:
'''

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)
        handler.live_check_processor.config_converter = FakeConverter()

        # Should handle unknown check type gracefully
        output = capture_output(lambda: handler.handle_status_command(
            'test-wf', 'http://argo', 'ma', False, False, True))

        assert 'test-wf' in output
