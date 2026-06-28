"""Shared status and badge formatting for workflow manage trees."""

import json
import re
from typing import Any, Dict, Optional

from rich.markup import escape


STATUS_PRIORITY = {
    "ok": 0,
    "changed": 1,
    "warning": 2,
    "gated": 3,
    "required": 4,
    "error": 5,
    "blocked": 6,
}

STATUS_STYLE = {
    "ok": "",
    "changed": "",
    "warning": "yellow",
    "gated": "magenta",
    "required": "yellow",
    "error": "red",
    "blocked": "bold red",
}

STATUS_BADGE = {
    "ok": "OK",
    "changed": "CHG",
    "warning": "WARN",
    "gated": "GATED",
    "required": "REQ",
    "error": "ERR",
    "blocked": "BLOCK",
}

STATUS_COUNT_KEY = {
    "changed": "changed",
    "warning": "warnings",
    "gated": "gated",
    "required": "required",
    "error": "errors",
    "blocked": "blocked",
}

BADGE_PREFIX_RE = re.compile(r"^\[[^\]]+\]\s*")


def strip_status_badge(label: str) -> str:
    return BADGE_PREFIX_RE.sub("", label)


def payload_status(payload: Dict[str, Any], fallback: Optional[Dict[str, Any]] = None) -> tuple[str, Dict[str, Any]]:
    counts = payload.get("statusCounts") or {}
    for status in ("blocked", "error", "required", "gated", "warning", "changed"):
        count_key = STATUS_COUNT_KEY[status]
        if counts.get(count_key):
            return status, counts
    status = payload.get("status")
    if status:
        return status, counts
    if fallback is not None:
        return payload_status(fallback)
    return "ok", counts


def format_status_badge(status: str, counts: Dict[str, Any]) -> str:
    name = STATUS_BADGE.get(status, status.upper())
    count_key = STATUS_COUNT_KEY.get(status)
    count = counts.get(count_key) if count_key else None
    if status == "ok":
        return ""
    if count is None:
        count = 1
    if status == "changed":
        return "(changed)"
    return f"[{name} {count}]"


def format_change_flag(summary: Dict[str, int]) -> str:
    if not summary.get("count"):
        return ""
    if summary.get("pending_submit"):
        return " [green](to submit)[/green]"
    return " [grey50](pending)[/grey50]"


def diagnostic_style(severity: str) -> str:
    if severity in ("error", "blocked"):
        return "red"
    if severity == "required":
        return "yellow"
    if severity == "gated":
        return "magenta"
    return "yellow"


def adoption_style(status: str) -> str:
    if status == "error":
        return "red"
    if status in ("partial", "outdated", "missing"):
        return "yellow"
    if status == "pending":
        return "grey50"
    if status == "deployed":
        return "green"
    return "dim"


def format_state_value(
    state: Dict[str, Any],
    *,
    missing_value: Optional[str] = None,
    absent_value: str = "<absent>",
    none_value: str = "null",
) -> Optional[str]:
    if state.get("present") is False:
        return absent_value
    if "value" not in state:
        return missing_value
    return format_tree_value(state.get("value"), none_value=none_value)


def same_value_state(left: Dict[str, Any], right: Dict[str, Any]) -> bool:
    if bool(left.get("present")) != bool(right.get("present")):
        return False
    if not left.get("present"):
        return True
    return json.dumps(left.get("value"), sort_keys=True) == json.dumps(right.get("value"), sort_keys=True)


def format_tree_value(value: Any, *, none_value: str = "null") -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, list):
        return "[" + ", ".join(str(item) for item in value) + "]"
    if isinstance(value, dict):
        return json.dumps(value, sort_keys=True)
    if value is None:
        return none_value
    return str(value)


def format_phase_value_groups(
    modes: tuple[str, ...],
    states: Dict[str, Dict[str, Any]],
    labels: Dict[str, str],
    *,
    missing_value: Optional[str] = None,
    none_value: str = "null",
) -> Optional[str]:
    values = []
    for mode in modes:
        value = format_state_value(
            states.get(mode) or {},
            missing_value=missing_value,
            none_value=none_value,
        )
        if value is not None:
            values.append((labels.get(mode, mode), value))
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


def format_phase_value_segment(
    mode: str,
    state: Dict[str, Any],
    labels: Dict[str, str],
    styles: Optional[Dict[str, str]] = None,
    *,
    rich_markup: bool = False,
    missing_value: Optional[str] = "<absent>",
    none_value: str = "null",
) -> str:
    value = format_state_value(state, missing_value=missing_value, none_value=none_value)
    segment = f"{labels.get(mode, mode)}={value}"
    if not rich_markup:
        return segment
    escaped = escape(segment)
    style = (styles or {}).get(mode)
    return f"[{style}]{escaped}[/{style}]" if style else escaped
