import base64

import pytest
import json
from unittest.mock import MagicMock, patch, ANY
from textual.widgets import Tree, Footer, Static, Button
from rich.text import Text

from console_link.workflow.commands.manage import NODE_TYPE_POD, WorkflowTreeApp, ConfirmModal, NODE_TYPE_SUSPEND, \
    copy_to_clipboard

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
    def get_pod_items_response(path, method, header_params=None, query_params=None, **kwargs):
        mock_resp = MagicMock()
        # Simulate finding pod-1 specifically for node-1
        items = [{"metadata": {"name": "node-1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}}]
        mock_resp.read.return_value = json.dumps({"items": items}).encode('utf-8')
        return (mock_resp, 200, {})

    k8s_mock.api_client.call_api.side_effect = get_pod_items_response
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
            await pilot.pause()

            with patch.object(app, "_show_logs_in_pager") as mock_logs:
                for _ in range(50):
                    await pilot.press("o")
                    await pilot.pause(0.1)
                    if mock_logs.call_count:
                        break
                else:
                    pytest.fail("output command was never called for node-1")

            with patch("console_link.workflow.commands.manage.copy_to_clipboard") as mock_copy:
                await pilot.press("c")
                mock_copy.assert_called_once_with("node-1")

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
            for _ in range(50):
                await pilot.pause(0.1)
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
                for _ in range(50):
                    await pilot.pause(0.1)
                    await pilot.press("o")
                    if mock_logs.call_count:
                        mock_logs.assert_called_once()
                        break
                else:
                    pytest.fail("output command was never called for node-2")

            logger.error("Done with test")


@pytest.mark.asyncio
async def test_approve_step_interaction(mock_workflow_pod_suspend):
    """Test the Modal flow and Service call for approving a step."""
    k8s_mock = MagicMock()
    app = WorkflowTreeApp(k8s_mock, "test-wf", "default", "http://argo")

    # 1. Patch the Service class used inside the App
    with patch("console_link.workflow.commands.manage.WorkflowService") as mock_service_class:
        # mock_service represents the instance created inside WorkflowTreeApp
        mock_service = mock_service_class.return_value
        mock_service.approve_workflow.return_value = {"success": True}

        async with app.run_test() as pilot:
            # 2. Manually load the tree using your unsafe method
            app._apply_workflow_updates_unsafe(mock_workflow_pod_suspend, False)
            await pilot.pause()  # Let the renderer catch up

            # 3. Navigate to Suspend node (node-2)
            # We use individual presses to ensure highlight events fire
            await pilot.press("down")
            await pilot.press("down")
            await pilot.pause()

            # 4. Trigger the Modal
            await pilot.press("a")
            await pilot.pause()

            # Verify Modal is present and focused
            assert isinstance(app.screen, ConfirmModal)

            # 5. Press 'y' inside the modal using the Poll pattern
            # We poll the service call count to see when 'y' is finally accepted
            for _ in range(50):
                await pilot.press("y")
                await pilot.pause(0.1)
                if mock_service.approve_workflow.call_count > 0:
                    break
            else:
                pytest.fail("Approval service was never called after pressing 'y'")

            # 6. Verify the service was called with correct parameters
            # Note: Ensure the 'node-2' selector matches your internal logic
            mock_service.approve_workflow.assert_called_once_with(
                "test-wf", "default", "http://argo", None, False, "id=node-2"
            )

            # Verify the modal closed after success
            assert not isinstance(app.screen, ConfirmModal)


@pytest.mark.parametrize("env_updates, expected_wrapper", [
    ({"SSH_TTY": "/dev/pts/0", "TERM": "xterm-256color"}, "{osc}"),
    ({"SSH_TTY": "/dev/pts/0", "TERM": "xterm-256color", "TMUX": "1"}, "\x1bPtmux;\x1b{osc}\x1b\\")
], ids=["standard_ssh", "tmux_ssh"])
def test_copy_to_clipboard_protocol_logic(env_updates, expected_wrapper, mocker):
    """Verifies the raw escape sequences generated for different terminal types."""
    test_text = "test-pod"
    b64_val = base64.b64encode(test_text.encode()).decode()
    raw_osc = f"\x1b]52;c;{b64_val}\x07"

    # Format the expected string based on the wrapper
    expected_output = expected_wrapper.format(osc=raw_osc)

    # Mock environment and stdout
    mocker.patch.dict("os.environ", env_updates, clear=False)
    mock_stdout = mocker.patch("sys.stdout.write")

    # Run the function
    result = copy_to_clipboard(test_text)

    # Assertions
    assert result is True
    mock_stdout.assert_called_with(expected_output)