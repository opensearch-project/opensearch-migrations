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
import subprocess
import sys
import time
from typing import Any, Dict, Optional

import yaml
from textual.app import App, ComposeResult
from textual.containers import Container
from textual.screen import ModalScreen
from textual.widgets import Footer, Header, Static, Tree

from .choice_select_modal import ChoiceSelectModal
from .config_edit_exit_modal import ConfigEditExitModal
from .confirm_modal import ConfirmModal
from .container_select_modal import ContainerSelectModal
from .config_edit_tree import (
    EDIT_MODE_ALL,
    EDIT_MODE_CURRENT_WORKFLOW,
    EDIT_MODE_DEPLOYED,
    EDIT_MODE_LABELS,
    EDIT_MODES,
    EDIT_MODE_PENDING_SUBMIT,
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
from ..commands.crd_utils import resource_display_name
from ..resource_tree import (
    CONFIG_MODE_LABELS,
    apply_config_overlays,
    resource_config_change_summary,
)
from ..commands.show import read_managed_output
from ..tree_utils import is_approval_node

logger = logging.getLogger(__name__)

TREE_ROOT_ANCHOR = "workflow-tree"

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
        self._edit_show_optional = True
        self._edit_show_expert = False
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
        self._edit_validation_generation = 0
        self._edit_validation_timer: Optional[Any] = None
        self._edit_validation_delay = 0.4
        self._mouse_input_enabled = True
        self._mouse_pixels_was_enabled = False

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
        for resource in (
            r for section in sections for group in section.groups
            for r in group.resources
        ):
            if resource.name in steps:
                resource.workflow_progress = steps[resource.name]
            for child in resource.children:
                if child.name in steps:
                    child.workflow_progress = steps[child.name]

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
        self._resource_change_summary = resource_config_change_summary(sections)
        if hasattr(self._tree_state, "set_config_value_mode"):
            self._tree_state.set_config_value_mode(self._resource_value_mode)

        if is_restart or not had_resource_tree:
            self.current_run_id = new_run_id
            self._pods.clear_cache()
            self._tree_state.rebuild(sections, workflow_data)
        else:
            self._tree_state.update(sections, workflow_data)
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
        if data and is_approval_node(data) and data.get('phase') == PHASE_RUNNING:
            event.stop()
            self.action_approve_step()
            return
        if event.node.is_expanded:
            event.node.collapse()
        else:
            event.node.expand()

    def on_key(self, event) -> None:
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

        selected_data = tree_node.data or {}
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
                    logger.info(
                        "Found managed output ref patch_step=%s node_id=%s resource=%s output=%s",
                        step_name,
                        data.get('id'),
                        resource_path,
                        output_name,
                    )
                    refs.append((resource_path, output_name))
                else:
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
        logger.info("Collected %s managed output ref(s)", len(refs))
        return refs

    def _find_output_refs_in_workflow_data(self, resource_name: str):
        """Search raw workflow nodes for patch-output steps matching a resource."""
        workflow_data = self._tree_state._workflow_data
        if not workflow_data:
            return []
        refs = []
        for node in (workflow_data.get('status', {}).get('nodes', {}) or {}).values():
            display_name = node.get('displayName', '')
            step_name = display_name.split('(')[0].strip()
            patch_spec = PATCH_OUTPUT_STEPS.get(step_name)
            if not patch_spec:
                continue
            plural, output_name = patch_spec
            node_resource = self._input_parameter(node, 'resourceName')
            if node_resource == resource_name:
                resource_path = resource_display_name(plural, node_resource)
                refs.append((resource_path, output_name))
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

    def action_view_resource_logs(self) -> None:
        """View logs for a migration resource via the workflow log CLI."""
        node = self.current_node_data
        if not node or not node.get('resource_path'):
            return
        resource_path = node['resource_path']
        with self.suspend():
            os.system('clear')
            cmd = f"workflow log resource {resource_path} | less -R"
            os.system(cmd)

    def action_copy_pod_name(self) -> None:
        if not self.current_node_data:
            return
        node_id = self.current_node_data.get('id')
        if pod_name := self._pods.get_name(node_id):
            if copy_to_clipboard(pod_name):
                self.notify(f"📋 Copied: {pod_name}")

    def action_approve_step(self) -> None:
        node = self.current_node_data
        if node and is_approval_node(node):
            msg = f"Approve '{node.get('display_name')}'?"
            reason = node.get('denial_reason')
            if reason:
                msg += f"\n\n{reason}"
            self.push_screen(ConfirmModal(msg),
                             lambda confirmed: self._execute_approval(node) if confirmed else None)

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
        if action in {"expand_node", "collapse_node", "edit_selected_config_node"} and isinstance(self.screen, ModalScreen):
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
        status_bar = self.query_one("#pod-status", Static)
        if self._edit_mode:
            node = selected_edit_node(self.tree_root_widget)
            dirty = "dirty" if self._edit_dirty else "clean"
            value_mode = EDIT_MODE_LABELS.get(self._edit_value_mode, self._edit_value_mode)
            status_mode = EDIT_MODE_LABELS.get(self._edit_status_mode, self._edit_status_mode)
            optional_state = "optional on" if self._edit_show_optional else "optional off"
            expert_state = "expert on" if self._edit_show_expert else "expert off"
            if node:
                status = node.get("status", "ok")
                status_bar.update(
                    f"Config edit: [bold cyan]{status}[/]  [{dirty}]  "
                    f"Values: {value_mode}  Status: {status_mode}  "
                    f"{optional_state}, {expert_state}  s/Ctrl+s saves, Esc exits"
                )
            else:
                status_bar.update(
                    f"Config edit: [{dirty}]  Values: {value_mode}  "
                    f"Status: {status_mode}  {optional_state}, {expert_state}  s/Ctrl+s saves, Esc exits"
                )
            return
        if self._resource_view:
            summary = self._resource_change_summary
            value_mode = CONFIG_MODE_LABELS.get(self._resource_value_mode, self._resource_value_mode)
            if self._submitting_workflow:
                status_bar.update(f"Submitting workflow...  Values: {value_mode}")
                return
            if summary.get('resources'):
                status_bar.update(
                    f"Config changes: [green]{summary.get('to_submit', 0)} to submit[/], "
                    f"[grey50]{summary.get('pending', 0)} pending[/]  Values: {value_mode}"
                )
                return
            status_bar.update(f"Values: {value_mode}")
            return
        if not self.current_node_data:
            status_bar.update("")
            return
        
        node_type = self.current_node_data.get('type')
        if is_approval_node(self.current_node_data):
            name_param = None
            for p in self.current_node_data.get('inputs', {}).get('parameters', []):
                if p.get('name') in ('resourceName', 'name'):
                    name_param = p.get('value')
                    break
            status_bar.update(f"Name: [bold cyan]{name_param}[/]" if name_param else "")
        elif node_type == NODE_TYPE_POD:
            node_id = self.current_node_data.get('id')
            name = self._pods.get_name(node_id) if node_id else None
            status_bar.update(f"Pod: [bold green]{name}[/]" if name else "Pod: (not available)")
        else:
            status_bar.update("")

    def _update_dynamic_bindings(self) -> None:
        """Reconfigures the Footer and keys based on the currently selected node."""
        self._bindings = self._bindings.__class__()

        self.bind("ctrl+p", "command_palette", show=False)
        self.bind("r", "manual_refresh", description="Refresh")
        self.bind("q", "quit", description="Quit")
        self.bind(
            "m",
            "toggle_mouse_input",
            description="Mouse Off" if self._mouse_input_enabled else "Mouse On",
        )

        if self._edit_mode:
            node = selected_edit_node(self.tree_root_widget)
            self.bind("escape", "exit_config_edit", description="Exit Edit")
            self.bind("s", "save_config_edit", description="Save")
            self.bind("ctrl+s", "save_config_edit", description="Save")
            self.bind("?", "show_config_edit_help", description="Help")
            optional_description = "Hide Optional" if self._edit_show_optional else "Show Optional"
            expert_description = "Hide Expert" if self._edit_show_expert else "Show Expert"
            self.bind("o", "toggle_config_optional_fields", description=optional_description)
            self.bind("O", "toggle_config_optional_fields", description=optional_description)
            self.bind("x", "toggle_config_expert_fields", description=expert_description)
            self.bind("X", "toggle_config_expert_fields", description=expert_description)
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
            if self._is_removable_edit_node(node):
                self.bind("delete", "remove_config_node", description="Remove")
                self.bind("backspace", "remove_config_node", show=False)
            self.refresh_bindings()
            return

        self.bind("left", "collapse_node", show=False)
        self.bind("right", "expand_node", show=False)

        if self._resource_view:
            self.bind("e", "edit_config", description="Edit Config")
            self.bind("s", "submit_workflow", description="Submit")
            self.bind("v", "cycle_resource_value_mode", description="Value Mode")

        node = self.current_node_data
        if node:
            self._bind_node_actions(node)

        self.refresh_bindings()

    def _bind_node_actions(self, node: Dict) -> None:
        """Bind context-sensitive keys for the selected node."""
        node_id = node.get('id') or ''
        ntype = node.get('type')

        if node_id.startswith(RESOURCE_ID_PREFIX):
            self.bind("l", "view_resource_logs", description="View Logs")
            if self._collect_managed_output_refs():
                self.bind("o", "view_output", description=DESC_SHOW_OUTPUT)
        elif ntype == NODE_TYPE_POD and self._pods.get_name(node_id) and not is_approval_node(node):
            self.bind("l", "view_logs", description="View Logs")
            if self._collect_managed_output_refs():
                self.bind("o", "view_output", description=DESC_SHOW_OUTPUT)
            if node.get('phase') == PHASE_RUNNING:
                self.bind("f", "follow_logs", description="Follow Logs")
            self.bind("c", "copy_pod_name", description="Copy Pod Name")
        elif is_approval_node(node) and node.get('phase') == PHASE_RUNNING:
            self.bind("a", "approve_step", description="Approve")
        elif self._collect_managed_output_refs():
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
        if node and is_approval_node(node) and node.get('phase') == PHASE_RUNNING:
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
        self._resource_value_mode = self._next_edit_mode(self._resource_value_mode)
        if hasattr(self._tree_state, "set_config_value_mode"):
            self._tree_state.set_config_value_mode(self._resource_value_mode)
        if self._last_resource_sections is not None:
            self._tree_state.update(self._last_resource_sections, self._last_resource_workflow_data)
            self._expand_changed_resource_nodes(self._last_resource_sections)
        self.update_pod_status()
        self._update_dynamic_bindings()

    def action_submit_workflow(self) -> None:
        if not self._resource_view or self._edit_mode or self._submitting_workflow:
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
        self.notify(f"Workflow submit failed: {error}", severity="error")
        self.update_pod_status()

    def action_edit_config(self) -> None:
        """Enter schema-guided pending-config edit mode."""
        if not self._resource_view:
            self.notify("Config edit is available from resource view", severity="warning")
            return
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

    def _load_config_edit_state_worker(self) -> None:
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
            self.call_from_thread(self._handle_config_edit_session, session, snapshots)
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

    def _handle_config_edit_session(self, session, snapshots: Optional[Dict[str, Any]] = None) -> None:
        edit_state = self._enrich_config_edit_state(
            getattr(session, "edit_state", None) or session["edit_state"],
            snapshots,
        )
        raw_yaml = getattr(session, "raw_yaml", None)
        if raw_yaml is None:
            raw_yaml = session.get("raw_yaml", "")
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
        self._edit_show_optional = True
        self._edit_show_expert = False
        render_edit_state(
            self.tree_root_widget,
            edit_state,
            self._edit_value_mode,
            self._edit_status_mode,
            self._edit_show_optional,
            self._edit_show_expert,
        )
        help_panel = self.query_one("#edit-help", Static)
        help_panel.display = True
        self._update_edit_help()
        self.update_pod_status()
        self._update_dynamic_bindings()

    def _enrich_config_edit_state(
        self,
        edit_state: Dict[str, Any],
        snapshots: Optional[Dict[str, Any]],
    ) -> Dict[str, Any]:
        """Attach deployed/current/pending value states to TS edit nodes."""
        if not snapshots:
            return edit_state

        submitted_config = ((snapshots.get("submitted") or {}).get("workflowConfig"))
        if submitted_config is None:
            return edit_state

        enriched = copy.deepcopy(edit_state)
        current_config = submitted_config
        for node in enriched.get("nodes") or []:
            self._enrich_config_edit_node(node, submitted_config, current_config)
        return enriched

    def _enrich_config_edit_node(
        self,
        node: Dict[str, Any],
        deployed_config: Dict[str, Any],
        current_config: Dict[str, Any],
    ) -> int:
        changed_count = 0
        for child in node.get("children") or []:
            changed_count += self._enrich_config_edit_node(child, deployed_config, current_config)

        own_changed = 0
        if self._edit_node_supports_value_states(node):
            states = copy.deepcopy(node.get("states") or {})
            deployed = self._workflow_config_value_state(deployed_config, node)
            current = self._workflow_config_value_state(current_config, node)
            pending = self._pending_edit_node_value_state(node)

            submitted_changed = not self._same_value_state(deployed, current)
            pending_changed = not self._same_value_state(current, pending)
            own_changed = 1 if submitted_changed or pending_changed else 0

            states[EDIT_MODE_DEPLOYED] = self._edit_state_payload(deployed, changed=False)
            states[EDIT_MODE_CURRENT_WORKFLOW] = self._edit_state_payload(current, changed=submitted_changed)
            states[EDIT_MODE_PENDING_SUBMIT] = self._edit_state_payload(pending, changed=pending_changed)
            node["states"] = states

        total_changed = changed_count + own_changed
        if total_changed:
            self._merge_edit_node_changed_status(node, total_changed)
        return total_changed

    @staticmethod
    def _edit_node_supports_value_states(node: Dict[str, Any]) -> bool:
        if node.get("valueKind") in {"scalar", "boolean"}:
            return True
        return node.get("valueKind") == "union" and "value" in node

    @classmethod
    def _workflow_config_value_state(cls, workflow_config: Dict[str, Any], node: Dict[str, Any]) -> Dict[str, Any]:
        found, value = cls._lookup_workflow_config_path(workflow_config, node.get("path") or [])
        if not found:
            return {"present": False}
        if node.get("valueKind") == "union":
            value = cls._union_variant_value(value, node)
        return {"present": True, "value": value}

    @staticmethod
    def _pending_edit_node_value_state(node: Dict[str, Any]) -> Dict[str, Any]:
        if "value" not in node:
            return {"present": False}
        value = node.get("value")
        if value == "" and (node.get("status") == "required" or node.get("required")):
            return {"present": False}
        return {"present": value is not None, "value": value}

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
        if WorkflowTreeApp._edit_status_rank(node.get("status")) < WorkflowTreeApp._edit_status_rank("changed"):
            node["status"] = "changed"

    @staticmethod
    def _edit_status_rank(status: Optional[str]) -> int:
        return {
            "ok": 0,
            "changed": 1,
            "warning": 2,
            "gated": 3,
            "required": 4,
            "error": 5,
            "blocked": 6,
        }.get(status or "ok", 0)

    @staticmethod
    def _same_value_state(left: Dict[str, Any], right: Dict[str, Any]) -> bool:
        if bool(left.get("present")) != bool(right.get("present")):
            return False
        if not left.get("present"):
            return True
        return json.dumps(left.get("value"), sort_keys=True) == json.dumps(right.get("value"), sort_keys=True)

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
        validation = (self._edit_state or {}).get("validation") or {}
        diagnostics = validation.get("diagnostics") or []
        errors = validation.get("errors") or []
        if validation.get("valid") is False or diagnostics or errors:
            count = len(diagnostics) or len(errors) or 1
            return f"Validation still reports {count} issue{'s' if count != 1 else ''}. You can save anyway, discard, or return."
        return "No validation errors are currently reported. You can save, discard, or return."

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
        self._edit_mode = False
        self._edit_loading = False
        self._edit_state = None
        self._edit_draft_yaml = None
        self._edit_dirty = False
        self._edit_value_mode = EDIT_MODE_ALL
        self._edit_status_mode = EDIT_MODE_ALL
        self._edit_show_optional = True
        self._edit_show_expert = False
        self._after_config_edit_save = None
        self._cancel_config_edit_validation()
        self._restore_resource_collapsed_ids_on_next_render = self._resource_collapsed_ids_before_edit
        self._resource_collapsed_ids_before_edit = None
        self.query_one("#edit-help", Static).display = False
        self.action_manual_refresh()

    def _expand_changed_resource_nodes(self, sections) -> None:
        if self._resource_view and hasattr(self._tree_state, "expand_config_differences"):
            self._tree_state.expand_config_differences(sections)

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

    def action_toggle_config_optional_fields(self) -> None:
        self._edit_show_optional = not self._edit_show_optional
        self._rerender_config_edit_state()

    def action_toggle_config_expert_fields(self) -> None:
        self._edit_show_expert = not self._edit_show_expert
        self._rerender_config_edit_state()

    @staticmethod
    def _next_edit_mode(current: str) -> str:
        try:
            index = EDIT_MODES.index(current)
        except ValueError:
            index = 0
        return EDIT_MODES[(index + 1) % len(EDIT_MODES)]

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
            self._edit_show_optional,
            self._edit_show_expert,
        )
        if selected_id:
            self.call_after_refresh(lambda: self._restore_config_edit_selection(selected_id))
        else:
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

    def _edit_config_node(self, node: Dict) -> None:
        kind = node.get("valueKind")
        if kind == "command" and node.get("id", "").endswith(":add"):
            command = node.get("command") or {}
            if command.get("requiresName") is False:
                self._apply_config_edit_operation({
                    "op": "add",
                    "path": node.get("path"),
                    "value": {},
                })
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
            self._show_external_resource_picker(node)
            return
        elif kind == "scalar":
            input_hint = node.get("inputHint") or {}
            options = input_hint.get("options") or []
            if input_hint.get("kind") == "reference" and options:
                choices = self._choices_with_unset(node, options)
                self.push_screen(
                    ChoiceSelectModal(
                        f"Select {'.'.join(node.get('path', []))}",
                        choices,
                        node.get("value"),
                        documentation=self._edit_node_documentation(node),
                    ),
                    lambda value: self._handle_scalar_config_value(node, value),
                )
                return
            self._show_scalar_config_text_input(node)
        elif kind == "boolean":
            self._show_boolean_config_picker(node)
        elif kind == "union":
            self._show_config_variant_picker(node)
        elif kind in {"object", "array"} and not node.get("children"):
            self._show_structured_config_editor(node)
        else:
            tree = self.tree_root_widget
            if tree.cursor_node:
                if tree.cursor_node.is_expanded:
                    tree.cursor_node.collapse()
                else:
                    tree.cursor_node.expand()

    def _show_scalar_config_text_input(self, node: Dict) -> None:
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
        )
        self.push_screen(modal, lambda value: self._handle_scalar_config_value(node, value))

    def _show_structured_config_editor(self, node: Dict) -> None:
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
            lambda value: self._handle_structured_config_value(node, value),
        )

    @staticmethod
    def _structured_config_initial_text(node: Dict) -> str:
        value = node.get("value")
        if value is None or value == "":
            return "[]\n" if node.get("valueKind") == "array" else "{}\n"
        return yaml.safe_dump(value, sort_keys=False)

    def _handle_structured_config_value(self, node: Dict, value: Optional[Any]) -> None:
        if value is None:
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

    def _show_external_resource_picker(self, node: Dict) -> None:
        external_ref = node.get("externalRef") or {}
        self.run_worker(
            lambda: self._load_external_resource_picker_worker(node, external_ref),
            thread=True,
            name="load_external_resource_picker",
        )

    def _load_external_resource_picker_worker(self, node: Dict, external_ref: Dict) -> None:
        try:
            service = self._config_edit_service_or_default()
            if not hasattr(service, "list_external_resources"):
                self.call_from_thread(self._show_scalar_config_text_input, node)
                return
            rows = service.list_external_resources(external_ref, node.get("value"))
            self.call_from_thread(self._open_external_resource_picker, node, rows)
        except Exception as e:
            logger.exception("Failed to list external resources")
            self.call_from_thread(self.notify, f"External resource picker unavailable: {e}", severity="error")

    def _open_external_resource_picker(self, node: Dict, rows: list[Dict]) -> None:
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
            lambda choice: self._handle_external_resource_picker_choice(node, choice),
        )

    def _handle_external_resource_picker_choice(self, node: Dict, choice: Optional[Dict]) -> None:
        if not choice:
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
            self.call_from_thread(self._open_external_resource_form, node, "update", resource, return_to_picker)
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
        if mode == "create":
            name_field = ((external_ref.get("create") or {}).get("apply") or {}).get("nameField")
            if name_field and node.get("value"):
                initial_values.setdefault(name_field, str(node.get("value") or ""))
        self.push_screen(
            ExternalResourceFormModal(
                external_ref,
                mode,
                initial_values=initial_values,
                existing_keys=resource.get("keys") if resource else None,
                documentation=self._edit_node_documentation(node),
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
        existing_name = resource.get("name") if resource else None
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

    def _show_config_variant_picker(self, node: Dict) -> None:
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
            lambda value: self._handle_config_variant_choice(node, value),
        )

    def _show_boolean_config_picker(self, node: Dict) -> None:
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
            lambda value: self._handle_boolean_config_value(node, value),
        )

    def _handle_boolean_config_value(self, node: Dict, value) -> None:
        if value is None or value == node.get("value"):
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
        self._apply_config_edit_operation({
            "op": "unset",
            "path": node.get("path"),
        }, selected_id=node.get("id"))

    @staticmethod
    def _config_node_can_unset(node: Dict) -> bool:
        return (
            node.get("presence") == "optional"
            and not node.get("required")
            and node.get("valueKind") in {"scalar", "boolean", "object", "array"}
        )

    def _handle_config_variant_choice(self, node: Dict, value) -> None:
        if value is None or value == node.get("value"):
            return
        if value is CLEAR_VALUE:
            self._unset_config_node(node)
            return
        self._apply_config_edit_operation({
            "op": "set",
            "path": node.get("path"),
            "value": value,
        }, selected_id=node.get("id"), auto_edit_required_child=True)

    def _handle_add_config_name(self, node: Dict, value: Optional[str]) -> None:
        name = (value or "").strip()
        if not name:
            return
        self._cancel_config_edit_validation()
        self._apply_config_edit_operation({
            "op": "add",
            "path": node.get("path"),
            "value": {"name": name},
        })

    def _handle_scalar_config_value(self, node: Dict, value: Optional[Any]) -> None:
        if value is None:
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
        self._apply_config_edit_operation({
            "op": "set",
            "path": node.get("path"),
            "value": value,
        }, selected_id=node.get("id"))

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
        node = selected_edit_node(self.tree_root_widget)
        if not node or node.get("valueKind") == "command":
            return
        path = node.get("path") or []
        if not self._is_removable_config_path(path):
            self.notify("Remove is available for config resource entries", severity="warning")
            return
        self.push_screen(
            ConfirmModal(f"Remove config entry '{'.'.join(path)}' from pending YAML?"),
            lambda confirmed: self._remove_config_node(path) if confirmed else None,
        )

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
            "kafkaClusterConfiguration",
            "snapshotMigrationConfigs",
        ):
            return True
        return len(path) == 3 and path[:2] in (
            ["traffic", "proxies"],
            ["traffic", "replayers"],
        )

    @classmethod
    def _is_removable_edit_node(cls, node: Optional[Dict]) -> bool:
        if not node or node.get("valueKind") == "command":
            return False
        return cls._is_removable_config_path(node.get("path") or [])

    @staticmethod
    def _edit_node_documentation(node: Dict) -> str:
        return str(node.get("description") or node.get("descriptionShort") or "")

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
        if node and is_approval_node(node) and node.get('phase') == PHASE_RUNNING:
            return "Approve"
        return "Expand"

    def _remove_config_node(self, path: list[str]) -> None:
        self._apply_config_edit_operation({
            "op": "removeConfig",
            "path": path,
        })

    def _apply_config_edit_operation(
        self,
        operation: Dict,
        selected_id: Optional[str] = None,
        auto_edit_required_child: bool = False,
    ) -> None:
        if self._edit_draft_yaml is None:
            self.notify("No edit draft loaded", severity="error")
            return
        if selected_id is None:
            node = self.tree_root_widget.cursor_node
            if node and node.data:
                selected_id = node.data.get("id")
        self.run_worker(
            lambda: self._apply_config_edit_operation_worker(operation, selected_id, auto_edit_required_child),
            thread=True,
            name="apply_config_edit_operation",
        )

    def _apply_config_edit_operation_worker(
        self,
        operation: Dict,
        selected_id: Optional[str],
        auto_edit_required_child: bool,
    ) -> None:
        try:
            service = self._config_edit_service_or_default()
            result = service.apply_operation(self._edit_draft_yaml or "", operation)
            self.call_from_thread(
                self._handle_config_edit_apply_result,
                result,
                selected_id,
                auto_edit_required_child,
            )
        except Exception as e:
            logger.exception("Failed to apply config edit operation")
            self.call_from_thread(self.notify, f"Edit failed: {e}", severity="error")

    def _handle_config_edit_apply_result(
        self,
        result,
        selected_id: Optional[str],
        auto_edit_required_child: bool = False,
    ) -> None:
        edit_state = self._enrich_config_edit_state(
            getattr(result, "edit_state", None) or result["edit_state"],
            self._last_resource_config_snapshots,
        )
        raw_yaml = getattr(result, "raw_yaml", None)
        if raw_yaml is None:
            raw_yaml = result.get("raw_yaml") or result.get("yaml", "")
        auto_edit_id = (
            self._first_required_edit_target_id(edit_state, selected_id)
            if auto_edit_required_child and selected_id else None
        )
        self._edit_state = edit_state
        self._edit_draft_yaml = raw_yaml
        self._edit_dirty = True
        render_edit_state(
            self.tree_root_widget,
            edit_state,
            self._edit_value_mode,
            self._edit_status_mode,
            self._edit_show_optional,
            self._edit_show_expert,
        )
        if auto_edit_id:
            self.call_after_refresh(lambda: self._select_and_edit_config_node(auto_edit_id))
        elif selected_id:
            self.call_after_refresh(lambda: self._restore_config_edit_selection(selected_id))
        self._update_edit_help()
        self.update_pod_status()
        self._update_dynamic_bindings()

    def _restore_config_edit_selection(self, selected_id: str) -> None:
        self._select_tree_node_by_id(selected_id)
        self._update_edit_help()
        self.update_pod_status()
        self._update_dynamic_bindings()

    def _select_tree_node_by_id(self, selected_id: str) -> None:
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
                return
            stack.extend(reversed(node.children))

    def _select_and_edit_config_node(self, selected_id: str) -> None:
        self._select_tree_node_by_id(selected_id)
        self._update_edit_help()
        self.update_pod_status()
        self._update_dynamic_bindings()
        node = selected_edit_node(self.tree_root_widget)
        if node:
            self._edit_config_node(node)

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
        return candidates[0] if len(candidates) == 1 else None

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
