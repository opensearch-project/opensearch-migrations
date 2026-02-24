"""
Interactive manage Text-UI for workflow CLI.
interactive tree navigation for status viewing and approval.
"""
import base64
import os
import platform
import subprocess
import sys
import time
from typing import Dict, Optional
from textual.app import App, ComposeResult
from textual.containers import Container
from textual.widgets import Footer, Header, Static, Tree

from .confirm_modal import ConfirmModal
from .container_select_modal import ContainerSelectModal
from .live_status_manager import LiveStatusManager
from .log_manager import LogManager
from .manage_injections import ArgoWorkflowInterface, PodScraperInterface, WaiterInterface
from .pod_name_manager import PodNameManager
from .tree_state_manager import TreeStateManager

TREE_ROOT_ANCHOR = "workflow-tree"

# --- Constants ---
NODE_TYPE_POD = "Pod"
NODE_TYPE_SUSPEND = "Suspend"
PHASE_RUNNING = "Running"
PHASE_SUCCEEDED = "Succeeded"
LOADING_ROOT_LABEL = "[yellow]⏳ Waiting for Workflow to be created...[/]"


class WorkflowTreeApp(App):
    CSS = """
    Tree { scrollbar-gutter: stable; }
    #pod-status { height: 1; padding: 0 1; }
    """

    def __init__(self,
                 namespace: str,
                 name: str,
                 argo_service: ArgoWorkflowInterface,
                 pod_scraper: PodScraperInterface,
                 workflow_waiter: WaiterInterface,
                 refresh_interval: float):
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

        # State Containers (Managers)
        self._pods = PodNameManager(self, pod_scraper, name, namespace)
        self._tree_state = TreeStateManager(on_new_pod=self._pods.observe_node)
        self._logs = LogManager(pod_scraper, namespace)
        self._live = LiveStatusManager(refresh_interval)

        # Internal Application Metadata
        self._workflow_name = name
        self._namespace = namespace

    def compose(self) -> ComposeResult:
        yield Header()
        yield Container(Tree(LOADING_ROOT_LABEL, id="workflow-tree"), id="tree-container")
        yield Static("", id="pod-status")
        yield Footer()

    def on_mount(self) -> None:
        self._tree_state.set_tree_widget(self.tree_root_widget)
        self.action_refresh_workflow()

    @property
    def tree_root_widget(self) -> Tree:
        return self.query_one(f"#{TREE_ROOT_ANCHOR}", Tree)

    def on_unmount(self) -> None:
        self.is_exiting = True
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
        self.call_from_thread(self._handle_workflow_data, data if res.get('success') else {})

    def _handle_workflow_data(self, new_data: Dict, force_reload: bool = False) -> None:
        """The Conductor routes data to the relevant managers."""
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
        self.run_worker(self._force_refresh_workflow, thread=True, name="_force_refresh_workflow")

    def _force_refresh_workflow(self) -> None:
        """Sequential fetch: Workflow Tree Data -> Trigger Strong Pod Resolution."""
        res, data = self._fetch_workflow_data()
        self.call_from_thread(self._handle_workflow_data, data if res.get('success') else {}, True)

    # --- Event Handlers & Actions ---

    def on_tree_node_highlighted(self, event: Tree.NodeHighlighted) -> None:
        self.update_pod_status()
        self._update_dynamic_bindings()

    @property
    def current_node_data(self) -> Optional[Dict]:
        """Get current node data from tree widget's cursor."""
        node = self.tree_root_widget.cursor_node
        return node.data if node and node.data else None

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

    def action_copy_pod_name(self) -> None:
        if not self.current_node_data:
            return
        node_id = self.current_node_data.get('id')
        if pod_name := self._pods.get_name(node_id):
            if copy_to_clipboard(pod_name):
                self.notify(f"📋 Copied: {pod_name}")

    def action_approve_step(self) -> None:
        node = self.current_node_data
        if node and node.get('type') == NODE_TYPE_SUSPEND:
            self.push_screen(ConfirmModal(f"Approve '{node.get('display_name')}'?"),
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

    def action_expand_node(self) -> None:
        tree = self.tree_root_widget
        if node := tree.cursor_node:
            node.expand()

    def action_collapse_node(self) -> None:
        tree = self.tree_root_widget
        if node := tree.cursor_node:
            if node.is_expanded:
                node.collapse()
            elif node.parent:
                tree.select_node(node.parent)

    def update_pod_status(self) -> None:
        status_bar = self.query_one("#pod-status", Static)
        if not self.current_node_data:
            status_bar.update("")
            return
        
        node_type = self.current_node_data.get('type')
        if node_type == NODE_TYPE_POD:
            node_id = self.current_node_data.get('id')
            name = self._pods.get_name(node_id) if node_id else None
            status_bar.update(f"Pod: [bold green]{name}[/]" if name else "Pod: (not available)")
        elif node_type == NODE_TYPE_SUSPEND:
            name_param = None
            for p in self.current_node_data.get('inputs', {}).get('parameters', []):
                if p.get('name') == 'name':
                    name_param = p.get('value')
                    break
            status_bar.update(f"Name: [bold cyan]{name_param}[/]" if name_param else "")
        else:
            status_bar.update("")

    def _update_dynamic_bindings(self) -> None:
        """Reconfigures the Footer and keys based on the currently selected node."""
        self._bindings = self._bindings.__class__()

        self.bind("ctrl+p", "command_palette", show=False)
        self.bind("r", "manual_refresh", description="Refresh")
        self.bind("q", "quit", description="Quit")
        self.bind("left", "collapse_node", show=False)
        self.bind("right", "expand_node", show=False)

        node = self.current_node_data
        if node:
            node_id = node.get('id')
            ntype = node.get('type')
            pod_resolved = self._pods.get_name(node_id) is not None

            if ntype == NODE_TYPE_POD and pod_resolved:
                self.bind("o", "view_logs", description="View Logs")
                if node.get('phase') == PHASE_RUNNING:
                    self.bind("f", "follow_logs", description="Follow Logs")
                self.bind("c", "copy_pod_name", description="Copy Pod Name")
            elif ntype == NODE_TYPE_SUSPEND and node.get('phase') == PHASE_RUNNING:
                self.bind("a", "approve_step", description="Approve")

        self.refresh_bindings()


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
