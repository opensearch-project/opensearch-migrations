import base64
import copy
import time
from typing import Any

import pytest
from unittest.mock import MagicMock, patch

from console_link.workflow.tui.workflow_manage_app import (
    WorkflowTreeApp,
    copy_to_clipboard, PHASE_SUCCEEDED, PHASE_RUNNING
)
from console_link.workflow.tui.confirm_modal import ConfirmModal
from console_link.workflow.tui.manage_injections import (
    WaiterInterface,
    PodScraperInterface,
    ArgoWorkflowInterface as ArgoService
)

import logging

logging.basicConfig(format='%(asctime)s [%(levelname)s] %(message)s', level=logging.INFO)
logger = logging.getLogger(__name__)


def get_clean_text_label(textual_node):
    """Extract plain text from a Rich-enabled Textual label."""
    label = textual_node.label
    return label.plain if hasattr(label, 'plain') else str(label)


@pytest.fixture
def mock_workflow_with_two_pods() -> dict[str, Any]:
    return {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod", "phase": "Failed",
                           "children": [], "startedAt": "2023-01-01T00:01:00Z",
                           "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]}},
                "node-2": {"id": "node-2", "displayName": "step-2", "type": "Pod", "phase": PHASE_RUNNING,
                           "children": [], "startedAt": "2023-01-01T00:02:00Z",
                           "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]}}
            }
        }
    }


@pytest.fixture
def mock_workflow_with_pod_and_suspend():
    return {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod",
                           "phase": PHASE_SUCCEEDED, "children": []},
                "node-2": {"id": "node-2", "displayName": "suspend-1", "type": "Suspend", "phase": PHASE_RUNNING,
                           "children": []}
            }
        }
    }


FAILING_WAITER = WaiterInterface(
    trigger=lambda: pytest.fail("Waiter trigger called unexpectedly"),
    checker=lambda: pytest.fail("Waiter checker called unexpectedly"),
    reset=MagicMock()
)


async def wait_until(pilot, predicate, timeout=5.0, interval=0.1):
    """
    Global utility to poll a condition within a Textual test.

    Args:
        pilot: The Textual Pilot instance.
        predicate: A callable that returns True when the condition is met.
        timeout: Maximum time to wait in seconds.
        interval: Time to sleep between polls.
    """
    end_time = time.time() + timeout
    while time.time() < end_time:
        if predicate():
            return True
        await pilot.pause(interval)
    return False


# --- Tests ---

@pytest.mark.asyncio
async def test_waiter_loop_and_rediscovery(mock_workflow_with_two_pods):
    """Test the discovery, deletion, and re-discovery lifecycle via WaiterInterface."""

    env_state = {"workflow_exists": False}
    workflow = mock_workflow_with_two_pods
    del workflow["status"]["nodes"]["node-1"]["inputs"]
    del workflow["status"]["nodes"]["node-2"]["inputs"]
    argo_service = ArgoService(get_workflow=lambda name, namespace:
                               ({"success": True}, workflow)
                               if env_state["workflow_exists"] else ({"success": False, "error": "not found"}, {}),
                               approve_step=MagicMock())

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    mock_waiter = WaiterInterface(
        trigger=MagicMock(),
        checker=lambda: env_state["workflow_exists"],
        reset=MagicMock()
    )

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=mock_waiter,
        refresh_interval=1.0
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        await pilot.pause()
        await pilot.wait_for_scheduled_animations()
        assert "Waiting for Workflow" in get_clean_text_label(tree.root)

        logger.info("Waiting for workflow detection trigger to fire")
        assert await wait_until(pilot, lambda: mock_waiter.trigger.call_count == 1)

        logger.info("Confirmed that no workflow was found and the UI indicated so.")
        env_state["workflow_exists"] = True

        logger.info("Waiting for the newly disclosed workflow to appear")
        assert await wait_until(pilot, lambda: "Workflow Steps" in get_clean_text_label(tree.root))

        assert len(tree.root.children) == 2
        mock_waiter.reset.assert_called()

        logger.info("The UI had two nodes in it, will remove the workflow next.")

        env_state["workflow_exists"] = False
        mock_waiter.trigger.reset_mock()

        assert await wait_until(pilot, lambda: "Waiting for Workflow" in get_clean_text_label(tree.root))
        assert mock_waiter.trigger.call_count >= 1


@pytest.mark.asyncio
async def test_functional_keybindings_execution(mock_workflow_with_pod_and_suspend):
    """Verify that injected K8sInterface and ArgoService methods are called by keys."""

    # Mock data for K8s pod discovery
    mock_pod = MagicMock()
    mock_pod.spec.init_containers = []
    mock_pod.spec.containers = [MagicMock(name="main")]

    k8s_interface = MagicMock(spec=PodScraperInterface(None, None, None))
    k8s_interface.fetch_pods_metadata.return_value = [
        {"metadata": {"name": "pod-1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}}
    ]
    k8s_interface.read_pod.return_value = mock_pod
    k8s_interface.read_pod_log.return_value = "pod logs here"

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, mock_workflow_with_pod_and_suspend)

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=k8s_interface,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)
        tree.focus()

        # Navigate to Pod (node-1)
        await pilot.press("down")
        await pilot.pause()

        # Test Log Viewing (triggers read_pod and read_pod_log)
        # We check the app's call to _get_pod_logs indirectly via the scraper mocks
        with patch.object(app, "_show_logs_in_pager") as mock_pager_method:
            await pilot.press("o")
            await pilot.pause()
            mock_pager_method.assert_called_once()

        # Test Clipboard (triggers external utility)
        with patch("console_link.workflow.tui.workflow_manage_app.copy_to_clipboard", return_value=True) as mock_cp:
            await pilot.press("c")
            await pilot.pause()
            mock_cp.assert_called_once_with("pod-1")

        # Test Approval (triggers argo_service.approve_step)
        await pilot.press("down")  # Move to Suspend (node-2)
        await pilot.pause()

        await pilot.press("a")
        await pilot.pause()
        # Check if the modal is now the active screen
        assert isinstance(app.screen, ConfirmModal)

        await pilot.press("y")
        await pilot.pause()

        argo_service.approve_step.assert_called_once()


@pytest.mark.asyncio
async def test_follow_logs_binding_responds_to_phase_changes(mock_workflow_with_two_pods):
    """Verify follow logs only works for Running pods and updates as phase changes."""

    workflow = copy.deepcopy(mock_workflow_with_two_pods)
    # Start with node-2 as Pending (not running)
    workflow["status"]["nodes"]["node-2"]["phase"] = "Pending"

    mock_pod = MagicMock()
    mock_pod.spec.init_containers = []
    mock_pod.spec.containers = [MagicMock(name="main")]

    k8s_interface = MagicMock(spec=PodScraperInterface(None, None, None))
    k8s_interface.fetch_pods_metadata.return_value = [
        {"metadata": {"name": "pod-1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}},
        {"metadata": {"name": "pod-2", "annotations": {"workflows.argoproj.io/node-id": "node-2"}}}
    ]
    k8s_interface.read_pod.return_value = mock_pod

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, copy.deepcopy(workflow))

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=k8s_interface,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)
        tree.focus()

        # Navigate to node-2 (Pending)
        await pilot.press("down")
        await pilot.press("down")
        await pilot.pause()

        # 'f' should NOT trigger follow_logs for Pending pod
        with patch.object(app._logs, "follow_logs") as mock_follow:
            await pilot.press("f")
            await pilot.pause()
            mock_follow.assert_not_called()

        # Update workflow: node-2 is now Running
        workflow["status"]["nodes"]["node-2"]["phase"] = PHASE_RUNNING
        workflow["metadata"]["resourceVersion"] = "124"
        argo_service.get_workflow.return_value = ({"success": True}, copy.deepcopy(workflow))

        # Trigger refresh without moving focus
        await pilot.press("r")
        assert await wait_until(pilot, lambda: app.current_node_data and
                                app.current_node_data.get('phase') == PHASE_RUNNING, timeout=5.0)

        # Now 'f' SHOULD trigger follow_logs
        with patch.object(app._logs, "follow_logs") as mock_follow:
            await pilot.press("f")
            await pilot.pause()
            mock_follow.assert_called_once()

        # Update workflow: node-2 is now Succeeded
        workflow["status"]["nodes"]["node-2"]["phase"] = PHASE_SUCCEEDED
        workflow["metadata"]["resourceVersion"] = "125"
        argo_service.get_workflow.return_value = ({"success": True}, copy.deepcopy(workflow))

        # Trigger refresh without moving focus
        await pilot.press("r")
        assert await wait_until(pilot, lambda: app.current_node_data and
                                app.current_node_data.get('phase') == PHASE_SUCCEEDED, timeout=5.0)

        # 'f' should NOT trigger follow_logs for Succeeded pod
        with patch.object(app._logs, "follow_logs") as mock_follow:
            await pilot.press("f")
            await pilot.pause()
            mock_follow.assert_not_called()


@pytest.mark.asyncio
async def test_manual_refresh_consistency(mock_workflow_with_two_pods):
    """Verify manual refresh forces a non-cached (strongly consistent) K8s metadata fetch."""

    fetch_log = []

    def fetch_metadata_mock(wf_name, ns, use_cache):
        fetch_log.append(use_cache)
        if use_cache:
            # Cached only sees pod-1
            return [{"metadata": {"name": "p1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}}]
        # Strong sees both
        return [
            {"metadata": {"name": "p1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}},
            {"metadata": {"name": "p2", "annotations": {"workflows.argoproj.io/node-id": "node-2"}}}
        ]

    k8s_interface = MagicMock(spec=PodScraperInterface(None, None, None))
    k8s_interface.fetch_pods_metadata.side_effect = fetch_metadata_mock

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, mock_workflow_with_two_pods)

    app = WorkflowTreeApp(
        namespace="default", name="test-wf",
        argo_service=argo_service,
        pod_scraper=k8s_interface,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0
    )

    async with app.run_test() as pilot:
        # Initial auto-refresh (cached)
        await pilot.pause()

        # Manual refresh (r)
        await pilot.press("r")
        await pilot.pause()

        # Check the log: the most recent call should have use_cache=False
        assert False in fetch_log
        assert app._pods.cache["node-2"] == "p2"


@pytest.mark.asyncio
async def test_live_check_lifecycle(mock_workflow_with_two_pods):
    """
    Test that Live Status appears when the last node is Running
    and disappears when a new Succeeded node is added.
    """
    # Setup with two pods, where the second one is still running
    workflow = mock_workflow_with_two_pods
    workflow_nodes = workflow["status"]["nodes"]
    workflow["status"]["nodes"] = {}

    argo_service = MagicMock(spec=ArgoService(None, None))

    def mocked_get_workflow(*args, **kwargs):

        logging.info("Mock: get_workflow called!")
        logging.info(f"Mock: Current node count in 'workflow' variable: "
                     f"{len(workflow['status'].get('nodes', {}))}")

        # Create the result
        result = ({"success": True}, copy.deepcopy(workflow))

        logging.debug(f"Mock: Returning: {result}")
        return result
    argo_service.get_workflow.side_effect = mocked_get_workflow

    app = WorkflowTreeApp(
        namespace="default", name="test",
        argo_service=argo_service,
        pod_scraper=MagicMock(),
        workflow_waiter=MagicMock(),
        refresh_interval=0.10
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")

        # Helper for checking node existence
        def has_live_status():
            return any("Live Status" in str(c.label) for c in tree.root.children)

        # start empty
        await pilot.pause()
        assert not has_live_status()

        logging.info("No Live Status node after the initial load")
        logging.info("Adding one running node in, so a live status node should appear")
        workflow["status"]["nodes"] = workflow_nodes
        workflow["metadata"]["resourceVersion"] = "124"
        logging.info("Updated resourceVersion to 124")
        assert await wait_until(pilot, has_live_status), "Live Status never appeared"

        # Verify status check is called and results rendered when expanded
        mock_status_result = {"success": True, "value": "Progress: 50%\nDocs: 1000/2000"}
        with patch("console_link.workflow.tui.live_status_manager.StatusCheckRunner.run_status_check",
                   return_value=mock_status_result) as mock_check, \
             patch("console_link.workflow.tui.live_status_manager.ConfigConverter.convert_with_jq",
                   return_value="{}"), \
             patch("console_link.workflow.tui.live_status_manager.Environment"):
            live_node = next(c for c in tree.root.children if "Live Status" in str(c.label))
            live_node.expand()
            assert await wait_until(pilot, lambda: mock_check.call_count > 0, timeout=30.0), \
                "StatusCheckRunner.run_status_check was never called"
            assert await wait_until(
                pilot, lambda: any("Progress" in str(c.label) for c in live_node.children), timeout=3.0
            ), f"Status results not rendered. Children: {[str(c.label) for c in live_node.children]}"

            # Verify continued polling - wait for at least one more call
            prev_count = mock_check.call_count
            assert await wait_until(pilot, lambda: mock_check.call_count > prev_count, timeout=3.0), \
                "Status check not called again while expanded"

            # Collapse and verify no more calls
            live_node.collapse()
            await pilot.pause(0.5)  # Let in-flight checks complete
            count_after_collapse = mock_check.call_count
            await pilot.pause(0.5)
            assert mock_check.call_count == count_after_collapse, \
                f"Status check called while collapsed: {mock_check.call_count} > {count_after_collapse}"

        logging.info("Found live status node, marking the last item as succeeded.")
        logging.info("Will wait for Live Status node to disappear.")
        workflow["status"]["nodes"]["node-2"]["phase"] = PHASE_SUCCEEDED
        workflow["metadata"]["resourceVersion"] = "125"
        assert await wait_until(pilot, lambda: not has_live_status()), "Live Status didn't disappear"


@pytest.mark.asyncio
async def test_workflow_restart_clears_and_rebuilds_tree():
    """Verify that when a workflow restarts (new startedAt), the tree is cleared and rebuilt."""

    workflow_v1 = {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod",
                           "phase": PHASE_RUNNING, "children": []},
                "node-2": {"id": "node-2", "displayName": "step-2", "type": "Pod",
                           "phase": PHASE_RUNNING, "children": []}
            }
        }
    }

    workflow_v2 = {
        "metadata": {"name": "test-wf", "resourceVersion": "999"},
        "status": {
            "startedAt": "2023-01-02T00:00:00Z",  # New run
            "nodes": {
                "node-new": {"id": "node-new", "displayName": "fresh-step", "type": "Pod",
                             "phase": PHASE_RUNNING, "children": []}
            }
        }
    }

    current_workflow = [workflow_v1]

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.side_effect = lambda *a, **kw: ({"success": True}, copy.deepcopy(current_workflow[0]))

    app = WorkflowTreeApp(
        namespace="default", name="test-wf",
        argo_service=argo_service,
        pod_scraper=MagicMock(),
        workflow_waiter=FAILING_WAITER,
        refresh_interval=0.1
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        assert await wait_until(pilot, lambda: len(tree.root.children) == 2, timeout=5.0), \
            f"Initial workflow nodes not loaded. Children: {len(tree.root.children)}"

        old_labels = {get_clean_text_label(c) for c in tree.root.children}
        assert any("step-1" in lbl for lbl in old_labels)

        # Simulate workflow restart
        current_workflow[0] = workflow_v2

        assert await wait_until(pilot, lambda: len(tree.root.children) == 1, timeout=5.0), \
            f"Tree not rebuilt after workflow restart. Children: {len(tree.root.children)}"

        new_labels = {get_clean_text_label(c) for c in tree.root.children}
        assert any("fresh-step" in lbl for lbl in new_labels), \
            f"New node not found. Labels: {new_labels}"


@pytest.mark.asyncio
async def test_node_phase_update_preserves_tree_structure():
    """Verify that phase changes update nodes in-place without rebuilding the tree."""

    workflow = {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod",
                           "phase": "Failed", "children": []},
                "node-2": {"id": "node-2", "displayName": "step-2", "type": "Pod",
                           "phase": PHASE_RUNNING, "children": []}
            }
        }
    }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.side_effect = lambda *a, **kw: ({"success": True}, copy.deepcopy(workflow))

    app = WorkflowTreeApp(
        namespace="default", name="test-wf",
        argo_service=argo_service,
        pod_scraper=MagicMock(),
        workflow_waiter=FAILING_WAITER,
        refresh_interval=0.1
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        assert await wait_until(pilot, lambda: len(tree.root.children) == 2, timeout=5.0)

        # Find the running node
        def get_node_2_label():
            for c in tree.root.children:
                if "step-2" in get_clean_text_label(c):
                    return get_clean_text_label(c)
            return ""

        assert await wait_until(pilot, lambda: "Running" in get_node_2_label(), timeout=5.0), \
            f"Expected Running phase in label: {get_node_2_label()}"

        # Update phase
        workflow["status"]["nodes"]["node-2"]["phase"] = PHASE_SUCCEEDED
        workflow["metadata"]["resourceVersion"] = "124"

        assert await wait_until(pilot, lambda: "Succeeded" in get_node_2_label(), timeout=5.0), \
            f"Phase not updated. Current label: {get_node_2_label()}"

        # Tree structure preserved
        assert len(tree.root.children) == 2, "Tree structure changed unexpectedly"


@pytest.mark.asyncio
async def test_live_check_lifecycle_with_group():
    """
    Test that Live Status appears as a sibling AFTER a group node with groupName=checks,
    not inside the group.
    """
    workflow = {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "group-1": {
                    "id": "group-1", "displayName": "waitingBlock", "type": "StepGroup",
                    "phase": PHASE_RUNNING, "children": ["node-1", "node-2"],
                    "inputs": {"parameters": [{"name": "groupName", "value": "checks"}]}
                },
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod", "phase": "Failed",
                           "children": [], "startedAt": "2023-01-01T00:01:00Z",
                           "boundaryID": "group-1",
                           "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]}},
                "node-2": {"id": "node-2", "displayName": "step-2", "type": "Pod", "phase": PHASE_RUNNING,
                           "children": [], "startedAt": "2023-01-01T00:02:00Z",
                           "boundaryID": "group-1",
                           "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]}}
            }
        }
    }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.side_effect = lambda *a, **kw: ({"success": True}, copy.deepcopy(workflow))

    app = WorkflowTreeApp(
        namespace="default", name="test",
        argo_service=argo_service,
        pod_scraper=MagicMock(),
        workflow_waiter=MagicMock(),
        refresh_interval=0.10
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")

        def has_live_status_at_root():
            return any("Live Status" in str(c.label) for c in tree.root.children)

        def has_live_status_in_group():
            for c in tree.root.children:
                if "waiting block" in str(c.label).lower():
                    return any("Live Status" in str(gc.label) for gc in c.children)
            return False

        await pilot.pause()
        assert await wait_until(pilot, has_live_status_at_root), \
            "Live Status should appear as sibling to group node"
        assert not has_live_status_in_group(), \
            "Live Status should NOT appear inside the checks group"

        # Verify Live Status is positioned after the group node
        root_labels = [str(c.label) for c in tree.root.children]
        group_idx = next((i for i, lbl in enumerate(root_labels) if "waiting block" in lbl.lower()), None)
        assert group_idx is not None, f"waiting block not found in root. Labels: {root_labels}"
        live_idx = next(i for i, lbl in enumerate(root_labels) if "Live Status" in lbl)
        assert live_idx > group_idx, "Live Status should appear after the group node"


@pytest.mark.parametrize("env_updates, expected_wrapper", [
    ({"SSH_TTY": "/dev/pts/0", "TERM": "xterm-256color"}, "{osc}"),
    ({"SSH_TTY": "/dev/pts/0", "TERM": "xterm-256color", "TMUX": "1"}, "\x1bPtmux;\x1b{osc}\x1b\\")
], ids=["standard_ssh", "tmux_ssh"])
def test_copy_to_clipboard_protocol_logic(env_updates, expected_wrapper, mocker):
    """Verifies raw escape sequences for terminal-based clipboard."""
    test_text = "test-pod"
    b64_val = base64.b64encode(test_text.encode()).decode()
    raw_osc = f"\x1b]52;c;{b64_val}\x07"
    expected_output = expected_wrapper.format(osc=raw_osc)

    mocker.patch.dict("os.environ", env_updates, clear=False)
    mock_stdout = mocker.patch("sys.stdout.write")

    assert copy_to_clipboard(test_text) is True
    mock_stdout.assert_called_with(expected_output)
