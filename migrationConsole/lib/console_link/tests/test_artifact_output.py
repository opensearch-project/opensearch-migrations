"""Tests for artifact output support in workflow tree utils and service."""

import sys
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parent.parent))
from console_link.workflow.tree_utils import get_step_status_output, ArtifactRef
from console_link.workflow.services.workflow_service import WorkflowService


class TestArtifactRef:
    """Test ArtifactRef dataclass."""

    def test_artifact_ref_stores_fields(self):
        ref = ArtifactRef(node_id="node-abc", artifact_name="statusOutput")
        assert ref.node_id == "node-abc"
        assert ref.artifact_name == "statusOutput"


class TestGetStepStatusOutputWithArtifacts:
    """Test get_step_status_output with artifact fallback."""

    def test_returns_parameter_value_when_present(self):
        workflow_data = {
            "status": {
                "nodes": {
                    "node-1": {
                        "outputs": {
                            "parameters": [
                                {"name": "statusOutput", "value": "snapshot completed"}
                            ]
                        },
                        "children": []
                    }
                }
            }
        }
        result = get_step_status_output(workflow_data, "node-1")
        assert result == "snapshot completed"

    def test_returns_artifact_ref_when_no_parameter(self):
        workflow_data = {
            "status": {
                "nodes": {
                    "node-1": {
                        "outputs": {
                            "parameters": [],
                            "artifacts": [
                                {"name": "statusOutput", "s3": {"key": "argo-artifacts/wf/pod/statusOutput"}}
                            ]
                        },
                        "children": []
                    }
                }
            }
        }
        result = get_step_status_output(workflow_data, "node-1")
        assert isinstance(result, ArtifactRef)
        assert result.node_id == "node-1"
        assert result.artifact_name == "statusOutput"

    def test_prefers_parameter_over_artifact(self):
        workflow_data = {
            "status": {
                "nodes": {
                    "node-1": {
                        "outputs": {
                            "parameters": [
                                {"name": "statusOutput", "value": "from parameter"}
                            ],
                            "artifacts": [
                                {"name": "statusOutput", "s3": {"key": "argo-artifacts/wf/pod/statusOutput"}}
                            ]
                        },
                        "children": []
                    }
                }
            }
        }
        result = get_step_status_output(workflow_data, "node-1")
        assert result == "from parameter"

    def test_returns_none_when_no_outputs(self):
        workflow_data = {
            "status": {
                "nodes": {
                    "node-1": {
                        "outputs": {"parameters": [], "artifacts": []},
                        "children": []
                    }
                }
            }
        }
        result = get_step_status_output(workflow_data, "node-1")
        assert result is None

    def test_finds_artifact_in_child_node(self):
        workflow_data = {
            "status": {
                "nodes": {
                    "parent": {
                        "outputs": {"parameters": []},
                        "children": ["child-1"]
                    },
                    "child-1": {
                        "outputs": {
                            "parameters": [],
                            "artifacts": [
                                {"name": "statusOutput", "s3": {"key": "argo-artifacts/wf/pod/statusOutput"}}
                            ]
                        },
                        "children": []
                    }
                }
            }
        }
        result = get_step_status_output(workflow_data, "parent")
        assert isinstance(result, ArtifactRef)
        assert result.node_id == "child-1"


class TestWorkflowServiceGetArtifactContent:
    """Test WorkflowService.get_artifact_content."""

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_get_artifact_content_success(self, mock_get):
        mock_get.return_value.status_code = 200
        mock_get.return_value.text = "snapshot completed successfully"

        service = WorkflowService()
        result = service.get_artifact_content(
            workflow_name="my-wf",
            node_id="node-abc",
            artifact_name="statusOutput",
            namespace="ma",
            argo_server="https://argo:2746"
        )

        assert result == "snapshot completed successfully"
        mock_get.assert_called_once_with(
            "https://argo:2746/api/v1/workflows/ma/my-wf/artifacts/node-abc/statusOutput",
            headers={"Content-Type": "application/json"},
            verify=True
        )

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_get_artifact_content_returns_none_on_404(self, mock_get):
        mock_get.return_value.status_code = 404

        service = WorkflowService()
        result = service.get_artifact_content(
            workflow_name="my-wf",
            node_id="node-abc",
            artifact_name="statusOutput",
            namespace="ma",
            argo_server="https://argo:2746"
        )

        assert result is None

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_get_artifact_content_with_token(self, mock_get):
        mock_get.return_value.status_code = 200
        mock_get.return_value.text = "content"

        service = WorkflowService()
        result = service.get_artifact_content(
            workflow_name="my-wf",
            node_id="node-abc",
            artifact_name="statusOutput",
            namespace="ma",
            argo_server="https://argo:2746",
            token="my-token"
        )

        assert result == "content"
        call_kwargs = mock_get.call_args
        assert call_kwargs[1]["headers"]["Authorization"] == "Bearer my-token"

    @patch('console_link.workflow.services.workflow_service.requests.get')
    def test_get_artifact_content_insecure(self, mock_get):
        mock_get.return_value.status_code = 200
        mock_get.return_value.text = "content"

        service = WorkflowService()
        service.get_artifact_content(
            workflow_name="my-wf",
            node_id="node-abc",
            artifact_name="statusOutput",
            namespace="ma",
            argo_server="https://argo:2746",
            insecure=True
        )

        call_kwargs = mock_get.call_args
        assert call_kwargs[1]["verify"] is False
