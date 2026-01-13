import logging
import yaml
from typing import Dict, Set, Any

# Internal imports (adjust paths based on your final structure)
from console_link.environment import Environment
from console_link.workflow.commands.status import StatusCheckRunner, ConfigConverter
from console_link.workflow.tree_utils import get_node_input_parameter

logger = logging.getLogger(__name__)


class LiveStatusManager:
    """
    Manages ephemeral 'Live Status' nodes.
    Acts as a 'Guest' that attaches data to the 'Host' TreeStateManager.
    """

    def __init__(self, refresh_interval: float):
        self._refresh_interval = refresh_interval
        self._active_loop_ids: Set[str] = set()
        self._live_check_in_progress: Set[str] = set()

    def reconcile(self, app: Any, tree_state: Any) -> None:
        """
        Main entry point called by the App's refresh cycle.
        Identifies the 'tip' of the workflow and ensures an attachment exists.
        """
        # 1. Identify the 'tip' node (the most recently started active node)
        # We look at the node_mapping owned by the tree_state
        real_nodes = [
            node.data for node in tree_state.node_mapping.values()
            if node.data and not node.data.get("is_ephemeral")
        ]

        if not real_nodes:
            return

        tip = max(real_nodes, key=lambda x: x.get("started_at") or "")
        tip_id = tip['id']

        # 2. Determine if this tip supports Live Checks
        has_config = get_node_input_parameter(tip, 'configContents') is not None
        # Phase logic: show for anything other than Succeeded
        is_active = tip.get('phase') != "Succeeded"

        if has_config and is_active:
            try:
                # 3. Get the parent of the tip node to attach Live Status as a sibling
                tip_tree_node = tree_state.get_node(tip_id)
                if not tip_tree_node or not tip_tree_node.parent:
                    return
                parent_tree_node = tip_tree_node.parent

                # Look for existing attachment in parent's children
                existing = next((c for c in parent_tree_node.children
                                if c.data and c.data.get("attachment_id") == "live-status-header"), None)
                if not existing:
                    parent_tree_node.add(
                        "[bold cyan]Live Status:[/]",
                        data={"is_ephemeral": True, "attachment_id": "live-status-header", "origin_id": tip_id}
                    )

                # 4. Kick off the independent loop if not already running for this tip
                if tip_id not in self._active_loop_ids:
                    self._active_loop_ids.add(tip_id)
                    self._per_node_live_loop(app, tree_state, tip_id)
            except ValueError:
                # Parent node might have been pruned during a race condition
                pass
        else:
            # If the node succeeded, remove the Live Status node
            self._remove_live_status_attachment(tree_state, tip_id)
            self._active_loop_ids.discard(tip_id)

    def _remove_live_status_attachment(self, tree_state: Any, tip_id: str) -> None:
        """Remove the Live Status attachment node."""
        tip_tree_node = tree_state.get_node(tip_id)
        if not tip_tree_node or not tip_tree_node.parent:
            return
        for child in list(tip_tree_node.parent.children):
            if child.data and child.data.get("attachment_id") == "live-status-header":
                child.remove()
                break

    def _per_node_live_loop(self, app: Any, tree_state: Any, node_id: str) -> None:
        """Independent, self-scheduling update loop for a specific live status."""
        if app.is_exiting:
            self._active_loop_ids.discard(node_id)
            return

        # Check if the attachment node still exists in the host
        parent_node = tree_state.get_node(node_id)
        if not parent_node:
            self._active_loop_ids.discard(node_id)
            return

        # Find the header attachment we created earlier
        header = next((c for c in parent_node.children
                       if c.data and c.data.get("attachment_id") == "live-status-header"), None)

        if header and self._should_continue_loop(node_id):
            if header.is_expanded:
                self._run_live_check_async(app, tree_state, node_id, header)

            # Reschedule the next tick of this specific loop
            app.set_timer(
                self._refresh_interval,
                lambda: self._per_node_live_loop(app, tree_state, node_id)
            )
        else:
            self._active_loop_ids.discard(node_id)

    def _should_continue_loop(self, node_id: str) -> bool:
        return node_id in self._active_loop_ids

    def _run_live_check_async(self, app: Any, tree_state: Any, node_id: str, header_node: Any) -> None:
        """Dispatches the worker thread."""
        if node_id in self._live_check_in_progress:
            return

        self._live_check_in_progress.add(node_id)
        node_data = tree_state.get_node(node_id).data

        app.run_worker(
            lambda: self._perform_check_worker(app, node_data),
            thread=True,
            name=f"live_check_{node_id}"
        )

    def _perform_check_worker(self, app: Any, node_data: Dict) -> None:
        """Background Thread: Performs the actual K8s/External status check."""
        node_id = node_data['id']
        try:
            raw_cfg = get_node_input_parameter(node_data, 'configContents')
            svc_cfg = ConfigConverter.convert_with_jq(raw_cfg)
            if svc_cfg:
                env = Environment(config=yaml.safe_load(svc_cfg))
                name_low = node_data.get('display_name', '').lower()
                node_data['check_type'] = 'snapshot' if 'snapshot' in name_low else 'backfill'

                result = StatusCheckRunner.run_status_check(env, node_data)
                app.call_from_thread(self._update_ui_result, node_id, result)
                return
            app.call_from_thread(self._update_ui_result, node_id, {"error": "Config conversion failed"})
        except Exception as e:
            app.call_from_thread(self._update_ui_result, node_id, {"error": str(e)})
        finally:
            self._live_check_in_progress.discard(node_id)

    def _update_ui_result(self, node_id: str, result: Dict) -> None:
        """Main Thread: Injects lines into the attachment node children."""
        # We don't touch 'app' here; we use the logic to find our attachment point
        # This is the 'Slot' we are filling.
        # This requires the App to have a reference to tree_state
        # Note: In a real app, you'd likely pass tree_state into this method
        # via the call_from_thread closure.
