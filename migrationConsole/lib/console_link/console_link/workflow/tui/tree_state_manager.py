from typing import Callable, Dict, List, Optional

from textual.widgets._tree import TreeNode, Tree

from console_link.workflow.tree_utils import (
    get_step_status_output, get_step_rich_label,
    build_nested_workflow_tree, filter_tree_nodes,
    overlay_approval_gate_status,
    overlay_data_snapshot_creation_status,
    overlay_snapshot_migration_backfill_status
)


class TreeStateManager:
    def __init__(self, tree_widget: Optional[Tree] = None, namespace: str = "",
                 on_new_pod: Optional[Callable[[str], None]] = None):
        self.tree: Optional[Tree] = tree_widget
        self._workflow_data: Dict = {}
        self._namespace = namespace
        self._on_new_pod_handler = on_new_pod

    def set_tree_widget(self, tree_widget: Tree) -> None:
        self.tree = tree_widget

    def reset(self, root_label: str) -> None:
        self.tree.clear()
        self.tree.root.label = root_label

    def rebuild(self, workflow_data: Dict) -> None:
        """Full rebuild for first load or workflow restart."""
        self._workflow_data = workflow_data
        self.tree.clear()
        self.tree.root.label = "[bold]Workflow Steps[/]"
        nodes = filter_tree_nodes(build_nested_workflow_tree(workflow_data))
        overlay_approval_gate_status(nodes, self._namespace)
        overlay_data_snapshot_creation_status(nodes, self._namespace)
        overlay_snapshot_migration_backfill_status(nodes, self._namespace)
        self._populate_recursive(self.tree.root, nodes)
        self.tree.root.expand_all()

    def update(self, workflow_data: Dict) -> None:
        """Incremental update for ongoing workflow."""
        self._workflow_data = workflow_data
        nodes = filter_tree_nodes(build_nested_workflow_tree(workflow_data))
        overlay_approval_gate_status(nodes, self._namespace)
        overlay_data_snapshot_creation_status(nodes, self._namespace)
        overlay_snapshot_migration_backfill_status(nodes, self._namespace)
        self._update_recursive(self.tree.root, nodes)

    @staticmethod
    def _sort_key(n: Dict):
        return (n.get('sort_order', 999),
                n.get('finished_at') or n.get('started_at') or '9999-12-31T23:59:59Z',
                n.get('display_name', ''))

    def _populate_recursive(self, parent_node: TreeNode, nodes: List[Dict]) -> None:
        for node in sorted(nodes, key=self._sort_key):
            label = self._get_label(node)
            tree_node = parent_node.add(label, data=node)
            if node.get('type') == 'Pod' and self._on_new_pod_handler:
                self._on_new_pod_handler(node['id'])
            if node.get('children'):
                self._populate_recursive(tree_node, node['children'])

    def _update_recursive(self, parent_tree_node: TreeNode, new_nodes: List[Dict]) -> None:
        existing_children = {
            child.data['id']: child
            for child in parent_tree_node.children
            if child.data and 'id' in child.data and not child.data.get('is_ephemeral')
        }
        new_ids = {node['id'] for node in new_nodes}

        # Detect if any new nodes need inserting or old ones need removing
        needs_reorder = (
            any(node['id'] not in existing_children for node in new_nodes) or
            any(
                child.data['id'] not in new_ids
                for child in parent_tree_node.children
                if child.data and 'id' in child.data and not child.data.get('is_ephemeral')
            )
        )

        if needs_reorder:
            # Rebuild this level to maintain sort order
            # Preserve expanded state and subtree data from existing children
            expanded_ids = {
                child.data['id'] for child in parent_tree_node.children
                if child.data and child.is_expanded
            }
            children_snapshot = list(parent_tree_node.children)
            for child in children_snapshot:
                child.remove()
            for node in sorted(new_nodes, key=self._sort_key):
                label = self._get_label(node)
                tree_node = parent_tree_node.add(label, data=node)
                if node['id'] in expanded_ids or node['id'] not in existing_children:
                    tree_node.expand()
                if node.get('type') == 'Pod' and self._on_new_pod_handler and node['id'] not in existing_children:
                    self._on_new_pod_handler(node['id'])
                if node.get('children'):
                    self._update_recursive(tree_node, node['children'])
        else:
            # No structural changes — just update labels and recurse
            for node in new_nodes:
                tree_node = existing_children[node['id']]
                if tree_node.data.get('phase') != node.get('phase'):
                    tree_node.set_label(self._get_label(node))
                tree_node.data = node
                if node.get('children'):
                    self._update_recursive(tree_node, node['children'])

    def _get_label(self, node: Dict):
        status_output = get_step_status_output(self._workflow_data, node['id'])
        return get_step_rich_label(node, status_output, show_approval_name=False)
