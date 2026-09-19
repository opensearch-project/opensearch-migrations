"""
Interactive manage Text-UI for workflow CLI.
interactive tree navigation for status viewing and approval.
"""
import base64
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

from textual.app import App, ComposeResult, Notify
from textual.containers import Container
from textual.notifications import Notification
from textual.screen import ModalScreen
from textual.widgets import Footer, Header, Static, Tree

from .confirm_modal import ConfirmModal
from .container_select_modal import ContainerSelectModal
from .live_status_manager import LiveStatusManager
from .log_manager import LogManager
from .manage_injections import ArgoWorkflowInterface, PodScraperInterface, WaiterInterface
from .pod_name_manager import PodNameManager
from .tree_state_manager import TreeStateManager
from .resource_tree_state_manager import RESOURCE_ID_PREFIX
from ..commands.artifact_store import ArtifactStoreError
from ..commands.crd_utils import RESETTABLE_PLURALS, parse_resource_path, resource_display_name
from ..resource_tree import (
    CONFIG_MODE_ALL,
    CONFIG_MODE_LABELS,
    resource_config_change_summary,
)
from ..commands.show import read_managed_output
from ..tree_utils import is_approval_node
from ..application.manage_state import (
    ManageStateService,
    assign_workflow_progress,
    iter_resource_nodes,
    iter_running_approval_nodes,
    workflow_has_active_rollout,
)

logger = logging.getLogger(__name__)

TREE_ROOT_ANCHOR = "workflow-tree"
DEFERRED_ERROR_NOTIFICATION_HOLD_SECONDS = 24 * 60 * 60

# --- Constants ---
NODE_TYPE_POD = "Pod"
PHASE_RUNNING = "Running"
PHASE_SUCCEEDED = "Succeeded"
LOADING_ROOT_LABEL = "[yellow]⏳ Waiting for Workflow to be created...[/]"
DESC_SHOW_OUTPUT = "Show Output"
DESC_VIEW_LOGS = "View Logs"
DESC_TAIL_LOGS = "Tail Logs"
PATCH_OUTPUT_STEPS = {
    "patchMetadataEvaluateOutput": ("snapshotmigrations", "metadataEvaluate"),
    "patchMetadataMigrateOutput": ("snapshotmigrations", "metadataMigrate"),
}
ENABLE_MOUSE_SEQUENCES = "\x1b[?1000h\x1b[?1003h\x1b[?1015h\x1b[?1006h"
DISABLE_MOUSE_SEQUENCES = "\x1b[?1000l\x1b[?1002l\x1b[?1003l\x1b[?1015l\x1b[?1006l\x1b[?1016l"
DISABLE_MOUSE_PIXELS_SEQUENCE = "\x1b[?1016l"


def _single_line(text: str) -> str:
    return re.sub(r"\s+", " ", text).strip()


def _submit_error_excerpt(text: str) -> str:
    text = text.split("\nstdout:", 1)[0]
    return text.split("\n\n", 1)[0]


def _format_workflow_submit_error(error: Exception) -> str:
    """Return a concise submit error suitable for a TUI toast."""
    text = str(error)
    denial = re.search(r"denied request:\s*", text)
    if denial:
        reason = _single_line(_submit_error_excerpt(text[denial.end():]))
        kind_match = re.search(r"Kind=([A-Za-z0-9]+)", text)
        name_match = re.search(r'Name:\s+"([^"]+)"', text)
        policy_match = re.search(r"ValidatingAdmissionPolicy\s+'([^']+)'", text)
        target = ""
        if kind_match and name_match:
            target = f"{kind_match.group(1)} {name_match.group(1)} "
        policy = f" by {policy_match.group(1)}" if policy_match else ""
        return f"Workflow submit failed: {target}denied{policy}: {reason}"

    invalid = re.search(r'(?:The )?[A-Za-z][A-Za-z0-9]* "[^"]+" is invalid: [^\n]*', text)
    if invalid:
        return f"Workflow submit failed: {_single_line(invalid.group(0))}"

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
        self.last_known_phase: str = ""
        self.is_exiting = False

        # Injected Services
        self._argo_service = argo_service
        self._workflow_waiter = workflow_waiter
        self._pod_scraper = pod_scraper
        self._refresh_interval = refresh_interval
        self._resource_view = resource_view
        self._config_edit_service = config_edit_service
        self._resource_value_mode = CONFIG_MODE_ALL
        self._resource_change_summary = {'pending': 0, 'to_submit': 0, 'resources': 0}
        self._last_resource_sections = None
        self._last_resource_workflow_data: Dict = {}
        self._last_resource_config_snapshots: Optional[Dict[str, Any]] = None
        self._expand_changed_resources_on_next_render = False
        self._submitting_workflow = False
        self._resetting_resource_path: Optional[str] = None
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
        self._manage_state_service = ManageStateService(
            namespace=namespace,
            workflow_name=name,
            argo_service=argo_service,
            config_service_provider=self._config_service_or_default,
        )

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(Tree(LOADING_ROOT_LABEL, id="workflow-tree"), id="tree-container")
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
        sections = self._manage_state_service.build_resource_sections(workflow_data)
        if self._manage_state_service.last_config_snapshots is not None:
            self._last_resource_config_snapshots = (
                self._manage_state_service.last_config_snapshots
            )
        return sections

    def _config_service_or_default(self):
        if self._config_edit_service is not None:
            return self._config_edit_service
        from ..services.config_edit_service import ConfigEditService
        return ConfigEditService(namespace=self._namespace)

    @staticmethod
    def _workflow_has_active_rollout(workflow_data: Dict) -> bool:
        """Return whether the submitted config still represents an active rollout."""
        return workflow_has_active_rollout(workflow_data)

    @staticmethod
    def _assign_workflow_progress(sections, steps):
        """Attach workflow step subtrees to matching resource nodes."""
        assign_workflow_progress(sections, steps)

    @staticmethod
    def _iter_resource_nodes(resources):
        yield from iter_resource_nodes(resources)

    @staticmethod
    def _iter_running_approval_nodes(steps):
        yield from iter_running_approval_nodes(steps)

    def _handle_resource_data(self, sections, workflow_data: Dict, force_reload: bool = False) -> None:
        """Handle pre-built resource sections on the main thread."""
        self.title = "Migration Status"
        self._update_last_known_phase(workflow_data)
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
        self._pods.trigger_resolve(new_run_id, use_cache=not force_reload)
        self.update_pod_status()
        self._update_dynamic_bindings()
        self.set_timer(self._refresh_interval, self.action_refresh_workflow)

    def _handle_workflow_data(self, new_data: Dict, force_reload: bool = False) -> None:
        """The Conductor routes data to the relevant managers."""
        self.title = "Workflow Steps"
        self._update_last_known_phase(new_data)
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

    def _update_last_known_phase(self, workflow_data: Dict) -> None:
        phase = workflow_data.get('status', {}).get('phase') if workflow_data else None
        if phase:
            self.last_known_phase = phase

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
        self.update_pod_status()
        self._update_dynamic_bindings()

    def on_tree_node_selected(self, event: Tree.NodeSelected) -> None:
        if not self.is_mounted or isinstance(self.screen, ModalScreen):
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
        if not self._resource_view:
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
            "The commit uses the exact resource set above. If a new dependent resource exists, "
            "reset will fail and you must dry-run again.",
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
        if action in {"expand_node", "collapse_node", "reset_resource"} and isinstance(
            self.screen, ModalScreen
        ):
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
        node = tree_node.data if tree_node and tree_node.data else None
        output_available = bool(node and self._has_managed_output_refs(tree_node))
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
            self._resource_view,
            self._mouse_input_enabled,
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
            self.bind("l", "view_resource_logs", description=DESC_VIEW_LOGS)
            self.bind("t", "tail_resource_logs", description=DESC_TAIL_LOGS)
            if self._approval_node_for_action(node):
                self.bind("a", "approve_step", description="Approve")
            if output_available:
                self.bind("o", "view_output", description=DESC_SHOW_OUTPUT)
        elif ntype == NODE_TYPE_POD and self._pods.get_name(node_id) and not is_approval_node(node):
            self.bind("l", "view_logs", description=DESC_VIEW_LOGS)
            if output_available:
                self.bind("o", "view_output", description=DESC_SHOW_OUTPUT)
            if node.get('phase') == PHASE_RUNNING:
                self.bind("f", "follow_logs", description="Follow Logs")
                self.bind("t", "follow_logs", description=DESC_TAIL_LOGS)
            self.bind("c", "copy_pod_name", description="Copy Pod Name")
        elif is_approval_node(node) and node.get('phase') == PHASE_RUNNING:
            self.bind("a", "approve_step", description="Approve")
        elif node.get('resource_path'):
            self.bind("l", "view_resource_logs", description=DESC_VIEW_LOGS)
            self.bind("t", "tail_resource_logs", description=DESC_TAIL_LOGS)
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
        if not self._resource_view:
            return
        self._set_resource_value_mode(
            self._next_resource_value_mode(self._resource_value_mode),
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

    @staticmethod
    def _next_resource_value_mode(current: str) -> str:
        modes = tuple(CONFIG_MODE_LABELS)
        try:
            index = modes.index(current)
        except ValueError:
            index = 0
        return modes[(index + 1) % len(modes)]

    def action_submit_workflow(self) -> None:
        if not self._resource_view or self._submitting_workflow:
            return
        self.push_screen(
            ConfirmModal("Submit saved workflow configuration and replace the current workflow?"),
            lambda confirmed: self._start_submit_workflow() if confirmed else None,
        )

    def _start_submit_workflow(self) -> None:
        self._submitting_workflow = True
        self.update_pod_status()
        self.run_worker(self._submit_workflow_worker, thread=True, name="submit_workflow")

    def _submit_workflow_worker(self) -> None:
        try:
            service = self._config_service_or_default()
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

    def _expand_changed_resource_nodes(self, sections) -> None:
        if self._resource_view and hasattr(self._tree_state, "expand_config_differences"):
            self._tree_state.expand_config_differences(sections)

    def _nearest_resource_reset_target(self) -> Optional[Dict[str, str]]:
        if not self._resource_view:
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
        if (
            isinstance(approval_node, dict)
            and is_approval_node(approval_node)
            and approval_node.get('phase') == PHASE_RUNNING
        ):
            return approval_node
        return None


# --- Utilities ---

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
