"""Unit tests for manage.py workflow command."""

import pytest
from unittest.mock import Mock, patch


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
    
    return WorkflowTreeApp(tree_nodes, workflow_data, k8s_client, 'test-wf', 'ma')


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
        
        mock_service = Mock()
        mock_service.get_workflow_status.return_value = {'success': True}
        mock_get.return_value = Mock(
            status_code=200,
            json=Mock(return_value={'status': {'phase': 'Running'}})
        )
        
        result, data = _get_workflow_data_internal(
            mock_service, 'test-wf', 'http://localhost:2746', 'ma', False, None
        )
        
        assert result['success'] is True
        assert data['status']['phase'] == 'Running'

    @patch('console_link.workflow.commands.manage.requests.get')
    def test_includes_auth_header_when_token_provided(self, mock_get):
        from console_link.workflow.commands.manage import _get_workflow_data_internal
        
        mock_service = Mock()
        mock_service.get_workflow_status.return_value = {'success': True}
        mock_get.return_value = Mock(status_code=200, json=Mock(return_value={}))
        
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
        
        # Returns falsy (None from `has_config and has_output`) when config missing
        assert not app._should_run_live_check(node)

    def test_should_run_live_check_false_without_status_output(self):
        app = make_app()
        node = make_node_data(config_contents='some config', has_status_output=False)
        
        assert app._should_run_live_check(node) is False

    def test_should_run_live_check_false_when_succeeded(self):
        app = make_app()
        node = make_node_data(phase='Succeeded', config_contents='cfg', has_status_output=True)
        
        assert app._should_run_live_check(node) is False

    def test_should_run_live_check_true_when_running_with_config_and_output(self):
        app = make_app()
        node = make_node_data(phase='Running', config_contents='cfg', has_status_output=True)
        
        assert app._should_run_live_check(node) is True

    def test_get_latest_pod_id_returns_most_recent(self):
        nodes = [
            make_node_data(node_id='n1', node_type='Pod', started_at='2024-01-01T10:00:00Z'),
            make_node_data(node_id='n2', node_type='Pod', started_at='2024-01-01T11:00:00Z'),
        ]
        app = make_app(tree_nodes=nodes)
        
        assert app._get_latest_pod_id(nodes) == 'n2'

    def test_get_latest_pod_id_ignores_non_pods(self):
        nodes = [
            make_node_data(node_id='n1', node_type='Pod', started_at='2024-01-01T10:00:00Z'),
            make_node_data(node_id='n2', node_type='Suspend', started_at='2024-01-01T12:00:00Z'),
        ]
        app = make_app(tree_nodes=nodes)
        
        assert app._get_latest_pod_id(nodes) == 'n1'

    def test_get_latest_pod_id_returns_none_when_no_pods(self):
        nodes = [make_node_data(node_id='n1', node_type='Suspend')]
        app = make_app(tree_nodes=nodes)
        
        assert app._get_latest_pod_id(nodes) is None

    def test_is_globally_latest_true_for_latest(self):
        nodes = [
            make_node_data(node_id='n1', node_type='Pod', started_at='2024-01-01T10:00:00Z'),
            make_node_data(node_id='n2', node_type='Pod', started_at='2024-01-01T11:00:00Z'),
        ]
        app = make_app(tree_nodes=nodes)
        
        assert app._is_globally_latest('n2') is True
        assert app._is_globally_latest('n1') is False


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
        app.action_refresh = Mock()
        
        mock_service_class.return_value.approve_workflow.return_value = {'success': True}
        
        node = make_node_data(display_name='Approval Gate')
        app._execute_approval(node)
        
        app.notify.assert_called()
        assert '✅' in str(app.notify.call_args)

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
