"""Interactive manage command for workflow CLI - interactive tree navigation for log viewing."""
import datetime
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
    filter_tree_nodes,
    get_node_input_parameter,
    get_step_rich_label,
    get_step_status_output,
)
from .output import _find_pod_by_node_id, _initialize_k8s_client
from .status import ConfigConverter, StatusCheckRunner
from .utils import auto_detect_workflow
from console_link.environment import Environment

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

        self.node_mapping = {}
        self.current_node_data = None
        self.tail_lines = 500

        # Pod Resolution State
        self._pod_name_cache = {}
        self._pod_resolution_in_progress = set()
        self._is_exiting = False

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

        # Seed cache and start crawler
        self.run_worker(self._bulk_resolve_pods, thread=True, name="bulk_pod_resolve")
        self._speculative_pod_resolution(self.tree_nodes)

        self.set_interval(3.0, self.action_refresh)

    def on_unmount(self) -> None:
        self._is_exiting = True

    # --- Pod Resolution Logic ---
    def _bulk_resolve_pods(self) -> None:
        """Seed cache using a single K8s list call with granular trace logging."""
        try:
            logger.info(f"STARTING bulk pod resolution for workflow: {self.workflow_name}")

            # Explicit timeout to prevent the 'hanging' you noticed earlier
            pods = self.k8s_client.list_namespaced_pod(
                namespace=self.namespace,
                label_selector=f"workflows.argoproj.io/workflow={self.workflow_name}",
                _request_timeout=30
            )

            if not pods or pods.items is None:
                logger.warning("Bulk resolve: K8s API returned a successful response but items list is None.")
                return

            found_names = [p.metadata.name for p in pods.items if p.metadata]
            logger.debug(f"K8s API found {len(found_names)} pods: {found_names}")

            count = 0
            for pod in pods.items:
                metadata = getattr(pod, 'metadata', None)
                if metadata is None:
                    logger.debug("Bulk resolve: Encountered pod object with NO metadata field.")
                    continue

                pod_name = getattr(metadata, 'name', 'unknown-pod')

                annotations = getattr(metadata, 'annotations', None)
                if annotations is None:
                    logger.debug(f"Bulk resolve: Pod '{pod_name}' has metadata but annotations is None.")
                    continue

                node_id = annotations.get('workflows.argoproj.io/node-id')
                if node_id:
                    self._pod_name_cache[node_id] = pod_name
                    count += 1
                else:
                    logger.debug(f"Bulk resolve: Pod '{pod_name}' is missing the node-id annotation.")

            logger.info(f"Bulk resolve FINISHED. Successfully cached {count} pod names.")
            self.call_from_thread(self._update_pod_status)

        except Exception as e:
            # logger.exception provides a full stack trace to the log file
            logger.exception(f"Bulk resolution worker failed: {str(e)}")

    def _resolve_pod_worker(self, node: Dict) -> Optional[str]:
        """Persistent worker that polls K8s until the pod name is found."""
        node_id = node['id']
        logger.debug(f"Worker [{node_id}] starting persistent poll.")

        for attempt in range(300):
            if self._is_exiting:
                return None
            try:
                # Calling your helper which searches for the specific node-id
                pod_name = _find_pod_by_node_id(self.k8s_client, self.namespace, self.workflow_name, node_id, node, None)
                if pod_name:
                    logger.info(f"Worker [{node_id}] RESOLVED to pod: {pod_name}")
                    return pod_name
            except Exception as e:
                # Log the error every 10 seconds so you can see why it's failing
                if attempt % 10 == 0:
                    logger.warning(f"Worker [{node_id}] attempt {attempt} error: {str(e)}")

            time.sleep(1.0)

        logger.warning(f"Worker [{node_id}] GAVE UP after 300 seconds.")
        return None

    def _speculative_pod_resolution(self, nodes: List[Dict]) -> None:
        """Recursive crawler to kick off workers for new pods."""
        for node in nodes:
            node_id = node.get('id')
            if (node.get('type') == NODE_TYPE_POD and
                    node_id not in self._pod_name_cache and
                    node_id not in self._pod_resolution_in_progress):

                self._pod_resolution_in_progress.add(node_id)
                self.run_worker(
                    lambda n=node: self._resolve_pod_worker(n),
                    thread=True,
                    name=f"pod_resolve_{node_id}"
                )
            if node.get('children'):
                self._speculative_pod_resolution(node['children'])

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
                self._is_branch_latest(node_id)):
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
        self.bind("r", "refresh", description="Refresh")
        self.bind("q", "quit", description="Quit")
        self.bind("left", "collapse_node", show=False)
        self.bind("right", "expand_node", show=False)
        if self.current_node_data:
            ntype = self.current_node_data.get('type')
            if ntype == NODE_TYPE_POD:
                self.bind("o", "view_logs", description="View Logs")
                self.bind("c", "copy_pod_name", description="Copy Pod Name")
            elif ntype == NODE_TYPE_SUSPEND and self.current_node_data.get('phase') == PHASE_RUNNING:
                self.bind("a", "approve_step", description="Approve")
        self.refresh_bindings()

    def action_refresh(self) -> None:
        self.run_worker(self._fetch_workflow_data, thread=True, name="refresh_worker")

    def action_view_logs(self) -> None:
        if self.current_node_data and self.current_node_data.get('type') == NODE_TYPE_POD:
            self._show_logs_in_pager(self.current_node_data)

    def action_copy_pod_name(self) -> None:
        if not (self.current_node_data and self.current_node_data.get('type') == NODE_TYPE_POD): return
        pod_name = self._pod_name_cache.get(self.current_node_data['id'])
        if pod_name and copy_to_clipboard(pod_name):
            self.notify(f"📋 Copied: {pod_name}", title="Clipboard", severity="information", timeout=3)
        else: self.notify("❌ Pod name not resolved yet", severity="error")

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
        try:
            return _get_workflow_data_internal(WorkflowService(), self.workflow_name, self.argo_server, self.namespace, False, None)
        except Exception as e:
            return {"success": False, "error": str(e)}, {}

    def on_worker_state_changed(self, event) -> None:
        if not event.worker.is_finished: return
        name = event.worker.name or ""

        if name.startswith("pod_resolve_"):
            node_id = name.replace("pod_resolve_", "")
            self._pod_resolution_in_progress.discard(node_id)
            if pod_name := event.worker.result:
                self._pod_name_cache[node_id] = pod_name
                if self.current_node_data and self.current_node_data.get('id') == node_id:
                    self._update_pod_status()
        elif name.startswith("live_check_"):
            self._update_live_check_result(name.replace("live_check_", ""), event.worker.result)
        elif name == "refresh_worker":
            result, new_data = event.worker.result
            if result.get('success'): self._apply_workflow_updates(new_data)
            else: self._handle_refresh_error(result.get('error', 'Workflow not found'))

    def _apply_workflow_updates(self, new_data: Dict) -> None:
        try:
            tree = self.query_one("#workflow-tree", Tree)
            # Restore root label if it was showing an error
            tree.root.label = "Workflow Steps"
            # Capture current scroll position to prevent jumping
            old_scroll_x, old_scroll_y = tree.scroll_offset

            new_nodes = filter_tree_nodes(build_nested_workflow_tree(new_data))

            if self.workflow_data.get('status', {}).get('startedAt') != new_data.get('status', {}).get('startedAt'):
                tree.clear()
                self.node_mapping.clear()
                self.nodes_with_live_checks.clear()
                self.live_check_in_progress.clear()
                self._pod_name_cache.clear()
                self._pod_resolution_in_progress.clear()
                self._populate_tree(tree, new_nodes, tree.root)
                tree.root.expand_all()
                self.run_worker(self._bulk_resolve_pods, thread=True, name="bulk_pod_resolve")
            else:
                self._update_tree_recursive(tree.root, new_nodes, new_data)

            self.workflow_data = new_data
            self.tree_nodes = new_nodes

            # speculatively resolve any new pods appearing
            self._speculative_pod_resolution(new_nodes)

            # Find the active edge per branch: latest node that hasn't succeeded yet in each branch
            latest_active_ids = self._get_latest_pod_ids_per_branch(new_nodes)

            # 1. Prune Live Status if it no longer represents a branch front
            for node_id in list(self.nodes_with_live_checks):
                if node_id not in latest_active_ids:
                    if t_node := self.node_mapping.get(node_id):
                        self._remove_ephemeral_nodes(t_node)

            # 2. Trigger/Reposition Live Status for each branch
            for latest_active_id in latest_active_ids:
                latest_node = self.node_mapping.get(latest_active_id)
                if latest_node and self._should_run_live_check(latest_node.data):
                    self._run_live_check_async(latest_node, latest_node.data)

            self._update_dynamic_bindings()
            self._update_pod_status()

            # Anchor scroll back to previous position
            tree.scroll_to(old_scroll_x, old_scroll_y, animate=False)

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
        elif node_id in self._pod_resolution_in_progress:
            status.update(f"Pod: [italic yellow]Resolving...[/]")
        else:
            status.update(f"Pod: [gray]({node.get('phase', 'Pending')})[/]")

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
            if not pod_name:
                pod_name = _find_pod_by_node_id(self.k8s_client, self.namespace, self.workflow_name, node_data['id'], node_data, None)

            if not pod_name:
                self.notify("Pod name not resolved", severity="warning")
                return

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
                self.action_refresh()
            else: self.notify(f"❌ Failed: {res.get('message')}", severity="error")
        except Exception as e: self.notify(f"Error: {e}", severity="error")

    # --- Live Check Logic ---
    def _get_latest_pod_ids_per_branch(self, nodes: List[Dict]) -> set:
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

    def _is_branch_latest(self, node_id: str) -> bool:
        return node_id in self._get_latest_pod_ids_per_branch(self.tree_nodes)

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
        try:
            raw_cfg = get_node_input_parameter(node_data, 'configContents')
            svc_cfg = ConfigConverter.convert_with_jq(raw_cfg)
            if svc_cfg:
                env = Environment(config=yaml.safe_load(svc_cfg))
                name_low = node_data.get('display_name', '').lower()
                node_data['check_type'] = 'snapshot' if 'snapshot' in name_low else 'backfill'
                return StatusCheckRunner.run_status_check(env, node_data)
            return {"error": "Config conversion failed"}
        except Exception as e: return {"error": str(e)}

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
    res = service.get_workflow_status(name, ns, server, token, insecure)
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{server}/api/v1/workflows/{ns}/{name}"
    resp = requests.get(url, headers=headers, verify=not insecure)
    return res, (resp.json() if resp.status_code == 200 else {})