"""Unit tests for WorkflowService class."""

import pytest
from unittest.mock import Mock, patch

from console_link.workflow.services.workflow_service import WorkflowService


class TestWorkflowServiceSubmission:
    """Test suite for workflow submission functionality."""

    @patch('console_link.workflow.services.workflow_service.requests.post')
    def test_submit_workflow_success(self, mock_post):
        """Test successful workflow submission."""
        service = WorkflowService()

        # Mock successful response
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'metadata': {
                'name': 'test-workflow-abc123',
                'uid': 'uid-12345'
            }
        }
        mock_post.return_value = mock_response

        workflow_spec = {'metadata': {'name': 'test'}, 'spec': {}}

        result = service.submit_workflow_to_argo(
            workflow_spec=workflow_spec,
            namespace='argo',
            argo_server='https://localhost:2746',
            insecure=True
        )

        assert result['success'] is True
        assert result['workflow_name'] == 'test-workflow-abc123'
        assert result['workflow_uid'] == 'uid-12345'
        assert result['namespace'] == 'argo'
        assert result['error'] is None

        # Verify request was made correctly
        mock_post.assert_called_once()
        call_args = mock_post.call_args
        assert call_args[0][0] == 'https://localhost:2746/api/v1/workflows/argo'
        assert call_args[1]['verify'] is False

    @patch('console_link.workflow.services.workflow_service.requests.post')
    def test_submit_workflow_with_token(self, mock_post):
        """Test workflow submission with authentication token."""
        service = WorkflowService()

        mock_response = Mock()
        mock_response.json.return_value = {
            'metadata': {'name': 'test', 'uid': 'uid'}
        }
        mock_post.return_value = mock_response

        workflow_spec = {'metadata': {}, 'spec': {}}

        service.submit_workflow_to_argo(
            workflow_spec=workflow_spec,
            namespace='argo',
            argo_server='https://localhost:2746',
            token='secret-token'
        )

        # Verify Authorization header was set
        call_args = mock_post.call_args
        headers = call_args[1]['headers']
        assert 'Authorization' in headers
        assert headers['Authorization'] == 'Bearer secret-token'

    @patch('console_link.workflow.services.workflow_service.requests.post')
    def test_submit_workflow_api_error(self, mock_post):
        """Test workflow submission with API error."""
        service = WorkflowService()

        # Mock API error
        mock_response = Mock()
        mock_response.status_code = 400
        mock_response.json.return_value = {'message': 'Invalid workflow'}
        mock_post.side_effect = Exception("Request failed")

        workflow_spec = {'metadata': {}, 'spec': {}}

        result = service.submit_workflow_to_argo(
            workflow_spec=workflow_spec,
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is False
        assert result['error'] is not None
        assert 'error' in result['error'].lower()

    @patch('console_link.workflow.services.workflow_service.requests.post')
    def test_submit_workflow_ensures_namespace(self, mock_post):
        """Test that namespace is always set in workflow metadata."""
        service = WorkflowService()

        mock_response = Mock()
        mock_response.json.return_value = {
            'metadata': {'name': 'test', 'uid': 'uid'}
        }
        mock_post.return_value = mock_response

        # Workflow without namespace in metadata
        workflow_spec = {'metadata': {'name': 'test'}, 'spec': {}}

        # Call submit_workflow_to_argo - we only care about the request being made correctly
        service.submit_workflow_to_argo(
            workflow_spec=workflow_spec,
            namespace='custom-namespace',
            argo_server='https://localhost:2746'
        )

        # Verify namespace was added to request body
        call_args = mock_post.call_args
        request_body = call_args[1]['json']
        assert request_body['workflow']['metadata']['namespace'] == 'custom-namespace'


class TestWorkflowServiceMonitoring:
    """Test suite for workflow monitoring functionality."""

    @patch('console_link.workflow.services.workflow_service.client.CustomObjectsApi')
    def test_wait_for_workflow_completion_success(self, mock_api_class):
        """Test successful workflow completion monitoring."""
        service = WorkflowService()

        # Mock Kubernetes API
        mock_api = Mock()
        mock_api_class.return_value = mock_api

        # First call: workflow running, second call: workflow succeeded
        mock_api.get_namespaced_custom_object.side_effect = [
            {
                'status': {'phase': 'Running', 'nodes': {}}
            },
            {
                'status': {
                    'phase': 'Succeeded',
                    'nodes': {
                        'node-1': {
                            'outputs': {
                                'parameters': [
                                    {'name': 'message', 'value': 'Hello World'}
                                ]
                            }
                        }
                    }
                }
            }
        ]

        phase, output = service.wait_for_workflow_completion(
            namespace='argo',
            workflow_name='test-workflow',
            timeout=10,
            interval=1
        )

        assert phase == 'Succeeded'
        assert output == 'Hello World'

    @patch('console_link.workflow.services.workflow_service.client.CustomObjectsApi')
    def test_wait_for_workflow_completion_timeout(self, mock_api_class):
        """Test workflow monitoring timeout."""
        service = WorkflowService()

        mock_api = Mock()
        mock_api_class.return_value = mock_api

        # Always return running status
        mock_api.get_namespaced_custom_object.return_value = {
            'status': {'phase': 'Running', 'nodes': {}}
        }

        with pytest.raises(TimeoutError) as exc_info:
            service.wait_for_workflow_completion(
                namespace='argo',
                workflow_name='test-workflow',
                timeout=2,
                interval=1
            )

        assert 'did not complete' in str(exc_info.value)

    @patch('console_link.workflow.services.workflow_service.client.CustomObjectsApi')
    def test_wait_for_workflow_completion_failed(self, mock_api_class):
        """Test monitoring workflow that fails."""
        service = WorkflowService()

        mock_api = Mock()
        mock_api_class.return_value = mock_api

        mock_api.get_namespaced_custom_object.return_value = {
            'status': {'phase': 'Failed', 'nodes': {}}
        }

        phase, output = service.wait_for_workflow_completion(
            namespace='argo',
            workflow_name='test-workflow',
            timeout=10,
            interval=1
        )

        assert phase == 'Failed'
        assert output is None

    @patch('console_link.workflow.services.workflow_service.client.CustomObjectsApi')
    def test_wait_for_workflow_completion_no_output(self, mock_api_class):
        """Test monitoring workflow that completes without output."""
        service = WorkflowService()

        mock_api = Mock()
        mock_api_class.return_value = mock_api

        mock_api.get_namespaced_custom_object.return_value = {
            'status': {'phase': 'Succeeded', 'nodes': {}}
        }

        phase, output = service.wait_for_workflow_completion(
            namespace='argo',
            workflow_name='test-workflow',
            timeout=10,
            interval=1
        )

        assert phase == 'Succeeded'
        assert output is None

    @patch('console_link.workflow.services.workflow_service.client.CustomObjectsApi')
    def test_wait_for_workflow_completion_api_error(self, mock_api_class):
        """Test handling of Kubernetes API errors during monitoring."""
        service = WorkflowService()

        mock_api = Mock()
        mock_api_class.return_value = mock_api

        mock_api.get_namespaced_custom_object.side_effect = Exception("API Error")

        with pytest.raises(Exception) as exc_info:
            service.wait_for_workflow_completion(
                namespace='argo',
                workflow_name='test-workflow',
                timeout=10,
                interval=1
            )

        assert 'API Error' in str(exc_info.value)


class TestWorkflowServiceStop:
    """Test suite for workflow stop functionality."""

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_stop_workflow_success(self, mock_put):
        """Test successful workflow stop."""
        service = WorkflowService()

        # Mock successful response
        mock_response = Mock()
        mock_response.status_code = 200
        mock_put.return_value = mock_response

        result = service.stop_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746',
            insecure=True
        )

        assert result['success'] is True
        assert result['workflow_name'] == 'test-workflow'
        assert result['namespace'] == 'argo'
        assert 'stopped successfully' in result['message']
        assert result['error'] is None

        # Verify request was made correctly
        mock_put.assert_called_once()
        call_args = mock_put.call_args
        assert call_args[0][0] == 'https://localhost:2746/api/v1/workflows/argo/test-workflow/stop'
        assert call_args[1]['verify'] is False

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_stop_workflow_with_token(self, mock_put):
        """Test workflow stop with authentication token."""
        service = WorkflowService()

        mock_response = Mock()
        mock_response.status_code = 200
        mock_put.return_value = mock_response

        result = service.stop_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746',
            token='secret-token'
        )

        assert result['success'] is True

        # Verify Authorization header was set
        call_args = mock_put.call_args
        headers = call_args[1]['headers']
        assert 'Authorization' in headers
        assert headers['Authorization'] == 'Bearer secret-token'

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_stop_workflow_not_found(self, mock_put):
        """Test workflow stop with 404 not found."""
        service = WorkflowService()

        # Mock 404 response
        mock_response = Mock()
        mock_response.status_code = 404
        mock_put.return_value = mock_response

        result = service.stop_workflow(
            workflow_name='nonexistent-workflow',
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is False
        assert result['workflow_name'] == 'nonexistent-workflow'
        assert result['namespace'] == 'argo'
        assert 'not found' in result['message']
        assert result['error'] is not None

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_stop_workflow_api_error(self, mock_put):
        """Test workflow stop with API error."""
        service = WorkflowService()

        # Mock API error response
        mock_response = Mock()
        mock_response.status_code = 500
        mock_response.text = 'Internal server error'
        mock_response.json.side_effect = ValueError("Not JSON")
        mock_put.return_value = mock_response

        result = service.stop_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is False
        assert result['workflow_name'] == 'test-workflow'
        assert 'Failed to stop workflow' in result['message']
        assert result['error'] is not None

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_stop_workflow_network_error(self, mock_put):
        """Test workflow stop with network error."""
        service = WorkflowService()

        # Mock network exception
        import requests
        mock_put.side_effect = requests.exceptions.ConnectionError("Connection failed")

        result = service.stop_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is False
        assert result['workflow_name'] == 'test-workflow'
        assert 'Network error' in result['message']
        assert result['error'] is not None

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_stop_workflow_insecure_flag(self, mock_put):
        """Test workflow stop with insecure flag variations."""
        service = WorkflowService()

        mock_response = Mock()
        mock_response.status_code = 200
        mock_put.return_value = mock_response

        # Test with insecure=True
        service.stop_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746',
            insecure=True
        )

        call_args = mock_put.call_args
        assert call_args[1]['verify'] is False

        mock_put.reset_mock()

        # Test with insecure=False (default)
        service.stop_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746',
            insecure=False
        )

        call_args = mock_put.call_args
        assert call_args[1]['verify'] is True


class TestWorkflowServiceList:
    """Test suite for workflow list functionality."""

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_list_workflows_success(self, mock_get):
        """Test successful workflow listing."""
        service = WorkflowService()

        # Mock successful response
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'items': [
                {'metadata': {'name': 'workflow-1'}},
                {'metadata': {'name': 'workflow-2'}},
                {'metadata': {'name': 'workflow-3'}}
            ]
        }
        mock_get.return_value = mock_response

        result = service.list_workflows(
            namespace='argo',
            argo_server='https://localhost:2746',
            insecure=True
        )

        assert result['success'] is True
        assert result['count'] == 3
        assert 'workflow-1' in result['workflows']
        assert 'workflow-2' in result['workflows']
        assert 'workflow-3' in result['workflows']
        assert result['error'] is None

        # Verify request was made correctly
        mock_get.assert_called_once()
        call_args = mock_get.call_args
        assert call_args[0][0] == 'https://localhost:2746/api/v1/workflows/argo'
        assert call_args[1]['verify'] is False

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_list_workflows_empty(self, mock_get):
        """Test listing workflows when none exist."""
        service = WorkflowService()

        # Mock empty response
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {'items': []}
        mock_get.return_value = mock_response

        result = service.list_workflows(
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is True
        assert result['count'] == 0
        assert result['workflows'] == []
        assert result['error'] is None

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_list_workflows_with_token(self, mock_get):
        """Test workflow listing with authentication token."""
        service = WorkflowService()

        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {'items': []}
        mock_get.return_value = mock_response

        result = service.list_workflows(
            namespace='argo',
            argo_server='https://localhost:2746',
            token='secret-token'
        )

        assert result['success'] is True

        # Verify Authorization header was set
        call_args = mock_get.call_args
        headers = call_args[1]['headers']
        assert 'Authorization' in headers
        assert headers['Authorization'] == 'Bearer secret-token'

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_list_workflows_api_error(self, mock_get):
        """Test workflow listing with API error."""
        service = WorkflowService()

        # Mock API error response
        mock_response = Mock()
        mock_response.status_code = 500
        mock_response.text = 'Internal server error'
        mock_response.json.side_effect = ValueError("Not JSON")
        mock_get.return_value = mock_response

        result = service.list_workflows(
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is False
        assert result['count'] == 0
        assert result['workflows'] == []
        assert 'Failed to list workflows' in result['error']

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_list_workflows_network_error(self, mock_get):
        """Test workflow listing with network error."""
        service = WorkflowService()

        # Mock network exception
        import requests
        mock_get.side_effect = requests.exceptions.ConnectionError("Connection failed")

        result = service.list_workflows(
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is False
        assert result['count'] == 0
        assert result['workflows'] == []
        assert 'Connection failed' in result['error']

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_list_workflows_filters_invalid_names(self, mock_get):
        """Test workflow listing filters out workflows without names."""
        service = WorkflowService()

        # Mock response with some workflows missing names
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'items': [
                {'metadata': {'name': 'workflow-1'}},
                {'metadata': {}},  # Missing name
                {'metadata': {'name': 'workflow-2'}},
                {'other': 'data'}  # Missing metadata
            ]
        }
        mock_get.return_value = mock_response

        result = service.list_workflows(
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is True
        assert result['count'] == 2
        assert 'workflow-1' in result['workflows']
        assert 'workflow-2' in result['workflows']
        assert len(result['workflows']) == 2

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_list_workflows_insecure_flag(self, mock_get):
        """Test workflow listing with insecure flag variations."""
        service = WorkflowService()

        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {'items': []}
        mock_get.return_value = mock_response

        # Test with insecure=True
        service.list_workflows(
            namespace='argo',
            argo_server='https://localhost:2746',
            insecure=True
        )

        call_args = mock_get.call_args
        assert call_args[1]['verify'] is False

        mock_get.reset_mock()

        # Test with insecure=False (default)
        service.list_workflows(
            namespace='argo',
            argo_server='https://localhost:2746',
            insecure=False
        )

        call_args = mock_get.call_args
        assert call_args[1]['verify'] is True


class TestWorkflowServiceStatus:
    """Test suite for workflow status functionality."""

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_get_workflow_status_success(self, mock_get):
        """Test successful workflow status retrieval with step parsing."""
        service = WorkflowService()

        # Mock successful response with workflow status including steps
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'status': {
                'phase': 'Running',
                'progress': '1/2',
                'startedAt': '2024-01-01T10:00:00Z',
                'finishedAt': None,
                'nodes': {
                    'node-1': {
                        'type': 'Pod',
                        'displayName': 'step1',
                        'phase': 'Succeeded',
                        'startedAt': '2024-01-01T10:00:00Z'
                    },
                    'node-2': {
                        'type': 'Pod',
                        'displayName': 'step2',
                        'phase': 'Running',
                        'startedAt': '2024-01-01T10:01:00Z'
                    }
                }
            }
        }
        mock_get.return_value = mock_response

        result = service.get_workflow_status(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746',
            insecure=True
        )

        assert result['success'] is True
        assert result['workflow_name'] == 'test-workflow'
        assert result['phase'] == 'Running'
        assert result['progress'] == '1/2'
        assert len(result['steps']) == 2
        assert result['steps'][0]['name'] == 'step1'
        assert result['steps'][0]['phase'] == 'Succeeded'
        assert result['steps'][1]['name'] == 'step2'
        assert result['steps'][1]['phase'] == 'Running'

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_get_workflow_status_with_suspend_node(self, mock_get):
        """Test workflow status with suspend/approval node."""
        service = WorkflowService()

        # Mock response with suspend node
        mock_response = Mock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            'status': {
                'phase': 'Running',
                'progress': '1/2',
                'startedAt': '2024-01-01T10:00:00Z',
                'finishedAt': None,
                'nodes': {
                    'node-1': {
                        'type': 'Pod',
                        'displayName': 'step1',
                        'phase': 'Succeeded',
                        'startedAt': '2024-01-01T10:00:00Z'
                    },
                    'node-2': {
                        'type': 'Suspend',
                        'displayName': 'approval-gate',
                        'phase': 'Running',
                        'startedAt': '2024-01-01T10:01:00Z'
                    }
                }
            }
        }
        mock_get.return_value = mock_response

        result = service.get_workflow_status(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is True
        assert result['phase'] == 'Running'
        assert len(result['steps']) == 2
        assert result['steps'][1]['type'] == 'Suspend'
        assert result['steps'][1]['phase'] == 'Running'

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_get_workflow_status_not_found(self, mock_get):
        """Test workflow status when workflow doesn't exist."""
        service = WorkflowService()

        # Mock 404 response
        mock_response = Mock()
        mock_response.status_code = 404
        mock_get.return_value = mock_response

        result = service.get_workflow_status(
            workflow_name='nonexistent',
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is False
        assert result['phase'] == 'Unknown'
        assert result['error'] is not None
        assert 'failed' in result['error'].lower() or '404' in result['error']


class TestWorkflowServiceApprove:
    """Test suite for workflow approve/resume functionality."""

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_approve_workflow_success(self, mock_put):
        """Test successful workflow approve/resume."""
        service = WorkflowService()

        # Mock successful response
        mock_response = Mock()
        mock_response.status_code = 200
        mock_put.return_value = mock_response

        result = service.approve_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746',
            insecure=True
        )

        assert result['success'] is True
        assert result['workflow_name'] == 'test-workflow'
        assert result['namespace'] == 'argo'
        assert 'resumed successfully' in result['message']
        assert result['error'] is None

        # Verify request was made correctly
        mock_put.assert_called_once()
        call_args = mock_put.call_args
        assert call_args[0][0] == 'https://localhost:2746/api/v1/workflows/argo/test-workflow/resume'
        assert call_args[1]['verify'] is False

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_approve_workflow_with_token(self, mock_put):
        """Test workflow approve with authentication token."""
        service = WorkflowService()

        mock_response = Mock()
        mock_response.status_code = 200
        mock_put.return_value = mock_response

        result = service.approve_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746',
            token='secret-token'
        )

        assert result['success'] is True

        # Verify Authorization header was set
        call_args = mock_put.call_args
        headers = call_args[1]['headers']
        assert 'Authorization' in headers
        assert headers['Authorization'] == 'Bearer secret-token'

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_approve_workflow_not_found(self, mock_put):
        """Test workflow approve with 404 not found."""
        service = WorkflowService()

        # Mock 404 response
        mock_response = Mock()
        mock_response.status_code = 404
        mock_put.return_value = mock_response

        result = service.approve_workflow(
            workflow_name='nonexistent-workflow',
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is False
        assert result['workflow_name'] == 'nonexistent-workflow'
        assert result['namespace'] == 'argo'
        assert 'not found' in result['message']
        assert result['error'] is not None

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_approve_workflow_api_error(self, mock_put):
        """Test workflow approve with API error."""
        service = WorkflowService()

        # Mock API error response
        mock_response = Mock()
        mock_response.status_code = 500
        mock_response.text = 'Internal server error'
        mock_response.json.side_effect = ValueError("Not JSON")
        mock_put.return_value = mock_response

        result = service.approve_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is False
        assert result['workflow_name'] == 'test-workflow'
        assert 'Failed to resume workflow' in result['message']
        assert result['error'] is not None

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_approve_workflow_network_error(self, mock_put):
        """Test workflow approve with network error."""
        service = WorkflowService()

        # Mock network exception
        import requests
        mock_put.side_effect = requests.exceptions.ConnectionError("Connection failed")

        result = service.approve_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746'
        )

        assert result['success'] is False
        assert result['workflow_name'] == 'test-workflow'
        assert 'Network error' in result['message']
        assert result['error'] is not None

    @patch('console_link.workflow.services.workflow_service.requests.put')
    def test_approve_workflow_insecure_flag(self, mock_put):
        """Test workflow approve with insecure flag variations."""
        service = WorkflowService()

        mock_response = Mock()
        mock_response.status_code = 200
        mock_put.return_value = mock_response

        # Test with insecure=True
        service.approve_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746',
            insecure=True
        )

        call_args = mock_put.call_args
        assert call_args[1]['verify'] is False

        mock_put.reset_mock()

        # Test with insecure=False (default)
        service.approve_workflow(
            workflow_name='test-workflow',
            namespace='argo',
            argo_server='https://localhost:2746',
            insecure=False
        )

        call_args = mock_put.call_args
        assert call_args[1]['verify'] is True
