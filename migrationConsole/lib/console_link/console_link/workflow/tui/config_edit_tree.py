"""Rendering helpers for schema-driven workflow config edit state."""

from typing import Any, Dict, Iterable, List, Optional

from rich.text import Text
from textual.widgets import Static, Tree
from textual.widgets._tree import TreeNode


EDIT_NODE_TYPE = "config-edit"


def render_edit_state(tree: Tree, edit_state: Dict[str, Any]) -> None:
    """Render a generic TS-provided EditStateV1 into the manage tree."""
    tree.clear()
    tree.root.set_label(Text("Workflow Config Edit"))
    tree.root.data = {"id": "config-edit-root", "type": EDIT_NODE_TYPE}
    for node in edit_state.get("nodes", []):
        _add_edit_node(tree.root, node)
    tree.root.expand_all()


def selected_edit_node(tree: Tree) -> Optional[Dict[str, Any]]:
    node = tree.cursor_node
    data = node.data if node else None
    if data and data.get("type") == EDIT_NODE_TYPE:
        return data.get("edit_node") or data
    return None


def update_help_panel(panel: Static, edit_node: Optional[Dict[str, Any]]) -> None:
    """Update the bottom help/status panel for the selected edit node."""
    if not edit_node:
        panel.update("")
        return

    path = " > ".join(str(part) for part in edit_node.get("path", [])) or "workflow config"
    description = edit_node.get("description") or edit_node.get("descriptionShort") or ""
    status_line = _status_line(edit_node)
    lines = [
        f"[bold]Help / Status[/]  [cyan]{path}[/]",
        status_line,
    ]
    if description:
        lines.append(description)
    panel.update("\n".join(lines[:4]))


def _add_edit_node(parent: TreeNode, edit_node: Dict[str, Any]) -> TreeNode:
    node = parent.add(
        Text(str(edit_node.get("label", ""))),
        data={
            "id": edit_node.get("id"),
            "type": EDIT_NODE_TYPE,
            "edit_node": edit_node,
        },
    )
    for child in edit_node.get("children") or []:
        _add_edit_node(node, child)
    return node


def _status_line(edit_node: Dict[str, Any]) -> str:
    diagnostics = list(_iter_diagnostics(edit_node))
    if diagnostics:
        first = diagnostics[0]
        severity = first.get("severity", "status").upper()
        return f"[yellow]{severity}:[/] {first.get('message', '')}"

    counts = edit_node.get("statusCounts") or {}
    summary = []
    if counts.get("required"):
        summary.append(f"{counts['required']} required")
    if counts.get("errors"):
        summary.append(f"{counts['errors']} errors")
    if counts.get("warnings"):
        summary.append(f"{counts['warnings']} warnings")
    if counts.get("gated"):
        summary.append(f"{counts['gated']} gated")
    if counts.get("blocked"):
        summary.append(f"{counts['blocked']} blocked")
    if counts.get("changed"):
        summary.append(f"{counts['changed']} changed")
    if summary:
        return "[yellow]Status:[/] " + ", ".join(summary)
    return "[green]Status:[/] complete"


def _iter_diagnostics(edit_node: Dict[str, Any]) -> Iterable[Dict[str, Any]]:
    direct = edit_node.get("diagnostics") or []
    for diagnostic in direct:
        yield diagnostic
    for child in edit_node.get("children") or []:
        yield from _iter_diagnostics(child)
