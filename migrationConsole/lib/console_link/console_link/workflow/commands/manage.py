"""Interactive manage command for workflow CLI - interactive tree navigation for log viewing."""
import datetime
import gc
import ijson
import json
import logging
import os
import platform
import subprocess
import sys
import tempfile
import time
from typing import Any, Dict, List, Optional, Tuple
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

# Internal imports
from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService
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
        if is_remote or os.environ.get("TERM") in ["xterm-256color", "screen-256color"]:
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
    def __init__(self, tree_nodes: List[Dict], workflow_data: Dict, k8s_client, name: str, ns: str, argo_server: str):
        super().__init__()
        log_mem("App Init Start")
        self.title = f"[{ns}] {name}"
        self.tree_nodes = tree_nodes
        self.workflow_data = workflow_data
        self.k8s_client = k8s_client
        self.workflow_name = name
        self.namespace = ns
        self.argo_server = argo_server

        self.nodes_with_live_checks = set()
        self.live_check_in_progress = set()
        self.last_check_time = {}
        self._active_branch_tips = set()

        self.node_mapping = {}
        self.current_node_data = None
        self.tail_lines = 500

        # Pod Resolution State
        self._pod_name_cache = {}
        self._pod_name_cache_is_dirty = True
        self._bulk_pod_name_refresh_active = False
        self._is_exiting = False
        # Track the unique ID of the current workflow run to prevent stale worker updates
        self._current_run_id = workflow_data.get('status', {}).get('startedAt')
        log_mem("App Init Complete")

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(Tree("Workflow Steps", id="workflow-tree"), id="tree-container")
        yield Static("", id="pod-status")
        yield Footer()

    def on_mount(self) -> None:
        tree = self.query_one("#workflow-tree", Tree)
        self._populate_tree(tree, self.tree_nodes, tree.root)
        tree.root.expand_all()
        self._update_dynamic_bindings()
        
        # Initialize active branch tips cache
        self._active_branch_tips = self._get_active_branch_tips(self.tree_nodes)

        # Seed pod name cache
        self._trigger_bulk_pod_name_resolve()

        self.set_interval(3.0, self.action_refresh)

    def on_unmount(self) -> None:
        logger.info("App unmounting - setting exit flag")
        self._is_exiting = True

    # --- Pod Resolution Logic ---
    def _fetch_workflow_pods_metadata(self, workflow_name: str, use_cache: bool = True) -> list[dict]:
        """High-performance fetch of pod metadata for a specific workflow."""
        query_params = [('labelSelector', f"workflows.argoproj.io/workflow={workflow_name}")]

        # Hybrid Consistency: resourceVersion=0 hits the API cache (fast).
        # Omitting it forces a strongly consistent read from etcd (safe/slow).
        if use_cache:
            query_params.append(('resourceVersion', '0'))

        try:
            # use call_api to bypass the V1Pod object creation, which is much slower
            response_tuple = self.k8s_client.api_client.call_api(
                f'/api/v1/namespaces/{self.namespace}/pods', 'GET',
                # Use headers to request ONLY metadata (strips spec and status)
                header_params={'Accept': 'application/json;as=PartialObjectMetadataList;v=v1;g=meta.k8s.io'},
                query_params=query_params,
                _preload_content=False,
                _request_timeout=10
            )

            response_object = response_tuple[0]
            try:
                data = json.loads(response_object.read())
                return data.get('items', [])
            finally:
                response_object.close()
        except Exception as e:
            logger.error(f"Failed to fetch pod metadata for {workflow_name} (use_cache={use_cache}): {e}")
            return []

    def _trigger_bulk_pod_name_resolve(self, use_cache: bool = True) -> None:
        """Kicks off a background fetch if dirty and not already active."""
        if self._bulk_pod_name_refresh_active or self._is_exiting or not self._pod_name_cache_is_dirty:
            return

        self._bulk_pod_name_refresh_active = True
        self._pod_name_cache_is_dirty = False

        # Pass current_run_id as a fencing token
        run_id = self._current_run_id
        self.run_worker(lambda: self._bulk_resolve_pods(run_id, use_cache), thread=True, name="bulk_pod_resolve")

    def _bulk_resolve_pods(self, run_id: str, use_cache: bool = True) -> None:
        """Worker: Fetches metadata and returns results tied to a run_id."""
        logger.info(f"WORKER START: _bulk_resolve_pods (use_cache={use_cache})")
        try:
            items = self._fetch_workflow_pods_metadata(self.workflow_name, use_cache=use_cache)
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

    def on_tree_node_expanded(self, event: Tree.NodeExpanded) -> None:
        node = event.node
        node_id = node.data.get('id') if node.data else None

        if (node.data and node.data.get('type') == NODE_TYPE_POD and
                self._should_run_live_check(node.data) and
                self._is_active_tip(node_id)):
            if node_id not in self.nodes_with_live_checks:
                self._run_live_check_async(node, node.data)

        elif node.data and node.data.get("is_live_status_header"):
            has_real_data = any(c.data and c.data.get("is_live_data") for c in node.children)
            if not has_real_data:
                origin_id = node.data.get("origin_id")
                if origin_node := self.node_mapping.get(origin_id):
                    self.nodes_with_live_checks.discard(origin_id)
                    self._run_live_check_async(origin_node, origin_node.data)

    def on_tree_node_collapsed(self, event: Tree.NodeCollapsed) -> None:
        node = event.node
        if node.data and node.data.get("is_live_status_header"):
            origin_id = node.data.get("origin_id")
            self.nodes_with_live_checks.discard(origin_id)
            node.set_label("[bold cyan]Live Status:[/]")
            for child in list(node.children): child.remove()

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

    def action_refresh(self) -> None:
        self.run_worker(self._fetch_workflow_data, thread=True, name="refresh_worker")

    def action_manual_refresh(self) -> None:
        """User-triggered manual refresh (Strongly Consistent)."""
        self.notify("Refreshing tree and pod metadata (Strong consistency)...", title="Manual Refresh")
        self.run_worker(self._force_refresh_sequence, thread=True, name="manual_refresh_worker")

    def _force_refresh_sequence(self) -> Tuple[Dict, Dict]:
        """Sequential fetch: Workflow Tree Data -> Trigger Strong Pod Resolution."""
        logger.info("WORKER START: _force_refresh_sequence")
        result = self._fetch_workflow_data()
        self.call_from_thread(self._handle_manual_refresh_result, result)
        return result

    def _handle_manual_refresh_result(self, result: Tuple[Dict, Dict]) -> None:
        """Main Thread: Process tree result and then trigger a STRONG pod fetch."""
        res, slim_data = result
        if res.get('success'):
            self._apply_workflow_updates(slim_data)
            self._pod_name_cache_is_dirty = True
            self._trigger_bulk_pod_name_resolve(use_cache=False)

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

    def _get_node_label(self, node: Dict, workflow_data: Dict):
        status_output = get_step_status_output(workflow_data, node['id'])
        return get_step_rich_label(node, status_output)

    # --- Data Fetching & Workers ---
    def _fetch_workflow_data(self) -> Tuple[Dict, Dict]:
        logger.info("WORKER START: refresh_worker")
        try:
            return _get_workflow_data_internal(WorkflowService(), self.workflow_name, self.argo_server, self.namespace, False, None)
        except Exception as e:
            return {"success": False, "error": str(e)}, {}
        finally:
            logger.info("WORKER END: refresh_worker")

    def on_worker_state_changed(self, event) -> None:
        """The Main Thread's 'Gatekeeper' for state changes."""
        if not event.worker.is_finished:
            return

        name = event.worker.name or ""
        if name == "refresh_worker" and event.worker.result:
            res, slim_data = event.worker.result
            if res.get('success'):
                self._apply_workflow_updates(slim_data)

    def _apply_workflow_updates(self, new_data: Dict) -> None:
        try:
            # Content Guard: Skip everything if no changes detected at the Argo level
            old_rv = self.workflow_data.get('metadata', {}).get('resourceVersion')
            new_rv = new_data.get('metadata', {}).get('resourceVersion')
            new_run_id = new_data.get('status', {}).get('startedAt')

            is_restart = self._current_run_id != new_run_id

            # If the version is the same, and it's not a totally new workflow, do nothing.
            if old_rv == new_rv and not is_restart:
                return

            log_mem("Refresh Update Start (Changes Detected)")

            tree = self.query_one("#workflow-tree", Tree)
            tree.root.label = "Workflow Steps"
            old_scroll_x, old_scroll_y = tree.scroll_offset

            new_nodes = filter_tree_nodes(build_nested_workflow_tree(new_data))

            if is_restart:
                logger.info(f"Workflow restart/swap (Run ID: {new_run_id}). Hard reset.")
                self._current_run_id = new_run_id

                # Full State Wipe
                tree.clear()
                self.node_mapping.clear()
                self.nodes_with_live_checks.clear()
                self.live_check_in_progress.clear()
                self._pod_name_cache.clear()

                self._populate_tree(tree, new_nodes, tree.root)
                tree.root.expand_all()
            else:
                # Soft Update
                self._update_tree_recursive(tree.root, new_nodes, new_data)

            # Update persistent storage
            self.workflow_data = new_data
            self.tree_nodes = new_nodes
            
            # Update active branch tips cache
            self._active_branch_tips = self._get_active_branch_tips(new_nodes)

            # Trigger Pod Cache Sync (Fast Path)
            self._pod_name_cache_is_dirty = True
            self._trigger_bulk_pod_name_resolve(use_cache=True)

            # --- Live Status Management ---
            latest_active_ids = self._get_latest_pod_ids_per_branch(new_nodes)

            for node_id in list(self.nodes_with_live_checks):
                if node_id not in latest_active_ids:
                    if t_node := self.node_mapping.get(node_id):
                        self._remove_ephemeral_nodes(t_node)

            for latest_active_id in latest_active_ids:
                latest_node = self.node_mapping.get(latest_active_id)
                if latest_node and self._should_run_live_check(latest_node.data):
                    self._run_live_check_async(latest_node, latest_node.data)

            # Update UI components
            self._update_dynamic_bindings()
            self._update_pod_status()

            tree.scroll_to(old_scroll_x, old_scroll_y, animate=False)
            gc.collect()
            log_mem("Post-Refresh Cleanup Complete")

        except Exception as e:
            logger.error(f"Update error: {e}")

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
                    if node.get('phase') == PHASE_SUCCEEDED: self._remove_ephemeral_nodes(tree_node)
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

    def _handle_refresh_error(self, error_msg: str) -> None:
        tree = self.query_one("#workflow-tree", Tree)
        tree.clear()
        self.node_mapping.clear()
        self.nodes_with_live_checks.clear()
        self.live_check_in_progress.clear()
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
        try:
            pod = self.k8s_client.read_namespaced_pod(name=pod_name, namespace=self.namespace)
            containers = [c.name for c in (pod.spec.init_containers or []) + pod.spec.containers]
            output = []
            for c in containers:
                output.append(f"\n--- Container: {c} ---\n")
                try:
                    logs = self.k8s_client.read_namespaced_pod_log(pod_name, self.namespace, container=c, tail_lines=self.tail_lines)
                    output.append(logs or "(No output)")
                except Exception: output.append("(Not ready)")
            return "".join(output)
        except Exception as e: return f"Error: {e}"

    def _execute_approval(self, node_data: Dict) -> None:
        try:
            res = WorkflowService().approve_workflow(self.workflow_name, self.namespace, self.argo_server, None, False, f"id={node_data.get('id')}")
            if res['success']:
                self.notify(f"✅ Approved: {node_data.get('display_name')}")
                self.action_manual_refresh()
            else: self.notify(f"❌ Failed: {res.get('message')}", severity="error")
        except Exception as e: self.notify(f"Error: {e}", severity="error")

    # --- Live Check Logic ---
    def _get_active_branch_tips(self, nodes: List[Dict]) -> set:
        """Return the latest active pod ID from each parallel branch."""
        latest_ids = set()
        def find_branches(n_list):
            for n in n_list:
                children = n.get('children', [])
                if len(children) > 1:
                    for branch in children:
                        all_pods = []
                        def collect(node):
                            if node.get('type') == NODE_TYPE_POD and node.get('phase') != PHASE_SUCCEEDED:
                                all_pods.append(node)
                            for c in node.get('children', []): collect(c)
                        collect(branch)
                        if all_pods:
                            latest = max(all_pods, key=lambda p: (p.get('started_at') or '', p.get('id', '')))
                            latest_ids.add(latest.get('id'))
                elif children: find_branches(children)
        find_branches(nodes)
        return latest_ids

    def _is_active_tip(self, node_id: str) -> bool:
        """Check if this node is an active tip of a parallel branch."""
        return node_id in self._active_branch_tips

    def _remove_ephemeral_nodes(self, tree_node: TreeNode) -> None:
        parent = tree_node.parent if tree_node.parent else tree_node
        for child in list(parent.children):
            if child.data and child.data.get("is_ephemeral"):
                if child.data.get("is_live_status_header"):
                    child.set_label("[bold cyan]Live Status:[/]")
                child.remove()
        if tree_node.data and 'id' in tree_node.data:
            nid = tree_node.data['id']
            self.nodes_with_live_checks.discard(nid)
            self.live_check_in_progress.discard(nid)

    def _should_run_live_check(self, node_data: Dict) -> bool:
        has_config = get_node_input_parameter(node_data, 'configContents')
        return node_data.get('phase') != PHASE_SUCCEEDED and has_config

    def _run_live_check_async(self, tree_node, node_data: Dict) -> None:
        node_id = node_data['id']
        now = time.time()
        if now - self.last_check_time.get(node_id, 0) < 2.0: return
        if node_id in self.live_check_in_progress: return

        self.live_check_in_progress.add(node_id)
        self.last_check_time[node_id] = now

        parent = tree_node.parent if tree_node.parent else tree_node
        header = next((c for c in parent.children if c.data and c.data.get("is_live_status_header")), None)

        if header and len(parent.children) > 0 and parent.children[-1] != header:
            was_expanded = header.is_expanded
            header.remove()
            header = parent.add("[bold cyan]Live Status:[/]", data={"is_ephemeral": True, "is_live_status_header": True, "origin_id": node_id})
            if was_expanded: header.expand()
        elif not header:
            header = parent.add("[bold cyan]Live Status:[/]", data={"is_ephemeral": True, "is_live_status_header": True, "origin_id": node_id})

        if not any(c.data and c.data.get("is_live_data") for c in header.children):
            if not any(c.data and c.data.get("is_loading_indicator") for c in header.children):
                header.add("🔄 Checking...", data={"is_ephemeral": True, "is_loading_indicator": True})

        worker = self.run_worker(lambda: self._perform_live_check(node_data), thread=True)
        worker.name = f"live_check_{node_id}"

    def _perform_live_check(self, node_data: Dict) -> Dict:
        node_id = node_data.get('id', 'unknown')
        logger.info(f"WORKER START: live_check_{node_id}")
        try:
            raw_cfg = get_node_input_parameter(node_data, 'configContents')
            svc_cfg = ConfigConverter.convert_with_jq(raw_cfg)
            if svc_cfg:
                env = Environment(config=yaml.safe_load(svc_cfg))
                name_low = node_data.get('display_name', '').lower()
                node_data['check_type'] = 'snapshot' if 'snapshot' in name_low else 'backfill'
                return StatusCheckRunner.run_status_check(env, node_data)
            return {"error": "Config conversion failed"}
        except Exception as e:
            return {"error": str(e)}
        finally:
            logger.info(f"WORKER END: live_check_{node_id}")

    def _update_live_check_result(self, node_id: str, result: Dict) -> None:
        self.live_check_in_progress.discard(node_id)
        target = self.node_mapping.get(node_id)
        if not target or not target.parent: return

        header = next((c for c in target.parent.children if c.data and c.data.get("is_live_status_header")), None)
        if not header: return

        if target.data.get('phase') == PHASE_SUCCEEDED:
            header.remove()
            return

        ts = datetime.datetime.now().strftime("%H:%M:%S")
        header.set_label(f"[bold cyan]Live Status[/] [italic gray](Updated: {ts})[bold cyan]:[/]")

        for child in list(header.children): child.remove()

        if result.get('success') and result.get('value'):
            lines = result['value'].replace('\\n', '\n').split('\n')
            for line in lines:
                if line.strip():
                    header.add(line, data={"is_ephemeral": True, "is_live_data": True})
            header.expand()
        else:
            msg = result.get('message') or result.get('error') or "Check failed"
            header.add(f"❌ {msg}", data={"is_ephemeral": True})

        self.nodes_with_live_checks.add(node_id)

# --- Entrypoint ---
@click.command(name="manage")
@click.argument('workflow_name', required=False)
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
            if not workflow_name: return
        result, data = _get_workflow_data_internal(service, workflow_name, argo_server, namespace, insecure, token)
        if not result['success']:
            click.echo(f"Error: {result['error']}", err=True)
            ctx.exit(ExitCode.FAILURE.value)
        nodes = filter_tree_nodes(build_nested_workflow_tree(data))
        if not nodes:
            click.echo("No workflow steps found.")
            return
        app = WorkflowTreeApp(nodes, data, _initialize_k8s_client(ctx), workflow_name, namespace, argo_server)
        app.run()
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)

def _get_workflow_data_internal(service, name, server, ns, insecure, token):
    log_mem("Fetch Data Start")
    res = service.get_workflow_status(name, ns, server, token, insecure)
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{server}/api/v1/workflows/{ns}/{name}"

    resp = requests.get(url, headers=headers, verify=not insecure, stream=True)
    if resp.status_code != 200:
        return res, {}

    slim_nodes = {}
    node_count = 0
    try:
        parser = ijson.kvitems(resp.raw, 'status.nodes')
        for node_id, node in parser:
            node_count += 1
            # EXTREME SLIMMING: Only keep what is actually rendered in tree_utils
            slim_nodes[node_id] = {
                "id": node_id,
                "displayName": clean_display_name(node.get("displayName")), # no reason to keep the massive version
                "phase": node.get("phase"),
                "type": node.get("type"),
                "boundaryID": node.get("boundaryID"),
                "children": node.get("children", []),
                "startedAt": node.get("startedAt"),
                "finishedAt": node.get("finishedAt"),
                # Only keep inputs/outputs if they contain specific UI keys
                "inputs": {"parameters": [p for p in node.get("inputs", {}).get("parameters", []) if p['name'] in ('groupName', 'configContents')]},
                "outputs": {"parameters": [p for p in node.get("outputs", {}).get("parameters", []) if p['name'] in ('statusOutput', 'overriddenPhase')]}
            }
    except Exception as e:
        logger.error(f"Streaming parse failed: {e}")
    finally:
        resp.close()

    slim_data = {
        "metadata": {"name": name},
        "status": {
            "nodes": slim_nodes,
            "startedAt": res.get('workflow', {}).get('status', {}).get('startedAt')
        }
    }

    log_mem(f"Fetch Data Complete ({node_count} nodes)")
    return res, slim_data
