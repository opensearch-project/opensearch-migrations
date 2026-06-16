"""Rendering helpers for schema-driven workflow config edit state."""

import re
from typing import Any, Dict, Iterable, Optional

from rich.text import Text
from textual.widgets import Static, Tree
from textual.widgets._tree import TreeNode


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
_STATUS_PRIORITY = {
    "ok": 0,
    "changed": 1,
    "warning": 2,
    "gated": 3,
    "required": 4,
    "error": 5,
    "blocked": 6,
}
_STATUS_STYLE = {
    "ok": "green",
    "changed": "cyan",
    "warning": "yellow",
    "gated": "magenta",
    "required": "yellow",
    "error": "red",
    "blocked": "bold red",
}
_STATUS_BADGE = {
    "ok": "OK",
    "changed": "CHG",
    "warning": "WARN",
    "gated": "GATED",
    "required": "REQ",
    "error": "ERR",
    "blocked": "BLOCK",
}
_STATUS_COUNT_KEY = {
    "changed": "changed",
    "warning": "warnings",
    "gated": "gated",
    "required": "required",
    "error": "errors",
    "blocked": "blocked",
}
_BADGE_PREFIX_RE = re.compile(r"^\[[^\]]+\]\s*")


def render_edit_state(
    tree: Tree,
    edit_state: Dict[str, Any],
    value_mode: str = EDIT_MODE_ALL,
    status_mode: str = EDIT_MODE_ALL,
) -> None:
    """Render a generic TS-provided EditStateV1 into the manage tree."""
    tree.clear()
    tree.root.set_label(Text("Workflow Config Edit"))
    tree.root.data = {"id": "config-edit-root", "type": EDIT_NODE_TYPE}
    for node in edit_state.get("nodes", []):
        _add_edit_node(tree.root, node, value_mode, status_mode)
    tree.root.expand_all()


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
) -> TreeNode:
    node = parent.add(
        _node_label(edit_node, value_mode, status_mode),
        data={
            "id": edit_node.get("id"),
            "type": EDIT_NODE_TYPE,
            "edit_node": edit_node,
        },
    )
    for child in edit_node.get("children") or []:
        _add_edit_node(node, child, value_mode, status_mode)
    return node


def _node_label(edit_node: Dict[str, Any], value_mode: str, status_mode: str) -> Text:
    status, counts = _effective_status(edit_node, status_mode)
    body = _label_body(edit_node, value_mode)
    badge = _badge(status, counts)
    label = f"{body} {badge}" if badge else body
    return Text(label, style=_STATUS_STYLE.get(status, ""))


def _label_body(edit_node: Dict[str, Any], value_mode: str) -> str:
    label = _strip_badge(str(edit_node.get("label", "")))
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
        if "value" not in payload:
            return None
        return _format_value(payload.get("value"))

    values = []
    for mode in _STATE_MODES:
        payload = states.get(mode) or {}
        if "value" in payload:
            values.append((_STATE_LABELS[mode], _format_value(payload.get("value"))))
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


def _format_value(value: Any) -> str:
    if value is None:
        return "<unset>"
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def _strip_badge(label: str) -> str:
    return _BADGE_PREFIX_RE.sub("", label)


def _badge(status: str, counts: Dict[str, Any]) -> str:
    name = _STATUS_BADGE.get(status, status.upper())
    count_key = _STATUS_COUNT_KEY.get(status)
    count = counts.get(count_key) if count_key else None
    if status == "ok":
        return ""
    if count is None:
        count = 1
    return f"[{name} {count}]"


def _effective_status(edit_node: Dict[str, Any], status_mode: str) -> tuple[str, Dict[str, Any]]:
    states = edit_node.get("states") or {}
    if status_mode != EDIT_MODE_ALL:
        payload = states.get(status_mode)
        if payload:
            return _payload_status(payload, edit_node)
        return _payload_status(edit_node)

    candidates = [_payload_status(edit_node)]
    for mode in _STATE_MODES:
        payload = states.get(mode)
        if payload:
            candidates.append(_payload_status(payload, edit_node))
    return max(candidates, key=lambda item: _STATUS_PRIORITY.get(item[0], 0))


def _payload_status(payload: Dict[str, Any], fallback: Optional[Dict[str, Any]] = None) -> tuple[str, Dict[str, Any]]:
    counts = payload.get("statusCounts") or {}
    for status in ("blocked", "error", "required", "gated", "warning", "changed"):
        count_key = _STATUS_COUNT_KEY[status]
        if counts.get(count_key):
            return status, counts
    status = payload.get("status")
    if status:
        return status, counts
    if fallback is not None:
        return _payload_status(fallback)
    return "ok", counts


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
