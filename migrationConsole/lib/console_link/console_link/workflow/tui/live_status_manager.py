import datetime
import logging
import yaml
from dataclasses import dataclass
from typing import Dict, Set, Optional, List

from textual.widgets._tree import TreeNode

from console_link.environment import Environment
from console_link.workflow.commands.status import StatusCheckRunner, ConfigConverter
from console_link.workflow.tree_utils import get_node_input_parameter

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class LiveCheckConfig:
    """Immutable config extracted from tip node for performing live checks."""
    node_id: str
    display_name: str
    config_contents: str
    check_type: str


class LiveStatusManager:
    """Manages ephemeral 'Live Status' nodes in the workflow tree."""

    def __init__(self, refresh_interval: float):
        self._refresh_interval = refresh_interval
        self._active_live_check_group_nodes: Set[TreeNode] = set()
        self._live_check_in_progress: Set[int] = set()

    def reconcile_tree_for_live_status_checks(self, app, root: TreeNode) -> None:
        """Recursively reconcile live status nodes throughout the tree."""
        logger.info("Starting live status reconciliation")
        current_active: Set[TreeNode] = set()
        self._reconcile_node(app, root, current_active)

        if removed := self._active_live_check_group_nodes - current_active:
            logger.info(f"Removing {len(removed)} stale live status headers")

        self._active_live_check_group_nodes = current_active
        logger.info(f"Reconciliation complete: {len(current_active)} active headers")

    def _reconcile_node(self, app, node: TreeNode, current_active: Set[TreeNode]) -> None:
        """Process a node: branching between group-level and direct-level status."""
        checks_group = self._find_checks_group(node)

        if checks_group:
            self._reconcile_checks_group(app, node, checks_group, current_active)
        else:
            self._reconcile_direct_config_children(app, node, current_active)

        # Recurse, but skip the checks_group if we just handled it
        for child in node.children:
            if child.data and not child.data.get("is_ephemeral") and child is not checks_group:
                self._reconcile_node(app, child, current_active)

    def _reconcile_checks_group(self, app, parent: TreeNode, checks_group: TreeNode,
                                current_active: Set[TreeNode]) -> None:
        """Handle live status as a sibling immediately following the checks group."""
        config_children = self._get_config_children(checks_group)
        live_node = self._find_live_node(parent)

        if not config_children:
            if live_node:
                live_node.remove()
            return

        if any(c.data.get('phase') == "Succeeded" for c in config_children):
            if live_node:
                live_node.remove()
        else:
            if not live_node:
                live_node = parent.add("[bold cyan]Live Status:[/]", data={
                    "is_ephemeral": True,
                    "ephemeral_node_type": "live-status-check",
                    "check_config": self._extract_check_config(config_children[0].data)
                }, after=checks_group)

            # Sibling positioning logic
            children = list(parent.children)
            if children.index(live_node) != children.index(checks_group) + 1:
                label, data = live_node.label, live_node.data
                live_node.remove()
                live_node = parent.add(label, data=data, after=checks_group)

            self._track_and_start(app, live_node, current_active)

    def _reconcile_direct_config_children(self, app, node: TreeNode, current_active: Set[TreeNode]) -> None:
        """Handle live status as a child of the current node."""
        config_children = self._get_config_children(node)
        live_node = self._find_live_node(node)

        if not config_children:
            if live_node:
                live_node.remove()
            return

        if any(c.data.get('phase') == "Succeeded" for c in config_children):
            if live_node:
                live_node.remove()
        else:
            if not live_node:
                live_node = node.add("[bold cyan]Live Status:[/]", data={
                    "is_ephemeral": True,
                    "ephemeral_node_type": "live-status-check",
                    "check_config": self._extract_check_config(config_children[0].data)
                })

            # Reordering logic: ensure non-ephemerals are before the live node
            children = list(node.children)
            live_idx = children.index(live_node)
            for child in children[live_idx + 1:]:
                if child.data and not child.data.get("is_ephemeral"):
                    label, data = child.label, child.data
                    child.remove()
                    node.add(label, data=data, before=live_node)

            self._track_and_start(app, live_node, current_active)

    def _find_checks_group(self, node: TreeNode) -> Optional[TreeNode]:
        return next((c for c in node.children if
                     c.data and not c.data.get("is_ephemeral") and c.data.get("group_name") == "checks"), None)

    def _find_live_node(self, container: TreeNode) -> Optional[TreeNode]:
        return next(
            (c for c in container.children if c.data and c.data.get("ephemeral_node_type") == "live-status-check"),
            None)

    def _get_config_children(self, node: TreeNode) -> List[TreeNode]:
        return [c for c in node.children if
                c.data and not c.data.get("is_ephemeral") and get_node_input_parameter(c.data,
                                                                                       'configContents') is not None]

    def _track_and_start(self, app, live_node: TreeNode, current_active: Set[TreeNode]) -> None:
        current_active.add(live_node)
        if live_node not in self._active_live_check_group_nodes:
            self._start_live_loop(app, live_node)

    def _extract_check_config(self, node_data: Dict) -> LiveCheckConfig:
        """Extract immutable check config from node data."""
        display_name = node_data.get('display_name', '')
        return LiveCheckConfig(
            node_id=node_data.get('id', 'unknown'),
            display_name=display_name,
            config_contents=get_node_input_parameter(node_data, 'configContents'),
            check_type='snapshot' if 'snapshot' in display_name.lower() else 'backfill'
        )

    def _start_live_loop(self, app, header: TreeNode) -> None:
        """Start the polling loop for a live status header."""
        self._active_live_check_group_nodes.add(header)
        self._per_header_live_loop(app, header)

    def _per_header_live_loop(self, app, header: TreeNode) -> None:
        """Self-scheduling update loop for a live status header."""
        if app.is_exiting or header not in self._active_live_check_group_nodes:
            logger.debug("Live loop stopping: app exiting or header removed")
            return

        if header.is_expanded:
            self._run_live_check_async(app, header)

        app.set_timer(self._refresh_interval, lambda: self._per_header_live_loop(app, header))

    def _run_live_check_async(self, app, header: TreeNode) -> None:
        """Dispatch worker thread for status check."""
        header_id = id(header)
        if header_id in self._live_check_in_progress:
            return
        self._live_check_in_progress.add(header_id)
        check_config: LiveCheckConfig = header.data.get("check_config")
        logger.debug(f"Starting async live check for node: {check_config.node_id}")
        app.run_worker(lambda: self._perform_check_worker(app, header, check_config), thread=True)

    def _perform_check_worker(self, app, header: TreeNode, config: LiveCheckConfig) -> None:
        """Background thread: perform status check."""
        header_id = id(header)
        try:
            svc_cfg = ConfigConverter.convert_with_jq(config.config_contents)
            if svc_cfg:
                env = Environment(config=yaml.safe_load(svc_cfg))
                logger.debug(f"Running status check for {config.node_id} (type={config.check_type})")
                result = StatusCheckRunner.run_status_check(env, {"check_type": config.check_type})
                app.call_from_thread(self._update_ui_result, header, result)
            else:
                logger.warning(f"Config conversion failed for {config.node_id}")
                app.call_from_thread(self._update_ui_result, header, {"error": "Config conversion failed"})
        except Exception as e:
            logger.exception(f"_perform_check_worker: exception for {config.node_id}")
            app.call_from_thread(self._update_ui_result, header, {"error": str(e)})
        finally:
            self._live_check_in_progress.discard(header_id)

    def _update_ui_result(self, header: TreeNode, result: Dict) -> None:
        """Main thread: update UI with check results."""
        if header not in self._active_live_check_group_nodes:
            logger.debug("Skipping UI update: header no longer active")
            return

        ts = datetime.datetime.now().strftime("%H:%M:%S")
        header.set_label(f"[bold cyan]Live Status[/] [italic gray]({ts})[bold cyan]:[/]")

        for child in header.children:
            child.remove()

        if result.get('success'):
            content = result.get('value') or f"{result.get('status', '')}\n{result.get('message', '')}"
            for line in content.replace('\\n', '\n').split('\n'):
                if line.strip():
                    header.add(line, data={"is_ephemeral": True, "is_live_data": True})
        else:
            msg = result.get('message') or result.get('error') or "Check failed"
            logger.debug(f"Live check failed: {msg}")
            header.add(f"❌ {msg}", data={"is_ephemeral": True})
