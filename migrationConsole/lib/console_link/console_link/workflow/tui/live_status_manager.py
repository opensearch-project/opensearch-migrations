import datetime
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
        Finds all parent nodes that have children with configContents and ensures
        a Live Status attachment exists for each active one.
        """
        real_nodes = [
            node.data for node in tree_state.node_mapping.values()
            if node.data and not node.data.get("is_ephemeral")
        ]

        if not real_nodes:
            return

        # Group nodes by parent, find tips with configContents
        # A "tip" is the last (by started_at) child with configContents under each parent
        parents_with_config_children = {}
        for node_data in real_nodes:
            if get_node_input_parameter(node_data, 'configContents') is not None:
                tree_node = tree_state.get_node(node_data['id'])
                if tree_node and tree_node.parent:
                    parent_id = id(tree_node.parent)  # Use object id since parent might be root
                    if parent_id not in parents_with_config_children:
                        parents_with_config_children[parent_id] = {
                            'parent_node': tree_node.parent,
                            'config_children': []
                        }
                    parents_with_config_children[parent_id]['config_children'].append(node_data)

        # For each parent, find the tip (last by started_at) and decide if Live Status should exist
        active_tips = set()
        parents_with_active_status = set()
        for parent_info in parents_with_config_children.values():
            parent_node = parent_info['parent_node']
            config_children = parent_info['config_children']
            tip = max(config_children, key=lambda x: x.get("started_at") or "")
            tip_id = tip['id']
            is_active = tip.get('phase') != "Succeeded"
            
            logger.info(f"reconcile: parent has {len(config_children)} config children, tip={tip_id}, phase={tip.get('phase')}, is_active={is_active}")

            if is_active:
                active_tips.add(tip_id)
                parents_with_active_status.add(id(parent_node))
                self._ensure_live_status(app, tree_state, parent_node, tip_id)
            else:
                logger.info(f"reconcile: removing Live Status for parent because tip {tip_id} is Succeeded")
                self._remove_live_status_for_parent(parent_node)

        # Also clean up any Live Status nodes on parents that no longer have config children
        # (This handles the case where nodes were removed from the tree)
        for node in tree_state.node_mapping.values():
            if node.parent:
                for child in list(node.parent.children):
                    if (child.data and child.data.get("attachment_id") == "live-status-header" 
                        and id(node.parent) not in parents_with_active_status):
                        logger.info(f"reconcile: cleaning up orphaned Live Status")
                        child.remove()

        # Clean up any loops for tips that are no longer active
        for old_tip in list(self._active_loop_ids):
            if old_tip not in active_tips:
                self._active_loop_ids.discard(old_tip)

    def _ensure_live_status(self, app: Any, tree_state: Any, parent_node: Any, tip_id: str) -> None:
        """Ensure Live Status exists for this parent, pointing to tip_id."""
        existing = next((c for c in parent_node.children
                        if c.data and c.data.get("attachment_id") == "live-status-header"), None)

        if existing:
            old_origin = existing.data.get("origin_id")
            if old_origin != tip_id:
                existing.data["origin_id"] = tip_id
                self._active_loop_ids.discard(old_origin)
                logger.info(f"reconcile: updated origin_id from {old_origin} to {tip_id}")

            # Move any nodes that ended up after Live Status to before it
            children = list(parent_node.children)
            live_status_idx = children.index(existing)
            nodes_after = children[live_status_idx + 1:]
            for node in nodes_after:
                if node.data and not node.data.get("is_ephemeral"):
                    logger.info(f"reconcile: moving node {node.data.get('id')} before Live Status")
                    label = node.label
                    data = node.data
                    node.remove()
                    parent_node.add(label, data=data, before=existing)
        else:
            parent_node.add(
                "[bold cyan]Live Status:[/]",
                data={"is_ephemeral": True, "attachment_id": "live-status-header", "origin_id": tip_id}
            )

        if tip_id not in self._active_loop_ids:
            self._active_loop_ids.add(tip_id)
            self._per_node_live_loop(app, tree_state, tip_id)

    def _remove_live_status_for_parent(self, parent_node: Any) -> None:
        """Remove Live Status attachment from this parent."""
        for child in list(parent_node.children):
            if child.data and child.data.get("attachment_id") == "live-status-header":
                child.remove()
                break

    def _per_node_live_loop(self, app: Any, tree_state: Any, node_id: str) -> None:
        """Independent, self-scheduling update loop for a specific live status."""
        if app.is_exiting:
            self._active_loop_ids.discard(node_id)
            return

        parent_node = tree_state.get_node(node_id)
        if not parent_node:
            self._active_loop_ids.discard(node_id)
            return

        header = next((c for c in parent_node.parent.children
                       if c.data and c.data.get("attachment_id") == "live-status-header"), None)

        if header and self._should_continue_loop(node_id):
            if header.is_expanded:
                self._run_live_check_async(app, tree_state, node_id, header)

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
            lambda: self._perform_check_worker(app, tree_state, node_data),
            thread=True,
            name=f"live_check_{node_id}"
        )

    def _perform_check_worker(self, app: Any, tree_state: Any, node_data: Dict) -> None:
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
                app.call_from_thread(self._update_ui_result, tree_state, node_id, result)
                return
            app.call_from_thread(self._update_ui_result, tree_state, node_id, {"error": "Config conversion failed"})
        except Exception as e:
            logger.exception(f"_perform_check_worker: exception for {node_id}")
            app.call_from_thread(self._update_ui_result, tree_state, node_id, {"error": str(e)})
        finally:
            self._live_check_in_progress.discard(node_id)

    def _update_ui_result(self, tree_state: Any, node_id: str, result: Dict) -> None:
        """Main Thread: Fills the UI node with external check results."""
        target = tree_state.get_node(node_id)
        if not target or not target.parent:
            return

        header = next((c for c in target.parent.children
                       if c.data and c.data.get("attachment_id") == "live-status-header"), None)
        if not header:
            return

        ts = datetime.datetime.now().strftime("%H:%M:%S")
        header.set_label(f"[bold cyan]Live Status[/] [italic gray]({ts})[bold cyan]:[/]")

        for child in list(header.children):
            child.remove()

        if result.get('success'):
            # Handle different result formats (value for snapshot, status+message for backfill)
            content = result.get('value') or f"{result.get('status', '')}\n{result.get('message', '')}"
            for line in content.replace('\\n', '\n').split('\n'):
                if line.strip():
                    header.add(line, data={"is_ephemeral": True, "is_live_data": True})
        else:
            msg = result.get('message') or result.get('error') or "Check failed"
            header.add(f"❌ {msg}", data={"is_ephemeral": True})
