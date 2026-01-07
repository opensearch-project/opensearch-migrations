"""Interactive manage command for workflow CLI - interactive tree navigation for log viewing."""

import logging
import os
import yaml
import click
import subprocess
import tempfile
from typing import Dict, List, Any, Optional

from textual.app import App, ComposeResult
from textual.widgets import Tree, Footer, Header, Static, Button
from textual.binding import Binding
from textual.containers import Container, Horizontal
from textual.screen import ModalScreen
from textual.worker import get_current_worker

from ..models.utils import ExitCode
from ..services.workflow_service import WorkflowService
from ..tree_utils import build_nested_workflow_tree, filter_tree_nodes, get_step_rich_label, get_step_status_output, get_node_input_parameter
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
import datetime
import tempfile
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
logger.propagate = False  # Don't propagate to console


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
    Button:focus {
        text-style: bold reverse;
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
    """
    
    BINDINGS = [
        ("o", "view_logs", "View Logs"),
        ("a", "approve_step", "Approve"),
        ("r", "refresh", "Refresh"),
        ("q,escape", "quit", "Quit"),
        ("left", "collapse_node", "Collapse"),
        ("right", "expand_node", "Expand"),
    ]
    
    def __init__(self, tree_nodes: List[Dict[str, Any]], workflow_data: Dict[str, Any], 
                 k8s_client, workflow_name: str, namespace: str):
        super().__init__()
        self.tree_nodes = tree_nodes
        self.workflow_data = workflow_data
        self.nodes_with_live_checks = set()  # Track nodes with live check results
        self.k8s_client = k8s_client
        self.workflow_name = workflow_name
        self.namespace = namespace
        self.tail_lines = 500
        self.node_mapping = {}  # Track node_id -> TreeNode for live updates
    
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
    
    def _get_node_label(self, node: Dict, workflow_data: Dict):
        """Get rich label for a node."""
        status_output = get_step_status_output(workflow_data, node['id'])
        return get_step_rich_label(node, status_output)
    
    def _populate_tree(self, tree: Tree, nodes: List[Dict[str, Any]], parent_node) -> None:
        """Populate textual tree with workflow nodes."""
        sorted_nodes = sorted(nodes, key=lambda n: n.get('started_at') or '9999-12-31T23:59:59Z')
        
        for node in sorted_nodes:
            label = self._get_node_label(node, self.workflow_data)
            tree_node = parent_node.add(label, data=node)
            
            # Track node mapping for live updates
            self.node_mapping[node['id']] = tree_node
            
            # Add children recursively
            if node['children']:
                self._populate_tree(tree, node['children'], tree_node)
    
    def on_tree_node_selected(self, event: Tree.NodeSelected) -> None:
        """Handle tree node selection and run live check if applicable."""
        if (event.node.data and 
            event.node.data['type'] == NODE_TYPE_POD and 
            self._should_run_live_check(event.node.data) and
            self._is_last_leaf_at_level(event.node) and
            event.node.data.get('id') not in self.nodes_with_live_checks):
            
            self._run_live_check_async(event.node, event.node.data)
        
        # Update contextual bindings
        logger.info("Triggering contextual binding update from selection change")

    
    def on_key(self, event) -> None:
        """Handle Ctrl+C to exit."""
        if event.key == "ctrl+c":
            self.exit()
            event.prevent_default()
    
    def action_view_logs(self) -> None:
        """View logs for current selected Pod node in pager."""
        tree = self.query_one("#workflow-tree", Tree)
        if tree.cursor_node and tree.cursor_node.data and tree.cursor_node.data['type'] == NODE_TYPE_POD:
            self._show_logs_in_pager(tree.cursor_node.data)
    
    def action_approve_step(self) -> None:
        """Approve current selected Suspend node - shows confirmation modal."""
        tree = self.query_one("#workflow-tree", Tree)
        if (tree.cursor_node and tree.cursor_node.data and 
            tree.cursor_node.data['type'] == NODE_TYPE_SUSPEND and 
            tree.cursor_node.data['phase'] == PHASE_RUNNING):
            node_data = tree.cursor_node.data
            step_name = node_data.get('display_name', 'Unknown Step')
            
            def handle_confirm(confirmed: bool) -> None:
                if confirmed:
                    self._execute_approval(node_data)
            
            self.push_screen(ConfirmModal(f"Approve '{step_name}'?"), handle_confirm)
    
    def action_refresh(self) -> None:
        """Manually refresh workflow data."""
        logger.info("Manual refresh triggered")
        self._start_background_refresh()
    

    def _start_background_refresh(self) -> None:
        """Start background refresh worker."""
        worker = self.run_worker(self._fetch_workflow_data, thread=True, exclusive=False)
        worker.name = "refresh_worker"
    
    def _fetch_workflow_data(self) -> tuple:
        """Fetch workflow data in background thread."""
        try:
            result, new_workflow_data = _get_workflow_data(
                WorkflowService(), self.workflow_name, 
                f"http://localhost:2746", self.namespace, False, None
            )
            return result, new_workflow_data
        except Exception as e:
            return {"success": False, "error": str(e)}, {}
    
    def on_worker_state_changed(self, event) -> None:
        """Handle worker completion for both refresh and live checks."""
        if not event.worker.is_finished:
            return
            
        if event.worker.name and event.worker.name.startswith("live_check_"):
            # Handle live check workers
            result = event.worker.result
            node_id = event.worker.name.replace("live_check_", "")
            self._update_live_check_result(node_id, result)
        elif event.worker.name == "refresh_worker":
            # Handle refresh workers
            try:
                result, new_workflow_data = event.worker.result
                if result.get('success'):
                    self._apply_workflow_updates(new_workflow_data)
                else:
                    # Workflow deleted - clear tree but keep polling
                    self._clear_tree_on_failure(result.get('error', 'Workflow not found'))
            except Exception as e:
                logger.error(f"Background refresh error: {e}")
    
    def _get_workflow_start_time(self, workflow_data: Dict) -> Optional[str]:
        """Get workflow start time from metadata."""
        return workflow_data.get('status', {}).get('startedAt')
    
    def _apply_workflow_updates(self, new_workflow_data: Dict) -> None:
        """Apply workflow updates using smart diffing (original approach)."""
        try:
            logger.info("Applying workflow updates")
            tree = self.query_one("#workflow-tree", Tree)
            
            # Check if this is a different workflow (start time changed = new workflow)
            old_start = self._get_workflow_start_time(self.workflow_data)
            new_start = self._get_workflow_start_time(new_workflow_data)
            is_new_workflow = old_start != new_start
            
            # Build new tree structure
            new_tree_nodes = build_nested_workflow_tree(new_workflow_data)
            new_filtered_tree = filter_tree_nodes(new_tree_nodes)
            
            if is_new_workflow:
                # Different workflow - clear and rebuild entirely
                logger.info(f"New workflow detected (start: {new_start}), rebuilding tree")
                tree.clear()
                tree.root.label = "Workflow Steps"
                self.node_mapping.clear()
                self.nodes_with_live_checks.clear()
                self._populate_tree(tree, new_filtered_tree, tree.root)
                tree.root.expand_all()
            else:
                # Same workflow - incremental update
                # Clear any error message children from root (they have no data)
                for child in list(tree.root.children):
                    if not child.data:
                        child.remove()
                tree.root.label = "Workflow Steps"
                
                self._update_tree_nodes(new_filtered_tree, new_workflow_data)
            
            # Update stored data
            self.workflow_data = new_workflow_data
            self.tree_nodes = new_filtered_tree

            logger.info("Workflow updates applied successfully")
            
        except Exception as e:
            logger.error(f"Apply updates error: {e}")
            self.notify(f"Refresh failed: {e}", severity="error")
    
    def _clear_tree_on_failure(self, error_msg: str) -> None:
        """Clear tree when workflow fetch fails but keep app running."""
        try:
            tree = self.query_one("#workflow-tree", Tree)
            tree.clear()
            tree.root.label = f"⚠️  Workflow fetch failed: {error_msg}"
            tree.root.add("Retrying in 3 seconds...")
            
            # Clear mappings
            self.node_mapping.clear()
            self.nodes_with_live_checks.clear()
            
        except Exception as e:
            logger.error(f"Error clearing tree: {e}")
    
    def _show_logs_in_pager(self, node_data: Dict) -> None:
        """Show logs in a pager and return to UI."""
        temp_file = None
        try:
            # Find the pod
            node_id = node_data['id']
            pod_name = _find_pod_by_node_id(
                self.k8s_client, self.namespace, self.workflow_name, 
                node_id, node_data, None  # ctx not needed for this call
            )
            
            # Get logs and write to temp file
            logs = self._get_pod_logs(pod_name, node_data)
            
            with tempfile.NamedTemporaryFile(mode='w', suffix='.log', delete=False) as f:
                f.write(f"=== Logs for {node_data['display_name']} ===\n")
                f.write(f"Pod: {pod_name}\n")
                f.write(f"Phase: {node_data['phase']}\n")
                f.write("=" * 60 + "\n\n")
                f.write(logs)
                temp_file = f.name
            
            # Suspend the app to restore terminal control
            with self.suspend():
                # Clear terminal and show in pager
                os.system('clear')
                pager = os.environ.get('PAGER', 'less')
                subprocess.run([pager, temp_file])
            
        except Exception as e:
            self.notify(f"Error viewing logs: {str(e)}", severity="error")
        finally:
            if temp_file and os.path.exists(temp_file):
                os.unlink(temp_file)
    
    def _get_pod_logs(self, pod_name: str, node_data: Dict) -> str:
        """Get logs from pod containers."""
        try:
            from kubernetes.client.rest import ApiException
            
            pod = self.k8s_client.read_namespaced_pod(name=pod_name, namespace=self.namespace)
            
            # Get container lists
            init_containers = [c.name for c in pod.spec.init_containers] if pod.spec.init_containers else []
            main_containers = [c.name for c in pod.spec.containers] if pod.spec.containers else []
            all_containers = init_containers + main_containers
            
            logs_output = []
            
            for container_name in all_containers:
                logs_output.append(f"\n{'─' * 80}")
                logs_output.append(f"Container: {container_name}")
                logs_output.append(f"{'─' * 80}\n")
                
                try:
                    container_logs = self.k8s_client.read_namespaced_pod_log(
                        name=pod_name,
                        namespace=self.namespace,
                        container=container_name,
                        tail_lines=self.tail_lines
                    )
                    
                    if container_logs:
                        logs_output.append(container_logs)
                    else:
                        logs_output.append("(No output available)")
                        
                except ApiException as e:
                    if e.status == 400:
                        logs_output.append("(Container not ready or no output available)")
                    else:
                        logs_output.append(f"(Error retrieving output: {e.reason})")
            
            return "\n".join(logs_output)
            
        except Exception as e:
            return f"Error retrieving logs: {str(e)}"
    
    def _execute_approval(self, node_data: Dict) -> None:
        """Execute approval for a specific suspended workflow node."""
        try:
            service = WorkflowService()
            step_name = node_data.get('display_name', 'Unknown Step')
            node_id = node_data.get('id')
            
            # Approve only the selected node using node field selector
            result = service.approve_workflow(
                workflow_name=self.workflow_name,
                namespace=self.namespace,
                argo_server=f"http://localhost:2746",
                token=None,
                insecure=False,
                node_field_selector=f"id={node_id}" if node_id else None
            )
            
            if result['success']:
                self.notify(f"✅ Approved: {step_name}", severity="information")
                self._start_background_refresh()
            else:
                self.notify(f"❌ Approval failed: {result.get('message', 'Unknown error')}", 
                           severity="error")
                
        except Exception as e:
            self.notify(f"Error approving step: {str(e)}", severity="error")
    

    def _update_tree_nodes(self, new_nodes: List[Dict[str, Any]], new_workflow_data: Dict) -> None:
        """Update existing nodes and add new ones incrementally."""
        tree = self.query_one("#workflow-tree", Tree)
        
        # Update tree incrementally instead of rebuilding
        self._update_tree_recursive(tree.root, new_nodes, new_workflow_data)
    
    def _update_tree_recursive(self, parent_tree_node, new_nodes: List[Dict], workflow_data: Dict) -> None:
        """Recursively update tree nodes."""
        # Create lookup of existing children by node ID
        existing_children = {}
        for child in parent_tree_node.children:
            if child.data and child.data.get('id'):
                existing_children[child.data['id']] = child
        
        # Process new nodes
        new_node_ids = set()
        for node in sorted(new_nodes, key=lambda n: n.get('started_at') or '9999-12-31T23:59:59Z'):
            node_id = node['id']
            new_node_ids.add(node_id)
            
            new_label = self._get_node_label(node, workflow_data)
            
            if node_id in existing_children:
                # Update existing node
                tree_node = existing_children[node_id]
                if tree_node.data.get('phase') != node.get('phase'):
                    tree_node.set_label(new_label)
                tree_node.data = node
            else:
                # Add new node
                tree_node = parent_tree_node.add(new_label, data=node)
                self.node_mapping[node_id] = tree_node
            
            # Update children recursively
            if node['children']:
                self._update_tree_recursive(self.node_mapping[node_id], node['children'], workflow_data)
        
        # Remove nodes that no longer exist
        for child in list(parent_tree_node.children):
            if child.data and child.data.get('id') not in new_node_ids:
                child.remove()
                # Clean up mappings
                if child.data.get('id') in self.node_mapping:
                    del self.node_mapping[child.data['id']]
    
    def check_action_enabled(self, action: str) -> bool:
        """Check if an action should be enabled based on current context."""
        tree = self.query_one("#workflow-tree", Tree)
        if not tree.cursor_node or not tree.cursor_node.data:
            return action not in ("view_logs", "approve_step")
        
        node_data = tree.cursor_node.data
        
        if action == "view_logs":
            return node_data.get('type') == NODE_TYPE_POD
        elif action == "approve_step":
            return (node_data.get('type') == NODE_TYPE_SUSPEND and 
                   node_data.get('phase') == PHASE_RUNNING)
        
        return True
    
    def action_expand_node(self) -> None:
        """Expand current highlighted node and run live check if applicable."""
        tree = self.query_one("#workflow-tree", Tree)
        if tree.cursor_node:
            tree.cursor_node.expand()
            
            # Run live check if this is an eligible Pod node
            if (tree.cursor_node.data and 
                tree.cursor_node.data['type'] == NODE_TYPE_POD and 
                self._should_run_live_check(tree.cursor_node.data) and
                self._is_last_leaf_at_level(tree.cursor_node) and
                tree.cursor_node.data.get('id') not in self.nodes_with_live_checks):
                
                self._run_live_check_async(tree.cursor_node, tree.cursor_node.data)
    
    def action_collapse_node(self) -> None:
        """Collapse current node, or move to parent if already collapsed."""
        tree = self.query_one("#workflow-tree", Tree)
        if tree.cursor_node:
            if tree.cursor_node.is_expanded:
                # Remove live check results before collapsing
                if (tree.cursor_node.data and 
                    tree.cursor_node.data.get('id') in self.nodes_with_live_checks):
                    self._remove_live_check_results(tree.cursor_node)
                tree.cursor_node.collapse()
            elif tree.cursor_node.parent:
                tree.select_node(tree.cursor_node.parent)
    
    def _should_run_live_check(self, node_data: Dict) -> bool:
        """Check if node qualifies for live status check."""
        return (node_data.get('phase') != PHASE_SUCCEEDED and
                get_node_input_parameter(node_data, 'configContents') and
                self._has_status_output(node_data))
    
    def _has_status_output(self, node_data: Dict) -> bool:
        """Check if node has statusOutput parameter."""
        outputs = node_data.get('outputs', {}).get('parameters', [])
        return any(p.get('name') == 'statusOutput' for p in outputs)
    
    def _is_last_leaf_at_level(self, tree_node) -> bool:
        """Check if this is the most recent Pod node at its level."""
        if not tree_node.parent:
            return True
        
        # Get all Pod siblings
        pod_siblings = [child for child in tree_node.parent.children 
                       if child.data and child.data.get('type') == NODE_TYPE_POD]
        
        if not pod_siblings:
            return True
        
        # Find the most recent by started_at time
        most_recent = max(pod_siblings, 
                         key=lambda n: n.data.get('started_at', ''))
        
        return tree_node == most_recent
    
    def _determine_check_type(self, node_data: Dict) -> str:
        """Determine check type based on node's parent context."""
        # Look at the display name to determine type
        display_name = node_data.get('display_name', '')
        
        # Check if this node or its context suggests snapshot or backfill
        if 'snapshot' in display_name.lower():
            return 'snapshot'
        elif 'backfill' in display_name.lower():
            return 'backfill'
        
        # Look at config contents for clues
        config_contents = get_node_input_parameter(node_data, 'configContents')
        if config_contents:
            if 'snapshot' in config_contents.lower():
                return 'snapshot'
            elif 'backfill' in config_contents.lower():
                return 'backfill'
        
        return 'unknown'
    
    def _run_live_check_async(self, tree_node, node_data: Dict) -> None:
        """Run live check and add result as child node."""
        # Add checking indicator immediately
        checking_node = tree_node.add("🔄 Running live check...")
        
        # Run check in background - create a lambda to pass the argument
        worker = self.run_worker(lambda: self._perform_live_check(node_data), thread=True)
        worker.name = f"live_check_{node_data['id']}"
    
    def _perform_live_check(self, node_data: Dict) -> Dict:
        """Perform the actual live check in background thread."""
        try:
            config_contents = get_node_input_parameter(node_data, 'configContents')
            services_config = ConfigConverter.convert_with_jq(config_contents)
            if services_config:
                env = Environment(config=yaml.safe_load(services_config))
                
                # Add check_type to node_data for StatusCheckRunner
                node_data_with_type = node_data.copy()
                node_data_with_type['check_type'] = self._determine_check_type(node_data)
                
                return StatusCheckRunner.run_status_check(env, node_data_with_type)
            return {"error": "Failed to convert config"}
        except Exception as e:
            return {"error": str(e)}
    
    def _update_live_check_result(self, node_id: str, result: Dict) -> None:
        """Update tree with live check result."""
        tree = self.query_one("#workflow-tree", Tree)
        
        # Find the node and its checking child
        def find_node_by_id(node, target_id):
            if node.data and node.data.get('id') == target_id:
                return node
            for child in node.children:
                found = find_node_by_id(child, target_id)
                if found:
                    return found
            return None
        
        target_node = find_node_by_id(tree.root, node_id)
        if target_node:
            # Remove checking indicator
            for child in list(target_node.children):
                if "🔄" in str(child.label):
                    child.remove()
            
            # Add result
            if result.get('success'):
                status_text = result.get('message', str(result.get('value', 'Check completed')))
                target_node.add(f"✅ {status_text}")
            else:
                error_text = result.get('error', 'Check failed')
                target_node.add(f"❌ {error_text}")
            
            # Track this node as having live check results
            self.nodes_with_live_checks.add(node_id)
            target_node.expand()
    
    def _remove_live_check_results(self, tree_node) -> None:
        """Remove live check result children from a node."""
        node_id = tree_node.data.get('id')
        if node_id:
            # Remove live check children (those with ✅, ❌, or 🔄)
            for child in list(tree_node.children):
                label_str = str(child.label)
                if any(symbol in label_str for symbol in ["✅", "❌", "🔄"]):
                    child.remove()
            
            # Remove from tracking set
            self.nodes_with_live_checks.discard(node_id)


@click.command(name="manage")
@click.argument('workflow_name', required=False)
@click.option(
    '--argo-server',
    default=f"http://{os.environ.get('ARGO_SERVER_SERVICE_HOST', 'localhost')}"
    f":{os.environ.get('ARGO_SERVER_SERVICE_PORT', '2746')}",
    help='Argo Server URL'
)
@click.option('--namespace', default='ma', help='Kubernetes namespace (default: ma)')
@click.option('--insecure', is_flag=True, default=False, help='Skip TLS certificate verification')
@click.option('--token', help='Bearer token for authentication')
@click.pass_context
def manage_command(ctx, workflow_name, argo_server, namespace, insecure, token):
    """Interactive workflow step management with tree navigation.
    
    Navigate workflow steps using arrow keys and Enter to view logs.
    Only Pod-type steps can be selected for log viewing.
    
    Controls:
    - Arrow keys: Navigate tree
    - Enter: View logs for selected step  
    - q/Escape: Quit
    
    Example:
        workflow manage
        workflow manage my-workflow --follow
    """
    try:
        service = WorkflowService()
        
        # Auto-detect workflow if not provided
        if not workflow_name:
            workflow_name = auto_detect_workflow(service, namespace, argo_server, token, insecure, ctx)
            if not workflow_name:
                return
        
        # Get workflow data
        result, workflow_data = _get_workflow_data(service, workflow_name, argo_server, namespace, insecure, token)
        if not result['success']:
            click.echo(f"Error: {result['error']}", err=True)
            ctx.exit(ExitCode.FAILURE.value)
        
        # Build tree structure
        tree_nodes = build_nested_workflow_tree(workflow_data)
        filtered_tree = filter_tree_nodes(tree_nodes)
        
        if not filtered_tree:
            click.echo("No workflow steps found.")
            return
        
        # Initialize k8s client
        k8s_core_api = _initialize_k8s_client(ctx)
        
        # Launch interactive tree
        app = WorkflowTreeApp(filtered_tree, workflow_data, k8s_core_api, workflow_name, namespace)
        app.run()
        
        click.echo("Exited manage interface.")
            
    except Exception as e:
        click.echo(f"Error: {str(e)}", err=True)
        logger.exception("Unexpected error in manage command")
        ctx.exit(ExitCode.FAILURE.value)


def _get_workflow_data(service, workflow_name, argo_server, namespace, insecure, token):
    """Get workflow status and raw data."""
    import requests
    
    result = service.get_workflow_status(
        workflow_name=workflow_name, namespace=namespace, argo_server=argo_server,
        token=token, insecure=insecure)
    
    # Fetch raw workflow data
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    url = f"{argo_server}/api/v1/workflows/{namespace}/{workflow_name}"
    response = requests.get(url, headers=headers, verify=not insecure)
    workflow_data = response.json() if response.status_code == 200 else {}
    
    return result, workflow_data
