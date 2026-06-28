"""Rendering helpers for schema-driven workflow config edit state."""

from typing import Any, Dict, Iterable, Optional

from rich.text import Text
from textual.widgets import Static, Tree

from console_link.workflow.manage_tree_schema import (
    EDIT_ID_BY_TREE_ID,
    RESOURCE_SECTIONS,
    WORKFLOW_CONFIGURATION_SECTION,
)
from console_link.workflow.manage_tree_status import (
    STATUS_PRIORITY,
    STATUS_STYLE,
    format_status_badge,
    payload_status,
    strip_status_badge,
    format_phase_value_groups,
    format_state_value,
)
from console_link.workflow.resource_tree import ResourceGroup, ResourceNode, ResourceSection
from console_link.workflow.tui.resource_tree_state_manager import ResourceTreeStateManager


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
    """Render a TS-provided EditStateV1 through the shared manage resource tree renderer."""
    sections = edit_state_resource_sections(
        edit_state,
        value_mode,
        status_mode,
        show_optional,
        show_expert,
        expansion_state,
    )
    ResourceTreeStateManager(tree_widget=tree).rebuild(
        sections,
        root_label="Workflow Config Edit",
        expand_all=False,
    )


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


def edit_state_resource_sections(
    edit_state: Dict[str, Any],
    value_mode: str,
    status_mode: str,
    show_optional: bool,
    show_expert: bool,
    expansion_state: Optional[Dict[str, bool]] = None,
) -> list[ResourceSection]:
    """Convert edit nodes to the same section/group/resource model used by status view."""
    roots = edit_state.get("nodes") or []
    node_by_id = _index_edit_nodes(roots)
    used_top_level_ids = set()
    sections: list[ResourceSection] = []

    for section_name, group_defs in RESOURCE_SECTIONS:
        section_edit_node = node_by_id.get(EDIT_ID_BY_TREE_ID.get(f"section:{section_name}", ""))
        groups: list[ResourceGroup] = []
        for plurals, group_name in group_defs:
            group_edit_node = node_by_id.get(EDIT_ID_BY_TREE_ID.get(f"group:{group_name}", ""))
            if group_edit_node is None:
                continue
            used_top_level_ids.add(str(group_edit_node.get("id")))
            visible_children = [
                child for child in group_edit_node.get("children") or []
                if _should_render_edit_node(child, status_mode, show_optional, show_expert)
            ]
            if not visible_children and not _should_render_edit_node(
                group_edit_node,
                status_mode,
                show_optional,
                show_expert,
                visible_children,
            ):
                continue
            groups.append(ResourceGroup(
                plural=plurals[0],
                display_name=group_name,
                resources=[
                    _edit_node_to_resource_node(
                        child,
                        index,
                        value_mode,
                        status_mode,
                        show_optional,
                        show_expert,
                        expansion_state,
                    )
                    for index, child in enumerate(visible_children)
                ],
                tree_id=str(group_edit_node.get("id") or f"group:{group_name}"),
                tree_label=_node_label(group_edit_node, value_mode, status_mode),
                tree_data=_edit_node_tree_data(group_edit_node),
                tree_default_expanded=_edit_node_should_expand(
                    group_edit_node,
                    status_mode,
                    visible_children,
                    expansion_state,
                ),
            ))
        if groups:
            if section_edit_node is not None:
                used_top_level_ids.add(str(section_edit_node.get("id")))
            sections.append(ResourceSection(
                name=section_name,
                groups=groups,
                tree_id=str(section_edit_node.get("id")) if section_edit_node else None,
                tree_label=_node_label(section_edit_node, value_mode, status_mode) if section_edit_node else None,
                tree_data=_edit_node_tree_data(section_edit_node) if section_edit_node else None,
                tree_default_expanded=_edit_node_should_expand(
                    section_edit_node,
                    status_mode,
                    section_edit_node.get("children") or [],
                    expansion_state,
                ) if section_edit_node else None,
            ))

    fallback_roots = [
        node for node in roots
        if str(node.get("id")) not in used_top_level_ids
        and not _root_consumed_by_section_groups(node, used_top_level_ids, status_mode, show_optional, show_expert)
        and _should_render_edit_node(node, status_mode, show_optional, show_expert)
    ]
    if fallback_roots:
        sections.append(ResourceSection(
            name=WORKFLOW_CONFIGURATION_SECTION,
            groups=[
                ResourceGroup(
                    plural="configedit",
                    display_name="Configuration",
                    resources=[
                        _edit_node_to_resource_node(
                            node,
                            index,
                            value_mode,
                            status_mode,
                            show_optional,
                            show_expert,
                            expansion_state,
                        )
                        for index, node in enumerate(fallback_roots)
                    ],
                )
            ],
        ))

    return sections


def _root_consumed_by_section_groups(
    root: Dict[str, Any],
    used_ids: set[str],
    status_mode: str,
    show_optional: bool,
    show_expert: bool,
) -> bool:
    children = [
        child for child in root.get("children") or []
        if _should_render_edit_node(child, status_mode, show_optional, show_expert)
    ]
    if not children:
        return False
    return all(str(child.get("id")) in used_ids for child in children)


def _index_edit_nodes(nodes: list[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    result: Dict[str, Dict[str, Any]] = {}
    stack = list(nodes)
    while stack:
        node = stack.pop()
        node_id = node.get("id")
        if node_id:
            result[str(node_id)] = node
        stack.extend(node.get("children") or [])
    return result


def _edit_node_tree_data(edit_node: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "type": EDIT_NODE_TYPE,
        "edit_node": edit_node,
    }


def _edit_node_to_resource_node(
    edit_node: Dict[str, Any],
    sort_index: int,
    value_mode: str,
    status_mode: str,
    show_optional: bool,
    show_expert: bool,
    expansion_state: Optional[Dict[str, bool]] = None,
) -> ResourceNode:
    visible_children = [
        child for child in edit_node.get("children") or []
        if _should_render_edit_node(child, status_mode, show_optional, show_expert)
    ]
    should_expand = _edit_node_should_expand(
        edit_node,
        status_mode,
        visible_children,
        expansion_state,
    )

    return ResourceNode(
        name=str(edit_node.get("id") or edit_node.get("label") or f"config-{sort_index}"),
        plural="configedit",
        phase="Config Edit",
        depends_on=[],
        spec={},
        status={},
        children=[
            _edit_node_to_resource_node(
                child,
                index,
                value_mode,
                status_mode,
                show_optional,
                show_expert,
                expansion_state,
            )
            for index, child in enumerate(visible_children)
        ],
        tree_id=str(edit_node.get("id") or f"edit:anonymous.{sort_index}"),
        tree_label=_node_label(edit_node, value_mode, status_mode),
        tree_data=_edit_node_tree_data(edit_node),
        tree_default_expanded=should_expand,
        tree_change_summary=_edit_change_summary(edit_node, status_mode),
        tree_sort_index=sort_index,
    )


def _edit_node_should_expand(
    edit_node: Dict[str, Any],
    status_mode: str,
    visible_children: list[Dict[str, Any]],
    expansion_state: Optional[Dict[str, bool]] = None,
) -> bool:
    node_id = str(edit_node.get("id") or "")
    if node_id in (expansion_state or {}):
        return bool((expansion_state or {}).get(node_id))
    return _should_expand_edit_node(edit_node, status_mode, visible_children)


def _edit_change_summary(edit_node: Dict[str, Any], status_mode: str) -> Dict[str, int]:
    status, counts = _effective_status(edit_node, status_mode)
    changed = int(counts.get("changed") or 0)
    notable = (
        changed
        or counts.get("required")
        or counts.get("errors")
        or counts.get("warnings")
        or counts.get("gated")
        or counts.get("blocked")
        or status not in {"ok", None}
    )
    return {
        "count": int(changed or 1) if notable else 0,
        "pending_submit": int(changed or 1) if changed or status == "changed" else 0,
    }


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
        return format_state_value(
            states.get(value_mode) or {},
            missing_value=None,
            none_value="<unset>",
        )
    return format_phase_value_groups(
        _STATE_MODES,
        states,
        _STATE_LABELS,
        missing_value=None,
        none_value="<unset>",
    )


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
        summary.append("changed")
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
