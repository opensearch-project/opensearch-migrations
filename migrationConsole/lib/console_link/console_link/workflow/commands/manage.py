"""Interactive manage command for workflow CLI - interactive tree navigation for log viewing."""

import logging
import os
import yaml
import click
import subprocess
import tempfile
import datetime
from typing import Dict, List, Any, Optional

from textual.app import App, ComposeResult
from textual.widgets import Tree, Footer, Header, Static, Button
from textual.binding import Binding
from textual.containers import Container, Horizontal
from textual.screen import ModalScreen

# Internal imports - assuming these exist in your project structure
from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService
from ..tree_utils import (
    build_nested_workflow_tree,
    filter_tree_nodes,
    get_step_rich_label,
    get_step_status_output,
    get_node_input_parameter
)
from .output import _initialize_k8s_client, _find_pod_by_node_id
from .utils import auto_detect_workflow
from .status import ConfigConverter, StatusCheckRunner
from console_link.environment import Environment

# Node types and phases
NODE_TYPE_POD = "Pod"
NODE_TYPE_SUSPEND = "Suspend"
PHASE_RUNNING = "Running"
PHASE_SUCCEEDED = "Succeeded"

# Set up file logging for debugging
log_dir = os.path.join(tempfile.gettempdir(), "workflow_manage")
os.makedirs(log_dir, exist_ok=True)
log_file = os.path.join(log_dir, f"manage_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}.log")
file_handler = logging.FileHandler(log_file)
file_handler.setLevel(logging.INFO)
formatter = logging.Formatter('%(asctime)s - %(levelname)s - %(name)s - %(message)s')
file_handler.setFormatter(formatter)

logger = logging.getLogger(__name__)
logger.addHandler(file_handler)
logger.setLevel(logging.INFO)
logger.propagate = False

class ConfirmModal(ModalScreen[bool]):
    """Modal dialog for confirmation."""
    CSS = """
    ConfirmModal {
        align: center middle;
        background: $background 60%;
    }
    #dialog {
        width: 45;
        height: auto;
        border: thick $primary;
        background: $surface;
        padding: 1 2;
    }
    #question {
        text-align: center;
        margin-bottom: 1;
    }
    #buttons {
        align: center middle;
        height: 3;
    }
    Button {
        margin: 0 1;
        min-width: 12;
    }
    """
    BINDINGS = [("y", "confirm", "Yes"), ("n", "cancel", "No"), ("escape", "cancel", "No")]

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

    def action_confirm(self) -> None:
        self.dismiss(True)

    def action_cancel(self) -> None:
        self.dismiss(False)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        self.dismiss(event.button.id == "yes")


class WorkflowTreeApp(App):
    """Interactive tree navigation app for workflow steps."""

    CSS = """
    Tree {
        scrollbar-gutter: stable;
    }

    /* 1. Hide the keys by default using !important to override internal styles */
    Footer > .footer--key-o, 
    Footer > .footer--key-a {
        display: none !important;
    }

    /* 2. Show them ONLY when the parent Footer has the toggle class */
    Footer.can-view-logs > .footer--key-o {
        display: block !important;
    }

    Footer.can-approve > .footer--key-a {
        display: block !important;
    }
    """

    BINDINGS = [
        Binding("o", "view_logs", "View Logs", show=True),
        Binding("a", "approve_step", "Approve", show=True),
        Binding("r", "refresh", "Refresh"),
        Binding("q", "quit", "Quit"),
        Binding("escape", "quit", "Quit"),
        Binding("left", "collapse_node", "Collapse"),
        Binding("right", "expand_node", "Expand"),
    ]

    def __init__(self, tree_nodes: List[Dict[str, Any]], workflow_data: Dict[str, Any],
                 k8s_client, workflow_name: str, namespace: str):
        super().__init__()
        self.tree_nodes = tree_nodes
        self.workflow_data = workflow_data
        self.nodes_with_live_checks = set()
        self.k8s_client = k8s_client
        self.workflow_name = workflow_name
        self.namespace = namespace
        self.tail_lines = 500
        self.node_mapping = {}
        self.current_node_data = None

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(
            Tree("Workflow Steps", id="workflow-tree"),
            id="tree-container"
        )
        yield Footer()

    def on_mount(self) -> None:
        # Redirect all logging to file during TUI
        root_logger = logging.getLogger()
        root_logger.handlers = [file_handler]
        root_logger.setLevel(logging.INFO)

        tree = self.query_one("#workflow-tree", Tree)
        self._populate_tree(tree, self.tree_nodes, tree.root)
        tree.root.expand_all()

        # Start live refresh timer (every 3 seconds)
        self.set_interval(3.0, self._start_background_refresh)

    def check_action_enabled(self, action: str) -> bool:
        """
        TEXTUAL HOOK: Controls whether an action can be triggered.
        This prevents 'ghost' keypresses for hidden bindings.
        """
        if action == "view_logs":
            return (self.current_node_data is not None and
                    self.current_node_data.get('type') == NODE_TYPE_POD)

        if action == "approve_step":
            return (self.current_node_data is not None and
                    self.current_node_data.get('type') == NODE_TYPE_SUSPEND and
                    self.current_node_data.get('phase') == PHASE_RUNNING)

        return True

    def on_tree_node_highlighted(self, event: Tree.NodeHighlighted) -> None:
        """Update UI state and binding visibility when cursor moves."""
        self.current_node_data = event.node.data
        footer = self.query_one(Footer)

        # Determine visibility states
        is_pod = self.current_node_data is not None and self.current_node_data.get('type') == NODE_TYPE_POD
        is_approvable = (self.current_node_data is not None and
                         self.current_node_data.get('type') == NODE_TYPE_SUSPEND and
                         self.current_node_data.get('phase') == PHASE_RUNNING)

        # Toggle CSS classes on the Footer to show/hide keys
        footer.set_class(is_pod, "can-view-logs")
        footer.set_class(is_approvable, "can-approve")

    def on_tree_node_selected(self, event: Tree.NodeSelected) -> None:
        """Handle tree node selection and run live check if applicable."""
        if (event.node.data and
                event.node.data['type'] == NODE_TYPE_POD and
                self._should_run_live_check(event.node.data) and
                self._is_last_leaf_at_level(event.node) and
                event.node.data.get('id') not in self.nodes_with_live_checks):

            self._run_live_check_async(event.node, event.node.data)
    def on_key(self, event) -> None:
        """Handle Ctrl+C to exit."""
        if event.key == "ctrl+c":
            self.exit()
            event.prevent_default()
    

    def action_view_logs(self) -> None:
        """View logs for current selected Pod node in pager."""
        if self.current_node_data and self.current_node_data['type'] == NODE_TYPE_POD:
            self._show_logs_in_pager(self.current_node_data)

    def action_approve_step(self) -> None:
        """Approve current selected Suspend node - shows confirmation modal."""
        if (self.current_node_data and
                self.current_node_data['type'] == NODE_TYPE_SUSPEND and
                self.current_node_data['phase'] == PHASE_RUNNING):

            node_data = self.current_node_data
            step_name = node_data.get('display_name', 'Unknown Step')

            def handle_confirm(confirmed: bool) -> None:
                if confirmed:
                    self._execute_approval(node_data)

            self.push_screen(ConfirmModal(f"Approve '{step_name}'?"), handle_confirm)

    # --- Core Logic & Tree Management ---

    def _get_node_label(self, node: Dict, workflow_data: Dict):
        status_output = get_step_status_output(workflow_data, node['id'])
        return get_step_rich_label(node, status_output)

    def _populate_tree(self, tree: Tree, nodes: List[Dict[str, Any]], parent_node) -> None:
        sorted_nodes = sorted(nodes, key=lambda n: n.get('started_at') or '9999-12-31T23:59:59Z')
        for node in sorted_nodes:
            label = self._get_node_label(node, self.workflow_data)
            tree_node = parent_node.add(label, data=node)
            self.node_mapping[node['id']] = tree_node
            if node['children']:
                self._populate_tree(tree, node['children'], tree_node)

    def action_refresh(self) -> None:
        logger.info("Manual refresh triggered")
        self._start_background_refresh()

    def _start_background_refresh(self) -> None:
        worker = self.run_worker(self._fetch_workflow_data, thread=True)
        worker.name = "refresh_worker"

    def _fetch_workflow_data(self) -> tuple:
        try:
            result, new_workflow_data = _get_workflow_data(
                WorkflowService(), self.workflow_name,
                f"http://localhost:2746", self.namespace, False, None
            )
            return result, new_workflow_data
        except Exception as e:
            return {"success": False, "error": str(e)}, {}

    def on_worker_state_changed(self, event) -> None:
        if not event.worker.is_finished:
            return

        if event.worker.name and event.worker.name.startswith("live_check_"):
            result = event.worker.result
            node_id = event.worker.name.replace("live_check_", "")
            self._update_live_check_result(node_id, result)
        elif event.worker.name == "refresh_worker":
            try:
                result, new_workflow_data = event.worker.result
                if result.get('success'):
                    self._apply_workflow_updates(new_workflow_data)
                else:
                    self._clear_tree_on_failure(result.get('error', 'Workflow not found'))
            except Exception as e:
                logger.error(f"Background refresh error: {e}")

    def _apply_workflow_updates(self, new_workflow_data: Dict) -> None:
        try:
            tree = self.query_one("#workflow-tree", Tree)
            old_start = self.workflow_data.get('status', {}).get('startedAt')
            new_start = new_workflow_data.get('status', {}).get('startedAt')

            new_tree_nodes = build_nested_workflow_tree(new_workflow_data)
            new_filtered_tree = filter_tree_nodes(new_tree_nodes)

            if old_start != new_start:
                tree.clear()
                tree.root.label = "Workflow Steps"
                self.node_mapping.clear()
                self.nodes_with_live_checks.clear()
                self._populate_tree(tree, new_filtered_tree, tree.root)
                tree.root.expand_all()
            else:
                for child in list(tree.root.children):
                    if not child.data: child.remove()
                tree.root.label = "Workflow Steps"
                self._update_tree_recursive(tree.root, new_filtered_tree, new_workflow_data)

            self.workflow_data = new_workflow_data
            self.tree_nodes = new_filtered_tree
        except Exception as e:
            logger.error(f"Apply updates error: {e}")

    def _update_tree_recursive(self, parent_tree_node, new_nodes: List[Dict], workflow_data: Dict) -> None:
        existing_children = {child.data['id']: child for child in parent_tree_node.children if child.data}
        new_node_ids = set()

        for node in sorted(new_nodes, key=lambda n: n.get('started_at') or '9999-12-31T23:59:59Z'):
            node_id = node['id']
            new_node_ids.add(node_id)
            new_label = self._get_node_label(node, workflow_data)

            if node_id in existing_children:
                tree_node = existing_children[node_id]
                if tree_node.data.get('phase') != node.get('phase'):
                    tree_node.set_label(new_label)
                tree_node.data = node
            else:
                tree_node = parent_tree_node.add(new_label, data=node)
                self.node_mapping[node_id] = tree_node

            if node['children']:
                self._update_tree_recursive(self.node_mapping[node_id], node['children'], workflow_data)

        for child in list(parent_tree_node.children):
            if child.data and child.data.get('id') not in new_node_ids:
                child.remove()
                if child.data.get('id') in self.node_mapping:
                    del self.node_mapping[child.data['id']]

    def _show_logs_in_pager(self, node_data: Dict) -> None:
        temp_file = None
        try:
            node_id = node_data['id']
            pod_name = _find_pod_by_node_id(self.k8s_client, self.namespace, self.workflow_name, node_id, node_data, None)
            logs = self._get_pod_logs(pod_name, node_data)

            with tempfile.NamedTemporaryFile(mode='w', suffix='.log', delete=False) as f:
                f.write(f"=== Logs for {node_data['display_name']} ===\nPod: {pod_name}\n\n{logs}")
                temp_file = f.name

            with self.suspend():
                os.system('clear')
                pager = os.environ.get('PAGER', 'less')
                subprocess.run([pager, temp_file])
        except Exception as e:
            self.notify(f"Error viewing logs: {str(e)}", severity="error")
        finally:
            if temp_file and os.path.exists(temp_file): os.unlink(temp_file)

    def _get_pod_logs(self, pod_name: str, node_data: Dict) -> str:
        try:
            from kubernetes.client.rest import ApiException
            pod = self.k8s_client.read_namespaced_pod(name=pod_name, namespace=self.namespace)
            containers = ([c.name for c in pod.spec.init_containers] if pod.spec.init_containers else []) + \
                         ([c.name for c in pod.spec.containers] if pod.spec.containers else [])

            output = []
            for c_name in containers:
                output.append(f"\n--- Container: {c_name} ---\n")
                try:
                    c_logs = self.k8s_client.read_namespaced_pod_log(pod_name, self.namespace, container=c_name, tail_lines=self.tail_lines)
                    output.append(c_logs or "(No output)")
                except ApiException:
                    output.append("(Container not ready)")
            return "".join(output)
        except Exception as e:
            return f"Error: {str(e)}"

    def _execute_approval(self, node_data: Dict) -> None:
        try:
            service = WorkflowService()
            node_id = node_data.get('id')
            result = service.approve_workflow(
                self.workflow_name, self.namespace, "http://localhost:2746",
                None, False, f"id={node_id}" if node_id else None
            )
            if result['success']:
                self.notify(f"✅ Approved: {node_data.get('display_name')}")
                self._start_background_refresh()
            else:
                self.notify(f"❌ Failed: {result.get('message')}", severity="error")
        except Exception as e:
            self.notify(f"Error: {str(e)}", severity="error")

    def action_expand_node(self) -> None:
        tree = self.query_one("#workflow-tree", Tree)
        if tree.cursor_node:
            tree.cursor_node.expand()
            if (tree.cursor_node.data and tree.cursor_node.data['type'] == NODE_TYPE_POD and
                    self._should_run_live_check(tree.cursor_node.data) and
                    self._is_last_leaf_at_level(tree.cursor_node) and
                    tree.cursor_node.data.get('id') not in self.nodes_with_live_checks):
                self._run_live_check_async(tree.cursor_node, tree.cursor_node.data)

    def action_collapse_node(self) -> None:
        tree = self.query_one("#workflow-tree", Tree)
        if tree.cursor_node:
            if tree.cursor_node.is_expanded:
                tree.cursor_node.collapse()
            elif tree.cursor_node.parent:
                tree.select_node(tree.cursor_node.parent)

    def _should_run_live_check(self, node_data: Dict) -> bool:
        return (node_data.get('phase') != PHASE_SUCCEEDED and
                get_node_input_parameter(node_data, 'configContents') and
                any(p.get('name') == 'statusOutput' for p in node_data.get('outputs', {}).get('parameters', [])))

    def _is_last_leaf_at_level(self, tree_node) -> bool:
        if not tree_node.parent: return True
        pod_siblings = [c for c in tree_node.parent.children if c.data and c.data.get('type') == NODE_TYPE_POD]
        if not pod_siblings: return True
        return tree_node == max(pod_siblings, key=lambda n: n.data.get('started_at', ''))

    def _run_live_check_async(self, tree_node, node_data: Dict) -> None:
        tree_node.add("🔄 Running live check...")
        worker = self.run_worker(lambda: self._perform_live_check(node_data), thread=True)
        worker.name = f"live_check_{node_data['id']}"

    def _perform_live_check(self, node_data: Dict) -> Dict:
        try:
            config_contents = get_node_input_parameter(node_data, 'configContents')
            services_config = ConfigConverter.convert_with_jq(config_contents)
            if services_config:
                env = Environment(config=yaml.safe_load(services_config))
                node_data['check_type'] = 'snapshot' if 'snapshot' in node_data.get('display_name', '').lower() else 'backfill'
                return StatusCheckRunner.run_status_check(env, node_data)
            return {"error": "Config conversion failed"}
        except Exception as e:
            return {"error": str(e)}

    def _update_live_check_result(self, node_id: str, result: Dict) -> None:
        tree = self.query_one("#workflow-tree", Tree)
        def find(node, tid):
            if node.data and node.data.get('id') == tid: return node
            for c in node.children:
                f = find(c, tid)
                if f: return f
            return None

        target = find(tree.root, node_id)
        if target:
            for c in list(target.children):
                if "🔄" in str(c.label): c.remove()
            if result.get('success'):
                target.add(f"✅ {result.get('message', 'Check completed')}")
            else:
                target.add(f"❌ {result.get('error', 'Check failed')}")
            self.nodes_with_live_checks.add(node_id)
            target.expand()

    def _clear_tree_on_failure(self, error_msg: str) -> None:
        tree = self.query_one("#workflow-tree", Tree)
        tree.clear()
        tree.root.label = f"⚠️  Fetch failed: {error_msg}"
        tree.root.add("Retrying...")

@click.command(name="manage")
@click.argument('workflow_name', required=False)
@click.option('--argo-server', default="http://localhost:2746")
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

        result, workflow_data = _get_workflow_data(service, workflow_name, argo_server, namespace, insecure, token)
        if not result['success']:
            click.echo(f"Error: {result['error']}", err=True)
            ctx.exit(ExitCode.FAILURE.value)

        tree_nodes = build_nested_workflow_tree(workflow_data)
        filtered_tree = filter_tree_nodes(tree_nodes)

        if not filtered_tree:
            click.echo("No workflow steps found.")
            return

        k8s_core_api = _initialize_k8s_client(ctx)
        app = WorkflowTreeApp(filtered_tree, workflow_data, k8s_core_api, workflow_name, namespace)
        app.run()

    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        ctx.exit(ExitCode.FAILURE.value)

def _get_workflow_data(service, workflow_name, argo_server, namespace, insecure, token):
    import requests
    result = service.get_workflow_status(workflow_name, namespace, argo_server, token, insecure)
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
    response = requests.get(url, headers=headers, verify=not insecure)
    return result, (response.json() if response.status_code == 200 else {})