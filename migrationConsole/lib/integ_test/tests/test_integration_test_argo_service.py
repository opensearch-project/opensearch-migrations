import json
from subprocess import CompletedProcess
from unittest.mock import Mock, patch, mock_open

import pytest
import yaml

from integ_test.integration_test_argo_service import IntegrationTestArgoService, WorkflowEndedBeforeSuspend
from console_link.models.command_result import CommandResult
from console_link.models.command_runner import CommandRunnerError, FlagOnlyArgument


# Test fixtures
@pytest.fixture
def argo_service():
    """Create IntegrationTestArgoService instance with test configuration."""
    return IntegrationTestArgoService(namespace="test-namespace")


@pytest.fixture
def mock_completed_process():
    """Create a mock CompletedProcess for testing."""
    return CompletedProcess(
        args=["kubectl", "get", "workflow"],
        returncode=0,
        stdout="test-workflow-123",
        stderr=""
    )


@pytest.fixture
def mock_success_result():
    """Create a successful CommandResult for testing."""
    return CommandResult(success=True, value="test-workflow-123")


# Helper functions
def create_mock_workflow_data(phase="Running", has_suspend_node=False):
    """Create mock workflow data for testing."""
    nodes = {
        "node1": {"phase": "Running", "type": "Suspend" if has_suspend_node else "Container"},
        "node2": {"phase": "Succeeded", "type": "Container"}
    }
    return {
        "status": {
            "phase": phase,
            "nodes": nodes
        }
    }


def create_mock_cluster_workflow_data(cluster_type="source", config=None):
    """Create mock workflow data with cluster configuration."""
    if config is None:
        config = {"endpoint": "https://test-cluster.com", "no_auth": None}

    return {
        "status": {
            "nodes": {
                "node1": {
                    "displayName": f"create-{cluster_type}-cluster",
                    "phase": "Succeeded",
                    "outputs": {
                        "parameters": [
                            {"name": "cluster-config", "value": json.dumps(config)}
                        ]
                    }
                }
            }
        }
    }


# Workflow lifecycle tests
@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._run_kubectl_command')
@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._create_workflow_yaml')
@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._wait_for_workflow_exists')
@patch('os.unlink')
def test_start_workflow_success(mock_unlink, mock_wait, mock_create_yaml, mock_kubectl,
                                argo_service, mock_completed_process):
    """Test successful workflow start."""
    # Setup
    mock_create_yaml.return_value = "/tmp/test-workflow.yaml"
    mock_kubectl.return_value = CommandResult(success=True, value="", output=mock_completed_process)
    mock_wait.return_value = CommandResult(success=True, value="Workflow exists")

    # Execute
    result = argo_service.start_workflow("test-template", {"param1": "value1"})

    # Verify
    assert result.success is True
    assert result.value == "test-workflow-123"
    mock_create_yaml.assert_called_once_with("test-template", {"param1": "value1"})
    mock_kubectl.assert_called_once()
    mock_wait.assert_called_once_with(workflow_name="test-workflow-123")
    mock_unlink.assert_called_once_with("/tmp/test-workflow.yaml")


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._run_kubectl_command')
@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._create_workflow_yaml')
def test_start_workflow_failure(mock_create_yaml, mock_kubectl, argo_service):
    """Test workflow start failure."""
    mock_create_yaml.return_value = "/tmp/test-workflow.yaml"
    mock_kubectl.side_effect = CommandRunnerError(1, ["kubectl", "create"], "kubectl failed")

    result = argo_service.start_workflow("test-template")

    assert result.success is False
    assert "Failed to start workflow" in result.value


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._patch_workflow')
@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._get_workflow_status_json')
def test_resume_workflow(mock_get_json, mock_patch, argo_service, mock_success_result):
    """Test workflow resume."""
    workflow_data = {
        "spec": {"suspend": True},
        "status": {
            "nodes": {
                "node1": {
                    "type": "Suspend",
                    "phase": "Running",
                    "outputs": {
                        "parameters": [
                            {
                                "name": "approval",
                                "valueFrom": {"supplied": {}, "default": "approved"}
                            }
                        ]
                    }
                },
                "node2": {"type": "Pod", "phase": "Running"}
            }
        }
    }
    mock_get_json.return_value = workflow_data
    mock_patch.return_value = mock_success_result

    result = argo_service.resume_workflow("test-workflow")

    assert result.success is True
    assert workflow_data["status"]["nodes"]["node1"]["phase"] == "Succeeded"
    assert "finishedAt" in workflow_data["status"]["nodes"]["node1"]
    assert workflow_data["status"]["nodes"]["node1"]["outputs"]["parameters"][0]["value"] == "approved"
    assert "valueFrom" not in workflow_data["status"]["nodes"]["node1"]["outputs"]["parameters"][0]
    patch_data = mock_patch.call_args[0][1]
    assert mock_patch.call_args[0][0] == "test-workflow"
    assert patch_data["spec"] == {"suspend": None}
    assert patch_data["status"]["nodes"]["node1"]["phase"] == "Succeeded"
    assert "finishedAt" in patch_data["status"]["nodes"]["node1"]
    assert patch_data["status"]["nodes"]["node1"]["outputs"]["parameters"][0]["value"] == "approved"


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._get_workflow_status_json')
def test_resume_workflow_no_suspend_state(mock_get_json, argo_service):
    """Test workflow resume when nothing is suspended."""
    mock_get_json.return_value = {"spec": {}, "status": {"nodes": {"node1": {"type": "Pod", "phase": "Running"}}}}

    with patch.object(argo_service, '_patch_workflow') as mock_patch:
        result = argo_service.resume_workflow("test-workflow")

    assert result.success is True
    assert "did not need to be resumed" in result.value
    mock_patch.assert_not_called()


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._run_kubectl_command')
def test_stop_workflow(mock_kubectl, argo_service, mock_success_result):
    """Test workflow stop."""
    mock_kubectl.return_value = mock_success_result

    result = argo_service.stop_workflow("test-workflow")

    assert result.success is True
    mock_kubectl.assert_called_once_with({
        "patch": FlagOnlyArgument,
        "workflow": "test-workflow",
        "--namespace": "test-namespace",
        "--type": "merge",
        "-p": json.dumps({"spec": {"shutdown": "Stop"}})
    })


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._run_kubectl_command')
def test_delete_workflow(mock_kubectl, argo_service, mock_success_result):
    """Test workflow deletion."""
    mock_kubectl.return_value = mock_success_result

    result = argo_service.delete_workflow("test-workflow")

    assert result.success is True
    expected_args = {
        "delete": FlagOnlyArgument,
        "workflow": "test-workflow",
        "--namespace": "test-namespace"
    }
    mock_kubectl.assert_called_once_with(expected_args)


# Workflow status tests
@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._get_workflow_status_json')
def test_get_workflow_status_with_suspended_nodes(mock_get_json, argo_service):
    """Test getting workflow status with suspended nodes."""
    mock_get_json.return_value = create_mock_workflow_data("Running", has_suspend_node=True)

    result = argo_service.get_workflow_status("test-workflow")

    assert result.success is True
    assert result.value["phase"] == "Running"
    assert result.value["has_suspended_nodes"] is True


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._get_workflow_status_json')
def test_get_workflow_status_without_suspended_nodes(mock_get_json, argo_service):
    """Test getting workflow status without suspended nodes."""
    mock_get_json.return_value = create_mock_workflow_data("Running", has_suspend_node=False)

    result = argo_service.get_workflow_status("test-workflow")

    assert result.success is True
    assert result.value["phase"] == "Running"
    assert result.value["has_suspended_nodes"] is False


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._get_workflow_status_json')
def test_get_workflow_status_completed(mock_get_json, argo_service):
    """Test getting workflow status for completed workflow."""
    mock_get_json.return_value = create_mock_workflow_data("Succeeded", has_suspend_node=False)

    result = argo_service.get_workflow_status("test-workflow")

    assert result.success is True
    assert result.value["phase"] == "Succeeded"
    assert result.value["has_suspended_nodes"] is False


# Workflow waiting tests
@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService.get_workflow_status')
def test_wait_for_suspend_success(mock_get_status, argo_service):
    """Test successful wait for suspend."""
    mock_get_status.return_value = CommandResult(
        success=True,
        value={"phase": "Running", "has_suspended_nodes": True}
    )

    result = argo_service.wait_for_suspend("test-workflow", timeout_seconds=1, interval=0.1)

    assert result.success is True
    assert "suspended state" in result.value


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService.get_workflow_status')
def test_wait_for_suspend_workflow_ended(mock_get_status, argo_service):
    """Test wait for suspend when workflow ends."""
    mock_get_status.return_value = CommandResult(
        success=True,
        value={"phase": "Failed", "has_suspended_nodes": False}
    )

    with pytest.raises(WorkflowEndedBeforeSuspend) as exc_info:
        argo_service.wait_for_suspend("test-workflow", timeout_seconds=1, interval=0.1)

    assert "test-workflow" in str(exc_info.value)
    assert "Failed" in str(exc_info.value)


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService.get_workflow_status')
@patch('time.sleep')
def test_wait_for_suspend_timeout(mock_sleep, mock_get_status, argo_service):
    """Test wait for suspend timeout."""
    mock_get_status.return_value = CommandResult(
        success=True,
        value={"phase": "Running", "has_suspended_nodes": False}
    )

    with pytest.raises(TimeoutError) as exc_info:
        argo_service.wait_for_suspend("test-workflow", timeout_seconds=1, interval=0.1)

    assert "did not reach suspended state" in str(exc_info.value)


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService.get_workflow_status')
def test_wait_for_suspend_status_failure(mock_get_status, argo_service):
    """Test wait for suspend when status check fails."""
    mock_get_status.return_value = CommandResult(success=False, value="Status check failed")

    with pytest.raises(ValueError) as exc_info:
        argo_service.wait_for_suspend("test-workflow", timeout_seconds=1, interval=0.1)

    assert "Failed to get workflow status" in str(exc_info.value)


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService.get_workflow_status')
def test_wait_for_ending_phase_success(mock_get_status, argo_service):
    """Test successful wait for ending phase."""
    mock_get_status.return_value = CommandResult(
        success=True,
        value={"phase": "Succeeded", "has_suspended_nodes": False}
    )

    result = argo_service.wait_for_ending_phase("test-workflow", timeout_seconds=1, interval=0.1)

    assert result.success is True
    assert "ending phase of Succeeded" in result.value


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService.get_workflow_status')
@patch('time.sleep')
def test_wait_for_ending_phase_timeout(mock_sleep, mock_get_status, argo_service):
    """Test wait for ending phase timeout."""
    mock_get_status.return_value = CommandResult(
        success=True,
        value={"phase": "Running", "has_suspended_nodes": False}
    )

    with pytest.raises(TimeoutError) as exc_info:
        argo_service.wait_for_ending_phase("test-workflow", timeout_seconds=1, interval=0.1)

    assert "did not reach ending state" in str(exc_info.value)


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService.get_workflow_status')
def test_wait_for_ending_phase_status_failure(mock_get_status, argo_service):
    """Test wait for ending phase when status check fails."""
    mock_get_status.return_value = CommandResult(success=False, value="Status check failed")

    with pytest.raises(ValueError) as exc_info:
        argo_service.wait_for_ending_phase("test-workflow", timeout_seconds=1, interval=0.1)

    assert "Failed to get workflow status" in str(exc_info.value)


# Internal method tests
def test_run_kubectl_command_success(argo_service, mock_success_result):
    """Test successful kubectl command execution."""
    with patch('integ_test.integration_test_argo_service.CommandRunner') as mock_command_runner_class:
        mock_runner_instance = Mock()
        mock_runner_instance.run.return_value = mock_success_result
        mock_command_runner_class.return_value = mock_runner_instance

        result = argo_service._run_kubectl_command({"get": "pods"})

        assert result.success is True
        mock_command_runner_class.assert_called_once_with("kubectl", {"get": "pods"})
        mock_runner_instance.run.assert_called_once_with(print_to_console=False)


def test_run_kubectl_command_failure(argo_service):
    """Test kubectl command execution failure."""
    with patch('integ_test.integration_test_argo_service.CommandRunner') as mock_command_runner_class:
        mock_runner_instance = Mock()
        mock_runner_instance.run.side_effect = CommandRunnerError(1, ["kubectl"], "Command failed")
        mock_command_runner_class.return_value = mock_runner_instance

        with pytest.raises(CommandRunnerError):
            argo_service._run_kubectl_command({"get": "pods"})


def test_get_workflow_logs_success(argo_service, mock_success_result):
    """Test successful workflow log command execution."""
    with patch('integ_test.integration_test_argo_service.CommandRunner') as mock_command_runner_class:
        mock_runner_instance = Mock()
        mock_runner_instance.run.return_value = mock_success_result
        mock_command_runner_class.return_value = mock_runner_instance

        result = argo_service._get_workflow_logs(
            "test-workflow", follow=True, print_output=True, stream_output=True
        )

        assert result.success is True
        expected_args = {
            "logs": FlagOnlyArgument,
            "-l": "workflows.argoproj.io/workflow=test-workflow",
            "--namespace": "test-namespace",
            "--all-containers=true": FlagOnlyArgument,
            "--prefix=true": FlagOnlyArgument,
            "--tail": "-1",
            "--follow": FlagOnlyArgument
        }
        mock_command_runner_class.assert_called_once_with("kubectl", expected_args)
        mock_runner_instance.run.assert_called_once_with(print_to_console=True, stream_output=True)


def test_get_workflow_logs_failure(argo_service):
    """Test workflow log command execution failure."""
    with patch('integ_test.integration_test_argo_service.CommandRunner') as mock_command_runner_class:
        mock_runner_instance = Mock()
        mock_runner_instance.run.side_effect = CommandRunnerError(1, ["kubectl"], "Command failed")
        mock_command_runner_class.return_value = mock_runner_instance

        with pytest.raises(CommandRunnerError):
            argo_service._get_workflow_logs("test-workflow")


def test_create_workflow_yaml_without_parameters(argo_service):
    """Test creating workflow YAML without parameters."""
    with patch('tempfile.NamedTemporaryFile') as mock_temp_file:
        mock_file = Mock()
        mock_file.name = "/tmp/test-workflow.yaml"
        mock_temp_file.return_value.__enter__.return_value = mock_file

        with patch('builtins.open', mock_open()):
            result = argo_service._create_workflow_yaml("test-template")

            assert result == "/tmp/test-workflow.yaml"

            # Verify YAML structure
            written_calls = mock_file.write.call_args_list
            written_content = ''.join(call[0][0] for call in written_calls)
            workflow_data = yaml.safe_load(written_content)

            assert workflow_data["apiVersion"] == "argoproj.io/v1alpha1"
            assert workflow_data["kind"] == "Workflow"
            assert workflow_data["metadata"]["generateName"] == "test-template-"
            assert workflow_data["spec"]["workflowTemplateRef"]["name"] == "test-template"
            assert workflow_data["spec"]["entrypoint"] == "main"
            assert "arguments" not in workflow_data["spec"]


def test_create_workflow_yaml_with_parameters(argo_service):
    """Test creating workflow YAML with parameters."""
    parameters = {
        "string_param": "test_value",
        "dict_param": {"key": "value"},
        "list_param": ["item1", "item2"]
    }

    with patch('tempfile.NamedTemporaryFile') as mock_temp_file:
        mock_file = Mock()
        mock_file.name = "/tmp/test-workflow.yaml"
        mock_temp_file.return_value.__enter__.return_value = mock_file

        with patch('builtins.open', mock_open()):
            result = argo_service._create_workflow_yaml("test-template", parameters)

            assert result == "/tmp/test-workflow.yaml"

            # Verify YAML structure with parameters
            written_calls = mock_file.write.call_args_list
            written_content = ''.join(call[0][0] for call in written_calls)
            workflow_data = yaml.safe_load(written_content)

            assert "arguments" in workflow_data["spec"]
            assert "parameters" in workflow_data["spec"]["arguments"]

            params = workflow_data["spec"]["arguments"]["parameters"]
            assert len(params) == 3

            # Verify parameter serialization
            string_param = next(p for p in params if p["name"] == "string_param")
            assert string_param["value"] == "test_value"

            dict_param = next(p for p in params if p["name"] == "dict_param")
            assert json.loads(dict_param["value"]) == {"key": "value"}

            list_param = next(p for p in params if p["name"] == "list_param")
            assert json.loads(list_param["value"]) == ["item1", "item2"]


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._run_kubectl_command')
@patch('time.sleep')
def test_wait_for_workflow_exists_success(mock_sleep, mock_kubectl, argo_service):
    """Test successful wait for workflow to exist."""
    mock_completed_process = CompletedProcess(
        args=["kubectl", "get", "workflow"],
        returncode=0,
        stdout="workflow-exists-output",
        stderr=""
    )
    mock_kubectl.return_value = CommandResult(success=True, value="", output=mock_completed_process)

    result = argo_service._wait_for_workflow_exists("test-workflow", timeout_seconds=1, interval=0.1)

    assert result.success is True
    assert "Workflow test-workflow exists" in result.value


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._run_kubectl_command')
@patch('time.sleep')
def test_wait_for_workflow_exists_timeout(mock_sleep, mock_kubectl, argo_service):
    """Test timeout waiting for workflow to exist."""
    mock_completed_process = CompletedProcess(
        args=["kubectl", "get", "workflow"],
        returncode=0,
        stdout="",
        stderr=""
    )
    mock_kubectl.return_value = CommandResult(success=True, value="", output=mock_completed_process)

    result = argo_service._wait_for_workflow_exists("test-workflow", timeout_seconds=1, interval=0.1)

    assert result.success is False
    assert "Timeout waiting for workflow" in result.value


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._run_kubectl_command')
def test_get_workflow_status_json_success(mock_kubectl, argo_service):
    """Test successful workflow status JSON retrieval."""
    workflow_data = {"status": {"phase": "Running"}}
    mock_completed_process = CompletedProcess(
        args=["kubectl", "get", "workflow"],
        returncode=0,
        stdout=json.dumps(workflow_data),
        stderr=""
    )
    mock_kubectl.return_value = CommandResult(success=True, value="", output=mock_completed_process)

    result = argo_service._get_workflow_status_json("test-workflow")

    assert result == workflow_data
    expected_args = {
        "get": FlagOnlyArgument,
        "workflow": "test-workflow",
        "-o": "json",
        "--namespace": "test-namespace"
    }
    mock_kubectl.assert_called_once_with(expected_args)


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._run_kubectl_command')
def test_get_workflow_status_json_invalid_json(mock_kubectl, argo_service):
    """Test workflow status JSON retrieval with invalid JSON."""
    mock_completed_process = CompletedProcess(
        args=["kubectl", "get", "workflow"],
        returncode=0,
        stdout="invalid json",
        stderr=""
    )
    mock_kubectl.return_value = CommandResult(success=True, value="", output=mock_completed_process)

    with pytest.raises(json.JSONDecodeError):
        argo_service._get_workflow_status_json("test-workflow")


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._get_workflow_status_json')
def test_get_cluster_config_from_workflow_success(mock_get_json, argo_service):
    """Test successful cluster config extraction."""
    cluster_config = {"endpoint": "https://test-cluster.com", "no_auth": None}
    mock_get_json.return_value = create_mock_cluster_workflow_data("source", cluster_config)

    with patch('integ_test.integration_test_argo_service.Cluster') as mock_cluster_class:
        mock_cluster_instance = Mock()
        mock_cluster_class.return_value = mock_cluster_instance

        result = argo_service.get_cluster_config_from_workflow("test-workflow", "source")

        assert result == mock_cluster_instance
        mock_cluster_class.assert_called_once_with(config=cluster_config)


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._get_workflow_status_json')
def test_get_cluster_config_from_workflow_node_not_found(mock_get_json, argo_service):
    """Test cluster config extraction when node not found."""
    workflow_data = {
        "status": {
            "nodes": {
                "node1": {
                    "displayName": "other-task",
                    "phase": "Succeeded"
                }
            }
        }
    }
    mock_get_json.return_value = workflow_data

    with pytest.raises(ValueError) as exc_info:
        argo_service.get_cluster_config_from_workflow("test-workflow", "source")

    assert "Did not find source cluster config" in str(exc_info.value)


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._get_workflow_status_json')
def test_get_cluster_config_from_workflow_no_config_param(mock_get_json, argo_service):
    """Test cluster config extraction when config parameter not found."""
    workflow_data = {
        "status": {
            "nodes": {
                "node1": {
                    "displayName": "create-source-cluster",
                    "phase": "Succeeded",
                    "outputs": {
                        "parameters": [
                            {"name": "other-param", "value": "other-value"}
                        ]
                    }
                }
            }
        }
    }
    mock_get_json.return_value = workflow_data

    with pytest.raises(ValueError) as exc_info:
        argo_service.get_cluster_config_from_workflow("test-workflow", "source")

    assert "Did not find source cluster config" in str(exc_info.value)


@patch('integ_test.integration_test_argo_service.IntegrationTestArgoService._get_workflow_status_json')
def test_get_cluster_config_from_workflow_invalid_json(mock_get_json, argo_service):
    """Test cluster config extraction with invalid JSON config."""
    workflow_data = {
        "status": {
            "nodes": {
                "node1": {
                    "displayName": "create-source-cluster",
                    "phase": "Succeeded",
                    "outputs": {
                        "parameters": [
                            {"name": "cluster-config", "value": "invalid json"}
                        ]
                    }
                }
            }
        }
    }
    mock_get_json.return_value = workflow_data

    with pytest.raises(json.JSONDecodeError):
        argo_service.get_cluster_config_from_workflow("test-workflow", "source")
