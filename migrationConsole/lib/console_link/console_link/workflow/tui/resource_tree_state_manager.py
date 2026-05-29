"""Resource-centric tree state manager for the workflow manage TUI."""

from typing import Dict, List, Optional

from textual.widgets._tree import TreeNode, Tree

from console_link.workflow.resource_tree import (
    ResourceNode, ResourceGroup, ResourceSection,
    PHASE_SYMBOLS, RESOURCE_SECTIONS, DISPLAY_PHASES,
    format_spec_fields, has_notable_steps,
    collect_notable_steps, find_last_succeeded, step_timestamp,
    maybe_rewrite_wait_step,
)
from console_link.workflow.tree_utils import get_step_rich_label
from console_link.workflow.commands.crd_utils import DISPLAY_NAMES


class ResourceTreeStateManager:
    """Builds and updates a resource-centric Textual Tree from CRs + Argo workflow data."""

    def __init__(self, tree_widget: Optional[Tree] = None, namespace: str = ""):
        self.tree: Optional[Tree] = tree_widget
        self._namespace = namespace
        self._workflow_data: Dict = {}

    def set_tree_widget(self, tree_widget: Tree) -> None:
        self.tree = tree_widget

    def reset(self, root_label: str) -> None:
        self.tree.clear()
        self.tree.root.label = root_label

    def rebuild(self, sections: List[ResourceSection], workflow_data: Dict = None) -> None:
        """Full rebuild of the resource tree from pre-built sections."""
        self._workflow_data = workflow_data or {}
        self.tree.clear()
        self.tree.root.label = "[bold]Migration Status[/]"
        self._populate_tree(sections)
        self.tree.root.expand_all()

    def update(self, sections: List[ResourceSection], workflow_data: Dict = None) -> None:
        """Rebuild preserving expand/collapse state."""
        self._workflow_data = workflow_data or {}
        collapsed_ids = self._collect_collapsed_ids()
        self.tree.clear()
        self.tree.root.label = "[bold]Migration Status[/]"
        self._populate_tree(sections)
        self._restore_collapsed(collapsed_ids)

    def _collect_collapsed_ids(self) -> set:
        """Collect IDs of collapsed nodes."""
        collapsed = set()

        def walk(node):
            for child in node.children:
                if child.data and not child.is_expanded:
                    node_id = child.data.get('id') if isinstance(child.data, dict) else None
                    if node_id:
                        collapsed.add(node_id)
                walk(child)

        walk(self.tree.root)
        return collapsed

    def _restore_collapsed(self, collapsed_ids: set) -> None:
        """Restore collapsed state by ID, expanding everything else."""
        for child in self._all_tree_nodes():
            node_id = child.data.get('id') if isinstance(child.data, dict) else None
            if node_id and node_id in collapsed_ids:
                child.collapse()
            else:
                child.expand()

    def _all_tree_nodes(self):
        """Yield all tree nodes depth-first."""
        stack = list(self.tree.root.children)
        while stack:
            node = stack.pop()
            yield node
            stack.extend(node.children)

    def _populate_tree(self, sections: List[ResourceSection]) -> None:
        """Populate the Textual Tree widget from resource sections."""
        for section in sections:
            section_has_content = any(g.resources or g.not_configured for g in section.groups)
            if not section_has_content:
                continue
            section_node = self.tree.root.add(
                f"[bold]{section.name}[/]", data={'id': f'section:{section.name}'})
            for group in section.groups:
                self._add_group(section_node, group)

    def _add_group(self, parent: TreeNode, group: ResourceGroup) -> None:
        """Add a resource group to the tree."""
        if not group.resources and not group.not_configured:
            return
        group_node = parent.add(
            f"[bold]{group.display_name}[/]", data={'id': f'group:{group.display_name}'})
        if group.not_configured:
            group_node.add("[dim](not configured)[/dim]", data=None)
            return

        group_plurals = next(
            (plurals for _, grps in RESOURCE_SECTIONS for plurals, _ in grps if plurals[0] == group.plural),
            [group.plural]
        )
        plural_order = {p: i for i, p in enumerate(group_plurals)}
        for resource in sorted(group.resources, key=lambda r: (plural_order.get(r.plural, 99), r.name)):
            self._add_resource(group_node, resource)

    def _add_resource(self, parent: TreeNode, resource: ResourceNode) -> None:
        """Add a resource node with its details and workflow subtree."""
        symbol, color = PHASE_SYMBOLS.get(resource.phase, ('?', 'white'))
        if resource.phase in DISPLAY_PHASES:
            label = f"[{color}]{symbol}[/{color}] [bold]{resource.name}[/bold] [{color}]({resource.phase})[/{color}]"
        else:
            label = f"[{color}]{symbol}[/{color}] [bold]{resource.name}[/bold]"
        resource_path = f"{DISPLAY_NAMES.get(resource.plural, resource.plural)}.{resource.name}"
        resource_node = parent.add(label, data={
            'id': f'resource:{resource.name}',
            'resource_path': resource_path,
        })

        # Spec details
        details = format_spec_fields(resource)
        if details:
            resource_node.add(f"[dim]{details}[/dim]", data=None)
        if resource.depends_on:
            resource_node.add(f"[dim]Depends on: {', '.join(resource.depends_on)}[/dim]", data=None)

        # Workflow subtree (nodes carry Argo dict data for interactions)
        self._add_workflow_progress(resource_node, resource)

        # Children (e.g., topics under kafka)
        for child in resource.children:
            self._add_resource(resource_node, child)

    def _add_workflow_progress(self, resource_node: TreeNode, resource: ResourceNode) -> None:
        """Add filtered workflow progress subtree if notable steps exist."""
        if not resource.workflow_progress:
            return
        if not has_notable_steps(resource.workflow_progress):
            return
        notable = collect_notable_steps(resource.workflow_progress)
        if not notable:
            return
        last_succeeded = find_last_succeeded(resource.workflow_progress)
        if last_succeeded and last_succeeded not in notable:
            notable.append(last_succeeded)
        notable.sort(key=step_timestamp)
        wf_node = resource_node.add(
            "Workflow progress:",
            data={'id': f'workflow:{resource.name}'})
        for step in notable:
            self._add_workflow_step(wf_node, step)

    def _add_workflow_step(self, parent: TreeNode, step: Dict) -> None:
        """Add a workflow step node (carries Argo dict for interactions)."""
        display_step = maybe_rewrite_wait_step(step)
        label = get_step_rich_label(display_step, status_output=None, show_approval_name=False)
        node = parent.add(label, data=step)
        live_check = step.get('live_check')
        if live_check and live_check.get('success') and 'value' in live_check:
            for line in live_check['value'].replace('\\n', '\n').strip().split('\n'):
                if line.strip():
                    node.add(f"[cyan]{line.strip()}[/cyan]", data=None)
        for child in sorted(collect_notable_steps(step.get('children', [])), key=step_timestamp):
            self._add_workflow_step(node, child)
