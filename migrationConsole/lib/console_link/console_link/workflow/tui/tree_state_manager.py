from typing import Callable, Dict, List, Optional

from textual.widgets._tree import TreeNode, Tree

from console_link.workflow.tree_utils import (
    get_step_status_output, get_step_rich_label,
    build_nested_workflow_tree, filter_tree_nodes
)


class TreeStateManager:
    def __init__(self, tree_widget: Optional[Tree] = None, on_new_pod: Optional[Callable[[str], None]] = None):
        self.tree: Optional[Tree] = tree_widget
        self.node_mapping: Dict[str, TreeNode] = {}
        self.workflow_data: Dict = {}
        self.on_new_pod = on_new_pod

    def set_tree_widget(self, tree_widget: Tree) -> None:
        self.tree = tree_widget

    def reset(self, root_label: str) -> None:
        self.tree.clear()
        self.node_mapping.clear()
        self.tree.root.label = root_label

    def rebuild(self, workflow_data: Dict) -> None:
        """Full rebuild for first load or workflow restart."""
        self.workflow_data = workflow_data
        self.node_mapping.clear()
        self.tree.clear()
        self.tree.root.label = "[bold]Workflow Steps[/]"
        nodes = filter_tree_nodes(build_nested_workflow_tree(workflow_data))
        self._populate_recursive(self.tree.root, nodes)
        self.tree.root.expand_all()

    def update(self, workflow_data: Dict) -> None:
        """Incremental update for ongoing workflow."""
        self.workflow_data = workflow_data
        nodes = filter_tree_nodes(build_nested_workflow_tree(workflow_data))
        self._update_recursive(self.tree.root, nodes)

    def get_node(self, node_id: str) -> Optional[TreeNode]:
        return self.node_mapping.get(node_id)

    def get_or_create_attachment(self, parent_id: str, label: str, attachment_id: str) -> TreeNode:
        """Get or create an ephemeral attachment node on a parent."""
        parent = self.node_mapping.get(parent_id)
        if not parent:
            raise ValueError(f"Parent node {parent_id} not found")

        for child in parent.children:
            if child.data and child.data.get("attachment_id") == attachment_id:
                return child

        return parent.add(label, data={"is_ephemeral": True, "attachment_id": attachment_id, "origin_id": parent_id})

    def _populate_recursive(self, parent_node: TreeNode, nodes: List[Dict]) -> None:
        for node in sorted(nodes, key=lambda n: n.get('started_at') or '9'):
            label = self._get_label(node)
            tree_node = parent_node.add(label, data=node)
            self.node_mapping[node['id']] = tree_node
            if node.get('type') == 'Pod' and self.on_new_pod:
                self.on_new_pod(node['id'])
            if node.get('children'):
                self._populate_recursive(tree_node, node['children'])

    def _update_recursive(self, parent_tree_node: TreeNode, new_nodes: List[Dict]) -> None:
        existing_children = {
            child.data['id']: child
            for child in parent_tree_node.children
            if child.data and 'id' in child.data and not child.data.get('is_ephemeral')
        }
        new_ids = {node['id'] for node in new_nodes}

        for node in new_nodes:
            node_id = node['id']
            label = self._get_label(node)
            if node_id in existing_children:
                tree_node = existing_children[node_id]
                if tree_node.data.get('phase') != node.get('phase'):
                    tree_node.set_label(label)
                tree_node.data = node
            else:
                tree_node = parent_tree_node.add(label, data=node)
                tree_node.expand()
                self.node_mapping[node_id] = tree_node
                if node.get('type') == 'Pod' and self.on_new_pod:
                    self.on_new_pod(node_id)

            if node.get('children'):
                self._update_recursive(self.node_mapping[node_id], node['children'])

        for child in list(parent_tree_node.children):
            if (child.data and 'id' in child.data and child.data['id'] not in new_ids and
                    not child.data.get('is_ephemeral')):
                self.node_mapping.pop(child.data['id'], None)
                child.remove()

    def _get_label(self, node: Dict):
        status_output = get_step_status_output(self.workflow_data, node['id'])
        return get_step_rich_label(node, status_output)
