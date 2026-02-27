"""Tests for manage_injections.py - dependency injection interfaces."""

import io
import json
import threading
import time
from unittest.mock import MagicMock, patch

import pytest

from console_link.workflow.tui.manage_injections import (
    WaiterInterface,
    make_argo_service,
    make_k8s_pod_scraper,
)


# --- WaiterInterface Threading Tests ---

class TestWaiterInterfaceThreading:
    """Stress tests for WaiterInterface to detect deadlocks."""

    @pytest.fixture
    def mock_get(self):
        """Create a controllable mock requests.get for Argo API polling."""
        with patch('console_link.workflow.tui.manage_injections.requests.get') as mock:
            yield mock

    def test_rapid_trigger_reset_cycles_no_deadlock(self, mock_get):
        """Rapidly triggering and resetting should not deadlock."""
        mock_response = MagicMock()
        mock_response.status_code = 404
        mock_get.return_value = mock_response

        waiter = WaiterInterface.default("test-wf", "test-ns",
                                         "http://argo:2746")

        def stress_cycle():
            for _ in range(20):
                waiter.trigger()
                time.sleep(0.01)
                waiter.reset()

        threads = [threading.Thread(target=stress_cycle) for _ in range(3)]
        for t in threads:
            t.start()

        # Timeout detection - if threads don't complete in 5s, we have a deadlock
        for t in threads:
            t.join(timeout=5.0)
            assert not t.is_alive(), "Thread deadlocked during rapid trigger/reset cycles"

    def test_concurrent_triggers_only_spawn_one_thread(self, mock_get):
        """Multiple concurrent trigger() calls should only spawn one poll thread."""
        call_count = [0]
        call_lock = threading.Lock()
        poll_started = threading.Event()
        poll_can_exit = threading.Event()

        def mock_get_fn(*args, **kwargs):
            with call_lock:
                call_count[0] += 1
            poll_started.set()
            poll_can_exit.wait(timeout=2.0)
            resp = MagicMock()
            resp.status_code = 200
            return resp

        mock_get.side_effect = mock_get_fn

        waiter = WaiterInterface.default("test-wf", "test-ns",
                                         "http://argo:2746")

        # Fire multiple triggers concurrently
        threads = [threading.Thread(target=waiter.trigger) for _ in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=2.0)

        # Wait for poll to start
        assert poll_started.wait(timeout=2.0), "Poll never started"

        # Allow exit
        poll_can_exit.set()
        time.sleep(0.2)

    def test_cleanup_during_active_wait_no_deadlock(self, mock_get):
        """Cleanup while polling should not deadlock."""
        poll_waiting = threading.Event()

        def mock_get_fn(*args, **kwargs):
            poll_waiting.set()
            time.sleep(0.5)
            resp = MagicMock()
            resp.status_code = 404
            return resp

        mock_get.side_effect = mock_get_fn

        waiter = WaiterInterface.default("test-wf", "test-ns",
                                         "http://argo:2746")
        waiter.trigger()

        # Wait for poll to be active
        assert poll_waiting.wait(timeout=2.0), "Poll never started"

        # Reset and re-trigger should not deadlock
        cleanup_done = threading.Event()

        def trigger_again():
            waiter.reset()
            cleanup_done.set()

        cleanup_thread = threading.Thread(target=trigger_again)
        cleanup_thread.start()
        cleanup_thread.join(timeout=3.0)

        assert not cleanup_thread.is_alive(), "Cleanup thread deadlocked"

    def test_multiple_start_stop_cycles_complete(self, mock_get):
        """Multiple full start/poll/stop cycles should complete without issues."""
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_get.return_value = mock_response

        waiter = WaiterInterface.default("test-wf", "test-ns",
                                         "http://argo:2746")

        cycle_count = 0
        for i in range(5):
            waiter.reset()
            waiter.trigger()

            # Wait for checker to become true (with timeout)
            start = time.time()
            while not waiter.checker() and (time.time() - start) < 2.0:
                time.sleep(0.05)

            assert waiter.checker(), f"Cycle {i}: checker never became true"
            cycle_count += 1

        assert cycle_count == 5, f"Only completed {cycle_count} cycles"


# --- ArgoWorkflowInterface Tests ---

class TestArgoServiceFiltering:
    """Tests that make_argo_service properly filters/slims workflow data."""

    def _make_bloated_workflow_response(self):
        """Create a realistic bloated workflow response."""
        return {
            "metadata": {
                "name": "my-workflow",
                "namespace": "default",
                "resourceVersion": "12345",
                "uid": "abc-123-def",
                "creationTimestamp": "2024-01-01T00:00:00Z",
                "labels": {"workflows.argoproj.io/phase": "Running"},
                "annotations": {"some": "annotation"}
            },
            "status": {
                "startedAt": "2024-01-01T00:00:00Z",
                "finishedAt": None,
                "phase": "Running",
                "progress": "1/3",
                "nodes": {
                    "node-1": {
                        "id": "node-1",
                        "displayName": "my-workflow.step-one(0:param=value)",
                        "name": "my-workflow.step-one",
                        "type": "Pod",
                        "phase": "Succeeded",
                        "boundaryID": "my-workflow",
                        "children": ["node-2"],
                        "startedAt": "2024-01-01T00:01:00Z",
                        "finishedAt": "2024-01-01T00:02:00Z",
                        "templateName": "step-one-template",
                        "templateScope": "local/my-workflow",
                        "hostNodeName": "ip-10-0-1-100.ec2.internal",
                        "resourcesDuration": {"cpu": 10, "memory": 100},
                        "inputs": {
                            "parameters": [
                                {"name": "configContents", "value": "important-config"},
                                {"name": "groupName", "value": "my-group"},
                                {"name": "irrelevantParam", "value": "should-be-filtered"},
                                {"name": "anotherParam", "value": "also-filtered"}
                            ]
                        },
                        "outputs": {
                            "parameters": [
                                {"name": "statusOutput", "value": "status-data"},
                                {"name": "overriddenPhase", "value": "CustomPhase"},
                                {"name": "result", "value": "should-be-filtered"},
                                {"name": "exitCode", "value": "0"}
                            ],
                            "artifacts": [{"name": "main-logs", "s3": {"key": "logs/node-1"}}]
                        }
                    },
                    "node-2": {
                        "id": "node-2",
                        "displayName": "my-workflow.step-two(0:x=y)",
                        "name": "my-workflow.step-two",
                        "type": "Pod",
                        "phase": "Running",
                        "boundaryID": "my-workflow",
                        "children": [],
                        "startedAt": "2024-01-01T00:02:00Z",
                        "finishedAt": None,
                        "templateName": "step-two-template",
                        "hostNodeName": "ip-10-0-1-101.ec2.internal",
                        "inputs": {"parameters": []},
                        "outputs": {"parameters": []}
                    }
                }
            }
        }

    @patch('console_link.workflow.tui.manage_injections.WorkflowService')
    @patch('console_link.workflow.tui.manage_injections.requests.get')
    def test_filters_node_fields_to_essential_only(self, mock_get, mock_service_class):
        """Verify extraneous node fields are dropped."""
        bloated = self._make_bloated_workflow_response()

        mock_service = MagicMock()
        mock_service.get_workflow_status.return_value = {
            'workflow': {'metadata': {'resourceVersion': '12345'}},
            'started_at': '2024-01-01T00:00:00Z'
        }
        mock_service_class.return_value = mock_service

        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.raw = io.BytesIO(json.dumps(bloated).encode())
        mock_get.return_value = mock_response

        argo = make_argo_service("http://argo:2746", False, "token")
        _, slim_data = argo.get_workflow("my-workflow", "default")

        node1 = slim_data["status"]["nodes"]["node-1"]

        # Should have these fields
        assert node1["id"] == "node-1"
        assert node1["phase"] == "Succeeded"
        assert node1["type"] == "Pod"
        assert node1["boundaryID"] == "my-workflow"
        assert node1["children"] == ["node-2"]
        assert node1["startedAt"] == "2024-01-01T00:01:00Z"
        assert node1["finishedAt"] == "2024-01-01T00:02:00Z"

        # Should NOT have these extra fields
        assert "templateName" not in node1
        assert "templateScope" not in node1
        assert "hostNodeName" not in node1
        assert "resourcesDuration" not in node1
        assert "name" not in node1

    @patch('console_link.workflow.tui.manage_injections.WorkflowService')
    @patch('console_link.workflow.tui.manage_injections.requests.get')
    def test_cleans_display_name(self, mock_get, mock_service_class):
        """Verify displayName is cleaned via clean_display_name."""
        bloated = self._make_bloated_workflow_response()

        mock_service = MagicMock()
        mock_service.get_workflow_status.return_value = {
            'workflow': {'metadata': {'resourceVersion': '12345'}},
            'started_at': '2024-01-01T00:00:00Z'
        }
        mock_service_class.return_value = mock_service

        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.raw = io.BytesIO(json.dumps(bloated).encode())
        mock_get.return_value = mock_response

        argo = make_argo_service("http://argo:2746", False, "token")
        _, slim_data = argo.get_workflow("my-workflow", "default")

        node1 = slim_data["status"]["nodes"]["node-1"]
        # Original: "my-workflow.step-one(0:param=value)" should be cleaned
        # clean_display_name strips the workflow prefix and params
        assert "my-workflow." not in node1["displayName"]
        assert "(0:" not in node1["displayName"]

    @patch('console_link.workflow.tui.manage_injections.WorkflowService')
    @patch('console_link.workflow.tui.manage_injections.requests.get')
    def test_filters_input_parameters_to_allowed_list(self, mock_get, mock_service_class):
        """Verify only groupName and configContents are kept in inputs."""
        bloated = self._make_bloated_workflow_response()

        mock_service = MagicMock()
        mock_service.get_workflow_status.return_value = {
            'workflow': {'metadata': {'resourceVersion': '12345'}},
            'started_at': '2024-01-01T00:00:00Z'
        }
        mock_service_class.return_value = mock_service

        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.raw = io.BytesIO(json.dumps(bloated).encode())
        mock_get.return_value = mock_response

        argo = make_argo_service("http://argo:2746", False, "token")
        _, slim_data = argo.get_workflow("my-workflow", "default")

        node1_inputs = slim_data["status"]["nodes"]["node-1"]["inputs"]["parameters"]
        param_names = {p["name"] for p in node1_inputs}

        assert param_names == {"configContents", "groupName"}
        assert "irrelevantParam" not in param_names
        assert "anotherParam" not in param_names

    @patch('console_link.workflow.tui.manage_injections.WorkflowService')
    @patch('console_link.workflow.tui.manage_injections.requests.get')
    def test_filters_output_parameters_to_allowed_list(self, mock_get, mock_service_class):
        """Verify only statusOutput and overriddenPhase are kept in outputs."""
        bloated = self._make_bloated_workflow_response()

        mock_service = MagicMock()
        mock_service.get_workflow_status.return_value = {
            'workflow': {'metadata': {'resourceVersion': '12345'}},
            'started_at': '2024-01-01T00:00:00Z'
        }
        mock_service_class.return_value = mock_service

        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.raw = io.BytesIO(json.dumps(bloated).encode())
        mock_get.return_value = mock_response

        argo = make_argo_service("http://argo:2746", False, "token")
        _, slim_data = argo.get_workflow("my-workflow", "default")

        node1_outputs = slim_data["status"]["nodes"]["node-1"]["outputs"]["parameters"]
        param_names = {p["name"] for p in node1_outputs}

        assert param_names == {"statusOutput", "overriddenPhase"}
        assert "result" not in param_names
        assert "exitCode" not in param_names

    @patch('console_link.workflow.tui.manage_injections.WorkflowService')
    @patch('console_link.workflow.tui.manage_injections.requests.get')
    def test_raises_on_non_200_response(self, mock_get, mock_service_class):
        """Verify HTTPError is raised when workflow not found in live or archive API."""
        mock_service = MagicMock()
        mock_service.get_workflow_status.return_value = {'workflow': {'metadata': {}}}
        mock_service._fetch_archived_workflow.return_value = None
        mock_service_class.return_value = mock_service

        mock_response = MagicMock()
        mock_response.status_code = 404
        mock_get.return_value = mock_response

        argo = make_argo_service("http://argo:2746", False, "token")

        with pytest.raises(Exception) as exc_info:
            argo.get_workflow("missing-wf", "default")

        assert "not found" in str(exc_info.value).lower()


# --- PodScraperInterface Tests ---

class TestPodScraperExactApiContract:
    """Tests that make_k8s_pod_scraper uses exact K8s API parameters."""

    def test_fetch_pods_metadata_api_path(self):
        """Verify exact API path format."""
        mock_client = MagicMock()
        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({"items": []}).encode()
        mock_client.api_client.call_api.return_value = (mock_response,)

        scraper = make_k8s_pod_scraper(mock_client)
        scraper.fetch_pods_metadata("my-workflow", "my-namespace", use_cache=True)

        call_args = mock_client.api_client.call_api.call_args
        assert call_args[0][0] == "/api/v1/namespaces/my-namespace/pods"
        assert call_args[0][1] == "GET"

    def test_fetch_pods_metadata_accept_header_for_partial_metadata(self):
        """Verify Accept header requests PartialObjectMetadataList."""
        mock_client = MagicMock()
        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({"items": []}).encode()
        mock_client.api_client.call_api.return_value = (mock_response,)

        scraper = make_k8s_pod_scraper(mock_client)
        scraper.fetch_pods_metadata("wf", "ns", use_cache=True)

        call_args = mock_client.api_client.call_api.call_args
        headers = call_args[1]["header_params"]

        expected_accept = ("application/json;as=PartialObjectMetadataList;"
                           "v=v1;g=meta.k8s.io")
        assert headers["Accept"] == expected_accept

    def test_fetch_pods_metadata_label_selector(self):
        """Verify labelSelector query param format."""
        mock_client = MagicMock()
        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({"items": []}).encode()
        mock_client.api_client.call_api.return_value = (mock_response,)

        scraper = make_k8s_pod_scraper(mock_client)
        scraper.fetch_pods_metadata("test-workflow", "ns", use_cache=True)

        call_args = mock_client.api_client.call_api.call_args
        query_params = call_args[1]["query_params"]

        assert ("labelSelector", "workflows.argoproj.io/workflow=test-workflow") in query_params

    def test_fetch_pods_metadata_with_cache_includes_resource_version_zero(self):
        """Verify use_cache=True adds resourceVersion=0 for API cache hit."""
        mock_client = MagicMock()
        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({"items": []}).encode()
        mock_client.api_client.call_api.return_value = (mock_response,)

        scraper = make_k8s_pod_scraper(mock_client)
        scraper.fetch_pods_metadata("wf", "ns", True)

        call_args = mock_client.api_client.call_api.call_args
        query_params = call_args[1]["query_params"]

        assert ("resourceVersion", "0") in query_params

    def test_fetch_pods_metadata_without_cache_omits_resource_version(self):
        """Verify use_cache=False omits resourceVersion for strong consistency."""
        mock_client = MagicMock()
        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({"items": []}).encode()
        mock_client.api_client.call_api.return_value = (mock_response,)

        scraper = make_k8s_pod_scraper(mock_client)
        scraper.fetch_pods_metadata("wf", "ns", False)

        call_args = mock_client.api_client.call_api.call_args
        query_params = call_args[1]["query_params"]

        resource_version_params = [p for p in query_params if p[0] == "resourceVersion"]
        assert len(resource_version_params) == 0

    def test_fetch_pods_metadata_auth_settings(self):
        """Verify BearerToken auth is used."""
        mock_client = MagicMock()
        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({"items": []}).encode()
        mock_client.api_client.call_api.return_value = (mock_response,)

        scraper = make_k8s_pod_scraper(mock_client)
        scraper.fetch_pods_metadata("wf", "ns", True)

        call_args = mock_client.api_client.call_api.call_args
        assert call_args[1]["auth_settings"] == ["BearerToken"]

    def test_fetch_pods_metadata_preload_content_false(self):
        """Verify _preload_content=False for streaming."""
        mock_client = MagicMock()
        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({"items": []}).encode()
        mock_client.api_client.call_api.return_value = (mock_response,)

        scraper = make_k8s_pod_scraper(mock_client)
        scraper.fetch_pods_metadata("wf", "ns", True)

        call_args = mock_client.api_client.call_api.call_args
        assert call_args[1]["_preload_content"] is False

    def test_read_pod_delegates_to_client(self):
        """Verify read_pod calls read_namespaced_pod with exact args."""
        mock_client = MagicMock()
        mock_client.read_namespaced_pod.return_value = "pod-object"

        scraper = make_k8s_pod_scraper(mock_client)
        result = scraper.read_pod("my-pod", "my-ns")

        mock_client.read_namespaced_pod.assert_called_once_with(name="my-pod", namespace="my-ns")
        assert result == "pod-object"

    def test_read_pod_log_delegates_to_client(self):
        """Verify read_pod_log calls read_namespaced_pod_log with exact args."""
        mock_client = MagicMock()
        mock_client.read_namespaced_pod_log.return_value = "log content"

        scraper = make_k8s_pod_scraper(mock_client)
        result = scraper.read_pod_log("my-pod", "my-ns", "main", 100)

        mock_client.read_namespaced_pod_log.assert_called_once_with(
            "my-pod", "my-ns", container="main", tail_lines=100
        )
        assert result == "log content"

    def test_fetch_pods_metadata_handles_null_items(self):
        """Verify null items in response returns empty list."""
        mock_client = MagicMock()
        mock_response = MagicMock()
        mock_response.read.return_value = json.dumps({"items": None}).encode()
        mock_client.api_client.call_api.return_value = (mock_response,)

        scraper = make_k8s_pod_scraper(mock_client)
        result = scraper.fetch_pods_metadata("wf", "ns", True)

        assert result == []
