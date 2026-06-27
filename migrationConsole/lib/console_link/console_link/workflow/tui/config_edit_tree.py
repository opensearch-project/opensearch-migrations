"""Rendering helpers for schema-driven workflow config edit state."""

from typing import Any, Dict, Iterable, Optional

from rich.text import Text
from textual.widgets import Static, Tree
from textual.widgets._tree import TreeNode

from console_link.workflow.manage_tree_status import (
    STATUS_PRIORITY,
    STATUS_STYLE,
    format_status_badge,
    payload_status,
    strip_status_badge,
)


EDIT_NODE_TYPE = "config-edit"
EDIT_MODE_ALL = "all"
EDIT_MODE_DEPLOYED = "deployed"
EDIT_MODE_CURRENT_WORKFLOW = "currentWorkflow"
EDIT_MODE_PENDING_SUBMIT = "pendingSubmit"

EDIT_MODES = (
    EDIT_MODE_ALL,
    EDIT_MODE_DEPLOYED,
    EDIT_MODE_CURRENT_WORKFLOW,
    EDIT_MODE_PENDING_SUBMIT,
)
EDIT_MODE_LABELS = {
    EDIT_MODE_ALL: "All",
    EDIT_MODE_DEPLOYED: "Deployed",
    EDIT_MODE_CURRENT_WORKFLOW: "After Workflow",
    EDIT_MODE_PENDING_SUBMIT: "After Submit",
}
_STATE_LABELS = {
    EDIT_MODE_DEPLOYED: "deployed",
    EDIT_MODE_CURRENT_WORKFLOW: "workflow",
    EDIT_MODE_PENDING_SUBMIT: "pending",
}
_STATE_MODES = (
    EDIT_MODE_DEPLOYED,
    EDIT_MODE_CURRENT_WORKFLOW,
    EDIT_MODE_PENDING_SUBMIT,
)
def render_edit_state(
    tree: Tree,
    edit_state: Dict[str, Any],
    value_mode: str = EDIT_MODE_ALL,
    status_mode: str = EDIT_MODE_ALL,
    show_optional: bool = True,
    show_expert: bool = False,
    expansion_state: Optional[Dict[str, bool]] = None,
) -> None:
    """Render a generic TS-provided EditStateV1 into the manage tree."""
    tree.clear()
    tree.root.set_label(Text("Workflow Config Edit"))
    tree.show_root = False
    tree.root.data = {"id": "config-edit-root", "type": EDIT_NODE_TYPE}
    for node in edit_state.get("nodes", []):
        _add_edit_node(tree.root, node, value_mode, status_mode, show_optional, show_expert, expansion_state)
    tree.root.expand()


def selected_edit_node(tree: Tree) -> Optional[Dict[str, Any]]:
    node = tree.cursor_node
    data = node.data if node else None
    if data and data.get("type") == EDIT_NODE_TYPE:
        return data.get("edit_node") or data
    return None


def update_help_panel(
    panel: Static,
    edit_node: Optional[Dict[str, Any]],
    status_mode: str = EDIT_MODE_ALL,
) -> None:
    """Update the bottom help/status panel for the selected edit node."""
    if not edit_node:
        panel.update("")
        return

    path = " > ".join(str(part) for part in edit_node.get("path", [])) or "workflow config"
    description = edit_node.get("description") or edit_node.get("descriptionShort") or ""
    status_line = _status_line(edit_node, status_mode)
    lines = [
        f"[bold]Help / Status[/]  [cyan]{path}[/]",
        status_line,
    ]
    if description:
        lines.append(description)
    panel.update("\n".join(lines[:4]))


def _add_edit_node(
    parent: TreeNode,
    edit_node: Dict[str, Any],
    value_mode: str,
    status_mode: str,
    show_optional: bool,
    show_expert: bool,
    expansion_state: Optional[Dict[str, bool]] = None,
) -> Optional[TreeNode]:
    visible_children = [
        child for child in edit_node.get("children") or []
        if _should_render_edit_node(child, status_mode, show_optional, show_expert)
    ]
    if not _should_render_edit_node(edit_node, status_mode, show_optional, show_expert, visible_children):
        return None
    node = parent.add(
        _node_label(edit_node, value_mode, status_mode),
        data={
            "id": edit_node.get("id"),
            "type": EDIT_NODE_TYPE,
            "edit_node": edit_node,
        },
    )
    for child in visible_children:
        _add_edit_node(node, child, value_mode, status_mode, show_optional, show_expert, expansion_state)
    node_id = edit_node.get("id")
    if node_id in (expansion_state or {}):
        should_expand = bool((expansion_state or {}).get(node_id))
    else:
        should_expand = _should_expand_edit_node(edit_node, status_mode, visible_children)
    if should_expand:
        node.expand()
    else:
        node.collapse()
    return node


def _should_expand_edit_node(
    edit_node: Dict[str, Any],
    status_mode: str,
    visible_children: list[Dict[str, Any]],
) -> bool:
    if not visible_children:
        return False
    if edit_node.get("collapsed"):
        return False
    status, counts = _effective_status(edit_node, status_mode)
    if _has_attention_status(status, counts):
        return True
    if _has_only_changed_status(status, counts):
        return False
    if _is_optional_unset_block(edit_node, visible_children):
        return False
    return True


def _has_attention_status(status: str, counts: Dict[str, Any]) -> bool:
    if status in {"warning", "gated", "required", "error", "blocked"}:
        return True
    return any(
        counts.get(key)
        for key in ("warnings", "gated", "required", "errors", "blocked")
    )


def _has_only_changed_status(status: str, counts: Dict[str, Any]) -> bool:
    if status != "changed" and not counts.get("changed"):
        return False
    return not any(
        counts.get(key)
        for key in ("warnings", "gated", "required", "errors", "blocked")
    )


def _is_optional_unset_block(edit_node: Dict[str, Any], visible_children: list[Dict[str, Any]]) -> bool:
    if not visible_children:
        return False
    if edit_node.get("presence") != "optional":
        return False
    if edit_node.get("valueKind") not in {"object", "array", "record", "union"}:
        return False

    label = strip_status_badge(str(edit_node.get("label", ""))).lower()
    value_present = "value" in edit_node
    value = edit_node.get("value")
    if "<unset>" in label:
        return True
    return value_present and value in (None, "", "unset")


def _should_render_edit_node(
    edit_node: Dict[str, Any],
    status_mode: str,
    show_optional: bool,
    show_expert: bool,
    visible_children: Optional[list[Dict[str, Any]]] = None,
) -> bool:
    if edit_node.get("valueKind") == "command":
        return True
    if len(edit_node.get("path") or []) <= 1:
        return True
    status, counts = _effective_status(edit_node, status_mode)
    if (
        status in {"required", "error", "blocked"}
        or counts.get("required")
        or counts.get("errors")
        or counts.get("blocked")
    ):
        return True
    if visible_children is None:
        visible_children = [
            child for child in edit_node.get("children") or []
            if _should_render_edit_node(child, status_mode, show_optional, show_expert)
        ]
    if visible_children:
        return True
    is_expert = bool(edit_node.get("expert"))
    if is_expert and not show_expert:
        return False
    if edit_node.get("presence") == "optional" and not show_optional and not is_expert:
        return False
    return True


def _node_label(edit_node: Dict[str, Any], value_mode: str, status_mode: str) -> Text:
    status, counts = _effective_status(edit_node, status_mode)
    body = _label_body(edit_node, value_mode)
    badge = format_status_badge(status, counts)
    label = f"{body} {badge}" if badge else body
    return Text(label, style=STATUS_STYLE.get(status, ""))


def _label_body(edit_node: Dict[str, Any], value_mode: str) -> str:
    label = strip_status_badge(str(edit_node.get("label", "")))
    mode_value = _formatted_mode_value(edit_node, value_mode)
    if mode_value is None:
        return label
    prefix = label.split(":", 1)[0] if ":" in label else label
    return f"{prefix}: {mode_value}" if prefix else mode_value


def _formatted_mode_value(edit_node: Dict[str, Any], value_mode: str) -> Optional[str]:
    states = edit_node.get("states") or {}
    if not states:
        return None

    if value_mode != EDIT_MODE_ALL:
        payload = states.get(value_mode) or {}
        value = _payload_value(payload)
        if value is None:
            return None
        return value

    values = []
    for mode in _STATE_MODES:
        payload = states.get(mode) or {}
        value = _payload_value(payload)
        if value is not None:
            values.append((_STATE_LABELS[mode], value))
    if not values:
        return None
    if len({value for _, value in values}) == 1:
        return values[0][1]

    groups: list[tuple[list[str], str]] = []
    for label, value in values:
        if groups and groups[-1][1] == value:
            groups[-1][0].append(label)
        else:
            groups.append(([label], value))
    return " | ".join(f"{'/'.join(labels)}={value}" for labels, value in groups)


def _payload_value(payload: Dict[str, Any]) -> Optional[str]:
    if payload.get("present") is False:
        return "<absent>"
    if "value" not in payload:
        return None
    return _format_value(payload.get("value"))


def _format_value(value: Any) -> str:
    if value is None:
        return "<unset>"
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _effective_status(edit_node: Dict[str, Any], status_mode: str) -> tuple[str, Dict[str, Any]]:
    states = edit_node.get("states") or {}
    if status_mode != EDIT_MODE_ALL:
        payload = states.get(status_mode)
        if payload:
            return payload_status(payload, edit_node)
        return payload_status(edit_node)

    candidates = [payload_status(edit_node)]
    for mode in _STATE_MODES:
        payload = states.get(mode)
        if payload:
            candidates.append(payload_status(payload, edit_node))
    return max(candidates, key=lambda item: STATUS_PRIORITY.get(item[0], 0))


def _status_line(edit_node: Dict[str, Any], status_mode: str) -> str:
    diagnostics = list(_iter_diagnostics(edit_node, status_mode))
    if diagnostics:
        first = diagnostics[0]
        severity = first.get("severity", "status").upper()
        return f"[yellow]{severity}:[/] {first.get('message', '')}"

    status, counts = _effective_status(edit_node, status_mode)
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
    if status != "ok":
        return f"[yellow]Status:[/] {status}"
    return "[green]Status:[/] complete"


def _iter_diagnostics(edit_node: Dict[str, Any], status_mode: str) -> Iterable[Dict[str, Any]]:
    if status_mode != EDIT_MODE_ALL:
        state_diagnostics = (edit_node.get("states") or {}).get(status_mode, {}).get("diagnostics") or []
        for diagnostic in state_diagnostics:
            yield diagnostic
        return

    direct = edit_node.get("diagnostics") or []
    for diagnostic in direct:
        yield diagnostic
    for child in edit_node.get("children") or []:
        yield from _iter_diagnostics(child, status_mode)
