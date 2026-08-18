"""Python rendering types for config-processor submission preflight reports."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping, Optional, Tuple


BLOCKING_CLASSIFICATIONS = {"recreate-required", "invalid"}


@dataclass(frozen=True)
class AdmissionPreflightIssue:
    kind: str
    name: str
    plural: Optional[str]
    classification: str
    message: str
    source: str

    @property
    def blocking(self) -> bool:
        return self.classification in BLOCKING_CLASSIFICATIONS

    @property
    def resource_id(self) -> Optional[str]:
        if not self.plural:
            return None
        return f"resource:{self.plural}:{self.name}"

    @property
    def reset_target_id(self) -> Optional[str]:
        if self.classification != "recreate-required" or not self.plural:
            return None
        return f"reset:{self.plural}:{self.name}"

    @classmethod
    def from_payload(
        cls,
        payload: Mapping[str, Any],
    ) -> "AdmissionPreflightIssue":
        return cls(
            kind=str(payload.get("kind") or "Unknown"),
            name=str(payload.get("name") or "unknown"),
            plural=(
                str(payload["plural"])
                if payload.get("plural")
                else None
            ),
            classification=str(
                payload.get("classification") or "warning"
            ),
            message=str(payload.get("message") or ""),
            source=str(payload.get("source") or "preflight"),
        )


@dataclass(frozen=True)
class AdmissionPreflightReport:
    checked_resources: int
    issues: Tuple[AdmissionPreflightIssue, ...]

    @property
    def allowed(self) -> bool:
        return not any(issue.blocking for issue in self.issues)

    @property
    def blocking_issues(self) -> Tuple[AdmissionPreflightIssue, ...]:
        return tuple(issue for issue in self.issues if issue.blocking)

    @property
    def warning_issues(self) -> Tuple[AdmissionPreflightIssue, ...]:
        return tuple(issue for issue in self.issues if not issue.blocking)

    @classmethod
    def from_payload(
        cls,
        payload: Mapping[str, Any],
    ) -> "AdmissionPreflightReport":
        return cls(
            checked_resources=int(
                payload.get(
                    "checkedResources",
                    payload.get("checked_resources", 0),
                )
            ),
            issues=tuple(
                AdmissionPreflightIssue.from_payload(item)
                for item in payload.get("issues") or ()
            ),
        )
