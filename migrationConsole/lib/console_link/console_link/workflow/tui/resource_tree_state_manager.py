"""Resource-centric tree state manager for the workflow manage TUI."""

from typing import Callable, Dict, List, Optional

from textual.widgets._tree import TreeNode, Tree

from console_link.workflow.resource_tree import (
    ResourceNode, ResourceGroup, ResourceSection,
    build_resource_tree, extract_workflow_steps_by_resource,
    mark_not_configured_groups, PHASE_SYMBOLS, RESOURCE_SECTIONS,
)
from console_link.workflow.tree_utils import (
    get_step_rich_label, get_step_status_output,
    build_nested_workflow_tree, filter_tree_nodes,
)
from console_link.workflow.commands.crd_utils import list_migration_resources_full


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

    def rebuild(self, workflow_data: Dict) -> None:
        """Full rebuild of the resource tree."""
        self._workflow_data = workflow_data
        self.tree.clear()
        self.tree.root.label = "[bold]Migration Resources[/]"

        sections = self._build_sections(workflow_data)
        self._populate_tree(sections)
        self.tree.root.expand_all()

    def update(self, workflow_data: Dict) -> None:
        """Rebuild preserving expand/collapse state."""
        self._workflow_data = workflow_data
        collapsed_ids = self._collect_collapsed_ids()
        self.tree.clear()
        self.tree.root.label = "[bold]Migration Resources[/]"

        sections = self._build_sections(workflow_data)
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

        def walk(node):
            for child in node.children:
                if child.data:
                    node_id = child.data.get('id') if isinstance(child.data, dict) else None
                    if node_id and node_id in collapsed_ids:
                        child.collapse()
                    else:
                        child.expand()
                else:
                    child.expand()
                walk(child)

        walk(self.tree.root)

    def _build_sections(self, workflow_data: Dict) -> List[ResourceSection]:
        """Build the resource sections with workflow steps merged."""
        sections = build_resource_tree(self._namespace)

        if workflow_data and workflow_data.get('status', {}).get('nodes'):
            filtered_tree = filter_tree_nodes(build_nested_workflow_tree(workflow_data))
            steps = extract_workflow_steps_by_resource(filtered_tree)
            for section in sections:
                for group in section.groups:
                    for resource in group.resources:
                        if resource.name in steps:
                            resource.workflow_step = steps[resource.name]
                        for child in resource.children:
                            if child.name in steps:
                                child.workflow_step = steps[child.name]
            mark_not_configured_groups(sections, filtered_tree)

        return sections

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
        label = f"[{color}]{symbol} {resource.name} ({resource.phase})[/{color}]"
        resource_node = parent.add(label, data={'id': f'resource:{resource.name}'})

        # Spec details
        from console_link.workflow.resource_tree import _format_spec_fields
        details = _format_spec_fields(resource)
        if details:
            resource_node.add(f"[dim]{details}[/dim]", data=None)
        if resource.depends_on:
            resource_node.add(f"[dim]Depends on: {', '.join(resource.depends_on)}[/dim]", data=None)

        # Workflow subtree (nodes carry Argo dict data for interactions)
        if resource.workflow_step:
            wf_node = resource_node.add(
                "[bold]Workflow progress:[/bold]",
                data={'id': f'workflow:{resource.name}'})
            for step in resource.workflow_step:
                self._add_workflow_step(wf_node, step)

        # Children (e.g., topics under kafka)
        for child in resource.children:
            self._add_resource(resource_node, child)

    def _add_workflow_step(self, parent: TreeNode, step: Dict) -> None:
        """Add a workflow step node (carries Argo dict for interactions)."""
        label = get_step_rich_label(step, status_output=None, show_approval_name=False)
        node = parent.add(label, data=step)
        for child in step.get('children', []):
            self._add_workflow_step(node, child)
