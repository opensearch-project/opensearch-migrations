import datetime
import logging
import yaml
from dataclasses import dataclass
from typing import Dict, Set

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
        current_live_check_group_nodes: Set[TreeNode] = set()
        self._reconcile_node(app, root, current_live_check_group_nodes)
        if removed := self._active_live_check_group_nodes - current_live_check_group_nodes:
            logger.info(f"Removing {len(removed)} stale live status headers")
        self._active_live_check_group_nodes = current_live_check_group_nodes
        logger.info(f"Reconciliation complete: {len(current_live_check_group_nodes)} active headers")

    def _reconcile_node(self, app, node: TreeNode, current_live_check_group_nodes: Set[TreeNode]) -> None:
        """Process a node: manage live status based on configContents children."""
        config_children = [
            c for c in node.children
            if c.data and not c.data.get("is_ephemeral") and
            get_node_input_parameter(c.data, 'configContents') is not None
        ]
        live_status_node = next(
            (c for c in node.children if c.data and c.data.get("ephemeral_node_type") == "live-status-check"), None
        )

        if config_children:
            any_succeeded = any(c.data.get('phase') == "Succeeded" for c in config_children)
            logger.info(
                f"config_children={len(config_children)}, any_succeeded={any_succeeded}, "
                f"phases={[c.data.get('phase') for c in config_children]}"
            )

            if any_succeeded:
                if live_status_node:
                    logger.info("Removing live status: a config child succeeded")
                    live_status_node.remove()
            else:
                if not live_status_node:
                    first = config_children[0]
                    logger.info(f"Creating live status header for: {first.data.get('display_name')}")
                    live_status_node = node.add("[bold cyan]Live Status:[/]", data={
                        "is_ephemeral": True,
                        "ephemeral_node_type": "live-status-check",
                        "check_config": self._extract_check_config(first.data)
                    })

                # Move non-ephemeral nodes after live_status to before it
                children = list(node.children)
                idx = children.index(live_status_node)
                for child in children[idx + 1:]:
                    assert child.data and not child.data.get("is_ephemeral"), \
                        "Only non-ephemeral nodes should appear after live status header"
                    logger.debug(f"Reordering node {child.data.get('display_name')} before live status")
                    label, data = child.label, child.data
                    child.remove()
                    node.add(label, data=data, before=live_status_node)

                current_live_check_group_nodes.add(live_status_node)
                if live_status_node not in self._active_live_check_group_nodes:
                    logger.debug("Starting live loop")
                    self._start_live_loop(app, live_status_node)
        elif live_status_node:
            logger.debug("Removing orphaned live status (no config children)")
            live_status_node.remove()

        for child in node.children:
            if child.data and not child.data.get("is_ephemeral"):
                self._reconcile_node(app, child, current_live_check_group_nodes)

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
                return
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
