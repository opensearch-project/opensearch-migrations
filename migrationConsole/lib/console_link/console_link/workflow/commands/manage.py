"""Interactive manage command for workflow CLI - interactive tree navigation for log viewing."""
import datetime
import gc
from dataclasses import dataclass

import ijson
import json
import logging
import os
import platform
import subprocess
import sys
import tempfile
import time
from typing import Any, Dict, List, Optional, Tuple, Callable
from pathlib import Path
import base64
import click
import requests
import yaml
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Container, Horizontal
from textual.screen import ModalScreen
from textual.widgets import Button, Footer, Header, Static, Tree
from textual.widgets.tree import TreeNode
from rich.text import Text

from .manage_injections import PodScraperInterface, ArgoService, make_k8s_pod_scraper, WaiterInterface, make_argo_service
# Internal imports
from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService, WorkflowApproveResult
from ..tree_utils import (
    build_nested_workflow_tree,
    clean_display_name,
    filter_tree_nodes,
    get_node_input_parameter,
    get_step_rich_label,
    get_step_status_output,
)
from .output import _initialize_k8s_client
from .status import ConfigConverter, StatusCheckRunner
from .utils import auto_detect_workflow
from console_link.environment import Environment

import psutil

def log_mem(context: str):
    process = psutil.Process(os.getpid())
    mem = process.memory_info().rss / 1024 / 1024
    logger.info(f"MEMORY [{context}]: {mem:.2f} MB")

# --- Constants ---
NODE_TYPE_POD = "Pod"
NODE_TYPE_SUSPEND = "Suspend"
PHASE_RUNNING = "Running"
PHASE_SUCCEEDED = "Succeeded"
LOADING_ROOT_LABEL = "[yellow]⏳ Waiting for Workflow to be created...[/]"

# --- Logging Configuration ---
log_dir = os.path.join(tempfile.gettempdir(), "workflow_manage")
os.makedirs(log_dir, exist_ok=True)
log_file = os.path.join(log_dir, f"manage_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}.log")

file_handler = logging.FileHandler(log_file)
file_handler.setFormatter(logging.Formatter('%(asctime)s - %(levelname)s - [%(threadName)s] - %(name)s - %(message)s'))

# Redirect ALL logging to file
root_logger = logging.getLogger()
root_logger.addHandler(file_handler)
root_logger.setLevel(logging.INFO)
# Remove any existing console handlers
for handler in root_logger.handlers[:]:
    if isinstance(handler, logging.StreamHandler) and handler.stream in (sys.stdout, sys.stderr):
        root_logger.removeHandler(handler)

logger = logging.getLogger(__name__)

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
            if is_remote: return True
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


# --- UI Screens ---
class ConfirmModal(ModalScreen[bool]):
    CSS = """
    ConfirmModal { align: center middle; background: $background 60%; }
    #dialog { width: 45; height: auto; border: thick $primary; background: $surface; padding: 1 2; }
    #question { text-align: center; margin-bottom: 1; }
    #buttons { align: center middle; height: 3; }
    Button { margin: 0 1; min-width: 12; }
    """
    BINDINGS = [
        Binding("y", "confirm", "Yes"),
        Binding("n", "cancel", "No"),
        Binding("escape", "cancel", "No", show=False)
    ]
    def __init__(self, message: str):
        super().__init__()
        self.message = message
    def compose(self) -> ComposeResult:
        with Container(id="dialog"):
            yield Static(self.message, id="question")
            with Horizontal(id="buttons"):
                yield Button("Yes (y)", id="yes", variant="success")
                yield Button("No (n)", id="no", variant="error")
    def on_mount(self) -> None:
        self.query_one("#yes", Button).focus()
    def action_confirm(self) -> None: self.dismiss(True)
    def action_cancel(self) -> None: self.dismiss(False)
    def on_button_pressed(self, event: Button.Pressed) -> None: self.dismiss(event.button.id == "yes")

# --- Main Application ---
class WorkflowTreeApp(App):
    CSS = """
    Tree { scrollbar-gutter: stable; }
    #pod-status { height: 1; padding: 0 1; }
    """
    BINDINGS = []
    def __init__(self, namespace: str,
                 name: str,
                 argo_service: ArgoService,
                 pod_scraper: PodScraperInterface,
                 workflow_waiter: WaiterInterface,
                 refresh_interval):
        super().__init__()
        self.refresh_interval = refresh_interval
        self.waiter = workflow_waiter
        self.pod_scraper = pod_scraper

        log_mem("App Init Start")

        self.title = f"[{namespace}] {name}"
        self.workflow_name = name
        self.namespace = namespace
        self.argo_service = argo_service

        self._is_exiting = False
        self._bulk_pod_name_refresh_active = False
        self.tail_lines = 500

        # Workflow Working State
        self.active_loop_ids = set()
        self.current_node_data = None
        self._current_run_id = None
        self.node_mapping = {}
        self.nodes_with_live_checks = set()
        self.live_check_in_progress = set()
        self._pod_name_cache = {}
        self._pod_name_cache_is_dirty = True
        self.tree_nodes = []
        self.workflow_data = {}

        self.marker_file = Path(tempfile.gettempdir()) / f"wf_ready_{self.workflow_name}.tmp"

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(Tree(LOADING_ROOT_LABEL, id="workflow-tree"), id="tree-container")
        yield Static("", id="pod-status")
        yield Footer()

    def on_mount(self) -> None:
        # Optimize for existence: try an immediate fetch
        self.action_refresh_workflow()

    def on_unmount(self) -> None:
        logger.info("App unmounting - setting exit flag")
        self._is_exiting = True
        if self.marker_file.exists():
            try: self.waiter.reset()
            except Exception: pass

    def _fetch_workflow_data(self):
        try:
            return self.argo_service.get_workflow(self.workflow_name, self.namespace)
        except Exception as e:
            return {"success": False, "error": str(e)}, {}

    def action_refresh_workflow(self) -> None:
        logger.info("action_refresh_workflow")
        self.run_worker(self._refresh_workflow, thread=True, name="_refresh_workflow")

    def _refresh_workflow(self) -> None:
        logger.info(f"WORKER START: _refresh_workflow")
        result = self._fetch_workflow_data()
        self.call_from_thread(self._handle_refresh_workflow_result, result, False)
        logger.info("WORKER END: _refresh_workflow")

    def _handle_refresh_workflow_result(self, result: Tuple[Dict, Dict], force_reload: bool) -> None:
        res, slim_data = result

        logger.info(f"START: _handle_refresh_workflow_result")
        try:
            if not res.get('success'):
                logger.warning(f"Error getting workflow {res.get('error', 'Unknown error')}.")
                slim_data = {}
            self._apply_workflow_updates(slim_data, force_reload)
        finally:
            logger.info(f"END: _handle_refresh_workflow_result")

    def action_manual_refresh(self) -> None:
        """User-triggered manual refresh (Strongly Consistent)."""
        self.notify("Refreshing tree and pod metadata (Strong consistency)...", title="Manual Refresh")
        self.run_worker(self._force_refresh_workflow, thread=True, name="_force_refresh_workflow")

    def _force_refresh_workflow(self) -> None:
        """Sequential fetch: Workflow Tree Data -> Trigger Strong Pod Resolution."""
        logger.info("WORKER START: _force_refresh_workflow")
        result = self._fetch_workflow_data()
        self.call_from_thread(self._handle_refresh_workflow_result, result, True)
        logger.info("WORKER END: _force_refresh_workflow")

    def _wait_for_workflow_worker(self) -> None:
        """Lightweight worker: polls disk, deletes immediately on find."""
        logger.info("WORKER START: _wait_for_workflow_worker")
        self.waiter.trigger() # use the injected value, don't call _start_background_waiter directly
        while not self._is_exiting:
            if self.waiter.checker():  # Use injected checker
                logger.info("Workflow detected via waiter.")
                self.waiter.reset()
                self.call_from_thread(self.action_refresh_workflow)
                break
            time.sleep(0.1)  # Reduced sleep for snappier testing
        logger.info("WORKER END: _wait_for_workflow_worker")

    # --- Pod Resolution Logic ---
    def _trigger_bulk_pod_name_resolve(self, use_cache: bool = True) -> None:
        """Kicks off a background fetch if dirty and not already active."""
        if self._bulk_pod_name_refresh_active or self._is_exiting or \
                (not self._pod_name_cache_is_dirty and use_cache):
            return

        self._bulk_pod_name_refresh_active = True
        self._pod_name_cache_is_dirty = False

        # Pass current_run_id as a fencing token
        run_id = self._current_run_id
        self.run_worker(lambda: self._bulk_resolve_pods(run_id, use_cache), thread=True, name="_bulk_resolve_pods")

    def _bulk_resolve_pods(self, run_id: str, use_cache: bool = True) -> None:
        """Worker: Fetches metadata and returns results tied to a run_id."""
        logger.info(f"WORKER START: _bulk_resolve_pods (use_cache={use_cache})")
        try:
            logger.error("TODO - if this call fails, I need to retry or reset the pod_cache_is_dirty flag!")
            items = self.pod_scraper.fetch_pods_metadata(self.workflow_name, self.namespace, use_cache)
            new_names = {}

            for pod in items:
                metadata = pod.get('metadata', {})
                node_id = metadata.get('annotations', {}).get('workflows.argoproj.io/node-id')
                if node_id:
                    new_names[node_id] = metadata.get('name')

            self.call_from_thread(self._finalize_bulk_resolve, new_names, run_id)
        finally:
            logger.info("WORKER END: _bulk_resolve_pods")

    def _finalize_bulk_resolve(self, new_names: Dict[str, str], worker_run_id: str) -> None:
        """Main Thread: Fences the update and checks if another run is needed."""
        if worker_run_id != self._current_run_id: # Discard if the workflow has changed since this worker started
            logger.info(f"Discarding stale bulk resolve (Worker ID: {worker_run_id} vs Current: {self._current_run_id})")
            self._bulk_pod_name_refresh_active = False # Reset flag so a valid worker can start
            return

        self._pod_name_cache.update(new_names)
        self._bulk_pod_name_refresh_active = False

        self._update_pod_status()
        self._update_dynamic_bindings()

        # If more changes happened during the fetch, loop again
        # (using cached reads since this is automatically triggered)
        if self._pod_name_cache_is_dirty:
            self._trigger_bulk_pod_name_resolve(use_cache=True)

    # --- Event Handlers ---
    def on_tree_node_highlighted(self, event: Tree.NodeHighlighted) -> None:
        self.current_node_data = event.node.data
        self._update_pod_status()
        self._update_dynamic_bindings()

    def on_key(self, event) -> None:
        if event.key == "ctrl+c":
            self.exit()
            event.prevent_default()

    # --- Actions ---
    def _update_dynamic_bindings(self) -> None:
        self._bindings = self._bindings.__class__()
        self.bind("ctrl+p", "command_palette", show=False)
        self.bind("r", "manual_refresh", description="Refresh")
        self.bind("q", "quit", description="Quit")
        self.bind("left", "collapse_node", show=False)
        self.bind("right", "expand_node", show=False)

        if self.current_node_data:
            node_id = self.current_node_data.get('id')
            ntype = self.current_node_data.get('type')
            pod_resolved = node_id in self._pod_name_cache

            if ntype == NODE_TYPE_POD and pod_resolved:
                self.bind("o", "view_logs", description="View Logs")
                self.bind("c", "copy_pod_name", description="Copy Pod Name")
            elif ntype == NODE_TYPE_SUSPEND and self.current_node_data.get('phase') == PHASE_RUNNING:
                self.bind("a", "approve_step", description="Approve")

        self.refresh_bindings()

    def action_view_logs(self) -> None:
        if self.current_node_data and self.current_node_data.get('type') == NODE_TYPE_POD:
            self._show_logs_in_pager(self.current_node_data)

    def action_copy_pod_name(self) -> None:
        if not (self.current_node_data and self.current_node_data.get('type') == NODE_TYPE_POD): return
        pod_name = self._pod_name_cache.get(self.current_node_data['id'])
        if pod_name and copy_to_clipboard(pod_name):
            self.notify(f"📋 Copied: {pod_name}", title="Clipboard", severity="information", timeout=3)
        else: self.notify("❌ Pod name not available", severity="error")

    def action_approve_step(self) -> None:
        node = self.current_node_data
        if not node: return
        def handle_confirm(confirmed: bool) -> None:
            if confirmed: self._execute_approval(node)
        if node.get('type') == NODE_TYPE_SUSPEND and node.get('phase') == PHASE_RUNNING:
            self.push_screen(ConfirmModal(f"Approve '{node.get('display_name')}'?"), handle_confirm)

    def action_expand_node(self) -> None:
        tree = self.query_one("#workflow-tree", Tree)
        if node := tree.cursor_node: node.expand()

    def action_collapse_node(self) -> None:
        tree = self.query_one("#workflow-tree", Tree)
        if node := tree.cursor_node:
            if node.is_expanded: node.collapse()
            elif node.parent: tree.select_node(node.parent)

    # --- Tree Management ---
    def _populate_tree(self, tree: Tree, nodes: List[Dict], parent_node) -> None:
        for node in sorted(nodes, key=lambda n: n.get('started_at') or '9'):
            label = self._get_node_label(node, self.workflow_data)
            tree_node = parent_node.add(label, data=node)
            self.node_mapping[node['id']] = tree_node
            if node['children']: self._populate_tree(tree, node['children'], tree_node)
        # Immediate UI Sync for the newly populated branch
        self._reconcile_live_status_node_with_argo_workflow(parent_node)

    def _get_node_label(self, node: Dict, workflow_data: Dict):
        status_output = get_step_status_output(workflow_data, node['id'])
        return get_step_rich_label(node, status_output)

    def _apply_workflow_updates_unsafe(self, new_data: Dict, force_reload: bool) -> None:
        tree = self.query("#workflow-tree").first()
        if not tree:
            return

        if not new_data:
            logger.warning("No workflow data. Reverting to pending.")
            self.workflow_data = {}
            self.tree_nodes = []
            self.node_mapping.clear()
            tree.clear()
            tree.root.label = LOADING_ROOT_LABEL
            return

        new_run_id = new_data.get('status', {}).get('startedAt')
        is_restart = self._current_run_id != new_run_id

        # Content Guard: Skip if nothing changed
        # Hard Restart Logic (Pending -> Active OR Workflow Restarted)
        if is_restart or self.workflow_data and not force_reload:
            if self.workflow_data.get('metadata', {}).get('resourceVersion') == \
                    new_data.get('metadata', {}).get('resourceVersion'):
                return

        logger.info(f"Setting up tree for {new_data.get('metadata', {}).get('resourceVersion')}")

        if is_restart or not self.workflow_data:
            self._current_run_id = new_run_id
            tree.clear()
            self.node_mapping.clear()
            self.nodes_with_live_checks.clear()
            self._pod_name_cache.clear()

            tree.root.label = "Workflow Steps"
            self.tree_nodes = filter_tree_nodes(build_nested_workflow_tree(new_data))
            self._populate_tree(tree, self.tree_nodes, tree.root)
            tree.root.expand_all()
        else:
            new_nodes = filter_tree_nodes(build_nested_workflow_tree(new_data))
            self._update_tree_recursive(tree.root, new_nodes, new_data)
            self.tree_nodes = new_nodes

        self.workflow_data = new_data

        # Trigger dynamic UI updates (Bindings, Pod Names, Live Checks)
        self._trigger_bulk_pod_name_resolve(not force_reload)
        self._update_dynamic_bindings()
        self._update_pod_status()

    def _apply_workflow_updates(self, new_data: Dict, force_reload: bool) -> None:
        try:
            self._apply_workflow_updates_unsafe(new_data, force_reload)
        except Exception as e:
            logger.error(f"Update error: {e}")
            raise
        finally:
            if not new_data:
                logger.info(f"No workflow found.  Beginning to wait for one.")
                self.run_worker(self._wait_for_workflow_worker, thread=True, name="_wait_for_workflow_worker")
            else:
                logger.info(f"Setting timer to refresh in {self.refresh_interval}s")
                self.set_timer(self.refresh_interval, self.action_refresh_workflow)

    def _update_pod_status(self) -> None:
        status = self.query_one("#pod-status", Static)
        node = self.current_node_data
        if not node or node.get('type') != NODE_TYPE_POD:
            status.update("")
            return

        node_id = node['id']
        if node_id in self._pod_name_cache:
            status.update(f"Pod: [bold green]{self._pod_name_cache[node_id]}[/]")
        else:
            status.update(f"Pod: [gray](name not available)[/]")

    def _update_tree_recursive(self, parent_tree_node, new_nodes: List[Dict], workflow_data: Dict) -> None:
        existing_children = {child.data['id']: child for child in parent_tree_node.children if child.data and 'id' in child.data and not child.data.get('is_ephemeral')}
        new_ids = {node['id'] for node in new_nodes}
        for node in new_nodes:
            node_id = node['id']
            label = self._get_node_label(node, workflow_data)
            if node_id in existing_children:
                tree_node = existing_children[node_id]
                if tree_node.data.get('phase') != node.get('phase'):
                    tree_node.set_label(label)
                tree_node.data = node
            else:
                tree_node = parent_tree_node.add(label, data=node)
                tree_node.expand()
                self.node_mapping[node_id] = tree_node
            if node['children']: self._update_tree_recursive(self.node_mapping[node_id], node['children'], workflow_data)

        for child in list(parent_tree_node.children):
            if child.data and 'id' in child.data and child.data['id'] not in new_ids and not child.data.get('is_ephemeral'):
                self.node_mapping.pop(child.data['id'], None)
                child.remove()

        # Reactive UI Reconciliation: Injects or Removes the Live Status node
        # based on the current active tip of this specific branch.
        self._reconcile_live_status_node_with_argo_workflow(parent_tree_node)

    def _handle_refresh_error(self, error_msg: str) -> None:
        if "not found" in error_msg.lower():
            logger.warning(f"Workflow {self.workflow_name} deleted. Resetting.")
            self._initialize_internal_state()
        else:
            tree = self.query_one("#workflow-tree", Tree)
            tree.root.label = f"⚠️  Fetch failed: {error_msg}"

    # --- External Integrations ---
    def _show_logs_in_pager(self, node_data: Dict) -> None:
        temp_path = None
        try:
            pod_name = self._pod_name_cache.get(node_data['id'])
            if not pod_name: return

            logs = self._get_pod_logs(pod_name)
            with tempfile.NamedTemporaryFile(mode='w', suffix='.log', delete=False) as f:
                f.write(f"=== Logs: {node_data['display_name']} ===\nPod: {pod_name}\n\n{logs}")
                temp_path = f.name
            with self.suspend():
                os.system('clear')
                pager = os.environ.get('PAGER', 'less')
                subprocess.run([pager, temp_path])
        except Exception as e: self.notify(f"Log Error: {e}", severity="error")
        finally:
            if temp_path and os.path.exists(temp_path): os.unlink(temp_path)

    def _get_pod_logs(self, pod_name: str) -> str:
        """Injected fetch of pod logs."""
        try:
            pod = self.pod_scraper.read_pod(pod_name, self.namespace)
            containers = [c.name for c in (pod.spec.init_containers or []) + pod.spec.containers]
            output = []
            for c in containers:
                output.append(f"\n--- Container: {c} ---\n")
                try:
                    logs = self.pod_scraper.read_pod_log(pod_name, self.namespace, c, self.tail_lines)
                    output.append(logs or "(No output)")
                except Exception: output.append("(Not ready)")
            return "".join(output)
        except Exception as e: return f"Error: {e}"

    def _execute_approval(self, node_data: Dict) -> None:
        try:
            res = self.argo_service.approve_step(node_data)
            if res['success']:
                self.notify(f"✅ Approved: {node_data.get('display_name')}")
                self.action_manual_refresh()
            else: self.notify(f"❌ Failed: {res.get('message')}", severity="error")
        except Exception as e: self.notify(f"Error: {e}", severity="error")

    # --- Live Check Logic ---
    def _reconcile_live_status_node_with_argo_workflow(self, parent_tree_node: TreeNode):
        """Derived-state logic: Manages the 'Live Status' node and kicks off its independent loop."""
        real_children = [c.data for c in parent_tree_node.children if c.data and not c.data.get("is_ephemeral")]
        if not real_children: return

        tip = max(real_children, key=lambda x: x.get("started_at") or "")
        tip_id = tip['id']

        # Check for capability (config) and status (active)
        has_config = get_node_input_parameter(tip, 'configContents') is not None
        logger.info(f"has_config={has_config} for {json.dumps(tip)}")
        is_active = not tip.get('phase') in [PHASE_RUNNING, PHASE_SUCCEEDED]

        should_exist = has_config and is_active

        # Look for the header in the parent's children
        existing_live_check_node = next((c for c in parent_tree_node.children if c.data and c.data.get("is_live_status_header")),
                               None)

        if should_exist:
            if not existing_live_check_node:
                # Create the header node
                parent_tree_node.add(
                    "[bold cyan]Live Status:[/]",
                    data={"is_ephemeral": True, "is_live_status_header": True, "origin_id": tip_id}
                )
            else:
                # Update origin link in case the tip shifted within the group
                existing_live_check_node.data["origin_id"] = tip_id

            # Kick off the independent loop if not already running for this tip
            if tip_id not in self.active_loop_ids:
                self.active_loop_ids.add(tip_id)
                self._per_node_live_loop(tip_id)

        elif existing_live_check_node:
            # Node removal is reactive: disappears instantly when Pod succeeds
            # The async loop for this node_id will notice the missing header and exit naturally
            existing_live_check_node.remove()
            self.nodes_with_live_checks.discard(tip_id)

    def _per_node_live_loop(self, last_check_node_id: str) -> None:
        """Independent, self-scheduling update loop for a specific live status display."""
        if self._is_exiting:
            self.active_loop_ids.discard(last_check_node_id)
            return

        tree_node = self.node_mapping.get(last_check_node_id) # node was removed
        if not tree_node:
            self.active_loop_ids.discard(last_check_node_id)
            return # do NOT reschedule a continuation timer

        # Is the 'Live Status' node still present?
        header = next((c for c in tree_node.parent.children
                       if c.data and c.data.get("is_live_status_header")), None)

        if header and header.data.get("origin_id") == last_check_node_id:
            if header.is_expanded:
                self._run_live_check_async(tree_node, tree_node.data)
            # Either way, keep the loop going in case it expands - this should be cheap to run w/out the async
            # status check, and it makes the state model much simpler
            self.set_timer(self.refresh_interval, lambda: self._per_node_live_loop(last_check_node_id))
        else:
            # The node was removed or re-assigned to a new tip.
            self.active_loop_ids.discard(last_check_node_id)

    def _run_live_check_async(self, tree_node, node_data: Dict) -> None:
        node_id = node_data['id']
        now = time.time()
        if node_id in self.live_check_in_progress: return

        self.live_check_in_progress.add(node_id)

        # Worker invokes the direct update method via call_from_thread
        self.run_worker(lambda: self._perform_live_check(node_data), thread=True, name=f"live_check_{node_id}")

    def _perform_live_check(self, node_data: Dict) -> None:
        node_id = node_data.get('id', 'unknown')
        logger.info(f"WORKER START: live_check_{node_id}")
        try:
            # Fetch derived routing/config from workflow parameters
            raw_cfg = get_node_input_parameter(node_data, 'configContents')
            svc_cfg = ConfigConverter.convert_with_jq(raw_cfg)
            if svc_cfg:
                env = Environment(config=yaml.safe_load(svc_cfg))
                name_low = node_data.get('display_name', '').lower()
                node_data['check_type'] = 'snapshot' if 'snapshot' in name_low else 'backfill'

                # Perform the external status check
                result = StatusCheckRunner.run_status_check(env, node_data)
                self.call_from_thread(self._update_live_check_result, node_id, result)
                return
            self.call_from_thread(self._update_live_check_result, node_id, {"error": "Config conversion failed"})
        except Exception as e:
            self.call_from_thread(self._update_live_check_result, node_id, {"error": str(e)})
        finally:
            logger.info(f"WORKER END: live_check_{node_id}")

    def _update_live_check_result(self, node_id: str, result: Dict) -> None:
        """Main-thread update: Fills the UI node with external check results."""
        self.live_check_in_progress.discard(node_id)
        target = self.node_mapping.get(node_id)
        if not target or not target.parent: return

        # Find the ephemeral header linked to this branch tip
        header = next((c for c in target.parent.children if c.data and c.data.get("is_live_status_header")), None)
        if not header: return

        ts = datetime.datetime.now().strftime("%H:%M:%S")
        header.set_label(f"[bold cyan]Live Status[/] [italic gray]({ts})[bold cyan]:[/]")

        # Clear existing ephemeral log lines
        for child in list(header.children): child.remove()

        if result.get('success') and result.get('value'):
            lines = result['value'].replace('\\n', '\n').split('\n')
            for line in lines:
                if line.strip():
                    header.add(line, data={"is_ephemeral": True, "is_live_data": True})
        else:
            msg = result.get('message') or result.get('error') or "Check failed"
            header.add(f"❌ {msg}", data={"is_ephemeral": True})

        self.nodes_with_live_checks.add(node_id)

# --- Entrypoint ---
@click.command(name="manage")
@click.option('--workflow-name', required=False)
@click.option(
    '--argo-server',
    default=f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
            f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}",
    help='Argo Server URL (default: ARGO_SERVER env var, or ARGO_SERVER_SERVICE_HOST:ARGO_SERVER_SERVICE_PORT)'
)
@click.option('--namespace', default='ma')
@click.option('--insecure', is_flag=True, default=False)
@click.option('--token')
@click.pass_context
def manage_command(ctx, workflow_name, argo_server, namespace, insecure, token):
    try:
        service = WorkflowService()
        if not workflow_name:
            workflow_name = auto_detect_workflow(service, namespace, argo_server, token, insecure, ctx)
            if not workflow_name:
                click.echo("No workflows found.  Use --workflow-name to wait for a specific workflow to start.")
                return
        app = WorkflowTreeApp(namespace, workflow_name,
                              make_argo_service(argo_server, False, None),
                              make_k8s_pod_scraper(_initialize_k8s_client(ctx)),
                              WaiterInterface.default(workflow_name, namespace),
                              3.0)
        app.run()
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)
