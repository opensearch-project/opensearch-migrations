"""Unit tests for manage.py workflow command."""

import json
from unittest.mock import Mock, patch, MagicMock


# --- Test Data Builders ---

def make_node_data(
    node_id='n1',
    display_name='Test Step',
    node_type='Pod',
    phase='Running',
    started_at='2024-01-01T10:00:00Z',
    finished_at=None,
    children=None,
    config_contents=None,
    has_status_output=False
):
    """Build a node_data dict with controlled defaults."""
    inputs = {'parameters': []}
    if config_contents:
        inputs['parameters'].append({'name': 'configContents', 'value': config_contents})
    
    outputs = {'parameters': []}
    if has_status_output:
        outputs['parameters'].append({'name': 'statusOutput', 'value': ''})
    
    return {
        'id': node_id,
        'display_name': display_name,
        'type': node_type,
        'phase': phase,
        'started_at': started_at,
        'finished_at': finished_at,
        'children': children or [],
        'inputs': inputs,
        'outputs': outputs,
        'group_name': None,
        'boundary_id': None
    }


def make_workflow_data(started_at='2024-01-01T10:00:00Z', nodes=None):
    """Build workflow_data dict with controlled defaults."""
    return {
        'status': {
            'startedAt': started_at,
            'nodes': nodes or {}
        }
    }


def make_app(tree_nodes=None, workflow_data=None, k8s_client=None):
    """Create a WorkflowTreeApp with controlled dependencies."""
    from console_link.workflow.commands.manage import WorkflowTreeApp
    
    if tree_nodes is None:
        tree_nodes = [make_node_data()]
    if workflow_data is None:
        workflow_data = make_workflow_data()
    if k8s_client is None:
        k8s_client = Mock()
    
    return WorkflowTreeApp('test-wf', k8s_client, tree_nodes, workflow_data, 'http://localhost:2746', 'ma')


# --- Tests ---

class TestCopyToClipboard:
    """Tests for copy_to_clipboard utility function."""

    @patch('console_link.workflow.commands.manage.subprocess.run')
    @patch('console_link.workflow.commands.manage.platform.system', return_value='Darwin')
    def test_macos_uses_pbcopy(self, mock_system, mock_run):
        from console_link.workflow.commands.manage import copy_to_clipboard
        mock_run.return_value = Mock(returncode=0)
        
        result = copy_to_clipboard("test text")
        
        assert result is True
        mock_run.assert_called_with(['pbcopy'], input=b'test text', check=True)

    @patch('console_link.workflow.commands.manage.subprocess.run')
    @patch('console_link.workflow.commands.manage.platform.system', return_value='Darwin')
    def test_returns_false_on_exception(self, mock_system, mock_run):
        from console_link.workflow.commands.manage import copy_to_clipboard
        mock_run.side_effect = Exception("clipboard error")
        
        result = copy_to_clipboard("test text")
        
        assert result is False


class TestGetWorkflowDataInternal:
    """Tests for _get_workflow_data_internal helper."""

    @patch('console_link.workflow.commands.manage.requests.get')
    def test_returns_workflow_data_on_success(self, mock_get):
        from console_link.workflow.commands.manage import _get_workflow_data_internal
        import io
        
        mock_service = Mock()
        mock_service.get_workflow_status.return_value = {
            'success': True,
            'workflow': {'status': {'startedAt': '2024-01-01T00:00:00Z'}}
        }
        
        # Mock streaming response with proper raw attribute for ijson
        workflow_data = {
            'status': {
                'nodes': {
                    'node1': {
                        'displayName': 'test-node',
                        'phase': 'Running',
                        'type': 'Pod',
                        'boundaryID': None
                    }
                }
            }
        }
        mock_response = Mock(
            status_code=200,
            raw=io.BytesIO(json.dumps(workflow_data).encode('utf-8'))
        )
        mock_get.return_value = mock_response
        
        result, data = _get_workflow_data_internal(
            mock_service, 'test-wf', 'http://localhost:2746', 'ma', False, None
        )
        
        assert result['success'] is True
        assert 'nodes' in data['status']
        assert 'node1' in data['status']['nodes']
        assert data['status']['nodes']['node1']['phase'] == 'Running'

    @patch('console_link.workflow.commands.manage.requests.get')
    def test_includes_auth_header_when_token_provided(self, mock_get):
        from console_link.workflow.commands.manage import _get_workflow_data_internal
        import io
        
        mock_service = Mock()
        mock_service.get_workflow_status.return_value = {'success': True}
        
        # Mock streaming response
        mock_response = Mock(
            status_code=200,
            raw=io.BytesIO(json.dumps({'status': {'nodes': {}}}).encode('utf-8'))
        )
        mock_get.return_value = mock_response
        
        _get_workflow_data_internal(
            mock_service, 'test-wf', 'http://localhost:2746', 'ma', False, 'my-token'
        )
        
        assert mock_get.call_args[1]['headers']['Authorization'] == 'Bearer my-token'

    @patch('console_link.workflow.commands.manage.requests.get')
    def test_returns_empty_dict_on_api_failure(self, mock_get):
        from console_link.workflow.commands.manage import _get_workflow_data_internal
        
        mock_service = Mock()
        mock_service.get_workflow_status.return_value = {'success': True}
        mock_get.return_value = Mock(status_code=404)
        
        result, data = _get_workflow_data_internal(
            mock_service, 'test-wf', 'http://localhost:2746', 'ma', False, None
        )
        
        assert data == {}


class TestManageCommand:
    """Tests for manage_command CLI entry point."""

    @patch('console_link.workflow.commands.manage._initialize_k8s_client')
    @patch('console_link.workflow.commands.manage._get_workflow_data_internal')
    @patch('console_link.workflow.commands.manage.WorkflowService')
    @patch('console_link.workflow.commands.manage.WorkflowTreeApp')
    def test_runs_app_on_success(self, mock_app_class, mock_service_class, mock_get_data, mock_k8s):
        from click.testing import CliRunner
        from console_link.workflow.commands.manage import manage_command
        
        mock_get_data.return_value = (
            {'success': True},
            {'status': {'nodes': {'n1': {'type': 'Pod', 'displayName': 'step1', 'phase': 'Running'}}}}
        )
        mock_app = Mock()
        mock_app_class.return_value = mock_app
        
        runner = CliRunner()
        result = runner.invoke(manage_command, ['test-workflow'])
        
        assert result.exit_code == 0
        mock_app.run.assert_called_once()

    @patch('console_link.workflow.commands.manage._get_workflow_data_internal')
    @patch('console_link.workflow.commands.manage.WorkflowService')
    def test_exits_with_error_when_workflow_not_found(self, mock_service_class, mock_get_data):
        from click.testing import CliRunner
        from console_link.workflow.commands.manage import manage_command
        
        mock_get_data.return_value = ({'success': False, 'error': 'Not found'}, {})
        
        runner = CliRunner()
        result = runner.invoke(manage_command, ['nonexistent'])
        
        assert result.exit_code != 0
        assert 'Not found' in result.output


class TestWorkflowTreeAppLiveCheck:
    """Tests for live check logic in WorkflowTreeApp."""

    def test_should_run_live_check_false_without_config(self):
        app = make_app()
        node = make_node_data(config_contents=None, has_status_output=True)
        
        # Returns falsy (None from `has_config`) when config missing
        assert not app._should_run_live_check(node)

    def test_should_run_live_check_false_when_succeeded(self):
        app = make_app()
        node = make_node_data(phase='Succeeded', config_contents='cfg', has_status_output=True)
        
        assert app._should_run_live_check(node) is False

    def test_should_run_live_check_truthy_when_running_with_config(self):
        app = make_app()
        node = make_node_data(phase='Running', config_contents='cfg', has_status_output=True)
        
        # Returns truthy (the config string) when conditions met
        assert app._should_run_live_check(node)


class TestWorkflowTreeAppWorkers:
    """Tests for async worker methods."""

    @patch('console_link.workflow.commands.manage._get_workflow_data_internal')
    def test_fetch_workflow_data_returns_result(self, mock_get_data):
        app = make_app()
        mock_get_data.return_value = ({'success': True}, {'status': {'phase': 'Running'}})
        
        result, data = app._fetch_workflow_data()
        
        assert result['success'] is True

    @patch('console_link.workflow.commands.manage._get_workflow_data_internal')
    def test_fetch_workflow_data_handles_exception(self, mock_get_data):
        app = make_app()
        mock_get_data.side_effect = Exception("Network error")
        
        result, _ = app._fetch_workflow_data()
        
        assert result['success'] is False
        assert 'Network error' in result['error']

    @patch('console_link.workflow.commands.manage.Environment')
    @patch('console_link.workflow.commands.manage.StatusCheckRunner.run_status_check')
    @patch('console_link.workflow.commands.manage.ConfigConverter.convert_with_jq')
    def test_perform_live_check_returns_status(self, mock_convert, mock_run_check, mock_env):
        app = make_app()
        mock_convert.return_value = "source_cluster:\n  endpoint: http://test"
        mock_run_check.return_value = {'success': True, 'value': 'status output'}
        
        node = make_node_data(display_name='backfill step', config_contents='raw config')
        result = app._perform_live_check(node)
        
        assert result['success'] is True

    @patch('console_link.workflow.commands.manage.ConfigConverter.convert_with_jq')
    def test_perform_live_check_returns_error_when_config_conversion_fails(self, mock_convert):
        app = make_app()
        mock_convert.return_value = None
        
        node = make_node_data(config_contents='bad config')
        result = app._perform_live_check(node)
        
        assert 'error' in result

    @patch('console_link.workflow.commands.manage.ConfigConverter.convert_with_jq')
    def test_perform_live_check_handles_exception(self, mock_convert):
        app = make_app()
        mock_convert.side_effect = Exception("Conversion failed")
        
        node = make_node_data(config_contents='config')
        result = app._perform_live_check(node)
        
        assert 'error' in result


class TestWorkflowTreeAppPodLogs:
    """Tests for pod log retrieval."""

    def test_get_pod_logs_returns_container_logs(self):
        k8s_client = Mock()
        mock_pod = Mock()
        mock_pod.spec.init_containers = []
        mock_pod.spec.containers = [Mock(name='main')]
        k8s_client.read_namespaced_pod.return_value = mock_pod
        k8s_client.read_namespaced_pod_log.return_value = "log output"
        
        app = make_app(k8s_client=k8s_client)
        result = app._get_pod_logs('test-pod')
        
        assert 'main' in result
        assert 'log output' in result

    def test_get_pod_logs_returns_error_on_exception(self):
        k8s_client = Mock()
        k8s_client.read_namespaced_pod.side_effect = Exception("Pod not found")
        
        app = make_app(k8s_client=k8s_client)
        result = app._get_pod_logs('nonexistent-pod')
        
        assert 'Error' in result


class TestWorkflowTreeAppApproval:
    """Tests for approval functionality."""

    @patch('console_link.workflow.commands.manage.WorkflowService')
    def test_execute_approval_notifies_on_success(self, mock_service_class):
        app = make_app()
        app.notify = Mock()
        app.action_manual_refresh = Mock()
        
        mock_service_class.return_value.approve_workflow.return_value = {'success': True}
        
        node = make_node_data(display_name='Approval Gate')
        app._execute_approval(node)
        
        app.notify.assert_called()
        assert '✅' in str(app.notify.call_args)
        app.action_manual_refresh.assert_called_once()

    @patch('console_link.workflow.commands.manage.WorkflowService')
    def test_execute_approval_notifies_error_on_failure(self, mock_service_class):
        app = make_app()
        app.notify = Mock()
        
        mock_service_class.return_value.approve_workflow.return_value = {
            'success': False, 'message': 'Not allowed'
        }
        
        node = make_node_data(display_name='Approval Gate')
        app._execute_approval(node)
        
        app.notify.assert_called()
        assert 'error' in str(app.notify.call_args)


def rig_mock_tree(app):
    """Mocks the internal Textual tree structure so we can spy on UI changes."""
    mock_tree = MagicMock()
    mock_root = MagicMock()
    mock_tree.root = mock_root

    # Track nodes added to the tree
    nodes = {}

    def add_node(label, data=None, expand=False):
        node = MagicMock()
        node.data = data
        node.children = []
        nodes[data['id'] if data else label] = node
        return node

    mock_root.add.side_effect = add_node

    # Mock query_one to return our mock tree
    app.query_one = Mock(return_value=mock_tree)
    return mock_tree, nodes


class TestWorkflowTreeAppRerender:
    """Tests to verify tree re-rendering logic for updates and restarts."""

    def test_apply_workflow_updates_hard_restart(self):
        """Verify that a new run_id (startedAt) triggers a full tree clear."""
        # Initial app state
        initial_workflow = {
            'metadata': {'resourceVersion': '1'},
            'status': {'startedAt': '10:00:00Z', 'nodes': {}}
        }
        app = make_app(workflow_data=initial_workflow)
        mock_tree, _ = rig_mock_tree(app)

        # New data with a different startedAt (Workflow Replaced/Restarted)
        new_workflow = {
            'metadata': {'resourceVersion': '2'},
            'status': {
                'startedAt': '11:00:00Z',  # Change triggers hard restart
                'nodes': {'n1': {'displayName': 'new-node', 'phase': 'Running'}}
            }
        }

        with patch('console_link.workflow.commands.manage.filter_tree_nodes', return_value=[{'id': 'n1'}]):
            app._apply_workflow_updates_unsafe(new_workflow)

        # Verify hard restart logic
        mock_tree.clear.assert_called_once()
        assert app.current_run_id == '11:00:00Z'
        assert app.node_mapping != {}  # Tree was repopulated

    def test_apply_workflow_updates_soft_update(self):
        """Verify that same run_id but new ResourceVersion updates existing nodes."""
        initial_workflow = {
            'metadata': {'resourceVersion': '1'},
            'status': {'startedAt': '10:00:00Z', 'nodes': {'n1': {'phase': 'Running'}}}
        }
        app = make_app(workflow_data=initial_workflow)
        mock_tree, nodes = rig_mock_tree(app)

        # Manually seed the node mapping
        mock_node = MagicMock()
        mock_node.data = {'id': 'n1', 'phase': 'Running'}
        app.node_mapping = {'n1': mock_node}
        mock_tree.root.children = [mock_node]

        # Update with same startedAt but new ResourceVersion and new Phase
        updated_workflow = {
            'metadata': {'resourceVersion': '2'},
            'status': {
                'startedAt': '10:00:00Z',
                'nodes': {'n1': {'displayName': 'n1', 'phase': 'Succeeded'}}
            }
        }

        # Mock the recursive update path
        with patch.object(app, '_update_tree_recursive') as mock_recursive:
            app._apply_workflow_updates(updated_workflow)

            # Verify tree was NOT cleared, but update was called
            mock_tree.clear.assert_not_called()
            mock_recursive.assert_called_once()
            assert app.workflow_data['metadata']['resourceVersion'] == '2'

    def test_apply_workflow_updates_no_change_skipped(self):
        """Verify that same ResourceVersion logs a skip and does no UI work."""
        initial_workflow = {
            'metadata': {'resourceVersion': '100'},
            'status': {'startedAt': '10:00:00Z'}
        }
        app = make_app(workflow_data=initial_workflow)

        with patch('console_link.workflow.commands.manage.logger') as mock_logger:
            # Same RV and same startedAt
            app._apply_workflow_updates(initial_workflow)

            # Check for the specific log message we added
            mock_logger.debug.assert_any_call("REFRESH SKIP: No changes detected (RV: 100)")

    def test_apply_workflow_updates_prunes_ephemeral_nodes(self):
        """Check that Succeeded phases trigger the removal of Live Status nodes."""
        app = make_app()
        _, _ = rig_mock_tree(app)

        # Rig a node that has a live check
        node_id = 'front_node'
        mock_node = MagicMock()
        mock_node.data = {'id': node_id, 'phase': 'Running'}
        app.node_mapping = {node_id: mock_node}
        app.nodes_with_live_checks = {node_id}

        # Data showing the node has finished
        new_workflow = {
            'metadata': {'resourceVersion': '2'},
            'status': {
                'startedAt': app.current_run_id,
                'nodes': {node_id: {'phase': 'Succeeded'}}
            }
        }

        with patch.object(app, '_remove_ephemeral_nodes') as mock_prune:
            # We need to ensure the node isn't considered an "active front" anymore
            with patch.object(app, '_get_branch_front_ids', return_value=set()):
                app._apply_workflow_updates(new_workflow)

                # Verify pruning was triggered
                mock_prune.assert_called_with(mock_node)
