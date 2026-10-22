import base64
import copy
import time
from typing import Any

import pytest
from unittest.mock import MagicMock, patch
from textual.app import App, ComposeResult
from textual.widgets import Button, Static

from console_link.workflow.tree_utils import APPROVAL_TEMPLATE_NAME
from console_link.workflow.resource_tree import (
    ResourceGroup,
    ResourceNode,
    ResourceSection,
    _build_tree_from_raw,
    format_approval_gate_line,
)
from console_link.workflow.tui.workflow_manage_app import (
    DEFERRED_ERROR_NOTIFICATION_HOLD_SECONDS,
    DISABLE_MOUSE_PIXELS_SEQUENCE,
    DISABLE_MOUSE_SEQUENCES,
    ENABLE_MOUSE_SEQUENCES,
    WorkflowTreeApp,
    copy_to_clipboard,
    PHASE_SUCCEEDED,
    PHASE_RUNNING,
    _format_workflow_submit_error,
    reset_terminal_mouse_reporting,
)
from console_link.workflow.tui.confirm_modal import ConfirmModal
from console_link.workflow.tui.container_select_modal import ContainerSelectModal
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


def binding_descriptions(app, key):
    try:
        bindings = app._bindings.get_bindings_for_key(key)
    except Exception:
        return []
    return [binding.description for binding in bindings]


def find_tree_node_by_id(root, target_id):
    stack = list(root.children)
    while stack:
        node = stack.pop()
        if node.data and isinstance(node.data, dict) and node.data.get("id") == target_id:
            return node
        stack.extend(node.children)
    return None


@pytest.fixture
def mock_workflow_with_two_pods() -> dict[str, Any]:
    return {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "phase": PHASE_RUNNING,
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
            "phase": PHASE_RUNNING,
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod",
                           "phase": PHASE_SUCCEEDED, "children": ["node-1-patch"],
                           "outputs": {"artifacts": [{"name": "metadataOutput"}]}},
                "node-1-patch": {"id": "node-1-patch", "displayName": "patchMetadataEvaluateOutput",
                                 "type": "Pod", "phase": PHASE_SUCCEEDED, "boundaryID": "node-1",
                                 "children": [],
                                 "inputs": {"parameters": [{"name": "resourceName", "value": "migration-0"}]}},
                "node-2": {"id": "node-2", "displayName": "suspend-1", "type": "Resource", "phase": PHASE_RUNNING,
                           "children": [],
                           "templateRef": {"name": "resource-management", "template": APPROVAL_TEMPLATE_NAME},
                           "inputs": {"parameters": [{"name": "resourceName", "value": "my-gate"}]}}
            }
        }
    }


FAILING_WAITER = WaiterInterface(
    trigger=lambda: pytest.fail("Waiter trigger called unexpectedly"),
    checker=lambda: pytest.fail("Waiter checker called unexpectedly"),
    reset=MagicMock()
)


def workflow_tree_app_for_unit_tests() -> WorkflowTreeApp:
    return WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=MagicMock(),
        pod_scraper=MagicMock(),
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
    )


@pytest.mark.asyncio
async def test_error_notifications_start_timeout_after_key_press():
    app = workflow_tree_app_for_unit_tests()
    app._argo_service.get_workflow.return_value = ({"success": True}, {})
    app.action_refresh_workflow = lambda: None
    async with app.run_test(notifications=True) as pilot:
        app.notify("boom", severity="error", timeout=2)
        assert await wait_until(pilot, lambda: len(app._notifications) == 1)

        held_notifications = list(app._notifications)
        assert len(held_notifications) == 1
        assert held_notifications[0].message == "boom"
        assert held_notifications[0].timeout == DEFERRED_ERROR_NOTIFICATION_HOLD_SECONDS
        assert len(app._deferred_error_notifications) == 1

        await pilot.press("x")
        assert await wait_until(pilot, lambda: len(app._deferred_error_notifications) == 0)

        released_notifications = list(app._notifications)
        assert len(released_notifications) == 1
        assert released_notifications[0].message == "boom"
        assert released_notifications[0].timeout == 2
        assert len(app._deferred_error_notifications) == 0


def test_workflow_submit_error_prefers_policy_denial_summary():
    error = RuntimeError(
        "Workflow submit script failed with exit code 1\n"
        "The request is invalid: patch: Invalid value: {\"metadata\":{\"annotations\":{\"parameters\":\"{"
        "\\\"documentBackfillDocTransformerConfig\\\":null,\\\"metadataMigrationTransformerConfig\\\":null}\"}}}\n"
        "to:\n"
        "Resource: \"migrations.opensearch.org/v1alpha1, Resource=snapshotmigrations\", "
        "GroupVersionKind: \"migrations.opensearch.org/v1alpha1, Kind=SnapshotMigration\"\n"
        "Name: \"source-target-s1-migration-0\", Namespace: \"ma\"\n"
        "for: \"/tmp/resources/011-snapshotmigration-source-target-s1-migration-0.yaml\": "
        "error when patching \"/tmp/resources/011-snapshotmigration-source-target-s1-migration-0.yaml\": "
        "snapshotmigrations.migrations.opensearch.org \"source-target-s1-migration-0\" is forbidden: "
        "ValidatingAdmissionPolicy 'migrations-snapshotmigration-policy' with binding "
        "'migrations-snapshotmigration-binding' denied request: Impossible: "
        "documentBackfillDocTransformerConfig cannot be changed. Delete and recreate.\n"
        "stdout: Validating generated Kubernetes resources..."
    )

    assert _format_workflow_submit_error(error) == (
        "Workflow submit failed: SnapshotMigration source-target-s1-migration-0 "
        "denied by migrations-snapshotmigration-policy: Impossible: "
        "documentBackfillDocTransformerConfig cannot be changed. Delete and recreate."
    )


@pytest.mark.asyncio
async def test_container_select_modal_renders_mouse_ok_enter_affordance():
    modal = ContainerSelectModal(["main", "sidecar"], "pod-a")
    result = {}

    class ContainerHarness(App):
        def compose(self) -> ComposeResult:
            yield Static("")

        async def on_mount(self) -> None:
            self.push_screen(modal, lambda value: result.update({"value": value}))

    app = ContainerHarness()
    async with app.run_test() as pilot:
        assert await wait_until(pilot, lambda: isinstance(app.screen, ContainerSelectModal))
        assert app.screen.query_one("#ok", Button).label.plain == "OK (<Enter>)"
        assert app.screen.query_one("#cancel", Button).label.plain == "Cancel (Esc)"
        assert not app.screen.query_one("#ok", Button).can_focus

        await pilot.press("down")
        await pilot.click("#ok")

    assert result == {"value": "sidecar"}


def resource_sections_for_manage_tests():
    return [
        ResourceSection(
            name="Snapshot Migration",
            groups=[
                ResourceGroup(
                    plural="datasnapshots",
                    display_name="Snapshot",
                    resources=[
                        ResourceNode(
                            name="snapshot-a",
                            plural="datasnapshots",
                            phase="Completed",
                            depends_on=[],
                            spec={},
                            status={},
                        )
                    ],
                )
            ],
        )
    ]


@pytest.mark.asyncio
def resource_sections_with_kafka_config():
    return [
        ResourceSection(
            name="Live Traffic Migration",
            groups=[
                ResourceGroup(
                    plural="kafkaclusters",
                    display_name="Buffer",
                    resources=[
                        ResourceNode(
                            name="default",
                            plural="kafkaclusters",
                            phase="Ready",
                            depends_on=[],
                            spec={"version": "3.6.0", "auth": {"type": "none"}},
                            status={},
                        )
                    ],
                )
            ],
        )
    ]


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
        assert await wait_until(pilot, lambda: mock_waiter.trigger.call_count >= 1)


@pytest.mark.asyncio
async def test_resource_view_renders_resources_without_workflow():
    """Resource view should render deployed/configured resources even when no workflow exists."""

    class FakeConfigEditService:
        pass

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    mock_waiter = WaiterInterface(
        trigger=MagicMock(),
        checker=MagicMock(return_value=False),
        reset=MagicMock(),
    )

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=mock_waiter,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_with_kafka_config()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(
                pilot,
                lambda: (
                    get_clean_text_label(tree.root) == "Migration Status"
                    and find_tree_node_by_id(tree.root, "resource:default") is not None
                ),
                timeout=5.0,
            )

            assert "Waiting for Workflow" not in get_clean_text_label(tree.root)
            assert "Values: All" in str(app.query_one("#pod-status").content)
            assert binding_descriptions(app, "v") == ["Value Mode"]

            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: "Values: Deployed" in str(app.query_one("#pod-status").content),
            )
            mock_waiter.trigger.assert_not_called()


@pytest.mark.asyncio
async def test_resource_view_left_right_expand_and_collapse_on_launch():
    """Resource view exposes operational navigation without the legacy editor binding."""

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=object(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_with_kafka_config()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(tree.root, "group:Buffer") is not None,
                timeout=5.0,
            )

            assert binding_descriptions(app, "e") == []
            assert binding_descriptions(app, "s") == ["Submit"]

            buffer_node = find_tree_node_by_id(tree.root, "group:Buffer")
            assert buffer_node.is_expanded
            tree.move_cursor(buffer_node)

            await pilot.press("left")
            assert not buffer_node.is_expanded

            await pilot.press("right")
            assert buffer_node.is_expanded


@pytest.mark.asyncio
async def test_manage_toggles_mouse_reporting_for_text_selection(mock_workflow_with_two_pods):
    """The manage UI can temporarily release terminal mouse handling for text selection."""

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, mock_workflow_with_two_pods)

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        tree.focus()
        assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

        disable_mouse = MagicMock()
        enable_mouse = MagicMock()
        enable_mouse_pixels = MagicMock()
        setattr(app._driver, "_mouse_pixels", True)

        with patch.object(app._driver, "_disable_mouse_support", disable_mouse, create=True), \
                patch.object(app._driver, "_enable_mouse_support", enable_mouse, create=True), \
                patch.object(app._driver, "_enable_mouse_pixels", enable_mouse_pixels, create=True):
            assert binding_descriptions(app, "m") == ["Mouse Off"]

            await pilot.press("m")
            assert await wait_until(
                pilot,
                lambda: app._mouse_input_enabled is False
                and binding_descriptions(app, "m") == ["Mouse On"],
            )
            disable_mouse.assert_called_once()
            enable_mouse.assert_not_called()

            await pilot.press("m")
            assert await wait_until(
                pilot,
                lambda: app._mouse_input_enabled is True
                and binding_descriptions(app, "m") == ["Mouse Off"],
            )
            enable_mouse.assert_called_once()
            enable_mouse_pixels.assert_called_once()


def test_mouse_reporting_falls_back_to_raw_escape_sequences():
    """Mouse reporting can be toggled even when a driver has no private helper methods."""

    class FakeDriver:
        def __init__(self):
            self.writes = []
            self.flushes = 0

        def write(self, value):
            self.writes.append(value)

        def flush(self):
            self.flushes += 1

    driver = FakeDriver()
    WorkflowTreeApp._write_mouse_reporting(driver, enabled=False)
    WorkflowTreeApp._write_mouse_reporting(driver, enabled=True)

    assert driver.writes == [DISABLE_MOUSE_SEQUENCES, ENABLE_MOUSE_SEQUENCES]
    assert driver.flushes == 2


def test_terminal_mouse_reporting_reset_writes_raw_disable_sequences():
    """The command shutdown guard always sends terminal mouse modes off."""

    class FakeOutput:
        def __init__(self):
            self.writes = []
            self.flushes = 0

        def write(self, value):
            self.writes.append(value)

        def flush(self):
            self.flushes += 1

    output = FakeOutput()
    reset_terminal_mouse_reporting(output)

    assert output.writes == [DISABLE_MOUSE_SEQUENCES]
    assert output.flushes == 1
    assert "\x1b[?1002l" in DISABLE_MOUSE_SEQUENCES


def test_mouse_reporting_private_disable_also_releases_pixel_mode():
    """Pixel mouse reporting is disabled explicitly when a driver helper omits that mode."""

    class FakeDriver:
        def __init__(self):
            self.disable_mouse = MagicMock()
            self.writes = []
            self.flushes = 0

        def _disable_mouse_support(self):
            self.disable_mouse()

        def write(self, value):
            self.writes.append(value)

        def flush(self):
            self.flushes += 1

    driver = FakeDriver()
    WorkflowTreeApp._write_mouse_reporting(driver, enabled=False)

    driver.disable_mouse.assert_called_once()
    assert driver.writes == [DISABLE_MOUSE_PIXELS_SEQUENCE]
    assert driver.flushes == 1


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
            await pilot.press("l")
            await pilot.pause()
            mock_pager_method.assert_called_once()

        with patch(
            "console_link.workflow.tui.workflow_manage_app.read_managed_output"
        ) as mock_read_output, patch.object(app._logs, "show_output_texts_in_pager") as mock_output_pager:
            mock_read_output.return_value.content = "archived output"
            mock_read_output.return_value.ref = {
                "s3Key": "migration-outputs/snapshotmigration/migration-0/uid/metadataEvaluate/wf.log"
            }
            await pilot.press("o")
            await pilot.pause()
            mock_read_output.assert_called_once_with(
                "default", "snapshotmigration.migration-0", "metadataEvaluate"
            )
            mock_output_pager.assert_called_once()
            assert mock_output_pager.call_args.args[1] == [
                ("snapshotmigration.migration-0 / metadataEvaluate", "archived output")
            ]
            assert mock_output_pager.call_args.kwargs == {"clean": True}

        # Test Clipboard (triggers external utility)
        with patch("console_link.workflow.tui.workflow_manage_app.copy_to_clipboard", return_value=True) as mock_cp:
            await pilot.press("c")
            await pilot.pause()
            mock_cp.assert_called_once_with("pod-1")

        # Test Approval (triggers argo_service.approve_step)
        await pilot.press("down")  # Move to patch-output child
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
async def test_tracks_last_known_workflow_phase(mock_workflow_with_two_pods):
    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, mock_workflow_with_two_pods)

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=MagicMock(spec=PodScraperInterface(None, None, None)),
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0
    )

    async with app.run_test() as pilot:
        assert await wait_until(pilot, lambda: app.last_known_phase == PHASE_RUNNING, timeout=5.0)


@pytest.mark.asyncio
async def test_enter_opens_approval_confirmation(mock_workflow_with_pod_and_suspend):
    """Enter should activate the selected approval row in the normal manage tree."""

    k8s_interface = MagicMock(spec=PodScraperInterface(None, None, None))
    k8s_interface.fetch_pods_metadata.return_value = []

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, mock_workflow_with_pod_and_suspend)

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=argo_service,
        pod_scraper=k8s_interface,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        tree.focus()
        assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

        for _ in range(3):
            await pilot.press("down")
        await pilot.pause()

        await pilot.press("enter")
        assert await wait_until(pilot, lambda: isinstance(app.screen, ConfirmModal))
        assert app.screen.query_one("#yes", Button).label.plain == "Yes (y)"
        assert app.screen.query_one("#no", Button).label.plain == "No (n)"
        await pilot.press("enter")
        await pilot.pause()
        argo_service.approve_step.assert_called_once()


@pytest.mark.asyncio
async def test_resource_view_shows_config_phases_and_submits_workflow(mock_workflow_with_two_pods):
    """Resource view shows deployed/pending/to-submit values and submits saved config."""

    class FakeConfigEditService:
        def __init__(self):
            self.submit_calls = []

        def load_resource_config_snapshots(self, workflow_name):
            return {
                "submitted": {
                    "resources": [{
                        "kind": "KafkaCluster",
                        "name": "default",
                        "parameters": {"version": "3.7.0", "auth": {"type": "none"}},
                    }]
                },
                "pending": {
                    "resources": [{
                        "kind": "KafkaCluster",
                        "name": "default",
                        "parameters": {"version": "3.8.0", "auth": {"type": "none"}},
                    }]
                },
            }

        def submit_saved_config(self, workflow_name):
            self.submit_calls.append(workflow_name)
            return {"workflow_name": workflow_name}

    service = FakeConfigEditService()
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, mock_workflow_with_two_pods),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=service,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_with_kafka_config()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: len(tree.root.children) > 0, timeout=5.0)

            resource_node = find_tree_node_by_id(tree.root, "resource:default")
            assert resource_node is not None
            assert "to submit" in get_clean_text_label(resource_node)
            labels = [get_clean_text_label(child) for child in resource_node.children]
            assert "version: deployed=3.6.0 | pending=3.7.0 | to-submit=3.8.0" in labels
            assert "Config changes:" in str(app.query_one("#pod-status").content)
            assert binding_descriptions(app, "s") == ["Submit"]

            group_node = find_tree_node_by_id(tree.root, "group:Buffer")
            assert group_node is not None
            group_node.collapse()
            resource_node.collapse()
            assert not group_node.is_expanded
            assert not resource_node.is_expanded

            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: any(
                    get_clean_text_label(child) == "version: deployed=3.6.0"
                    for child in find_tree_node_by_id(tree.root, "resource:default").children
                ),
            )
            assert not find_tree_node_by_id(tree.root, "group:Buffer").is_expanded
            assert not find_tree_node_by_id(tree.root, "resource:default").is_expanded
            await pilot.press("v")
            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: any(
                    get_clean_text_label(child) == "version: to-submit=3.8.0"
                    for child in find_tree_node_by_id(tree.root, "resource:default").children
                ),
            )

            await pilot.press("s")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ConfirmModal))
            assert app.screen.focused.id == "yes"
            await pilot.press("right")
            assert app.screen.focused.id == "no"
            await pilot.press("left")
            assert app.screen.focused.id == "yes"
            await pilot.press("enter")
            assert await wait_until(pilot, lambda: service.submit_calls == ["migration"])


@pytest.mark.asyncio
async def test_resource_view_resource_log_binding_uses_resource_log_command():
    """Resource rows should alias workflow log resource, even when workflow pods are attached."""

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = [
        {"metadata": {"name": "cap-workflow-pod", "annotations": {"workflows.argoproj.io/node-id": "pod-1"}}}
    ]

    sections = [
        ResourceSection(
            name="Live Traffic Migration",
            groups=[
                ResourceGroup(
                    plural="captureproxies",
                    display_name="Capture",
                    resources=[
                        ResourceNode(
                            name="cap",
                            plural="captureproxies",
                            phase="Error",
                            depends_on=[],
                            spec={},
                            status={},
                            workflow_progress=[
                                {
                                    "id": "pod-1",
                                    "display_name": "captureProxy",
                                    "phase": "Failed",
                                    "type": "Pod",
                                    "started_at": "2026-01-01T10:00:00Z",
                                    "children": [],
                                },
                            ],
                        )
                    ],
                )
            ],
        )
    ]

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree", return_value=sections):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: find_tree_node_by_id(tree.root, "resource:cap") is not None)
            assert await wait_until(pilot, lambda: app._pods.get_name("pod-1") == "cap-workflow-pod")

            tree.move_cursor(find_tree_node_by_id(tree.root, "resource:cap"))
            await pilot.pause()
            assert binding_descriptions(app, "l") == ["View Logs"]
            assert binding_descriptions(app, "t") == ["Tail Logs"]

            with patch.object(app._logs, "show_in_pager") as pager, \
                    patch.object(app, "action_view_resource_logs") as resource_logs, \
                    patch.object(app, "action_tail_resource_logs") as tail_logs:
                await pilot.press("l")
                await pilot.pause()
                resource_logs.assert_called_once_with()
                pager.assert_not_called()

                await pilot.press("t")
                await pilot.pause()
                tail_logs.assert_called_once_with()


def test_resource_log_command_can_tail_with_follow():
    assert WorkflowTreeApp._resource_log_command("captureproxy.cap") == (
        "workflow log resource captureproxy.cap | less -R"
    )
    assert WorkflowTreeApp._resource_log_command("captureproxy.cap", follow=True) == (
        "workflow log resource captureproxy.cap -f | less -R +F"
    )


def test_assign_workflow_progress_copies_nested_approval_to_target_resource():
    approval = {
        "id": "gate-1",
        "display_name": "CapturedTraffic: cap-topic",
        "phase": "Running",
        "type": "Pod",
        "is_approval": True,
        "denial_reason": "Impossible: kafkaBrokers cannot be changed.",
        "inputs": {"parameters": [{"name": "resourceName", "value": "capturedtraffic.cap-topic.vapretry"}]},
        "children": [],
    }
    topic = ResourceNode(
        name="cap-topic",
        plural="capturedtraffics",
        phase="Ready",
        depends_on=[],
        spec={},
        status={},
    )
    kafka = ResourceNode(
        name="default",
        plural="kafkaclusters",
        phase="Ready",
        depends_on=[],
        spec={},
        status={},
    )
    cap = ResourceNode(
        name="cap",
        plural="captureproxies",
        phase="Ready",
        depends_on=[],
        spec={},
        status={},
    )
    sections = [
        ResourceSection(
            name="Live Traffic Migration",
            groups=[
                ResourceGroup(plural="kafkaconfigs", display_name="Buffer", resources=[kafka, topic]),
                ResourceGroup(plural="captureproxies", display_name="Capture", resources=[cap]),
            ],
        )
    ]

    WorkflowTreeApp._assign_workflow_progress(sections, {"cap": [approval]})

    assert format_approval_gate_line(cap) is None
    assert format_approval_gate_line(topic) == "BLOCKED: Impossible: kafkaBrokers cannot be changed."


@pytest.mark.asyncio
async def test_resource_view_resource_row_can_approve_attached_gate():
    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})
    argo_service.approve_step.return_value = {"success": True}

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    approval = {
        "id": "gate-1",
        "display_name": "CaptureProxy: cap",
        "phase": "Running",
        "type": "Pod",
        "is_approval": True,
        "denial_reason": "Impossible: listenPort cannot be changed.",
        "inputs": {"parameters": [{"name": "resourceName", "value": "captureproxy.cap.vapretry"}]},
        "children": [],
    }
    sections = [
        ResourceSection(
            name="Live Traffic Migration",
            groups=[
                ResourceGroup(
                    plural="captureproxies",
                    display_name="Capture",
                    resources=[
                        ResourceNode(
                            name="cap",
                            plural="captureproxies",
                            phase="Error",
                            depends_on=[],
                            spec={},
                            status={},
                            workflow_progress=[approval],
                        )
                    ],
                )
            ],
        )
    ]

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree", return_value=sections):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(tree.root, "resource:cap") is not None,
            )

            resource_node = find_tree_node_by_id(tree.root, "resource:cap")
            tree.move_cursor(resource_node)
            await pilot.pause()
            assert "BLOCKED: Impossible: listenPort cannot be changed." in get_clean_text_label(resource_node)
            assert any(
                "BLOCKED: Impossible: listenPort cannot be changed." in get_clean_text_label(child)
                for child in resource_node.children
            )
            assert binding_descriptions(app, "a") == ["Approve"]

            await pilot.press("a")
            assert await wait_until(pilot, lambda: isinstance(app.screen, ConfirmModal))
            await pilot.press("y")
            await pilot.pause()

            argo_service.approve_step.assert_called_once_with("default", "migration", approval)


@pytest.mark.asyncio
async def test_resource_view_delete_maps_to_workflow_reset_dry_run_and_exact_commit():
    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    sections = [
        ResourceSection(
            name="Live Traffic Migration",
            groups=[
                ResourceGroup(
                    plural="captureproxies",
                    display_name="Capture",
                    resources=[
                        ResourceNode(
                            name="cap",
                            plural="captureproxies",
                            phase="Error",
                            depends_on=[],
                            spec={},
                            status={},
                        )
                    ],
                )
            ],
        )
    ]

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree", return_value=sections):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: find_tree_node_by_id(tree.root, "resource:cap") is not None)

            tree.move_cursor(find_tree_node_by_id(tree.root, "resource:cap"))
            await pilot.pause()

            assert binding_descriptions(app, "delete") == ["Reset"]
            target = app._nearest_resource_reset_target()
            assert target == {
                "resource_path": "captureproxy.cap",
                "resource_plural": "captureproxies",
                "resource_name": "cap",
            }
            assert app._resource_reset_dry_run_command_args(target) == [
                "workflow",
                "reset",
                "--namespace",
                "default",
                "--cascade",
                "--include-proxies",
                "--dry-run",
                "--output",
                "json",
                "captureproxy.cap",
            ]
            plan = {
                "request": target,
                "targets": [
                    {
                        "plural": "captureproxies",
                        "path": "captureproxy.cap",
                        "phase": "Error",
                    },
                    {
                        "plural": "capturedtraffics",
                        "path": "capturedtraffic.cap-topic",
                        "phase": "Ready",
                    },
                ],
                "messages": [],
                "warnings": [],
            }
            assert app._resource_reset_commit_command_args(plan) == [
                "workflow",
                "reset",
                "--namespace",
                "default",
                "--exact",
                "--include-proxies",
                "captureproxy.cap",
                "capturedtraffic.cap-topic",
            ]


def test_resource_reset_target_ignores_virtual_config_rows():
    target = WorkflowTreeApp._resource_reset_target_from_data({
        "resource_path": "kafkaconfigs.default",
        "resource_plural": "kafkaconfigs",
        "resource_name": "default",
    })

    assert target is None


def test_reset_success_does_not_auto_open_output_pager():
    app = object.__new__(WorkflowTreeApp)
    app._resetting_resource_path = "captureproxy.cap"
    app._logs = MagicMock()
    app.notify = MagicMock()
    app.update_pod_status = MagicMock()
    app.action_manual_refresh = MagicMock()

    app._handle_reset_resource_succeeded(
        {"request": {"resource_path": "captureproxy.cap"}},
        "  Deleted captureproxy.cap\n",
    )

    app.notify.assert_called_once_with("Reset complete: captureproxy.cap")
    app._logs.show_output_texts_in_pager.assert_not_called()
    app.action_manual_refresh.assert_called_once()


@pytest.mark.asyncio
async def test_resource_view_collapses_submitted_projection_after_workflow_succeeds():
    """Submitted projections are rollout state only while the workflow is active."""

    class FakeConfigEditService:
        def load_resource_config_snapshots(self, workflow_name):
            return {
                "submitted": {
                    "resources": [{
                        "kind": "KafkaCluster",
                        "name": "default",
                        "parameters": {"version": "3.7.0", "auth": {"type": "none"}},
                    }]
                },
                "pending": {
                    "resources": [{
                        "kind": "KafkaCluster",
                        "name": "default",
                        "parameters": {"version": "3.8.0", "auth": {"type": "none"}},
                    }]
                },
            }

    workflow = {
        "metadata": {"name": "migration", "resourceVersion": "123"},
        "status": {
            "phase": PHASE_SUCCEEDED,
            "nodes": {
                "node-1": {"id": "node-1", "displayName": "step-1", "type": "Pod", "phase": PHASE_SUCCEEDED}
            },
        },
    }
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, workflow),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=resource_sections_with_kafka_config()):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: find_tree_node_by_id(tree.root, "resource:default") is not None)

            resource_node = find_tree_node_by_id(tree.root, "resource:default")
            labels = [get_clean_text_label(child) for child in resource_node.children]
            assert "version: deployed=3.6.0 | pending=3.6.0 | to-submit=3.8.0" in labels

            await pilot.press("v")
            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: any(
                    get_clean_text_label(child) == "version: pending=3.6.0"
                    for child in find_tree_node_by_id(tree.root, "resource:default").children
                ),
            )


@pytest.mark.asyncio
async def test_resource_view_uses_submitted_console_as_deployed_virtual_config_after_success():
    """Terminal workflows use the latest submitted console config as the virtual deployed baseline."""

    class FakeConfigEditService:
        def load_resource_config_snapshots(self, workflow_name):
            return {
                "submitted_console": {
                    "sources": [{
                        "refName": "source",
                        "clientConfig": {"endpoint": "https://old.example.com"},
                    }],
                },
                "pending_console": {
                    "sources": [{
                        "refName": "source",
                        "clientConfig": {"endpoint": "https://new.example.com"},
                    }],
                },
            }

    workflow = {
        "metadata": {"name": "migration", "resourceVersion": "123"},
        "status": {"phase": PHASE_SUCCEEDED, "nodes": {}},
    }
    argo_service = ArgoService(
        get_workflow=lambda name, namespace: ({"success": True}, workflow),
        approve_step=MagicMock(),
    )
    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=_build_tree_from_raw({})):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(pilot, lambda: find_tree_node_by_id(tree.root, "resource:source") is not None)

            source_node = find_tree_node_by_id(tree.root, "resource:source")
            assert "Deployed Config" in get_clean_text_label(source_node)
            labels = [get_clean_text_label(child) for child in source_node.children]
            assert (
                "endpoint: deployed=https://old.example.com | pending=https://old.example.com | "
                "to-submit=https://new.example.com"
            ) in labels


@pytest.mark.asyncio
async def test_resource_view_shows_pending_only_config_resources_without_workflow():
    """Saved config resources that do not exist in K8s should still render in resource view."""

    class FakeConfigEditService:
        def load_resource_config_snapshots(self, workflow_name):
            return {
                "pending": {
                    "resources": [{
                        "kind": "TrafficReplay",
                        "name": "replay-new",
                        "parameters": {"podReplicas": 2, "speedupFactor": 1.5, "removeAuthHeader": False},
                    }]
                },
            }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=_build_tree_from_raw({})):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(tree.root, "resource:replay-new") is not None,
                timeout=5.0,
            )

            replay_node = find_tree_node_by_id(tree.root, "resource:replay-new")
            assert "Pending Config" in get_clean_text_label(replay_node)
            assert replay_node.is_expanded
            labels = [get_clean_text_label(child) for child in replay_node.children]
            assert "podReplicas: deployed=<absent> | pending=<absent> | to-submit=2" in labels

            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: (
                    "Values: Deployed" in str(app.query_one("#pod-status").content)
                    and find_tree_node_by_id(tree.root, "resource:replay-new") is None
                ),
            )

            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: (
                    "Values: Pending" in str(app.query_one("#pod-status").content)
                    and find_tree_node_by_id(tree.root, "resource:replay-new") is None
                ),
            )

            await pilot.press("v")
            assert await wait_until(
                pilot,
                lambda: (
                    "Values: To Submit" in str(app.query_one("#pod-status").content)
                    and find_tree_node_by_id(tree.root, "resource:replay-new") is not None
                ),
            )


@pytest.mark.asyncio
async def test_resource_view_shows_loose_projection_diagnostics_without_workflow():
    """Incomplete saved config resources render in resource view with validation diagnostics."""

    class FakeConfigEditService:
        def load_resource_config_snapshots(self, workflow_name):
            return {
                "pending": {
                    "resources": [{
                        "kind": "CaptureProxy",
                        "name": "capture-new",
                        "parameters": {"dependsOn": ["capture-new-topic"]},
                        "diagnostics": [{
                            "severity": "required",
                            "path": ["traffic", "proxies", "capture-new", "proxyConfig"],
                            "message": "Invalid input: expected object, received undefined",
                        }],
                    }]
                },
            }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": False, "error": "not found"}, {})

    pod_scraper = MagicMock(spec=PodScraperInterface(None, None, None))
    pod_scraper.fetch_pods_metadata.return_value = []

    app = WorkflowTreeApp(
        namespace="default",
        name="migration",
        argo_service=argo_service,
        pod_scraper=pod_scraper,
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
        resource_view=True,
        config_edit_service=FakeConfigEditService(),
    )

    with patch("console_link.workflow.resource_tree.build_resource_tree",
               return_value=_build_tree_from_raw({})):
        async with app.run_test() as pilot:
            tree = app.query_one("#workflow-tree")
            tree.focus()
            assert await wait_until(
                pilot,
                lambda: find_tree_node_by_id(tree.root, "resource:capture-new") is not None,
                timeout=5.0,
            )

            capture_node = find_tree_node_by_id(tree.root, "resource:capture-new")
            assert "(required)" in get_clean_text_label(capture_node)
            labels = [get_clean_text_label(child) for child in capture_node.children]
            assert (
                "required: traffic.proxies.capture-new.proxyConfig: "
                "Invalid input: expected object, received undefined"
            ) in labels


@pytest.mark.asyncio
async def test_show_output_falls_back_to_artifact_s3_key(mock_workflow_with_pod_and_suspend):
    workflow = copy.deepcopy(mock_workflow_with_pod_and_suspend)

    k8s_interface = MagicMock(spec=PodScraperInterface(None, None, None))
    k8s_interface.fetch_pods_metadata.return_value = [
        {"metadata": {"name": "pod-1", "annotations": {"workflows.argoproj.io/node-id": "node-1"}}}
    ]

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, workflow)

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
        await pilot.press("down")
        await pilot.pause()

        with patch("console_link.workflow.tui.workflow_manage_app.read_managed_output") as mock_read_output, \
                patch.object(app._logs, "show_output_texts_in_pager") as mock_output_pager:
            mock_read_output.return_value.content = "archived s3 output"
            mock_read_output.return_value.ref = {
                "s3Key": "migration-outputs/snapshotmigration/migration-0/uid/metadataEvaluate/wf.log"
            }
            await pilot.press("o")
            await pilot.pause()

            mock_read_output.assert_called_once_with(
                "default", "snapshotmigration.migration-0", "metadataEvaluate"
            )
            mock_output_pager.assert_called_once()
            assert mock_output_pager.call_args.args[1] == [
                ("snapshotmigration.migration-0 / metadataEvaluate", "archived s3 output")
            ]
            assert mock_output_pager.call_args.kwargs == {"clean": True}


def test_managed_output_ref_map_indexes_all_patch_steps(mock_workflow_with_pod_and_suspend):
    workflow = copy.deepcopy(mock_workflow_with_pod_and_suspend)
    workflow["status"]["nodes"]["node-3-patch"] = {
        "id": "node-3-patch",
        "displayName": "patchMetadataMigrateOutput",
        "type": "Pod",
        "phase": PHASE_SUCCEEDED,
        "children": [],
        "inputs": {"parameters": [{"name": "resourceName", "value": "migration-1"}]},
    }

    app = WorkflowTreeApp(
        namespace="default",
        name="test-wf",
        argo_service=MagicMock(spec=ArgoService(None, None)),
        pod_scraper=MagicMock(spec=PodScraperInterface(None, None, None)),
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0,
    )
    app._tree_state._workflow_data = workflow

    assert app._find_output_refs_in_workflow_data("migration-0") == [
        ("snapshotmigration.migration-0", "metadataEvaluate")
    ]
    assert app._find_output_refs_in_workflow_data("migration-1") == [
        ("snapshotmigration.migration-1", "metadataMigrate")
    ]


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

            # Collapse and verify no more calls.
            # Allow any in-flight check to finish before capturing the baseline.
            live_node.collapse()
            await pilot.pause(0.5)
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
                    "inputs": {"parameters": [{"name": "groupName_view", "value": "checks"}]}
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


@pytest.mark.asyncio
async def test_tree_renders_with_artifact_outputs():
    """Verify workflow manage renders without crashing when nodes have artifact-based statusOutput.

    Regression test for TypeError: can only concatenate str (not "ArtifactRef") to str.
    When Argo stores statusOutput as an S3 artifact instead of an inline parameter,
    get_step_status_output returns an ArtifactRef object. The TUI cannot resolve artifacts
    (that requires an API call), so the status output is omitted from the label.

    Contrast with parameter-based statusOutput (node-3), which IS shown inline.
    """
    workflow = {
        "metadata": {"name": "test-wf", "resourceVersion": "123"},
        "status": {
            "startedAt": "2023-01-01T00:00:00Z",
            "nodes": {
                "node-1": {
                    "id": "node-1", "displayName": "Check Snapshot", "type": "Pod",
                    "phase": PHASE_SUCCEEDED, "children": [],
                    "startedAt": "2023-01-01T00:01:00Z",
                    "finishedAt": "2023-01-01T00:02:00Z",
                    "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]},
                    "outputs": {
                        "parameters": [
                            {"name": "overriddenPhase", "value": "", "valueFrom": {"path": "/tmp/phase-output.txt"}}
                        ],
                        "artifacts": [
                            {"name": "statusOutput", "path": "/tmp/status-output.txt",
                             "s3": {"key": "argo-artifacts/test-wf/node-1/statusOutput"},
                             "archive": {"none": {}}}
                        ]
                    }
                },
                "node-2": {
                    "id": "node-2", "displayName": "Migrate Data", "type": "Pod",
                    "phase": PHASE_RUNNING, "children": [],
                    "startedAt": "2023-01-01T00:02:00Z",
                    "inputs": {"parameters": []},
                    "outputs": {"parameters": [], "artifacts": []}
                },
                "node-3": {
                    "id": "node-3", "displayName": "Create Snapshot", "type": "Pod",
                    "phase": PHASE_SUCCEEDED, "children": [],
                    "startedAt": "2023-01-01T00:00:30Z",
                    "finishedAt": "2023-01-01T00:01:00Z",
                    "inputs": {"parameters": [{"name": "configContents", "value": "cfg"}]},
                    "outputs": {
                        "parameters": [
                            {"name": "statusOutput", "value": "snapshot completed successfully"}
                        ],
                        "artifacts": []
                    }
                }
            }
        }
    }

    argo_service = MagicMock(spec=ArgoService(None, None))
    argo_service.get_workflow.return_value = ({"success": True}, workflow)

    app = WorkflowTreeApp(
        namespace="default", name="test-wf",
        argo_service=argo_service,
        pod_scraper=MagicMock(),
        workflow_waiter=FAILING_WAITER,
        refresh_interval=100.0
    )

    async with app.run_test() as pilot:
        tree = app.query_one("#workflow-tree")
        assert await wait_until(pilot, lambda: len(tree.root.children) == 3, timeout=5.0), \
            f"Expected 3 nodes, got {len(tree.root.children)}"

        labels = {get_clean_text_label(c) for c in tree.root.children}

        # node-1: artifact-based statusOutput — NOT shown in TUI label (can't resolve without API call)
        check_label = next(lbl for lbl in labels if "Check Snapshot" in lbl)
        assert "ArtifactRef" not in check_label, f"Raw ArtifactRef leaked into label: {check_label}"
        assert "argo-artifacts" not in check_label, f"S3 key leaked into label: {check_label}"

        # node-3: parameter-based statusOutput — IS shown inline in TUI label
        create_label = next(lbl for lbl in labels if "Create Snapshot" in lbl)
        assert "snapshot completed successfully" in create_label, \
            f"Parameter statusOutput should appear in label. Got: {create_label}"

        # node-2: no statusOutput at all
        migrate_label = next(lbl for lbl in labels if "Migrate Data" in lbl)
        assert "Running" in migrate_label, f"Expected Running phase. Got: {migrate_label}"
