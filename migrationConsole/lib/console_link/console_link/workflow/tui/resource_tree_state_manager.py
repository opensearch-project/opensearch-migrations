"""Resource-centric tree state manager for the workflow manage TUI."""

from typing import Dict, List, Optional

from textual.widgets._tree import TreeNode, Tree

from console_link.workflow.resource_tree import (
    ResourceNode, ResourceGroup, ResourceSection,
    PHASE_SYMBOLS, RESOURCE_SECTIONS, DISPLAY_PHASES,
    CONFIG_MODE_ALL, format_config_diff_fields, format_spec_fields, format_live_status, has_notable_steps,
    collect_notable_steps, find_last_succeeded, step_timestamp,
    maybe_rewrite_wait_step, resource_visible_in_config_mode,
    format_resource_diagnostics,
)
from console_link.workflow.tree_utils import get_step_rich_label, get_step_status_output
from console_link.workflow.commands.crd_utils import DISPLAY_NAMES


RESOURCE_ID_PREFIX = 'resource:'


class ResourceTreeStateManager:
    """Builds and updates a resource-centric Textual Tree from CRs + Argo workflow data."""

    def __init__(self, tree_widget: Optional[Tree] = None, namespace: str = "",
                 on_new_pod=None):
        self.tree: Optional[Tree] = tree_widget
        self._namespace = namespace
        self._workflow_data: Dict = {}
        self._on_new_pod = on_new_pod
        self._config_value_mode = CONFIG_MODE_ALL

    def set_tree_widget(self, tree_widget: Tree) -> None:
        self.tree = tree_widget

    def set_config_value_mode(self, value_mode: str) -> None:
        self._config_value_mode = value_mode

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

    def expand_config_differences(self, sections: List[ResourceSection]) -> None:
        """Expand the ancestors and resource nodes that contain rollout-phase differences."""
        if not self.tree:
            return
        expansion_ids = self._config_difference_expansion_ids(sections)
        if not expansion_ids:
            return
        self.tree.root.expand()
        stack = list(self.tree.root.children)
        while stack:
            node = stack.pop()
            node_id = (node.data or {}).get('id') if isinstance(node.data, dict) else None
            if node_id in expansion_ids:
                node.expand()
            stack.extend(node.children)

    @classmethod
    def _config_difference_expansion_ids(cls, sections: List[ResourceSection]) -> set:
        expansion_ids = set()
        for section in sections:
            section_has_changes = False
            for group in section.groups:
                group_has_changes = False
                for resource in group.resources:
                    if cls._collect_changed_resource_ids(resource, expansion_ids):
                        group_has_changes = True
                if group_has_changes:
                    expansion_ids.add(f'group:{group.display_name}')
                    section_has_changes = True
            if section_has_changes:
                expansion_ids.add(f'section:{section.name}')
        return expansion_ids

    @classmethod
    def _collect_changed_resource_ids(cls, resource: ResourceNode, expansion_ids: set) -> bool:
        has_changes = bool(resource.config_diff) or bool(resource.diagnostics)
        child_has_changes = False
        for child in resource.children:
            if cls._collect_changed_resource_ids(child, expansion_ids):
                child_has_changes = True
        if has_changes or child_has_changes:
            expansion_ids.add(f'{RESOURCE_ID_PREFIX}{resource.name}')
            return True
        return False

    def update(self, sections: List[ResourceSection], workflow_data: Dict = None) -> None:
        """Incremental update preserving cursor, scroll, and expand/collapse state."""
        self._workflow_data = workflow_data or {}
        self.tree.root.label = "[bold]Migration Status[/]"
        self._update_sections(self.tree.root, sections)

    # --- Incremental diffing ---

    def _update_sections(self, root: TreeNode, sections: List[ResourceSection]) -> None:
        """Diff sections against existing tree children."""
        new_sections = [s for s in sections if any(self._group_has_content(g) for g in s.groups)]
        existing = self._existing_by_id(root)
        new_ids = [f'section:{s.name}' for s in new_sections]

        if self._has_structural_change(existing, new_ids):
            collapsed = self._save_collapsed(root)
            self._remove_children(root)
            for section in new_sections:
                sid = f'section:{section.name}'
                node = root.add(f"[bold]{section.name}[/]", data={'id': sid})
                for group in section.groups:
                    self._add_group(node, group)
                if sid in collapsed:
                    node.collapse()
                else:
                    node.expand()
        else:
            for section in new_sections:
                sid = f'section:{section.name}'
                section_node = existing[sid]
                self._update_groups(section_node, section.groups)

    def _update_groups(self, section_node: TreeNode, groups: List[ResourceGroup]) -> None:
        """Diff groups within a section."""
        new_groups = [g for g in groups if self._group_has_content(g)]
        existing = self._existing_by_id(section_node)
        new_ids = [f'group:{g.display_name}' for g in new_groups]

        if self._has_structural_change(existing, new_ids):
            self._rebuild_groups(section_node, new_groups)
        else:
            for group in new_groups:
                gid = f'group:{group.display_name}'
                group_node = existing[gid]
                if not group.not_configured:
                    self._update_resources(group_node, group)

    def _rebuild_groups(self, section_node: TreeNode, groups: List[ResourceGroup]) -> None:
        """Rebuild all groups under a section (structural change detected)."""
        collapsed = self._save_collapsed(section_node)
        self._remove_children(section_node)
        for group in groups:
            gid = f'group:{group.display_name}'
            node = section_node.add(f"[bold]{group.display_name}[/]", data={'id': gid})
            if group.not_configured:
                node.add("[dim](not configured)[/dim]", data=None)
            else:
                self._add_group_resources(node, group)
            if gid in collapsed:
                node.collapse()
            else:
                node.expand()

    def _update_resources(self, group_node: TreeNode, group: ResourceGroup) -> None:
        """Diff resources within a group."""
        group_plurals = next(
            (plurals for _, grps in RESOURCE_SECTIONS for plurals, _ in grps if plurals[0] == group.plural),
            [group.plural]
        )
        plural_order = {p: i for i, p in enumerate(group_plurals)}
        sorted_resources = sorted(
            self._visible_resources(group),
            key=lambda r: (plural_order.get(r.plural, 99), r.name),
        )

        existing = self._existing_by_id(group_node)
        new_ids = [f'{RESOURCE_ID_PREFIX}{r.name}' for r in sorted_resources]

        if self._has_structural_change(existing, new_ids):
            collapsed = self._save_collapsed(group_node)
            self._remove_children(group_node)
            for resource in sorted_resources:
                self._add_resource(group_node, resource)
            self._restore_collapse_state(group_node, collapsed)
        else:
            for resource in sorted_resources:
                rid = f'{RESOURCE_ID_PREFIX}{resource.name}'
                resource_node = existing[rid]
                resource_node.set_label(self._resource_label(resource))
                resource_node.data['phase'] = resource.phase
                # Always rebuild the subtree below the resource (details + workflow steps)
                self._rebuild_resource_children(resource_node, resource)

    def _rebuild_resource_children(self, resource_node: TreeNode, resource: ResourceNode) -> None:
        """Rebuild spec details, deps, workflow progress, and child resources under a resource node."""
        collapsed = self._save_collapsed_recursive(resource_node)
        self._remove_children(resource_node)

        self._add_resource_details(resource_node, resource)
        if resource.depends_on and resource.phase not in ('Ready', 'Completed'):
            resource_node.add(f"[dim]Depends on: {', '.join(resource.depends_on)}[/dim]", data=None)
        live = format_live_status(resource)
        if live:
            summary_line, detail_lines = live
            live_node = resource_node.add(f"[cyan]{summary_line}[/cyan]", data={'id': f'live:{resource.name}'})
            for line in detail_lines:
                live_node.add(f"[cyan]{line}[/cyan]", data=None)
            live_node.collapse()
        self._add_workflow_progress(resource_node, resource)
        for child in resource.children:
            if not self._resource_visible(child):
                continue
            self._add_resource(resource_node, child)

        self._restore_collapse_state_recursive(resource_node, collapsed)

    def _add_group_resources(self, group_node: TreeNode, group: ResourceGroup) -> None:
        """Add sorted resources to a group node."""
        group_plurals = next(
            (plurals for _, grps in RESOURCE_SECTIONS for plurals, _ in grps if plurals[0] == group.plural),
            [group.plural]
        )
        plural_order = {p: i for i, p in enumerate(group_plurals)}
        for resource in sorted(group.resources, key=lambda r: (plural_order.get(r.plural, 99), r.name)):
            if not self._resource_visible(resource):
                continue
            self._add_resource(group_node, resource)

    @staticmethod
    def _resource_label(resource: ResourceNode) -> str:
        symbol, color = PHASE_SYMBOLS.get(resource.phase, ('?', 'white'))
        change_label = ResourceTreeStateManager._resource_change_label(resource)
        if resource.phase in DISPLAY_PHASES:
            return (
                f"[{color}]{symbol}[/{color}] [bold]{resource.name}[/bold] "
                f"[{color}]({resource.phase})[/{color}]{change_label}"
            )
        return f"[{color}]{symbol}[/{color}] [bold]{resource.name}[/bold]{change_label}"

    @staticmethod
    def _resource_change_label(resource: ResourceNode) -> str:
        diagnostic = ResourceTreeStateManager._highest_priority_diagnostic(resource)
        if diagnostic:
            severity = diagnostic.get('severity') or 'error'
            style = ResourceTreeStateManager._diagnostic_style(severity)
            label = 'required' if severity == 'required' else severity
            return f' [{style}]({label})[/{style}]'
        diff = resource.config_diff or {}
        if not diff:
            return ''
        if diff.get('has_pending_submit_changes'):
            return ' [green](to submit)[/green]'
        if diff.get('has_submitted_changes'):
            return ' [grey50](pending)[/grey50]'
        return ''

    @staticmethod
    def _highest_priority_diagnostic(resource: ResourceNode) -> Optional[Dict]:
        rank = {'error': 4, 'required': 3, 'blocked': 3, 'gated': 2, 'warning': 1}
        diagnostics = resource.diagnostics or []
        if not diagnostics:
            return None
        return max(diagnostics, key=lambda item: rank.get(item.get('severity'), 0))

    @staticmethod
    def _diagnostic_style(severity: str) -> str:
        if severity in ('error', 'blocked'):
            return 'red'
        if severity == 'required':
            return 'yellow'
        if severity == 'gated':
            return 'magenta'
        return 'yellow'

    @staticmethod
    def _existing_by_id(parent: TreeNode) -> Dict[str, TreeNode]:
        """Map existing children by their stable ID."""
        return {
            child.data['id']: child
            for child in parent.children
            if child.data and isinstance(child.data, dict) and 'id' in child.data
        }

    @staticmethod
    def _has_structural_change(existing: Dict[str, TreeNode], new_ids: List[str]) -> bool:
        """Check if nodes were added, removed, or reordered."""
        return list(existing.keys()) != new_ids

    @staticmethod
    def _save_collapsed(parent: TreeNode) -> set:
        """Save collapsed node IDs under a parent (direct children only)."""
        result = set()
        for child in parent.children:
            if child.data and isinstance(child.data, dict) and not child.is_expanded:
                nid = child.data.get('id')
                if nid:
                    result.add(nid)
        return result

    @staticmethod
    def _remove_children(parent: TreeNode) -> None:
        """Remove all children from a node."""
        while parent.children:
            parent.children[-1].remove()

    def _restore_collapse_state(self, parent: TreeNode, collapsed_ids: set) -> None:
        """Restore expanded state for children. Only collapse nodes that were previously collapsed."""
        for child in parent.children:
            if child.data and isinstance(child.data, dict):
                nid = child.data.get('id')
                if nid and nid in collapsed_ids:
                    child.collapse()
                else:
                    child.expand()
            else:
                child.expand()

    @staticmethod
    def _save_collapsed_recursive(parent: TreeNode) -> set:
        """Save collapsed node IDs recursively under a parent."""
        result = set()
        stack = list(parent.children)
        while stack:
            child = stack.pop()
            if child.data and isinstance(child.data, dict) and not child.is_expanded:
                nid = child.data.get('id')
                if nid:
                    result.add(nid)
            stack.extend(child.children)
        return result

    def _restore_collapse_state_recursive(self, parent: TreeNode, collapsed_ids: set) -> None:
        """Restore expanded state recursively. Only collapse nodes that were previously collapsed."""
        stack = list(parent.children)
        while stack:
            child = stack.pop()
            if child.data and isinstance(child.data, dict):
                nid = child.data.get('id')
                if nid and nid in collapsed_ids:
                    child.collapse()
                else:
                    child.expand()
            else:
                child.expand()
            stack.extend(child.children)

    def _populate_tree(self, sections: List[ResourceSection]) -> None:
        """Populate the Textual Tree widget from resource sections."""
        for section in sections:
            section_has_content = any(self._group_has_content(g) for g in section.groups)
            if not section_has_content:
                continue
            section_node = self.tree.root.add(
                f"[bold]{section.name}[/]", data={'id': f'section:{section.name}'})
            for group in section.groups:
                self._add_group(section_node, group)

    def _add_group(self, parent: TreeNode, group: ResourceGroup) -> None:
        """Add a resource group to the tree."""
        if not self._group_has_content(group):
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
        for resource in sorted(self._visible_resources(group), key=lambda r: (plural_order.get(r.plural, 99), r.name)):
            self._add_resource(group_node, resource)

    def _add_resource(self, parent: TreeNode, resource: ResourceNode) -> None:
        """Add a resource node with its details and workflow subtree."""
        label = self._resource_label(resource)
        resource_path = f"{DISPLAY_NAMES.get(resource.plural, resource.plural)}.{resource.name}"
        resource_node = parent.add(label, data={
            'id': f'{RESOURCE_ID_PREFIX}{resource.name}',
            'resource_path': resource_path,
            'phase': resource.phase,
        })

        # Spec details
        self._add_resource_details(resource_node, resource)
        if resource.depends_on and resource.phase not in ('Ready', 'Completed'):
            resource_node.add(f"[dim]Depends on: {', '.join(resource.depends_on)}[/dim]", data=None)
        live = format_live_status(resource)
        if live:
            summary_line, detail_lines = live
            live_node = resource_node.add(f"[cyan]{summary_line}[/cyan]", data={'id': f'live:{resource.name}'})
            for line in detail_lines:
                live_node.add(f"[cyan]{line}[/cyan]", data=None)
            live_node.collapse()

        # Workflow subtree (nodes carry Argo dict data for interactions)
        self._add_workflow_progress(resource_node, resource)

        # Children (e.g., topics under kafka)
        for child in resource.children:
            if not self._resource_visible(child):
                continue
            self._add_resource(resource_node, child)

    def _add_resource_details(self, resource_node: TreeNode, resource: ResourceNode) -> None:
        for field in format_spec_fields(resource):
            resource_node.add(f"[dim]{field}[/dim]", data=None)
        for field in format_config_diff_fields(resource, self._config_value_mode, rich_markup=True):
            resource_node.add(field, data=None)
        for diagnostic in format_resource_diagnostics(resource):
            style = self._diagnostic_style(diagnostic.get('severity', 'error'))
            resource_node.add(f"[{style}]{diagnostic['label']}[/{style}]", data=None)

    def _group_has_content(self, group: ResourceGroup) -> bool:
        return bool(self._visible_resources(group)) or group.not_configured

    def _visible_resources(self, group: ResourceGroup) -> List[ResourceNode]:
        return [resource for resource in group.resources if self._resource_visible(resource)]

    def _resource_visible(self, resource: ResourceNode) -> bool:
        return resource_visible_in_config_mode(resource, self._config_value_mode)

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
        status_output = get_step_status_output(self._workflow_data, step.get('id', ''))
        label = get_step_rich_label(display_step, status_output=status_output, show_approval_name=False)
        node = parent.add(label, data=step)
        if step.get('type') == 'Pod' and self._on_new_pod:
            self._on_new_pod(step['id'])
        self._add_live_check_lines(node, step)
        for child in sorted(collect_notable_steps(step.get('children', [])), key=step_timestamp):
            self._add_workflow_step(node, child)

    @staticmethod
    def _add_live_check_lines(node: TreeNode, step: Dict) -> None:
        """Add live check result lines under a workflow step node."""
        live_check = step.get('live_check')
        if live_check and live_check.get('success') and 'value' in live_check:
            for line in live_check['value'].replace('\\n', '\n').strip().split('\n'):
                if line.strip():
                    node.add(f"[cyan]{line.strip()}[/cyan]", data=None)
