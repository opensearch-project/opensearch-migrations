"""Interactive manage command for workflow CLI - interactive tree navigation for log viewing."""

import datetime
import logging
import os
import platform
import subprocess
import sys
import tempfile
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
DEFAULT_ARGO_URL = "http://localhost:2746"

# --- Logging Configuration ---
log_dir = os.path.join(tempfile.gettempdir(), "workflow_manage")
os.makedirs(log_dir, exist_ok=True)
log_file = os.path.join(log_dir, f"manage_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}.log")

file_handler = logging.FileHandler(log_file)
file_handler.setFormatter(logging.Formatter('%(asctime)s - %(levelname)s - %(name)s - %(message)s'))
logger = logging.getLogger(__name__)
logger.addHandler(file_handler)
logger.setLevel(logging.INFO)
logger.propagate = False

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
    """
    BINDINGS = []

    def __init__(self, tree_nodes: List[Dict], workflow_data: Dict, k8s_client, name: str, ns: str):
        super().__init__()
        self.title = f"[{ns}] {name}"
        self.tree_nodes = tree_nodes
        self.workflow_data = workflow_data
        self.k8s_client = k8s_client
        self.workflow_name = name
        self.namespace = ns

        self.nodes_with_live_checks = set()
        self.node_mapping = {}
        self.current_node_data = None
        self.tail_lines = 500

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(Tree("Workflow Steps", id="workflow-tree"), id="tree-container")
        yield Footer()

    def on_mount(self) -> None:
        # Redirect logs to file to prevent TUI corruption
        logging.getLogger().handlers = [file_handler]
        tree = self.query_one("#workflow-tree", Tree)
        self._populate_tree(tree, self.tree_nodes, tree.root)
        tree.root.expand_all()
        self._update_dynamic_bindings()
        self.set_interval(3.0, self.action_refresh)

    # --- Event Handlers ---

    def on_tree_node_highlighted(self, event: Tree.NodeHighlighted) -> None:
        self.current_node_data = event.node.data
        self._update_dynamic_bindings()

    def on_tree_node_expanded(self, event: Tree.NodeExpanded) -> None:
        """Central trigger for live checks (mouse or keyboard)."""
        node = event.node
        if (node.data and node.data.get('type') == NODE_TYPE_POD and
                self._should_run_live_check(node.data) and
                self._is_last_leaf_at_level(node) and
                node.data.get('id') not in self.nodes_with_live_checks):
            self._run_live_check_async(node, node.data)

    def on_tree_node_collapsed(self, event: Tree.NodeCollapsed) -> None:
        """Central cleanup for ephemeral nodes (mouse or keyboard)."""
        self._remove_ephemeral_nodes(event.node)

    def on_key(self, event) -> None:
        """Safely exit on Ctrl+C."""
        if event.key == "ctrl+c":
            self.exit()
            event.prevent_default()

    # --- Actions ---

    def _update_dynamic_bindings(self) -> None:
        """Context-aware keybindings."""
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
        try:
            pod_name = _find_pod_by_node_id(self.k8s_client, self.namespace, self.workflow_name, self.current_node_data['id'], self.current_node_data, None)
            if pod_name and copy_to_clipboard(pod_name):
                self.notify(f"📋 Copied: {pod_name}", title="Clipboard", severity="information", timeout=3)
            else: self.notify("❌ Clipboard copy failed", severity="error")
        except Exception as e: self.notify(f"❌ Error: {str(e)}", severity="error")

    def action_approve_step(self) -> None:
        """Handles the approval flow for Suspend nodes."""
        node = self.current_node_data
        if not node: return

        def handle_confirm(confirmed: bool) -> None:
            if confirmed: self._execute_approval(node)

        if node.get('type') == NODE_TYPE_SUSPEND and node.get('phase') == PHASE_RUNNING:
            self.push_screen(ConfirmModal(f"Approve '{node.get('display_name')}'?"), handle_confirm)

    def action_expand_node(self) -> None:
        """Expand via keyboard. Triggers on_tree_node_expanded automatically."""
        tree = self.query_one("#workflow-tree", Tree)
        if node := tree.cursor_node:
            node.expand()

    def action_collapse_node(self) -> None:
        """Collapse via keyboard. Triggers on_tree_node_collapsed automatically."""
        tree = self.query_one("#workflow-tree", Tree)
        if node := tree.cursor_node:
            if node.is_expanded:
                node.collapse()
            elif node.parent:
                tree.select_node(node.parent)

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
            return _get_workflow_data_internal(WorkflowService(), self.workflow_name, DEFAULT_ARGO_URL, self.namespace, False, None)
        except Exception as e:
            return {"success": False, "error": str(e)}, {}

    def on_worker_state_changed(self, event) -> None:
        if not event.worker.is_finished: return
        name = event.worker.name or ""
        if name.startswith("live_check_"):
            self._update_live_check_result(name.replace("live_check_", ""), event.worker.result)
        elif name == "refresh_worker":
            result, new_data = event.worker.result
            if result.get('success'): self._apply_workflow_updates(new_data)
            else: self._handle_refresh_error(result.get('error', 'Workflow not found'))

    def _apply_workflow_updates(self, new_data: Dict) -> None:
        try:
            tree = self.query_one("#workflow-tree", Tree)
            tree.root.label = "Workflow Steps"
            self.title = f"[{self.namespace}] {self.workflow_name}"
            new_nodes = filter_tree_nodes(build_nested_workflow_tree(new_data))
            if self.workflow_data.get('status', {}).get('startedAt') != new_data.get('status', {}).get('startedAt'):
                tree.clear()
                self.node_mapping.clear()
                self.nodes_with_live_checks.clear()
                self._populate_tree(tree, new_nodes, tree.root)
                tree.root.expand_all()
            else:
                self._update_tree_recursive(tree.root, new_nodes, new_data)
            self.workflow_data = new_data
            self.tree_nodes = new_nodes
            self._update_dynamic_bindings()
        except Exception as e: logger.error(f"Update error: {e}")

    def _update_tree_recursive(self, parent_tree_node, new_nodes: List[Dict], workflow_data: Dict) -> None:
        existing_children = {child.data['id']: child for child in parent_tree_node.children if child.data}
        new_ids = {node['id'] for node in new_nodes}
        pods = [n for n in new_nodes if n.get('type') == NODE_TYPE_POD]
        latest_pod_id = max(pods, key=lambda n: n.get('started_at', ''), default=None).get('id') if pods else None

        for node in new_nodes:
            node_id = node['id']
            label = self._get_node_label(node, workflow_data)
            if node_id in existing_children:
                tree_node = existing_children[node_id]
                if tree_node.data.get('phase') != node.get('phase'): tree_node.set_label(label)
                tree_node.data = node
                if node.get('type') == NODE_TYPE_POD and node_id != latest_pod_id: self._remove_ephemeral_nodes(tree_node)
            else:
                tree_node = parent_tree_node.add(label, data=node)
                self.node_mapping[node_id] = tree_node
            if node['children']: self._update_tree_recursive(self.node_mapping[node_id], node['children'], workflow_data)

        for child in list(parent_tree_node.children):
            if child.data and child.data['id'] not in new_ids:
                self.node_mapping.pop(child.data['id'], None)
                child.remove()

    def _handle_refresh_error(self, error_msg: str) -> None:
        tree = self.query_one("#workflow-tree", Tree)
        tree.root.label = f"⚠️  Fetch failed: {error_msg}"

    # --- External Integrations ---

    def _show_logs_in_pager(self, node_data: Dict) -> None:
        temp_path = None
        try:
            pod_name = _find_pod_by_node_id(self.k8s_client, self.namespace, self.workflow_name, node_data['id'], node_data, None)
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
            res = WorkflowService().approve_workflow(self.workflow_name, self.namespace, DEFAULT_ARGO_URL, None, False, f"id={node_data.get('id')}")
            if res['success']:
                self.notify(f"✅ Approved: {node_data.get('display_name')}")
                self.action_refresh()
            else: self.notify(f"❌ Failed: {res.get('message')}", severity="error")
        except Exception as e: self.notify(f"Error: {e}", severity="error")

    # --- Live Check Logic ---

    def _remove_ephemeral_nodes(self, tree_node: TreeNode) -> None:
        for child in list(tree_node.children):
            if child.data and child.data.get("is_ephemeral"):
                child.remove()
                if tree_node.data:
                    self.nodes_with_live_checks.discard(tree_node.data['id'])

    def _should_run_live_check(self, node_data: Dict) -> bool:
        has_config = get_node_input_parameter(node_data, 'configContents')
        has_output = any(p.get('name') == 'statusOutput' for p in node_data.get('outputs', {}).get('parameters', []))
        return node_data.get('phase') != PHASE_SUCCEEDED and has_config and has_output

    def _is_last_leaf_at_level(self, tree_node) -> bool:
        if not tree_node.parent: return True
        siblings = [c for c in tree_node.parent.children if c.data and c.data.get('type') == NODE_TYPE_POD]
        return not siblings or tree_node == max(siblings, key=lambda n: n.data.get('started_at', ''))

    def _run_live_check_async(self, tree_node, node_data: Dict) -> None:
        tree_node.add("🔄 Running live check...", data={"is_ephemeral": True})
        worker = self.run_worker(lambda: self._perform_live_check(node_data), thread=True)
        worker.name = f"live_check_{node_data['id']}"

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
        if target := self.node_mapping.get(node_id):
            self._remove_ephemeral_nodes(target)
            icon, msg = ("✅", result.get('message')) if result.get('success') else ("❌", result.get('error'))
            target.add(f"{icon} {msg or 'Check finished'}", data={"is_ephemeral": True})
            self.nodes_with_live_checks.add(node_id)
            target.expand()

# --- Entrypoint ---

@click.command(name="manage")
@click.argument('workflow_name', required=False)
@click.option('--argo-server', default=DEFAULT_ARGO_URL)
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
        app = WorkflowTreeApp(nodes, data, _initialize_k8s_client(ctx), workflow_name, namespace)
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