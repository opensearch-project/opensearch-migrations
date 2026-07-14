"""
Interactive manage Text-UI for workflow CLI.
interactive tree navigation for status viewing and approval.
"""
import base64
import copy
import json
import logging
import os
import platform
import re
import shlex
import subprocess
import sys
import time
from typing import Any, Dict, Optional

import yaml
from textual.app import App, ComposeResult, Notify
from textual.containers import Container
from textual.notifications import Notification
from textual.screen import ModalScreen
from textual.widgets import Footer, Header, Static, Tree

from .choice_select_modal import ChoiceSelectModal
from .config_edit_exit_modal import ConfigEditExitModal
from .confirm_modal import ConfirmModal
from .container_select_modal import ContainerSelectModal
from .config_edit_tree import (
    EDIT_NODE_TYPE,
    EDIT_MODE_ALL,
    EDIT_MODE_CURRENT_WORKFLOW,
    EDIT_MODE_DEPLOYED,
    EDIT_MODE_LABELS,
    EDIT_MODES,
    EDIT_MODE_PENDING_SUBMIT,
    FIELD_VISIBILITY_ESSENTIAL,
    FIELD_VISIBILITY_LABELS,
    FIELD_VISIBILITY_MODES,
    render_edit_state,
    selected_edit_node,
    update_help_panel,
)
from .external_resource_modal import (
    ExternalResourceFormModal,
    ExternalResourcePickerModal,
    values_for_form,
)
from .live_status_manager import LiveStatusManager
from .log_manager import LogManager
from .manage_injections import ArgoWorkflowInterface, PodScraperInterface, WaiterInterface
from .modal_results import CLEAR_VALUE
from .pod_name_manager import PodNameManager
from .structured_value_modal import StructuredValueModal
from .text_input_modal import TextInputModal
from .tree_state_manager import TreeStateManager
from .resource_tree_state_manager import RESOURCE_ID_PREFIX
from ..commands.artifact_store import ArtifactStoreError
from ..commands.crd_utils import RESETTABLE_PLURALS, parse_resource_path, resource_display_name
from ..resource_tree import (
    CONFIG_MODE_LABELS,
    approval_target_ref,
    apply_config_overlays,
    resource_config_change_summary,
)
from ..manage_tree_schema import EDIT_ID_BY_TREE_ID, EDIT_RESOURCE_COLLECTION_PATHS
from ..manage_tree_status import STATUS_PRIORITY, same_value_state, strip_status_badge
from ..commands.show import read_managed_output
from ..tree_utils import get_node_phase, is_approval_node

logger = logging.getLogger(__name__)

TREE_ROOT_ANCHOR = "workflow-tree"
DEFERRED_ERROR_NOTIFICATION_HOLD_SECONDS = 24 * 60 * 60

# --- Constants ---
NODE_TYPE_POD = "Pod"
PHASE_RUNNING = "Running"
PHASE_SUCCEEDED = "Succeeded"
ACTIVE_WORKFLOW_PHASES = {"Pending", PHASE_RUNNING}
TERMINAL_WORKFLOW_PHASES = {PHASE_SUCCEEDED, "Failed", "Error"}
LOADING_ROOT_LABEL = "[yellow]⏳ Waiting for Workflow to be created...[/]"
DESC_SHOW_OUTPUT = "Show Output"
PATCH_OUTPUT_STEPS = {
    "patchMetadataEvaluateOutput": ("snapshotmigrations", "metadataEvaluate"),
    "patchMetadataMigrateOutput": ("snapshotmigrations", "metadataMigrate"),
}
ENABLE_MOUSE_SEQUENCES = "\x1b[?1000h\x1b[?1003h\x1b[?1015h\x1b[?1006h"
DISABLE_MOUSE_SEQUENCES = "\x1b[?1000l\x1b[?1002l\x1b[?1003l\x1b[?1015l\x1b[?1006l\x1b[?1016l"
DISABLE_MOUSE_PIXELS_SEQUENCE = "\x1b[?1016l"


def _single_line(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def _format_workflow_submit_error(error: Exception) -> str:
    """Return a concise submit error suitable for a TUI toast."""
    text = str(error)
    denial = re.search(r"denied request:\s*(?P<reason>.+?)(?:\nstdout:|\Z)", text, flags=re.DOTALL)
    if denial:
        reason = _single_line(denial.group("reason"))
        kind_match = re.search(r"Kind=([A-Za-z0-9]+)", text)
        name_match = re.search(r'Name:\s+"([^"]+)"', text)
        policy_match = re.search(r"ValidatingAdmissionPolicy\s+'([^']+)'", text)
        target = ""
        if kind_match and name_match:
            target = f"{kind_match.group(1)} {name_match.group(1)} "
        policy = f" by {policy_match.group(1)}" if policy_match else ""
        return f"Workflow submit failed: {target}denied{policy}: {reason}"

    invalid = re.search(r'((?:The )?[A-Za-z][A-Za-z0-9]* "[^"]+" is invalid: .+?)(?:\n|stdout:|\Z)', text)
    if invalid:
        return f"Workflow submit failed: {_single_line(invalid.group(1))}"

    return f"Workflow submit failed: {text}"


def reset_terminal_mouse_reporting(output=None) -> None:
    """Best-effort terminal guard for leaked mouse reporting modes."""
    target = output or sys.stdout
    write = getattr(target, "write", None)
    if not callable(write):
        return
    try:
        write(DISABLE_MOUSE_SEQUENCES)
        flush = getattr(target, "flush", None)
        if callable(flush):
            flush()
    except Exception:
        logger.debug("Failed to reset terminal mouse reporting", exc_info=True)


class WorkflowTreeApp(App):
    CSS = """
    Tree { scrollbar-gutter: stable; }
    #edit-help {
        height: 4;
        padding: 0 1;
        border-top: solid $primary;
        display: none;
    }
    #pod-status { height: 1; padding: 0 1; }
    Toast {
        width: 90;
        max-width: 75%;
    }
    """

    def __init__(self,
                 namespace: str,
                 name: str,
                 argo_service: ArgoWorkflowInterface,
                 pod_scraper: PodScraperInterface,
                 workflow_waiter: WaiterInterface,
                 refresh_interval: float,
                 resource_view: bool = False,
                 config_edit_service=None):
        super().__init__()
        self.title = f"[{namespace}] {name}"  # override from base

        # Exposed Metadata
        self.current_run_id: Optional[str] = None
        self.is_exiting = False

        # Injected Services
        self._argo_service = argo_service
        self._workflow_waiter = workflow_waiter
        self._pod_scraper = pod_scraper
        self._refresh_interval = refresh_interval
        self._resource_view = resource_view
        self._config_edit_service = config_edit_service
        self._edit_mode = False
        self._edit_loading = False
        self._edit_state: Optional[Dict] = None
        self._edit_draft_yaml: Optional[str] = None
        self._edit_dirty = False
        self._edit_value_mode = EDIT_MODE_ALL
        self._edit_status_mode = EDIT_MODE_ALL
        self._edit_field_visibility = FIELD_VISIBILITY_ESSENTIAL
        self._after_config_edit_save: Optional[str] = None
        self._resource_value_mode = EDIT_MODE_ALL
        self._resource_change_summary = {'pending': 0, 'to_submit': 0, 'resources': 0}
        self._last_resource_sections = None
        self._last_resource_workflow_data: Dict = {}
        self._last_resource_config_snapshots: Optional[Dict[str, Any]] = None
        self._resource_collapsed_ids_before_edit: Optional[set[str]] = None
        self._restore_resource_collapsed_ids_on_next_render: Optional[set[str]] = None
        self._expand_changed_resources_on_next_render = False
        self._submitting_workflow = False
        self._resetting_resource_path: Optional[str] = None
        self._edit_validation_generation = 0
        self._edit_validation_timer: Optional[Any] = None
        self._edit_validation_delay = 0.4
        self._mouse_input_enabled = True
        self._mouse_pixels_was_enabled = False
        self._last_pod_status_text: Optional[str] = None
        self._last_binding_signature: Optional[tuple] = None
        self._managed_output_ref_cache: Dict[str, list[tuple[str, str]]] = {}
        self._workflow_output_refs_by_resource: Optional[Dict[str, list[tuple[str, str]]]] = None
        self._deferred_error_notifications: Dict[str, tuple[Notification, float]] = {}

        # State Containers (Managers)
        self._pods = PodNameManager(self, pod_scraper, name, namespace)
        if resource_view:
            from .resource_tree_state_manager import ResourceTreeStateManager
            self._tree_state = ResourceTreeStateManager(namespace=namespace, on_new_pod=self._pods.observe_node)
        else:
            self._tree_state = TreeStateManager(namespace=namespace, on_new_pod=self._pods.observe_node)
        self._logs = LogManager(pod_scraper, namespace)
        self._live = LiveStatusManager(refresh_interval)

        # Internal Application Metadata
        self._workflow_name = name
        self._namespace = namespace

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(Tree(LOADING_ROOT_LABEL, id="workflow-tree"), id="tree-container")
        yield Static("", id="edit-help")
        yield Static("", id="pod-status")
        yield Footer()

    def on_mount(self) -> None:
        self._tree_state.set_tree_widget(self.tree_root_widget)
        if self._resource_view and hasattr(self._tree_state, "set_config_value_mode"):
            self._tree_state.set_config_value_mode(self._resource_value_mode)
        self.action_refresh_workflow()

    def notify(
        self,
        message: str,
        *,
        title: str = "",
        severity: str = "information",
        timeout: Optional[float] = None,
        markup: bool = True,
    ) -> None:
        if severity != "error":
            super().notify(message, title=title, severity=severity, timeout=timeout, markup=markup)
            return

        intended_timeout = self.NOTIFICATION_TIMEOUT if timeout is None else timeout
        notification = Notification(
            message,
            title,
            severity,
            DEFERRED_ERROR_NOTIFICATION_HOLD_SECONDS,
            markup=markup,
        )
        self._deferred_error_notifications[notification.identity] = (
            notification,
            intended_timeout,
        )
        self.post_message(Notify(notification))

    def _start_deferred_error_notification_timers(self) -> None:
        pending = list(self._deferred_error_notifications.values())
        if not pending:
            return
        self._deferred_error_notifications.clear()
        refresh_needed = False
        for notification, timeout in pending:
            if notification not in self._notifications:
                continue
            self._unnotify(notification, refresh=False)
            refresh_needed = True
            super().notify(
                notification.message,
                title=notification.title,
                severity=notification.severity,
                timeout=timeout,
                markup=notification.markup,
            )
        if refresh_needed:
            self._refresh_notifications()

    @property
    def tree_root_widget(self) -> Tree:
        return self.query_one(f"#{TREE_ROOT_ANCHOR}", Tree)

    def on_unmount(self) -> None:
        self.is_exiting = True
        try:
            self.capture_mouse(None)
        except Exception:
            pass
        reset_terminal_mouse_reporting(getattr(self, "_driver", None))
        self._mouse_input_enabled = False
        self._mouse_pixels_was_enabled = False
        try:
            self._workflow_waiter.reset()
        except Exception:
            pass

    # --- Core Orchestration ---

    def _fetch_workflow_data(self):
        """Wrapper that converts exceptions to error responses."""
        try:
            return self._argo_service.get_workflow(self._workflow_name, self._namespace)
        except Exception as e:
            return {"success": False, "error": str(e)}, {}

    def action_refresh_workflow(self) -> None:
        if self._edit_mode:
            return
        self.run_worker(self._refresh_workflow_worker, thread=True, name="refresh_wf")

    def _refresh_workflow_worker(self) -> None:
        """Worker: Fetch data and route back to main thread."""
        res, data = self._fetch_workflow_data()
        workflow_data = data if res.get('success') else {}

        if self._resource_view:
            sections = self._build_resource_sections(workflow_data)
            self.call_from_thread(self._handle_resource_data, sections, workflow_data)
        else:
            self.call_from_thread(self._handle_workflow_data, workflow_data)

    def _build_resource_sections(self, workflow_data: Dict):
        """Build resource sections with workflow steps merged (runs in worker thread)."""
        from ..resource_tree import (
            build_resource_tree, extract_workflow_steps_by_resource, mark_not_configured_groups,
        )
        from ..tree_utils import build_nested_workflow_tree, filter_tree_nodes
        sections = build_resource_tree(self._namespace)
        if workflow_data and workflow_data.get('status', {}).get('nodes'):
            tree_nodes = build_nested_workflow_tree(workflow_data)
            filtered_tree = filter_tree_nodes(tree_nodes)
            steps = extract_workflow_steps_by_resource(filtered_tree)
            self._assign_workflow_progress(sections, steps)
            mark_not_configured_groups(sections, filtered_tree)
        try:
            service = self._config_edit_service_or_default()
            if hasattr(service, "load_resource_config_snapshots"):
                snapshots = service.load_resource_config_snapshots(self._workflow_name)
                self._last_resource_config_snapshots = snapshots
                submitted_active = self._workflow_has_active_rollout(workflow_data)
                apply_config_overlays(
                    sections,
                    submitted_resolved_config=snapshots.get("submitted") if submitted_active else None,
                    pending_resolved_config=snapshots.get("pending"),
                    deployed_console_config=snapshots.get("submitted_console") if not submitted_active else None,
                    submitted_console_config=snapshots.get("submitted_console") if submitted_active else None,
                    pending_console_config=snapshots.get("pending_console"),
                )
        except Exception:
            logger.exception("Failed to load resource config change overlays")
        return sections

    @staticmethod
    def _workflow_has_active_rollout(workflow_data: Dict) -> bool:
        """Return whether the submitted config still represents an active rollout."""
        status = (workflow_data or {}).get("status") or {}
        phase = status.get("phase")
        if phase in TERMINAL_WORKFLOW_PHASES:
            return False
        if phase in ACTIVE_WORKFLOW_PHASES:
            return True

        nodes = status.get("nodes") or {}
        return any(
            (node or {}).get("phase") in ACTIVE_WORKFLOW_PHASES
            for node in nodes.values()
        )

    @staticmethod
    def _assign_workflow_progress(sections, steps):
        """Attach workflow step subtrees to matching resource nodes."""
        resources = [
            resource
            for section in sections
            for group in section.groups
            for resource in WorkflowTreeApp._iter_resource_nodes(group.resources)
        ]
        by_ref = {(resource.plural, resource.name): resource for resource in resources}
        for resource in resources:
            if resource.name in steps:
                resource.workflow_progress = steps[resource.name]
        for resource in resources:
            for approval in WorkflowTreeApp._iter_running_approval_nodes(resource.workflow_progress or []):
                target = approval_target_ref(approval)
                if not target or target == (resource.plural, resource.name):
                    continue
                target_resource = by_ref.get(target)
                if not target_resource:
                    continue
                existing = {step.get('id') for step in target_resource.workflow_progress or []}
                if approval.get('id') not in existing:
                    target_resource.workflow_progress = [
                        *(target_resource.workflow_progress or []),
                        approval,
                    ]

    @staticmethod
    def _iter_resource_nodes(resources):
        for resource in resources:
            yield resource
            yield from WorkflowTreeApp._iter_resource_nodes(resource.children)

    @staticmethod
    def _iter_running_approval_nodes(steps):
        for step in steps:
            if is_approval_node(step) and get_node_phase(step) == PHASE_RUNNING:
                yield step
            yield from WorkflowTreeApp._iter_running_approval_nodes(step.get('children', []))

    def _handle_resource_data(self, sections, workflow_data: Dict, force_reload: bool = False) -> None:
        """Handle pre-built resource sections on the main thread."""
        if self._edit_mode or self._edit_loading:
            logger.info("Ignoring resource tree refresh while config edit is active or loading")
            return
        self.title = "Migration Status"
        if not sections:
            self._tree_state.reset(LOADING_ROOT_LABEL)
            self.run_worker(self._wait_for_workflow_worker, thread=True, name="_wait_for_workflow_worker")
            return

        new_run_id = workflow_data.get('status', {}).get('startedAt') if workflow_data else None
        had_resource_tree = self._last_resource_sections is not None
        is_restart = self.current_run_id != new_run_id
        self._last_resource_sections = sections
        self._last_resource_workflow_data = workflow_data
        self._clear_managed_output_ref_caches()
        self._resource_change_summary = resource_config_change_summary(sections)
        if hasattr(self._tree_state, "set_config_value_mode"):
            self._tree_state.set_config_value_mode(self._resource_value_mode)

        if is_restart or not had_resource_tree:
            self.current_run_id = new_run_id
            self._pods.clear_cache()
            self._tree_state.rebuild(sections, workflow_data)
        else:
            self._tree_state.update(sections, workflow_data)
        self.tree_root_widget.focus()
        if self._expand_changed_resources_on_next_render:
            self._expand_changed_resources_on_next_render = False
            self._expand_changed_resource_nodes(sections)
        if self._restore_resource_collapsed_ids_on_next_render is not None:
            self._restore_collapsed_tree_ids(self._restore_resource_collapsed_ids_on_next_render)
            self._restore_resource_collapsed_ids_on_next_render = None

        self._pods.trigger_resolve(new_run_id, use_cache=not force_reload)
        self.update_pod_status()
        self._update_dynamic_bindings()
        self.set_timer(self._refresh_interval, self.action_refresh_workflow)

    def _handle_workflow_data(self, new_data: Dict, force_reload: bool = False) -> None:
        """The Conductor routes data to the relevant managers."""
        self.title = "Workflow Steps"
        if not new_data:
            self._tree_state.reset(LOADING_ROOT_LABEL)
            self.run_worker(self._wait_for_workflow_worker, thread=True, name="_wait_for_workflow_worker")
            return

        new_run_id = new_data.get('status', {}).get('startedAt')
        is_restart = self.current_run_id != new_run_id
        self._clear_managed_output_ref_caches()

        if is_restart:
            self.current_run_id = new_run_id
            self._pods.clear_cache()
            self._tree_state.rebuild(new_data)
        else:
            self._tree_state.update(new_data)

        self._pods.trigger_resolve(new_run_id, use_cache=not force_reload)
        self._live.reconcile_tree_for_live_status_checks(self, self._tree_state.tree.root)
        self.update_pod_status()
        self._update_dynamic_bindings()
        self.set_timer(self._refresh_interval, self.action_refresh_workflow)

    def _wait_for_workflow_worker(self) -> None:
        """Lightweight worker: monitors memory event, triggers refresh on find."""
        # Only trigger if we aren't already waiting and haven't found it yet
        if not self._workflow_waiter.checker():
            self._workflow_waiter.trigger()

        while not self.is_exiting:
            if self._workflow_waiter.checker():
                self._workflow_waiter.reset()
                self.call_from_thread(self.action_refresh_workflow)
                break
            time.sleep(0.1)

    def action_manual_refresh(self) -> None:
        """User-triggered manual refresh (Strongly Consistent)."""
        if self._edit_mode:
            return
        self.run_worker(self._force_refresh_workflow, thread=True, name="_force_refresh_workflow")

    def _force_refresh_workflow(self) -> None:
        """Sequential fetch: Workflow Tree Data -> Trigger Strong Pod Resolution."""
        res, data = self._fetch_workflow_data()
        workflow_data = data if res.get('success') else {}
        if self._resource_view:
            sections = self._build_resource_sections(workflow_data)
            self.call_from_thread(self._handle_resource_data, sections, workflow_data, True)
        else:
            self.call_from_thread(self._handle_workflow_data, workflow_data, True)

    # --- Event Handlers & Actions ---

    def on_tree_node_highlighted(self, event: Tree.NodeHighlighted) -> None:
        if self._edit_mode:
            self._update_edit_help()
        self.update_pod_status()
        self._update_dynamic_bindings()

    def on_tree_node_selected(self, event: Tree.NodeSelected) -> None:
        if not self.is_mounted or isinstance(self.screen, ModalScreen):
            return
        if self._edit_mode:
            event.stop()
            data = event.node.data or {}
            if data.get("type") == "config-edit":
                self._edit_config_node(data.get("edit_node") or data)
            return

        data = event.node.data or {}
        if self._approval_node_for_action(data):
            event.stop()
            self.action_approve_step()
            return
        if event.node.is_expanded:
            event.node.collapse()
        else:
            event.node.expand()

    def on_key(self, event) -> None:
        self._start_deferred_error_notification_timers()
        if not self._edit_mode or isinstance(self.screen, ModalScreen):
            return
        if event.key == "right":
            event.stop()
            self.action_expand_node()
        elif event.key == "left":
            event.stop()
            self.action_collapse_node()
        elif event.key == "space":
            event.stop()
            self.action_toggle_config_boolean()

    @property
    def current_node_data(self) -> Optional[Dict]:
        """Get current node data from tree widget's cursor."""
        node = self.tree_root_widget.cursor_node
        return node.data if node and node.data else None

    @staticmethod
    def _input_parameter(node_data: Dict, name: str) -> Optional[str]:
        for param in node_data.get('inputs', {}).get('parameters', []):
            if param.get('name') == name:
                return param.get('value')
        return None

    def _collect_managed_output_refs(self):
        """Collect CR-status output refs for the selected node or descendants.

        Argo Server can read artifacts by current workflow/node/artifact name,
        but that lookup depends on the workflow CR still existing. `workflow
        show` is resource-centric so it can read the latest retained output
        after old workflows have been replaced. Manage follows that same path
        by using the patch-output steps, whose inputs identify the migration CR
        and status output key that `show` reads.
        """
        tree_node = self.tree_root_widget.cursor_node
        if not tree_node:
            logger.info("Show output requested with no selected tree node")
            return []
        refs = self._managed_output_refs_for_tree_node(tree_node, log=True)
        logger.info("Collected %s managed output ref(s)", len(refs))
        return refs

    def _has_managed_output_refs(self, tree_node) -> bool:
        return bool(self._managed_output_refs_for_tree_node(tree_node, log=False))

    def _managed_output_refs_for_tree_node(self, tree_node, log: bool = False):
        if not tree_node:
            return []
        cache_key = self._managed_output_ref_cache_key(tree_node)
        if cache_key in self._managed_output_ref_cache:
            return self._managed_output_ref_cache[cache_key]
        selected_data = tree_node.data or {}
        if log:
            logger.info(
                "Collecting managed output refs from selected node id=%s name=%s type=%s phase=%s",
                selected_data.get('id'),
                selected_data.get('display_name') or selected_data.get('displayName'),
                selected_data.get('type'),
                selected_data.get('phase'),
            )

        refs = []
        stack = [tree_node]
        while stack:
            current = stack.pop()
            data = current.data or {}
            display_name = data.get('display_name') or data.get('displayName') or ''
            step_name = display_name.split('(')[0].strip()
            patch_spec = PATCH_OUTPUT_STEPS.get(step_name)
            if patch_spec:
                plural, output_name = patch_spec
                resource_name = self._input_parameter(data, 'resourceName')
                if resource_name:
                    resource_path = resource_display_name(plural, resource_name)
                    if log:
                        logger.info(
                            "Found managed output ref patch_step=%s node_id=%s resource=%s output=%s",
                            step_name,
                            data.get('id'),
                            resource_path,
                            output_name,
                        )
                    refs.append((resource_path, output_name))
                elif log:
                    logger.warning(
                        "Managed output patch step %s node_id=%s had no resourceName input",
                        step_name,
                        data.get('id'),
                    )
            stack.extend(reversed(current.children))
        # Fallback for resource nodes: search raw workflow data for patch-output steps
        # (they may be filtered out of the tree by collect_notable_steps)
        if not refs and selected_data.get('id', '').startswith(RESOURCE_ID_PREFIX):
            resource_name = selected_data.get('id', '').removeprefix(RESOURCE_ID_PREFIX)
            refs = self._find_output_refs_in_workflow_data(resource_name)
        self._managed_output_ref_cache[cache_key] = refs
        return refs

    @staticmethod
    def _managed_output_ref_cache_key(tree_node) -> str:
        data = tree_node.data or {}
        node_id = data.get("id")
        return str(node_id) if node_id else f"tree:{id(tree_node)}"

    def _clear_managed_output_ref_caches(self) -> None:
        self._managed_output_ref_cache.clear()
        self._workflow_output_refs_by_resource = None
        self._last_binding_signature = None

    def _find_output_refs_in_workflow_data(self, resource_name: str):
        """Search raw workflow nodes for patch-output steps matching a resource."""
        return list(self._workflow_output_ref_map().get(resource_name, []))

    def _workflow_output_ref_map(self) -> Dict[str, list[tuple[str, str]]]:
        if self._workflow_output_refs_by_resource is not None:
            return self._workflow_output_refs_by_resource
        workflow_data = self._tree_state._workflow_data
        if not workflow_data:
            self._workflow_output_refs_by_resource = {}
            return self._workflow_output_refs_by_resource
        refs: Dict[str, list[tuple[str, str]]] = {}
        for node in (workflow_data.get('status', {}).get('nodes', {}) or {}).values():
            display_name = node.get('displayName', '')
            step_name = display_name.split('(')[0].strip()
            patch_spec = PATCH_OUTPUT_STEPS.get(step_name)
            if not patch_spec:
                continue
            plural, output_name = patch_spec
            node_resource = self._input_parameter(node, 'resourceName')
            if node_resource:
                resource_path = resource_display_name(plural, node_resource)
                refs.setdefault(node_resource, []).append((resource_path, output_name))
        self._workflow_output_refs_by_resource = refs
        return refs

    def action_view_output(self) -> None:
        output_refs = self._collect_managed_output_refs()
        if not output_refs:
            logger.info("Show output unavailable because no managed output artifacts were found")
            self.notify("Output is not available yet", severity="warning")
            return
        node = self.current_node_data or {}
        display_name = node.get('display_name') or node.get('displayName') or self._workflow_name
        output_texts = []
        for resource_name, output_name in output_refs:
            logger.info(
                "Fetching managed output via CR status namespace=%s resource=%s output=%s",
                self._namespace,
                resource_name,
                output_name,
            )
            try:
                output = read_managed_output(self._namespace, resource_name, output_name)
                logger.info(
                    "Managed output fetch succeeded resource=%s output=%s s3_key=%s bytes=%s",
                    resource_name,
                    output_name,
                    output.ref.get('s3Key'),
                    len(output.content.encode('utf-8')),
                )
                output_texts.append((f"{resource_name} / {output_name}", output.content))
            except ArtifactStoreError as e:
                logger.warning(
                    "Managed output unavailable resource=%s output=%s error=%s",
                    resource_name,
                    output_name,
                    e,
                )
                self.notify(f"Output unavailable: {resource_name} / {output_name}", severity="error")
                return
        logger.info("Opening pager with %s output artifact(s) for display_name=%s", len(output_texts), display_name)
        self._logs.show_output_texts_in_pager(self, output_texts, display_name, clean=True)

    def action_follow_logs(self) -> None:
        if not self.current_node_data or self.current_node_data.get('type') != NODE_TYPE_POD:
            return
        
        node_id = self.current_node_data.get('id')
        pod_name = self._pods.get_name(node_id)
        if not pod_name:
            self.notify("Pod not available", severity="error")
            return
        
        containers = self._logs.get_containers(pod_name)
        if not containers:
            self.notify("No containers found", severity="error")
            return
        
        if len(containers) == 1:
            # Single container, follow directly
            self._logs.follow_logs(self, pod_name, containers[0])
        else:
            # Multiple containers, show selection dialog
            self.push_screen(
                ContainerSelectModal(containers, pod_name),
                lambda container: self._follow_selected_container(pod_name, container) if container else None
            )

    def _follow_selected_container(self, pod_name: str, container: str) -> None:
        """Follow logs for the selected container."""
        self._logs.follow_logs(self, pod_name, container)

    def action_view_logs(self) -> None:
        if self.current_node_data and self.current_node_data.get('type') == NODE_TYPE_POD:
            pod_name = self._pods.get_name(self.current_node_data['id'])
            if pod_name:
                self._show_logs_in_pager(self.current_node_data)

    def _show_logs_in_pager(self, node_data: Dict) -> None:
        pod_name = self._pods.get_name(node_data['id'])
        if pod_name:
            self._logs.show_in_pager(self, pod_name, node_data.get('display_name', ''))

    def action_view_resource_progress_logs(self) -> None:
        """View logs for the latest notable workflow pod attached to a resource."""
        node = self.current_node_data or {}
        pod_id = node.get('resource_log_node_id')
        pod_name = self._pods.get_name(pod_id)
        if not pod_name:
            self.action_view_resource_logs()
            return
        display_name = node.get('display_name') or node.get('resource_path') or pod_name
        self._logs.show_in_pager(self, pod_name, display_name)

    def action_view_resource_logs(self) -> None:
        """View logs for a migration resource via the workflow log CLI."""
        node = self.current_node_data
        if not node or not node.get('resource_path'):
            return
        resource_path = node['resource_path']
        with self.suspend():
            os.system('clear')
            os.system(self._resource_log_command(resource_path))

    def action_tail_resource_logs(self) -> None:
        """Tail logs for a migration resource via the workflow log CLI."""
        node = self.current_node_data
        if not node or not node.get('resource_path'):
            return
        resource_path = node['resource_path']
        with self.suspend():
            os.system('clear')
            os.system(self._resource_log_command(resource_path, follow=True))

    @staticmethod
    def _resource_log_command(resource_path: str, follow: bool = False) -> str:
        follow_arg = " -f" if follow else ""
        pager_args = " -R +F" if follow else " -R"
        return f"workflow log resource {shlex.quote(resource_path)}{follow_arg} | less{pager_args}"

    def action_reset_resource(self) -> None:
        """Reset the selected migration resource via the workflow reset command."""
        if self._edit_mode or not self._resource_view:
            return
        if self._resetting_resource_path:
            self.notify(f"Reset already running: {self._resetting_resource_path}", severity="warning")
            return
        target = self._nearest_resource_reset_target()
        if not target:
            return
        self._start_reset_resource_plan(target)

    def _start_reset_resource_plan(self, target: Dict[str, str]) -> None:
        self._resetting_resource_path = target["resource_path"]
        self.update_pod_status()
        self.run_worker(
            lambda: self._reset_resource_dry_run_worker(target),
            thread=True,
            name="reset_resource_dry_run",
        )

    def _reset_resource_dry_run_worker(self, target: Dict[str, str]) -> None:
        command = self._resource_reset_dry_run_command_args(target)
        try:
            result = subprocess.run(command, capture_output=True, text=True, check=True)
            plan = json.loads(result.stdout or "{}")
            plan["request"] = target
            self.call_from_thread(self._handle_reset_resource_plan_loaded, plan)
        except subprocess.CalledProcessError as e:
            output = self._combined_command_output(e)
            self.call_from_thread(self._handle_reset_resource_plan_failed, target, output or str(e))
        except Exception as e:
            logger.exception("Failed to compute reset resource plan")
            output = str(e)
            if isinstance(e, json.JSONDecodeError):
                output = f"Could not parse reset dry-run output: {e}"
            self.call_from_thread(self._handle_reset_resource_plan_failed, target, output)

    def _handle_reset_resource_plan_loaded(self, plan: Dict) -> None:
        self._resetting_resource_path = None
        targets = plan.get("targets") or []
        if not targets:
            request = plan.get("request") or {}
            self.notify(f"Reset plan is empty: {request.get('resource_path', 'resource')}", severity="warning")
            self.update_pod_status()
            return
        self.update_pod_status()
        command = self._resource_reset_commit_command_args(plan)
        self.push_screen(
            ConfirmModal(
                self._resource_reset_confirmation_message(plan, command),
                confirm_label="Reset",
                cancel_label="Cancel",
                default_confirm=False,
            ),
            lambda confirmed: self._start_reset_resource(plan) if confirmed else None,
        )

    def _handle_reset_resource_plan_failed(self, target: Dict[str, str], output: str) -> None:
        self._resetting_resource_path = None
        resource_path = target.get("resource_path") or "resource"
        self.notify(f"Reset dry-run failed: {resource_path}", severity="error")
        self.update_pod_status()
        if output.strip():
            self._logs.show_output_texts_in_pager(
                self,
                [(f"workflow reset --dry-run {resource_path}", output)],
                f"Reset dry-run failed: {resource_path}",
            )

    def _start_reset_resource(self, plan: Dict) -> None:
        resource_path = ((plan.get("request") or {}).get("resource_path") or "resource")
        self._resetting_resource_path = resource_path
        self.update_pod_status()
        self.run_worker(
            lambda: self._reset_resource_worker(plan),
            thread=True,
            name="reset_resource",
        )

    def _reset_resource_worker(self, plan: Dict) -> None:
        command = self._resource_reset_commit_command_args(plan)
        try:
            result = subprocess.run(command, capture_output=True, text=True, check=True)
            output = self._combined_command_output(result)
            self.call_from_thread(self._handle_reset_resource_succeeded, plan, output)
        except subprocess.CalledProcessError as e:
            output = self._combined_command_output(e)
            self.call_from_thread(self._handle_reset_resource_failed, plan, output or str(e))
        except Exception as e:
            logger.exception("Failed to reset resource")
            self.call_from_thread(self._handle_reset_resource_failed, plan, str(e))

    def _handle_reset_resource_succeeded(self, plan: Dict, output: str) -> None:
        self._resetting_resource_path = None
        resource_path = ((plan.get("request") or {}).get("resource_path") or "resource")
        self.notify(f"Reset complete: {resource_path}")
        self.update_pod_status()
        self.action_manual_refresh()

    def _handle_reset_resource_failed(self, plan: Dict, output: str) -> None:
        self._resetting_resource_path = None
        resource_path = ((plan.get("request") or {}).get("resource_path") or "resource")
        self.notify(f"Reset failed: {resource_path}", severity="error")
        self.update_pod_status()
        if output.strip():
            self._logs.show_output_texts_in_pager(
                self,
                [(f"workflow reset {resource_path}", output)],
                f"Reset failed: {resource_path}",
            )

    @staticmethod
    def _resource_reset_confirmation_message(plan: Dict, command: list[str]) -> str:
        request = plan.get("request") or {}
        targets = plan.get("targets") or []
        lines = [
            f"Reset {request.get('resource_path', 'resource')}?",
            "",
            f"This confirmed plan will delete {len(targets)} resource{'s' if len(targets) != 1 else ''}:",
        ]
        for target in targets:
            phase = f" ({target.get('phase')})" if target.get("phase") else ""
            lines.append(f"  - {target.get('path')}{phase}")
        messages = [str(message) for message in (plan.get("messages") or []) if str(message).strip()]
        warnings = [str(warning) for warning in (plan.get("warnings") or []) if str(warning).strip()]
        if messages:
            lines.extend(["", "Messages:"])
            lines.extend(f"  {message}" for message in messages)
        if warnings:
            lines.extend(["", "Warnings:"])
            lines.extend(f"  {warning}" for warning in warnings)
        lines.extend([
            "",
            "Commit command:",
            shlex.join(command),
            "",
            "The commit uses the exact resource set above. If a new dependent resource exists, reset will fail and you must dry-run again.",
        ])
        return "\n".join(lines)

    @staticmethod
    def _combined_command_output(result) -> str:
        parts = []
        stdout = getattr(result, "stdout", None)
        stderr = getattr(result, "stderr", None)
        if stdout:
            parts.append(str(stdout).rstrip("\n"))
        if stderr:
            parts.append(str(stderr).rstrip("\n"))
        return "\n".join(part for part in parts if part)

    def action_copy_pod_name(self) -> None:
        if not self.current_node_data:
            return
        node_id = self.current_node_data.get('id')
        if pod_name := self._pods.get_name(node_id):
            if copy_to_clipboard(pod_name):
                self.notify(f"📋 Copied: {pod_name}")

    def action_approve_step(self) -> None:
        node = self.current_node_data
        approval_node = self._approval_node_for_action(node)
        if approval_node:
            msg = f"Approve '{approval_node.get('display_name')}'?"
            reason = approval_node.get('denial_reason')
            if reason:
                msg += f"\n\n{reason}"
            self.push_screen(ConfirmModal(msg),
                             lambda confirmed: self._execute_approval(approval_node) if confirmed else None)

    def _execute_approval(self, node_data: Dict) -> None:
        try:
            res = self._argo_service.approve_step(self._namespace, self._workflow_name, node_data)
            if res.get('success'):
                self.notify(f"✅ Approved: {node_data.get('display_name')}")
                self.action_manual_refresh()
            else:
                self.notify(f"❌ Failed: {res.get('message')}", severity="error")
        except Exception as e:
            self.notify(f"Error: {e}", severity="error")

    def check_action(self, action: str, parameters: tuple[object, ...]) -> bool | None:
        if action in {
            "expand_node",
            "collapse_node",
            "edit_selected_config_node",
            "reload_config_edit",
            "rename_config_node",
            "reset_resource",
        } and isinstance(self.screen, ModalScreen):
            return False
        return super().check_action(action, parameters)

    def action_expand_node(self) -> None:
        tree = self.tree_root_widget
        if node := tree.cursor_node:
            node.expand()

    def action_collapse_node(self) -> None:
        tree = self.tree_root_widget
        if node := tree.cursor_node:
            if node.is_expanded and node.children:
                node.collapse()
            elif node.parent:
                tree.move_cursor(node.parent)
                tree.focus()

    def update_pod_status(self) -> None:
        if self._edit_mode:
            node = selected_edit_node(self.tree_root_widget)
            dirty = "dirty" if self._edit_dirty else "clean"
            value_mode = EDIT_MODE_LABELS.get(self._edit_value_mode, self._edit_value_mode)
            status_mode = EDIT_MODE_LABELS.get(self._edit_status_mode, self._edit_status_mode)
            field_visibility = FIELD_VISIBILITY_LABELS.get(
                self._edit_field_visibility,
                self._edit_field_visibility,
            )
            if node:
                status = node.get("status", "ok")
                self._set_pod_status(
                    f"Config edit: [bold cyan]{status}[/]  [{dirty}]  "
                    f"Values: {value_mode}  Status: {status_mode}  "
                    f"Fields: {field_visibility}  w/Ctrl+s saves, Esc exits"
                )
            else:
                self._set_pod_status(
                    f"Config edit: [{dirty}]  Values: {value_mode}  "
                    f"Status: {status_mode}  Fields: {field_visibility}  w/Ctrl+s saves, Esc exits"
                )
            return
        if self._resource_view:
            summary = self._resource_change_summary
            value_mode = CONFIG_MODE_LABELS.get(self._resource_value_mode, self._resource_value_mode)
            if self._resetting_resource_path:
                self._set_pod_status(f"Resetting {self._resetting_resource_path}...  Values: {value_mode}")
                return
            if self._submitting_workflow:
                self._set_pod_status(f"Submitting workflow...  Values: {value_mode}")
                return
            if summary.get('resources'):
                self._set_pod_status(
                    f"Config changes: [green]{summary.get('to_submit', 0)} to submit[/], "
                    f"[grey50]{summary.get('pending', 0)} pending[/]  Values: {value_mode}"
                )
                return
            self._set_pod_status(f"Values: {value_mode}")
            return
        node = self.current_node_data
        if not node:
            self._set_pod_status("")
            return
        
        node_type = node.get('type')
        approval_node = self._approval_node_for_action(node)
        if approval_node:
            name_param = None
            for p in approval_node.get('inputs', {}).get('parameters', []):
                if p.get('name') in ('resourceName', 'name'):
                    name_param = p.get('value')
                    break
            self._set_pod_status(f"Name: [bold cyan]{name_param}[/]" if name_param else "")
        elif node_type == NODE_TYPE_POD:
            node_id = node.get('id')
            name = self._pods.get_name(node_id) if node_id else None
            self._set_pod_status(f"Pod: [bold green]{name}[/]" if name else "Pod: (not available)")
        else:
            self._set_pod_status("")

    def _set_pod_status(self, content: str) -> None:
        if content == self._last_pod_status_text:
            return
        self._last_pod_status_text = content
        self.query_one("#pod-status", Static).update(content)

    def _update_dynamic_bindings(self) -> None:
        """Reconfigures the Footer and keys based on the currently selected node."""
        tree_node = self.tree_root_widget.cursor_node
        edit_node = selected_edit_node(self.tree_root_widget) if self._edit_mode else None
        node = edit_node if self._edit_mode else (tree_node.data if tree_node and tree_node.data else None)
        output_available = (
            False
            if self._edit_mode or not node
            else self._has_managed_output_refs(tree_node)
        )
        signature = self._dynamic_binding_signature(node, output_available)
        if signature == self._last_binding_signature:
            return
        self._last_binding_signature = signature

        self._bindings = self._bindings.__class__()

        self.bind("ctrl+p", "command_palette", show=False)
        self.bind("q", "quit", description="Quit")
        self.bind(
            "m",
            "toggle_mouse_input",
            description="Mouse Off" if self._mouse_input_enabled else "Mouse On",
        )

        if self._edit_mode:
            self.bind("escape", "exit_config_edit", description="Exit Edit")
            self.bind("r", "reload_config_edit", description="Reload")
            self.bind("s", "submit_workflow", description="Submit")
            self.bind("w", "save_config_edit", description="Save")
            self.bind("ctrl+s", "save_config_edit", description="Save")
            self.bind("?", "show_config_edit_help", description="Help", show=False)
            self.bind("f", "cycle_config_field_visibility", description=self._next_field_visibility_description())
            self.bind("i", "edit_selected_config_node", show=False)
            self._bindings.bind(
                "enter",
                "edit_selected_config_node",
                "",
                show=False,
                priority=True,
            )
            self._bindings.bind(
                "left",
                "collapse_node",
                "",
                show=False,
                priority=True,
            )
            self._bindings.bind(
                "right",
                "expand_node",
                "",
                show=False,
                priority=True,
            )
            if node and node.get("valueKind") == "boolean":
                self._bindings.bind("space", "toggle_config_boolean", "Toggle", priority=True)
            if node and node.get("valueKind") == "command":
                self.bind("a", "edit_selected_config_node", description="Add")
            if self._nearest_config_rename_target():
                self.bind("n", "rename_config_node", description="Rename")
            delete_target = self._nearest_config_delete_target()
            if delete_target and delete_target[0] == "remove":
                self.bind("delete", "remove_config_node", description="Remove")
                self.bind("backspace", "remove_config_node", show=False)
            elif delete_target and delete_target[0] == "clear":
                description = "Delete" if self._config_node_can_clear_required_union(delete_target[1]) else "Clear"
                self.bind("delete", "clear_config_node", description=description)
                self.bind("backspace", "clear_config_node", description=description, show=False)
            self.refresh_bindings()
            return

        self.bind("r", "manual_refresh", description="Refresh")

        self._bindings.bind(
            "left",
            "collapse_node",
            "",
            show=False,
            priority=True,
        )
        self._bindings.bind(
            "right",
            "expand_node",
            "",
            show=False,
            priority=True,
        )

        if self._resource_view:
            self.bind("e", "edit_config", description="Edit Config")
            self.bind("s", "submit_workflow", description="Submit")
            self.bind("v", "cycle_resource_value_mode", description="Value Mode")

        if node:
            self._bind_node_actions(node, output_available)

        if self._nearest_resource_reset_target() and not self._resetting_resource_path:
            self.bind("delete", "reset_resource", description="Reset")
            self.bind("backspace", "reset_resource", show=False)

        self.refresh_bindings()

    def _dynamic_binding_signature(self, node: Optional[Dict], output_available: bool) -> tuple:
        base = (
            self._edit_mode,
            self._resource_view,
            self._mouse_input_enabled,
        )
        if self._edit_mode:
            return (
                *base,
                self._edit_field_visibility,
                (node or {}).get("valueKind"),
                bool((node or {}).get("command")),
                self._is_removable_edit_node(node),
                bool(self._nearest_config_rename_target()),
                self._config_node_can_unset(node or {}),
            )

        node = node or {}
        node_id = node.get('id') or ''
        node_type = node.get('type')
        return (
            *base,
            node_id.startswith(RESOURCE_ID_PREFIX),
            node_type,
            node.get('phase'),
            is_approval_node(node),
            bool(self._approval_node_for_action(node)),
            bool(self._pods.get_name(node_id)) if node_type == NODE_TYPE_POD else False,
            bool(node.get('resource_path')),
            bool(self._pods.get_name(node.get('resource_log_node_id'))),
            bool(self._nearest_resource_reset_target()),
            bool(self._resetting_resource_path),
            output_available,
        )

    def _bind_node_actions(self, node: Dict, output_available: bool) -> None:
        """Bind context-sensitive keys for the selected node."""
        node_id = node.get('id') or ''
        ntype = node.get('type')

        if node_id.startswith(RESOURCE_ID_PREFIX):
            self.bind("l", "view_resource_logs", description="View Logs")
            self.bind("t", "tail_resource_logs", description="Tail Logs")
            if self._approval_node_for_action(node):
                self.bind("a", "approve_step", description="Approve")
            if output_available:
                self.bind("o", "view_output", description=DESC_SHOW_OUTPUT)
        elif ntype == NODE_TYPE_POD and self._pods.get_name(node_id) and not is_approval_node(node):
            self.bind("l", "view_logs", description="View Logs")
            if output_available:
                self.bind("o", "view_output", description=DESC_SHOW_OUTPUT)
            if node.get('phase') == PHASE_RUNNING:
                self.bind("f", "follow_logs", description="Follow Logs")
                self.bind("t", "follow_logs", description="Tail Logs")
            self.bind("c", "copy_pod_name", description="Copy Pod Name")
        elif is_approval_node(node) and node.get('phase') == PHASE_RUNNING:
            self.bind("a", "approve_step", description="Approve")
        elif node.get('resource_path'):
            self.bind("l", "view_resource_logs", description="View Logs")
            self.bind("t", "tail_resource_logs", description="Tail Logs")
        elif output_available:
            self.bind("o", "view_output", description=DESC_SHOW_OUTPUT)

    def action_toggle_mouse_input(self) -> None:
        """Temporarily release or restore terminal mouse reporting."""
        self._set_mouse_input_enabled(not self._mouse_input_enabled)

    def _set_mouse_input_enabled(
        self,
        enabled: bool,
        notify: bool = True,
        update_bindings: bool = True,
    ) -> None:
        if enabled == self._mouse_input_enabled:
            return
        driver = getattr(self, "_driver", None)
        if driver is not None:
            if not enabled:
                self.capture_mouse(None)
                self._mouse_pixels_was_enabled = bool(getattr(driver, "_mouse_pixels", False))
                self._write_mouse_reporting(driver, enabled=False)
            else:
                self._write_mouse_reporting(driver, enabled=True)
                if self._mouse_pixels_was_enabled and hasattr(driver, "_enable_mouse_pixels"):
                    driver._enable_mouse_pixels()
                self._mouse_pixels_was_enabled = False

        self._mouse_input_enabled = enabled
        if notify:
            if enabled:
                self.notify("Mouse handling restored")
            else:
                self.notify("Mouse handling disabled; drag to select text, press m to restore")
        if update_bindings:
            self._update_dynamic_bindings()

    @staticmethod
    def _write_mouse_reporting(driver, enabled: bool) -> None:
        method_name = "_enable_mouse_support" if enabled else "_disable_mouse_support"
        method = getattr(driver, method_name, None)
        if callable(method):
            method()
            if not enabled and callable(getattr(driver, "write", None)):
                driver.write(DISABLE_MOUSE_PIXELS_SEQUENCE)
                flush = getattr(driver, "flush", None)
                if callable(flush):
                    flush()
            return
        write = getattr(driver, "write", None)
        if callable(write):
            write(ENABLE_MOUSE_SEQUENCES if enabled else DISABLE_MOUSE_SEQUENCES)
            flush = getattr(driver, "flush", None)
            if callable(flush):
                flush()

    def action_activate_selected_node(self) -> None:
        node = self.current_node_data
        if self._approval_node_for_action(node):
            self.action_approve_step()
            return
        tree = self.tree_root_widget
        if tree.cursor_node:
            if tree.cursor_node.is_expanded:
                tree.cursor_node.collapse()
            else:
                tree.cursor_node.expand()

    def action_cycle_resource_value_mode(self) -> None:
        if not self._resource_view or self._edit_mode:
            return
        self._set_resource_value_mode(
            self._next_edit_mode(self._resource_value_mode),
            refresh_tree=True,
        )

    def _set_resource_value_mode(self, mode: str, refresh_tree: bool = False) -> None:
        self._resource_value_mode = mode
        if hasattr(self._tree_state, "set_config_value_mode"):
            self._tree_state.set_config_value_mode(self._resource_value_mode)
        if refresh_tree and self._last_resource_sections is not None:
            self._tree_state.update(self._last_resource_sections, self._last_resource_workflow_data)
        self.update_pod_status()
        self._update_dynamic_bindings()

    def action_submit_workflow(self) -> None:
        if not self._resource_view or self._submitting_workflow:
            return
        if self._edit_mode:
            self._submit_config_edit()
            return
        self.push_screen(
            ConfirmModal("Submit saved workflow configuration and replace the current workflow?"),
            lambda confirmed: self._start_submit_workflow() if confirmed else None,
        )

    def _submit_config_edit(self) -> None:
        if self._edit_draft_yaml is None:
            return
        validation = (self._edit_state or {}).get("validation") or {}
        if validation.get("valid") is False or validation.get("diagnostics") or validation.get("errors"):
            count = self._config_edit_validation_issue_count()
            self.notify(
                f"Validation still reports {count} issue{'s' if count != 1 else ''}. "
                "Fix before submit, or press w to save the draft.",
                severity="error",
                timeout=8,
            )
            return
        self.push_screen(
            ConfirmModal("Save pending config and submit workflow?"),
            lambda confirmed: self._save_config_edit_then_submit() if confirmed else None,
        )

    def _save_config_edit_then_submit(self) -> None:
        self._after_config_edit_save = "submit"
        self.action_save_config_edit()

    def _start_submit_workflow(self) -> None:
        self._submitting_workflow = True
        self.update_pod_status()
        self.run_worker(self._submit_workflow_worker, thread=True, name="submit_workflow")

    def _submit_workflow_worker(self) -> None:
        try:
            service = self._config_edit_service_or_default()
            result = service.submit_saved_config(self._workflow_name)
            self.call_from_thread(self._handle_workflow_submitted, result)
        except Exception as e:
            logger.exception("Failed to submit workflow")
            self.call_from_thread(self._handle_workflow_submit_failed, e)

    def _handle_workflow_submitted(self, result: Dict) -> None:
        self._submitting_workflow = False
        submitted_name = result.get('workflow_name') or self._workflow_name
        self.notify(f"Workflow submitted: {submitted_name}")
        self.current_run_id = None
        self._expand_changed_resources_on_next_render = True
        self.action_manual_refresh()

    def _handle_workflow_submit_failed(self, error: Exception) -> None:
        self._submitting_workflow = False
        self.notify(_format_workflow_submit_error(error), severity="error", markup=False)
        self.update_pod_status()

    def action_edit_config(self) -> None:
        """Enter schema-guided pending-config edit mode."""
        if not self._resource_view:
            self.notify("Config edit is available from resource view", severity="warning")
            return
        self._set_resource_value_mode(EDIT_MODE_ALL, refresh_tree=True)
        self._resource_collapsed_ids_before_edit = self._collapsed_tree_ids()
        self._show_config_edit_loading()
        logger.info("Loading workflow config edit state")
        self.run_worker(self._load_config_edit_state_worker, thread=True, name="load_config_edit_state")

    def _show_config_edit_loading(self) -> None:
        self._edit_loading = True
        self.title = "Workflow Config Edit"
        tree = self.tree_root_widget
        tree.disabled = True
        help_panel = self.query_one("#edit-help", Static)
        help_panel.display = True
        help_panel.update("[bold]Workflow Config Edit[/]\nLoading configuration editor...")
        self.update_pod_status()
        self._update_dynamic_bindings()

    def _config_edit_service_or_default(self):
        if self._config_edit_service is not None:
            return self._config_edit_service
        from ..services.config_edit_service import ConfigEditService
        return ConfigEditService(namespace=self._namespace)

    def _load_config_edit_state_worker(self, selected_id: Optional[str] = None) -> None:
        try:
            service = self._config_edit_service_or_default()
            if hasattr(service, "load_edit_session"):
                session = service.load_edit_session()
            else:
                session = {
                    "raw_yaml": "",
                    "edit_state": service.load_edit_state(),
                }
            snapshots = self._load_config_edit_snapshots(service)
            self.call_from_thread(self._handle_config_edit_session, session, snapshots, selected_id)
        except Exception as e:
            logger.exception("Failed to load config edit state")
            self.call_from_thread(self._handle_config_edit_load_failed, e)

    def _load_config_edit_snapshots(self, service) -> Optional[Dict[str, Any]]:
        if self._last_resource_config_snapshots is not None:
            return self._last_resource_config_snapshots
        if not hasattr(service, "load_resource_config_snapshots"):
            return None
        try:
            snapshots = service.load_resource_config_snapshots(self._workflow_name)
            self._last_resource_config_snapshots = snapshots
            return snapshots
        except Exception:
            logger.exception("Failed to load config edit value snapshots")
            return None

    def _handle_config_edit_load_failed(self, error: Exception) -> None:
        self._edit_loading = False
        self._resource_collapsed_ids_before_edit = None
        self.title = "Migration Status"
        tree = self.tree_root_widget
        tree.disabled = False
        self.query_one("#edit-help", Static).display = False
        self.update_pod_status()
        self._update_dynamic_bindings()
        self.notify(f"Config edit unavailable: {error}", severity="error")

    def _handle_config_edit_session(
        self,
        session,
        snapshots: Optional[Dict[str, Any]] = None,
        selected_id: Optional[str] = None,
    ) -> None:
        raw_yaml = getattr(session, "raw_yaml", None)
        if raw_yaml is None:
            raw_yaml = session.get("raw_yaml", "")
        edit_state = self._enrich_config_edit_state(
            getattr(session, "edit_state", None) or session["edit_state"],
            snapshots,
            self._parse_config_yaml(raw_yaml),
        )
        logger.info(
            "Loaded workflow config edit state: raw_yaml_bytes=%s nodes=%s validation_valid=%s",
            len(raw_yaml or ""),
            len(edit_state.get("nodes") or []),
            (edit_state.get("validation") or {}).get("valid"),
        )
        self._edit_loading = False
        self.title = "Workflow Config Edit"
        self.tree_root_widget.disabled = False
        self._edit_mode = True
        self._edit_state = edit_state
        self._edit_draft_yaml = raw_yaml
        self._edit_dirty = False
        self._edit_value_mode = EDIT_MODE_ALL
        self._edit_status_mode = EDIT_MODE_ALL
        self._edit_field_visibility = FIELD_VISIBILITY_ESSENTIAL
        expansion_state = self._edit_expansion_state_for_render(edit_state)
        render_edit_state(
            self.tree_root_widget,
            edit_state,
            self._edit_value_mode,
            self._edit_status_mode,
            self._edit_field_visibility,
            expansion_state=expansion_state,
        )
        if selected_id:
            self.call_after_refresh(lambda: self._restore_config_edit_selection(selected_id))
        else:
            self._focus_config_edit_tree()
        help_panel = self.query_one("#edit-help", Static)
        help_panel.display = True
        self._update_edit_help()
        self.update_pod_status()
        self._update_dynamic_bindings()

    def _enrich_config_edit_state(
        self,
        edit_state: Dict[str, Any],
        snapshots: Optional[Dict[str, Any]],
        pending_config: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """Attach deployed/current/pending value states to TS edit nodes."""
        if not snapshots:
            return edit_state

        enriched = copy.deepcopy(edit_state)
        submitted_config = ((snapshots.get("submitted") or {}).get("workflowConfig")) or {}
        current_config = submitted_config
        pending_config = pending_config or {}
        submitted_console = snapshots.get("submitted_console") or {}
        current_console = submitted_console
        pending_console = snapshots.get("pending_console") or {}
        for node in enriched.get("nodes") or []:
            self._enrich_config_edit_node(
                node,
                submitted_config,
                current_config,
                pending_config,
                submitted_console,
                current_console,
                pending_console,
            )
        return enriched

    def _enrich_config_edit_node(
        self,
        node: Dict[str, Any],
        deployed_config: Dict[str, Any],
        current_config: Dict[str, Any],
        pending_config: Dict[str, Any],
        deployed_console: Dict[str, Any],
        current_console: Dict[str, Any],
        pending_console: Dict[str, Any],
    ) -> int:
        changed_count = 0
        for child in node.get("children") or []:
            changed_count += self._enrich_config_edit_node(
                child,
                deployed_config,
                current_config,
                pending_config,
                deployed_console,
                current_console,
                pending_console,
            )

        own_changed = 0
        if self._edit_node_supports_value_states(node):
            states = copy.deepcopy(node.get("states") or {})
            deployed = self._with_schema_default_hint(
                self._config_edit_value_state(deployed_config, deployed_console, node),
                node,
            )
            current = self._with_schema_default_hint(
                self._config_edit_value_state(current_config, current_console, node),
                node,
            )
            pending = self._with_schema_default_hint(
                self._config_edit_value_state(
                    pending_config,
                    pending_console,
                    node,
                    prefer_console=False,
                ),
                node,
            )

            submitted_changed = self._edit_state_changed(deployed, current)
            pending_changed = self._edit_state_changed(current, pending)
            own_changed = 1 if submitted_changed or pending_changed else 0

            states[EDIT_MODE_DEPLOYED] = self._edit_state_payload(deployed, changed=False)
            states[EDIT_MODE_CURRENT_WORKFLOW] = self._edit_state_payload(current, changed=submitted_changed)
            states[EDIT_MODE_PENDING_SUBMIT] = self._edit_state_payload(pending, changed=pending_changed)
            self._merge_edit_node_validation_status(states[EDIT_MODE_PENDING_SUBMIT], node)
            node["states"] = states

        total_changed = changed_count + own_changed
        if total_changed:
            self._merge_edit_node_changed_status(node, total_changed)
        return total_changed

    @classmethod
    def _config_edit_value_state(
        cls,
        workflow_config: Dict[str, Any],
        console_config: Dict[str, Any],
        node: Dict[str, Any],
        prefer_console: bool = True,
    ) -> Dict[str, Any]:
        console_state = cls._console_config_value_state(console_config, node)
        if prefer_console and console_state is not None:
            return console_state

        workflow_state = cls._workflow_config_value_state(
            workflow_config,
            node,
            allow_node_default=True,
        )
        if not prefer_console:
            if workflow_state.get("present") or console_state is None:
                return workflow_state
            return console_state
        return workflow_state

    @staticmethod
    def _with_schema_default_hint(state: Dict[str, Any], node: Dict[str, Any]) -> Dict[str, Any]:
        if state.get("present") is not False:
            return state
        if not node.get("valueDefaulted") or "value" not in node:
            return state
        return {**state, "defaultValue": node.get("value")}

    @classmethod
    def _console_config_value_state(
        cls,
        console_config: Dict[str, Any],
        node: Dict[str, Any],
    ) -> Optional[Dict[str, Any]]:
        path = node.get("path") or []
        if len(path) < 3 or not console_config:
            return None

        root = path[0]
        if root == "traffic" and len(path) >= 3 and path[1] == "kafkaClusters":
            return cls._kafka_console_value_state(
                console_config,
                str(path[2]),
                [str(part) for part in path[3:]],
            )

        name = str(path[1])
        relative_path = [str(part) for part in path[2:]]
        if root == "sourceClusters":
            return cls._cluster_console_value_state(
                console_config,
                "sources",
                name,
                relative_path,
                node,
            )
        if root == "targetClusters":
            return cls._cluster_console_value_state(
                console_config,
                "targets",
                name,
                relative_path,
                node,
            )
        return None

    @classmethod
    def _cluster_console_value_state(
        cls,
        console_config: Dict[str, Any],
        collection_name: str,
        name: str,
        relative_path: list[str],
        node: Dict[str, Any],
    ) -> Optional[Dict[str, Any]]:
        resource = cls._find_console_resource(console_config, collection_name, name)
        if resource is None:
            return {"present": False}
        client_config = resource.get("clientConfig") or {}
        parameter_path = cls._cluster_console_parameter_path(relative_path, node)
        if parameter_path is None:
            return None
        if parameter_path == ["authConfig"]:
            return cls._cluster_auth_mode_state(client_config)
        return cls._nested_state(client_config, parameter_path)

    @staticmethod
    def _cluster_console_parameter_path(relative_path: list[str], node: Dict[str, Any]) -> Optional[list[str]]:
        if relative_path == ["endpoint"]:
            return ["endpoint"]
        if relative_path == ["allowInsecure"]:
            return ["allow_insecure"]
        if relative_path == ["version"]:
            return ["version"]
        if relative_path == ["authConfig"] and node.get("valueKind") == "union":
            return ["authConfig"]
        if relative_path[:2] == ["authConfig", "basic"]:
            field = relative_path[2:]
            if field == ["secretName"]:
                return ["basic_auth", "k8s_secret_name"]
            if field in (["username"], ["password"]):
                return ["basic_auth", field[0]]
        if relative_path[:2] == ["authConfig", "sigv4"]:
            return ["sigv4", *relative_path[2:]]
        if relative_path[:2] == ["authConfig", "mtls"]:
            return ["mtls_auth", *relative_path[2:]]
        return None

    @staticmethod
    def _cluster_auth_mode_state(client_config: Dict[str, Any]) -> Dict[str, Any]:
        if "basic_auth" in client_config:
            return {"present": True, "value": "basic"}
        if "sigv4" in client_config:
            return {"present": True, "value": "sigv4"}
        if "mtls_auth" in client_config:
            return {"present": True, "value": "mtls"}
        if "no_auth" in client_config:
            return {"present": True, "value": "none"}
        return {"present": False}

    @classmethod
    def _kafka_console_value_state(
        cls,
        console_config: Dict[str, Any],
        name: str,
        relative_path: list[str],
    ) -> Optional[Dict[str, Any]]:
        resource = cls._find_console_resource(console_config, "kafkas", name)
        if resource is None:
            return {"present": False}
        runtime = resource.get("runtime") or {}
        if relative_path == ["mode"]:
            runtime_type = runtime.get("type")
            if runtime_type == "strimzi":
                return {"present": True, "value": "autoCreate"}
            if runtime_type == "direct":
                return {"present": True, "value": "existing"}
            return {"present": False}
        if relative_path == ["autoCreate", "auth"]:
            auth_type = runtime.get("authType")
            return {"present": True, "value": auth_type} if auth_type else {"present": False}
        if relative_path == ["autoCreate", "auth", "type"]:
            auth_type = runtime.get("authType")
            return {"present": True, "value": auth_type} if auth_type else {"present": False}
        return None

    @staticmethod
    def _find_console_resource(
        console_config: Dict[str, Any],
        collection_name: str,
        name: str,
    ) -> Optional[Dict[str, Any]]:
        for resource in console_config.get(collection_name) or []:
            aliases = [resource.get("refName"), *(resource.get("aliases") or [])]
            if name in aliases:
                return resource
        return None

    @classmethod
    def _nested_state(cls, source: Dict[str, Any], path: list[str]) -> Dict[str, Any]:
        found, value = cls._lookup_workflow_config_path(source, path)
        if not found:
            return {"present": False}
        return {"present": True, "value": value}

    @staticmethod
    def _edit_node_supports_value_states(node: Dict[str, Any]) -> bool:
        if node.get("valueKind") in {"scalar", "boolean"}:
            return True
        return node.get("valueKind") == "union" and "value" in node

    @classmethod
    def _workflow_config_value_state(
        cls,
        workflow_config: Dict[str, Any],
        node: Dict[str, Any],
        allow_node_default: bool = False,
    ) -> Dict[str, Any]:
        mode_value = cls._single_key_union_mode_value(workflow_config, node)
        if mode_value is not None:
            return {"present": True, "value": mode_value}
        found, value = cls._lookup_workflow_config_path(workflow_config, node.get("path") or [])
        if not found:
            if allow_node_default and cls._node_schema_default_applies(workflow_config, node):
                return {"present": True, "value": node.get("value"), "defaulted": True}
            return {"present": False}
        if node.get("valueKind") == "union":
            value = cls._union_variant_value(value, node)
        return {"present": True, "value": value}

    @staticmethod
    def _edit_state_changed(previous: Dict[str, Any], next_state: Dict[str, Any]) -> bool:
        if same_value_state(previous, next_state):
            return False
        if (
            not previous.get("present")
            and next_state.get("present")
            and next_state.get("defaulted")
        ):
            return False
        return True

    @classmethod
    def _node_schema_default_applies(cls, workflow_config: Dict[str, Any], node: Dict[str, Any]) -> bool:
        path = node.get("path") or []
        if not node.get("valueDefaulted") or "value" not in node or not path:
            return False
        parent_found, _parent = cls._lookup_workflow_config_path(workflow_config, path[:-1])
        return parent_found

    @classmethod
    def _single_key_union_mode_value(cls, workflow_config: Dict[str, Any], node: Dict[str, Any]) -> Optional[Any]:
        path = node.get("path") or []
        if node.get("valueKind") != "union" or not path or path[-1] != "mode":
            return None
        found, value = cls._lookup_workflow_config_path(workflow_config, path[:-1])
        if not found or not isinstance(value, dict):
            return None
        variant_values = [
            variant.get("value")
            for variant in (node.get("variants") or [])
            if variant.get("value") is not None
        ]
        for variant_value in variant_values:
            if variant_value in value:
                return variant_value
        return None

    @staticmethod
    def _edit_state_payload(state: Dict[str, Any], changed: bool) -> Dict[str, Any]:
        payload = dict(state)
        payload["status"] = "changed" if changed else "ok"
        if changed:
            payload["statusCounts"] = {"changed": 1}
        else:
            payload.pop("statusCounts", None)
        return payload

    @staticmethod
    def _merge_edit_node_changed_status(node: Dict[str, Any], changed_count: int) -> None:
        counts = dict(node.get("statusCounts") or {})
        counts["changed"] = max(int(counts.get("changed") or 0), changed_count)
        node["statusCounts"] = counts
        if STATUS_PRIORITY.get(node.get("status") or "ok", 0) < STATUS_PRIORITY["changed"]:
            node["status"] = "changed"

    @staticmethod
    def _merge_edit_node_validation_status(payload: Dict[str, Any], node: Dict[str, Any]) -> None:
        counts = dict(payload.get("statusCounts") or {})
        node_counts = node.get("statusCounts") or {}
        for count_key in ("errors", "required", "warnings", "gated", "blocked"):
            node_count = int(node_counts.get(count_key) or 0)
            if node_count:
                counts[count_key] = max(int(counts.get(count_key) or 0), node_count)
        if counts:
            payload["statusCounts"] = counts

        diagnostics = list(node.get("diagnostics") or [])
        if diagnostics:
            payload["diagnostics"] = diagnostics

        node_status = str(node.get("status") or "ok")
        payload_status = str(payload.get("status") or "ok")
        if (
            node_status != "changed"
            and STATUS_PRIORITY.get(node_status, 0) > STATUS_PRIORITY.get(payload_status, 0)
        ):
            payload["status"] = node_status

    @staticmethod
    def _lookup_workflow_config_path(workflow_config: Dict[str, Any], path: list[Any]) -> tuple[bool, Any]:
        current: Any = workflow_config
        for part in path:
            if isinstance(current, dict):
                if part not in current:
                    return False, None
                current = current[part]
                continue
            if isinstance(current, list) and str(part).isdigit():
                index = int(str(part))
                if index >= len(current):
                    return False, None
                current = current[index]
                continue
            return False, None
        return True, current

    @staticmethod
    def _union_variant_value(value: Any, node: Dict[str, Any]) -> Any:
        if not isinstance(value, dict) or not value:
            return value
        variant_values = {variant.get("value") for variant in (node.get("variants") or [])}
        matching_keys = [key for key in value.keys() if key in variant_values]
        if len(matching_keys) == 1:
            return matching_keys[0]
        if len(value) == 1:
            return next(iter(value.keys()))
        return value

    @staticmethod
    def _parse_config_yaml(raw_yaml: Optional[str]) -> Dict[str, Any]:
        if not raw_yaml or not raw_yaml.strip():
            return {}
        try:
            parsed = yaml.safe_load(raw_yaml) or {}
            return parsed if isinstance(parsed, dict) else {}
        except Exception:
            logger.debug("Failed to parse pending config YAML for edit value overlay", exc_info=True)
            return {}

    def action_exit_config_edit(self) -> None:
        """Leave edit mode and restore the live resource tree."""
        if not self._edit_mode:
            return
        if self._edit_dirty:
            self.push_screen(
                ConfigEditExitModal(
                    "Leave config edit mode?",
                    self._config_edit_exit_status_message(),
                    default_action=self._default_config_edit_exit_action(),
                ),
                lambda action: self._handle_config_edit_exit_choice(action, quit_after=False),
            )
            return
        self._discard_config_edit()

    def action_reload_config_edit(self) -> None:
        """Reload the edit draft from the saved config source."""
        if not self._edit_mode:
            self.action_manual_refresh()
            return
        if self._edit_dirty:
            self.push_screen(
                ConfigEditExitModal(
                    "Reload saved workflow configuration?",
                    "Unsaved edit changes will be discarded unless you save before reloading.",
                    default_action="return",
                    save_label="Save and reload",
                    discard_label="Discard and reload",
                ),
                self._handle_config_edit_reload_choice,
            )
            return
        self._reload_config_edit()

    def _handle_config_edit_reload_choice(self, action: Optional[str]) -> None:
        if action == "discard":
            self._reload_config_edit()
        elif action == "save":
            self._after_config_edit_save = "reload"
            self.action_save_config_edit()

    def _reload_config_edit(self) -> None:
        selected_id = None
        node = self.tree_root_widget.cursor_node
        if node and node.data:
            selected_id = node.data.get("id")
        self._last_resource_config_snapshots = None
        self._cancel_config_edit_validation()
        self._show_config_edit_loading()
        logger.info("Reloading workflow config edit state")
        self.run_worker(
            lambda: self._load_config_edit_state_worker(selected_id),
            thread=True,
            name="reload_config_edit_state",
        )

    def action_quit(self) -> None:
        """Quit the app, confirming first if a config edit draft is dirty."""
        if self._edit_mode and self._edit_dirty:
            self.push_screen(
                ConfigEditExitModal(
                    "Quit manage with unsaved config edits?",
                    self._config_edit_exit_status_message(),
                    default_action=self._default_config_edit_exit_action(),
                    save_label="Save and quit",
                    discard_label="Discard and quit",
                ),
                lambda action: self._handle_config_edit_exit_choice(action, quit_after=True),
            )
            return
        self.exit()

    def _config_edit_exit_status_message(self) -> str:
        count = self._config_edit_validation_issue_count()
        if count:
            return f"Validation still reports {count} issue{'s' if count != 1 else ''}. You can save anyway, discard, or return."
        return "No validation errors are currently reported. You can save, discard, or return."

    def _config_edit_validation_issue_count(self) -> int:
        validation = (self._edit_state or {}).get("validation") or {}
        diagnostics = validation.get("diagnostics") or []
        errors = validation.get("errors") or []
        if validation.get("valid") is False or diagnostics or errors:
            return len(diagnostics) or len(errors) or 1
        return 0

    def _default_config_edit_exit_action(self) -> str:
        validation = (self._edit_state or {}).get("validation") or {}
        if validation.get("valid") is False or validation.get("diagnostics") or validation.get("errors"):
            return "return"
        return "save"

    def _handle_config_edit_exit_choice(self, action: Optional[str], quit_after: bool = False) -> None:
        if action == "discard":
            if quit_after:
                self.exit()
            else:
                self._discard_config_edit()
        elif action == "save":
            self._after_config_edit_save = "quit" if quit_after else "exit"
            self.action_save_config_edit()

    def _discard_config_edit(self) -> None:
        """Discard the current edit session and restore the live resource tree."""
        collapsed_ids_after_edit = self._resource_collapsed_ids_after_edit()
        self._edit_mode = False
        self._edit_loading = False
        self._edit_state = None
        self._edit_draft_yaml = None
        self._edit_dirty = False
        self._edit_value_mode = EDIT_MODE_ALL
        self._edit_status_mode = EDIT_MODE_ALL
        self._edit_field_visibility = FIELD_VISIBILITY_ESSENTIAL
        self._after_config_edit_save = None
        self._cancel_config_edit_validation()
        self._set_resource_value_mode(EDIT_MODE_ALL)
        self._restore_resource_collapsed_ids_on_next_render = collapsed_ids_after_edit
        self._resource_collapsed_ids_before_edit = None
        self.query_one("#edit-help", Static).display = False
        self.action_manual_refresh()

    def _expand_changed_resource_nodes(self, sections) -> None:
        if self._resource_view and hasattr(self._tree_state, "expand_config_differences"):
            self._tree_state.expand_config_differences(sections)

    def _changed_resource_expansion_ids(self, sections) -> set[str]:
        if self._resource_view and hasattr(self._tree_state, "config_difference_expansion_ids"):
            return set(self._tree_state.config_difference_expansion_ids(sections))
        return set()

    def _edit_expansion_state_for_render(self, edit_state: Optional[Dict[str, Any]] = None) -> Dict[str, bool]:
        current = self._tree_expansion_state()
        if any(node_id.startswith("edit:") for node_id in current):
            return current
        return self._resource_expansion_state_for_edit(current, edit_state or self._edit_state or {})

    def _tree_expansion_state(self) -> Dict[str, bool]:
        state = {}
        stack = list(self.tree_root_widget.root.children)
        while stack:
            node = stack.pop()
            data = node.data if isinstance(node.data, dict) else {}
            node_id = data.get("id")
            if node_id and node.children:
                state[str(node_id)] = bool(node.is_expanded)
            stack.extend(node.children)
        return state

    def _resource_expansion_state_for_edit(
        self,
        resource_state: Dict[str, bool],
        edit_state: Dict[str, Any],
    ) -> Dict[str, bool]:
        mapped: Dict[str, bool] = {}
        for source_id, target_id in EDIT_ID_BY_TREE_ID.items():
            if source_id in resource_state:
                mapped[target_id] = resource_state[source_id]

        edit_ids_by_resource_name = self._edit_ids_by_resource_name(edit_state)
        for source_id, expanded in resource_state.items():
            if not source_id.startswith(RESOURCE_ID_PREFIX):
                continue
            name = source_id[len(RESOURCE_ID_PREFIX):]
            for edit_id in edit_ids_by_resource_name.get(name, []):
                mapped.setdefault(edit_id, expanded)
        return mapped

    def _resource_collapsed_ids_after_edit(self) -> set[str]:
        """Map edit-tree expansion back onto resource-tree ids for returning to status."""
        collapsed = set(self._resource_collapsed_ids_before_edit or set())
        current = self._tree_expansion_state()
        if not any(node_id.startswith("edit:") for node_id in current):
            return collapsed

        for edit_id, expanded in current.items():
            for resource_id in self._resource_tree_ids_for_edit_id(edit_id):
                if expanded:
                    collapsed.discard(resource_id)
                else:
                    collapsed.add(resource_id)
        return collapsed

    def _resource_tree_ids_for_edit_id(self, edit_id: str) -> set[str]:
        ids = {
            source_id
            for source_id, target_id in EDIT_ID_BY_TREE_ID.items()
            if target_id == edit_id
        }
        node = self._find_edit_node_by_id((self._edit_state or {}).get("nodes") or [], edit_id)
        if node:
            path = tuple(str(part) for part in (node.get("path") or []))
            if self._is_edit_resource_path(path):
                ids.update(
                    f"{RESOURCE_ID_PREFIX}{name}"
                    for name in self._edit_resource_names(node, path)
                )
        return ids

    @staticmethod
    def _edit_ids_by_resource_name(edit_state: Dict[str, Any]) -> Dict[str, list[str]]:
        result: Dict[str, list[str]] = {}
        stack = list(edit_state.get("nodes") or [])
        while stack:
            node = stack.pop()
            path = tuple(str(part) for part in (node.get("path") or []))
            if WorkflowTreeApp._is_edit_resource_path(path):
                node_id = str(node.get("id"))
                for name in WorkflowTreeApp._edit_resource_names(node, path):
                    result.setdefault(name, []).append(node_id)
            stack.extend(node.get("children") or [])
        return result

    @staticmethod
    def _is_edit_resource_path(path: tuple[str, ...]) -> bool:
        for collection_path in EDIT_RESOURCE_COLLECTION_PATHS:
            if len(path) == len(collection_path) + 1 and path[:len(collection_path)] == collection_path:
                return True
        return False

    @staticmethod
    def _edit_resource_names(node: Dict[str, Any], path: tuple[str, ...]) -> set[str]:
        names = {path[-1]}
        label = strip_status_badge(str(node.get("label") or "")).strip()
        if label:
            names.add(label)
            if ":" in label:
                names.add(label.split(":", 1)[1].strip())
        return {name for name in names if name}

    def _collapsed_tree_ids(self) -> set[str]:
        collapsed = set()
        stack = list(self.tree_root_widget.root.children)
        while stack:
            node = stack.pop()
            data = node.data if isinstance(node.data, dict) else {}
            node_id = data.get("id")
            if node_id and node.children and not node.is_expanded:
                collapsed.add(str(node_id))
            stack.extend(node.children)
        return collapsed

    def _restore_collapsed_tree_ids(self, collapsed_ids: set[str]) -> None:
        stack = list(self.tree_root_widget.root.children)
        while stack:
            node = stack.pop()
            data = node.data if isinstance(node.data, dict) else {}
            node_id = data.get("id")
            if node_id and node.children and str(node_id) in collapsed_ids:
                node.collapse()
            stack.extend(node.children)

    def action_save_config_edit(self) -> None:
        """Save the current edit draft back to the workflow config store."""
        if not self._edit_mode or self._edit_draft_yaml is None:
            return
        self.run_worker(self._save_config_edit_worker, thread=True, name="save_config_edit")

    def _save_config_edit_worker(self) -> None:
        try:
            service = self._config_edit_service_or_default()
            message = service.save_raw_yaml(self._edit_draft_yaml or "")
            self.call_from_thread(self._handle_config_edit_saved, message)
        except Exception as e:
            logger.exception("Failed to save config edit draft")
            self.call_from_thread(self._handle_config_edit_save_failed, e)

    def _handle_config_edit_saved(self, message: str) -> None:
        after_save = self._after_config_edit_save
        self._after_config_edit_save = None
        self._edit_dirty = False
        self.update_pod_status()
        self.notify(message or "Configuration saved")
        if after_save == "exit":
            self._discard_config_edit()
        elif after_save == "quit":
            self.exit()
        elif after_save == "submit":
            self._discard_config_edit()
            self._start_submit_workflow()
        elif after_save == "reload":
            self._reload_config_edit()

    def _handle_config_edit_save_failed(self, error: Exception) -> None:
        self._after_config_edit_save = None
        self.notify(f"Save failed: {error}", severity="error")

    def action_show_config_edit_help(self) -> None:
        node = selected_edit_node(self.tree_root_widget)
        if node:
            description = node.get("description") or node.get("descriptionShort") or "No field description available"
            self.notify(description, timeout=8)

    def _update_edit_help(self) -> None:
        help_panel = self.query_one("#edit-help", Static)
        update_help_panel(help_panel, selected_edit_node(self.tree_root_widget), self._edit_status_mode)

    def action_cycle_config_value_mode(self) -> None:
        self._edit_value_mode = self._next_edit_mode(self._edit_value_mode)
        self._rerender_config_edit_state()

    def action_cycle_config_status_mode(self) -> None:
        self._edit_status_mode = self._next_edit_mode(self._edit_status_mode)
        self._rerender_config_edit_state()

    def action_cycle_config_field_visibility(self) -> None:
        self._edit_field_visibility = self._next_field_visibility(self._edit_field_visibility)
        self._rerender_config_edit_state()

    @staticmethod
    def _next_edit_mode(current: str) -> str:
        try:
            index = EDIT_MODES.index(current)
        except ValueError:
            index = 0
        return EDIT_MODES[(index + 1) % len(EDIT_MODES)]

    @staticmethod
    def _next_field_visibility(current: str) -> str:
        try:
            index = FIELD_VISIBILITY_MODES.index(current)
        except ValueError:
            index = 0
        return FIELD_VISIBILITY_MODES[(index + 1) % len(FIELD_VISIBILITY_MODES)]

    def _next_field_visibility_description(self) -> str:
        next_mode = self._next_field_visibility(self._edit_field_visibility)
        label = FIELD_VISIBILITY_LABELS.get(next_mode, next_mode)
        if next_mode == FIELD_VISIBILITY_ESSENTIAL:
            return f"Show {label}"
        return f"Show {label} Fields"

    def _rerender_config_edit_state(self) -> None:
        if not self._edit_mode or self._edit_state is None:
            return
        selected_id = None
        node = self.tree_root_widget.cursor_node
        if node and node.data:
            selected_id = node.data.get("id")
        render_edit_state(
            self.tree_root_widget,
            self._edit_state,
            self._edit_value_mode,
            self._edit_status_mode,
            self._edit_field_visibility,
            expansion_state=self._edit_expansion_state_for_render(self._edit_state),
        )
        if selected_id:
            self.call_after_refresh(lambda: self._restore_config_edit_selection(selected_id))
        else:
            self._focus_config_edit_tree()
            self._update_edit_help()
            self.update_pod_status()
            self._update_dynamic_bindings()

    def action_next_config_variant(self) -> None:
        self._cycle_config_variant(1)

    def action_previous_config_variant(self) -> None:
        self._cycle_config_variant(-1)

    def _cycle_config_variant(self, delta: int) -> None:
        node = selected_edit_node(self.tree_root_widget)
        if not node or node.get("valueKind") != "union":
            if delta > 0:
                self.action_expand_node()
            else:
                self.action_collapse_node()
            return
        variants = node.get("variants") or []
        values = [variant.get("value") for variant in variants]
        if not values:
            return
        try:
            current_index = values.index(node.get("value"))
        except ValueError:
            current_index = 0
        next_value = values[(current_index + delta) % len(values)]
        self._apply_config_edit_operation({
            "op": "set",
            "path": node.get("path"),
            "value": next_value,
        }, selected_id=node.get("id"))

    def action_toggle_config_boolean(self) -> None:
        node = selected_edit_node(self.tree_root_widget)
        if not node or node.get("valueKind") != "boolean":
            return
        self._apply_config_edit_operation({
            "op": "set",
            "path": node.get("path"),
            "value": not bool(node.get("value")),
        }, selected_id=node.get("id"))

    def action_edit_selected_config_node(self) -> None:
        node = selected_edit_node(self.tree_root_widget)
        if not node:
            return
        self._edit_config_node(node)

    def _edit_config_node(self, node: Dict, discard_path_on_cancel: Optional[list[str]] = None) -> None:
        kind = node.get("valueKind")
        if kind == "command" and node.get("id", "").endswith(":add"):
            command = node.get("command") or {}
            blocked_message = str(command.get("blockedMessage") or "")
            if blocked_message:
                self.notify(blocked_message, severity="warning", timeout=8)
                return
            if command.get("requiresName") is False:
                if command.get("autoEditAdded", True):
                    added_id, added_path = self._array_add_auto_edit_target(node)
                else:
                    added_id, added_path = None, None
                self._apply_config_edit_operation({
                    "op": "add",
                    "path": node.get("path"),
                    "value": {},
                }, post_apply_edit_id=added_id, discard_path_on_cancel=added_path)
                return
            label = str(node.get("label", "+ Add")).replace("[OK] ", "")
            self.push_screen(
                TextInputModal(
                    f"{label} name",
                    documentation=self._edit_node_documentation(node),
                    validation=self._edit_node_validation(node),
                    required=True,
                ),
                lambda value: self._handle_add_config_name(node, value),
            )
        elif node.get("externalRef"):
            self._show_external_resource_picker(node, discard_path_on_cancel=discard_path_on_cancel)
            return
        elif kind == "scalar":
            input_hint = node.get("inputHint") or {}
            options = input_hint.get("options") or []
            blocked_reference_message = self._blocked_reference_choice_message(node, input_hint, options)
            if blocked_reference_message:
                self.notify(blocked_reference_message, severity="warning", timeout=8)
                return
            if self._should_use_reference_choice_modal(input_hint, options):
                choices = self._choices_with_unset(node, options)
                self.push_screen(
                    ChoiceSelectModal(
                        f"Select {'.'.join(node.get('path', []))}",
                        choices,
                        node.get("value"),
                        documentation=self._edit_node_documentation(node, include_input_hint=True),
                    ),
                    lambda value: self._handle_scalar_config_value(
                        node,
                        value,
                        discard_path_on_cancel=discard_path_on_cancel,
                    ),
                )
                return
            self._show_scalar_config_text_input(node, discard_path_on_cancel=discard_path_on_cancel)
        elif kind == "boolean":
            self._show_boolean_config_picker(node, discard_path_on_cancel=discard_path_on_cancel)
        elif kind == "union":
            self._show_config_variant_picker(node, discard_path_on_cancel=discard_path_on_cancel)
        elif kind in {"object", "array"} and not node.get("children"):
            self._show_structured_config_editor(node, discard_path_on_cancel=discard_path_on_cancel)
        else:
            tree = self.tree_root_widget
            if tree.cursor_node:
                if tree.cursor_node.is_expanded:
                    tree.cursor_node.collapse()
                else:
                    tree.cursor_node.expand()

    def _show_scalar_config_text_input(
        self,
        node: Dict,
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        modal = TextInputModal(
            f"Edit {'.'.join(node.get('path', []))}",
            str(node.get("value") or ""),
            documentation=self._edit_node_documentation(node),
            validation=self._edit_node_validation(node),
            required=bool(node.get("required")),
            clear_allowed=self._config_node_can_unset(node),
            clear_label="Clear",
            on_change=lambda value, locally_valid: self._schedule_scalar_config_validation(
                node,
                value,
                modal,
                locally_valid,
            ),
            regex_help=self._edit_node_regex_help(node),
        )
        self.push_screen(
            modal,
            lambda value: self._handle_scalar_config_value(
                node,
                value,
                discard_path_on_cancel=discard_path_on_cancel,
            ),
        )

    def _show_structured_config_editor(
        self,
        node: Dict,
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        kind = str(node.get("valueKind") or "object")
        self.push_screen(
            StructuredValueModal(
                f"Edit {'.'.join(node.get('path', []))}",
                self._structured_config_initial_text(node),
                documentation=self._edit_node_documentation(node),
                expected_kind=kind,
                clear_allowed=self._config_node_can_unset(node),
                clear_label="Clear",
            ),
            lambda value: self._handle_structured_config_value(
                node,
                value,
                discard_path_on_cancel=discard_path_on_cancel,
            ),
        )

    @staticmethod
    def _structured_config_initial_text(node: Dict) -> str:
        value = node.get("value")
        if value is None or value == "":
            return "[]\n" if node.get("valueKind") == "array" else "{}\n"
        return yaml.safe_dump(value, sort_keys=False)

    def _handle_structured_config_value(
        self,
        node: Dict,
        value: Optional[Any],
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        if value is None:
            self._discard_config_edit_added_item(discard_path_on_cancel)
            return
        if value is CLEAR_VALUE:
            self._unset_config_node(node)
            return
        self._cancel_config_edit_validation()
        self._apply_config_edit_operation({
            "op": "set",
            "path": node.get("path"),
            "value": value,
        }, selected_id=node.get("id"))

    def _show_external_resource_picker(
        self,
        node: Dict,
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        external_ref = node.get("externalRef") or {}
        self.run_worker(
            lambda: self._load_external_resource_picker_worker(node, external_ref, discard_path_on_cancel),
            thread=True,
            name="load_external_resource_picker",
        )

    def _load_external_resource_picker_worker(
        self,
        node: Dict,
        external_ref: Dict,
        discard_path_on_cancel: Optional[list[str]],
    ) -> None:
        try:
            service = self._config_edit_service_or_default()
            if not hasattr(service, "list_external_resources"):
                self.call_from_thread(self._show_scalar_config_text_input, node, discard_path_on_cancel)
                return
            rows = service.list_external_resources(external_ref, node.get("value"))
            self.call_from_thread(self._open_external_resource_picker, node, rows, discard_path_on_cancel)
        except Exception as e:
            logger.exception("Failed to list external resources")
            self.call_from_thread(self.notify, f"External resource picker unavailable: {e}", severity="error")

    def _open_external_resource_picker(
        self,
        node: Dict,
        rows: list[Dict],
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        external_ref = node.get("externalRef") or {}
        title = f"Select {external_ref.get('displayName') or '.'.join(node.get('path', []))}"
        self.push_screen(
            ExternalResourcePickerModal(
                title,
                rows,
                self._external_resource_current_value(node),
                documentation=self._edit_node_documentation(node),
                can_create=bool(external_ref.get("create")),
                external_ref=external_ref,
                clear_allowed=self._config_node_can_unset(node),
            ),
            lambda choice: self._handle_external_resource_picker_choice(
                node,
                choice,
                discard_path_on_cancel=discard_path_on_cancel,
            ),
        )

    def _handle_external_resource_picker_choice(
        self,
        node: Dict,
        choice: Optional[Dict],
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        if not choice:
            self._discard_config_edit_added_item(discard_path_on_cancel)
            return
        action = choice.get("action")
        if action == "create":
            self._open_external_resource_form(node, "create", return_to_picker=True)
            return
        if action == "clear":
            self._unset_config_node(node)
            return
        row = choice.get("row") or {}
        if action == "select":
            self._select_external_resource_row(node, row)
        elif action == "update":
            self._open_external_resource_form_for_row(node, row, return_to_picker=True)

    def _select_external_resource_row(self, node: Dict, row: Dict) -> None:
        name = row.get("name")
        if not name:
            return
        if row.get("status") == "warn":
            message = f"Use {name} anyway?"
            if row.get("message"):
                message += f"\n\n{row.get('message')}"
            self.push_screen(
                ConfirmModal(message, confirm_label="Use", cancel_label="Cancel", default_confirm=False),
                lambda confirmed: self._apply_external_resource_row(node, row) if confirmed else None,
            )
            return
        self._apply_external_resource_row(node, row)

    def _external_resource_current_value(self, node: Dict):
        value = node.get("value")
        if isinstance(value, dict):
            return str(value.get("name") or "")
        return value

    def _apply_external_resource_row(self, node: Dict, row: Dict) -> None:
        value = self._external_resource_value_for_row(node.get("externalRef") or {}, row)
        self._apply_external_resource_value(node, value)

    @staticmethod
    def _external_resource_value_for_row(external_ref: Dict, row: Dict):
        selection = external_ref.get("selection") or {}
        if selection.get("target") == "objectRef":
            value = {
                selection.get("nameField") or "name": row.get("name"),
                selection.get("kindField") or "kind": row.get("kind"),
            }
            group = row.get("group")
            if group:
                value[selection.get("groupField") or "group"] = group
            return value
        return row.get("name")

    def _apply_external_resource_value(self, node: Dict, value) -> None:
        if isinstance(value, dict):
            self._cancel_config_edit_validation()
            self._apply_config_edit_operation({
                "op": "set",
                "path": node.get("path"),
                "value": value,
            }, selected_id=node.get("id"))
            return
        self._handle_scalar_config_value(node, value)

    def _open_external_resource_form_for_row(self, node: Dict, row: Dict, return_to_picker: bool = False) -> None:
        self.run_worker(
            lambda: self._read_external_resource_worker(node, row, return_to_picker),
            thread=True,
            name="read_external_resource",
        )

    def _read_external_resource_worker(self, node: Dict, row: Dict, return_to_picker: bool) -> None:
        try:
            service = self._config_edit_service_or_default()
            if not hasattr(service, "read_external_resource"):
                raise RuntimeError("reading external resources is not implemented")
            resource = service.read_external_resource(node.get("externalRef") or {}, str(row.get("name") or ""))
            mode = "create" if resource.get("missing") else "update"
            if resource.get("missing") and resource.get("message"):
                self.call_from_thread(self.notify, str(resource.get("message")), severity="warning")
            self.call_from_thread(self._open_external_resource_form, node, mode, resource, return_to_picker)
        except Exception as e:
            logger.exception("Failed to read external resource")
            self.call_from_thread(self.notify, f"External resource read failed: {e}", severity="error")

    def _open_external_resource_form(
        self,
        node: Dict,
        mode: str,
        resource: Optional[Dict] = None,
        return_to_picker: bool = False,
    ) -> None:
        external_ref = node.get("externalRef") or {}
        if not external_ref.get("create"):
            self.notify("Create/update is not available for this reference", severity="warning")
            return
        initial_values = values_for_form(external_ref, resource)
        self.push_screen(
            ExternalResourceFormModal(
                external_ref,
                mode,
                initial_values=initial_values,
                existing_keys=resource.get("keys") if resource else None,
                documentation=self._edit_node_documentation(node),
                notice=_external_resource_form_notice(resource),
            ),
            lambda values: self._handle_external_resource_form(node, mode, resource, values, return_to_picker),
        )

    def _handle_external_resource_form(
        self,
        node: Dict,
        mode: str,
        resource: Optional[Dict],
        values: Optional[Dict[str, str]],
        return_to_picker: bool = False,
    ) -> None:
        if values is None:
            if return_to_picker:
                self._show_external_resource_picker(node)
            return
        existing_name = resource.get("name") if resource and mode != "create" and not resource.get("missing") else None
        self.run_worker(
            lambda: self._save_external_resource_worker(node, values, existing_name),
            thread=True,
            name="save_external_resource",
        )

    def _save_external_resource_worker(
        self,
        node: Dict,
        values: Dict[str, str],
        existing_name: Optional[str],
    ) -> None:
        try:
            service = self._config_edit_service_or_default()
            if not hasattr(service, "save_external_resource"):
                raise RuntimeError("saving external resources is not implemented")
            result = service.save_external_resource(node.get("externalRef") or {}, values, existing_name=existing_name)
            self.call_from_thread(self._handle_external_resource_saved, node, result)
        except Exception as e:
            logger.exception("Failed to save external resource")
            self.call_from_thread(self.notify, f"External resource save failed: {e}", severity="error")

    def _handle_external_resource_saved(self, node: Dict, result: Dict[str, str]) -> None:
        name = result.get("name")
        if result.get("message"):
            self.notify(result["message"])
        if name:
            self._apply_external_resource_value(node, name)

    def _show_config_variant_picker(
        self,
        node: Dict,
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        variants = node.get("variants") or []
        if not variants:
            return
        path = ".".join(str(part) for part in node.get("path", []))
        self.push_screen(
            ChoiceSelectModal(
                f"Select {path}",
                variants,
                node.get("value"),
                documentation=self._edit_node_documentation(node),
            ),
            lambda value: self._handle_config_variant_choice(
                node,
                value,
                discard_path_on_cancel=discard_path_on_cancel,
            ),
        )

    @staticmethod
    def _should_use_reference_choice_modal(input_hint: Dict, options: list[Dict[str, Any]]) -> bool:
        return input_hint.get("kind") == "reference" and bool(options)

    @staticmethod
    def _blocked_reference_choice_message(node: Dict, input_hint: Dict, options: list[Dict[str, Any]]) -> str:
        if input_hint.get("kind") != "reference" or options or input_hint.get("allowCustom"):
            return ""
        return str(
            input_hint.get("message")
            or node.get("description")
            or "No choices are available for this field."
        )

    def _show_boolean_config_picker(
        self,
        node: Dict,
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        choices = self._choices_with_unset(node, [
            {"label": "true", "value": True},
            {"label": "false", "value": False},
        ])
        path = ".".join(str(part) for part in node.get("path", []))
        self.push_screen(
            ChoiceSelectModal(
                f"Select {path}",
                choices,
                node.get("value"),
                documentation=self._edit_node_documentation(node),
            ),
            lambda value: self._handle_boolean_config_value(
                node,
                value,
                discard_path_on_cancel=discard_path_on_cancel,
            ),
        )

    def _handle_boolean_config_value(
        self,
        node: Dict,
        value,
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        if value is None:
            self._discard_config_edit_added_item(discard_path_on_cancel)
            return
        if value == node.get("value"):
            return
        if value is CLEAR_VALUE:
            self._unset_config_node(node)
            return
        self._apply_config_edit_operation({
            "op": "set",
            "path": node.get("path"),
            "value": bool(value),
        }, selected_id=node.get("id"))

    def _choices_with_unset(self, node: Dict, choices: list[Dict[str, Any]]) -> list[Dict[str, Any]]:
        if not self._config_node_can_unset(node):
            return choices
        return [
            {"label": "unset", "value": CLEAR_VALUE, "description": "Remove this optional value."},
            *choices,
        ]

    def _unset_config_node(self, node: Dict) -> None:
        self._cancel_config_edit_validation()
        operation = {
            "op": "unset",
            "path": node.get("path"),
        }
        self._apply_config_edit_operation_with_destructive_confirm(
            operation,
            selected_id=node.get("id"),
        )

    @staticmethod
    def _config_node_can_unset(node: Dict) -> bool:
        return (
            node.get("presence") == "optional"
            and not node.get("required")
            and node.get("valueKind") in {"scalar", "boolean", "object", "array", "union"}
        )

    @staticmethod
    def _config_node_can_clear_required_union(node: Dict) -> bool:
        return (
            node.get("valueKind") == "union"
            and node.get("presence") == "required"
            and node.get("value") not in (None, "", "unset")
        )

    def _handle_config_variant_choice(
        self,
        node: Dict,
        value,
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        if value is None:
            self._discard_config_edit_added_item(discard_path_on_cancel)
            return
        if value == node.get("value"):
            return
        if value is CLEAR_VALUE:
            self._unset_config_node(node)
            return
        self._apply_config_edit_operation({
            "op": "set",
            "path": node.get("path"),
            "value": value,
        },
            selected_id=node.get("id"),
            auto_edit_required_child=True,
            discard_path_on_cancel=discard_path_on_cancel,
        )

    def _handle_add_config_name(self, node: Dict, value: Optional[str]) -> None:
        name = (value or "").strip()
        if not name:
            return
        self._cancel_config_edit_validation()
        added_path = [str(part) for part in (node.get("path") or [])] + [name]
        command = node.get("command") or {}
        added_id = self._edit_id_for_path(added_path)
        auto_edit_added = command.get("autoEditAdded", True)
        self._apply_config_edit_operation({
            "op": "add",
            "path": node.get("path"),
            "value": {"name": name},
        },
            selected_id=added_id,
            post_apply_edit_id=added_id if added_id and auto_edit_added else None,
            discard_path_on_cancel=added_path if command.get("editAdded") else None,
        )

    def _handle_scalar_config_value(
        self,
        node: Dict,
        value: Optional[Any],
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        if value is None:
            self._discard_config_edit_added_item(discard_path_on_cancel)
            return
        if value is CLEAR_VALUE:
            self._unset_config_node(node)
            return
        try:
            value = self._coerce_config_scalar_value(node, value)
        except ValueError as e:
            self.notify(str(e), severity="error")
            return
        self._cancel_config_edit_validation()
        operation = {
            "op": "set",
            "path": node.get("path"),
            "value": value,
        }
        self._apply_config_edit_operation_with_destructive_confirm(
            operation,
            selected_id=node.get("id"),
        )

    @staticmethod
    def _coerce_config_scalar_value(node: Dict, value: Any) -> Any:
        if node.get("valueType") != "number":
            return value
        text = str(value).strip()
        if not text:
            return value
        try:
            return float(text) if any(part in text.lower() for part in (".", "e")) else int(text)
        except ValueError as e:
            path = ".".join(str(part) for part in node.get("path", [])) or "value"
            raise ValueError(f"{path} must be a number.") from e

    def _schedule_scalar_config_validation(
        self,
        node: Dict,
        value: str,
        modal: TextInputModal,
        locally_valid: bool,
    ) -> None:
        self._edit_validation_generation += 1
        generation = self._edit_validation_generation
        self._stop_config_edit_validation_timer()
        if not locally_valid or self._edit_draft_yaml is None:
            modal.set_remote_validation("")
            return
        modal.set_remote_validation("Checking full config...", "pending")
        self._edit_validation_timer = self.set_timer(
            self._edit_validation_delay,
            lambda: self._start_scalar_config_validation(node, value, modal, generation),
        )

    def _start_scalar_config_validation(
        self,
        node: Dict,
        value: str,
        modal: TextInputModal,
        generation: int,
    ) -> None:
        if generation != self._edit_validation_generation or self._edit_draft_yaml is None:
            return
        try:
            operation_value = self._coerce_config_scalar_value(node, value)
        except ValueError as e:
            modal.set_remote_validation(str(e), "error")
            return
        operation = {
            "op": "set",
            "path": node.get("path"),
            "value": operation_value,
        }
        raw_yaml = self._edit_draft_yaml or ""
        self.run_worker(
            lambda: self._validate_config_edit_operation_worker(raw_yaml, operation, generation, node.get("path") or [], modal),
            thread=True,
            name="validate_config_edit_operation",
        )

    def _validate_config_edit_operation_worker(
        self,
        raw_yaml: str,
        operation: Dict,
        generation: int,
        path: list[str],
        modal: TextInputModal,
    ) -> None:
        try:
            service = self._config_edit_service_or_default()
            if hasattr(service, "validate_operation"):
                result = service.validate_operation(raw_yaml, operation)
            else:
                result = service.apply_operation(raw_yaml, operation)
            self.call_from_thread(self._handle_config_edit_validation_result, result, generation, path, modal)
        except Exception as e:
            logger.exception("Failed to validate config edit operation")
            self.call_from_thread(self._handle_config_edit_validation_error, e, generation, modal)

    def _handle_config_edit_validation_result(
        self,
        result,
        generation: int,
        path: list[str],
        modal: TextInputModal,
    ) -> None:
        if generation != self._edit_validation_generation or self.screen is not modal:
            return
        edit_state = getattr(result, "edit_state", None) or result["edit_state"]
        diagnostic = self._diagnostic_for_path(edit_state, path)
        if diagnostic:
            modal.set_remote_validation(str(diagnostic.get("message") or ""), str(diagnostic.get("severity") or "error"))
        else:
            modal.set_remote_validation("No validation issues found.", "ok")

    def _handle_config_edit_validation_error(
        self,
        error: Exception,
        generation: int,
        modal: TextInputModal,
    ) -> None:
        if generation != self._edit_validation_generation or self.screen is not modal:
            return
        modal.set_remote_validation(f"Validation unavailable: {error}", "warning")

    def _cancel_config_edit_validation(self) -> None:
        self._edit_validation_generation += 1
        self._stop_config_edit_validation_timer()

    def _stop_config_edit_validation_timer(self) -> None:
        timer = self._edit_validation_timer
        self._edit_validation_timer = None
        if timer is not None and hasattr(timer, "stop"):
            try:
                timer.stop()
            except Exception:
                pass

    @classmethod
    def _diagnostic_for_path(cls, edit_state: Dict, path: list[str]) -> Optional[Dict]:
        node = cls._find_edit_node_by_path(edit_state.get("nodes") or [], path)
        for diagnostic in (node or {}).get("diagnostics") or []:
            if cls._paths_equal(diagnostic.get("path") or path, path):
                return diagnostic
        validation = edit_state.get("validation") or {}
        for diagnostic in validation.get("diagnostics") or []:
            if cls._paths_equal(diagnostic.get("path") or [], path):
                return diagnostic
        return None

    @classmethod
    def _find_edit_node_by_path(cls, nodes, path: list[str]) -> Optional[Dict]:
        stack = list(nodes or [])
        while stack:
            node = stack.pop()
            if cls._paths_equal(node.get("path") or [], path):
                return node
            stack.extend(node.get("children") or [])
        return None

    @staticmethod
    def _paths_equal(left, right) -> bool:
        return [str(part) for part in left] == [str(part) for part in right]

    def action_remove_config_node(self) -> None:
        target = self._nearest_config_delete_target()
        if not target or target[0] != "remove":
            return
        node = target[1]
        path = node.get("path") or []
        label = strip_status_badge(str(node.get("label") or ".".join(path))).strip()
        operation = {
            "op": "removeConfig",
            "path": path,
        }
        destructive_message = self._destructive_config_operation_message(operation)
        if destructive_message:
            self.push_screen(
                ConfirmModal(
                    destructive_message,
                    confirm_label="Remove",
                    cancel_label="Cancel",
                    default_confirm=False,
                ),
                lambda confirmed: self._apply_config_edit_operation(
                    operation,
                    selected_id=self._edit_id_for_path(path),
                ) if confirmed else None,
            )
            return
        self.push_screen(
            ConfirmModal(f"Remove config entry '{label}' from pending YAML?"),
            lambda confirmed: self._remove_config_node(path) if confirmed else None,
        )

    def action_clear_config_node(self) -> None:
        target = self._nearest_config_delete_target()
        if not target or target[0] != "clear":
            return
        self._unset_config_node(target[1])

    def action_rename_config_node(self) -> None:
        node = self._nearest_config_rename_target()
        if not node:
            return
        path = [str(part) for part in (node.get("path") or [])]
        if not path:
            return
        current_name = path[-1]
        label = strip_status_badge(str(node.get("label") or current_name)).strip()
        self.push_screen(
            TextInputModal(
                f"Rename {label}",
                current_name,
                documentation=(
                    "Rename this config entry and update any workflow references that point to it."
                ),
                validation=self._rename_config_validation(path),
                required=True,
            ),
            lambda value: self._handle_rename_config_name(node, value),
        )

    def _handle_rename_config_name(self, node: Dict, value: Optional[str]) -> None:
        if value is None:
            return
        new_name = str(value or "").strip()
        path = [str(part) for part in (node.get("path") or [])]
        if not new_name or not path or new_name == path[-1]:
            return
        self._cancel_config_edit_validation()
        new_path = [*path[:-1], new_name]
        self._apply_config_edit_operation({
            "op": "renameConfig",
            "path": path,
            "newName": new_name,
        }, selected_id=self._edit_id_for_path(new_path))

    @staticmethod
    def _rename_config_validation(path: list[str]) -> Optional[Dict[str, str]]:
        if (
            (len(path) == 3 and path[:2] == ["traffic", "kafkaClusters"])
            or (len(path) == 3 and path[:2] in (
                ["traffic", "proxies"],
                ["traffic", "s3Sources"],
                ["traffic", "replayers"],
            ))
        ):
            return {
                "pattern": r"^[a-z0-9]([-a-z0-9]*[a-z0-9])?(\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*$",
                "message": "Use a valid Kubernetes DNS name: lowercase letters, numbers, '-' or '.', starting and ending with an alphanumeric character.",
            }
        return None

    @staticmethod
    def _is_removable_config_path(path: list[str]) -> bool:
        if len(path) >= 2:
            try:
                if int(str(path[-1])) >= 0 and str(path[-1]).isdigit():
                    return True
            except ValueError:
                pass
        if len(path) == 2 and path[0] in (
            "sourceClusters",
            "targetClusters",
            "snapshotMigrationConfigs",
        ):
            return True
        return len(path) == 3 and path[:2] in (
            ["traffic", "kafkaClusters"],
            ["traffic", "proxies"],
            ["traffic", "replayers"],
        )

    @staticmethod
    def _is_renameable_config_path(path: list[str]) -> bool:
        if len(path) == 2 and path[0] in (
            "sourceClusters",
            "targetClusters",
        ):
            return True
        if len(path) == 3 and path[:2] in (
            ["traffic", "kafkaClusters"],
            ["traffic", "proxies"],
            ["traffic", "s3Sources"],
            ["traffic", "replayers"],
        ):
            return True
        return len(path) == 5 and path[0] == "sourceClusters" and path[2] == "snapshotInfo" and path[3] in (
            "repos",
            "snapshots",
        )

    @classmethod
    def _is_removable_edit_node(cls, node: Optional[Dict]) -> bool:
        if not node or node.get("valueKind") == "command":
            return False
        return bool(node.get("removable")) or cls._is_removable_config_path(node.get("path") or [])

    @classmethod
    def _is_renameable_edit_node(cls, node: Optional[Dict]) -> bool:
        if not node or node.get("valueKind") == "command":
            return False
        return cls._is_renameable_config_path(node.get("path") or [])

    @staticmethod
    def _edit_node_from_tree_node(tree_node) -> Optional[Dict]:
        data = tree_node.data if tree_node else None
        if not data or data.get("type") != EDIT_NODE_TYPE:
            return None
        return data.get("edit_node") or data

    def _nearest_config_delete_target(self) -> Optional[tuple[str, Dict]]:
        tree_node = self.tree_root_widget.cursor_node
        selected = self._edit_node_from_tree_node(tree_node)
        if selected and selected.get("valueKind") == "command":
            return None
        while tree_node:
            node = self._edit_node_from_tree_node(tree_node)
            if node and node.get("valueKind") != "command":
                if self._config_node_can_unset(node):
                    return "clear", node
                if self._config_node_can_clear_required_union(node):
                    return "clear", node
                if node.get("valueKind") == "union":
                    return None
                if self._is_removable_edit_node(node):
                    return "remove", node
            tree_node = getattr(tree_node, "parent", None)
        return None

    def _nearest_config_rename_target(self) -> Optional[Dict]:
        tree_node = self.tree_root_widget.cursor_node
        selected = self._edit_node_from_tree_node(tree_node)
        if selected and selected.get("valueKind") == "command":
            return None
        while tree_node:
            node = self._edit_node_from_tree_node(tree_node)
            if self._is_renameable_edit_node(node):
                return node
            tree_node = getattr(tree_node, "parent", None)
        return None

    def _nearest_resource_reset_target(self) -> Optional[Dict[str, str]]:
        if self._edit_mode or not self._resource_view:
            return None
        tree_node = self.tree_root_widget.cursor_node
        while tree_node:
            data = tree_node.data if tree_node else None
            if isinstance(data, dict):
                target = self._resource_reset_target_from_data(data)
                if target:
                    return target
            tree_node = getattr(tree_node, "parent", None)
        return None

    @staticmethod
    def _resource_reset_target_from_data(data: Dict) -> Optional[Dict[str, str]]:
        resource_path = data.get("resource_path")
        resource_plural = data.get("resource_plural")
        resource_name = data.get("resource_name")
        if not resource_plural or not resource_name:
            parsed = parse_resource_path(str(resource_path or ""))
            if parsed:
                resource_plural, resource_name = parsed
        if not resource_path and resource_plural and resource_name:
            resource_path = resource_display_name(resource_plural, resource_name)
        if not resource_path or resource_plural not in RESETTABLE_PLURALS or not resource_name:
            return None
        return {
            "resource_path": str(resource_path),
            "resource_plural": str(resource_plural),
            "resource_name": str(resource_name),
        }

    def _resource_reset_dry_run_command_args(self, target: Dict[str, str]) -> list[str]:
        return [
            "workflow",
            "reset",
            "--namespace",
            self._namespace,
            "--cascade",
            "--include-proxies",
            "--dry-run",
            "--output",
            "json",
            target["resource_path"],
        ]

    def _resource_reset_commit_command_args(self, plan: Dict) -> list[str]:
        targets = plan.get("targets") or []
        command = [
            "workflow",
            "reset",
            "--namespace",
            self._namespace,
            "--exact",
        ]
        if any(target.get("plural") == "captureproxies" for target in targets):
            command.append("--include-proxies")
        command.extend(str(target.get("path")) for target in targets if target.get("path"))
        return command

    @staticmethod
    def _edit_node_documentation(node: Dict, include_input_hint: bool = False) -> str:
        documentation = str(node.get("description") or node.get("descriptionShort") or "")
        if include_input_hint:
            message = str((node.get("inputHint") or {}).get("message") or "")
            if message and message not in documentation:
                documentation = f"{documentation}\n\n{message}" if documentation else message
        return documentation

    @staticmethod
    def _edit_node_validation(node: Dict) -> Dict:
        validation = dict(node.get("validation") or {})
        input_hint = node.get("inputHint") or {}
        if not validation and input_hint.get("kind") == "text" and input_hint.get("pattern"):
            validation = {
                "pattern": input_hint.get("pattern"),
                "message": input_hint.get("message"),
            }
        return validation

    @staticmethod
    def _edit_node_regex_help(node: Dict) -> Optional[Dict]:
        input_hint = node.get("inputHint") or {}
        return dict(input_hint) if input_hint.get("kind") == "javaRegex" else None

    @classmethod
    def _config_edit_enter_description(cls, node: Optional[Dict]) -> str:
        if not node:
            return "Edit"
        kind = node.get("valueKind")
        if kind == "command":
            return "Add"
        if node.get("externalRef"):
            return "Pick Resource"
        if kind == "scalar":
            return "Edit Value"
        if kind == "boolean":
            return "Choose Value"
        if kind == "union":
            return "Choose Option"
        if kind in {"object", "array"} and not node.get("children"):
            return "Edit YAML"
        return "Expand"

    @staticmethod
    def _node_enter_description(node: Optional[Dict]) -> str:
        if WorkflowTreeApp._approval_node_for_action(node):
            return "Approve"
        return "Expand"

    @staticmethod
    def _approval_node_for_action(node: Optional[Dict]) -> Optional[Dict]:
        if not node:
            return None
        if is_approval_node(node) and node.get('phase') == PHASE_RUNNING:
            return node
        approval_node = node.get('approval_node')
        if isinstance(approval_node, dict) and is_approval_node(approval_node) and approval_node.get('phase') == PHASE_RUNNING:
            return approval_node
        return None

    def _remove_config_node(self, path: list[str]) -> None:
        operation = {
            "op": "removeConfig",
            "path": path,
        }
        self._apply_config_edit_operation_with_destructive_confirm(
            operation,
            selected_id=self._edit_id_for_path(path),
        )

    def _discard_config_edit_added_item(self, path: Optional[list[str]]) -> None:
        if not path:
            return
        self._cancel_config_edit_validation()
        self._remove_config_node(path)

    def _apply_config_edit_operation_with_destructive_confirm(
        self,
        operation: Dict,
        selected_id: Optional[str] = None,
        auto_edit_required_child: bool = False,
        post_apply_edit_id: Optional[str] = None,
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        message = self._destructive_config_operation_message(operation)
        if not message:
            self._apply_config_edit_operation(
                operation,
                selected_id=selected_id,
                auto_edit_required_child=auto_edit_required_child,
                post_apply_edit_id=post_apply_edit_id,
                discard_path_on_cancel=discard_path_on_cancel,
            )
            return

        self.push_screen(
            ConfirmModal(
                message,
                confirm_label="Apply",
                cancel_label="Cancel",
                default_confirm=False,
            ),
            lambda confirmed: self._apply_config_edit_operation(
                operation,
                selected_id=selected_id,
                auto_edit_required_child=auto_edit_required_child,
                post_apply_edit_id=post_apply_edit_id,
                discard_path_on_cancel=discard_path_on_cancel,
            ) if confirmed else None,
        )

    def _destructive_config_operation_message(self, operation: Dict) -> Optional[str]:
        config = self._parse_config_yaml(self._edit_draft_yaml)
        path = [str(part) for part in (operation.get("path") or [])]
        op = operation.get("op")
        if op == "set":
            removals = self._per_snapshot_entries_removed_by_source_change(
                config,
                path,
                operation.get("value"),
            )
            if removals:
                old_source = removals[0].get("source") or "<unset>"
                new_source = str(operation.get("value") or "<unset>")
                return self._destructive_config_removal_message(
                    f"Changing fromSource from '{old_source}' to '{new_source}'",
                    removals,
                )
            return None

        if op in {"removeConfig", "unset"}:
            removals = self._config_entries_removed_by_source_delete(config, path)
            if removals:
                source_name = path[1] if len(path) > 1 else "<source>"
                return self._destructive_config_removal_message(
                    f"Removing source cluster '{source_name}'",
                    removals,
                    "dependent config entries",
                )
            removals = self._per_snapshot_entries_removed_by_snapshot_delete(config, path)
            if removals:
                return self._destructive_config_removal_message(
                    "Removing this source snapshot configuration",
                    removals,
                )
        return None

    @staticmethod
    def _destructive_config_removal_message(
        action: str,
        removals: list[Dict[str, str]],
        removal_label: str = "dependent per-snapshot migration config",
    ) -> str:
        lines = [
            f"{action} will remove {removal_label}:",
            *[
                f"- {item['path']}{' (' + item['reason'] + ')' if item.get('reason') else ''}"
                for item in removals[:8]
            ],
        ]
        if len(removals) > 8:
            lines.append(f"- ... and {len(removals) - 8} more")
        lines.append("")
        lines.append("Continue?")
        return "\n".join(lines)

    @staticmethod
    def _per_snapshot_entries_removed_by_source_change(
        config: Dict[str, Any],
        path: list[str],
        new_source,
    ) -> list[Dict[str, str]]:
        if len(path) != 3 or path[0] != "snapshotMigrationConfigs" or path[2] != "fromSource":
            return []
        if not path[1].isdigit():
            return []
        migrations = config.get("snapshotMigrationConfigs")
        index = int(path[1])
        if not isinstance(migrations, list) or index >= len(migrations):
            return []
        migration = migrations[index]
        if not isinstance(migration, dict):
            return []
        old_source = migration.get("fromSource")
        if str(old_source or "") == str(new_source or ""):
            return []
        per_snapshot = migration.get("perSnapshotConfig")
        if not isinstance(per_snapshot, dict):
            return []
        return [
            {
                "source": str(old_source or ""),
                "path": f"snapshotMigrationConfigs.{index}.perSnapshotConfig.{snapshot_name}",
            }
            for snapshot_name in sorted(per_snapshot)
        ]

    @classmethod
    def _config_entries_removed_by_source_delete(
        cls,
        config: Dict[str, Any],
        path: list[str],
    ) -> list[Dict[str, str]]:
        if len(path) != 2 or path[0] != "sourceClusters":
            return []
        source_name = path[1]
        graph = cls._config_dependency_graph(config)
        source_path = ["sourceClusters", source_name]
        direct_removals = [
            edge for edge in graph
            if cls._paths_equal(edge["to_path"], source_path)
        ]
        removed_proxy_path_keys = {
            cls._config_path_key(edge["from_path"])
            for edge in direct_removals
            if edge["from_path"][:2] == ["traffic", "proxies"] and len(edge["from_path"]) == 3
        }
        transitive_replayers = [
            edge for edge in graph
            if cls._config_path_key(edge["to_path"]) in removed_proxy_path_keys
        ]
        return cls._dedupe_config_removals(direct_removals + transitive_replayers)

    @classmethod
    def _per_snapshot_entries_removed_by_snapshot_delete(
        cls,
        config: Dict[str, Any],
        path: list[str],
    ) -> list[Dict[str, str]]:
        removed = cls._source_snapshots_removed_by_path(config, path)
        if not removed:
            return []
        source_name, snapshot_names = removed
        snapshots = set(snapshot_names)
        migrations = config.get("snapshotMigrationConfigs")
        if not isinstance(migrations, list):
            return []
        snapshot_path_keys = {
            cls._config_path_key(["sourceClusters", source_name, "snapshotInfo", "snapshots", snapshot_name])
            for snapshot_name in snapshots
        }
        return cls._dedupe_config_removals([
            edge for edge in cls._config_dependency_graph(config)
            if cls._config_path_key(edge["to_path"]) in snapshot_path_keys
        ])

    @staticmethod
    def _config_path_key(path: list[str]) -> str:
        return "\0".join(str(part) for part in path)

    @staticmethod
    def _config_path_label(path: list[str]) -> str:
        return ".".join(str(part) for part in path)

    @classmethod
    def _dedupe_config_removals(cls, edges: list[Dict[str, Any]]) -> list[Dict[str, str]]:
        removals: list[Dict[str, str]] = []
        seen = set()
        for edge in edges:
            key = cls._config_path_key(edge["from_path"])
            if key in seen:
                continue
            seen.add(key)
            removals.append({
                "path": cls._config_path_label(edge["from_path"]),
                "reason": str(edge.get("reason") or ""),
            })
        return removals

    @classmethod
    def _config_dependency_graph(cls, config: Dict[str, Any]) -> list[Dict[str, Any]]:
        edges: list[Dict[str, Any]] = []

        def add(from_path, from_field_path, to_path, reason):
            edges.append({
                "from_path": [str(part) for part in from_path],
                "from_field_path": [str(part) for part in from_field_path],
                "to_path": [str(part) for part in to_path],
                "reason": str(reason),
            })

        traffic = config.get("traffic") if isinstance(config.get("traffic"), dict) else {}
        proxies = traffic.get("proxies") if isinstance(traffic.get("proxies"), dict) else {}
        s3_sources = traffic.get("s3Sources") if isinstance(traffic.get("s3Sources"), dict) else {}
        replayers = traffic.get("replayers") if isinstance(traffic.get("replayers"), dict) else {}

        migrations = config.get("snapshotMigrationConfigs")
        if isinstance(migrations, list):
            for index, migration in enumerate(migrations):
                if not isinstance(migration, dict):
                    continue
                migration_path = ["snapshotMigrationConfigs", str(index)]
                from_source = str(migration.get("fromSource") or "")
                if from_source:
                    add(migration_path, [*migration_path, "fromSource"], ["sourceClusters", from_source], f"fromSource={from_source}")
                to_target = str(migration.get("toTarget") or "")
                if to_target:
                    add(migration_path, [*migration_path, "toTarget"], ["targetClusters", to_target], f"toTarget={to_target}")
                per_snapshot = migration.get("perSnapshotConfig")
                if from_source and isinstance(per_snapshot, dict):
                    for snapshot_name in per_snapshot:
                        snapshot_path = [*migration_path, "perSnapshotConfig", str(snapshot_name)]
                        add(
                            snapshot_path,
                            snapshot_path,
                            ["sourceClusters", from_source, "snapshotInfo", "snapshots", str(snapshot_name)],
                            f"snapshot={snapshot_name}",
                        )

        for proxy_name, proxy in proxies.items():
            if not isinstance(proxy, dict):
                continue
            proxy_path = ["traffic", "proxies", str(proxy_name)]
            source = str(proxy.get("source") or "")
            if source:
                add(proxy_path, [*proxy_path, "source"], ["sourceClusters", source], f"source={source}")
            kafka = str(proxy.get("kafka") or "default")
            add(proxy_path, [*proxy_path, "kafka"], ["traffic", "kafkaClusters", kafka], f"kafka={kafka}")

        for s3_name, s3_source in s3_sources.items():
            if not isinstance(s3_source, dict):
                continue
            s3_path = ["traffic", "s3Sources", str(s3_name)]
            kafka = str(s3_source.get("kafka") or "default")
            add(s3_path, [*s3_path, "kafka"], ["traffic", "kafkaClusters", kafka], f"kafka={kafka}")

        for replayer_name, replayer in replayers.items():
            if not isinstance(replayer, dict):
                continue
            replayer_path = ["traffic", "replayers", str(replayer_name)]
            from_captured_traffic = str(replayer.get("fromCapturedTraffic") or "")
            if from_captured_traffic:
                if from_captured_traffic in proxies:
                    to_path = ["traffic", "proxies", from_captured_traffic]
                elif from_captured_traffic in s3_sources:
                    to_path = ["traffic", "s3Sources", from_captured_traffic]
                else:
                    to_path = None
                if to_path:
                    add(replayer_path, [*replayer_path, "fromCapturedTraffic"], to_path, f"fromCapturedTraffic={from_captured_traffic}")
            to_target = str(replayer.get("toTarget") or "")
            if to_target:
                add(replayer_path, [*replayer_path, "toTarget"], ["targetClusters", to_target], f"toTarget={to_target}")
            dependencies = replayer.get("dependsOnSnapshotMigrations")
            if isinstance(dependencies, list):
                for index, dependency in enumerate(dependencies):
                    if not isinstance(dependency, dict):
                        continue
                    dependency_path = [*replayer_path, "dependsOnSnapshotMigrations", str(index)]
                    source = str(dependency.get("source") or "")
                    if source:
                        add(dependency_path, [*dependency_path, "source"], ["sourceClusters", source], f"source={source}")
                    snapshot = str(dependency.get("snapshot") or "")
                    if source and snapshot:
                        add(
                            dependency_path,
                            [*dependency_path, "snapshot"],
                            ["sourceClusters", source, "snapshotInfo", "snapshots", snapshot],
                            f"snapshot={snapshot}",
                        )

        sources = config.get("sourceClusters") if isinstance(config.get("sourceClusters"), dict) else {}
        for source_name, source in sources.items():
            if not isinstance(source, dict):
                continue
            snapshot_info = source.get("snapshotInfo") if isinstance(source.get("snapshotInfo"), dict) else {}
            repos = snapshot_info.get("repos") if isinstance(snapshot_info.get("repos"), dict) else {}
            snapshots = snapshot_info.get("snapshots") if isinstance(snapshot_info.get("snapshots"), dict) else {}
            if not repos or not snapshots:
                continue
            for snapshot_name, snapshot in snapshots.items():
                if not isinstance(snapshot, dict):
                    continue
                repo_name = str(snapshot.get("repoName") or "")
                if repo_name:
                    snapshot_path = ["sourceClusters", str(source_name), "snapshotInfo", "snapshots", str(snapshot_name)]
                    add(
                        snapshot_path,
                        [*snapshot_path, "repoName"],
                        ["sourceClusters", str(source_name), "snapshotInfo", "repos", repo_name],
                        f"repoName={repo_name}",
                    )
        return edges

    @staticmethod
    def _source_snapshots_removed_by_path(
        config: Dict[str, Any],
        path: list[str],
    ) -> Optional[tuple[str, list[str]]]:
        if len(path) < 3 or path[0] != "sourceClusters":
            return None
        source_name = path[1]
        snapshots = (
            config.get("sourceClusters", {})
            .get(source_name, {})
            .get("snapshotInfo", {})
            .get("snapshots", {})
        )
        if not isinstance(snapshots, dict):
            return None
        if len(path) == 5 and path[2:4] == ["snapshotInfo", "snapshots"]:
            return source_name, [path[4]]
        if len(path) == 4 and path[2:4] == ["snapshotInfo", "snapshots"]:
            return source_name, sorted(snapshots)
        if len(path) == 3 and path[2] == "snapshotInfo":
            return source_name, sorted(snapshots)
        return None

    def _apply_config_edit_operation(
        self,
        operation: Dict,
        selected_id: Optional[str] = None,
        auto_edit_required_child: bool = False,
        post_apply_edit_id: Optional[str] = None,
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        if self._edit_draft_yaml is None:
            self.notify("No edit draft loaded", severity="error")
            return
        if selected_id is None:
            node = self.tree_root_widget.cursor_node
            if node and node.data:
                selected_id = node.data.get("id")
        self.run_worker(
            lambda: self._apply_config_edit_operation_worker(
                operation,
                selected_id,
                auto_edit_required_child,
                post_apply_edit_id,
                discard_path_on_cancel,
            ),
            thread=True,
            name="apply_config_edit_operation",
        )

    def _apply_config_edit_operation_worker(
        self,
        operation: Dict,
        selected_id: Optional[str],
        auto_edit_required_child: bool,
        post_apply_edit_id: Optional[str],
        discard_path_on_cancel: Optional[list[str]],
    ) -> None:
        try:
            service = self._config_edit_service_or_default()
            result = service.apply_operation(self._edit_draft_yaml or "", operation)
            self.call_from_thread(
                self._handle_config_edit_apply_result,
                result,
                selected_id,
                auto_edit_required_child,
                post_apply_edit_id,
                discard_path_on_cancel,
            )
        except Exception as e:
            logger.exception("Failed to apply config edit operation")
            self.call_from_thread(self.notify, f"Edit failed: {e}", severity="error")

    def _handle_config_edit_apply_result(
        self,
        result,
        selected_id: Optional[str],
        auto_edit_required_child: bool = False,
        post_apply_edit_id: Optional[str] = None,
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        raw_yaml = getattr(result, "raw_yaml", None)
        if raw_yaml is None:
            raw_yaml = result.get("raw_yaml") or result.get("yaml", "")
        edit_state = self._enrich_config_edit_state(
            getattr(result, "edit_state", None) or result["edit_state"],
            self._last_resource_config_snapshots,
            self._parse_config_yaml(raw_yaml),
        )
        auto_edit_id = (
            self._first_required_edit_target_id(edit_state, selected_id)
            if auto_edit_required_child and selected_id else None
        )
        expand_after_render_id = None
        if post_apply_edit_id:
            auto_edit_id = self._preferred_edit_target_id(edit_state, post_apply_edit_id)
            if auto_edit_id is None:
                expand_after_render_id = post_apply_edit_id
        self._edit_state = edit_state
        self._edit_draft_yaml = raw_yaml
        self._edit_dirty = True
        expansion_state = self._edit_expansion_state_for_render(edit_state)
        if expand_after_render_id:
            expansion_state[expand_after_render_id] = True
        render_edit_state(
            self.tree_root_widget,
            edit_state,
            self._edit_value_mode,
            self._edit_status_mode,
            self._edit_field_visibility,
            expansion_state=expansion_state,
        )
        if auto_edit_id:
            self.call_after_refresh(
                lambda: self._select_and_edit_config_node(
                    auto_edit_id,
                    discard_path_on_cancel=discard_path_on_cancel,
                )
            )
        elif expand_after_render_id:
            self.call_after_refresh(lambda: self._restore_and_expand_config_edit_selection(expand_after_render_id))
        elif selected_id:
            self.call_after_refresh(lambda: self._restore_config_edit_selection(selected_id))
        self._update_edit_help()
        self.update_pod_status()
        self._update_dynamic_bindings()

    def _restore_config_edit_selection(self, selected_id: str) -> None:
        for candidate_id in self._config_edit_selection_candidate_ids(selected_id):
            if self._select_tree_node_by_id(candidate_id):
                break
        else:
            self._focus_config_edit_tree()
        self._update_edit_help()
        self.update_pod_status()
        self._update_dynamic_bindings()

    def _restore_and_expand_config_edit_selection(self, selected_id: str) -> None:
        for candidate_id in self._config_edit_selection_candidate_ids(selected_id):
            if self._select_tree_node_by_id(candidate_id):
                if self.tree_root_widget.cursor_node and self.tree_root_widget.cursor_node.children:
                    self.tree_root_widget.cursor_node.expand()
                break
        else:
            self._focus_config_edit_tree()
        self._update_edit_help()
        self.update_pod_status()
        self._update_dynamic_bindings()

    @classmethod
    def _config_edit_selection_candidate_ids(cls, selected_id: str) -> list[str]:
        candidates: list[str] = []

        def add(candidate: str) -> None:
            if candidate and candidate not in candidates:
                candidates.append(candidate)

        add(selected_id)
        base_id = selected_id
        if base_id.endswith(":add"):
            base_id = base_id.removesuffix(":add")
            add(base_id)
        if base_id.startswith("edit:"):
            path = base_id.removeprefix("edit:")
            parts = path.split(".") if path else []
            for length in range(len(parts) - 1, 0, -1):
                add(f"edit:{'.'.join(parts[:length])}")
        return candidates

    def _select_tree_node_by_id(self, selected_id: str) -> bool:
        stack = list(self.tree_root_widget.root.children)
        while stack:
            node = stack.pop()
            if node.data and node.data.get("id") == selected_id:
                parent = node.parent
                while parent is not None:
                    parent.expand()
                    parent = parent.parent
                # Textual only assigns visible line numbers when its line cache is rebuilt.
                # Hidden descendants keep _line=-1 until then, so rebuild before moving.
                _ = self.tree_root_widget._tree_lines
                self.tree_root_widget.move_cursor(node)
                self.tree_root_widget.focus()
                return True
            stack.extend(reversed(node.children))
        return False

    def _focus_config_edit_tree(self) -> None:
        tree = self.tree_root_widget
        node = self._first_config_edit_tree_node()
        if node is not None:
            _ = tree._tree_lines
            tree.move_cursor(node)
        tree.focus()

    def _first_config_edit_tree_node(self) -> Optional[Any]:
        root_children = list(self.tree_root_widget.root.children)
        stack = list(reversed(root_children))
        fallback = root_children[0] if root_children else None
        while stack:
            node = stack.pop()
            if node.data and node.data.get("type") == "config-edit":
                return node
            stack.extend(reversed(node.children))
        return fallback

    def _select_and_edit_config_node(
        self,
        selected_id: str,
        discard_path_on_cancel: Optional[list[str]] = None,
    ) -> None:
        if not any(
            self._select_tree_node_by_id(candidate_id)
            for candidate_id in self._config_edit_selection_candidate_ids(selected_id)
        ):
            self._focus_config_edit_tree()
            self._update_edit_help()
            self.update_pod_status()
            self._update_dynamic_bindings()
            return
        self._update_edit_help()
        self.update_pod_status()
        self._update_dynamic_bindings()
        node = selected_edit_node(self.tree_root_widget)
        if node:
            self._edit_config_node(node, discard_path_on_cancel=discard_path_on_cancel)

    @classmethod
    def _first_required_edit_target_id(cls, edit_state: Dict, parent_id: Optional[str]) -> Optional[str]:
        parent = cls._find_edit_node_by_id(edit_state.get("nodes") or [], parent_id)
        if not parent:
            return None
        candidates = [
            node.get("id")
            for node in cls._required_edit_targets(parent.get("children") or [])
            if node.get("id")
        ]
        if len(candidates) == 1:
            return candidates[0]
        if not candidates and cls._is_required_edit_target(parent):
            descendants = cls._editable_descendant_ids(parent.get("children") or [])
            return descendants[0] if len(descendants) == 1 else None
        return None

    @classmethod
    def _required_edit_targets(cls, nodes) -> list[Dict]:
        targets = []
        for node in nodes or []:
            child_targets = cls._required_edit_targets(node.get("children") or [])
            if child_targets:
                targets.extend(child_targets)
            elif cls._is_required_edit_target(node):
                targets.append(node)
        return targets

    @classmethod
    def _is_required_edit_target(cls, node: Dict) -> bool:
        if node.get("valueKind") not in {"scalar", "boolean", "union"}:
            return False
        if node.get("status") == "required" or node.get("required"):
            return True
        return bool((node.get("statusCounts") or {}).get("required"))

    @classmethod
    def _find_edit_node_by_id(cls, nodes, selected_id: Optional[str]) -> Optional[Dict]:
        if not selected_id:
            return None
        stack = list(nodes or [])
        while stack:
            node = stack.pop()
            if node.get("id") == selected_id:
                return node
            stack.extend(node.get("children") or [])
        return None

    @classmethod
    def _editable_descendant_ids(cls, nodes) -> list[str]:
        descendants = []
        for node in nodes or []:
            if cls._opens_config_edit_dialog(node) and node.get("id"):
                descendants.append(node.get("id"))
            descendants.extend(cls._editable_descendant_ids(node.get("children") or []))
        return descendants

    def _array_add_auto_edit_target(self, node: Dict) -> tuple[Optional[str], Optional[list[str]]]:
        path = [str(part) for part in (node.get("path") or [])]
        if not path:
            return None, None
        parent = self._find_edit_node_by_id((self._edit_state or {}).get("nodes") or [], self._edit_id_for_path(path))
        if not parent or parent.get("valueKind") != "array":
            return None, None
        next_index = 0
        for child in parent.get("children") or []:
            child_path = [str(part) for part in (child.get("path") or [])]
            if child.get("valueKind") == "command":
                continue
            if len(child_path) != len(path) + 1 or child_path[:len(path)] != path:
                continue
            if child_path[-1].isdigit():
                next_index = max(next_index, int(child_path[-1]) + 1)
        added_path = [*path, str(next_index)]
        return self._edit_id_for_path(added_path), added_path

    @classmethod
    def _preferred_edit_target_id(cls, edit_state: Dict, selected_id: Optional[str]) -> Optional[str]:
        selected = cls._find_edit_node_by_id(edit_state.get("nodes") or [], selected_id)
        if not selected:
            return None
        if cls._opens_config_edit_dialog(selected):
            return selected.get("id")
        targets = [
            target.get("id")
            for target in cls._required_edit_targets(selected.get("children") or [])
            if target.get("id")
        ]
        if len(targets) == 1:
            return targets[0]
        return selected.get("id") if not targets else None

    @staticmethod
    def _opens_config_edit_dialog(node: Dict) -> bool:
        kind = node.get("valueKind")
        return (
            bool(node.get("externalRef"))
            or kind in {"scalar", "boolean", "union"}
            or (kind in {"object", "array"} and not node.get("children"))
        )

    @staticmethod
    def _edit_id_for_path(path: list[str]) -> str:
        return f"edit:{'.'.join(str(part) for part in path)}"


# --- Utilities ---

def _external_resource_form_notice(resource: Optional[Dict]) -> str:
    if not resource or not resource.get("missing"):
        return ""
    message = str(resource.get("message") or "").strip()
    if message:
        return f"**ERROR:** {message} This form will create it."
    kind = str(resource.get("kind") or "Resource")
    name = str(resource.get("name") or "").strip()
    return f"**ERROR:** {kind} {name or 'resource'} does not exist. This form will create it."


def copy_to_clipboard(text: str) -> bool:
    """Universal copy-to-clipboard: SSH, kubectl exec, and Local OS."""
    try:
        is_remote = any(k in os.environ for k in ["SSH_TTY", "SSH_CLIENT", "KUBERNETES_SERVICE_HOST"])
        if os.environ.get("TERM") in ["xterm-256color", "screen-256color"]:
            b64_text = base64.b64encode(text.encode('utf-8')).decode('utf-8')
            osc_52 = f"\x1b]52;c;{b64_text}\x07"
            if "TMUX" in os.environ:
                osc_52 = f"\x1bPtmux;\x1b{osc_52}\x1b\\"
            sys.stdout.write(osc_52)
            sys.stdout.flush()
            if is_remote:
                return True
        system = platform.system()
        if system == "Darwin":
            subprocess.run(['pbcopy'], input=text.encode('utf-8'), check=True)
        elif system == "Windows":
            subprocess.run(['clip'], input=text, text=True, check=True)
        elif system == "Linux":
            try:
                subprocess.run(['wl-copy'], input=text.encode('utf-8'), check=True)
            except FileNotFoundError:
                subprocess.run(['xclip', '-selection', 'clipboard'], input=text.encode('utf-8'), check=True)
        return True
    except Exception:
        return False
