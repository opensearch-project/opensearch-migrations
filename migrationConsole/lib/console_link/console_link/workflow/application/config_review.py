"""Shared configuration review projection for draft and saved documents."""

from dataclasses import dataclass
from typing import Any, Iterable, Mapping, Optional


@dataclass(frozen=True)
class ConfigReviewChange:
    resource_id: Optional[str]
    resource_label: Optional[str]
    path: str
    label: str
    kind: str


def validation_messages(
    validation: Mapping[str, Any],
) -> tuple[str, ...]:
    messages = [
        str(item.get("message"))
        for item in validation.get("diagnostics") or []
        if isinstance(item, Mapping) and item.get("message")
    ]
    messages.extend(
        str(message)
        for message in validation.get("errors") or []
        if message
    )
    return tuple(dict.fromkeys(messages))


def review_changes(
    edit_state: Mapping[str, Any],
    snapshot: Optional[Any],
) -> tuple[ConfigReviewChange, ...]:
    candidates = (
        *_snapshot_review_changes(snapshot),
        *_edit_review_changes(edit_state.get("nodes") or []),
    )
    return _unique_review_changes(candidates)


def _snapshot_review_changes(
    snapshot: Optional[Any],
) -> tuple[ConfigReviewChange, ...]:
    changes: list[ConfigReviewChange] = []
    nodes = getattr(snapshot, "nodes", {}).values() if snapshot else ()
    for node in nodes:
        if getattr(node, "kind", None) == "resource":
            changes.extend(_resource_review_changes(node))
    return tuple(changes)


def _resource_review_changes(node: Any) -> tuple[ConfigReviewChange, ...]:
    resource_id = str(getattr(node, "id", ""))
    resource_label = str(getattr(node, "label", ""))
    changes = [
        ConfigReviewChange(
            resource_id=resource_id,
            resource_label=resource_label,
            path=str(getattr(comparison, "path", "")),
            label=str(
                getattr(
                    comparison,
                    "label",
                    getattr(comparison, "path", ""),
                )
            ),
            kind="field",
        )
        for comparison in getattr(node, "comparisons", ())
        if getattr(comparison, "pending_changed", False)
    ]
    summary = str(getattr(node, "value_summary", "") or "")
    if "pending submission" in summary.lower():
        changes.append(ConfigReviewChange(
            resource_id=resource_id,
            resource_label=resource_label,
            path="$presence",
            label=summary,
            kind="resource",
        ))
    return tuple(changes)


def _edit_review_changes(
    nodes: Iterable[Mapping[str, Any]],
) -> tuple[ConfigReviewChange, ...]:
    changes: list[ConfigReviewChange] = []
    for node in nodes:
        children = node.get("children") or []
        if _is_changed_value(node, children):
            path = ".".join(str(part) for part in node.get("path") or [])
            if path:
                changes.append(ConfigReviewChange(
                    resource_id=None,
                    resource_label=None,
                    path=path,
                    label=str(node.get("label") or path),
                    kind="field",
                ))
        changes.extend(_edit_review_changes(children))
    return tuple(changes)


def _is_changed_value(
    node: Mapping[str, Any],
    children: Iterable[Mapping[str, Any]],
) -> bool:
    return (
        node.get("status") == "changed"
        and (
            not children
            or node.get("valueKind") in {"scalar", "boolean", "union"}
        )
    )


def _unique_review_changes(
    candidates: Iterable[ConfigReviewChange],
) -> tuple[ConfigReviewChange, ...]:
    changes: list[ConfigReviewChange] = []
    seen: set[tuple[Optional[str], str]] = set()
    for change in candidates:
        key = (change.resource_id, change.path)
        if key in seen:
            continue
        seen.add(key)
        changes.append(change)
    return tuple(changes)
