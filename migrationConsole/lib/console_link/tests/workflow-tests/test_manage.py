import pytest
import json
from unittest.mock import MagicMock, patch, ANY
from textual.widgets import Tree, Footer, Static, Button
from rich.text import Text

from console_link.workflow.commands.manage import NODE_TYPE_POD, WorkflowTreeApp, ConfirmModal, NODE_TYPE_SUSPEND

import logging
logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)

@pytest.fixture
def mock_workflow_two_pods():
    return {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod", "phase": "Running", "children": []},
                "node-2": {"id": "node-2", "displayName": "step-2", "type": "Pod", "phase": "Running", "children": []}
            }
        }
    }

@pytest.fixture
def mock_workflow_pod_suspend():
    return {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod", "phase": "Running", "children": []},
                "node-2": {"id": "node-2", "displayName": "suspend-1", "type": "Suspend", "phase": "Running",
                           "children": []}
            }
        }
    }


@pytest.fixture
def mock_tree_nodes():
    return [
        {
            "id": "node-1",
            "display_name": "step-1",
            "type": NODE_TYPE_POD,
            "phase": "Running",
            "children": [],
            "started_at": "2023-01-01T00:00:01Z"
        },
        {
            "id": "node-2",
            "display_name": "suspend-1",
            "type": NODE_TYPE_SUSPEND,
            "phase": "Running",
            "children": [],
            "started_at": "2023-01-01T00:00:02Z"
        }
    ]


# --- Tests ---

import pytest
from unittest.mock import MagicMock, patch


@pytest.mark.asyncio
async def test_workflow_lifecycle_transitions(mock_tree_nodes, mock_workflow_pod_suspend):
    """
    Tests the three main states of the app:
    1. Pending (Waiting for workflow)
    2. Active (Workflow discovered/populated)
    3. Re-Pending (Workflow deleted)
    """
    k8s_mock = MagicMock()
    app = WorkflowTreeApp(k8s_mock, "test-wf", "default", "http://argo")

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")

        # --- STATE 1: PENDING ---
        # Simulate a 404/Empty result from the Hub
        app._apply_workflow_updates_unsafe({}, False)
        await pilot.pause()

        assert "Waiting for Workflow" in str(tree.root.label)
        assert len(tree.root.children) == 0
        assert app._refresh_scheduled is False

        # --- STATE 2: ACTIVE ---
        # Simulate a successful discovery
        app._apply_workflow_updates_unsafe(mock_workflow_pod_suspend, False)
        await pilot.pause()

        assert "Workflow Steps" in str(tree.root.label)
        assert len(tree.root.children) == 2
        assert app._refresh_scheduled is True
        assert app._current_run_id == mock_workflow_pod_suspend['status']['startedAt']

        # --- STATE 3: RE-PENDING ---
        # Simulate the workflow being deleted (API returns None again)
        app._apply_workflow_updates_unsafe(None, False)
        await pilot.pause()

        assert "Waiting for Workflow" in str(tree.root.label)
        assert len(tree.root.children) == 0
        # Internal data should be wiped
        assert app.workflow_data == {}

        # --- STATE 4: RE-DISCOVERY (New Run ID) ---
        new_run_data = mock_workflow_pod_suspend.copy()
        new_run_data['status']['startedAt'] = '2026-01-01T12:00:00Z'  # Different ID

        app._apply_workflow_updates_unsafe(new_run_data, False)
        await pilot.pause()

        assert len(tree.root.children) == 2
        assert app._current_run_id == '2026-01-01T12:00:00Z'


@pytest.mark.asyncio
async def test_functional_keybindings_execution(mock_workflow_pod_suspend):
    """Verify that keys actually trigger actions based on navigation state."""
    k8s_mock = MagicMock()
    app = WorkflowTreeApp(k8s_mock, "test-wf", "default", "http://argo")

    with patch("console_link.workflow.commands.manage._get_workflow_data_internal") as mock_fetch:
        mock_fetch.return_value = ({"success": True}, mock_workflow_pod_suspend)

        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")

            # Wait for the tree to appear and be ready
            while len(tree.root.children) == 0:
                await pilot.pause(0.05)
            tree.focus()

            # Navigation to node-1 (pod)
            await pilot.press("down")
            app._pod_name_cache["node-1"] = "real-pod-abc"
            app._update_dynamic_bindings()
            await pilot.pause()

            with patch.object(app, "_show_logs_in_pager") as mock_logs:
                await pilot.press("o") # output command
                await pilot.pause()
                mock_logs.assert_called_once()

            await pilot.press("down")
            await pilot.pause()

            with patch.object(app, "_execute_approval") as mock_approve:
                await pilot.press("a")
                await pilot.pause()

                # Check if the modal is now the active screen
                from console_link.workflow.commands.manage import ConfirmModal
                assert isinstance(app.screen, ConfirmModal)

                # Close modal to clean up
                await pilot.press("escape")


@pytest.mark.asyncio
async def test_manual_refresh_resolves_missing_pods(mock_workflow_two_pods):
    """Verify that a manual refresh finds pods that the initial cached fetch missed."""
    logger.error("starting test")
    k8s_mock = MagicMock()
    app = WorkflowTreeApp(k8s_mock, "test-wf", "default", "http://argo")

    def get_pod_items_response(path, method, header_params=None, query_params=None, **kwargs):
        is_cached = any(p == ('resourceVersion', '0') for p in (query_params or []))

        mock_resp = MagicMock()
        if is_cached:
            # Cached version only sees pod-1
            items = [{"metadata": {"name": "pod-1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}}]
        else:
            # Strong version sees both pod-1 and pod-2
            items = [
                {"metadata": {"name": "pod-1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}},
                {"metadata": {"name": "pod-2", "annotations": {"workflows.argoproj.io/node-id": "node-2"}}}
            ]

        mock_resp.read.return_value = json.dumps({"items": items}).encode('utf-8')
        return (mock_resp, 200, {})

    k8s_mock.api_client.call_api.side_effect = get_pod_items_response

    with patch("console_link.workflow.commands.manage._get_workflow_data_internal") as mock_fetch:
        mock_fetch.return_value = ({"success": True}, mock_workflow_two_pods)

        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")

            # Wait for population
            MAX_WAIT_SECONDS = 5
            for i in range(MAX_WAIT_SECONDS):
                await pilot.pause(MAX_WAIT_SECONDS/1000.0)
                if tree.root.children:
                    break
            else:
                pytest.fail("tree.root.children is still not set")

            tree.focus()

            # INITIAL CACHED STATE ---
            # Navigate to node-1 (Pod 1)
            await pilot.press("down")
            await pilot.pause()

            # Verify logs are available for Pod 1
            with patch.object(app, "_show_logs_in_pager") as mock_logs:
                await pilot.press("o")
                mock_logs.assert_called_once()

            # Navigate to node-2 (Pod 2 - still unknown to cache)
            await pilot.press("down")
            await pilot.pause()

            # Verify logs are NOT available (binding should not exist)
            with patch.object(app, "_show_logs_in_pager") as mock_logs:
                await pilot.press("o")
                mock_logs.assert_not_called()

                # --- PHASE 2: MANUAL REFRESH (STRONGLY-CONSISTENT reads of the k8s state) ---
                await pilot.press("r")
                # Give some time for the strong background resolve to finish.
                # Eventually, it should have added the second node's pod name,
                # at which point, the output command should have worked
                MAX_WAIT_SECONDS = 5
                for i in range(MAX_WAIT_SECONDS):
                    await pilot.pause(MAX_WAIT_SECONDS/1000.0)
                    await pilot.press("o")
                    if mock_logs.call_count:
                        mock_logs.assert_called_once()
                        break
                else:
                    pytest.fail("output command was never called for node-2")

            logger.error("Done with test")


@pytest.mark.asyncio
async def test_approve_step_interaction(mock_tree_nodes, mock_workflow_pod_suspend):
    """Test the Modal flow and Service call for approving a step."""
    k8s_mock = MagicMock()
    app = WorkflowTreeApp(mock_tree_nodes, mock_workflow_pod_suspend, k8s_mock, "test-wf", "default", "http://argo")

    with patch("your_module.WorkflowService") as mock_service_class:
        mock_service = mock_service_class.return_value
        mock_service.approve_workflow.return_value = {"success": True}

        async with app.run_test() as pilot:
            # Navigate to Suspend node (node-2)
            await pilot.press("down", "down")

            # Press 'a' to approve
            await pilot.press("a")

            # Verify Modal is present
            assert isinstance(app.screen, ConfirmModal)

            # Press 'y' inside the modal
            await pilot.press("y")

            # Verify the service was called correctly
            mock_service.approve_workflow.assert_called_once_with(
                "test-wf", "default", "http://argo", None, False, "id=node-2"
            )


@pytest.mark.asyncio
@patch("your_module.copy_to_clipboard")
async def test_copy_pod_name_action(mock_copy, mock_tree_nodes, mock_workflow_pod_suspend):
    """Verify that the copy action sends the correct pod name to the clipboard utility."""
    k8s_mock = MagicMock()
    app = WorkflowTreeApp(mock_tree_nodes, mock_workflow_pod_suspend, k8s_mock, "test-wf", "default", "http://argo")
    app._pod_name_cache["node-1"] = "pod-to-copy"

    async with app.run_test() as pilot:
        await pilot.press("down")  # Highlight pod
        await pilot.press("c")  # Trigger copy

        mock_copy.assert_called_once_with("pod-to-copy")