"""Shared status and badge formatting for workflow manage trees."""

import re
from typing import Any, Dict, Optional


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
