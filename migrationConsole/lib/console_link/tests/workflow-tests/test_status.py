"""Tests for workflow status command."""

import sys
from io import StringIO
from typing import Any, Dict, List, Optional

import pytest

from console_link.workflow.commands.status import StatusCommandHandler, WorkflowDataFetcher
from console_link.workflow.services.workflow_service import WorkflowService, WorkflowListResult


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
                if self.workflows[name].get('status', {}).get('phase') not in ('Succeeded', 'Failed')
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
            'test-wf', 'https://argo', 'ma', False, False, False))

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
            'test-wf', 'https://argo', 'ma', False, False, False))

        assert 'test-wf' in output
        assert 'Succeeded' in output

    def test_snapshot_migration_wait_node_reads_backfill_status(self, monkeypatch):
        def fake_snapshot_migration_reader(name: str, namespace: str):
            assert name == 'source-target-snap1-migration-0'
            assert namespace == 'ma'
            return {
                'status': {
                    'documentBackfill': {
                        'phase': 'Running',
                        'updatedAt': '2026-05-14T10:05:00Z',
                        'summary': {
                            'percentageCompleted': 50.0,
                            'shardsTotal': 4,
                            'shardsMigrated': 2,
                            'shardsInProgress': 1,
                            'shardsWaiting': 1,
                        }
                    }
                }
            }

        monkeypatch.setattr(
            'console_link.workflow.tree_utils._default_snapshot_migration_reader',
            fake_snapshot_migration_reader
        )
        workflows = {
            'test-wf': {
                'metadata': {'name': 'test-wf'},
                'status': {
                    'phase': 'Running',
                    'startedAt': '2026-05-14T10:00:00Z',
                    'nodes': {
                        'root': {'id': 'root', 'displayName': 'root', 'type': 'Steps',
                                 'phase': 'Running', 'children': ['wait']},
                        'wait': {
                            'id': 'wait',
                            'displayName': 'waitForSnapshotMigration',
                            'type': 'Suspend',
                            'phase': 'Running',
                            'boundaryID': 'root',
                            'inputs': {'parameters': [
                                {'name': 'resourceName', 'value': 'source-target-snap1-migration-0'}
                            ]}
                        }
                    }
                }
            }
        }

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)

        output = capture_output(lambda: handler.handle_status_command(
            'test-wf', 'https://argo', 'ma', False, False, False))

        assert 'RFS: Running (50%, shards 2/4' in output
        assert 'progress 1, waiting 1)' in output
        assert 'updated 2026-05-14T10:05:00Z' in output

    def test_data_snapshot_wait_node_reads_snapshot_creation_status(self, monkeypatch):
        def fake_data_snapshot_reader(name: str, namespace: str):
            assert name == 'source-snap1'
            assert namespace == 'ma'
            return {
                'status': {
                    'snapshotCreation': {
                        'phase': 'Running',
                        'updatedAt': '2026-05-14T10:05:00Z',
                        'summary': {
                            'shardsTotal': 4,
                            'shardsSuccessful': 2,
                            'shardsFailed': 0,
                            'dataProcessed': '12.3',
                            'dataProcessedUnit': 'mb',
                            'eta': '0h 1m 0s',
                        }
                    }
                }
            }

        monkeypatch.setattr(
            'console_link.workflow.tree_utils._default_data_snapshot_reader',
            fake_data_snapshot_reader
        )
        workflows = {
            'test-wf': {
                'metadata': {'name': 'test-wf'},
                'status': {
                    'phase': 'Running',
                    'startedAt': '2026-05-14T10:00:00Z',
                    'nodes': {
                        'root': {'id': 'root', 'displayName': 'root', 'type': 'Steps',
                                 'phase': 'Running', 'children': ['wait']},
                        'wait': {
                            'id': 'wait',
                            'displayName': 'waitForDataSnapshot',
                            'type': 'Suspend',
                            'phase': 'Running',
                            'boundaryID': 'root',
                            'inputs': {'parameters': [
                                {'name': 'resourceName', 'value': 'source-snap1'}
                            ]}
                        }
                    }
                }
            }
        }

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)

        output = capture_output(lambda: handler.handle_status_command(
            'test-wf', 'https://argo', 'ma', False, False, False))
        compact_output = ' '.join(output.split())

        assert 'Snapshot: Running (shards 2/4' in compact_output
        assert 'failed 0, data 12.3 mb, ETA 0h 1m 0s)' in compact_output
        assert 'updated 2026-05-14T10:05:00Z' in compact_output

    def test_workflow_not_found(self):
        service = FakeWorkflowService({})
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher({}, service)

        with pytest.raises(Exception):  # click.Abort
            handler.handle_status_command('nonexistent', 'https://argo', 'ma', False, False, False)

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
            None, 'https://argo', 'ma', False, False, False))

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
            None, 'https://argo', 'ma', False, False, False))

        assert 'Found 1 workflow(s)' in output
        assert 'wf-running' in output
        assert 'wf-done' not in output

    def test_no_workflows_shows_message(self):
        service = FakeWorkflowService({})
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher({}, service)

        output = capture_output(lambda: handler.handle_status_command(
            None, 'https://argo', 'ma', False, False, False))

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
            'test-wf', 'https://argo', 'ma', False, False, False))

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
            None, 'https://argo', 'ma', False, True, False))

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
            None, 'https://argo', 'ma', False, False, False))

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
            handler.handle_status_command(None, 'https://argo', 'ma', False, False, False)

    def test_rfs_coordinator_retry_collapsed_in_status(self):
        """Infrastructure retry nodes (bare leaf Pods) are collapsed; retries with
        richer children (statusOutput, nested steps) remain visible."""
        workflows = {
            'test-wf': {
                'metadata': {'name': 'test-wf'},
                'status': {
                    'phase': 'Running',
                    'startedAt': '2023-01-01T10:00:00Z',
                    'nodes': {
                        'root': {
                            'id': 'root', 'displayName': 'root', 'type': 'Steps',
                            'phase': 'Running', 'children': ['rfs-retry', 'backfill-retry']
                        },
                        'rfs-retry': {
                            'id': 'rfs-retry', 'displayName': 'createRfsCoordinatorStatefulSet',
                            'type': 'Retry', 'phase': 'Succeeded', 'boundaryID': 'root',
                            'children': ['rfs-0', 'rfs-1']
                        },
                        'rfs-0': {
                            'id': 'rfs-0', 'displayName': 'createRfsCoordinatorStatefulSet(0)',
                            'type': 'Pod', 'phase': 'Error', 'boundaryID': 'rfs-retry',
                            'startedAt': '2023-01-01T10:00:00Z',
                            'finishedAt': '2023-01-01T10:00:30Z'
                        },
                        'rfs-1': {
                            'id': 'rfs-1', 'displayName': 'createRfsCoordinatorStatefulSet(1)',
                            'type': 'Pod', 'phase': 'Succeeded', 'boundaryID': 'rfs-retry',
                            'startedAt': '2023-01-01T10:01:00Z',
                            'finishedAt': '2023-01-01T10:02:00Z'
                        },
                        'backfill-retry': {
                            'id': 'backfill-retry', 'displayName': 'waitForCompletionInternal',
                            'type': 'Retry', 'phase': 'Running', 'boundaryID': 'root',
                            'children': ['bf-0', 'bf-1']
                        },
                        'bf-0': {
                            'id': 'bf-0', 'displayName': 'waitForCompletionInternal(0)',
                            'type': 'Pod', 'phase': 'Failed', 'boundaryID': 'backfill-retry',
                            'startedAt': '2023-01-01T10:02:00Z',
                            'outputs': {'parameters': [{'name': 'statusOutput', 'value': 'checking...'}]}
                        },
                        'bf-1': {
                            'id': 'bf-1', 'displayName': 'waitForCompletionInternal(1)',
                            'type': 'Pod', 'phase': 'Running', 'boundaryID': 'backfill-retry',
                            'startedAt': '2023-01-01T10:03:00Z',
                            'outputs': {'parameters': [{'name': 'statusOutput', 'value': 'checking...'}]}
                        }
                    }
                }
            }
        }

        service = FakeWorkflowService(workflows)
        handler = StatusCommandHandler(service)
        handler.data_fetcher = FakeDataFetcher(workflows, service)

        output = capture_output(lambda: handler.handle_status_command(
            'test-wf', 'https://argo', 'ma', False, False, False))

        # RFS retry attempts should be collapsed — no (0) or (1) suffixes visible
        assert 'createRfsCoordinatorStatefulSet(0)' not in output
        assert 'createRfsCoordinatorStatefulSet(1)' not in output
        # The collapsed step name should still appear (without attempt suffix)
        assert 'createRfsCoordinatorStatefulSet' in output

        # Backfill retry attempts should remain visible
        assert 'waitForCompletionInternal(0)' in output
        assert 'waitForCompletionInternal(1)' in output

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
            'test-wf', 'https://argo', 'ma', False, False, True))

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
            'test-wf', 'https://argo', 'ma', False, False, True))

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
            'test-wf', 'https://argo', 'ma', False, False, True))

        assert 'test-wf' in output
